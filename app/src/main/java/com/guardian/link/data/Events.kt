package com.guardian.link.data

import org.json.JSONArray
import org.json.JSONObject

enum class EventType {
    EXIT_HOME,
    RETURN_HOME,
    LOCATION_UPDATE,
    LOCATION_STALE,
    DEVICE_ALERT,
}

enum class SendStatus {
    NONE,
    PENDING,
    SENT,
    FAILED_RETRIABLE,
    FAILED_PERMANENT,
}

/**
 * 监护事件。重要字段：id 全局唯一（幂等键）；awayEpisodeId 标识同一次离家过程；
 * ackedBy 记录已确认收到该事件的监护人设备 ID。
 */
data class GuardianEvent(
    val id: String,
    val type: EventType,
    val occurredAt: Long,
    val locatedAt: Long? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyM: Float? = null,
    val coord: String? = null,
    val sourceDeviceId: String,
    val awayEpisodeId: String? = null,
    val note: String? = null,
    val sendStatus: SendStatus = SendStatus.NONE,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val sentAt: Long? = null,
    val ackedBy: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.name)
        .put("occurredAt", occurredAt)
        .put("locatedAt", locatedAt ?: JSONObject.NULL)
        .put("lat", lat ?: JSONObject.NULL)
        .put("lng", lng ?: JSONObject.NULL)
        .put("accuracyM", accuracyM ?: JSONObject.NULL)
        .put("coord", coord ?: JSONObject.NULL)
        .put("sourceDeviceId", sourceDeviceId)
        .put("awayEpisodeId", awayEpisodeId ?: JSONObject.NULL)
        .put("note", note ?: JSONObject.NULL)
        .put("sendStatus", sendStatus.name)
        .put("retryCount", retryCount)
        .put("lastError", lastError ?: JSONObject.NULL)
        .put("sentAt", sentAt ?: JSONObject.NULL)
        .put("ackedBy", JSONArray(ackedBy))

    companion object {
        fun fromJson(j: JSONObject): GuardianEvent = GuardianEvent(
            id = j.getString("id"),
            type = runCatching { EventType.valueOf(j.getString("type")) }.getOrDefault(EventType.DEVICE_ALERT),
            occurredAt = j.getLong("occurredAt"),
            locatedAt = j.optLongOrNull("locatedAt"),
            lat = j.optDoubleOrNull("lat"),
            lng = j.optDoubleOrNull("lng"),
            accuracyM = j.optFloatOrNull("accuracyM"),
            coord = j.optStringOrNull("coord"),
            sourceDeviceId = j.optString("sourceDeviceId"),
            awayEpisodeId = j.optStringOrNull("awayEpisodeId"),
            note = j.optStringOrNull("note"),
            sendStatus = runCatching { SendStatus.valueOf(j.getString("sendStatus")) }.getOrDefault(SendStatus.NONE),
            retryCount = j.optInt("retryCount", 0),
            lastError = j.optStringOrNull("lastError"),
            sentAt = j.optLongOrNull("sentAt"),
            ackedBy = j.optJSONArray("ackedBy")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it, null) } } ?: emptyList(),
        )

        private fun JSONObject.optLongOrNull(k: String): Long? = if (isNull(k) || !has(k)) null else getLong(k)
        private fun JSONObject.optDoubleOrNull(k: String): Double? = if (isNull(k) || !has(k)) null else getDouble(k)
        private fun JSONObject.optFloatOrNull(k: String): Float? = if (isNull(k) || !has(k)) null else getDouble(k).toFloat()
        private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k) || !has(k)) null else getString(k)
    }
}

fun eventToJson(e: GuardianEvent): JSONObject = e.toJson()
fun eventFromJson(j: JSONObject): GuardianEvent = GuardianEvent.fromJson(j)
