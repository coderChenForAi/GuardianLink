package com.guardian.link.core.locate

import android.annotation.SuppressLint
import android.content.Context
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * 高德定位 SDK 封装（不依赖 Google Play services）。
 * 高德定位返回坐标为 GCJ-02，与地图/高德链接一致，无需转换。
 */
class AMapLocationSource(private val context: Context) {

    fun hasFinePermission(): Boolean = runCatching {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 连续定位流。定位失败的错误码通过 [onError] 上报（由健康监测展示），不中断定位流。
     */
    @SuppressLint("MissingPermission")
    fun fixes(
        intervalMs: Long,
        onError: (code: Int, msg: String) -> Unit = { _, _ -> },
    ): Flow<Loc> = callbackFlow {
        val client = AMapLocationClient(context.applicationContext)
        val option = AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            interval = intervalMs
            isOnceLocation = false
            isNeedAddress = false
            isLocationCacheEnable = true
        }
        client.setLocationOption(option)
        val listener = AMapLocationListener { loc ->
            if (loc == null) return@AMapLocationListener
            if (loc.errorCode == 0) {
                trySend(
                    Loc(
                        lat = loc.latitude,
                        lng = loc.longitude,
                        accuracyM = loc.accuracy,
                        time = loc.time,
                        coord = CoordSystem.GCJ02,
                        source = "amap",
                    )
                )
            } else {
                onError(loc.errorCode, loc.errorInfo ?: "定位失败")
            }
        }
        client.setLocationListener(listener)
        client.startLocation()
        awaitClose {
            runCatching {
                client.stopLocation()
                client.onDestroy()
            }
        }
    }.buffer(capacity = 16, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    /** 单次定位（围栏设置页"使用当前位置"）。超时返回 null。 */
    @SuppressLint("MissingPermission")
    suspend fun current(timeoutMs: Long = 20_000): Loc? = try {
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                try {
                    val client = AMapLocationClient(context.applicationContext)
                    val option = AMapLocationClientOption().apply {
                        locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                        isOnceLocation = true
                        isNeedAddress = false
                    }
                    client.setLocationOption(option)
                    client.setLocationListener { loc ->
                        if (cont.isActive) {
                            runCatching { client.stopLocation(); client.onDestroy() }
                            if (loc != null && loc.errorCode == 0) {
                                cont.resume(
                                    Loc(loc.latitude, loc.longitude, loc.accuracy, loc.time, CoordSystem.GCJ02, "amap")
                                )
                            } else {
                                cont.resume(null)
                            }
                        }
                    }
                    client.startLocation()
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    } catch (t: Throwable) {
        null
    }
}
