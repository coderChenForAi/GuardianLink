package com.guardian.link.data

import org.json.JSONObject

enum class Role(val label: String) {
    WARD("被监护人"),
    GUARDIAN("监护人"),
}

enum class GuardMode(val label: String) {
    WIFI("WiFi 模式"),
    BLUETOOTH("蓝牙模式"),
}

enum class AlertReason(val title: String) {
    LEFT_WIFI("已离开 WiFi 范围"),
    PEER_LOST("对端设备失联"),
    BT_LOST("蓝牙连接已断开"),
}

sealed interface GuardState {
    data object Idle : GuardState
    data class Connecting(val detail: String = "") : GuardState
    data class Connected(val peer: String, val since: Long) : GuardState
    data class Degraded(val peer: String, val detail: String) : GuardState
    data class Alerting(val reason: AlertReason) : GuardState
}

data class GuardConfig(
    val role: Role,
    val mode: GuardMode,
    val peerName: String,
    val peerIp: String,
    val btAddress: String,
    val wifiGraceMs: Long,
    val heartbeatIntervalMs: Long,
    val lostAfterMs: Long,
    val alertOnPeerOffline: Boolean,
    val feishuWebhook: String,
    val reportEnabled: Boolean,
    val reportIntervalMs: Long,
) {
    fun toJson(): String = JSONObject()
        .put("role", role.name)
        .put("mode", mode.name)
        .put("peerName", peerName)
        .put("peerIp", peerIp)
        .put("btAddress", btAddress)
        .put("wifiGraceMs", wifiGraceMs)
        .put("heartbeatIntervalMs", heartbeatIntervalMs)
        .put("lostAfterMs", lostAfterMs)
        .put("alertOnPeerOffline", alertOnPeerOffline)
        .put("feishuWebhook", feishuWebhook)
        .put("reportEnabled", reportEnabled)
        .put("reportIntervalMs", reportIntervalMs)
        .toString()

    companion object {
        fun fromJson(raw: String?): GuardConfig? = runCatching {
            if (raw.isNullOrBlank()) return null
            val j = JSONObject(raw)
            GuardConfig(
                role = Role.valueOf(j.getString("role")),
                mode = GuardMode.valueOf(j.getString("mode")),
                peerName = j.optString("peerName"),
                peerIp = j.optString("peerIp"),
                btAddress = j.optString("btAddress"),
                wifiGraceMs = j.getLong("wifiGraceMs"),
                heartbeatIntervalMs = j.getLong("heartbeatIntervalMs"),
                lostAfterMs = j.getLong("lostAfterMs"),
                alertOnPeerOffline = j.getBoolean("alertOnPeerOffline"),
                feishuWebhook = j.optString("feishuWebhook"),
                reportEnabled = j.getBoolean("reportEnabled"),
                reportIntervalMs = j.getLong("reportIntervalMs"),
            )
        }.getOrNull()
    }
}
