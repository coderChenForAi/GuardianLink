import Foundation
import Network
import UserNotifications
import Combine

enum AppPaths {
    static var documents: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }
}

/// 每个文件一个共享 actor 实例，避免多实例缓存不一致
enum Stores {
    static let elder = EventStore(directory: AppPaths.documents, fileName: "guardian_events.json")
    static let guardianReceived = EventStore(directory: AppPaths.documents, fileName: "guardian_received_events.json")
}

/**
 * 局域网多监护人同步（实验性）：UDP 47910，协议与 Android 端 v2 兼容。
 * 安全边界与 Android 端一致：UDP 明文 + token，可防"随手加入"，不抗同网段抓包重放。
 * iOS 本地网络权限：首次收发时系统弹窗，拒绝则局域网功能不可用（不影响本地围栏与飞书告警）。
 */
@MainActor
final class LanSyncService: ObservableObject {

    static let shared = LanSyncService()

    @Published var lastReceivedEvent: GuardianEvent?
    @Published var lastError: String?

    private var listener: NWListener?
    private var pingTimer: Timer?
    /// 老人端：各监护人最近出现过的对端 endpoint（由 ping 刷新）
    private var guardianEndpoints: [String: NWEndpoint] = [:]

    private let pairing = PairingStore.shared
    private var selfName: String { AppSettingsStore.shared.settings.deviceLabel }

    func start(role: DeviceRole) {
        stop()
        let params = NWParameters.udp
        params.allowLocalEndpointReuse = true
        guard let port = NWEndpoint.Port(rawValue: 47910),
              let l = try? NWListener(using: params, on: port) else {
            lastError = "无法监听 UDP 47910"
            return
        }
        listener = l
        let connQueue = DispatchQueue(label: "lan.sync.conn")
        l.newConnectionHandler = { [weak self] conn in
            conn.start(queue: connQueue)
            self?.pump(conn: conn, role: role)
        }
        l.start(queue: DispatchQueue(label: "lan.sync.listen"))
        if role == .guardian {
            startPing()
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
        pingTimer?.invalidate()
        pingTimer = nil
        guardianEndpoints.removeAll()
    }

    // MARK: - 老人端：推送事件

    func pushEvent(_ event: GuardianEvent) {
        let snap = pairing.snapshot
        for g in snap.guardians {
            guard let endpoint = guardianEndpoints[g.id] else { continue }
            let msg: [String: Any] = [
                "v": 2, "type": "event_push",
                "fromId": snap.deviceId, "fromName": selfName,
                "ts": Int64(Date().timeIntervalSince1970 * 1000),
                "groupId": snap.groupId, "token": g.token,
                "event": eventDictionary(event),
            ]
            send(msg, to: endpoint)
        }
    }

    // MARK: - 监护人端：配对

    func pair(ip: String, code: String, timeoutMs: Int64 = 8000) async -> ElderEntry? {
        await withCheckedContinuation { continuation in
            let host = NWEndpoint.Host(ip)
            let port = NWEndpoint.Port(rawValue: 47910) ?? 47910
            let conn = NWConnection(host: host, port: port, using: .udp)
            var finished = false
            let queue = DispatchQueue(label: "lan.pair")
            let snap = pairing.snapshot
            let req: [String: Any] = [
                "v": 2, "type": "pair_req",
                "fromId": snap.deviceId, "fromName": selfName,
                "ts": Int64(Date().timeIntervalSince1970 * 1000),
                "code": code.trimmingCharacters(in: .whitespaces),
            ]
            conn.stateUpdateHandler = { state in
                if case .ready = state {
                    if let data = try? JSONSerialization.data(withJSONObject: req) {
                        conn.send(content: data, completion: .contentProcessed { _ in })
                    }
                }
            }
            // 超时
            queue.asyncAfter(deadline: .now() + Double(timeoutMs) / 1000) {
                guard !finished else { return }
                finished = true
                conn.cancel()
                Task { @MainActor in continuation.resume(returning: nil) }
            }
            conn.receiveMessage { [weak self] data, _, _, error in
                guard !finished else { return }
                finished = true
                defer { conn.cancel() }
                guard error == nil,
                      let data,
                      let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      json["type"] as? String == "pair_ack",
                      let token = json["token"] as? String else {
                    Task { @MainActor in continuation.resume(returning: nil) }
                    return
                }
                let entry = ElderEntry(
                    id: json["elderId"] as? String ?? "",
                    name: json["elderName"] as? String ?? "",
                    token: token,
                    groupId: json["groupId"] as? String ?? "",
                    host: ip,
                    port: json["port"] as? Int ?? 47910,
                    lastSeen: Int64(Date().timeIntervalSince1970 * 1000)
                )
                Task { @MainActor in
                    self?.pairing.setElder(entry)
                    continuation.resume(returning: entry)
                }
            }
            conn.start(queue: queue)
        }
    }

    // MARK: - 收包处理

    private nonisolated func pump(conn: NWConnection, role: DeviceRole) {
        conn.receiveMessage { [weak self] data, _, _, error in
            if let data {
                Task { @MainActor in self?.handle(data: data, from: conn.endpoint, role: role) }
                self?.pump(conn: conn, role: role)
            } else {
                conn.cancel()
            }
        }
    }

    private func handle(data: Data, from endpoint: NWEndpoint, role: DeviceRole) {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        switch type {
        case "discover" where role == .elder:
            send([
                "v": 2, "type": "announce", "role": "elder",
                "fromId": pairing.snapshot.deviceId, "fromName": selfName, "ts": now,
                "port": 47910,
            ], to: endpoint)

        case "pair_req" where role == .elder:
            guard json["code"] as? String == pairing.snapshot.pairingCode else { return }
            let fromId = (json["fromId"] as? String) ?? "unknown"
            let entry = pairing.authorizeGuardian(id: fromId, name: (json["fromName"] as? String) ?? "监护人")
            guardianEndpoints[entry.id] = endpoint
            send([
                "v": 2, "type": "pair_ack",
                "fromId": pairing.snapshot.deviceId, "fromName": selfName, "ts": now,
                "groupId": pairing.snapshot.groupId,
                "token": entry.token,
                "elderId": pairing.snapshot.deviceId,
                "elderName": selfName,
                "port": 47910,
            ], to: endpoint)

        case "ping" where role == .elder:
            guard let token = json["token"] as? String,
                  let g = pairing.guardianByToken(token) else { return }
            guardianEndpoints[g.id] = endpoint
            pairing.touch(guardianId: g.id, at: now)
            send(base("pong", now), to: endpoint)

        case "pong" where role == .guardian:
            pairing.touchElder(at: now)

        case "event_push" where role == .guardian:
            guard let elder = pairing.snapshot.elder,
                  json["token"] as? String == elder.token,
                  let eventJson = json["event"] as? [String: Any],
                  let data = try? JSONSerialization.data(withJSONObject: eventJson),
                  let event = try? JSONDecoder().decode(GuardianEvent.self, from: data) else { return }
            Task {
                await Stores.guardianReceived.upsert(event)
            }
            let ack: [String: Any] = [
                "v": 2, "type": "event_ack",
                "fromId": pairing.snapshot.deviceId, "fromName": selfName, "ts": now,
                "token": elder.token, "eventId": event.id,
            ]
            send(ack, to: endpoint)
            lastReceivedEvent = event
            if event.type == .exitHome {
                postLocalNotification(
                    title: "⚠️ 老人已离开家庭安全区域",
                    body: "来自老人设备（\(event.sourceDeviceId)）的推送"
                )
            } else if event.type == .returnHome {
                postLocalNotification(title: "✅ 老人已回到家庭安全区域", body: "")
            }

        case "event_ack" where role == .elder:
            guard let token = json["token"] as? String,
                  let g = pairing.guardianByToken(token),
                  let eventId = json["eventId"] as? String else { return }
            guardianEndpoints[g.id] = endpoint
            Task { await Stores.elder.markAcked(id: eventId, guardianId: g.id) }

        default:
            break
        }
    }

    private func startPing() {
        pingTimer?.invalidate()
        pingTimer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                guard let elder = self.pairing.snapshot.elder, !elder.host.isEmpty else { return }
                var msg = self.base("ping", Self.nowMs())
                msg["token"] = elder.token
                self.send(msg, host: elder.host, port: elder.port)
            }
        }
    }

    private nonisolated static func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    private func postLocalNotification(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        )
    }

    // MARK: - 基础

    private func base(_ type: String, _ now: Int64) -> [String: Any] {
        [
            "v": 2, "type": type,
            "fromId": pairing.snapshot.deviceId, "fromName": selfName, "ts": now,
        ]
    }

    private func eventDictionary(_ e: GuardianEvent) -> [String: Any] {
        guard let data = try? JSONEncoder().encode(e),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return obj
    }

    private func send(_ msg: [String: Any], to endpoint: NWEndpoint) {
        guard let data = try? JSONSerialization.data(withJSONObject: msg) else { return }
        let conn = NWConnection(to: endpoint, using: .udp)
        conn.stateUpdateHandler = { state in
            if case .ready = state {
                conn.send(content: data, completion: .contentProcessed { _ in conn.cancel() })
            }
        }
        conn.start(queue: DispatchQueue(label: "lan.send"))
    }

    private func send(_ msg: [String: Any], host: String, port: Int) {
        let endpoint = NWEndpoint.hostPort(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(rawValue: UInt16(port)) ?? 47910
        )
        send(msg, to: endpoint)
    }
}
