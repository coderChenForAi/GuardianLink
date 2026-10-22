import Foundation
import Combine

/// 设备身份/守护组/已授权监护人与已配对老人的持久化（与 Android 端字段兼容）。
struct GuardianEntry: Codable, Equatable, Identifiable {
    var id: String
    var name: String
    var token: String
    var lastSeen: Int64
}

struct ElderEntry: Codable, Equatable {
    var id: String
    var name: String
    var token: String
    var groupId: String
    var host: String
    var port: Int
    var lastSeen: Int64
}

struct PairingSnapshot: Codable, Equatable {
    var deviceId: String
    var groupId: String
    var pairingCode: String
    var guardians: [GuardianEntry]
    var elder: ElderEntry?
}

/// 配对与身份存储（JSON 文件，原子写）。
final class PairingStore: ObservableObject {
    static let shared = PairingStore()

    @Published private(set) var snapshot: PairingSnapshot

    private let fileURL: URL
    private let queue = DispatchQueue(label: "pairing.store")

    init(directory: URL? = nil) {
        let dir = directory ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        fileURL = dir.appendingPathComponent("pairing.json")
        snapshot = PairingStore.load(from: fileURL) ?? PairingStore.newSnapshot()
        PairingStore.save(snapshot, to: fileURL)
    }

    private static func newSnapshot() -> PairingSnapshot {
        PairingSnapshot(
            deviceId: "dev-" + String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(12)),
            groupId: "gl-" + String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(10)),
            pairingCode: String(format: "%06d", Int.random(in: 0...999_999)),
            guardians: [],
            elder: nil
        )
    }

    private static func load(from url: URL) -> PairingSnapshot? {
        guard let data = try? Data(contentsOf: url),
              let snap = try? JSONDecoder().decode(PairingSnapshot.self, from: data),
              !snap.deviceId.isEmpty else { return nil }
        return snap
    }

    private static func save(_ snap: PairingSnapshot, to url: URL) {
        guard let data = try? JSONEncoder().encode(snap) else { return }
        try? data.write(to: url, options: .atomic)
    }

    func update(_ transform: @escaping (inout PairingSnapshot) -> Void) {
        queue.sync {
            var copy = snapshot
            transform(&copy)
            snapshot = copy
            PairingStore.save(copy, to: fileURL)
        }
    }

    func regeneratePairingCode() {
        update { $0.pairingCode = String(format: "%06d", Int.random(in: 0...999_999)) }
    }

    /// 注册/更新一位监护人并签发独立 token（老人端调用）
    @discardableResult
    func authorizeGuardian(id: String, name: String) -> GuardianEntry {
        var newEntry: GuardianEntry?
        update { snap in
            if let existing = snap.guardians.first(where: { $0.id == id }) {
                var updated = existing
                updated.name = name
                newEntry = updated
                snap.guardians.removeAll { $0.id == id }
                snap.guardians.append(updated)
            } else {
                let entry = GuardianEntry(
                    id: id,
                    name: name,
                    token: UUID().uuidString.replacingOccurrences(of: "-", with: ""),
                    lastSeen: 0
                )
                newEntry = entry
                snap.guardians.append(entry)
            }
        }
        return newEntry!
    }

    func revokeGuardian(id: String) {
        update { $0.guardians.removeAll { $0.id == id } }
    }

    func guardianByToken(_ token: String) -> GuardianEntry? {
        snapshot.guardians.first { $0.token == token }
    }

    func touch(guardianId: String, at: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        update { snap in
            snap.guardians = snap.guardians.map {
                $0.id == guardianId ? GuardianEntry(id: $0.id, name: $0.name, token: $0.token, lastSeen: at) : $0
            }
        }
    }

    func setElder(_ elder: ElderEntry) {
        update { $0.elder = elder }
    }

    func touchElder(at: Int64) {
        update { snap in
            if let e = snap.elder {
                snap.elder = ElderEntry(
                    id: e.id, name: e.name, token: e.token,
                    groupId: e.groupId, host: e.host, port: e.port, lastSeen: at
                )
            }
        }
    }

    func clearElder() {
        update { $0.elder = nil }
    }

    func resetGroup() {
        update {
            $0.groupId = "gl-" + String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(10))
            $0.pairingCode = String(format: "%06d", Int.random(in: 0...999_999))
            $0.guardians = []
        }
    }
}

/// 运行设置（UserDefaults 持久化）。
final class AppSettingsStore: ObservableObject {
    static let shared = AppSettingsStore()

    @Published var settings: AppSettings {
        didSet { persist() }
    }

    private init() {
        if let data = UserDefaults.standard.data(forKey: "app_settings"),
           let decoded = try? JSONDecoder().decode(AppSettings.self, from: data) {
            settings = decoded
        } else {
            settings = AppSettings()
        }
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(settings) {
            UserDefaults.standard.set(data, forKey: "app_settings")
        }
    }
}

/// 监护状态机快照持久化（App 重启后恢复，不重复告警）。
enum StateStore {
    private static let key = "guardian_state_snapshot"

    struct Snapshot: Codable, Equatable {
        var state: GuardianState
        var episodeId: String?
        var beforeStale: GuardianState?
    }

    static func save(_ snap: Snapshot) {
        if let data = try? JSONEncoder().encode(snap) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    static func load() -> Snapshot? {
        guard let data = UserDefaults.standard.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(Snapshot.self, from: data)
    }

    static func clear() {
        UserDefaults.standard.removeObject(forKey: key)
    }
}
