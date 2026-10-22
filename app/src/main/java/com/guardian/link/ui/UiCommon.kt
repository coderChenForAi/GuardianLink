package com.guardian.link.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}

internal fun formatAge(ts: Long): String {
    if (ts <= 0) return "暂无"
    val age = System.currentTimeMillis() - ts
    return when {
        age < 60_000 -> "${age / 1000} 秒前"
        age < 3_600_000 -> "${age / 60_000} 分钟前"
        age < 86_400_000 -> "${age / 3_600_000} 小时前"
        else -> "${age / 86_400_000} 天前"
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
}
