package com.aqi.ccb.gateway

import com.aqi.ccb.data.ModelInfo
import java.net.HttpURLConnection
import java.net.URL
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
        return try {
            val conn = URL("$base/models/$modelId").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            if (key.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $key")
            val code = conn.responseCode
            try { conn.disconnect() } catch (_: Exception) {}
            if (code in 200..299) 0 else code
        } catch (e: Exception) {
            -1
        }
    }

    private fun tryMinimalChat(base: String, key: String, modelId: String, timeoutMs: Int): Pair<Boolean, String> {
        return try {
            val conn = URL("$base/chat/completions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Content-Type", "application/json")
            if (key.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $key")
            val body = """{"model":"$modelId","messages":[{"role":"user","content":"hi"}],"max_tokens":1,"stream":false}"""
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = try {
                val ins = if (code in 200..299) conn.inputStream else conn.errorStream
                ins?.readBytes()?.toString(Charsets.UTF_8) ?: ""
            } catch (e: Exception) { "" }
            try { conn.disconnect() } catch (_: Exception) {}
            if (code in 200..299 && (text.contains("choices") || text.contains("content"))) {
                true to ""
            } else {
                val brief = when {
                    code == 401 || code == 403 -> "鉴权失败($code)"
                    code == 404 -> "模型不存在(404)"
                    code == 429 -> "限流(429)"
                    code >= 500 -> "服务端错误($code)"
                    else -> "http_$code ${text.take(60)}"
                }
                false to brief
            }
        } catch (e: Exception) {
            val m = e.message ?: ""
            val brief = when {
                m.contains("timeout") || m.contains("timed out") -> "超时"
                m.contains("Connect") || m.contains("connect") -> "连接失败"
                m.contains("ENOTFOUND") -> "域名不存在"
                m.contains("SSL") -> "SSL 错误"
                else -> m.take(40)
            }
            false to brief
        }
    }
}
