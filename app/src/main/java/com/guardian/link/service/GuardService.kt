package com.guardian.link.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.guardian.link.MainActivity
import com.guardian.link.R
import com.guardian.link.core.alarm.AlarmController
import com.guardian.link.core.bt.BtLinkManager
import com.guardian.link.core.feishu.FeishuClient
import com.guardian.link.core.locate.GeoConverter
import com.guardian.link.core.locate.Loc
import com.guardian.link.core.locate.LocationProvider
import com.guardian.link.core.wifi.HeartbeatEngine
import com.guardian.link.core.wifi.WifiMonitor
import com.guardian.link.data.AlertReason
import com.guardian.link.data.GuardConfig
import com.guardian.link.data.GuardMode
import com.guardian.link.data.GuardState
import com.guardian.link.data.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 监护前台服务：持有当前模式的状态机，链路异常→告警→恢复的编排、
 * 状态通知、飞书位置上报都在这里。两端代码相同，各自独立判定。
 */
class GuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var config: GuardConfig? = null
    private var wifiMonitor: WifiMonitor? = null
    private var heartbeat: HeartbeatEngine? = null
    private var btLink: BtLinkManager? = null
    private var engineJobs = mutableListOf<Job>()
    private var btGraceJob: Job? = null
    private var reportJob: Job? = null
    private var alertAck = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val cfg = GuardConfig.fromJson(intent.getStringExtra(EXTRA_CONFIG))
                if (cfg == null) {
                    Log.w(TAG, "bad config, stopping")
                    stopSelf()
                } else {
                    shutdownEngines()
                    config = cfg
                    alertAck = false
                    startAsForeground("正在连接对端…")
                    state.value = GuardState.Connecting(
                        if (cfg.mode == GuardMode.WIFI) "等待对端心跳" else "等待蓝牙连接"
                    )
                    beginMonitoring(cfg)
                }
            }
            ACTION_DISARM -> {
                alertAck = true
                AlarmController.stop(applicationContext)
            }
            ACTION_STOP -> {
                AlarmController.stop(applicationContext)
                shutdownEngines()
                state.value = GuardState.Idle
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            null -> {
                // START_STICKY 重启后拿不到配置，直接退出，由用户重新开启
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        shutdownEngines()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 监控编排

    private fun beginMonitoring(cfg: GuardConfig) {
        acquireWakeLock()
        // 先订阅状态联动，再启动引擎，避免错过首条事件
        engineJobs += scope.launch {
            state.collect { s ->
                updateStatusNotification(s)
                if (s is GuardState.Alerting) {
                    if (!alertAck) {
                        AlarmController.start(applicationContext, "⚠️ GuardianLink 告警", s.reason.title)
                        reportAlert(cfg, s.reason)
                    }
                } else {
                    alertAck = false
                    AlarmController.stop(applicationContext)
                }
            }
        }
        when (cfg.mode) {
            GuardMode.WIFI -> startWifiMode(cfg)
            GuardMode.BLUETOOTH -> startBtMode(cfg)
        }
        startPeriodicReport(cfg)
    }

    private fun startWifiMode(cfg: GuardConfig) {
        val peer = cfg.peerName.ifBlank { cfg.peerIp }
        val monitor = WifiMonitor(scope)
        wifiMonitor = monitor
        monitor.start(applicationContext, cfg.wifiGraceMs)
        engineJobs += scope.launch {
            monitor.lostConfirmed.collect { onLinkProblem(AlertReason.LEFT_WIFI) }
        }
        engineJobs += scope.launch {
            monitor.connected.collect { up ->
                val s = state.value
                if (up == true && s is GuardState.Alerting && s.reason == AlertReason.LEFT_WIFI) {
                    onLinkRestored(peer)
                }
            }
        }
        val hb = HeartbeatEngine(
            scope = scope,
            peerIp = cfg.peerIp,
            selfName = "${Build.MODEL}·${cfg.role.label}",
            intervalMs = cfg.heartbeatIntervalMs,
            lostAfterMs = cfg.lostAfterMs,
        )
        heartbeat = hb
        hb.start()
        engineJobs += scope.launch {
            hb.events.collect { ev ->
                when (ev) {
                    HeartbeatEngine.Event.PeerSeen -> onLinkRestored(peer)
                    HeartbeatEngine.Event.PeerLost ->
                        if (cfg.alertOnPeerOffline) onLinkProblem(AlertReason.PEER_LOST)
                }
            }
        }
    }

    private fun startBtMode(cfg: GuardConfig) {
        val peer = cfg.peerName.ifBlank { cfg.btAddress }
        val link = BtLinkManager(
            context = applicationContext,
            scope = scope,
            peerAddress = cfg.btAddress,
            isServer = cfg.role == Role.WARD,
        )
        btLink = link
        link.start()
        engineJobs += scope.launch {
            link.events.collect { ev ->
                when (ev) {
                    BtLinkManager.Event.LinkUp -> {
                        btGraceJob?.cancel()
                        btGraceJob = null
                        onLinkRestored(peer)
                    }
                    BtLinkManager.Event.LinkLost -> {
                        if (state.value is GuardState.Alerting) return@collect
                        state.value = GuardState.Degraded(peer, "蓝牙链路抖动，重连中…")
                        btGraceJob?.cancel()
                        btGraceJob = scope.launch {
                            delay(BT_GRACE_MS)
                            onLinkProblem(AlertReason.BT_LOST)
                        }
                    }
                    is BtLinkManager.Event.Info -> {
                        val s = state.value
                        if (s is GuardState.Connecting || s is GuardState.Idle) {
                            state.value = GuardState.Connecting(ev.msg)
                        }
                    }
                }
            }
        }
    }

    private fun onLinkProblem(reason: AlertReason) {
        if (state.value is GuardState.Alerting) return
        state.value = GuardState.Alerting(reason)
    }

    private fun onLinkRestored(peer: String) {
        val cur = state.value
        val since = if (cur is GuardState.Connected) cur.since else System.currentTimeMillis()
        if (cur is GuardState.Alerting) {
            val c = config
            if (c != null) scope.launch { reportRecovered(c) }
        }
        state.value = GuardState.Connected(peer, since)
    }

    // ---------------------------------------------------------------- 飞书上报

    private fun startPeriodicReport(cfg: GuardConfig) {
        if (!cfg.reportEnabled || cfg.feishuWebhook.isBlank()) return
        reportJob = scope.launch {
            val provider = LocationProvider(applicationContext)
            if (!provider.hasPermission()) return@launch
            provider.periodic(cfg.reportIntervalMs).collect { loc ->
                sendLocationReport(cfg, loc)
            }
        }
    }

    private fun reportAlert(cfg: GuardConfig, reason: AlertReason) = scope.launch {
        if (cfg.feishuWebhook.isBlank()) return@launch
        val loc = LocationProvider(applicationContext).current()
        sendFeishu(cfg, buildText("【GuardianLink 告警】⚠️ ${reason.title}", cfg, loc))
    }

    private fun reportRecovered(cfg: GuardConfig) {
        if (cfg.feishuWebhook.isBlank()) return
        sendFeishu(cfg, buildText("【GuardianLink】✅ 链路已恢复，告警解除", cfg, null))
    }

    private suspend fun sendLocationReport(cfg: GuardConfig, loc: Loc) {
        if (cfg.feishuWebhook.isBlank()) return
        val head = if (state.value is GuardState.Alerting) "【GuardianLink 告警中】⚠️ 当前位置" else "【GuardianLink】📍 定时位置播报"
        sendFeishu(cfg, buildText(head, cfg, loc))
    }

    private fun sendFeishu(cfg: GuardConfig, text: String) {
        scope.launch(Dispatchers.IO) { FeishuClient.send(cfg.feishuWebhook, text) }
    }

    private fun buildText(head: String, cfg: GuardConfig, loc: Loc?): String {
        val sb = StringBuilder()
        sb.append(head).append('\n')
        sb.append("设备: ").append(Build.MODEL).append("（").append(cfg.role.label).append("）\n")
        sb.append("时间: ").append(timeFmt().format(Date())).append('\n')
        if (loc != null) {
            val (gLat, gLng) = GeoConverter.wgs84ToGcj02(loc.lat, loc.lng)
            val acc = if (loc.accuracyM > 0) "（精度±${loc.accuracyM.toInt()}m）" else ""
            sb.append("位置: ")
                .append(String.format(Locale.US, "%.6f", gLat)).append(", ")
                .append(String.format(Locale.US, "%.6f", gLng)).append(acc).append('\n')
            sb.append("地图: https://uri.amap.com/marker?position=")
                .append(String.format(Locale.US, "%.6f", gLng)).append(",")
                .append(String.format(Locale.US, "%.6f", gLat))
                .append("&name=")
                .append(URLEncoder.encode("${Build.MODEL} 位置", "UTF-8"))
        } else {
            sb.append("位置: 暂无法获取（室内或无定位权限）")
        }
        return sb.toString()
    }

    private fun timeFmt() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    // ---------------------------------------------------------------- 通知/前台

    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "监护状态", NotificationManager.IMPORTANCE_LOW)
        )
        AlarmController.ensureChannel(this)
    }

    private fun startAsForeground(text: String) {
        val notif = buildStatusNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (config?.mode == GuardMode.BLUETOOTH) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildStatusNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle("GuardianLink")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateStatusNotification(s: GuardState) {
        val text = when (s) {
            GuardState.Idle -> "未开启监护"
            is GuardState.Connecting -> "连接中：${s.detail}"
            is GuardState.Connected -> "监护中 · ${s.peer} 连接正常"
            is GuardState.Degraded -> "链路抖动：${s.detail}"
            is GuardState.Alerting -> "⚠️ ${s.reason.title}"
        }
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildStatusNotification(text))
        }
    }

    // ---------------------------------------------------------------- 保活/清理

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GuardianLink:monitor").apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L)
            }
        }.getOrNull()
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun shutdownEngines() {
        engineJobs.forEach { it.cancel() }
        engineJobs.clear()
        btGraceJob?.cancel(); btGraceJob = null
        reportJob?.cancel(); reportJob = null
        wifiMonitor?.stop(); wifiMonitor = null
        heartbeat?.stop(); heartbeat = null
        btLink?.stop(); btLink = null
        releaseWakeLock()
        AlarmController.stop(applicationContext)
    }

    companion object {
        private const val TAG = "GuardService"
        private const val CHANNEL_STATUS = "guard_status"
        private const val NOTIF_ID = 1001
        private const val BT_GRACE_MS = 10_000L

        const val ACTION_START = "com.guardian.link.START"
        const val ACTION_STOP = "com.guardian.link.STOP"
        const val ACTION_DISARM = "com.guardian.link.DISARM"
        const val EXTRA_CONFIG = "config"

        /** 当前监护状态，UI 直接收集（同进程）。 */
        val state = MutableStateFlow<GuardState>(GuardState.Idle)

        fun start(context: Context, config: GuardConfig) {
            val intent = Intent(context, GuardService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, config.toJson())
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, GuardService::class.java).setAction(ACTION_STOP)
            )
        }

        fun disarm(context: Context) {
            context.startService(
                Intent(context, GuardService::class.java).setAction(ACTION_DISARM)
            )
        }
    }
}
