package com.guardian.link.core.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.guardian.link.MainActivity
import com.guardian.link.R

/**
 * 告警子系统：ALARM 音频流拉到最大音量循环播放内置警笛 + 循环震动 + 高优先级全屏通知，
 * 若用户授予了勿扰访问权限则临时切到"优先"模式穿透勿扰。stop() 恢复一切原状。
 */
object AlarmController {

    private const val TAG = "Alarm"
    const val CHANNEL_ALERT = "guard_alert"
    private const val ALERT_NOTIF_ID = 2001

    private var player: MediaPlayer? = null
    private var prevAlarmVolume = -1
    private var prevDndFilter = -1
    private var vibrating = false

    fun start(context: Context, title: String, text: String) {
        val app = context.applicationContext
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (prevAlarmVolume < 0) prevAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (t: Throwable) {
            Log.w(TAG, "set alarm volume failed: ${t.message}")
        }
        if (player == null) {
            player = try {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    app.resources.openRawResourceFd(R.raw.alarm).use { afd ->
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    }
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "alarm sound failed: ${t.message}")
                null
            }
        }
        try {
            vibrator(app).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400), 0))
            vibrating = true
        } catch (t: Throwable) {
            Log.w(TAG, "vibrate failed: ${t.message}")
        }
        tryBypassDnd(app)
        postAlertNotification(app, title, text)
        runCatching {
            app.startActivity(
                Intent(app, AlertActivity::class.java)
                    .putExtra(AlertActivity.EXTRA_REASON, title)
                    .putExtra(AlertActivity.EXTRA_DETAIL, text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
        if (vibrating) {
            runCatching { vibrator(app).cancel() }
            vibrating = false
        }
        if (prevAlarmVolume >= 0) {
            runCatching {
                val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_ALARM, prevAlarmVolume, 0)
            }
            prevAlarmVolume = -1
        }
        if (prevDndFilter >= 0) {
            runCatching {
                val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.setInterruptionFilter(prevDndFilter)
            }
            prevDndFilter = -1
        }
        runCatching {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(ALERT_NOTIF_ID)
        }
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ALERT) == null) {
            val ch = NotificationChannel(CHANNEL_ALERT, "告警", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(null, null)
            ch.enableVibration(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun postAlertNotification(context: Context, title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context)
        val intent = Intent(context, AlertActivity::class.java)
            .putExtra(AlertActivity.EXTRA_REASON, title)
            .putExtra(AlertActivity.EXTRA_DETAIL, text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            context, 11, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()
        nm.notify(ALERT_NOTIF_ID, notif)
    }

    private fun tryBypassDnd(context: Context) {
        if (prevDndFilter >= 0) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return
        val current = runCatching { nm.currentInterruptionFilter }
            .getOrDefault(NotificationManager.INTERRUPTION_FILTER_ALL)
        if (current == NotificationManager.INTERRUPTION_FILTER_ALL) return
        prevDndFilter = current
        runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY) }
    }

    private fun vibrator(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
}
