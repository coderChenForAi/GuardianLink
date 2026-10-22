import SwiftUI

func formatAge(_ ts: Int64?) -> String {
    guard let ts, ts > 0 else { return "暂无" }
    let age = Int64(Date().timeIntervalSince1970 * 1000) - ts
    if age < 0 { return "刚刚" }
    if age < 60_000 { return "\(age / 1000) 秒前" }
    if age < 3_600_000 { return "\(age / 60_000) 分钟前" }
    if age < 86_400_000 { return "\(age / 3_600_000) 小时前" }
    return "\(age / 86_400_000) 天前"
}

struct HomeView: View {
    @ObservedObject var monitor = FenceMonitor.shared
    @ObservedObject var settingsStore = AppSettingsStore.shared
    @ObservedObject var pairing = PairingStore.shared
    @ObservedObject var lan = LanSyncService.shared

    @State private var pairIp = ""
    @State private var pairCode = ""
    @State private var pairingInFlight = false
    @State private var showPairAlert = false

    private var running: Bool { monitor.monitorRunning }
    private var isElder: Bool { settingsStore.settings.role == .elder }

    var body: some View {
        NavigationStack {
            List {
                statusSection
                if isElder {
                    fenceSection
                    guardianGroupSection
                } else {
                    pairSection
                }
                controlSection
                healthSection
            }
            .navigationTitle("GuardianLink")
            .alert("输入配对码", isPresented: $showPairAlert) {
                TextField("6 位配对码", text: $pairCode)
                Button("配对") { Task { await doPair() } }
                Button("取消", role: .cancel) {}
            } message: {
                Text("请输入老人机「监护」页显示的 6 位配对码（老人机需已开启监护且与本机同一 Wi-Fi）")
            }
        }
    }

    // MARK: - 各分区（全部使用经典 Section 形式，避免多尾闭包重载解析问题）

    private var statusSection: some View {
        Section(header: Text("监护状态")) {
            LabeledContent("角色", value: settingsStore.settings.role.label)
            if isElder {
                LabeledContent("围栏监护", value: running ? monitor.state.label : "未开启")
                LabeledContent("家庭区域", value: monitor.fenceSummary)
                LabeledContent("最近定位", value: formatAge(monitor.lastFix?.time))
            } else {
                LabeledContent("已配对老人机", value: elderText)
                LabeledContent("最近在线", value: formatAge(pairing.snapshot.elder?.lastSeen))
                LabeledContent("最近事件", value: recentEventText)
            }
            LabeledContent("飞书告警", value: feishuText)
            if let err = lan.lastError {
                LabeledContent("局域网", value: err)
            }
        }
    }

    private var fenceSection: some View {
        Section(header: Text("家庭安全区域")) {
            NavigationLink("设置家庭安全区域") { FenceSetupView() }
        }
    }

    private var guardianGroupSection: some View {
        Section(header: Text("多监护人（局域网）")) {
            LabeledContent("配对码", value: pairing.snapshot.pairingCode)
            ForEach(pairing.snapshot.guardians) { g in
                LabeledContent("监护人 \(g.name.isEmpty ? g.id : g.name)", value: formatAge(g.lastSeen))
            }
            Button("重新生成配对码") { pairing.regeneratePairingCode() }
        }
    }

    private var pairSection: some View {
        Section(header: Text("守护对（同一 Wi-Fi）")) {
            if let elder = pairing.snapshot.elder {
                LabeledContent("老人机", value: guardianName(elder.name, id: elder.id))
                Button("解除配对", role: .destructive) { pairing.clearElder() }
            } else {
                TextField("老人机 IP", text: $pairIp)
                    .keyboardType(.decimalPad)
                Button("输入配对码配对…") {
                    pairCode = ""
                    showPairAlert = true
                }
                .disabled(pairIp.isEmpty || pairingInFlight)
            }
        }
    }

    private var controlSection: some View {
        Section(footer: Text("监护状态与位置是两回事——设备在线不代表人在家。围栏监测在本机独立运行，离家后通过飞书通知家人。")) {
            Button {
                if running { monitor.stop() } else { monitor.start() }
            } label: {
                Text(running ? "停止监护" : "开始监护")
                    .frame(maxWidth: .infinity)
                    .foregroundColor(running ? .red : .accentColor)
            }
        }
    }

    private var healthSection: some View {
        Section(header: Text("设备健康")) {
            LabeledContent("后台监护服务", value: running ? "✅ 运行中" : "未开启")
            LabeledContent("最近飞书成功", value: formatAge(monitor.lastFeishuOkAt))
            if let err = monitor.lastError {
                LabeledContent("最近错误", value: err)
            }
        }
    }

    // MARK: - 辅助

    private var feishuText: String {
        settingsStore.settings.feishuWebhook.isEmpty ? "⚠️ 未配置（仅本机提醒）" : "✅ 已配置"
    }

    private var elderText: String {
        guard let elder = pairing.snapshot.elder else { return "未配对" }
        return guardianName(elder.name, id: elder.id)
    }

    private var recentEventText: String {
        guard let e = lan.lastReceivedEvent else { return "暂无" }
        return e.type.rawValue
    }

    private func guardianName(_ name: String, id: String) -> String {
        name.isEmpty ? id : name
    }

    private func doPair() async {
        pairingInFlight = true
        let elder = await lan.pair(ip: pairIp, code: pairCode)
        pairingInFlight = false
        if elder != nil { showPairAlert = false }
    }
}
