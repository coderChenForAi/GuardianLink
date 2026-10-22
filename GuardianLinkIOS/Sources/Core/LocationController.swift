import Foundation
import CoreLocation

/// CoreLocation 封装：系统级 CLCircularRegion 围栏 + 持续定位。回调统一回到主线程由调用方包装。
final class LocationController: NSObject, CLLocationManagerDelegate {

    let manager = CLLocationManager()

    var onFix: ((Loc) -> Void)?
    var onRegionExit: (() -> Void)?
    var onRegionEnter: (() -> Void)?
    var onError: ((String) -> Void)?
    var onAuthChanged: (() -> Void)?

    private var homeRegion: CLCircularRegion?
    private static let regionId = "home_fence"

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        manager.distanceFilter = 20
        manager.activityType = .other
    }

    var authStatus: CLAuthorizationStatus { manager.authorizationStatus }

    var hasWhenInUse: Bool {
        manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways
    }

    var hasAlways: Bool { manager.authorizationStatus == .authorizedAlways }

    var locationServicesEnabled: Bool { CLLocationManager.locationServicesEnabled() }

    func requestWhenInUse() { manager.requestWhenInUseAuthorization() }

    /// 系统会在"使用期间"授权后择机弹出"始终允许"升级提示
    func requestAlways() { manager.requestAlwaysAuthorization() }

    /// 注册系统围栏并开始持续定位
    func startFence(center: CLLocationCoordinate2D, radiusM: Double) {
        stopMonitoringOnly()
        let region = CLCircularRegion(
            center: center,
            radius: max(radiusM, 100),
            identifier: Self.regionId
        )
        region.notifyOnEntry = true
        region.notifyOnExit = true
        homeRegion = region
        manager.startMonitoring(for: region)
        manager.requestState(for: region)
        startUpdates()
    }

    func startUpdates() {
        if canRunBackground {
            manager.allowsBackgroundLocationUpdates = true
            manager.pausesLocationUpdatesAutomatically = false
        } else {
            manager.allowsBackgroundLocationUpdates = false
        }
        manager.startUpdatingLocation()
    }

    private var canRunBackground: Bool { manager.authorizationStatus == .authorizedAlways }

    func stopMonitoringOnly() {
        for region in manager.monitoredRegions where region.identifier == Self.regionId {
            manager.stopMonitoring(for: region)
        }
        homeRegion = nil
    }

    func stopAll() {
        manager.allowsBackgroundLocationUpdates = false
        stopMonitoringOnly()
        manager.stopUpdatingLocation()
    }

    // MARK: - CLLocationManagerDelegate

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        onAuthChanged?()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let last = locations.last else { return }
        let fix = Loc(
            lat: last.coordinate.latitude,
            lng: last.coordinate.longitude,
            accuracyM: Float(last.horizontalAccuracy),
            time: Int64(last.timestamp.timeIntervalSince1970 * 1000),
            coord: .wgs84,
            source: "corelocation"
        )
        onFix?(fix)
    }

    func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        guard region.identifier == Self.regionId else { return }
        onRegionExit?()
    }

    func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        guard region.identifier == Self.regionId else { return }
        onRegionEnter?()
    }

    func locationManager(_ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: Error) {
        onError?("围栏监测失败: \(error.localizedDescription)")
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        onError?("定位失败: \(error.localizedDescription)")
    }
}
