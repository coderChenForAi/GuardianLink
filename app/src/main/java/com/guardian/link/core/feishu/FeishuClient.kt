package com.guardian.link.core.feishu

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 发送结果：成功 / 可重试失败（网络抖动、限流、5xx）/ 永久失败（配置错误、业务拒绝）。 */
sealed interface FeishuResult {
    data object Success : FeishuResult
    data class Retriable(val reason: String) : FeishuResult
    data class Permanent(val reason: String) : FeishuResult
}

/** 纯分类逻辑，可单测。 */
object FeishuClassifier {
    fun classify(httpCode: Int, feishuCode: Int?, feishuMsg: String?): FeishuResult = when {
        httpCode in 200..299 && (feishuCode == null || feishuCode == 0) -> FeishuResult.Success
        httpCode == 429 -> FeishuResult.Retriable("HTTP 429 限流")
        httpCode in 500..599 -> FeishuResult.Retriable("HTTP $httpCode")
        httpCode in 200..299 -> FeishuResult.Permanent("飞书业务错误 code=$feishuCode msg=${feishuMsg ?: ""}")
        else -> FeishuResult.Permanent("HTTP $httpCode")
    }
}

/**
 * 飞书群自定义机器人客户端。
 * - 支持可选的签名校验（用户在飞书侧开启"签名校验"后把 secret 填进 App 设置）
 * - 校验 HTTP 状态码与飞书业务码（关键词不匹配时飞书返回 HTTP 200 + 非零 code，必须识别）
 * - 不打印 webhook 与 secret
 */
object FeishuClient {

    private const val TAG = "FeishuClient"

    fun send(webhook: String, secret: String, text: String): FeishuResult {
        if (webhook.isBlank()) return FeishuResult.Permanent("未配置飞书 Webhook")
        return try {
            val payload = JSONObject()
                .put("msg_type", "text")
                .put("content", JSONObject().put("text", text))
            if (secret.isNotBlank()) {
                val ts = System.currentTimeMillis() / 1000
                val stringToSign = "$ts\n$secret"
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(stringToSign.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
                val sign = Base64.getEncoder().encodeToString(mac.doFinal(ByteArray(0)))
                payload.put("timestamp", ts)
                payload.put("sign", sign)
            }
            val conn = URL(webhook).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
            val httpCode = conn.responseCode
            val body = (if (httpCode in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            val parsed = parseFeishuCode(body)
            val result = FeishuClassifier.classify(httpCode, parsed.first, parsed.second)
            when (result) {
                is FeishuResult.Success -> Log.i(TAG, "feishu sent ok")
                is FeishuResult.Retriable -> Log.w(TAG, "feishu retriable: ${result.reason}")
                is FeishuResult.Permanent -> Log.w(TAG, "feishu permanent: ${result.reason}")
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "feishu send io error: ${e.message}")
            FeishuResult.Retriable("网络异常: ${e.message ?: "unknown"}")
        }
    }

    /** 兼容飞书机器人多种返回格式：errcode / code / StatusCode。 */
    internal fun parseFeishuCode(body: String): Pair<Int?, String?> = runCatching {
        if (body.isBlank()) return null to null
        val j = JSONObject(body)
        val code = when (val c = j.opt("errcode") ?: j.opt("code") ?: j.opt("StatusCode")) {
            is Number -> c.toInt()
            is String -> c.toIntOrNull()
            else -> null
        }
        val msg = j.optString("msg", null) ?: j.optString("StatusMessage", null)
        code to msg
    }.getOrDefault(null to null)
}
