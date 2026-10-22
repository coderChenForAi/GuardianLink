package com.guardian.link.data

import org.json.JSONObject

/** 设备角色。兼容旧存储值：WARD→ELDER_DEVICE，GUARDIAN→GUARDIAN_DEVICE。 */
enum class Role(val label: String) {
    ELDER_DEVICE("老人设备"),
    GUARDIAN_DEVICE("监护人设备");

    companion object {
        fun fromStored(raw: String?): Role = when (raw) {
            "GUARDIAN_DEVICE", "GUARDIAN" -> GUARDIAN_DEVICE
            else -> ELDER_DEVICE
        }
    }
}

enum class GuardMode(val label: String) {
    FENCE("家庭围栏监护"),
    WIFI("连接监护·WiFi"),
    BLUETOOTH("连接监护·蓝牙"),
}

enum class AlertReason(val title: String) {
    LEFT_WIFI("设备已离开 WiFi 范围"),
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
    val feishuSecret: String,
    val deviceLabel: String,
    val reportEnabled: Boolean,
    val reportIntervalMs: Long,
    val awayReportIntervalMs: Long,
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
        .put("feishuSecret", feishuSecret)
        .put("deviceLabel", deviceLabel)
        .put("reportEnabled", reportEnabled)
        .put("reportIntervalMs", reportIntervalMs)
        .put("awayReportIntervalMs", awayReportIntervalMs)
        .toString()

    companion object {
        fun fromJson(raw: String?): GuardConfig? = runCatching {
            if (raw.isNullOrBlank()) return null
            val j = JSONObject(raw)
            GuardConfig(
                role = Role.fromStored(j.optString("role", "ELDER_DEVICE")),
                mode = runCatching { GuardMode.valueOf(j.getString("mode")) }.getOrDefault(GuardMode.FENCE),
                peerName = j.optString("peerName"),
                peerIp = j.optString("peerIp"),
                btAddress = j.optString("btAddress"),
                wifiGraceMs = j.getLong("wifiGraceMs"),
                heartbeatIntervalMs = j.getLong("heartbeatIntervalMs"),
                lostAfterMs = j.getLong("lostAfterMs"),
                alertOnPeerOffline = j.getBoolean("alertOnPeerOffline"),
                feishuWebhook = j.optString("feishuWebhook"),
                feishuSecret = j.optString("feishuSecret"),
                deviceLabel = j.optString("deviceLabel"),
                reportEnabled = j.getBoolean("reportEnabled"),
                reportIntervalMs = j.getLong("reportIntervalMs"),
                awayReportIntervalMs = j.optLong("awayReportIntervalMs", 10 * 60_000L),
            )
        }.getOrNull()
    }
}
