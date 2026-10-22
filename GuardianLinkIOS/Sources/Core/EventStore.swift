import Foundation

/// 事件存储：先持久化后发送；原子写（临时文件+替换）。actor 保证并发安全，可单测。
actor EventStore {
    static let maxEvents = 200

    private let fileURL: URL
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder
    private var cache: [GuardianEvent]?

    init(directory: URL, fileName: String = "guardian_events.json") {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        fileURL = directory.appendingPathComponent(fileName)
        encoder = JSONEncoder()
        decoder = JSONDecoder()
    }

    private func load() -> [GuardianEvent] {
        if let cache { return cache }
        guard let data = try? Data(contentsOf: fileURL), !data.isEmpty,
              let list = try? decoder.decode([GuardianEvent].self, from: data) else {
            cache = []
            return []
        }
        cache = list
        return list
    }

    private func save(_ list: [GuardianEvent]) {
        let trimmed = Self.trim(list)
        cache = trimmed
        guard let data = try? encoder.encode(trimmed) else { return }
        let tmp = fileURL.deletingLastPathComponent().appendingPathComponent(fileURL.lastPathComponent + ".tmp")
        do {
            try data.write(to: tmp, options: .atomic)
            _ = try? FileManager.default.replaceItemAt(fileURL, withItemAt: tmp)
        } catch {
            try? data.write(to: fileURL, options: .atomic)
            try? FileManager.default.removeItem(at: tmp)
        }
    }

    private static func trim(_ list: [GuardianEvent]) -> [GuardianEvent] {
        guard list.count > maxEvents else { return list }
        return Array(list.sorted { $0.occurredAt > $1.occurredAt }.prefix(maxEvents))
    }

    func all() -> [GuardianEvent] { load() }

    func find(id: String) -> GuardianEvent? { load().first { $0.id == id } }

    func upsert(_ event: GuardianEvent) {
        var list = load()
        if let idx = list.firstIndex(where: { $0.id == event.id }) {
            list[idx] = event
        } else {
            list.append(event)
        }
        save(list)
    }

    /// 待发送或可重试失败的事件
    func pending() -> [GuardianEvent] {
        load().filter { $0.sendStatus == .pending || $0.sendStatus == .failedRetriable }
    }

    @discardableResult
    func markSent(id: String, at: Int64) -> GuardianEvent? {
        mutate(id: id) { $0.copyWithStatus(.sent, error: nil, sentAt: at) }
    }

    @discardableResult
    func markFailed(id: String, retryable: Bool, error: String) -> GuardianEvent? {
        mutate(id: id) {
            $0.copyWithStatus(
                retryable ? .failedRetriable : .failedPermanent,
                error: String(error.prefix(300)),
                sentAt: nil,
                bumpRetry: true
            )
        }
    }

    @discardableResult
    func markAcked(id: String, guardianId: String) -> GuardianEvent? {
        mutate(id: id) { e in
            guard !e.ackedBy.contains(guardianId) else { return e }
            var copy = e
            copy.ackedBy = e.ackedBy + [guardianId]
            return copy
        }
    }

    func recent(limit: Int) -> [GuardianEvent] {
        Array(load().sorted { $0.occurredAt > $1.occurredAt }.prefix(limit))
    }

    func exists(id: String) -> Bool { load().contains { $0.id == id } }

    func clear() { save([]) }

    private func mutate(id: String, _ transform: (GuardianEvent) -> GuardianEvent) -> GuardianEvent? {
        var list = load()
        guard let idx = list.firstIndex(where: { $0.id == id }) else { return nil }
        let updated = transform(list[idx])
        list[idx] = updated
        save(list)
        return updated
    }
}

private extension GuardianEvent {
    func copyWithStatus(_ status: SendStatus, error: String?, sentAt: Int64?, bumpRetry: Bool = false) -> GuardianEvent {
        var copy = self
        copy.sendStatus = status
        copy.lastError = error
        copy.sentAt = sentAt
        if bumpRetry { copy.retryCount += 1 }
        return copy
    }
}
