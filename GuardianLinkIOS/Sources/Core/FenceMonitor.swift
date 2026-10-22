import Foundation
import CoreLocation
import UserNotifications
import AudioToolbox
import Combine

/// 围栏监护服务（老人端核心）：系统围栏 + 持续定位 → 去抖 → 状态机 → 告警/通知/推送。
/// iOS 平台说明：应用被用户强杀后系统围栏仍可唤醒本 App（CLLocationManager 区域监控由系统托管），
/// 但定位权限必须为"始终允许"；后台持续定位受 iOS 限制，与 Android 前台服务行为不同。
@MainActor
final class FenceMonitor: ObservableObject {

    static let shared = FenceMonitor()

    @Published private(set) var state: GuardianState = .homeSafe
    @Published private(set) var lastFix: Loc?
    @Published private(set) var monitorRunning = false
    @Published private(set) var fenceSummary = "未设置家庭安全区域"
    @Published private(set) var lastFeishuOkAt: Int64 = 0
    @Published private(set) var lastError: String?
    /// 前台弹出的告警（本机提醒；后台走系统通知）
    @Published var presentAlertTitle: String?

    private var machine = GuardianStateMachine()
    private var debouncer = FenceDebouncer()
    private let controller = LocationController()
    private var dispatcher: AlertDispatcher?
    private var staleTask: Task<Void, Never>?
    private var resumeNotified = false

    private let settingsStore = AppSettingsStore.shared
    private var settings: AppSettings { settingsStore.settings }

    private lazy var eventStore = Stores.elder
    private let pairing = PairingStore.shared

    private init() {
        if let snap = StateStore.load() {
            machine.restore(state: snap.state, episodeId: snap.episodeId, beforeStale: snap.beforeStale)
            state = snap.state
        }
        controller.onFix = { [weak self] fix in
            Task { @MainActor in self?.handleFix(fix) }
        }
        controller.onRegionExit = { [weak self] in
            Task { @MainActor in _ = self?.machine.onRawOutside(now: Self.nowMs()) }
        }
        controller.onRegionEnter = { [weak self] in
            Task { @MainActor in _ = self?.machine.onRawInside(now: Self.nowMs()) }
        }
        controller.onError = { [weak self] msg in
            Task { @MainActor in self?.lastError = msg }
        }
    }

    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    // MARK: - 启停

    func start() {
        let fence = loadFence()
        guard fence.configured else {
            lastError = "请先在「围栏」页设置家庭安全区域"
            return
        }
        guard controller.hasWhenInUse else {
            controller.requestWhenInUse()
            lastError = "需要定位权限（建议选择「始终允许」以保证后台监测）"
            return
        }
        if !controller.hasAlways {
            // 系统会择机弹出"始终允许"升级提示
            controller.requestAlways()
        }
        UserDefaults.standard.set(true, forKey: "guardian_armed")

        let cfg = AlertConfig(
            webhook: settings.feishuWebhook,
            secret: settings.feishuSecret,
            deviceLabel: settings.deviceLabel,
            deviceId: pairing.snapshot.deviceId,
            fenceRadiusM: fence.radiusM,
            awayReportIntervalMs: Int64(settings.awayReportMinutes) * 60_000
        )
        let d = AlertDispatcher(store: eventStore, config: cfg)
        d.onSendFinished = { [weak self] _, result in
            Task { @MainActor in
                if case .success = result { self?.lastFeishuOkAt = Self.nowMs() }
            }
        }
        dispatcher = d

        // 恢复上次状态：进行中的离家不重复告警，首帧位置补一条更新
        resumeNotified = false
        if let snap = StateStore.load() {
            machine.restore(state: snap.state, episodeId: snap.episodeId, beforeStale: snap.beforeStale)
            state = snap.state
        }
        debouncer = FenceDebouncer()

        controller.startFence(
            center: CLLocationCoordinate2D(latitude: fence.centerLat, longitude: fence.centerLng),
            radiusM: Double(fence.radiusM)
        )
        monitorRunning = true
        fenceSummary = "中心 \(String(format: "%.5f", fence.centerLat)),\(String(format: "%.5f", fence.centerLng)) · 半径 \(fence.radiusM)m · v\(fence.version)"
        requestNotificationAuthorization()
        startStaleWatchdog(minutes: settings.staleAfterMinutes)
        // 启动时补发之前失败的事件
        Task { [d] in _ = await d.retryPending() }
    }

    func stop() {
        controller.stopAll()
        staleTask?.cancel()
        staleTask = nil
        monitorRunning = false
        UserDefaults.standard.set(false, forKey: "guardian_armed")
        AlarmCenter.shared.stopLocalAlert()
    }

    func reloadFence() {
        guard monitorRunning else { return }
        stop()
        start()
    }

    private func loadFence() -> FenceConfig {
        if let data = UserDefaults.standard.data(forKey: "fence_config"),
           let f = try? JSONDecoder().decode(FenceConfig.self, from: data) {
            return f
        }
        return FenceConfig(centerLat: 0, centerLng: 0, radiusM: 0)
    }

    func saveFence(centerLat: Double, centerLng: Double, radiusM: Int) -> String? {
        guard FenceConfig.isValidCenter(lat: centerLat, lng: centerLng) else { return "中心点坐标超出中国范围" }
        var fence = loadFence()
        fence = FenceConfig(
            centerLat: centerLat,
            centerLng: centerLng,
            radiusM: FenceConfig.coerceRadius(radiusM),
            coordSystem: fence.coordSystem,
            updatedAt: Self.nowMs(),
            version: fence.version + 1,
            enabled: true
        )
        if let data = try? JSONEncoder().encode(fence) {
            UserDefaults.standard.set(data, forKey: "fence_config")
        }
        fenceSummary = "中心 \(String(format: "%.5f", fence.centerLat)),\(String(format: "%.5f", fence.centerLng)) · 半径 \(fence.radiusM)m · v\(fence.version)"
        return nil
    }

    func savedFence() -> FenceConfig { loadFence() }

    // MARK: - 定位与围栏信号

    private func handleFix(_ fix: Loc) {
        let now = Self.nowMs()
        lastFix = fix
        let transitions = machine.onFreshFix(now: now)
        if !transitions.isEmpty { publishState() }

        let fence = loadFence()
        guard fence.configured else { return }
        let signals = debouncer.onFix(fix, centerLat: fence.centerLat, centerLng: fence.centerLng, radiusM: Double(fence.radiusM))
        for signal in signals {
            switch signal {
            case .rawOutside:
                _ = machine.onRawOutside(now: now)
            case .rawInside:
                _ = machine.onRawInside(now: now)
            case .confirmedExit:
                for t in machine.onExitConfirmed(now: now) {
                    if case .exitConfirmed(let episodeId, _) = t {
                        let event = dispatcher?.exitConfirmed(episodeId: episodeId, fix: fix, now: now)
                        if let event { LanSyncService.shared.pushEvent(event) }
                        raiseLocalAlert(
                            title: "⚠️ 已离开家庭安全区域",
                            body: "检测到离家，已通知家人（如已配置飞书）"
                        )
                    }
                }
            case .confirmedEnter:
                for t in machine.onReturnConfirmed(now: now) {
                    if case .returnConfirmed(let episodeId, _) = t {
                        let event = dispatcher?.returnConfirmed(episodeId: episodeId, fix: fix, now: now)
                        if let event { LanSyncService.shared.pushEvent(event) }
                        AlarmCenter.shared.stopLocalAlert()
                    }
                }
            }
        }
        // 离家期间位置更新（首次强制发送）
        if let ep = machine.episodeId, machine.state != .homeSafe {
            if let event = dispatcher?.awayLocationUpdate(episodeId: ep, fix: fix, now: now, force: !resumeNotified) {
                LanSyncService.shared.pushEvent(event)
            }
            resumeNotified = true
        }
        persistMachine()
        publishState()
    }

    private func startStaleWatchdog(minutes: Int) {
        staleTask?.cancel()
        staleTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 60 * 1_000_000_000)
                guard let self, self.monitorRunning else { continue }
                let now = Self.nowMs()
                guard self.machine.state != .locationStale else { continue }
                let ref = self.lastFix?.time ?? 0
                if ref > 0, now - ref > Int64(minutes) * 60_000 {
                    let transitions = self.machine.onStale(now: now)
                    if !transitions.isEmpty {
                        if let event = self.dispatcher?.locationStale(episodeId: self.machine.episodeId, now: now) {
                            LanSyncService.shared.pushEvent(event)
                        }
                        self.notify(
                            title: "⚠️ 定位长时间不可用",
                            body: "GuardianLink 无法获取定位，监护可能出现盲区，请检查手机状态"
                        )
                        self.persistMachine()
                        self.publishState()
                    }
                }
            }
        }
    }

    // MARK: - 状态与告警

    private func persistMachine() {
        let snap = machine.snapshot
        StateStore.save(StateStore.Snapshot(state: snap.state, episodeId: snap.episodeId, beforeStale: snap.beforeStale))
    }

    private func publishState() {
        state = machine.state
    }

    /// 本机提醒：通知（后台可见）+ 震动 + 前台弹窗（App 活跃时）
    private func raiseLocalAlert(title: String, body: String) {
        presentAlertTitle = title
        notify(title: title, body: body)
        AlarmCenter.shared.startLocalAlert()
    }

    func notify(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    func requestNotificationAuthorization() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    /// 启动恢复：App 冷启动时调用（恢复状态 + 补发失败事件 + 若曾开启监护则恢复）
    func bootstrap() {
        requestNotificationAuthorization()
        if UserDefaults.standard.bool(forKey: "guardian_armed"), !monitorRunning {
            start()
        }
        let d = dispatcher
        Task { [d] in
            if let d { _ = await d.retryPending() }
        }
    }
}

/// 本机震动提醒（iOS 无法像 Android 一样以最大音量循环播放告警，采用系统通知+震动组合）
@MainActor
final class AlarmCenter {
    static let shared = AlarmCenter()
    private var vibrationTask: Task<Void, Never>?

    func startLocalAlert() {
        stopLocalAlert()
        vibrationTask = Task { @MainActor in
            for _ in 0..<10 {
                if Task.isCancelled { break }
                AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
                try? await Task.sleep(nanoseconds: 800 * 1_000_000)
            }
        }
    }

    func stopLocalAlert() {
        vibrationTask?.cancel()
        vibrationTask = nil
    }
}
