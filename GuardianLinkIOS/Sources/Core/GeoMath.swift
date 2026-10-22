import Foundation

/// 纯几何计算（与 Android 端 GeoMath 同算法），可单测。坐标两点必须同坐标系。
enum GeoMath {
    private static let earthRadiusM = 6_371_000.0

    /// Haversine 大圆距离（米）
    static func haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double) -> Double {
        let dLat = (lat2 - lat1) * .pi / 180
        let dLng = (lng2 - lng1) * .pi / 180
        let a = sin(dLat / 2) * sin(dLat / 2)
            + cos(lat1 * .pi / 180) * cos(lat2 * .pi / 180) * sin(dLng / 2) * sin(dLng / 2)
        let c = 2 * asin(min(max(a, 0), 1).squareRoot())
        return earthRadiusM * c
    }

    /// 是否位于圆形围栏内（边界算内部）
    static func isInsideFence(lat: Double, lng: Double, centerLat: Double, centerLng: Double, radiusM: Double) -> Bool {
        haversineMeters(lat1: lat, lng1: lng, lat2: centerLat, lng2: centerLng) <= radiusM
    }
}

// MARK: - 去抖参数与信号（与 Android FenceDebouncer 同逻辑）

struct FenceParams: Equatable {
    var maxAccuracyM: Float = 100
    var confirmOutsideFixes = 3
    var confirmInsideFixes = 3

    init(maxAccuracyM: Float = 100, confirmOutsideFixes: Int = 3, confirmInsideFixes: Int = 3) {
        precondition(confirmOutsideFixes >= 1, "confirmOutsideFixes >= 1")
        precondition(confirmInsideFixes >= 1, "confirmInsideFixes >= 1")
        self.maxAccuracyM = maxAccuracyM
        self.confirmOutsideFixes = confirmOutsideFixes
        self.confirmInsideFixes = confirmInsideFixes
    }
}

enum FenceSignal: Equatable {
    case rawOutside(Loc)
    case rawInside(Loc)
    case confirmedExit(Loc)
    case confirmedEnter(Loc)
}

struct FenceDebouncer {
    private var params: FenceParams
    private var outsideStreak = 0
    private var insideStreak = 0
    private var confirmedOutside = false

    init(params: FenceParams = FenceParams()) {
        self.params = params
    }

    var isConfirmedOutside: Bool { confirmedOutside }

    mutating func onFix(_ fix: Loc, centerLat: Double, centerLng: Double, radiusM: Double) -> [FenceSignal] {
        guard isValid(fix) else { return [] }
        let inside = GeoMath.isInsideFence(lat: fix.lat, lng: fix.lng, centerLat: centerLat, centerLng: centerLng, radiusM: radiusM)
        return inside ? onInside(fix) : onOutside(fix)
    }

    private func isValid(_ fix: Loc) -> Bool {
        fix.lat != 0 && fix.lng != 0 && fix.accuracyM >= 0 && fix.accuracyM <= params.maxAccuracyM
    }

    private mutating func onInside(_ fix: Loc) -> [FenceSignal] {
        outsideStreak = 0
        insideStreak += 1
        if confirmedOutside {
            if insideStreak >= params.confirmInsideFixes {
                confirmedOutside = false
                insideStreak = 0
                return [.confirmedEnter(fix)]
            }
            // 确认离家后首次回到围栏内也要上报，供状态机进入"疑似返家"
            return insideStreak == 1 ? [.rawInside(fix)] : []
        }
        return insideStreak == 1 ? [.rawInside(fix)] : []
    }

    private mutating func onOutside(_ fix: Loc) -> [FenceSignal] {
        insideStreak = 0
        outsideStreak += 1
        if confirmedOutside { return [] }
        if outsideStreak >= params.confirmOutsideFixes {
            confirmedOutside = true
            outsideStreak = 0
            return [.confirmedExit(fix)]
        }
        return outsideStreak == 1 ? [.rawOutside(fix)] : []
    }

    mutating func reset() {
        outsideStreak = 0
        insideStreak = 0
        confirmedOutside = false
    }
}
