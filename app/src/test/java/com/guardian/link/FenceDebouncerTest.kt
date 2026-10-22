package com.guardian.link

import com.guardian.link.core.geo.FenceDebouncer
import com.guardian.link.core.geo.FenceParams
import com.guardian.link.core.geo.FenceSignal
import com.guardian.link.core.locate.Loc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FenceDebouncerTest {

    private val centerLat = 39.9087
    private val centerLng = 116.3975
    private val radius = 200.0
    private val params = FenceParams(confirmOutsideFixes = 3, confirmInsideFixes = 3)

    private fun fix(lat: Double, lng: Double = centerLng, accuracy: Float = 10f, t: Long = 0) =
        Loc(lat, lng, accuracy, t)

    private fun outsideFix(i: Int) = fix(centerLat + (250 + i) / 111_320.0)

    private fun insideFix(i: Int = 0) = fix(centerLat + i * 0.000001)

    @Test
    fun `first outside fix is raw not confirmed`() {
        val d = FenceDebouncer(params)
        val signals = d.onFix(outsideFix(0), centerLat, centerLng, radius)
        assertEquals(1, signals.size)
        assertTrue(signals[0] is FenceSignal.RawOutside)
    }

    @Test
    fun `confirmed exit exactly once after enough consecutive outside fixes`() {
        val d = FenceDebouncer(params)
        assertTrue(d.onFix(outsideFix(0), centerLat, centerLng, radius).none { it is FenceSignal.ConfirmedExit })
        assertTrue(d.onFix(outsideFix(1), centerLat, centerLng, radius).none { it is FenceSignal.ConfirmedExit })
        val third = d.onFix(outsideFix(2), centerLat, centerLng, radius)
        assertEquals(1, third.count { it is FenceSignal.ConfirmedExit })
        // 继续在外不再重复确认
        assertTrue(d.onFix(outsideFix(3), centerLat, centerLng, radius).none { it is FenceSignal.ConfirmedExit })
    }

    @Test
    fun `accuracy too poor fixes are ignored`() {
        val d = FenceDebouncer(params)
        repeat(5) {
            val signals = d.onFix(fix(centerLat + 0.01, accuracy = 500f), centerLat, centerLng, radius)
            assertTrue(signals.isEmpty())
        }
    }

    @Test
    fun `return requires consecutive inside fixes and exit re-arms`() {
        val d = FenceDebouncer(params)
        repeat(3) { d.onFix(outsideFix(it), centerLat, centerLng, radius) }
        // 回到围栏内：前两次只是 RAW
        val r1 = d.onFix(insideFix(), centerLat, centerLng, radius)
        assertTrue(r1.any { it is FenceSignal.RawInside } && r1.none { it is FenceSignal.ConfirmedEnter })
        d.onFix(insideFix(), centerLat, centerLng, radius)
        val r3 = d.onFix(insideFix(), centerLat, centerLng, radius)
        assertEquals(1, r3.count { it is FenceSignal.ConfirmedEnter })
        // 再次离家需要重新累计 3 次
        assertTrue(d.onFix(outsideFix(0), centerLat, centerLng, radius).none { it is FenceSignal.ConfirmedExit })
        d.onFix(outsideFix(1), centerLat, centerLng, radius)
        assertTrue(d.onFix(outsideFix(2), centerLat, centerLng, radius).any { it is FenceSignal.ConfirmedExit })
    }

    @Test
    fun `reset clears confirmed state`() {
        val d = FenceDebouncer(params)
        repeat(3) { d.onFix(outsideFix(it), centerLat, centerLng, radius) }
        assertTrue(d.isConfirmedOutside())
        d.reset()
        assertTrue(!d.isConfirmedOutside())
    }
}
