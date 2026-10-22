package com.guardian.link.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.guardian.link.core.alert.AlertDispatcher
import com.guardian.link.data.EventStore
import com.guardian.link.data.SettingsRepository
import com.guardian.link.data.buildAlertConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 飞书发送失败的延迟重试：指数退避，最多自动重试 6 次；
 * 放弃自动重试后事件仍保留 FAILED_RETRIABLE 状态，App 启动/服务启动时会再补发。
 */
class FeishuRetryWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = EventStore(File(ctx.filesDir, "guardian_events.json"))
        val settings = SettingsRepository(ctx).all.first()
        val dispatcher = AlertDispatcher(
            store = store,
            configProvider = { runBlocking { buildAlertConfig(ctx) } },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        val remaining = dispatcher.retryPending()
        return when {
            remaining == 0 -> Result.success()
            runAttemptCount >= MAX_AUTO_RETRIES -> Result.success()
            else -> Result.retry()
        }
    }

    companion object {
        private const val MAX_AUTO_RETRIES = 6
        private const val WORK_NAME = "feishu_retry"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<FeishuRetryWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            )
        }
    }
}
