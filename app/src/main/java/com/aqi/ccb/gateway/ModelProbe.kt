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

    /** Probe only proves observed behavior. Negative results do not prove permanent lack of support. */
    fun probe(baseUrl: String, apiKey: String, modelId: String, timeoutMs: Int = 30000, cancelled: () -> Boolean = { false }): ProbeResult {
        if (cancelled() || Thread.currentThread().isInterrupted) return ProbeResult(false,false,false,0,"probe_cancelled")
        require(timeoutMs in 1..120_000) { "Probe timeout must be bounded" }
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        fun request(stream: Boolean, tools: Boolean = false): String = JSONObject().apply {
            put("model", modelId); put("stream", stream); put("max_tokens", if (tools) 64 else 8)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content",
                if (tools) "What is the weather in Beijing? Use get_weather." else "hi")))
            if (tools) put("tools", JSONArray().put(JSONObject().put("type", "function").put("function",
                JSONObject().put("name", "get_weather").put("description", "Get weather for a city")
                    .put("parameters", JSONObject().put("type", "object")
                        .put("properties", JSONObject().put("city", JSONObject().put("type", "string")))
                        .put("required", JSONArray().put("city"))))))
        }.toString()
        val start = System.nanoTime()
        val first = HttpUtil.postJson(url, apiKey, request(false), timeoutMs)
        val latency = (System.nanoTime() - start) / 1_000_000
        val firstJson = objectOrNull(first.body)
        if (!first.ok || firstJson == null || !hasMessageContent(firstJson)) {
            return ProbeResult(false, false, false, latency,
                if (first.error != null) "non_streaming_transport_error" else "non_streaming_invalid_response_http_${first.code}",
                "non_streaming=not_proven;streaming=not_tested;tool_call=not_tested")
        }
        if (cancelled() || Thread.currentThread().isInterrupted) return ProbeResult(true, false, false, latency, detail = "probe_cancelled")
        val streamOk = streamEvidence(url, apiKey, request(true), timeoutMs)
        if (cancelled() || Thread.currentThread().isInterrupted) return ProbeResult(true, streamOk, false, latency, detail = "probe_cancelled")
        val tool = HttpUtil.postJson(url, apiKey, request(false, true), timeoutMs)
        val toolOk = tool.ok && objectOrNull(tool.body)?.let { hasRequestedTool(it) } == true
        return ProbeResult(true, streamOk, toolOk, latency, detail =
            "non_streaming=observed;streaming=" + (if (streamOk) "observed" else "not_proven") +
                ";tool_call=" + (if (toolOk) "observed" else "not_proven"))
    }

    private fun objectOrNull(text: String): JSONObject? = try {
        val tokener = org.json.JSONTokener(text)
        val value = tokener.nextValue()
        if (value is JSONObject && tokener.nextClean() == 0.toChar()) value else null
    } catch (_: Exception) { null }

    private fun generatedContent(message: JSONObject): Boolean {
        if (message.has("role") && message.opt("role") != "assistant") return false
        val content = message.opt("content")
        if (content is String) return content.isNotBlank()
        if (content is JSONArray) return (0 until content.length()).any { i ->
            val part = content.optJSONObject(i)
            part?.opt("type") == "text" && (part.opt("text") as? String)?.isNotBlank() == true
        }
        return false
    }

    private fun hasMessageContent(json: JSONObject): Boolean {
        if (json.has("error") && !json.isNull("error")) return false
        val choices = json.optJSONArray("choices") ?: return false
        return (0 until choices.length()).any { i ->
            choices.optJSONObject(i)?.optJSONObject("message")?.let { generatedContent(it) } == true
        }
    }

    private fun hasRequestedTool(json: JSONObject): Boolean {
        if (json.has("error") && !json.isNull("error")) return false
        fun functionMatches(fn: JSONObject?): Boolean {
            if (fn == null || fn.opt("name") != "get_weather") return false
            val args = (fn.opt("arguments") as? String)?.let { objectOrNull(it) } ?: return false
            return (args.opt("city") as? String)?.isNotBlank() == true
        }
        val choices = json.optJSONArray("choices") ?: return false
        return (0 until choices.length()).any { i ->
            val msg = choices.optJSONObject(i)?.optJSONObject("message") ?: return@any false
            if (msg.has("role") && msg.opt("role") != "assistant") return@any false
            val calls = msg.optJSONArray("tool_calls")
            val modern = calls != null && (0 until calls.length()).any { n ->
                val call = calls.optJSONObject(n)
                call != null && call.opt("type") == "function" &&
                    (call.opt("id") as? String)?.isNotBlank() == true && functionMatches(call.optJSONObject("function"))
            }
            modern || functionMatches(msg.optJSONObject("function_call"))
        }
    }

    private fun streamEvidence(url: String, key: String, body: String, timeoutMs: Int): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            conn = c
            c.instanceFollowRedirects = false
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "text/event-stream")
            if (key.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $key")
            val request = body.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(request.size)
            c.outputStream.use { it.write(request) }
            if (c.responseCode !in 200..299 || !c.contentType.orEmpty().substringBefore(';').trim().equals("text/event-stream", true)) return false
            var generated = false
            val tracker = SocketIo.SseCompletion { event ->
                val choices = event.optJSONArray("choices")
                if (choices != null) for (i in 0 until choices.length()) {
                    val delta = choices.optJSONObject(i)?.optJSONObject("delta")
                    if (delta != null && generatedContent(delta)) generated = true
                }
            }
            val bytes = SocketIo.readResponse(c, 1024 * 1024)
            tracker.accept(bytes, bytes.size)
            tracker.requireComplete()
            generated
        } catch (_: Exception) { false }
        finally { runCatching { conn?.disconnect() } }
    }
}
