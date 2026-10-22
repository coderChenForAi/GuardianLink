package com.guardian.link.core.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 纯几何计算，不依赖 Android，可单测。坐标一律使用同一坐标系下的十进制度。 */
object GeoMath {

    private const val EARTH_RADIUS_M = 6371000.0

    /** Haversine 大圆距离（米）。 */
    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        return EARTH_RADIUS_M * c
    }

    /** 是否位于圆形围栏内（边界算内部）。坐标两点必须同坐标系。 */
    fun isInsideFence(
        lat: Double,
        lng: Double,
        centerLat: Double,
        centerLng: Double,
        radiusM: Double,
    ): Boolean = haversineMeters(lat, lng, centerLat, centerLng) <= radiusM
}
