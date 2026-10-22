package com.guardian.link

import com.guardian.link.core.geo.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {

    // 天安门坐标（GCJ-02 近似值仅用于数学测试，与坐标系无关）
    private val centerLat = 39.9087
    private val centerLng = 116.3975

    @Test
    fun `haversine zero distance for same point`() {
        assertEquals(0.0, GeoMath.haversineMeters(centerLat, centerLng, centerLat, centerLng), 0.001)
    }

    @Test
    fun `haversine roughly 111m per 0_001 degree latitude`() {
        val d = GeoMath.haversineMeters(centerLat, centerLng, centerLat + 0.001, centerLng)
        assertEquals(111.2, d, 1.5)
    }

    @Test
    fun `inside and outside fence boundary`() {
        // 约 100 米外
        val inLat = centerLat + 100.0 / 111_320.0
        assertTrue(GeoMath.isInsideFence(inLat, centerLng, centerLat, centerLng, 200.0))
        // 约 300 米外
        val outLat = centerLat + 300.0 / 111_320.0
        assertFalse(GeoMath.isInsideFence(outLat, centerLng, centerLat, centerLng, 200.0))
        // 边界距离等于半径视为内部
        val edgeLat = centerLat + 200.0 / 111_320.0
        assertTrue(GeoMath.isInsideFence(edgeLat, centerLng, centerLat, centerLng, 200.0))
    }
}
