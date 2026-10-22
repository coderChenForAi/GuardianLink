package com.guardian.link

import com.guardian.link.core.feishu.FeishuClassifier
import com.guardian.link.core.feishu.FeishuClient
import com.guardian.link.core.feishu.FeishuResult
import com.guardian.link.data.FenceConfig
import com.guardian.link.data.FenceConfigRepository
import com.guardian.link.core.geo.FenceParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamsAndFeishuTest {

    // ---------------- 飞书结果分类 ----------------

    @Test
    fun `http 200 with zero or missing code is success`() {
        assertEquals(FeishuResult.Success, FeishuClassifier.classify(200, 0, "success"))
        assertEquals(FeishuResult.Success, FeishuClassifier.classify(200, null, null))
    }

    @Test
    fun `keyword rejection is permanent`() {
        // 飞书关键词不匹配：HTTP 200 + 非零业务码
        val r = FeishuClassifier.classify(200, 310100, "Key Words Not Found")
        assertTrue(r is FeishuResult.Permanent)
    }

    @Test
    fun `rate limit and server errors are retriable`() {
        assertTrue(FeishuClassifier.classify(429, null, null) is FeishuResult.Retriable)
        assertTrue(FeishuClassifier.classify(502, null, null) is FeishuResult.Retriable)
    }

    @Test
    fun `other http errors are permanent`() {
        assertTrue(FeishuClassifier.classify(400, null, null) is FeishuResult.Permanent)
        assertTrue(FeishuClassifier.classify(401, null, null) is FeishuResult.Permanent)
    }

    @Test
    fun `parse feishu code supports multiple response shapes`() {
        assertEquals(0 to "success", FeishuClient.parseFeishuCode("""{"errcode":0,"msg":"success"}"""))
        assertEquals(0 to null, FeishuClient.parseFeishuCode("""{"code":0}"""))
        assertEquals(0 to null, FeishuClient.parseFeishuCode("""{"code":"0"}"""))
        val (code, _) = FeishuClient.parseFeishuCode("""{"StatusCode":200,"StatusMessage":"success"}""")
        assertEquals(200, code)
        assertEquals(null to null, FeishuClient.parseFeishuCode("not json"))
    }

    // ---------------- 围栏参数校验 ----------------

    @Test
    fun `radius coerced into range`() {
        assertEquals(50, FenceConfig.coerceRadius(10))
        assertEquals(1000, FenceConfig.coerceRadius(5000))
        assertEquals(200, FenceConfig.coerceRadius(200))
    }

    @Test
    fun `center must be within china bounds`() {
        assertTrue(FenceConfig.isValidCenter(39.9, 116.4))
        assertFalse(FenceConfig.isValidCenter(0.0, 0.0))
        assertFalse(FenceConfig.isValidCenter(-30.0, 120.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fence params require positive confirm counts`() {
        FenceParams(confirmOutsideFixes = 0)
    }

    @Test
    fun `default fence params are sane`() {
        val p = FenceParams()
        assertTrue(p.maxAccuracyM in 10f..500f)
        assertTrue(p.confirmOutsideFixes in 1..10)
        assertEquals(FenceConfigRepository.MIN_RADIUS_M, 50)
    }
}
