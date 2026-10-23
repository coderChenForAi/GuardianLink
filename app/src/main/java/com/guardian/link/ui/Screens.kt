package com.guardian.link.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.guardian.link.core.feishu.FeishuClient
import com.guardian.link.core.locate.AMapLocationSource
import com.guardian.link.core.sync.LanSyncEngine
import com.guardian.link.core.wifi.PeerDiscoverer
import com.guardian.link.data.AppSettings
import com.guardian.link.data.ElderEntry
import com.guardian.link.data.EventType
import com.guardian.link.data.FenceConfigRepository
import com.guardian.link.data.GuardMode
import com.guardian.link.data.GuardState
import com.guardian.link.data.GuardianEvent
import com.guardian.link.data.PairingStore
import com.guardian.link.data.Role
import com.guardian.link.data.SendStatus
import com.guardian.link.data.SettingsRepository
import com.guardian.link.data.toGuardConfig
import com.guardian.link.service.GuardService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun statusText(state: GuardState): String = when (state) {
    GuardState.Idle -> "未开启监护"
    is GuardState.Connecting -> if (state.detail.isBlank()) "连接中…" else "连接中：${state.detail}"
    is GuardState.Connected -> "监护中 · ${state.peer} 连接正常"
    is GuardState.Degraded -> "链路异常：${state.detail}"
    is GuardState.Alerting -> "⚠️ ${state.reason.title}"
}

private fun startGuard(context: Context, s: AppSettings) {
    if (s.mode == GuardMode.WIFI && s.peerIp.isBlank()) {
        toast(context, "请先绑定对端（扫描或手动填 IP）")
        return
    }
    if (s.mode == GuardMode.BLUETOOTH && s.btAddress.isBlank()) {
        toast(context, "请先选择已配对的蓝牙设备")
        return
    }
    GuardService.start(context, s.toGuardConfig())
}

private fun eventTypeLabel(t: EventType): String = when (t) {
    EventType.EXIT_HOME -> "⚠️ 离家"
    EventType.RETURN_HOME -> "✅ 返家"
    EventType.LOCATION_UPDATE -> "📍 位置更新"
    EventType.LOCATION_STALE -> "📵 定位异常"
    EventType.DEVICE_ALERT -> "🔧 设备异常"
}

private fun sendStatusLabel(e: GuardianEvent): String = when (e.sendStatus) {
    SendStatus.NONE -> if (e.ackedBy.isEmpty()) "本地记录" else "已确认×${e.ackedBy.size}"
    SendStatus.PENDING -> "待发送"
    SendStatus.SENT -> "已发送飞书" + if (e.ackedBy.isEmpty()) "" else " · 已确认×${e.ackedBy.size}"
    SendStatus.FAILED_RETRIABLE -> "发送失败(将重试)"
    SendStatus.FAILED_PERMANENT -> "发送失败(需检查配置)"
}

// ---------------------------------------------------------------- 引导页

@Composable
fun OnboardingScreen(onPick: (Role) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("GuardianLink", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "老人离家安全监护：家庭围栏 + 飞书告警 + 多监护人局域网同步",
            fontSize = 14.sp, color = Color.Gray,
        )
        Spacer(Modifier.height(40.dp))
        Card(
            modifier = Modifier.fillMaxWidth().height(110.dp).clickable { onPick(Role.ELDER_DEVICE) },
        ) {
            Column(Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Text("这是老人随身手机", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("设置家庭安全区域，离家自动响铃并发飞书位置", fontSize = 13.sp, color = Color.Gray)
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth().height(110.dp).clickable { onPick(Role.GUARDIAN_DEVICE) },
        ) {
            Column(Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Text("这是监护人手机", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("与老人机配对，离家事件实时推送并响铃", fontSize = 13.sp, color = Color.Gray)
            }
        }
    }
}

// ---------------------------------------------------------------- 首页

@Composable
fun HomeScreen(
    repo: SettingsRepository,
    fenceRepo: FenceConfigRepository,
    onOpenSettings: () -> Unit,
    onOpenFence: () -> Unit,
    onOpenEvents: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by repo.all.collectAsState(initial = AppSettings())
    val guardState by GuardService.state.collectAsState()
    val fenceUi by GuardService.fenceState.collectAsState()
    val health by GuardService.health.collectAsState()
    val recent by GuardService.recentEvents.collectAsState()

    val pairingStore = remember { PairingStore(File(context.filesDir, "pairing.json")) }
    var pairedElder by remember { mutableStateOf<PairingStore?>(null) }
    var elderEntry by remember { mutableStateOf<ElderEntry?>(null) }
    LaunchedEffect2 {
        elderEntry = pairingStore.elder()
    }

    var scanning by remember { mutableStateOf(false) }
    var scanResults by remember { mutableStateOf(listOf<PeerDiscoverer.Candidate>()) }
    var btDevices by remember { mutableStateOf(listOf<BluetoothDevice>()) }
    var showScanDialog by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var showBtDialog by remember { mutableStateOf(false) }
    var manualIp by remember { mutableStateOf("") }
    var manualName by remember { mutableStateOf("") }
    var pairTargetIp by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var showPairDialog by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startGuard(context, s)
        else toast(context, "权限未授予完整，无法开始监护")
    }

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val batteryOk = remember { powerManager.isIgnoringBatteryOptimizations(context.packageName) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GuardianLink", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenSettings) { Text("⚙ 设置") }
        }
        Spacer(Modifier.height(12.dp))

        Text("角色", fontSize = 13.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        Row {
            Role.values().forEach { r ->
                if (s.role == r) {
                    Button(
                        onClick = { scope.launch { repo.completeOnboarding(r) } },
                        modifier = Modifier.weight(1f),
                    ) { Text(r.label) }
                } else {
                    OutlinedButton(
                        onClick = { scope.launch { repo.completeOnboarding(r) } },
                        modifier = Modifier.weight(1f),
                    ) { Text(r.label) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("监护模式", fontSize = 13.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        Row {
            GuardMode.values().forEach { m ->
                if (s.mode == m) {
                    Button(
                        onClick = { scope.launch { repo.setMode(m) } },
                        modifier = Modifier.weight(1f),
                    ) { Text(m.label) }
                } else {
                    OutlinedButton(
                        onClick = { scope.launch { repo.setMode(m) } },
                        modifier = Modifier.weight(1f),
                    ) { Text(m.label) }
                }
            }
        }

        // ------------------------------------------------ 家庭围栏监护（核心）
        if (s.mode == GuardMode.FENCE && s.role == Role.ELDER_DEVICE) {
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("家庭围栏监护", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    StatusRow(
                        "监护状态",
                        if (fenceUi.monitorRunning) guardStateText(fenceUi.machineState) else "未开启监护"
                    )
                    StatusRow("家庭区域", if (fenceUi.fenceConfigured) fenceUi.fenceSummary else "未设置")
                    StatusRow("最近定位", formatAge(fenceUi.lastFixAt))
                    StatusRow("飞书告警", if (health.feishuConfigured) "✅ 已配置" else "⚠ 未配置(仅本地响铃)")
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(onClick = onOpenFence) { Text("设置家庭安全区域") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = onOpenEvents) { Text("事件记录(${recent.size})") }
                    }
                }
            }
        }

        // ------------------------------------------------ 监护人：配对与接收
        if (s.mode == GuardMode.FENCE && s.role == Role.GUARDIAN_DEVICE) {
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("守护对（局域网配对）", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    StatusRow(
                        "已配对老人机",
                        elderEntry?.let { "${it.name.ifBlank { it.id }} · 最近在线 ${formatAge(it.lastSeen)}" } ?: "未配对"
                    )
                    StatusRow("接收状态", statusText(guardState))
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(
                            onClick = {
                                scanning = true
                                scope.launch {
                                    val found = withContext(Dispatchers.IO) { PeerDiscoverer.discover(5_000) }
                                    scanning = false
                                    if (found.isEmpty()) {
                                        toast(context, "未发现老人设备：请确认老人机已开启监护且同一 Wi-Fi（未开 AP 隔离）")
                                    } else {
                                        scanResults = found
                                        showScanDialog = true
                                    }
                                }
                            },
                            enabled = !scanning,
                        ) { Text(if (scanning) "扫描中…" else "扫描老人设备") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { manualIp = ""; showManualDialog = true }) { Text("手动填 IP") }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenEvents) { Text("事件记录(${recent.size})") }
                }
            }
        }

        // ------------------------------------------------ 遗留：连接监护绑定
        if (s.mode == GuardMode.WIFI) {
            Spacer(Modifier.height(16.dp))
            Text("对端绑定（连接监护·WiFi）", fontSize = 13.sp, color = Color.Gray)
            Text(
                if (s.peerIp.isBlank()) "尚未绑定对端" else "${s.peerName.ifBlank { s.peerIp }} · ${s.peerIp}",
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(
                    onClick = {
                        scanning = true
                        scope.launch {
                            val found = withContext(Dispatchers.IO) { PeerDiscoverer.discover(5_000) }
                            scanning = false
                            if (found.isEmpty()) {
                                toast(context, "未发现对端：请确认对端已开启监护，且与本机在同一 WiFi（未开 AP 隔离）")
                            } else {
                                scanResults = found
                                showScanDialog = true
                            }
                        }
                    },
                    enabled = !scanning,
                ) { Text(if (scanning) "扫描中…" else "扫描对端") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    manualIp = ""
                    manualName = ""
                    showManualDialog = true
                }) { Text("手动填 IP") }
            }
        } else if (s.mode == GuardMode.BLUETOOTH) {
            Spacer(Modifier.height(16.dp))
            Text("对端绑定（连接监护·蓝牙）", fontSize = 13.sp, color = Color.Gray)
            Text(
                if (s.btAddress.isBlank()) "尚未选择蓝牙设备"
                else "${s.peerName.ifBlank { s.btAddress }}\n${s.btAddress}",
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                try {
                    val adapter =
                        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    val devices = adapter?.bondedDevices?.toList() ?: emptyList()
                    if (devices.isEmpty()) {
                        toast(context, "没有已配对设备，请先到系统设置与对方手机配对")
                    } else {
                        btDevices = devices
                        showBtDialog = true
                    }
                } catch (t: Throwable) {
                    toast(context, "读取蓝牙设备失败：${t.message}")
                }
            }) { Text("选择蓝牙设备") }
        }

        // ------------------------------------------------ 设备健康
        Spacer(Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("设备健康", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                StatusRow("后台监护服务", if (health.monitoring || guardState !is GuardState.Idle) "✅ 运行中" else "未开启")
                StatusRow("精确定位权限", if (health.hasFineLocation) "✅" else "❌ 未授予")
                StatusRow("系统定位服务", if (health.locationEnabled) "✅ 开启" else "❌ 关闭")
                StatusRow("网络", if (health.networkOk) "✅ 可用" else "❌ 不可用")
                StatusRow("电量", if (health.batteryPct in 0..100) "${health.batteryPct}%" else "未知")
                if (s.role == Role.ELDER_DEVICE) {
                    StatusRow("最近飞书成功", formatAge(health.lastFeishuOkAt))
                    StatusRow("最近监护人同步", formatAge(health.lastSyncAt))
                }
            }
        }

        if (!batteryOk) {
            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("⚠ 未加入电池优化白名单", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("系统可能杀掉后台监护导致漏报，强烈建议加入白名单（华为还需在启动管理中放开）。", fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = {
                        val i = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                        runCatching { context.startActivity(i) }.onFailure {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        }
                    }) { Text("申请白名单") }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        val running = guardState !is GuardState.Idle || fenceUi.monitorRunning
        Button(
            onClick = {
                if (running) {
                    GuardService.stop(context)
                } else {
                    val missing = mutableListOf<String>()
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        missing += Manifest.permission.ACCESS_FINE_LOCATION
                    }
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        missing += Manifest.permission.POST_NOTIFICATIONS
                    }
                    if (s.mode == GuardMode.BLUETOOTH && Build.VERSION.SDK_INT >= 31 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                    ) {
                        missing += Manifest.permission.BLUETOOTH_CONNECT
                    }
                    if (missing.isEmpty()) startGuard(context, s)
                    else permissionLauncher.launch(missing.toTypedArray())
                }
            },
            colors = if (running) {
                ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
            } else {
                ButtonDefaults.buttonColors()
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(if (running) "停止监护" else "开始监护", fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "提示：监护状态与位置是两回事——设备在线不代表人在家；家庭围栏监测在老人手机本地独立运行。",
            fontSize = 12.sp, color = Color.Gray,
        )
        Spacer(Modifier.height(24.dp))
    }

    // ------------------------------------------------ 对话框
    if (showScanDialog) {
        AlertDialog(
            onDismissRequest = { showScanDialog = false },
            title = { Text(if (s.mode == GuardMode.FENCE && s.role == Role.GUARDIAN_DEVICE) "发现老人设备" else "发现对端设备") },
            text = {
                Column {
                    scanResults.forEach { c ->
                        TextButton(onClick = {
                            if (s.mode == GuardMode.FENCE && s.role == Role.GUARDIAN_DEVICE) {
                                pairTargetIp = c.ip
                                pairCode = ""
                                showPairDialog = true
                            } else {
                                scope.launch {
                                    repo.setPeer(c.name, c.ip)
                                    showScanDialog = false
                                }
                            }
                        }) { Text("${c.name}\n${c.ip}") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showScanDialog = false }) { Text("关闭") } },
        )
    }
    if (showPairDialog) {
        AlertDialog(
            onDismissRequest = { if (!pairing) showPairDialog = false },
            title = { Text("输入配对码") },
            text = {
                Column {
                    Text("请输入老人机「设置家庭安全区域」页顶部或首页显示的 6 位配对码（老人机需已开启监护）。", fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pairCode,
                        onValueChange = { pairCode = it.take(6) },
                        label = { Text("6 位配对码") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !pairing && pairCode.length == 6,
                    onClick = {
                        pairing = true
                        scope.launch {
                            val engine = LanSyncEngine(
                                scope = scope,
                                pairing = pairingStore,
                                selfRole = Role.GUARDIAN_DEVICE,
                                selfName = Build.MODEL,
                                elderEventStore = null,
                                guardianEventStore = null,
                            )
                            val elder = withContext(Dispatchers.IO) {
                                engine.pair(pairTargetIp, pairCode)
                            }
                            pairing = false
                            if (elder != null) {
                                elderEntry = elder
                                repo.setPeer(elder.name, pairTargetIp)
                                showPairDialog = false
                                toast(context, "配对成功：${elder.name.ifBlank { "老人设备" }}")
                            } else {
                                toast(context, "配对失败：请检查同一 Wi-Fi、老人机已开启监护、配对码正确")
                            }
                        }
                    }
                ) { Text(if (pairing) "配对中…" else "配对") }
            },
            dismissButton = {
                TextButton(onClick = { if (!pairing) showPairDialog = false }) { Text("取消") }
            },
        )
    }
    if (showManualDialog) {
        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            title = { Text(if (s.mode == GuardMode.FENCE && s.role == Role.GUARDIAN_DEVICE) "手动输入老人机 IP" else "手动绑定对端 IP") },
            text = {
                Column {
                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it },
                        label = { Text("对端 IP，如 192.168.1.23") },
                        singleLine = true,
                    )
                    if (s.mode != GuardMode.FENCE) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = manualName,
                            onValueChange = { manualName = it },
                            label = { Text("备注名（可选）") },
                            singleLine = true,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (manualIp.isNotBlank()) {
                        if (s.mode == GuardMode.FENCE && s.role == Role.GUARDIAN_DEVICE) {
                            pairTargetIp = manualIp.trim()
                            pairCode = ""
                            showManualDialog = false
                            showPairDialog = true
                        } else {
                            scope.launch {
                                repo.setPeer(manualName.ifBlank { manualIp.trim() }, manualIp.trim())
                                showManualDialog = false
                            }
                        }
                    }
                }) { Text(if (s.mode == GuardMode.FENCE && s.role == Role.GUARDIAN_DEVICE) "下一步" else "绑定") }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) { Text("取消") }
            },
        )
    }
    if (showBtDialog) {
        AlertDialog(
            onDismissRequest = { showBtDialog = false },
            title = { Text("选择已配对的蓝牙设备") },
            text = {
                Column {
                    btDevices.forEach { d ->
                        val label = runCatching { "${d.name}\n${d.address}" }.getOrDefault(d.address)
                        TextButton(onClick = {
                            val name = runCatching { d.name ?: d.address }.getOrDefault(d.address)
                            scope.launch {
                                repo.setBtAddress(d.address, name)
                                showBtDialog = false
                            }
                        }) { Text(label) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBtDialog = false }) { Text("取消") } },
        )
    }
}

/** 简化的 LaunchedEffect(Unit) 别名，避免多处重复导入。 */
@Composable
private fun LaunchedEffect2(block: suspend () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { block() }
}

// ---------------------------------------------------------------- 设置页

@Composable
fun SettingsScreen(repo: SettingsRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by repo.all.collectAsState(initial = AppSettings())

    var webhook by remember(s) { mutableStateOf(s.feishuWebhook) }
    var secret by remember(s) { mutableStateOf(s.feishuSecret) }
    var deviceLabel by remember(s) { mutableStateOf(s.deviceLabel) }
    var reportEnabled by remember(s) { mutableStateOf(s.reportEnabled) }
    var alertPeerOffline by remember(s) { mutableStateOf(s.alertOnPeerOffline) }
    var reportIntervalS by remember(s) { mutableStateOf(s.reportIntervalS.toString()) }
    var awayReportMin by remember(s) { mutableStateOf(s.awayReportMin.toString()) }
    var graceS by remember(s) { mutableStateOf(s.wifiGraceS.toString()) }
    var hbS by remember(s) { mutableStateOf(s.heartbeatIntervalS.toString()) }
    var lostS by remember(s) { mutableStateOf(s.lostAfterS.toString()) }
    var testing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.weight(1f))
            Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }

        SectionTitle("老人标识")
        NumberField("设备/老人显示名（默认：老人手机）", deviceLabel) { deviceLabel = it }

        SectionTitle("飞书位置告警")
        OutlinedTextField(
            value = webhook,
            onValueChange = { webhook = it },
            label = { Text("飞书群机器人 Webhook URL（留空则不上报）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text("签名校验密钥（机器人未开启签名则留空）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = reportEnabled, onCheckedChange = { reportEnabled = it })
            Spacer(Modifier.width(8.dp))
            Text("离家期间定时位置更新")
        }
        Spacer(Modifier.height(4.dp))
        NumberField("离家位置更新间隔（分钟），默认 10", awayReportMin) { awayReportMin = it }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (webhook.isBlank()) {
                    toast(context, "请先填写 Webhook URL")
                    return@Button
                }
                testing = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        FeishuClient.send(
                            webhook.trim(), secret.trim(),
                            "【GuardianLink】测试消息：飞书告警配置成功 ✅"
                        ) is com.guardian.link.core.feishu.FeishuResult.Success
                    }
                    testing = false
                    toast(context, if (ok) "已发送，请到飞书群里查看" else "发送失败：请检查 URL/密钥/关键词设置")
                }
            },
            enabled = !testing,
        ) { Text(if (testing) "发送中…" else "发送测试消息") }

        SectionTitle("离家确认与告警策略")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = alertPeerOffline, onCheckedChange = { alertPeerOffline = it })
            Spacer(Modifier.width(8.dp))
            Text("连接监护：对端设备离线时也告警")
        }
        Spacer(Modifier.height(4.dp))
        NumberField("WiFi 断开宽限（秒），默认 5", graceS) { graceS = it }
        NumberField("心跳间隔（秒），默认 5", hbS) { hbS = it }
        NumberField("心跳失联判定（秒），默认 15", lostS) { lostS = it }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                scope.launch {
                    repo.saveAdvanced(
                        feishuWebhook = webhook.trim(),
                        feishuSecret = secret.trim(),
                        deviceLabel = deviceLabel.trim(),
                        reportEnabled = reportEnabled,
                        reportIntervalS = reportIntervalS.toLongOrNull()?.coerceAtLeast(10) ?: 30,
                        awayReportMin = awayReportMin.toLongOrNull()?.coerceIn(1, 120) ?: 10,
                        alertOnPeerOffline = alertPeerOffline,
                        wifiGraceS = graceS.toLongOrNull()?.coerceIn(1, 60) ?: 5,
                        heartbeatIntervalS = hbS.toLongOrNull()?.coerceIn(2, 60) ?: 5,
                        lostAfterS = lostS.toLongOrNull()?.coerceIn(5, 300) ?: 15,
                    )
                    toast(context, "已保存")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存设置") }
        Spacer(Modifier.height(8.dp))
        Text("说明：设置在下次“开始监护”时生效；离家确认默认需连续 3 次有效定位（约 45 秒），如需调整请修改 FenceParams。", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
}

// ---------------------------------------------------------------- 事件记录页

@Composable
fun EventsScreen(onBack: () -> Unit) {
    val recent by GuardService.recentEvents.collectAsState()
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA) }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.weight(1f))
            Text("事件记录", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(80.dp))
        }
        if (recent.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("暂无事件。开启监护后，离家/返家/位置更新/定位异常都会记录在这里。", fontSize = 13.sp, color = Color.Gray)
        } else {
            LazyColumn {
                items(recent, key = { it.id }) { e ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(eventTypeLabel(e.type), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                Text(fmt.format(Date(e.occurredAt)), fontSize = 12.sp, color = Color.Gray)
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(sendStatusLabel(e), fontSize = 12.sp, color = Color.Gray)
                            if (e.lastError != null) {
                                Text("错误: ${e.lastError}", fontSize = 11.sp, color = Color(0xFFB71C1C))
                            }
                            if (e.note != null) {
                                Text(e.note, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
