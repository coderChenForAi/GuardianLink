import SwiftUI

struct EventsView: View {
    @ObservedObject var settingsStore = AppSettingsStore.shared
    @State private var events: [GuardianEvent] = []

    private let fmt: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MM-dd HH:mm:ss"
        return f
    }()

    var body: some View {
        List {
            if events.isEmpty {
                Text("暂无事件。开启监护后，离家/返家/位置更新/定位异常都会记录在这里。")
                    .foregroundColor(.secondary)
                    .font(.footnote)
            } else {
                ForEach(events) { e in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(Self.typeLabel(e.type)).fontWeight(.semibold)
                            Spacer()
                            Text(fmt.string(from: Date(timeIntervalSince1970: Double(e.occurredAt) / 1000)))
                                .font(.caption).foregroundColor(.secondary)
                        }
                        Text(Self.statusLabel(e)).font(.caption).foregroundColor(.secondary)
                        if let err = e.lastError {
                            Text("错误: \(err)").font(.caption2).foregroundColor(.red)
                        }
                        if let note = e.note {
                            Text(note).font(.caption2)
                        }
                    }
                }
            }
        }
        .navigationTitle("事件记录")
        .task { await reload() }
        .refreshable { await reload() }
    }

    private func reload() async {
        let store = settingsStore.settings.role == .guardian ? Stores.guardianReceived : Stores.elder
        events = await store.recent(limit: 50)
    }

    static func typeLabel(_ t: EventType) -> String {
        switch t {
        case .exitHome: return "⚠️ 离家"
        case .returnHome: return "✅ 返家"
        case .locationUpdate: return "📍 位置更新"
        case .locationStale: return "📵 定位异常"
        case .deviceAlert: return "🔧 设备异常"
        }
    }

    static func statusLabel(_ e: GuardianEvent) -> String {
        switch e.sendStatus {
        case .none:
            return e.ackedBy.isEmpty ? "本地记录" : "已确认×\(e.ackedBy.count)"
        case .pending: return "待发送"
        case .sent:
            return e.ackedBy.isEmpty ? "已发送飞书" : "已发送飞书 · 已确认×\(e.ackedBy.count)"
        case .failedRetriable: return "发送失败(将重试)"
        case .failedPermanent: return "发送失败(需检查配置)"
        }
    }
}
