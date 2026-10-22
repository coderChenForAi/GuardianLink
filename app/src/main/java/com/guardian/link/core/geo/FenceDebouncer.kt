package com.guardian.link.core.geo

import com.guardian.link.core.locate.Loc

/**
 * 围栏判定的去抖层：用连续 N 次位于围栏内/外来确认真实的进出，
 * 过滤定位漂移；精度高于 [FenceParams.maxAccuracyM] 的定位点直接丢弃。
 * 纯 JVM 实现，可单测。
 */
data class FenceParams(
    val maxAccuracyM: Float = 100f,
    val confirmOutsideFixes: Int = 3,
    val confirmInsideFixes: Int = 3,
) {
    init {
        require(confirmOutsideFixes >= 1) { "confirmOutsideFixes >= 1" }
        require(confirmInsideFixes >= 1) { "confirmInsideFixes >= 1" }
    }
}

sealed interface FenceSignal {
    /** 第一次出现围栏外定位（未确认）。 */
    data class RawOutside(val fix: Loc) : FenceSignal

    /** 第一次回到围栏内（未确认）。 */
    data class RawInside(val fix: Loc) : FenceSignal

    /** 连续多次围栏外 → 确认离家。 */
    data class ConfirmedExit(val fix: Loc) : FenceSignal

    /** 确认离家后，连续多次围栏内 → 确认返家。 */
    data class ConfirmedEnter(val fix: Loc) : FenceSignal
}

class FenceDebouncer(private val params: FenceParams = FenceParams()) {

    private var outsideStreak = 0
    private var insideStreak = 0
    private var confirmedOutside = false

    fun onFix(fix: Loc, centerLat: Double, centerLng: Double, radiusM: Double): List<FenceSignal> {
        if (!fix.isValid()) return emptyList()
        val inside = GeoMath.isInsideFence(fix.lat, fix.lng, centerLat, centerLng, radiusM)
        return if (inside) onInside(fix) else onOutside(fix)
    }

    private fun Loc.isValid(): Boolean =
        lat != 0.0 && lng != 0.0 && accuracyM in 0f..params.maxAccuracyM

    private fun onInside(fix: Loc): List<FenceSignal> {
        outsideStreak = 0
        insideStreak++
        if (confirmedOutside) {
            if (insideStreak >= params.confirmInsideFixes) {
                confirmedOutside = false
                insideStreak = 0
                return listOf(FenceSignal.ConfirmedEnter(fix))
            }
            // 确认离家后首次回到围栏内也要上报，供状态机进入"疑似返家"
            return if (insideStreak == 1) listOf(FenceSignal.RawInside(fix)) else emptyList()
        }
        return if (insideStreak == 1) listOf(FenceSignal.RawInside(fix)) else emptyList()
    }

    private fun onOutside(fix: Loc): List<FenceSignal> {
        insideStreak = 0
        outsideStreak++
        if (confirmedOutside) return emptyList()
        if (outsideStreak >= params.confirmOutsideFixes) {
            confirmedOutside = true
            outsideStreak = 0
            return listOf(FenceSignal.ConfirmedExit(fix))
        }
        return if (outsideStreak == 1) listOf(FenceSignal.RawOutside(fix)) else emptyList()
    }

    fun reset() {
        outsideStreak = 0
        insideStreak = 0
        confirmedOutside = false
    }

    /** 是否处于"已确认在围栏外"状态（用于 UI/恢复）。 */
    fun isConfirmedOutside(): Boolean = confirmedOutside
}
