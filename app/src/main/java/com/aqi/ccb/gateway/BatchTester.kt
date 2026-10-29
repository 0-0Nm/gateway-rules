package com.aqi.ccb.gateway

import com.aqi.ccb.data.ModelInfo
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 批量模型测试 —— 逐个测延迟与可用性，结果逐模型回填。
 *
 * 与 rikka 的 ProviderConnectionTester 的差异：
 *   rikka 一次只测用户选中的一个模型（对话框里选 model 再测）
 *   这里支持批量：并发数默认 3，逐个回调结果，UI 立刻能显示 "1200ms" / "失败"。
 *
 * 测试策略（轻量，不影响额度）：
 *   1 先试 GET /models/{id}（部分服务支持，最省）
 *   2 不支持则发 1 token 的 chat 请求（max_tokens=1, stream=false）
 *
 * 结果写回 ModelInfo.available / latencyMs / lastError / testedAt。
 */
object BatchTester {

    data class Progress(
        val done: Int,
        val total: Int,
        val currentModel: String = "",
        val finished: Boolean = false,
    )

    /**
     * 批量测试。
     * @param onItem 每个模型测完后回调（主线程由调用方切）
     * @param onProgress 进度回调
     */
    fun testAll(
        baseUrl: String,
        apiKey: String,
        models: List<ModelInfo>,
        concurrency: Int = 3,
        timeoutMs: Int = 15000,
        onItem: (ModelInfo) -> Unit,
        onProgress: (Progress) -> Unit,
    ) {
        if (models.isEmpty()) {
            onProgress(Progress(0, 0, finished = true))
            return
        }
        val pool = Executors.newFixedThreadPool(concurrency.coerceIn(1, 8))
        val done = AtomicInteger(0)
        val total = models.size

        models.forEach { m ->
            pool.execute {
                val tested = testOne(baseUrl, apiKey, m, timeoutMs)
                onItem(tested)
                val d = done.incrementAndGet()
                onProgress(Progress(d, total, m.modelId, d >= total))
            }
        }
        pool.shutdown()
    }

    /** 测单个模型：延迟 + 可用性 */
    fun testOne(baseUrl: String, apiKey: String, model: ModelInfo, timeoutMs: Int = 15000): ModelInfo {
        val base = baseUrl.trimEnd('/')
        val t0 = System.currentTimeMillis()

        // ---- 策略 1：GET /models/{id}（最省，不动额度）----
        val byGet = tryGetModel(base, apiKey, model.modelId, timeoutMs)
        if (byGet == 0) {
            val lat = System.currentTimeMillis() - t0
            return model.copy(
                available = true,
                latencyMs = lat,
                lastError = "",
                testedAt = System.currentTimeMillis(),
            )
        }

        // ---- 策略 2：1-token chat ----
        val (ok, err) = tryMinimalChat(base, apiKey, model.modelId, timeoutMs)
        val lat = System.currentTimeMillis() - t0
        return model.copy(
            available = ok,
            latencyMs = if (ok) lat else -1,
            lastError = if (ok) "" else err,
            testedAt = System.currentTimeMillis(),
        )
    }

    /** 返回 0 = 成功；否则 HTTP 码或 -1 */
    private fun tryGetModel(base: String, key: String, modelId: String, timeoutMs: Int): Int {
        val r = HttpUtil.get("$base/models/$modelId", key, timeoutMs)
        return when {
            r.error != null -> -1
            r.ok -> 0
            else -> r.code
        }
    }

    private fun tryMinimalChat(base: String, key: String, modelId: String, timeoutMs: Int): Pair<Boolean, String> {
        val body = """{"model":"$modelId","messages":[{"role":"user","content":"hi"}],"max_tokens":1,"stream":false}"""
        val r = HttpUtil.postJson("$base/chat/completions", key, body, timeoutMs)
        if (r.error != null) return false to r.error
        if (r.ok && (r.body.contains("choices") || r.body.contains("content"))) {
            return true to ""
        }
        val brief = when {
            r.code == 401 || r.code == 403 -> "鉴权失败(" + r.code + ")"
            r.code == 404 -> "模型不存在(404)"
            r.code == 429 -> "限流(429)"
            r.code >= 500 -> "服务端错误(" + r.code + ")"
            else -> "http_" + r.code + " " + r.body.take(60)
        }
        return false to brief
    }
}
