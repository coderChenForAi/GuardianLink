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
import android.widget.Toast
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
import com.guardian.link.core.wifi.PeerDiscoverer
import com.guardian.link.data.AppSettings
import com.guardian.link.data.GuardMode
import com.guardian.link.data.GuardState
import com.guardian.link.data.Role
import com.guardian.link.data.SettingsRepository
import com.guardian.link.data.toGuardConfig
import com.guardian.link.service.GuardService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}

private fun statusText(state: GuardState): String = when (state) {
    GuardState.Idle -> "未开启监护"
    is GuardState.Connecting -> if (state.detail.isBlank()) "连接中…" else "连接中：${state.detail}"
    is GuardState.Connected -> "监护中 · 连接正常"
    is GuardState.Degraded -> "链路抖动：${state.detail}"
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
            "两台手机互为守护：离开 WiFi / 蓝牙断开，双方同时响铃",
            fontSize = 14.sp, color = Color.Gray,
        )
        Spacer(Modifier.height(40.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clickable { onPick(Role.WARD) },
        ) {
            Column(
                Modifier.padding(16.dp).fillMaxSize(),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("我是被监护人（孩子）", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("离开 WiFi / 蓝牙断开时本机响铃，并把位置发到飞书", fontSize = 13.sp, color = Color.Gray)
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clickable { onPick(Role.GUARDIAN) },
        ) {
            Column(
                Modifier.padding(16.dp).fillMaxSize(),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("我是监护人（家长）", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("对端失联时本机响铃，收到对端的位置推送", fontSize = 13.sp, color = Color.Gray)
            }
        }
    }
}

// ---------------------------------------------------------------- 首页

@Composable
fun HomeScreen(repo: SettingsRepository, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by repo.all.collectAsState(initial = AppSettings())
    val guardState by GuardService.state.collectAsState()

    var scanning by remember { mutableStateOf(false) }
    var scanResults by remember { mutableStateOf(listOf<PeerDiscoverer.Candidate>()) }
    var btDevices by remember { mutableStateOf(listOf<BluetoothDevice>()) }
    var showScanDialog by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var showBtDialog by remember { mutableStateOf(false) }
    var manualIp by remember { mutableStateOf("") }
    var manualName by remember { mutableStateOf("") }

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
        Text("模式", fontSize = 13.sp, color = Color.Gray)
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

        Spacer(Modifier.height(16.dp))
        val statusColor = when (guardState) {
            GuardState.Idle -> Color(0xFF9E9E9E)
            is GuardState.Connecting -> Color(0xFFFFA000)
            is GuardState.Connected -> Color(0xFF2E7D32)
            is GuardState.Degraded -> Color(0xFFFFA000)
            is GuardState.Alerting -> Color(0xFFB71C1C)
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("●", color = statusColor, fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(statusText(guardState), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                val gs = guardState
                if (gs is GuardState.Connected) {
                    Spacer(Modifier.height(4.dp))
                    Text("对端：${gs.peer}", fontSize = 13.sp, color = Color.Gray)
                }
                if (gs is GuardState.Alerting) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { GuardService.disarm(context) }) { Text("解除告警") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        if (s.mode == GuardMode.WIFI) {
            Text("对端绑定（WiFi 模式）", fontSize = 13.sp, color = Color.Gray)
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
        } else {
            Text("对端绑定（蓝牙模式）", fontSize = 13.sp, color = Color.Gray)
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

        if (!batteryOk) {
            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("⚠ 未加入电池优化白名单", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("系统可能杀掉后台监护导致漏报，强烈建议加入白名单。", fontSize = 13.sp)
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
        val running = guardState !is GuardState.Idle
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
        Text("提示：修改角色/模式/设置后，需停止再重新开始监护才会生效。", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(24.dp))
    }

    if (showScanDialog) {
        AlertDialog(
            onDismissRequest = { showScanDialog = false },
            title = { Text("发现对端设备") },
            text = {
                Column {
                    scanResults.forEach { c ->
                        TextButton(onClick = {
                            scope.launch {
                                repo.setPeer(c.name, c.ip)
                                showScanDialog = false
                            }
                        }) { Text("${c.name}\n${c.ip}") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showScanDialog = false }) { Text("关闭") }
            },
        )
    }
    if (showManualDialog) {
        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            title = { Text("手动绑定对端 IP") },
            text = {
                Column {
                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it },
                        label = { Text("对端 IP，如 192.168.1.23") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualName,
                        onValueChange = { manualName = it },
                        label = { Text("备注名（可选）") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (manualIp.isNotBlank()) {
                        scope.launch {
                            repo.setPeer(manualName.ifBlank { manualIp.trim() }, manualIp.trim())
                            showManualDialog = false
                        }
                    }
                }) { Text("绑定") }
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
            confirmButton = {
                TextButton(onClick = { showBtDialog = false }) { Text("取消") }
            },
        )
    }
}

// ---------------------------------------------------------------- 设置页

@Composable
fun SettingsScreen(repo: SettingsRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by repo.all.collectAsState(initial = AppSettings())

    var webhook by remember(s) { mutableStateOf(s.feishuWebhook) }
    var reportEnabled by remember(s) { mutableStateOf(s.reportEnabled) }
    var alertPeerOffline by remember(s) { mutableStateOf(s.alertOnPeerOffline) }
    var reportIntervalS by remember(s) { mutableStateOf(s.reportIntervalS.toString()) }
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

        SectionTitle("飞书位置上报")
        OutlinedTextField(
            value = webhook,
            onValueChange = { webhook = it },
            label = { Text("飞书群机器人 Webhook URL（留空则不上报）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = reportEnabled, onCheckedChange = { reportEnabled = it })
            Spacer(Modifier.width(8.dp))
            Text("监护期间定时上报位置")
        }
        Spacer(Modifier.height(4.dp))
        NumberField("上报间隔（秒），默认 30", reportIntervalS) { reportIntervalS = it }
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
                        FeishuClient.send(webhook.trim(), "【GuardianLink】测试消息：飞书上报配置成功 ✅")
                    }
                    testing = false
                    toast(context, if (ok) "已发送，请到飞书群里查看" else "发送失败：请检查 URL 和网络")
                }
            },
            enabled = !testing,
        ) { Text(if (testing) "发送中…" else "发送测试消息") }

        SectionTitle("告警策略")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = alertPeerOffline, onCheckedChange = { alertPeerOffline = it })
            Spacer(Modifier.width(8.dp))
            Text("对端设备离线时也告警")
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
                        reportEnabled = reportEnabled,
                        reportIntervalS = reportIntervalS.toLongOrNull()?.coerceAtLeast(10) ?: 30,
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
        Text("说明：设置在下次“开始监护”时生效；定时上报会明显增加耗电。", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
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
