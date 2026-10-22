import XCTest
import CryptoKit
@testable import GuardianLink

final class FeishuClassifierTests: XCTestCase {

    func testHttp200WithZeroOrMissingCodeIsSuccess() {
        XCTAssertEqual(FeishuClassifier.classify(httpCode: 200, feishuCode: 0, feishuMsg: "success"), .success)
        XCTAssertEqual(FeishuClassifier.classify(httpCode: 200, feishuCode: nil, feishuMsg: nil), .success)
    }

    func testKeywordRejectionIsPermanent() {
        // 飞书关键词不匹配：HTTP 200 + 非零业务码
        guard case .permanent = FeishuClassifier.classify(httpCode: 200, feishuCode: 310100, feishuMsg: "Key Words Not Found") else {
            return XCTFail("expected permanent")
        }
    }

    func testRateLimitAndServerErrorsAreRetriable() {
        assertCase(FeishuClassifier.classify(httpCode: 429, feishuCode: nil, feishuMsg: nil), isRetriable: true)
        assertCase(FeishuClassifier.classify(httpCode: 502, feishuCode: nil, feishuMsg: nil), isRetriable: true)
    }

    func testOtherHttpErrorsArePermanent() {
        assertCase(FeishuClassifier.classify(httpCode: 400, feishuCode: nil, feishuMsg: nil), isRetriable: false)
        assertCase(FeishuClassifier.classify(httpCode: 401, feishuCode: nil, feishuMsg: nil), isRetriable: false)
    }

    func testParseFeishuCodeSupportsMultipleShapes() {
        let body1 = #"{"errcode":0,"msg":"success"}"#.data(using: .utf8)!
        XCTAssertEqual(FeishuClient.parseFeishuCode(body1).code, 0)
        let body2 = #"{"code":0}"#.data(using: .utf8)!
        XCTAssertEqual(FeishuClient.parseFeishuCode(body2).code, 0)
        let body3 = #"{"code":"0"}"#.data(using: .utf8)!
        XCTAssertEqual(FeishuClient.parseFeishuCode(body3).code, 0)
        let body4 = #"{"StatusCode":200}"#.data(using: .utf8)!
        XCTAssertEqual(FeishuClient.parseFeishuCode(body4).code, 200)
        let body5 = "not json".data(using: .utf8)!
        XCTAssertNil(FeishuClient.parseFeishuCode(body5).code)
    }

    func testSignatureIsStableBase64Hmac() {
        // 飞书自定义机器人签名：key = "timestamp\nsecret"，空消息
        let ts: Int64 = 1_700_000_000
        let secret = "test-secret"
        let stringToSign = "\(ts)\n\(secret)"
        let key = SymmetricKey(data: Data(stringToSign.utf8))
        let mac = HMAC<SHA256>.authenticationCode(for: Data(), using: key)
        let sign = Data(mac).base64EncodedString()
        XCTAssertFalse(sign.isEmpty)
        // 确定性校验
        let mac2 = HMAC<SHA256>.authenticationCode(for: Data(), using: SymmetricKey(data: Data(stringToSign.utf8)))
        XCTAssertEqual(sign, Data(mac2).base64EncodedString())
    }
}

private func assertCase(
    _ result: FeishuResult,
    isRetriable: Bool,
    file: StaticString = #filePath,
    line: UInt = #line
) {
    switch result {
    case .retriable:
        XCTAssertTrue(isRetriable, "expected retriable, got \(result)", file: file, line: line)
    case .permanent:
        XCTAssertFalse(isRetriable, "expected permanent, got \(result)", file: file, line: line)
    case .success:
        XCTFail("unexpected success", file: file, line: line)
    }
}
