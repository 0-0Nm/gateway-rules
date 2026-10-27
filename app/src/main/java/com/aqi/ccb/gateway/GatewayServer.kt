package com.aqi.ccb.gateway

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地 OpenAI 兼容网关。
 *
 * 职责链：接收请求 -> 限流准入 -> 规则选路 -> 上游转发 -> SSE 透传 -> 双轨日志。
 *
 * 线程模型：accept 1 线程 + 固定线程池（默认 8）处理连接。
 * 零第三方依赖：ServerSocket + HttpURLConnection。
 */
class GatewayServer(
    private val port: Int,
    private var providers: List<Provider>,
    private var rules: List<RuleSpec>,
    private val limiter: RateLimiter,
    private val localAuth: String = "",
    private var virtualModels: List<VirtualModel> = emptyList()
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(8)
    private val inflight = AtomicInteger(0)

    @Volatile var lastError: String? = null
    @Volatile var totalRequests: Long = 0
    @Volatile var totalOk: Long = 0
    @Volatile var totalFail: Long = 0

    fun update(providers: List<Provider>, rules: List<RuleSpec>,
               virtualModels: List<VirtualModel>? = null) {
        this.providers = providers
        this.rules = rules
        virtualModels?.let { this.virtualModels = it }
    }

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            // 同 McpServer：显式绑 0.0.0.0，避免 IPv6-only 导致 127.0.0.1 连不上
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("0.0.0.0"), port))
            serverSocket = ss
            running.set(true)
            acceptThread = Thread({
                while (running.get()) {
                    try {
                        val s = ss.accept()
                        pool.execute { handleConnection(s) }
                    } catch (e: Exception) {
                        if (running.get()) GateLog.error("gw", "accept failed", e)
                    }
                }
            }, "gw-accept").apply { isDaemon = true; start() }
            GateLog.info("gw", "listening 127.0.0.1:$port providers=${providers.size} rules=${rules.size}")
            true
        } catch (e: Exception) {
            lastError = e.toString()
            GateLog.error("gw", "start failed port=$port", e)
            false
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread = null
        GateLog.info("gw", "stopped")
    }

    fun isRunning(): Boolean = running.get()

    // ==================== 连接处理 ====================
    private fun handleConnection(socket: Socket) {
        val reqId = "r-" + UUID.randomUUID().toString().take(8)
        val t0 = System.currentTimeMillis()
        try {
            socket.soTimeout = 120_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val head = readHeader(input) ?: return
            val lines = head.split("\r\n")
            if (lines.isEmpty()) return
            val requestLine = lines[0].split(" ")
            if (requestLine.size < 2) return
            val method = requestLine[0]
            val path = requestLine[1]

            val headers = LinkedHashMap<String, String>()
            for (i in 1 until lines.size) {
                val idx = lines[i].indexOf(':')
                if (idx > 0) headers[lines[i].substring(0, idx).trim().lowercase()] =
                    lines[i].substring(idx + 1).trim()
            }

            val bodyLen = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (bodyLen > 0) readExactly(input, bodyLen) else ByteArray(0)

            val client = socket.inetAddress?.hostAddress ?: "?"
            GateLog.requestStart(reqId, method, path, client, headers, body.size)
            totalRequests++

            if (!authorized(headers)) {
                writeJson(output, 401, """{"error":{"message":"unauthorized","type":"auth"}}""")
                totalFail++
                return
            }

            when {
                method == "GET" && path.startsWith("/health") -> {
                    writeJson(output, 200, """{"status":"ok","running":${running.get()},"port":$port}""")
                }
                method == "GET" && path.startsWith("/v1/_gw/diag") -> {
                    writeJson(output, 200, diagJson())
                }
                method == "GET" && path.startsWith("/v1/_gw/status") -> {
                    writeJson(output, 200, statusJson())
                }
                method == "GET" && path.startsWith("/v1/models") -> {
                    handleModels(output, reqId)
                }
                method == "POST" && path.startsWith("/v1/chat/completions") -> {
                    handleChat(output, reqId, body)
                }
                else -> {
                    writeJson(output, 404, """{"error":{"message":"path not found","type":"invalid_request"}}""")
                }
            }
            GateLog.info("http", "done rid=$reqId ${System.currentTimeMillis() - t0}ms")
        } catch (e: Exception) {
            GateLog.error("gw", "connection error rid=$reqId", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ==================== 路由 ====================
    private fun handleModels(output: OutputStream, reqId: String) {
        val ids = LinkedHashSet<String>()
        providers.filter { it.enabled }.forEach { p -> ids.addAll(p.models.keys) }
        val data = ids.joinToString(",") { """{"id":"$it","object":"model","owned_by":"gateway"}""" }
        writeJson(output, 200, """{"object":"list","data":[$data]}""")
        GateLog.info("models", "served ${ids.size} models rid=$reqId")
    }

    private fun handleChat(output: OutputStream, reqId: String, body: ByteArray) {
        val reqStart = System.currentTimeMillis()
        val bodyStr = String(body, Charsets.UTF_8)
        val modelId = extractJsonString(bodyStr, "model") ?: ""
        val stream = bodyStr.contains("\"stream\"") && bodyStr.contains("true")
        val sessionId = extractJsonString(bodyStr, "session_id")
            ?: extractJsonString(bodyStr, "conversation_id")
            ?: extractJsonString(bodyStr, "user")
            ?: ""
        val ctx = RequestCtx(
            modelId = modelId,
            reqId = reqId,
            ruleKey = rules.firstOrNull()?.id ?: "default",
            sessionId = sessionId,
            turnIndex = SwitchPolicy.deriveTurnIndex(bodyStr),
            estTokens = bodyStr.length / 3
        )

        val rule = rules.firstOrNull { it.enabled }
        if (rule == null) {
            writeJson(output, 503, """{"error":{"message":"no enabled rule","type":"no_rule"}}""")
            totalFail++
            return
        }

        // ---- 虚拟模型优先: 客户端请求的 model 名若命中虚拟模型, 用其绑定的 members 做候选 ----
        val resolved = VirtualModelResolver.resolve(modelId, virtualModels, providers)
        var cands: List<Cand>
        val targetModelId: String
        if (resolved != null) {
            // 虚拟模型命中: 候选来自 members, 转发时把 model 改写为各 member 的真实 modelId
            cands = resolved
            targetModelId = resolved.firstOrNull()?.m?.modelId ?: modelId
            cands.forEach { c -> c.why.add("vmodel_target=" + c.m.modelId) }
        } else {
            cands = RuleEngine.buildCands(providers, ctx)
            targetModelId = modelId
        }
        if (cands.isEmpty()) {
            writeJson(output, 404, """{"error":{"message":"no provider supports model '$modelId'","type":"no_model"}}""")
            totalFail++
            return
        }

        cands = try {
            RuleEngine.applySelect(cands, ctx, rule.select)
        } catch (e: Exception) {
            writeJson(output, 500, """{"error":{"message":"rule error: ${esc(e.message ?: "")}","type":"rule"}}""")
            totalFail++
            return
        }

        if (cands.isEmpty()) {
            writeJson(output, 503, """{"error":{"message":"rule filtered out all providers","type":"no_candidate"}}""")
            totalFail++
            return
        }

        // ---- 模型切换粒度: 按 SESSION/TURN/CALL 重排候选(只调排序, 不硬绑) ----
        val ordered = SwitchPolicy.reorder(cands, rule.switchGranularity, ctx.sessionId, ctx.turnIndex)
        GateLog.info(
            "switch",
            "gran=" + rule.switchGranularity.name +
                " session=" + (if (ctx.sessionId.isEmpty()) "-" else ctx.sessionId) +
                " turn=" + ctx.turnIndex +
                " order=" + ordered.joinToString(",") { it.p.id }
        )

        // ---- 顺序尝试（execute=first 语义；race 需并发，此处按顺序保证稳定性）----
        var lastErr: String? = null
        for (c in ordered) {
            val permit = limiter.acquire(c.p.id)
            if (!permit.ok) {
                GateLog.rateLimited(reqId, c.p.id, permit.waitMs)
                lastErr = "rate_limited:${c.p.id}:${permit.reason}"
                continue
            }
            val at = System.currentTimeMillis()
            try {
                val conn = openUpstream(c, body)
                val code = conn.responseCode
                if (code in 200..299) {
                    val latency = (System.currentTimeMillis() - at).toDouble()
                    c.p.latEwma = c.p.latEwma * 0.8 + latency * 0.2
                    c.p.errors = 0
                    c.p.lastUsedMs = System.currentTimeMillis()
                    GateLog.attempt(reqId, c.p.id, modelId, true, latency, code, null)
                    relay(output, conn, stream)
                    SwitchPolicy.onSuccess(
                        rule.switchGranularity, ctx.sessionId, ctx.turnIndex, c.p.id
                    )
                    GateLog.requestEnd(reqId, true, c.p.id,
                        (System.currentTimeMillis() - reqStart).toDouble(),
                        c.why + listOf("first_winner", "gran=" + rule.switchGranularity.name))
                    totalOk++
                    return
                } else {
                    val latency = (System.currentTimeMillis() - at).toDouble()
                    val errBody = runCatching { conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull()
                    c.p.errors += 1
                    RuntimeState.noteError(c.p.id)
                    if (code == 429) {
                        c.p.breaker = BreakerState.OPEN
                        c.p.openUntilMs = System.currentTimeMillis() + 600_000
                    }
                    GateLog.attempt(reqId, c.p.id, modelId, false, latency, code, errBody?.take(200))
                    lastErr = "http_$code"
                    conn.disconnect()
                }
            } catch (e: Exception) {
                c.p.errors += 1
                RuntimeState.noteError(c.p.id)
                GateLog.attempt(reqId, c.p.id, modelId, false,
                    (System.currentTimeMillis() - at).toDouble(), null, e.toString())
                lastErr = e.toString()
            } finally {
                limiter.release(c.p.id)
            }
        }

        totalFail++
        GateLog.requestEnd(reqId, false, "-", 0.0, listOf("all_failed"))
        writeJson(output, 502, """{"error":{"message":"all providers failed: ${esc(lastErr ?: "")}","type":"upstream"}}""")
    }

    private fun openUpstream(c: Cand, body: ByteArray): HttpURLConnection {
        val base = c.p.baseUrl.trimEnd('/')
        val url = URL("$base/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream, application/json")
        val key = SecretStore.resolve(c.p)
        if (key.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $key")
        conn.outputStream.use { it.write(body) }
        return conn
    }

    /** SSE / JSON 字节级透传，不重组、不吞尾包 */
    private fun relay(output: OutputStream, conn: HttpURLConnection, stream: Boolean) {
        val code = conn.responseCode
        val isSse = (conn.contentType ?: "").contains("text/event-stream")
        output.write("HTTP/1.1 $code OK\r\n".toByteArray())
        output.write("Content-Type: ${if (isSse) "text/event-stream" else "application/json"}\r\n".toByteArray())
        output.write("Cache-Control: no-cache\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.flush()
        val ins: InputStream = conn.inputStream
        val buf = ByteArray(4096)
        while (true) {
            val n = try { ins.read(buf) } catch (e: Exception) { -1 }
            if (n <= 0) break
            output.write(buf, 0, n)
            output.flush()
        }
        conn.disconnect()
    }

    // ==================== HTTP 工具 ====================
    private fun readHeader(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(b.toChar())
            if (prev == '\r'.code && b == '\n'.code) {
                val s = sb.toString()
                if (s.endsWith("\r\n\r\n")) return s.dropLast(2)
            }
            prev = b
            if (sb.length > 32768) return null
        }
    }

    private fun readExactly(input: InputStream, len: Int): ByteArray {
        val out = ByteArrayOutputStream(len)
        val buf = ByteArray(4096)
        var remaining = len
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size, remaining))
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    private fun writeJson(output: OutputStream, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        output.write("HTTP/1.1 $code OK\r\n".toByteArray())
        output.write("Content-Type: application/json\r\n".toByteArray())
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun authorized(h: Map<String, String>): Boolean {
        if (localAuth.isEmpty()) return true
        val a = h["authorization"] ?: return false
        return a == "Bearer $localAuth"
    }

    /**
     * 诊断端点 —— 供外部（AI/curl）自助排查，无需人工手测。
     * 暴露：日志目录、各配置文件路径与条目数、设置快照、限流实时状态。
     */
    private fun diagJson(): String {
        val sb = StringBuilder()
        sb.append("{\"running\":").append(running.get())
        sb.append(",\"port\":").append(port)
        sb.append(",\"logDir\":\"").append(esc(esc(logDirPath))).append("\"")
        sb.append(",\"configDir\":\"").append(esc(esc(configDirPath))).append("\"")
        sb.append(",\"providers\":").append(providers.size)
        sb.append(",\"rules\":").append(rules.size)
        sb.append(",\"virtualModels\":").append(virtualModels.size)
        sb.append(",\"requests\":").append(totalRequests)
        sb.append(",\"ok\":").append(totalOk)
        sb.append(",\"fail\":").append(totalFail)
        sb.append(",\"inflight\":").append(inflight.get())
        val sw = SwitchPolicy.stats()
        sb.append(",\"switch\":{\"bindings\":").append(sw["bindings"])
        sb.append(",\"sessions\":").append(sw["sessions"]).append("}")
        // 限流实时
        sb.append(",\"rate\":[")
        providers.forEachIndexed { i, p ->
            if (i > 0) sb.append(",")
            sb.append("{\"id\":\"").append(esc(esc(p.id))).append("\",")
            sb.append("\"peek\":").append(peekJson(p.id)).append("}")
        }
        sb.append("]")
        // provider 明细（含 breaker）
        sb.append(",\"providerDetail\":[")
        providers.forEachIndexed { i, p ->
            if (i > 0) sb.append(",")
            sb.append("{\"id\":\"").append(esc(esc(p.id))).append("\"")
            sb.append(",\"enabled\":").append(p.enabled)
            sb.append(",\"breaker\":\"").append(p.breaker).append("\"")
            sb.append(",\"errors\":").append(p.errors)
            sb.append(",\"models\":").append(p.models.size)
            sb.append(",\"ratePerMin\":").append(p.ratePolicy.ratePerMin)
            sb.append(",\"minIntervalMs\":").append(p.ratePolicy.minIntervalMs)
            sb.append(",\"maxInflight\":").append(p.ratePolicy.maxInflight)
            sb.append("}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /** 日志目录（由 MainActivity 注入，供 diag 暴露） */
    @Volatile var logDirPath: String = ""
    @Volatile var configDirPath: String = ""

    private fun statusJson(): String {
        val ps = providers.joinToString(",") { p ->
            """{"id":"${p.id}","enabled":${p.enabled},"tier":"${p.tier}","errors":${p.errors},""" +
            """"breaker":"${p.breaker}","lat":${"%.0f".format(p.latEwma)},""" +
            """"rate":${peekJson(p.id)}}"""
        }
        val sw = SwitchPolicy.stats()
        return """{"running":${running.get()},"port":$port,"requests":$totalRequests,""" +
            """"ok":$totalOk,"fail":$totalFail,"inflight":${inflight.get()},""" +
            """"switch":{"bindings":${sw["bindings"]},"sessions":${sw["sessions"]}},""" +
            """"providers":[$ps]}"""
    }

    private fun peekJson(pid: String): String {
        val m = limiter.peek(pid)
        return """{"tokens":${m["tokens"]},"inflight":${m["inflight"]},"can_send":${m["can_send"]},"wait_ms":${m["wait_ms"]}}"""
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun extractJsonString(json: String, key: String): String? {
        val idx = json.indexOf("\"$key\"")
        if (idx < 0) return null
        val colon = json.indexOf(':', idx)
        if (colon < 0) return null
        var i = colon + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '"') return null
        val sb = StringBuilder()
        i++
        while (i < json.length && json[i] != '"') {
            sb.append(json[i]); i++
        }
        return sb.toString()
    }
}

/** API Key 解析：契约里只存 apiKeyRef，实际密钥由调用方注入 */
object SecretStore {
    private val map = HashMap<String, String>()
    fun put(ref: String, key: String) { map[ref] = key }
    fun resolve(p: Provider): String = map[p.apiKeyRef] ?: ""
    fun clear() = map.clear()
}
