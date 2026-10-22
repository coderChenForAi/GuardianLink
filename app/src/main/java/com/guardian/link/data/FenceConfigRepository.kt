package com.guardian.link.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.fenceDataStore by preferencesDataStore(name = "fence_config")

/** 家庭围栏配置。坐标固定为 GCJ-02（高德系），半径单位米。 */
data class FenceConfig(
    val centerLat: Double,
    val centerLng: Double,
    val radiusM: Int,
    val coordSystem: String = "GCJ-02",
    val updatedAt: Long = 0,
    val version: Int = 0,
    val enabled: Boolean = false,
) {
    val configured: Boolean get() = centerLat != 0.0 && centerLng != 0.0 && radiusM > 0

    companion object {
        const val MIN_RADIUS_M = 50
        const val MAX_RADIUS_M = 1000

        fun coerceRadius(m: Int): Int = m.coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)

        fun isValidCenter(lat: Double, lng: Double): Boolean =
            lat in 0.8293..55.8271 && lng in 72.004..137.8347
    }
}

class FenceConfigRepository(private val context: Context) {

    private val latKey = doublePreferencesKey("center_lat")
    private val lngKey = doublePreferencesKey("center_lng")
    private val radiusKey = intPreferencesKey("radius_m")
    private val versionKey = intPreferencesKey("version")
    private val updatedKey = longPreferencesKey("updated_at")
    private val enabledKey = booleanPreferencesKey("enabled")

    val config: Flow<FenceConfig> = context.fenceDataStore.data.map { p ->
        FenceConfig(
            centerLat = p[latKey] ?: 0.0,
            centerLng = p[lngKey] ?: 0.0,
            radiusM = p[radiusKey] ?: 200,
            updatedAt = p[updatedKey] ?: 0,
            version = p[versionKey] ?: 0,
            enabled = p[enabledKey] ?: false,
        )
    }

    /** 保存并递增配置版本；返回校验错误，null 表示成功。 */
    suspend fun save(centerLat: Double, centerLng: Double, radiusM: Int, enabled: Boolean = true): String? {
        if (!FenceConfig.isValidCenter(centerLat, centerLng)) return "中心点坐标超出中国范围"
        val r = FenceConfig.coerceRadius(radiusM)
        context.fenceDataStore.edit { p ->
            val v = (p[versionKey] ?: 0) + 1
            p[latKey] = centerLat
            p[lngKey] = centerLng
            p[radiusKey] = r
            p[versionKey] = v
            p[updatedKey] = System.currentTimeMillis()
            p[enabledKey] = enabled
        }
        return null
    }

    suspend fun setEnabled(enabled: Boolean) = context.fenceDataStore.edit {
        it[enabledKey] = enabled
    }
}
