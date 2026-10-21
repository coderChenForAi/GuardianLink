package com.guardian.link.core.feishu

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** 飞书群自定义机器人 webhook 客户端（text 消息），无需任何服务器。 */
object FeishuClient {

    private const val TAG = "FeishuClient"

    fun send(webhook: String, text: String): Boolean {
        if (webhook.isBlank()) return false
        return try {
            val payload = JSONObject()
                .put("msg_type", "text")
                .put("content", JSONObject().put("text", text))
            val conn = URL(webhook).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            Log.i(TAG, "feishu send code=$code resp=$body")
            code in 200..299
        } catch (t: Throwable) {
            Log.w(TAG, "feishu send failed: ${t.message}")
            false
        }
    }
}
