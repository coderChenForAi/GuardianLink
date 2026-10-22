package com.guardian.link.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "guardian_settings")

data class AppSettings(
    val role: Role = Role.ELDER_DEVICE,
    val mode: GuardMode = GuardMode.FENCE,
    val peerName: String = "",
    val peerIp: String = "",
    val btAddress: String = "",
    val wifiGraceS: Long = 5,
    val heartbeatIntervalS: Long = 5,
    val lostAfterS: Long = 15,
    val alertOnPeerOffline: Boolean = true,
    val feishuWebhook: String = "",
    val feishuSecret: String = "",
    val deviceLabel: String = "",
    val reportEnabled: Boolean = true,
    val reportIntervalS: Long = 30,
    val awayReportMin: Long = 10,
    val onboarded: Boolean = false,
)

fun AppSettings.toGuardConfig(): GuardConfig = GuardConfig(
    role = role,
    mode = mode,
    peerName = peerName.ifBlank { peerIp },
    peerIp = peerIp,
    btAddress = btAddress,
    wifiGraceMs = wifiGraceS * 1000,
    heartbeatIntervalMs = heartbeatIntervalS * 1000,
    lostAfterMs = lostAfterS * 1000,
    alertOnPeerOffline = alertOnPeerOffline,
    feishuWebhook = feishuWebhook,
    feishuSecret = feishuSecret,
    deviceLabel = deviceLabel.ifBlank { "老人手机" },
    reportEnabled = reportEnabled,
    reportIntervalMs = reportIntervalS * 1000,
    awayReportIntervalMs = awayReportMin * 60_000,
)

class SettingsRepository(private val context: Context) {

    private val roleKey = stringPreferencesKey("role")
    private val modeKey = stringPreferencesKey("mode")
    private val peerNameKey = stringPreferencesKey("peer_name")
    private val peerIpKey = stringPreferencesKey("peer_ip")
    private val btAddressKey = stringPreferencesKey("bt_address")
    private val wifiGraceKey = longPreferencesKey("wifi_grace_s")
    private val hbIntervalKey = longPreferencesKey("hb_interval_s")
    private val lostAfterKey = longPreferencesKey("lost_after_s")
    private val alertPeerOfflineKey = booleanPreferencesKey("alert_peer_offline")
    private val webhookKey = stringPreferencesKey("feishu_webhook")
    private val secretKey = stringPreferencesKey("feishu_secret")
    private val deviceLabelKey = stringPreferencesKey("device_label")
    private val reportEnabledKey = booleanPreferencesKey("report_enabled")
    private val reportIntervalKey = longPreferencesKey("report_interval_s")
    private val awayReportKey = longPreferencesKey("away_report_min")
    private val onboardedKey = booleanPreferencesKey("onboarded")
    private val armedKey = booleanPreferencesKey("armed")

    val all: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            role = Role.fromStored(p[roleKey]),
            mode = p[modeKey]?.let { runCatching { GuardMode.valueOf(it) }.getOrNull() } ?: GuardMode.FENCE,
            peerName = p[peerNameKey] ?: "",
            peerIp = p[peerIpKey] ?: "",
            btAddress = p[btAddressKey] ?: "",
            wifiGraceS = p[wifiGraceKey] ?: 5,
            heartbeatIntervalS = p[hbIntervalKey] ?: 5,
            lostAfterS = p[lostAfterKey] ?: 15,
            alertOnPeerOffline = p[alertPeerOfflineKey] ?: true,
            feishuWebhook = p[webhookKey] ?: "",
            feishuSecret = p[secretKey] ?: "",
            deviceLabel = p[deviceLabelKey] ?: "",
            reportEnabled = p[reportEnabledKey] ?: true,
            reportIntervalS = p[reportIntervalKey] ?: 30,
            awayReportMin = p[awayReportKey] ?: 10,
            onboarded = p[onboardedKey] ?: false,
        )
    }

    suspend fun completeOnboarding(role: Role) = context.dataStore.edit {
        it[roleKey] = role.name
        it[onboardedKey] = true
    }

    suspend fun setMode(mode: GuardMode) = context.dataStore.edit { it[modeKey] = mode.name }

    /** 监护开关记忆：设备重启后由 BootCompletedReceiver 据此恢复服务。 */
    suspend fun isArmed(): Boolean =
        context.dataStore.data.first()[armedKey] ?: false

    suspend fun setArmed(v: Boolean) = context.dataStore.edit { it[armedKey] = v }

    suspend fun setPeer(name: String, ip: String) = context.dataStore.edit {
        it[peerNameKey] = name
        it[peerIpKey] = ip
    }

    suspend fun setBtAddress(address: String, name: String) = context.dataStore.edit {
        it[btAddressKey] = address
        it[peerNameKey] = name
    }

    suspend fun saveAdvanced(
        feishuWebhook: String,
        feishuSecret: String,
        deviceLabel: String,
        reportEnabled: Boolean,
        reportIntervalS: Long,
        awayReportMin: Long,
        alertOnPeerOffline: Boolean,
        wifiGraceS: Long,
        heartbeatIntervalS: Long,
        lostAfterS: Long,
    ) = context.dataStore.edit {
        it[webhookKey] = feishuWebhook
        it[secretKey] = feishuSecret
        it[deviceLabelKey] = deviceLabel
        it[reportEnabledKey] = reportEnabled
        it[reportIntervalKey] = reportIntervalS
        it[awayReportKey] = awayReportMin
        it[alertPeerOfflineKey] = alertOnPeerOffline
        it[wifiGraceKey] = wifiGraceS
        it[hbIntervalKey] = heartbeatIntervalS
        it[lostAfterKey] = lostAfterS
    }
}
