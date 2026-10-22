package com.guardian.link.ui

import android.content.pm.PackageManager
import android.widget.Toast
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.guardian.link.core.locate.AMapLocationSource
import com.guardian.link.data.AppSettings
import com.guardian.link.data.FenceConfig
import com.guardian.link.data.FenceConfigRepository
import com.guardian.link.data.SettingsRepository
import com.guardian.link.service.GuardService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 家庭围栏设置页：高德地图选点（长按/拖动 Marker）+ 圆形覆盖层 + 半径滑块 + 保存。
 * 地图瓦片需要高德 Key；未配置 Key 时地图显示空白网格，但选点/保存/围栏功能不受影响。
 */
@Composable
fun FenceSettingsScreen(
    repo: SettingsRepository,
    fenceRepo: FenceConfigRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by repo.all.collectAsState(initial = AppSettings())
    val fence by fenceRepo.config.collectAsState(initial = FenceConfig(0.0, 0.0, 200))
    val fenceUi by GuardService.fenceState.collectAsState()
    val health by GuardService.health.collectAsState()

    var center by remember { mutableStateOf<LatLng?>(null) }
    var radius by remember { mutableStateOf(200f) }
    var saved by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val f = fenceRepo.config.first()
        if (f.configured) {
            center = LatLng(f.centerLat, f.centerLng)
            radius = f.radiusM.toFloat()
            saved = true
        }
    }

    val aMapRef = remember { mutableStateOf<AMap?>(null) }
    val markerRef = remember { mutableStateOf<Marker?>(null) }
    val circleRef = remember { mutableStateOf<Circle?>(null) }
    val mapReady = remember { mutableStateOf(false) }

    fun syncOverlays() {
        val aMap = aMapRef.value ?: return
        val c = center ?: return
        val marker = markerRef.value
        if (marker == null) {
            markerRef.value = aMap.addMarker(
                MarkerOptions()
                    .position(c)
                    .title("家庭中心")
                    .snippet("长按地图或拖动此标记调整")
                    .draggable(true)
            )
        } else {
            marker.position = c
        }
        val circle = circleRef.value
        if (circle == null) {
            circleRef.value = aMap.addCircle(
                CircleOptions()
                    .center(c)
                    .radius(radius.toDouble())
                    .strokeColor(0xCC1565C0.toInt())
                    .strokeWidth(4f)
                    .fillColor(0x261565C0)
            )
        } else {
            circle.center = c
            circle.radius = radius.toDouble()
        }
    }

    val mapView = remember { MapView(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        val aMap = mapView.map
        aMapRef.value = aMap
        aMap.setOnMapLoadedListener { mapReady.value = true }
        aMap.setOnMapLongClickListener { latLng ->
            center = latLng
            saved = false
            syncOverlays()
        }
        aMap.setOnMarkerDragListener(object : AMap.OnMarkerDragListener {
            override fun onMarkerDrag(marker: Marker) {}
            override fun onMarkerDragStart(marker: Marker) {}
            override fun onMarkerDragEnd(marker: Marker) {
                center = marker.position
                saved = false
                syncOverlays()
            }
        })
        mapReady.value = true
    }

    // 地图就绪或候选点变化 → 移动相机 + 同步覆盖层
    LaunchedEffect(mapReady.value, center, radius) {
        val aMap = aMapRef.value ?: return@LaunchedEffect
        val c = center ?: return@LaunchedEffect
        syncOverlays()
        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(c, 15f))
    }

    val amapKeyOk = remember {
        runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            val key = info.metaData?.getString("com.amap.api.v2.apikey")
            !key.isNullOrBlank() && !key.startsWith("REPLACE")
        }.getOrDefault(false)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.weight(1f))
            Text("家庭安全区域", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(96.dp))
        }

        if (!amapKeyOk) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                Text(
                    "高德 Key 未配置：地图瓦片无法加载（显示空白网格属正常），选点/保存/围栏功能不受影响。配置方式见 README。",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(10.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        AndroidView(factory = { mapView.apply { onCreate(null) } }, modifier = Modifier.fillMaxWidth().height(320.dp))
        Spacer(Modifier.height(8.dp))

        val c = center
        Text(
            if (c == null) "尚未选择中心点：长按地图选点，或点“使用当前位置”"
            else "中心点: ${"%.6f".format(c.latitude)}, ${"%.6f".format(c.longitude)}（GCJ-02）",
            fontSize = 13.sp,
        )
        Text("安全半径: ${radius.toInt()} 米（可保存范围 50~1000）", fontSize = 13.sp)
        Slider(
            value = radius,
            onValueChange = {
                radius = it
                saved = false
                syncOverlays()
            },
            valueRange = FenceConfig.MIN_RADIUS_M.toFloat()..FenceConfig.MAX_RADIUS_M.toFloat(),
        )

        Row {
            Button(
                onClick = {
                    locating = true
                    scope.launch {
                        val fix = AMapLocationSource(context).current()
                        locating = false
                        if (fix != null) {
                            center = LatLng(fix.lat, fix.lng)
                            saved = false
                            toast(context, "已获取当前位置作为候选中心点，请确认后保存")
                        } else {
                            toast(context, "定位失败：请检查定位权限/在室外重试")
                        }
                    }
                },
                enabled = !locating
            ) { Text(if (locating) "定位中…" else "使用当前位置") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val cc = center
                    if (cc == null) {
                        toast(context, "请先选择家庭中心点")
                        return@Button
                    }
                    scope.launch {
                        val err = fenceRepo.save(cc.latitude, cc.longitude, radius.toInt())
                        if (err == null) {
                            saved = true
                            toast(context, "家庭安全区域已保存")
                            if (fenceUi.monitorRunning) GuardService.reloadFence(context)
                        } else {
                            toast(context, "保存失败：$err")
                        }
                    }
                }
            ) { Text("保存围栏配置") }
        }
        if (saved) {
            Spacer(Modifier.height(4.dp))
            Text("✅ 已保存。${if (fenceUi.monitorRunning) "围栏监测已按新配置生效。" else "开启监护后生效。"}", fontSize = 12.sp, color = Color(0xFF2E7D32))
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("围栏与定位状态")
        StatusRow("当前监护状态", if (fenceUi.monitorRunning) guardStateText(fenceUi.machineState) else "未开启监护")
        StatusRow("定位权限(精确)", if (health.hasFineLocation) "✅ 已授予" else "❌ 未授予")
        StatusRow("定位服务", if (health.locationEnabled) "✅ 已开启" else "❌ 已关闭")
        StatusRow("最近有效定位", if (fenceUi.lastFixAt > 0) formatAge(fenceUi.lastFixAt) else "暂无")
        if (fenceUi.lastFixAt > 0) {
            StatusRow("最近定位精度", "±${fenceUi.lastFixAccuracy.toInt()} 米")
        }
        Spacer(Modifier.height(24.dp))
        Text("说明：离开/返回的判定由连续多次定位确认（默认连续 3 次），精度差于 100 米的定位会被忽略，避免漂移误报。", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
internal fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 13.sp, color = Color.Gray, modifier = Modifier.width(120.dp))
        Text(value, fontSize = 13.sp)
    }
}

internal fun guardStateText(s: com.guardian.link.core.state.GuardianState?): String = when (s) {
    null -> "未开启监护"
    com.guardian.link.core.state.GuardianState.HOME_SAFE -> "在家 · 围栏监护中"
    com.guardian.link.core.state.GuardianState.EXIT_SUSPECTED -> "疑似离家，确认中…"
    com.guardian.link.core.state.GuardianState.AWAY_ALERTED -> "⚠️ 已离家 · 已发出告警"
    com.guardian.link.core.state.GuardianState.AWAY_TRACKING -> "已离家 · 持续监护"
    com.guardian.link.core.state.GuardianState.RETURN_SUSPECTED -> "疑似返家，确认中…"
    com.guardian.link.core.state.GuardianState.LOCATION_STALE -> "⚠ 定位不可用 · 监护存在盲区"
}
