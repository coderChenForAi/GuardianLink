package com.guardian.link.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.guardian.link.MainActivity
import com.guardian.link.R
import com.guardian.link.core.alarm.AlarmController
import com.guardian.link.core.alert.AlertConfig
import com.guardian.link.core.alert.AlertDispatcher
import com.guardian.link.core.bt.BtLinkManager
import com.guardian.link.core.feishu.FeishuResult
import com.guardian.link.core.geo.FenceDebouncer
import com.guardian.link.core.geo.FenceSignal
import com.guardian.link.core.locate.AMapLocationSource
import com.guardian.link.core.locate.Loc
import com.guardian.link.core.state.GuardianState
import com.guardian.link.core.state.GuardianStateMachine
import com.guardian.link.core.state.GuardianTransition
import com.guardian.link.core.sync.LanSyncEngine
import com.guardian.link.core.wifi.HeartbeatEngine
import com.guardian.link.core.wifi.PeerDiscoverer
import com.guardian.link.core.wifi.WifiMonitor
import com.guardian.link.data.EventStore
import com.guardian.link.data.EventType
import com.guardian.link.data.FenceConfig
import com.guardian.link.data.FenceConfigRepository
import com.guardian.link.data.GuardConfig
import com.guardian.link.data.GuardMode
import com.guardian.link.data.GuardState
import com.guardian.link.data.GuardianEntry
import com.guardian.link.data.GuardianEvent
import com.guardian.link.data.PairingStore
import com.guardian.link.data.Role
import com.guardian.link.data.SettingsRepository
import com.guardian.link.data.StateStore
import com.guardian.link.data.buildAlertConfig
import com.guardian.link.data.toGuardConfig
import com.guardian.link.work.FeishuRetryWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 监护前台服务。三种模式：
 *  FENCE     —— 核心：老人本地地理围栏监护（自研去抖引擎 + 监护状态机 + 飞书告警 + 局域网推送）；
 *               监护人设备则运行局域网接收端。
 *  WIFI/BT   —— 遗留的"设备连接监护"（辅助信号，不代表老人是否在家）。
 */
class GuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var config: GuardConfig? = null

    // 遗留连接监护
    private var wifiMonitor: WifiMonitor? = null
    private var heartbeat: HeartbeatEngine? = null
    private var btLink: BtLinkManager? = null
    private var btGraceJob: Job? = null
    private var reportJob: Job? = null
    private var alertAck = false

    // 围栏监护
    private var fenceJob: Job? = null
    private var healthJob: Job? = null
    private var lanSync: LanSyncEngine? = null
    private var locationSource: AMapLocationSource? = null
    private val machine = GuardianStateMachine()
    private var alertConfig: AlertConfig? = null
    private var dispatcher: AlertDispatcher? = null
    private var lastFix: Loc? = null
    private var lastFeishuOkAt = 0L
    private var lastSyncAt = 0L

    private var engineJobs = mutableListOf<Job>()
    private var wakeLock: PowerManager.WakeLock? = null

    private val stateStore by lazy { StateStore(File(filesDir, "guardian_state.json")) }
    private val eventStore by lazy { EventStore(File(filesDir, "guardian_events.json")) }
    private val receivedEventStore by lazy { EventStore(File(filesDir, "guardian_received_events.json")) }
    private val pairingStore by lazy { PairingStore(File(filesDir, "pairing.json")) }

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
                    startAsForeground(initialStatusText(cfg))
                    scope.launch { runCatching { SettingsRepository(applicationContext).setArmed(true) } }
                    beginMonitoring(cfg)
                }
            }
            ACTION_RELOAD_FENCE -> {
                if (config?.mode == GuardMode.FENCE && config?.role == Role.ELDER_DEVICE) {
                    Log.i(TAG, "reload fence config")
                    shutdownEngines()
                    config?.let { beginMonitoring(it) }
                }
            }
            ACTION_DISARM -> {
                alertAck = true
                AlarmController.stop(applicationContext)
            }
            ACTION_STOP -> {
                AlarmController.stop(applicationContext)
                shutdownEngines()
                scope.launch { runCatching { SettingsRepository(applicationContext).setArmed(false) } }
                state.value = GuardState.Idle
                fenceState.value = FenceUiState()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            null -> {
                // START_STICKY 重启后拿不到配置：若此前处于开启状态则按存储配置恢复
                val settings = SettingsRepository(applicationContext)
                val armed = runCatching { runBlocking { settings.isArmed() } }.getOrDefault(false)
                if (armed) {
                    val cfg = runCatching { runBlocking { settings.all.first().toGuardConfig() } }.getOrNull()
                    if (cfg != null) {
                        config = cfg
                        startAsForeground(initialStatusText(cfg))
                        beginMonitoring(cfg)
                        return START_STICKY
                    }
                }
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

    // ---------------------------------------------------------------- 编排

    private fun beginMonitoring(cfg: GuardConfig) {
        acquireWakeLock()
        scope.launch {
            alertConfig = runCatching { buildAlertConfig(applicationContext) }.getOrNull()
        }
        dispatcher = AlertDispatcher(
            store = eventStore,
            configProvider = {
                alertConfig ?: AlertConfig(
                    webhook = "",
                    secret = "",
                    deviceLabel = cfg.deviceLabel,
                    deviceId = pairingStore.deviceId(),
                    fenceRadiusM = 0,
                    awayReportIntervalMs = cfg.awayReportIntervalMs,
                )
            },
            scope = scope,
            onSendFinished = { _, result ->
                if (result is FeishuResult.Success) {
                    lastFeishuOkAt = System.currentTimeMillis()
                }
                refreshHealth()
            },
            scheduleRetry = { FeishuRetryWorker.schedule(applicationContext) },
        )
        engineJobs += scope.launch {
            state.collect { s ->
                updateStatusNotification(s)
                if (s is GuardState.Alerting) {
                    if (!alertAck && cfg.mode != GuardMode.FENCE) {
                        AlarmController.start(applicationContext, "⚠️ GuardianLink 告警", s.reason.title)
                    }
                } else if (cfg.mode != GuardMode.FENCE) {
                    alertAck = false
                    AlarmController.stop(applicationContext)
                }
            }
        }
        when (cfg.mode) {
            GuardMode.FENCE ->
                if (cfg.role == Role.ELDER_DEVICE) startElderFence(cfg) else startGuardianSync(cfg)
            GuardMode.WIFI -> startWifiMode(cfg)
            GuardMode.BLUETOOTH -> startBtMode(cfg)
        }
        healthJob = scope.launch {
            while (isActive) {
                refreshHealth()
                delay(HEALTH_TICK_MS)
            }
        }
    }

    // ---------------------------------------------------------------- 老人端：围栏监护

    private fun startElderFence(cfg: GuardConfig) {
        val fenceRepo = FenceConfigRepository(applicationContext)
        fenceJob = scope.launch {
            val fence = fenceRepo.config.first()
            if (!fence.configured) {
                fenceState.value = FenceUiState(
                    fenceConfigured = false, monitorRunning = true,
                    fenceSummary = "未设置家庭安全区域，监护未生效",
                )
                state.value = GuardState.Connecting("请先设置家庭安全区域")
                return@launch
            }
            // 恢复上次状态：进行中的离家不重复告警，只补一条位置更新
            stateStore.load()?.let { (s, ep, bs) ->
                machine.restore(parseState(s) ?: GuardianState.HOME_SAFE, ep, bs?.let(::parseState))
            }
            val resumedAway = machine.state == GuardianState.AWAY_ALERTED ||
                machine.state == GuardianState.AWAY_TRACKING
            publishFence(fence, machine)
            refreshEvents()

            val dispatcher0 = dispatcher ?: return@launch
            locationSource = AMapLocationSource(applicationContext)
            val source = locationSource!!
            if (!source.hasFinePermission()) {
                fenceState.value = fenceState.value.copy(
                    fenceSummary = "缺少精确定位权限，围栏监测未生效",
                )
                state.value = GuardState.Connecting("缺少定位权限，请在系统设置中允许精确定位")
                return@launch
            }
            lanSync = LanSyncEngine(
                scope = scope,
                pairing = pairingStore,
                selfRole = Role.ELDER_DEVICE,
                selfName = cfg.deviceLabel,
                elderEventStore = eventStore,
                guardianEventStore = null,
                onGuardianPaired = { refreshPairingInfo() },
                onEventAcked = { _, _ -> refreshEvents() },
                onGuardianSeen = {
                    lastSyncAt = System.currentTimeMillis()
                    refreshHealth()
                },
            ).also { it.start() }
            refreshPairingInfo()

            val debouncer = FenceDebouncer()
            var resumeNotified = resumedAway

            source.fixes(FIX_INTERVAL_MS, onError = { code, msg ->
                Log.w(TAG, "locate error code=$code msg=$msg")
            }).collect { fix ->
                val now = System.currentTimeMillis()
                lastFix = fix
                machine.onFreshFix(now)
                val signals = debouncer.onFix(fix, fence.centerLat, fence.centerLng, fence.radiusM.toDouble())
                for (sig in signals) {
                    when (sig) {
                        is FenceSignal.RawOutside -> machine.onRawOutside(now)
                        is FenceSignal.RawInside -> machine.onRawInside(now)
                        is FenceSignal.ConfirmedExit -> {
                            for (t in machine.onExitConfirmed(now)) {
                                if (t is GuardianTransition.ExitConfirmed) {
                                    alertAck = false
                                    val ev = dispatcher0.exitConfirmed(t.episodeId, fix, now)
                                    lanSync?.pushEvent(ev)
                                    AlarmController.start(
                                        applicationContext,
                                        "⚠️ 已离开家庭安全区域",
                                        "老人手机检测到离家，请家属留意飞书通知"
                                    )
                                }
                            }
                        }
                        is FenceSignal.ConfirmedEnter -> {
                            for (t in machine.onReturnConfirmed(now)) {
                                if (t is GuardianTransition.ReturnConfirmed) {
                                    val ev = dispatcher0.returnConfirmed(t.episodeId, fix, now)
                                    lanSync?.pushEvent(ev)
                                    AlarmController.stop(applicationContext)
                                }
                            }
                        }
                    }
                }
                val ep = machine.episodeId
                if (ep != null && machine.state != GuardianState.HOME_SAFE) {
                    dispatcher0.awayLocationUpdate(ep, fix, now, force = !resumeNotified)
                        ?.let { lanSync?.pushEvent(it) }
                    resumeNotified = true
                }
                persistMachine()
                publishFence(fence, machine)
                refreshHealth()
            }
        }
        // 定位失效看门狗：超过阈值没有任何有效定位 → LOCATION_STALE + 一次告警
        engineJobs += scope.launch {
            while (isActive) {
                delay(STALE_TICK_MS)
                val now = System.currentTimeMillis()
                if (machine.state == GuardianState.LOCATION_STALE) continue
                val ref = lastFix?.time ?: 0
                if (ref in 1..(now - STALE_AFTER_MS)) {
                    val ts = machine.onStale(now)
                    if (ts.isNotEmpty()) {
                        dispatcher?.locationStale(machine.episodeId, now)?.let { lanSync?.pushEvent(it) }
                        persistMachine()
                        lastFenceConfig?.let { publishFence(it, machine) }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 监护人端：局域网接收

    private fun startGuardianSync(cfg: GuardConfig) {
        lanSync = LanSyncEngine(
            scope = scope,
            pairing = pairingStore,
            selfRole = Role.GUARDIAN_DEVICE,
            selfName = Build.MODEL,
            elderEventStore = null,
            guardianEventStore = receivedEventStore,
            onEventReceived = { ev ->
                refreshEvents()
                when (ev.type) {
                    EventType.EXIT_HOME -> {
                        alertAck = false
                        AlarmController.start(
                            applicationContext,
                            "⚠️ 老人已离开家庭安全区域",
                            "来自老人设备（${ev.sourceDeviceId}）的推送"
                        )
                    }
                    EventType.RETURN_HOME -> AlarmController.stop(applicationContext)
                    else -> Unit
                }
                state.value = GuardState.Connected(
                    pairingStore.elder()?.name?.ifBlank { "老人设备" } ?: "老人设备",
                    System.currentTimeMillis()
                )
            },
        ).also { it.start() }
        state.value = GuardState.Connecting("等待老人设备上线（同一 Wi-Fi）")
        refreshEvents()
    }

    // ---------------------------------------------------------------- 遗留：连接监护（WiFi/蓝牙）

    private fun startWifiMode(cfg: GuardConfig) {
        val peer = cfg.peerName.ifBlank { cfg.peerIp }
        val monitor = WifiMonitor(scope)
        wifiMonitor = monitor
        monitor.start(applicationContext, cfg.wifiGraceMs)
        engineJobs += scope.launch {
            monitor.lostConfirmed.collect { onLinkProblem(AlertReason_LEFT_WIFI) }
        }
        engineJobs += scope.launch {
            monitor.connected.collect { up ->
                val s = state.value
                if (up == true && s is GuardState.Alerting && s.reason == AlertReason_LEFT_WIFI) {
                    onLinkRestored(peer)
                }
            }
        }
        val selfName = "${Build.MODEL}·${cfg.role.label}"
        val hb = HeartbeatEngine(
            scope = scope,
            peerIp = cfg.peerIp,
            selfName = selfName,
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
                        if (cfg.alertOnPeerOffline) onLinkProblem(AlertReason_PEER_LOST)
                    is HeartbeatEngine.Event.ConnectTimeout ->
                        if (state.value !is GuardState.Alerting && state.value !is GuardState.Connected) {
                            state.value = GuardState.Degraded(
                                peer,
                                "启动 ${ev.waitedMs / 60000} 分钟仍未收到对端心跳；请确认对端已开始监护、绑定 IP 有效且路由器未开 AP 隔离",
                            )
                        }
                }
            }
        }
        // 对端 IP 失效自愈：断连期间低频广播重新发现对端并刷新地址（对端换 IP / 绑定填错都能自动纠正）
        engineJobs += scope.launch {
            while (isActive) {
                delay(PEER_REFRESH_MS)
                if (hb.lastSeenAgoMs() < cfg.lostAfterMs) continue // 连接正常，无需刷新
                val found = runCatching { PeerDiscoverer.discover(PEER_DISCOVER_TIMEOUT_MS) }
                    .getOrDefault(emptyList())
                    .filter { it.name != selfName } // 排除本机回环应答
                val match = found.firstOrNull { it.name == peer }
                    ?: found.singleOrNull()?.takeIf { cfg.peerName.isBlank() }
                if (match != null && match.ip != hb.currentPeerIp) {
                    Log.i(TAG, "peer ip refreshed: ${hb.currentPeerIp} -> ${match.ip} (${match.name})")
                    hb.updatePeerIp(match.ip)
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
            isServer = cfg.role == Role.ELDER_DEVICE,
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
                            onLinkProblem(AlertReason_BT_LOST)
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

    private fun onLinkProblem(reason: com.guardian.link.data.AlertReason) {
        if (state.value is GuardState.Alerting) return
        state.value = GuardState.Alerting(reason)
    }

    private fun onLinkRestored(peer: String) {
        val cur = state.value
        val since = if (cur is GuardState.Connected) cur.since else System.currentTimeMillis()
        state.value = GuardState.Connected(peer, since)
    }

    // ---------------------------------------------------------------- 状态/健康/持久化

    private fun parseState(s: String): GuardianState? =
        runCatching { GuardianState.valueOf(s) }.getOrNull()

    private fun persistMachine() {
        val snap = machine.snapshot()
        stateStore.save(snap.state.name, snap.episodeId, snap.beforeStale?.name)
    }

    private var lastFenceConfig: FenceConfig? = null

    private fun publishFence(fence: FenceConfig, m: GuardianStateMachine) {
        lastFenceConfig = fence
        fenceState.value = FenceUiState(
            machineState = m.state,
            episodeId = m.episodeId,
            lastFixAt = lastFix?.time ?: 0,
            lastFixLat = lastFix?.lat ?: 0.0,
            lastFixLng = lastFix?.lng ?: 0.0,
            lastFixAccuracy = lastFix?.accuracyM ?: 0f,
            fenceConfigured = true,
            fenceSummary = "中心 ${"%.5f".format(fence.centerLat)},${"%.5f".format(fence.centerLng)} · 半径 ${fence.radiusM}m · 配置v${fence.version}",
            monitorRunning = true,
        )
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildStatusNotification(fenceStateText(m.state)))
        }
    }

    private fun refreshEvents() {
        val store = if (config?.role == Role.GUARDIAN_DEVICE) receivedEventStore else eventStore
        recentEvents.value = store.recent(30)
    }

    private fun refreshPairingInfo() {
        guardiansFlow.value = pairingStore.guardians()
        pairingCodeFlow.value = pairingStore.pairingCode()
    }

    private fun refreshHealth() {
        val ctx = applicationContext
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locationEnabled = runCatching {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        val fine = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
            .getOrDefault(-1)
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkOk = cm.activeNetwork != null
        health.value = HealthSnapshot(
            monitoring = true,
            hasFineLocation = fine,
            locationEnabled = locationEnabled,
            feishuConfigured = !alertConfig?.webhook.isNullOrBlank(),
            networkOk = networkOk,
            batteryPct = battery,
            lastFixAt = lastFix?.time ?: 0,
            lastFeishuOkAt = lastFeishuOkAt,
            lastSyncAt = lastSyncAt,
            updatedAt = System.currentTimeMillis(),
        )
    }

    // ---------------------------------------------------------------- 前台/通知

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
            val type = when {
                config?.mode == GuardMode.BLUETOOTH -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                config?.mode == GuardMode.FENCE && config?.role == Role.GUARDIAN_DEVICE ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
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
            is GuardState.Degraded -> "链路异常：${s.detail}"
            is GuardState.Alerting -> "⚠️ ${s.reason.title}"
        }
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildStatusNotification(text))
        }
    }

    private fun initialStatusText(cfg: GuardConfig): String = when (cfg.mode) {
        GuardMode.FENCE -> if (cfg.role == Role.ELDER_DEVICE) "家庭围栏监护启动中…" else "守护接收端启动中…"
        else -> "正在连接对端…"
    }

    private fun fenceStateText(s: GuardianState): String = when (s) {
        GuardianState.HOME_SAFE -> "在家 · 围栏监护中"
        GuardianState.EXIT_SUSPECTED -> "疑似离家，确认中…"
        GuardianState.AWAY_ALERTED -> "⚠️ 已离家 · 已发出告警"
        GuardianState.AWAY_TRACKING -> "已离家 · 持续监护"
        GuardianState.RETURN_SUSPECTED -> "疑似返家，确认中…"
        GuardianState.LOCATION_STALE -> "⚠ 定位不可用 · 监护存在盲区"
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
        fenceJob?.cancel(); fenceJob = null
        healthJob?.cancel(); healthJob = null
        wifiMonitor?.stop(); wifiMonitor = null
        heartbeat?.stop(); heartbeat = null
        btLink?.stop(); btLink = null
        lanSync?.stop(); lanSync = null
        locationSource = null
        dispatcher = null
        releaseWakeLock()
        AlarmController.stop(applicationContext)
    }

    companion object {
        private const val TAG = "GuardService"
        private const val CHANNEL_STATUS = "guard_status"
        private const val NOTIF_ID = 1001
        private const val BT_GRACE_MS = 10_000L
        private const val FIX_INTERVAL_MS = 15_000L
        private const val STALE_AFTER_MS = 10 * 60_000L
        private const val STALE_TICK_MS = 60_000L
        private const val HEALTH_TICK_MS = 30_000L
        private const val PEER_REFRESH_MS = 60_000L
        private const val PEER_DISCOVER_TIMEOUT_MS = 3_000L

        const val ACTION_START = "com.guardian.link.START"
        const val ACTION_STOP = "com.guardian.link.STOP"
        const val ACTION_DISARM = "com.guardian.link.DISARM"
        const val ACTION_RELOAD_FENCE = "com.guardian.link.RELOAD_FENCE"
        const val EXTRA_CONFIG = "config"

        private val AlertReason_LEFT_WIFI = com.guardian.link.data.AlertReason.LEFT_WIFI
        private val AlertReason_PEER_LOST = com.guardian.link.data.AlertReason.PEER_LOST
        private val AlertReason_BT_LOST = com.guardian.link.data.AlertReason.BT_LOST

        /** 遗留连接监护状态。 */
        val state = MutableStateFlow<GuardState>(GuardState.Idle)

        /** 围栏监护状态（老人端）。 */
        data class FenceUiState(
            val machineState: GuardianState? = null,
            val episodeId: String? = null,
            val lastFixAt: Long = 0,
            val lastFixLat: Double = 0.0,
            val lastFixLng: Double = 0.0,
            val lastFixAccuracy: Float = 0f,
            val fenceConfigured: Boolean = false,
            val fenceSummary: String = "",
            val monitorRunning: Boolean = false,
        )

        val fenceState = MutableStateFlow(FenceUiState())

        /** 设备健康快照。注意：设备在线 ≠ 老人在家，两者是不同维度。 */
        data class HealthSnapshot(
            val monitoring: Boolean = false,
            val hasFineLocation: Boolean = false,
            val locationEnabled: Boolean = false,
            val feishuConfigured: Boolean = false,
            val networkOk: Boolean = false,
            val batteryPct: Int = -1,
            val lastFixAt: Long = 0,
            val lastFeishuOkAt: Long = 0,
            val lastSyncAt: Long = 0,
            val updatedAt: Long = 0,
        )

        val health = MutableStateFlow(HealthSnapshot())

        /** 最近事件（老人端=已发送事件；监护人端=已接收事件）。 */
        val recentEvents = MutableStateFlow<List<GuardianEvent>>(emptyList())
        val guardiansFlow = MutableStateFlow<List<GuardianEntry>>(emptyList())
        val pairingCodeFlow = MutableStateFlow("")

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

        fun reloadFence(context: Context) {
            context.startService(
                Intent(context, GuardService::class.java).setAction(ACTION_RELOAD_FENCE)
            )
        }
    }
}
