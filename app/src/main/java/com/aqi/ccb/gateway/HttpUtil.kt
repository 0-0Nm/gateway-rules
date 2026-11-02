package com.aqi.ccb.gateway

import java.net.HttpURLConnection
import java.net.URL

/**
 * 上游 HTTP 请求统一入口。
 *
 * 为什么抽出来：
 *   BaseUrlProbe / ModelProbe / BatchTester / MainActivity 各自手写了一套
 *   openConnection + 超时设置 + 请求头 + 读 body + disconnect。
 *   5 份实现带来的真实风险：
 *     - 超时值不一致（有的 12s 有的 30s，用户看到的行为前后不一）
 *     - disconnect 漏调（连接泄漏，长跑后 fd 耗尽）
 *     - 错误文案不一致（同样是连不上，一个说"连接失败"一个说 Connection refused）
 *   本项目多数 bug 都来自"同一逻辑多处实现"这一模式，
 *   默认值重复、状态源重复、WakeLock 各自实现都是同一个病根。
 *
 * 只覆盖同步一次性请求（探活/测速/拉模型列表）。
 * 流式转发（SSE）仍需 GatewayServer 自己管连接生命周期，不复用这里。
 */
object HttpUtil {

    /** 一次请求的结果 */
    data class Resp(
        val code: Int,
        /** 响应体文本；网络异常时为空串 */
        val body: String,
        /** 网络层异常信息；成功时为 null */
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null && code in 200..299
    }

    /**
     * GET 请求。
     * @param accept Accept 头，默认 application/json
     */
    fun get(
        url: String,
        apiKey: String = "",
        timeoutMs: Int = 15_000,
        accept: String = "application/json",
        headers: Map<String, String> = emptyMap(),
    ): Resp = execute("GET", url, apiKey, null, timeoutMs, accept, headers)

    /**
     * POST JSON 请求。
     * @param jsonBody 已序列化的 JSON 文本
     */
    fun postJson(
        url: String,
        apiKey: String = "",
        jsonBody: String,
        timeoutMs: Int = 30_000,
        accept: String = "application/json",
    ): Resp = execute("POST", url, apiKey, jsonBody, timeoutMs, accept)

    /** 把网络异常翻译成人话，保证各调用方文案一致 */
    fun describeError(e: Exception): String {
        val m = (e.message ?: return e::class.java.simpleName).lowercase()
        return when {
            m.contains("timed out") || m.contains("timeout") -> "超时"
            m.contains("connectexception") || m.contains("failed to connect") ||
                m.contains("connection refused") -> "连接失败"
            m.contains("enotfound") || m.contains("no address") || m.contains("unknownhost") ->
                "域名不存在"
            m.contains("ssl") || m.contains("certificate") -> "SSL 错误"
            else -> (e.message ?: e::class.java.simpleName).take(60)
        }
    }

    private fun execute(
        method: String,
        url: String,
        apiKey: String,
        jsonBody: String?,
        timeoutMs: Int,
        accept: String,
        headers: Map<String, String> = emptyMap(),
    ): Resp {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", accept)
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
                if (apiKey.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                if (jsonBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            val text = try {
                val ins = if (code in 200..299) conn.inputStream else conn.errorStream
                ins?.readBytes()?.toString(Charsets.UTF_8) ?: ""
            } catch (_: Exception) {
                ""
            }
            Resp(code, text, null)
        } catch (e: Exception) {
            Resp(-1, "", describeError(e))
        } finally {
            // 必须保证释放：原来多处分支提前 return 时漏掉 disconnect
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
