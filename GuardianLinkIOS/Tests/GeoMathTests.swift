import XCTest
@testable import GuardianLink

final class GeoMathTests: XCTestCase {

    private let centerLat = 39.9087
    private let centerLng = 116.3975

    func testZeroDistance() {
        XCTAssertEqual(GeoMath.haversineMeters(lat1: centerLat, lng1: centerLng, lat2: centerLat, lng2: centerLng), 0, accuracy: 0.001)
    }

    func testRoughly111mPerMilliDegreeLatitude() {
        let d = GeoMath.haversineMeters(lat1: centerLat, lng1: centerLng, lat2: centerLat + 0.001, lng2: centerLng)
        XCTAssertEqual(d, 111.2, accuracy: 1.5)
    }

    func testInsideOutsideBoundary() {
        let inLat = centerLat + 100.0 / 111_320.0
        XCTAssertTrue(GeoMath.isInsideFence(lat: inLat, lng: centerLng, centerLat: centerLat, centerLng: centerLng, radiusM: 200))
        let outLat = centerLat + 300.0 / 111_320.0
        XCTAssertFalse(GeoMath.isInsideFence(lat: outLat, lng: centerLng, centerLat: centerLat, centerLng: centerLng, radiusM: 200))
        let edgeLat = centerLat + 200.0 / 111_320.0
        XCTAssertTrue(GeoMath.isInsideFence(lat: edgeLat, lng: centerLng, centerLat: centerLat, centerLng: centerLng, radiusM: 200))
    }
}
