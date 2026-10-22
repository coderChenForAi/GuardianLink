import XCTest
@testable import GuardianLink

final class FenceDebouncerTests: XCTestCase {

    private let centerLat = 39.9087
    private let centerLng = 116.3975
    private let radius = 200.0
    private let params = FenceParams(confirmOutsideFixes: 3, confirmInsideFixes: 3)

    private func outsideFix(_ i: Int) -> Loc {
        Loc(lat: centerLat + (250 + Double(i)) / 111_320.0, lng: centerLng, accuracyM: 10, time: 0)
    }

    private func insideFix(_ i: Double = 0) -> Loc {
        Loc(lat: centerLat + i * 0.000001, lng: centerLng, accuracyM: 10, time: 0)
    }

    func testFirstOutsideIsRawNotConfirmed() {
        var d = FenceDebouncer(params: params)
        let signals = d.onFix(outsideFix(0), centerLat: centerLat, centerLng: centerLng, radiusM: radius)
        XCTAssertEqual(signals.count, 1)
        guard case .rawOutside = signals[0] else { return XCTFail("expected rawOutside") }
    }

    func testConfirmedExitExactlyOnce() {
        var d = FenceDebouncer(params: params)
        XCTAssertFalse(d.onFix(outsideFix(0), centerLat: centerLat, centerLng: centerLng, radiusM: radius).contains { $0 == .confirmedExit(outsideFix(1)) })
        XCTAssertTrue(d.onFix(outsideFix(1), centerLat: centerLat, centerLng: centerLng, radiusM: radius).allSatisfy { if case .confirmedExit = $0 { return false }; return true })
        let third = d.onFix(outsideFix(2), centerLat: centerLat, centerLng: centerLng, radiusM: radius)
        XCTAssertEqual(third.filter { if case .confirmedExit = $0 { return true }; return false }.count, 1)
        XCTAssertTrue(d.onFix(outsideFix(3), centerLat: centerLat, centerLng: centerLng, radiusM: radius).allSatisfy { if case .confirmedExit = $0 { return false }; return true })
    }

    func testPoorAccuracyIgnored() {
        var d = FenceDebouncer(params: params)
        for _ in 0..<5 {
            let bad = Loc(lat: centerLat + 0.01, lng: centerLng, accuracyM: 500, time: 0)
            XCTAssertTrue(d.onFix(bad, centerLat: centerLat, centerLng: centerLng, radiusM: radius).isEmpty)
        }
    }

    func testReturnRequiresStreakAndExitRearms() {
        var d = FenceDebouncer(params: params)
        for i in 0..<3 { _ = d.onFix(outsideFix(i), centerLat: centerLat, centerLng: centerLng, radiusM: radius) }
        let r1 = d.onFix(insideFix(), centerLat: centerLat, centerLng: centerLng, radiusM: radius)
        XCTAssertTrue(r1.contains { if case .rawInside = $0 { return true }; return false })
        XCTAssertTrue(r1.allSatisfy { if case .confirmedEnter = $0 { return false }; return true })
        _ = d.onFix(insideFix(), centerLat: centerLat, centerLng: centerLng, radiusM: radius)
        let r3 = d.onFix(insideFix(), centerLat: centerLat, centerLng: centerLng, radiusM: radius)
        XCTAssertEqual(r3.filter { if case .confirmedEnter = $0 { return true }; return false }.count, 1)
        // 再次离家需重新累计
        XCTAssertTrue(d.onFix(outsideFix(0), centerLat: centerLat, centerLng: centerLng, radiusM: radius).allSatisfy { if case .confirmedExit = $0 { return false }; return true })
        _ = d.onFix(outsideFix(1), centerLat: centerLat, centerLng: centerLng, radiusM: radius)
        XCTAssertTrue(d.onFix(outsideFix(2), centerLat: centerLat, centerLng: centerLng, radiusM: radius).contains { if case .confirmedExit = $0 { return true }; return false })
    }

    func testResetClearsConfirmed() {
        var d = FenceDebouncer(params: params)
        for i in 0..<3 { _ = d.onFix(outsideFix(i), centerLat: centerLat, centerLng: centerLng, radiusM: radius) }
        XCTAssertTrue(d.isConfirmedOutside)
        d.reset()
        XCTAssertFalse(d.isConfirmedOutside)
    }
}
