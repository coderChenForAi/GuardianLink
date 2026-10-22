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
    @State private var pairHint = ""

    private var running: Bool { monitor.monitorRunning }

    var body: some View {
        NavigationStack {
            List {
                Section("监护状态") {
                    LabeledContent("角色", value: settingsStore.settings.role.label)
                    if settingsStore.settings.role == .elder {
                        LabeledContent("围栏监护", value: running ? monitor.state.label : "未开启")
                        LabeledContent("家庭区域", value: monitor.fenceSummary)
                        LabeledContent("最近定位", value: formatAge(monitor.lastFix?.time))
                    } else {
                        LabeledContent(
                            "已配对老人机",
                            value: elderText
                        )
                        LabeledContent("最近在线", value: formatAge(pairing.snapshot.elder?.lastSeen))
                        LabeledContent("最近事件", value: lan.lastReceivedEvent.map { $0.type.rawValue } ?? "暂无")
                    }
                    LabeledContent(
                        "飞书告警",
                        value: settingsStore.settings.feishuWebhook.isEmpty ? "⚠️ 未配置（仅本机提醒）" : "✅ 已配置"
                    )
                    if let err = lan.lastError {
                        LabeledContent("局域网", value: err)
                    }
                }

                if settingsStore.settings.role == .elder {
                    Section("家庭安全区域") {
                        NavigationLink("设置围栏") { FenceSetupView() }
                    }
                    Section("多监护人（局域网）") {
                        LabeledContent("配对码", value: pairing.snapshot.pairingCode)
                        ForEach(pairing.snapshot.guardians) { g in
                            LabeledContent("监护人 \(g.name.isEmpty ? g.id : g.name)", value: formatAge(g.lastSeen))
                        }
                        Button("重新生成配对码") { pairing.regeneratePairingCode() }
                    }
                } else {
                    Section("守护对（同一 Wi-Fi）") {
                        if let elder = pairing.snapshot.elder {
                            LabeledContent("老人机", value: elder.name.isEmpty ? elder.id : elder.name)
                            Button("解除配对", role: .destructive) { pairing.clearElder() }
                        } else {
                            TextField("老人机 IP，如 192.168.1.23", text: $pairIp)
                                .keyboardType(.decimalPad)
                            Button("输入配对码配对…") {
                                pairCode = ""
                                showPairAlert = true
                            }
                            .disabled(pairIp.isEmpty || pairingInFlight)
                        }
                    }
                }

                Section {
                    Button {
                        if running { monitor.stop() } else { monitor.start() }
                    } label: {
                        Text(running ? "停止监护" : "开始监护")
                            .frame(maxWidth: .infinity)
                            .foregroundColor(running ? .red : .accentColor)
                    }
                } footer: {
                    Text("监护状态与位置是两回事——设备在线不代表人在家。围栏监测在本机独立运行，离家后通过飞书通知家人。")
                }

                Section("设备健康") {
                    LabeledContent("后台监护服务", value: running ? "✅ 运行中" : "未开启")
                    LabeledContent("最近飞书成功", value: formatAge(monitor.lastFeishuOkAt))
                    if let err = monitor.lastError {
                        LabeledContent("最近错误", value: err)
                    }
                }
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

    private var elderText: String {
        guard let elder = pairing.snapshot.elder else { return "未配对" }
        let name = elder.name.isEmpty ? elder.id : elder.name
        return name
    }

    private func doPair() async {
        pairingInFlight = true
        let elder = await lan.pair(ip: pairIp, code: pairCode)
        pairingInFlight = false
        pairHint = elder != nil ? "配对成功" : "配对失败：请检查同一 Wi-Fi、老人机已开启监护、配对码正确"
        if elder != nil { showPairAlert = false }
    }
}
