package com.aqi.ccb.gateway

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型能力探测 —— 移植 rikka 的 ProviderConnectionTester 三件套思路。
 *
 * rikka 原版测三项：非流式 / 流式 / 工具调用。
 * 这里同样测三项，但结果不仅展示，还能反填到 Model 能力位：
 *  - 非流式通过      -> 模型可用
 *  - 流式通过        -> 支持 SSE
 *  - 工具调用通过    -> abilities += TOOL
 *
 * 这样「模型支持什么」是探测出来的，不是手填的。
 */
object ModelProbe {

    data class ProbeResult(
        val nonStreaming: Boolean,
        val streaming: Boolean,
        val toolCall: Boolean,
        val latencyMs: Long,
        val error: String? = null,
        val detail: String = "",
    ) {
        fun summary(): String {
            if (error != null) return "失败: $error"
            val sb = StringBuilder()
            sb.append("非流式 ").append(if (nonStreaming) "✓" else "✗")
            sb.append(" · 流式 ").append(if (streaming) "✓" else "✗")
            sb.append(" · 工具 ").append(if (toolCall) "✓" else "✗")
            sb.append(" · ").append(latencyMs).append("ms")
            return sb.toString()
        }

        /** 探测结果 -> 能力位 */
        fun abilities(): List<String> {
            val list = mutableListOf<String>()
            if (toolCall) list.add("TOOL")
            return list
        }
    }

    /**
     * 对一个 (provider, model) 跑三件套测试。
     * 全部同步执行，调用方应放在后台线程。
     */
    fun probe(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        timeoutMs: Int = 30000,
    ): ProbeResult {
        val base = baseUrl.trimEnd('/')
        var lastErr: String? = null

        // ---- 1) 非流式 ----
        val t0 = System.currentTimeMillis()
        val nonStream = try {
            val body = JSONObject().apply {
                put("model", modelId)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", "hi")
                }))
                put("max_tokens", 8)
                put("stream", false)
            }.toString()
            val (code, resp) = post("$base/chat/completions", apiKey, body, timeoutMs)
            if (code in 200..299 && resp.contains("\"choices\"")) true
            else {
                lastErr = "http_$code ${resp.take(80)}"
                false
            }
        } catch (e: Exception) {
            lastErr = e.toString()
            false
        }
        val latency = System.currentTimeMillis() - t0

        if (!nonStream) {
            return ProbeResult(false, false, false, latency, lastErr ?: "non-streaming failed")
        }

        // ---- 2) 流式 ----
        val streamOk = try {
            val body = JSONObject().apply {
                put("model", modelId)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", "hi")
                }))
                put("max_tokens", 8)
                put("stream", true)
            }.toString()
            val conn = open("$base/chat/completions", apiKey, body, timeoutMs)
            val code = conn.responseCode
            var gotData = false
            if (code in 200..299) {
                conn.inputStream.bufferedReader().use { r ->
                    var lines = 0
                    while (lines < 200) {
                        val line = r.readLine() ?: break
                        lines++
                        if (line.startsWith("data:")) {
                            gotData = true
                            break
                        }
                    }
                }
            }
            try { conn.disconnect() } catch (_: Exception) {}
            gotData
        } catch (e: Exception) {
            false
        }

        // ---- 3) 工具调用 ----
        val toolOk = try {
            val body = JSONObject().apply {
                put("model", modelId)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", "What is the weather in Beijing? Use the tool.")
                }))
                put("max_tokens", 64)
                put("stream", false)
                put("tools", JSONArray().put(JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", "get_weather")
                        put("description", "Get weather for a city")
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().put(
                                "city", JSONObject().put("type", "string")
                            ))
                            put("required", JSONArray().put("city"))
                        })
                    })
                }))
            }.toString()
            val (code, resp) = post("$base/chat/completions", apiKey, body, timeoutMs)
            code in 200..299 && (resp.contains("tool_calls") || resp.contains("function_call"))
        } catch (e: Exception) {
            false
        }

        return ProbeResult(nonStream, streamOk, toolOk, latency, null)
    }

    // ---------- HTTP ----------

    /**
     * 建立连接但**不**立即读取/断开，供流式测试边读边判断。
     * 与 HttpUtil 的区别：那边是一次性请求，读完即 disconnect。
     */
    private fun open(url: String, key: String, body: String, timeoutMs: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream, application/json")
        if (key.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $key")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return conn
    }

    /**
     * 一次性 POST（非流式）。
     * 注：流式测试仍需用 open() 自己管连接生命周期，
     * 因为要边读边判断是否收到 data: 行，不能一次性读完就断。
     */
    private fun post(url: String, key: String, body: String, timeoutMs: Int): Pair<Int, String> {
        val r = HttpUtil.postJson(
            url, key, body, timeoutMs,
            accept = "text/event-stream, application/json",
        )
        if (r.error != null) return -1 to r.error
        return r.code to r.body
    }
}
