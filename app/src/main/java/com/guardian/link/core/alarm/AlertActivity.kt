package com.guardian.link.core.alarm

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardian.link.service.GuardService

/** 全屏告警页：锁屏之上弹起，红底大字，可"解除告警"或"停止监护"。 */
class AlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val reason = intent.getStringExtra(EXTRA_REASON) ?: "触发告警"
        val detail = intent.getStringExtra(EXTRA_DETAIL) ?: ""
        setContent {
            MaterialTheme {
                AlertScreen(
                    reason = reason,
                    detail = detail,
                    onDismiss = {
                        GuardService.disarm(this)
                        finish()
                    },
                    onStop = {
                        GuardService.stop(this)
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_REASON = "reason"
        const val EXTRA_DETAIL = "detail"
    }
}

@Composable
private fun AlertScreen(reason: String, detail: String, onDismiss: () -> Unit, onStop: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFB71C1C))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("⚠️", fontSize = 64.sp)
        Spacer(Modifier.height(16.dp))
        Text("GuardianLink 告警", color = Color.White, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text(reason, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(detail, color = Color(0xFFFFCDD2), fontSize = 14.sp)
        }
        Spacer(Modifier.height(48.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text("解除告警（继续监护）", fontSize = 18.sp)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text("停止监护", fontSize = 18.sp, color = Color.White)
        }
    }
}
