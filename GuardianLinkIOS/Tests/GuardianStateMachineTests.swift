import XCTest
@testable import GuardianLink

final class GuardianStateMachineTests: XCTestCase {

    func testFullAwayAndReturnCycle() {
        var m = GuardianStateMachine()
        let now: Int64 = 1000
        XCTAssertEqual(m.onRawOutside(now: now).filter { if case .exitSuspected = $0 { return true }; return false }.count, 1)
        XCTAssertEqual(m.state, .exitSuspected)

        let exits = m.onExitConfirmed(now: now)
        guard case .exitConfirmed(let episodeId, _) = exits[0] else { return XCTFail() }
        XCTAssertEqual(m.state, .awayAlerted)
        XCTAssertFalse(episodeId.isEmpty)

        _ = m.onRawInside(now: now)
        XCTAssertEqual(m.state, .returnSuspected)

        let returns = m.onReturnConfirmed(now: now)
        guard case .returnConfirmed(let retEpisode, _) = returns[0] else { return XCTFail() }
        XCTAssertEqual(retEpisode, episodeId)
        XCTAssertEqual(m.state, .homeSafe)
        XCTAssertNil(m.episodeId)
    }

    func testRawOutsideInAwayDoesNotReset() {
        var m = GuardianStateMachine()
        _ = m.onRawOutside(now: 1)
        _ = m.onExitConfirmed(now: 2)
        let s = m.state
        _ = m.onRawOutside(now: 3)
        XCTAssertEqual(m.state, s)
    }

    func testStalePreservesAndRestores() {
        var m = GuardianStateMachine()
        _ = m.onRawOutside(now: 1)
        _ = m.onExitConfirmed(now: 2)
        _ = m.onStale(now: 3)
        XCTAssertEqual(m.state, .locationStale)
        XCTAssertTrue(m.onRawOutside(now: 4).isEmpty)
        XCTAssertTrue(m.onRawInside(now: 4).isEmpty)
        let resumed = m.onFreshFix(now: 5)
        XCTAssertEqual(resumed.filter { if case .exitedStale = $0 { return true }; return false }.count, 1)
        XCTAssertEqual(m.state, .awayAlerted)
    }

    func testEpisodeStableAndUnique() {
        var m = GuardianStateMachine()
        _ = m.onExitConfirmed(now: 1)
        let ep1 = m.episodeId
        _ = m.onExitConfirmed(now: 2)
        XCTAssertEqual(m.episodeId, ep1)
        _ = m.onRawInside(now: 3)
        _ = m.onReturnConfirmed(now: 4)
        XCTAssertNil(m.episodeId)
        _ = m.onRawOutside(now: 5)
        _ = m.onExitConfirmed(now: 6)
        XCTAssertNotEqual(m.episodeId, ep1)
    }

    func testRestoreKeepsEpisode() {
        var m = GuardianStateMachine()
        m.restore(state: .awayTracking, episodeId: "episode-1", beforeStale: nil)
        XCTAssertEqual(m.state, .awayTracking)
        XCTAssertEqual(m.episodeId, "episode-1")
        _ = m.onRawInside(now: 1)
        let returns = m.onReturnConfirmed(now: 2)
        guard case .returnConfirmed(let ep, _) = returns[0] else { return XCTFail() }
        XCTAssertEqual(ep, "episode-1")
    }
}
