import Foundation

/// 事件分发：先持久化再发送；离家期间位置更新按可配置间隔节流；事件 ID 幂等。
@MainActor
final class AlertDispatcher: ObservableObject {

    private let store: EventStore
    private var config: AlertConfig
    var onSendFinished: ((GuardianEvent, FeishuResult) -> Void)?

    private var lastUpdateAt: [String: Int64] = [:]

    init(store: EventStore, config: AlertConfig) {
        self.store = store
        self.config = config
    }

    func updateConfig(_ newConfig: AlertConfig) {
        config = newConfig
    }

    // MARK: - 事件入口（返回已创建并持久化的事件，便于推送给局域网监护人）

    @discardableResult
    func exitConfirmed(episodeId: String, fix: Loc?, now: Int64) -> GuardianEvent {
        let event = GuardianEvent(
            id: UUID().uuidString,
            type: .exitHome,
            occurredAt: now,
            locatedAt: fix?.time,
            lat: fix?.lat,
            lng: fix?.lng,
            accuracyM: fix?.accuracyM,
            coord: fix?.coord.rawValue,
            sourceDeviceId: config.deviceId,
            awayEpisodeId: episodeId
        )
        dispatch(event)
        return event
    }

    @discardableResult
    func returnConfirmed(episodeId: String, fix: Loc?, now: Int64) -> GuardianEvent {
        let event = GuardianEvent(
            id: UUID().uuidString,
            type: .returnHome,
            occurredAt: now,
            locatedAt: fix?.time,
            lat: fix?.lat,
            lng: fix?.lng,
            accuracyM: fix?.accuracyM,
            coord: fix?.coord.rawValue,
            sourceDeviceId: config.deviceId,
            awayEpisodeId: episodeId
        )
        dispatch(event)
        lastUpdateAt.removeValue(forKey: episodeId)
        return event
    }

    @discardableResult
    func locationStale(episodeId: String?, now: Int64) -> GuardianEvent {
        let event = GuardianEvent(
            id: UUID().uuidString,
            type: .locationStale,
            occurredAt: now,
            sourceDeviceId: config.deviceId,
            awayEpisodeId: episodeId,
            note: "定位长时间不可用，监护可能出现盲区"
        )
        dispatch(event)
        return event
    }

    @discardableResult
    func awayLocationUpdate(episodeId: String, fix: Loc, now: Int64, force: Bool = false) -> GuardianEvent? {
        let last = lastUpdateAt[episodeId] ?? 0
        if !force, now - last < config.awayReportIntervalMs { return nil }
        lastUpdateAt[episodeId] = now
        let event = GuardianEvent(
            id: UUID().uuidString,
            type: .locationUpdate,
            occurredAt: now,
            locatedAt: fix.time,
            lat: fix.lat,
            lng: fix.lng,
            accuracyM: fix.accuracyM,
            coord: fix.coord.rawValue,
            sourceDeviceId: config.deviceId,
            awayEpisodeId: episodeId
        )
        dispatch(event)
        return event
    }

    // MARK: - 发送

    func dispatch(_ event: GuardianEvent) {
        var pendingEvent = event
        pendingEvent.sendStatus = .pending
        Task {
            // 先持久化再发送
            await store.upsert(pendingEvent)
            let result = await FeishuClient.send(
                webhook: config.webhook, secret: config.secret,
                text: Self.buildMessage(pendingEvent, config: config)
            )
            switch result {
            case .success:
                let updated = await store.markSent(id: pendingEvent.id, at: Self.nowMs())
                onSendFinished?(updated ?? pendingEvent, result)
            case .retriable:
                await store.markFailed(id: pendingEvent.id, retryable: true, error: reason(result))
                onSendFinished?(pendingEvent, result)
            case .permanent:
                await store.markFailed(id: pendingEvent.id, retryable: false, error: reason(result))
                onSendFinished?(pendingEvent, result)
            }
        }
    }

    /// 立即重发所有待发送/可重试事件，返回仍未成功的数量
    @discardableResult
    func retryPending() async -> Int {
        for event in await store.pending() {
            let result = await FeishuClient.send(
                webhook: config.webhook, secret: config.secret,
                text: Self.buildMessage(event, config: config)
            )
            switch result {
            case .success:
                await store.markSent(id: event.id, at: Self.nowMs())
            case .retriable:
                await store.markFailed(id: event.id, retryable: true, error: reason(result))
            case .permanent:
                await store.markFailed(id: event.id, retryable: false, error: reason(result))
            }
        }
        return await store.pending().count
    }

    private func reason(_ result: FeishuResult) -> String {
        switch result {
        case .success: return ""
        case .retriable(let r): return r
        case .permanent(let r): return r
        }
    }

    // MARK: - 消息构造（与 Android 端同格式）

    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    static func buildMessage(_ e: GuardianEvent, config: AlertConfig) -> String {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd HH:mm:ss"
        fmt.timeZone = TimeZone.current
        var lines: [String] = []
        let title: String
        switch e.type {
        case .exitHome: title = "【GuardianLink 告警】⚠️ 老人已离开家庭安全区域"
        case .returnHome: title = "【GuardianLink】✅ 老人已回到家庭安全区域"
        case .locationUpdate: title = "【GuardianLink】📍 离家期间位置更新"
        case .locationStale: title = "【GuardianLink 提醒】⚠️ 定位长时间不可用"
        case .deviceAlert: title = "【GuardianLink 提醒】设备异常"
        }
        lines.append(title)
        lines.append("对象: \(config.deviceLabel)（\(e.sourceDeviceId)）")
        lines.append("事件时间: \(fmt.string(from: Date(timeIntervalSince1970: Double(e.occurredAt) / 1000)))")
        if let locatedAt = e.locatedAt {
            var locatedLine = "定位时间: \(fmt.string(from: Date(timeIntervalSince1970: Double(locatedAt) / 1000)))"
            let staleMs = e.occurredAt - locatedAt
            if staleMs > 10 * 60_000 {
                locatedLine += "（⚠ 已过期 \(staleMs / 60_000) 分钟）"
            }
            lines.append(locatedLine)
        }
        if let lat = e.lat, let lng = e.lng {
            var gLat = lat
            var gLng = lng
            if e.coord == CoordSystem.wgs84.rawValue {
                let converted = GeoConverter.wgs84ToGcj02(lat: lat, lng: lng)
                gLat = converted.lat
                gLng = converted.lng
            }
            var accuracyPart = ""
            if let acc = e.accuracyM, acc > 0 { accuracyPart = "（精度±\(Int(acc))m）" }
            lines.append("坐标: \(String(format: "%.6f", gLat)), \(String(format: "%.6f", gLng))\(accuracyPart)（GCJ-02）")
            lines.append("地图: \(markerUri(lng: gLng, lat: gLat, name: config.deviceLabel))")
        } else if e.type == .locationStale {
            lines.append("位置: 暂无法获取（室内/权限受限/信号差）")
        }
        if e.type == .exitHome || e.type == .returnHome {
            lines.append("家庭安全区域半径 \(config.fenceRadiusM) 米")
        }
        if let ep = e.awayEpisodeId { lines.append("离家事件ID: \(ep)") }
        lines.append("事件ID: \(e.id)")
        if let note = e.note { lines.append("备注: \(note)") }
        return lines.joined(separator: "\n")
    }
}
