package com.guardian.link.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "guardian_settings")

data class AppSettings(
    val role: Role = Role.WARD,
    val mode: GuardMode = GuardMode.WIFI,
    val peerName: String = "",
    val peerIp: String = "",
    val btAddress: String = "",
    val wifiGraceS: Long = 5,
    val heartbeatIntervalS: Long = 5,
    val lostAfterS: Long = 15,
    val alertOnPeerOffline: Boolean = true,
    val feishuWebhook: String = "",
    val reportEnabled: Boolean = true,
    val reportIntervalS: Long = 30,
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
    reportEnabled = reportEnabled,
    reportIntervalMs = reportIntervalS * 1000,
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
    private val reportEnabledKey = booleanPreferencesKey("report_enabled")
    private val reportIntervalKey = longPreferencesKey("report_interval_s")
    private val onboardedKey = booleanPreferencesKey("onboarded")

    val all: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            role = p[roleKey]?.let { runCatching { Role.valueOf(it) }.getOrNull() } ?: Role.WARD,
            mode = p[modeKey]?.let { runCatching { GuardMode.valueOf(it) }.getOrNull() } ?: GuardMode.WIFI,
            peerName = p[peerNameKey] ?: "",
            peerIp = p[peerIpKey] ?: "",
            btAddress = p[btAddressKey] ?: "",
            wifiGraceS = p[wifiGraceKey] ?: 5,
            heartbeatIntervalS = p[hbIntervalKey] ?: 5,
            lostAfterS = p[lostAfterKey] ?: 15,
            alertOnPeerOffline = p[alertPeerOfflineKey] ?: true,
            feishuWebhook = p[webhookKey] ?: "",
            reportEnabled = p[reportEnabledKey] ?: true,
            reportIntervalS = p[reportIntervalKey] ?: 30,
            onboarded = p[onboardedKey] ?: false,
        )
    }

    suspend fun completeOnboarding(role: Role) = context.dataStore.edit {
        it[roleKey] = role.name
        it[onboardedKey] = true
    }

    suspend fun setMode(mode: GuardMode) = context.dataStore.edit {
        it[modeKey] = mode.name
    }

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
        reportEnabled: Boolean,
        reportIntervalS: Long,
        alertOnPeerOffline: Boolean,
        wifiGraceS: Long,
        heartbeatIntervalS: Long,
        lostAfterS: Long,
    ) = context.dataStore.edit {
        it[webhookKey] = feishuWebhook
        it[reportEnabledKey] = reportEnabled
        it[reportIntervalKey] = reportIntervalS
        it[alertPeerOfflineKey] = alertOnPeerOffline
        it[wifiGraceKey] = wifiGraceS
        it[hbIntervalKey] = heartbeatIntervalS
        it[lostAfterKey] = lostAfterS
    }
}
