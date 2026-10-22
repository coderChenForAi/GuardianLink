package com.guardian.link.core.locate

/** 定位结果坐标系。高德定位 SDK 返回 GCJ-02；系统 LocationManager 返回 WGS-84。 */
enum class CoordSystem {
    GCJ02,
    WGS84,
}

data class Loc(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val time: Long,
    val coord: CoordSystem = CoordSystem.GCJ02,
    val source: String = "amap",
)
