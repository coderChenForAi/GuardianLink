import XCTest
@testable import GuardianLink

final class EventStoreTests: XCTestCase {

    private func newStore() -> EventStore {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("est-\(UUID().uuidString)")
        return EventStore(directory: dir)
    }

    private func event(id: String, type: EventType = .exitHome, at: Int64 = 1) -> GuardianEvent {
        GuardianEvent(
            id: id, type: type, occurredAt: at, locatedAt: nil,
            lat: 30.0, lng: 120.0, accuracyM: 10, coord: "GCJ02",
            sourceDeviceId: "dev-test"
        )
    }

    func testPersistBeforeSendAndPending() async {
        let s = newStore()
        await s.upsert(event(id: "e1").withStatus(.pending))
        var pending = await s.pending()
        XCTAssertEqual(pending.count, 1)
        await s.markSent(id: "e1", at: 99)
        pending = await s.pending()
        XCTAssertEqual(pending.count, 0)
        let e = await s.find(id: "e1")
        XCTAssertEqual(e?.sendStatus, .sent)
        XCTAssertEqual(e?.sentAt, 99)
    }

    func testUpsertSameIdNoDuplicate() async {
        let s = newStore()
        await s.upsert(event(id: "e1"))
        await s.upsert(event(id: "e1", at: 2))
        let all = await s.all()
        XCTAssertEqual(all.count, 1)
        XCTAssertEqual(all.first?.occurredAt, 2)
    }

    func testRetryableAndPermanent() async {
        let s = newStore()
        await s.upsert(event(id: "e1").withStatus(.pending))
        await s.markFailed(id: "e1", retryable: true, error: "HTTP 500")
        XCTAssertEqual(await s.pending().count, 1)
        await s.markFailed(id: "e1", retryable: false, error: "飞书业务错误")
        XCTAssertEqual(await s.pending().count, 0)
        let e = await s.find(id: "e1")
        XCTAssertEqual(e?.sendStatus, .failedPermanent)
        XCTAssertEqual(e?.retryCount, 2)
    }

    func testMarkAckedIdempotent() async {
        let s = newStore()
        await s.upsert(event(id: "e1"))
        await s.markAcked(id: "e1", guardianId: "g1")
        await s.markAcked(id: "e1", guardianId: "g1")
        let e = await s.find(id: "e1")
        XCTAssertEqual(e?.ackedBy, ["g1"])
    }

    func testRecentSortedDescAndTrimmed() async {
        let s = newStore()
        for i in 0..<(EventStore.maxEvents + 5) {
            await s.upsert(event(id: "e\(i)", at: Int64(i)))
        }
        let all = await s.all()
        XCTAssertEqual(all.count, EventStore.maxEvents)
        let recent = await s.recent(limit: 3)
        XCTAssertEqual(recent.map(\.id), [
            "e\(EventStore.maxEvents + 4)",
            "e\(EventStore.maxEvents + 3)",
            "e\(EventStore.maxEvents + 2)",
        ])
    }
}

private extension GuardianEvent {
    func withStatus(_ status: SendStatus) -> GuardianEvent {
        var copy = self
        copy.sendStatus = status
        return copy
    }
}
