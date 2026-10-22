import Foundation

/// WGS-84 → GCJ-02 标准偏移算法（与 Android 端 GeoConverter 同实现）。
/// iOS CoreLocation 返回 WGS-84；高德地图链接需要 GCJ-02，发送告警前转换。
enum GeoConverter {
    private static let pi = Double.pi
    private static let a = 6_378_245.0
    private static let ee = 0.006_693_421_622_965_943_23

    static func wgs84ToGcj02(lat: Double, lng: Double) -> (lat: Double, lng: Double) {
        if outOfChina(lat: lat, lng: lng) { return (lat, lng) }
        var dLat = transformLat(x: lng - 105.0, y: lat - 35.0)
        var dLng = transformLng(x: lng - 105.0, y: lat - 35.0)
        let radLat = lat / 180.0 * pi
        var magic = sin(radLat)
        magic = 1 - ee * magic * magic
        let sqrtMagic = magic.squareRoot()
        dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * pi)
        dLng = (dLng * 180.0) / (a / sqrtMagic * cos(radLat) * pi)
        return (lat + dLat, lng + dLng)
    }

    private static func outOfChina(lat: Double, lng: Double) -> Bool {
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    private static func transformLat(x: Double, y: Double) -> Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * (abs(x)).squareRoot()
        ret += (20.0 * sin(6.0 * x * pi) + 20.0 * sin(2.0 * x * pi)) * 2.0 / 3.0
        ret += (20.0 * sin(y * pi) + 40.0 * sin(y / 3.0 * pi)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * pi) + 320.0 * sin(y * pi / 30.0)) * 2.0 / 3.0
        return ret
    }

    private static func transformLng(x: Double, y: Double) -> Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * (abs(x)).squareRoot()
        ret += (20.0 * sin(6.0 * x * pi) + 20.0 * sin(2.0 * x * pi)) * 2.0 / 3.0
        ret += (20.0 * sin(x * pi) + 40.0 * sin(x / 3.0 * pi)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * pi) + 300.0 * sin(x / 30.0 * pi)) * 2.0 / 3.0
        return ret
    }
}

/// 高德 URI API marker 链接（入参必须是 GCJ-02 坐标）。
func markerUri(lng: Double, lat: Double, name: String) -> String {
    let label = name.isEmpty ? "位置" : name
    let encoded = label.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? label
    return "https://uri.amap.com/marker?position="
        + String(format: "%.6f", lng) + "," + String(format: "%.6f", lat)
        + "&name=" + encoded
}

/// WGS-84 定位点转 GCJ-02
func toGcj02(_ loc: Loc) -> Loc {
    guard loc.coord == .wgs84 else { return loc }
    let converted = GeoConverter.wgs84ToGcj02(lat: loc.lat, lng: loc.lng)
    var copy = loc
    copy.lat = converted.lat
    copy.lng = converted.lng
    copy.coord = .gcj02
    return copy
}
