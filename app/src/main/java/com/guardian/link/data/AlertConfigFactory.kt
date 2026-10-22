package com.guardian.link.data

import android.content.Context
import com.guardian.link.core.alert.AlertConfig
import kotlinx.coroutines.flow.first
import java.io.File

/** 由设置+围栏配置+配对存储组装发送所需配置（服务与 Worker 共用）。 */
suspend fun buildAlertConfig(context: Context): AlertConfig {
    val s = SettingsRepository(context).all.first()
    val fence = FenceConfigRepository(context).config.first()
    val pairing = PairingStore(File(context.filesDir, "pairing.json"))
    return AlertConfig(
        webhook = s.feishuWebhook,
        secret = s.feishuSecret,
        deviceLabel = s.deviceLabel.ifBlank { "老人手机" },
        deviceId = pairing.deviceId(),
        fenceRadiusM = if (fence.configured) fence.radiusM else 0,
        awayReportIntervalMs = s.awayReportMin * 60_000,
    )
}
