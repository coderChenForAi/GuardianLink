import SwiftUI

struct SettingsView: View {
    @ObservedObject var settingsStore = AppSettingsStore.shared
    @ObservedObject var pairing = PairingStore.shared

    @State private var testResult = ""
    @State private var testing = false

    var body: some View {
        Form {
            Section("角色与标识") {
                Picker("角色", selection: roleBinding) {
                    ForEach(DeviceRole.allCases, id: \.self) { r in
                        Text(r.label).tag(r)
                    }
                }
                TextField("设备/老人显示名", text: labelBinding)
            }

            Section("飞书位置告警") {
                TextField("Webhook URL（留空则不上报）", text: webhookBinding)
                SecureField("签名校验密钥（未开启签名则留空）", text: secretBinding)
                Stepper("离家位置更新间隔: \(settingsStore.settings.awayReportMinutes) 分钟",
                        value: awayReportBinding, in: 1...120)
                Button {
                    testing = true
                    let webhook = settingsStore.settings.feishuWebhook
                    let secret = settingsStore.settings.feishuSecret
                    Task {
                        let result = await FeishuClient.send(
                            webhook: webhook, secret: secret,
                            text: "【GuardianLink】测试消息：飞书告警配置成功 ✅"
                        )
                        testing = false
                        switch result {
                        case .success: testResult = "✅ 已发送，请到飞书群里查看"
                        case .retriable(let r): testResult = "⚠ 可重试失败: \(r)"
                        case .permanent(let r): testResult = "❌ 失败: \(r)"
                        }
                    }
                } label: {
                    Text(testing ? "发送中…" : "发送测试消息")
                }
                if !testResult.isEmpty {
                    Text(testResult).font(.footnote).foregroundColor(.secondary)
                }
            } footer: {
                Text("Webhook 与密钥仅保存在本机；机器人若配置自定义关键词，请包含 GuardianLink。")
            }

            Section("监护策略") {
                Stepper("定位失效判定: \(settingsStore.settings.staleAfterMinutes) 分钟",
                        value: staleBinding, in: 3...60)
            } footer: {
                Text("离家/返家确认默认需连续 3 次有效定位（约 45 秒），精度差于 100 米的定位被忽略。")
            }

            Section("守护组（配对）") {
                LabeledContent("设备 ID", value: pairing.snapshot.deviceId)
                LabeledContent("守护组", value: pairing.snapshot.groupId)
                LabeledContent("配对码", value: pairing.snapshot.pairingCode)
                Button("重新生成配对码") { pairing.regeneratePairingCode() }
                ForEach(pairing.snapshot.guardians) { g in
                    LabeledContent("监护人 \(g.name.isEmpty ? g.id : g.name)", value: formatAge(g.lastSeen))
                }
                if !pairing.snapshot.guardians.isEmpty {
                    ForEach(pairing.snapshot.guardians) { g in
                        Button("撤销授权: \(g.name.isEmpty ? g.id : g.name)", role: .destructive) {
                            pairing.revokeGuardian(id: g.id)
                        }
                    }
                }
                if pairing.snapshot.elder != nil {
                    Button("解除与老人机的配对", role: .destructive) { pairing.clearElder() }
                }
            }

            Section("说明") {
                Text("本机提醒：iOS 采用系统通知 + 震动（后台时），与 Android 的最大音量告警不同。后台围栏监测需要「始终允许」定位权限；应用被用户强杀后系统围栏仍可唤醒，但建议不要强杀。")
                    .font(.footnote)
                    .foregroundColor(.secondary)
            }
        }
        .navigationTitle("设置")
    }

    private var roleBinding: Binding<DeviceRole> {
        Binding(
            get: { settingsStore.settings.role },
            set: { settingsStore.settings.role = $0 }
        )
    }

    private var labelBinding: Binding<String> {
        Binding(
            get: { settingsStore.settings.deviceLabel },
            set: { settingsStore.settings.deviceLabel = $0 }
        )
    }

    private var webhookBinding: Binding<String> {
        Binding(
            get: { settingsStore.settings.feishuWebhook },
            set: { settingsStore.settings.feishuWebhook = $0 }
        )
    }

    private var secretBinding: Binding<String> {
        Binding(
            get: { settingsStore.settings.feishuSecret },
            set: { settingsStore.settings.feishuSecret = $0 }
        )
    }

    private var awayReportBinding: Binding<Int> {
        Binding(
            get: { settingsStore.settings.awayReportMinutes },
            set: { settingsStore.settings.awayReportMinutes = $0 }
        )
    }

    private var staleBinding: Binding<Int> {
        Binding(
            get: { settingsStore.settings.staleAfterMinutes },
            set: { settingsStore.settings.staleAfterMinutes = $0 }
        )
    }
}
