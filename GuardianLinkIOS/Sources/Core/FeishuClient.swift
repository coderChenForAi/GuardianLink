import Foundation
import CryptoKit

/// 发送结果：成功 / 可重试（网络、限流、5xx）/ 永久失败（配置或业务拒绝）。与 Android 端同语义。
enum FeishuResult: Equatable {
    case success
    case retriable(String)
    case permanent(String)
}

/// 纯分类逻辑，可单测。
enum FeishuClassifier {
    static func classify(httpCode: Int, feishuCode: Int?, feishuMsg: String?) -> FeishuResult {
        if (200...299).contains(httpCode), feishuCode == nil || feishuCode == 0 {
            return .success
        }
        if httpCode == 429 { return .retriable("HTTP 429 限流") }
        if (500...599).contains(httpCode) { return .retriable("HTTP \(httpCode)") }
        if (200...299).contains(httpCode) {
            return .permanent("飞书业务错误 code=\(feishuCode ?? -1) msg=\(feishuMsg ?? "")")
        }
        return .permanent("HTTP \(httpCode)")
    }
}

/// 飞书群自定义机器人客户端：可选签名校验（CryptoKit HMAC-SHA256）+ 业务码校验。
enum FeishuClient {

    static func send(webhook: String, secret: String, text: String) async -> FeishuResult {
        guard !webhook.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return .permanent("未配置飞书 Webhook")
        }
        var payload: [String: Any] = [
            "msg_type": "text",
            "content": ["text": text],
        ]
        if !secret.trimmingCharacters(in: .whitespaces).isEmpty {
            let ts = Int64(Date().timeIntervalSince1970)
            let stringToSign = "\(ts)\n\(secret)"
            let key = SymmetricKey(data: Data(stringToSign.utf8))
            let mac = HMAC<SHA256>.authenticationCode(for: Data(), using: key)
            payload["timestamp"] = Int(ts)
            payload["sign"] = Data(mac).base64EncodedString()
        }
        guard let body = try? JSONSerialization.data(withJSONObject: payload) else {
            return .permanent("消息序列化失败")
        }
        guard let url = URL(string: webhook.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            return .permanent("Webhook URL 无效")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 10
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = body

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let httpCode = (response as? HTTPURLResponse)?.statusCode ?? 0
            let parsed = parseFeishuCode(data)
            return FeishuClassifier.classify(httpCode: httpCode, feishuCode: parsed.code, feishuMsg: parsed.msg)
        } catch {
            return .retriable("网络异常: \(error.localizedDescription)")
        }
    }

    /// 兼容飞书机器人多种返回格式：errcode / code / StatusCode
    static func parseFeishuCode(_ data: Data) -> (code: Int?, msg: String?) {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return (nil, nil)
        }
        var code: Int?
        for key in ["errcode", "code", "StatusCode"] {
            if let n = obj[key] as? Int { code = n; break }
            if let s = obj[key] as? String, let parsed = Int(s) { code = parsed; break }
        }
        let msg = (obj["msg"] as? String) ?? (obj["StatusMessage"] as? String)
        return (code, msg)
    }
}
