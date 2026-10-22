package com.guardian.link.core.alert

import com.guardian.link.core.feishu.FeishuClient
import com.guardian.link.core.feishu.FeishuResult
import com.guardian.link.core.locate.CoordSystem
import com.guardian.link.core.locate.GeoConverter
import com.guardian.link.core.locate.Loc
import com.guardian.link.data.EventStore
import com.guardian.link.data.EventType
import com.guardian.link.data.GuardianEvent
import com.guardian.link.data.SendStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 发送所需的运行时配置快照。 */
data class AlertConfig(
    val webhook: String,
    val secret: String,
    val deviceLabel: String,
    val deviceId: String,
    val fenceRadiusM: Int,
    val awayReportIntervalMs: Long,
)

/**
 * 事件分发：先持久化、再发送；成功/可重试/永久失败分别处理；
 * 离家期间位置更新按可配置间隔节流；事件 ID 幂等，重试不产生新事件。
 */
class AlertDispatcher(
    private val store: EventStore,
    private val configProvider: () -> AlertConfig,
    private val scope: CoroutineScope,
    private val onSendFinished: ((GuardianEvent, FeishuResult) -> Unit)? = null,
    private val scheduleRetry: () -> Unit = {},
) {

    private val lastUpdateAt = HashMap<String, Long>()

    fun fenceSummaryText(radiusM: Int): String = "家庭安全区域半径 ${radiusM} 米"

    // ---------------------------------------------------------------- 事件入口

    fun exitConfirmed(episodeId: String, fix: Loc?, now: Long): GuardianEvent {
        val event = GuardianEvent(
            id = UUID.randomUUID().toString(),
            type = EventType.EXIT_HOME,
            occurredAt = now,
            locatedAt = fix?.time,
            lat = fix?.lat,
            lng = fix?.lng,
            accuracyM = fix?.accuracyM,
            coord = fix?.coord?.name,
            sourceDeviceId = configProvider().deviceId,
            awayEpisodeId = episodeId,
        )
        dispatch(event)
        return event
    }

    fun returnConfirmed(episodeId: String, fix: Loc?, now: Long): GuardianEvent {
        val event = GuardianEvent(
            id = UUID.randomUUID().toString(),
            type = EventType.RETURN_HOME,
            occurredAt = now,
            locatedAt = fix?.time,
            lat = fix?.lat,
            lng = fix?.lng,
            accuracyM = fix?.accuracyM,
            coord = fix?.coord?.name,
            sourceDeviceId = configProvider().deviceId,
            awayEpisodeId = episodeId,
        )
        dispatch(event)
        lastUpdateAt.remove(episodeId)
        return event
    }

    fun locationStale(episodeId: String?, now: Long): GuardianEvent {
        val event = GuardianEvent(
            id = UUID.randomUUID().toString(),
            type = EventType.LOCATION_STALE,
            occurredAt = now,
            sourceDeviceId = configProvider().deviceId,
            awayEpisodeId = episodeId,
            note = "定位长时间不可用，监护可能出现盲区",
        )
        dispatch(event)
        return event
    }

    /** 离家期间的位置更新：按 [AlertConfig.awayReportIntervalMs] 节流。 */
    fun awayLocationUpdate(episodeId: String, fix: Loc, now: Long, force: Boolean = false): GuardianEvent? {
        val interval = configProvider().awayReportIntervalMs
        val last = lastUpdateAt[episodeId] ?: 0
        if (!force && now - last < interval) return null
        lastUpdateAt[episodeId] = now
        val event = GuardianEvent(
            id = UUID.randomUUID().toString(),
            type = EventType.LOCATION_UPDATE,
            occurredAt = now,
            locatedAt = fix.time,
            lat = fix.lat,
            lng = fix.lng,
            accuracyM = fix.accuracyM,
            coord = fix.coord.name,
            sourceDeviceId = configProvider().deviceId,
            awayEpisodeId = episodeId,
        )
        dispatch(event)
        return event
    }

    // ---------------------------------------------------------------- 发送

    fun dispatch(event: GuardianEvent) {
        // 先持久化再发送，避免崩溃/断网导致事件丢失
        store.upsert(event.copy(sendStatus = SendStatus.PENDING))
        scope.launch(Dispatchers.IO) { sendOne(event.id) }
    }

    /** 立即重发所有待发送/可重试事件，返回仍未成功的数量（供 WorkManager 决定是否继续退避）。 */
    fun retryPending(): Int {
        val pending = store.pending()
        for (e in pending) {
            sendOne(e.id)
        }
        return store.pending().size
    }

    private fun sendOne(id: String) {
        val event = store.find(id) ?: return
        if (event.sendStatus == SendStatus.SENT) return
        val cfg = configProvider()
        val result = FeishuClient.send(cfg.webhook, cfg.secret, buildMessage(event, cfg))
        val updated = when (result) {
            is FeishuResult.Success -> store.markSent(id, System.currentTimeMillis())
            is FeishuResult.Retriable -> {
                store.markFailed(id, retryable = true, error = result.reason)
                scheduleRetry()
                null
            }
            is FeishuResult.Permanent -> store.markFailed(id, retryable = false, error = result.reason)
        }
        updated?.let { onSendFinished?.invoke(it, result) } ?: onSendFinished?.invoke(event, result)
    }

    // ---------------------------------------------------------------- 消息构造

    fun buildMessage(e: GuardianEvent, cfg: AlertConfig): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        val sb = StringBuilder()
        val title = when (e.type) {
            EventType.EXIT_HOME -> "【GuardianLink 告警】⚠️ 老人已离开家庭安全区域"
            EventType.RETURN_HOME -> "【GuardianLink】✅ 老人已回到家庭安全区域"
            EventType.LOCATION_UPDATE -> "【GuardianLink】📍 离家期间位置更新"
            EventType.LOCATION_STALE -> "【GuardianLink 提醒】⚠️ 定位长时间不可用"
            EventType.DEVICE_ALERT -> "【GuardianLink 提醒】设备异常"
        }
        sb.append(title).append('\n')
        sb.append("对象: ").append(cfg.deviceLabel).append("（").append(e.sourceDeviceId).append("）\n")
        sb.append("事件时间: ").append(fmt.format(Date(e.occurredAt))).append('\n')
        if (e.locatedAt != null) {
            sb.append("定位时间: ").append(fmt.format(Date(e.locatedAt)))
            val staleMs = e.occurredAt - e.locatedAt
            if (staleMs > STALE_WARN_MS) sb.append("（⚠ 已过期 ").append(staleMs / 60000).append(" 分钟）")
            sb.append('\n')
        }
        if (e.lat != null && e.lng != null) {
            val (gLat, gLng) = if (e.coord == CoordSystem.WGS84.name) {
                GeoConverter.wgs84ToGcj02(e.lat, e.lng)
            } else {
                e.lat to e.lng
            }
            val acc = e.accuracyM?.takeIf { it > 0 }?.let { "（精度±${it.toInt()}m）" } ?: ""
            sb.append("坐标: ")
                .append(String.format(Locale.US, "%.6f", gLat)).append(", ")
                .append(String.format(Locale.US, "%.6f", gLng))
                .append(acc).append("（GCJ-02）\n")
            sb.append("地图: ").append(markerUri(gLng, gLat, cfg.deviceLabel)).append('\n')
        } else if (e.type == EventType.LOCATION_STALE) {
            sb.append("位置: 暂无法获取（室内/权限受限/信号差）\n")
        }
        if (e.type == EventType.EXIT_HOME || e.type == EventType.RETURN_HOME) {
            sb.append(fenceSummaryText(cfg.fenceRadiusM)).append('\n')
        }
        if (e.awayEpisodeId != null) sb.append("离家事件ID: ").append(e.awayEpisodeId).append('\n')
        sb.append("事件ID: ").append(e.id).append('\n')
        if (e.note != null) sb.append("备注: ").append(e.note).append('\n')
        return sb.toString().trimEnd()
    }

    companion object {
        private const val STALE_WARN_MS = 10 * 60_000L
    }
}

/** 高德 URI API 的 marker 链接（入参必须是 GCJ-02 坐标）。 */
internal fun markerUri(lng: Double, lat: Double, name: String): String =
    "https://uri.amap.com/marker?position=" +
        String.format(Locale.US, "%.6f", lng) + "," + String.format(Locale.US, "%.6f", lat) +
        "&name=" + URLEncoder.encode(name.ifBlank { "位置" }, "UTF-8")
