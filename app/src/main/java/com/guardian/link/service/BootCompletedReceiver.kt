package com.guardian.link.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.guardian.link.data.SettingsRepository
import com.guardian.link.data.toGuardConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 设备重启后，若此前监护处于开启状态，则自动恢复监护服务（围栏配置从本地恢复）。 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = SettingsRepository(context)
                if (repo.isArmed()) {
                    val cfg = repo.all.first().toGuardConfig()
                    GuardService.start(context, cfg)
                }
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }
    }
}
