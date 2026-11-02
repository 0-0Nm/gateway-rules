package com.aqi.ccb.gateway

import java.io.BufferedInputStream
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
    val port: Int,   // 公开：供 GatewayService / diag 读取实际监听端口
    providers: List<Provider>,
    rules: List<RuleSpec>,
    private val limiter: RateLimiter,
    private val localAuth: String,
    virtualModels: List<VirtualModel> = emptyList(),
    private val bindHost: String = "127.0.0.1",
    private val upstreamReadTimeoutMs: Int = 90_000
) {
    init { require(upstreamReadTimeoutMs in 1..90_000)
        require(bindHost in setOf("127.0.0.1", "0.0.0.0")) { "Unsupported bind address" } }
    private data class LiveConfig(val providers:List<Provider>,val rules:List<RuleSpec>,val virtualModels:List<VirtualModel>,val revision:Long,val keys:Map<String,String>)
    @Volatile private var live=LiveConfig(providers.toList(),rules.toList(),virtualModels.toList(),0,SecretStore.snapshot())
    private val providers:List<Provider> get()=live.providers
    private val rules:List<RuleSpec> get()=live.rules
    private val virtualModels:List<VirtualModel> get()=live.virtualModels
    fun configRevisionPublic():Long=live.revision

    private val accessPolicy = ServerAccessPolicy(localAuth, loopbackOnly = bindHost == "127.0.0.1")
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val activeRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<RequestRun>()
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    // 修复假死：原来 fixed(8) + 无容量控制，慢请求（上游 120s）堆满后
    // accept 仍继续但无工作线程，表现为整个网关无响应（连 /health 都挂）。
    // 改为 cached 池并封顶 64，慢请求不会阻塞新连接的短请求。
    private val pool = java.util.concurrent.ThreadPoolExecutor(
        4, 64, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue(),
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
    )
    private val inflight = AtomicInteger(0)

    @Volatile var lastError: String? = null
    @Volatile var totalRequests: Long = 0
    @Volatile var totalOk: Long = 0
    @Volatile var totalFail: Long = 0
    /** 本地拒绝数（401/404/路径错误），与上游失败分开，避免污染成功率 */
    @Volatile var localRejects: Long = 0

    // ---- 近期失败时间戳（滑动窗口），供「中断」状态判定 ----
    //
    // 为什么需要：STATE_ERROR 定义了却从未被设置，等于死状态，
    // 悬浮球永远不会显示「中断」，即使上游连续挂掉。
    // 若要恢复它，必须用真实数据驱动，不能造假。
    // 这里记录每次上游失败的时间戳，按时间窗口自然衰减。
    private val recentFailureMs = java.util.concurrent.ConcurrentLinkedDeque<Long>()

    /** 记录一次失败（供 fail() 与 handleChat 的失败分支调用） */
    private fun noteFailureTs() {
        val now = System.currentTimeMillis()
        recentFailureMs.addLast(now)
        // 修剪超过 5 分钟的记录，避免无界增长
        while (true) {
            val head = recentFailureMs.peekFirst() ?: break
            if (now - head > 5 * 60_000L) recentFailureMs.pollFirst() else break
        }
    }

    /** 窗口内失败次数（供悬浮球「中断」判定与 diag 展示） */
    fun recentFailureCountPublic(windowMs: Long = 60_000L): Int {
        val now = System.currentTimeMillis()
        var n = 0
        for (t in recentFailureMs) {
            if (now - t <= windowMs) n++
        }
        return n
    }
    /** 最近一次失败距今毫秒；无失败返回 -1 */
    fun lastFailureAgoMsPublic(): Long {
        val last = recentFailureMs.peekLast() ?: return -1L
        return System.currentTimeMillis() - last
    }

    // ---- 诊断指标（照 DeepSeek 本地 API 的实时监控面板）----
    /** 当前 TCP 连接数（accept 后 +1，关闭后 -1） */
    private val tcpConnections = AtomicInteger(0)
    /** 客户端主动断开次数 */
    @Volatile var clientDisconnects: Long = 0
    /** 最近一次成功路由：model@provider */
    @Volatile var latestRoute: String = ""
    /** 最近一次失败原因 */
    @Volatile var latestFailure: String = ""
    /** 最近一次失败的原因分类（用于面板"Failure reasons"） */
    @Volatile var latestFailureReason: String = ""
    /** 实际绑定地址（启动时记录，便于诊断） */
    @Volatile var actualBind: String = ""
    /** 启动时刻 */
    @Volatile var startedAtMs: Long = 0L

    @Synchronized
    fun update(providers: List<Provider>, rules: List<RuleSpec>,
               virtualModels: List<VirtualModel>? = null, revision:Long=live.revision+1,
               keys:Map<String,String> = SecretStore.snapshot()) {
        require(revision >= live.revision) { "Stale runtime config revision" }
        require(providers.map{it.id}.distinct().size==providers.size) { "Duplicate provider id" }
        require(rules.map{it.id}.distinct().size==rules.size) { "Duplicate rule id" }
        val next=LiveConfig(providers.toList(),rules.toList(),virtualModels?.toList()?:live.virtualModels,revision,keys.toMap())
        limiter.updatePolicies(next.providers.filter{it.enabled}.associate{it.id to it.ratePolicy})
        live=next
    }

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            // 同 McpServer：显式绑 0.0.0.0，避免 IPv6-only 导致 127.0.0.1 连不上
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName(bindHost), port))
            serverSocket = ss
            running.set(true)
            acceptThread = Thread({
                while (running.get()) {
                    try {
                        val s = ss.accept()
                        clients.add(s)
                        try { pool.execute { handleConnection(s) } }
                        catch (_: java.util.concurrent.RejectedExecutionException) {
                            clients.remove(s)
                            s.close()
                        }
                    } catch (e: Exception) {
                        if (running.get()) GateLog.error("gw", "accept failed", e)
                    }
                }
            }, "gw-accept").apply { isDaemon = true; start() }
            actualBind = "$bindHost:$port"
            startedAtMs = System.currentTimeMillis()
            GateLog.info("gw", "listening $actualBind providers=${providers.size} rules=${rules.size}")
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
        activeRequests.forEach { it.cancel("server_stopped") }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        pool.shutdownNow()
        GateLog.info("gw", "stopped")
    }

    fun isRunning(): Boolean = running.get()

    // ---- 供 UI 读取的公开诊断访问器（内部实现保持 private）----
    fun pathDiagnosisPublic(): String = pathDiagnosis()
    fun lanHintPublic(): String = lanHint()
    fun acceptAlivePublic(): Boolean = acceptThread?.isAlive == true
    fun tcpConnectionsPublic(): Int = tcpConnections.get()
    fun workerCountPublic(): Int = pool.activeCount
    /** 处理中请求数（供悬浮球判断「生成中」） */
    fun inflightPublic(): Int = inflight.get()
    fun queuedCountPublic(): Int = pool.queue?.size ?: 0
    fun uptimeSecPublic(): Long =
        if (startedAtMs > 0) (System.currentTimeMillis() - startedAtMs) / 1000 else 0

    // ==================== 连接处理 ====================
    private fun handleConnection(socket: Socket) {
        val reqId = "r-" + UUID.randomUUID().toString().take(8)
        val t0 = System.currentTimeMillis()
        val connNow = tcpConnections.incrementAndGet()
        // inflight 是「处理中请求数」，与 tcpConnections（连接数）不同：
        // 前者用于并发诊断与限流观察，之前从未增减导致 diag 恒为 0。
        inflight.incrementAndGet()
        GateLog.info("gw", "conn open #" + connNow + " from=" + (socket.inetAddress?.hostAddress ?: "?"))
        try {
            socket.soTimeout = 30_000   // 客户端读请求头/体，30s 足够，避免长挂占线程
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val head = readHeader(input) ?: return
            val request = SocketIo.parseRequestHead(head)
            val headers = request.headers
            val method = request.method
            val path = request.path
            val denied = accessPolicy.rejectStatus(headers, socket.localPort, socket.inetAddress.isLoopbackAddress)
            if (denied != null) {
                localRejects++
                writeJson(output, denied, "{\"error\":{\"message\":\"access denied\",\"type\":\"auth\"}}")
                return
            }
            val bodyLen = SocketIo.contentLength(headers, SocketIo.MAX_GATEWAY_BODY_BYTES)
            if (method == "POST" && !headers["content-type"].orEmpty().substringBefore(';').trim().equals("application/json", true)) {
                writeJson(output, 415, "{\"error\":{\"message\":\"application/json required\",\"type\":\"invalid_request\"}}")
                return
            }
            val body = readExactly(input, bodyLen)
            val client = socket.inetAddress?.hostAddress ?: "?"
            GateLog.requestStart(reqId, method, path, client, headers, body.size)
            synchronized(this) { totalRequests++ }

            when {
                method == "GET" && path == "/health" -> {
                    writeJson(output, 200, """{"status":"ok","running":${running.get()},"port":$port}""")
                }
                method == "GET" && path == "/v1/_gw/diag" -> {
                    writeJson(output, 200, diagJson())
                }
                method == "GET" && path == "/v1/_gw/status" -> {
                    writeJson(output, 200, statusJson())
                }
                method == "GET" && path == "/v1/models" -> {
                    handleModels(output, reqId)
                }
                method == "POST" && path == "/v1/chat/completions" -> {
                    runChat(output, reqId, body, socket, input)
                }
                else -> {
                    fail(output, 404,
                        """{"error":{"message":"path not found","type":"invalid_request"}}""",
                        "http_404 path not found: " + path,
                        countAsUpstreamFail = false)
                }
            }
            GateLog.info("http", "done rid=$reqId ${System.currentTimeMillis() - t0}ms")
        } catch (e: SocketIo.HttpInputException) {
            localRejects++
            runCatching { writeJson(socket.getOutputStream(), e.status, "{\"error\":{\"message\":\"invalid HTTP framing\",\"type\":\"invalid_request\"}}") }
        } catch (_: java.io.EOFException) {
            localRejects++
            runCatching { writeJson(socket.getOutputStream(), 400, "{\"error\":{\"message\":\"truncated body\",\"type\":\"invalid_request\"}}") }
        } catch (_: java.net.SocketTimeoutException) {
            localRejects++
            runCatching { writeJson(socket.getOutputStream(), 408, "{\"error\":{\"message\":\"request timeout\",\"type\":\"invalid_request\"}}") }
        } catch (e: Exception) {
            GateLog.warn("gw", "connection error rid=$reqId class=" + e.javaClass.simpleName)
        } finally {
            clients.remove(socket)
            tcpConnections.decrementAndGet()
            inflight.decrementAndGet()
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ==================== 路由 ====================
    /**
     * 返回 /v1/models 列表。
     *
     * 修复：原来只遍历 providers，虚拟模型完全不在列表里。
     * 用户建了虚拟模型并连上网关，却在模型列表看不到 —— 正是这个原因。
     *
     * 现在：真实模型（provider 下 enabled 的）+ 虚拟模型（enabled）都列出。
     * 虚拟模型排在最前，方便客户端优先看到聚合入口。
     */
    private fun handleModels(output: OutputStream, reqId: String) {
        val config=synchronized(this){live}
        val providers=config.providers
        val rules=config.rules
        val virtualModels=config.virtualModels
        val vNames = virtualModels.filter { it.enabled }.map { it.name }
        val ids = LinkedHashSet<String>()
        // 虚拟模型优先（聚合入口）
        ids.addAll(vNames)
        // 只列用户勾选过的模型：初次筛选之外的不对外暴露
        providers.filter { it.enabled }.forEach { p ->
            p.models.values.filter { it.enabled }.forEach { ids.add(it.modelId) }
        }

        val data = org.json.JSONArray()
        ids.forEach { id -> data.put(org.json.JSONObject().put("id", id).put("object", "model")
            .put("owned_by", if (id in vNames) "gateway:virtual" else "gateway")) }
        writeJson(output, 200, org.json.JSONObject().put("object", "list").put("data", data).toString())
        GateLog.info(
            "models",
            "served " + ids.size + " models (virtual=" + vNames.size +
                ") rid=" + reqId
        )
    }

    private class DownstreamClosed(cause: java.io.IOException) : java.io.IOException("downstream_closed", cause)
    private class RequestCancelled : java.io.IOException("request_cancelled")

    /** One request owns its connection, commit boundary and terminal record. */
    private class RequestRun(val reqId: String) {
        val started = System.nanoTime()
        val owner = Thread.currentThread()
        val upstream = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>()
        @Volatile var finished = false
        @Volatile var cancelled = false
        @Volatile var cancelReason = "client_disconnect"
        var committed = false
        var sent = false
        var provider = "-"
        var outcome = "upstream_failure"
        var reason = "unexpected_failure"
        var success = false
        fun elapsed() = (System.nanoTime() - started) / 1_000_000.0
        fun checkActive() {
            if (cancelled || Thread.currentThread().isInterrupted) throw RequestCancelled()
        }
        @Synchronized fun attach(conn: HttpURLConnection) {
            checkActive()
            upstream.set(conn)
        }
        @Synchronized fun cancel(why: String) {
            if (finished || cancelled) return
            cancelReason = why
            cancelled = true
            owner.interrupt()
            // Some JDK implementations serialize disconnect with a blocked read. Never block
            // stop()/the cancellation observer on that lock; the read deadline remains a bound.
            upstream.get()?.let { conn ->
                Thread({ runCatching { conn.disconnect() } }, "gw-abort").apply { isDaemon = true; start() }
            }
        }
        @Synchronized fun markSuccess() {
            checkActive()
            success = true; outcome = "success"; reason = "complete_response"
            finished = true
        }
        @Synchronized fun complete() {
            if (finished) return
            if (cancelled || owner.isInterrupted) {
                success = false
                outcome = "cancelled"
                reason = if (cancelled) cancelReason else "interrupted"
            }
            finished = true
        }
        fun output(delegate: OutputStream) = object : OutputStream() {
            private fun send(action: () -> Unit) {
                checkActive()
                // Set BEFORE write: even a throwing write may have transmitted a prefix.
                committed = true
                try { action() } catch (e: java.io.IOException) { throw DownstreamClosed(e) }
            }
            override fun write(b: Int) = send { delegate.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) = send { delegate.write(b, off, len) }
            override fun flush() = send { delegate.flush() }
        }
    }

    // Keep the three-argument entry for JVM integration callers; real sockets use runChat.
    private fun handleChat(output: OutputStream, reqId: String, body: ByteArray) =
        runChat(output, reqId, body, null, null)

    private fun runChat(output: OutputStream, reqId: String, body: ByteArray,
                        socket: Socket?, input: InputStream?) {
        val run = RequestRun(reqId)
        activeRequests.add(run)
        val guarded = run.output(output)
        try {
            if (socket != null && input != null) {
                try { socket.soTimeout = 250 } catch (e: java.io.IOException) { throw DownstreamClosed(e) }
                Thread({
                    while (!run.finished && !run.cancelled) {
                        try {
                            // A FIN may be a legal request-side half-close: NOT proof of cancellation.
                            // A reset/read error is cancellation. No pipelining on Connection: close.
                            if (input.read() < 0) break
                            run.cancel("unexpected_pipelining")
                        } catch (_: java.net.SocketTimeoutException) {
                            // Poll only; inactivity is not cancellation.
                        } catch (_: java.io.IOException) {
                            if (!run.finished) run.cancel("client_disconnect")
                        }
                    }
                }, "gw-cancel-watch").apply { isDaemon = true; start() }
            }
            routeChat(guarded, body, run)
        } catch (_: DownstreamClosed) {
            run.outcome = "cancelled"; run.reason = "client_disconnect"
        } catch (_: RequestCancelled) {
            run.outcome = "cancelled"; run.reason = if (run.cancelled) run.cancelReason else "interrupted"
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            run.outcome = "cancelled"; run.reason = "interrupted"
        } catch (e: Exception) {
            run.outcome = "upstream_failure"; run.reason = e.javaClass.simpleName
            if (!run.committed && !run.cancelled) {
                try { writeJson(guarded, 502, "{\"error\":{\"message\":\"upstream failed\",\"type\":\"upstream\"}}") }
                catch (_: java.io.IOException) { run.outcome = "cancelled"; run.reason = "client_disconnect" }
            }
        } finally {
            run.complete()
            activeRequests.remove(run)
            synchronized(this) {
                when (run.outcome) {
                    "success" -> totalOk++
                    "cancelled" -> { if (run.reason == "client_disconnect") clientDisconnects++; Unit }
                    "local_reject" -> localRejects++
                    else -> { totalFail++; noteFailureTs(); latestFailure = run.reason; latestFailureReason = classifyFailure(run.reason) }
                }
            }
            // The only terminal call for chat requests, including invalid input and cancellation.
            GateLog.requestEnd(reqId, run.success, run.provider, run.elapsed(), listOf(run.outcome, run.reason))
        }
    }

    private fun routeChat(output: OutputStream, body: ByteArray, run: RequestRun) {
        fun reject(code: Int, message: String, type: String = "invalid_request") {
            run.outcome = "local_reject"; run.reason = type
            writeJson(output, code, org.json.JSONObject().put("error", org.json.JSONObject()
                .put("message", message).put("type", type)).toString())
        }
        run.checkActive()
        val bodyStr = String(body, Charsets.UTF_8)
        val json = try {
            val tokener = org.json.JSONTokener(bodyStr)
            val value = tokener.nextValue()
            require(value is org.json.JSONObject && tokener.nextClean() == 0.toChar())
            value
        } catch (_: Exception) { reject(400, "A single JSON object is required"); return }
        val modelId = json.opt("model") as? String
        val streamValue = json.opt("stream")
        if (modelId.isNullOrBlank() || (json.has("stream") && streamValue !is Boolean)) {
            reject(400, "model must be a nonempty string; stream must be boolean"); return
        }
        val stream = streamValue == true
        val config = synchronized(this) { live }
        val vm = config.virtualModels.firstOrNull { it.enabled && it.name == modelId }
        val rule = if (vm?.ruleId?.isNotBlank() == true)
            config.rules.firstOrNull { it.enabled && it.id == vm.ruleId }
        else config.rules.firstOrNull { it.enabled }
        if (rule == null) { reject(503, "No enabled bound rule", "no_rule"); return }
        if (rule.execute.op.ifBlank { "first" } != "first") {
            reject(422, "Configured execute operator is not implemented", "unsupported_operator"); return
        }
        val sessionId = listOf("session_id", "conversation_id", "user")
            .firstNotNullOfOrNull { json.opt(it) as? String }.orEmpty()
        val ctx = RequestCtx(modelId = modelId, reqId = run.reqId, ruleKey = rule.id,
            sessionId = sessionId, turnIndex = SwitchPolicy.deriveTurnIndex(bodyStr), estTokens = bodyStr.length / 3)
        val resolved = VirtualModelResolver.resolve(modelId, config.virtualModels, config.providers)
        var cands = resolved ?: RuleEngine.buildCands(config.providers, ctx)
        if (cands.isEmpty()) { reject(404, "No provider supports requested model", "no_model"); return }
        cands = try { RuleEngine.applySelect(cands, ctx, rule.select) }
        catch (_: Exception) { reject(422, "Rule evaluation failed", "rule"); return }
        if (cands.isEmpty()) { reject(503, "Rule filtered out all providers", "no_candidate"); return }
        val ordered = SwitchPolicy.reorder(cands, rule.switchGranularity, ctx.sessionId, ctx.turnIndex)
        val transformed = if (vm != null && vm.transforms.isNotEmpty()) RequestTransform.apply(vm.transforms, body)
            else TransformedRequest(body, emptyMap(), emptySet(), emptyList())
        var attempted = false
        var denied = false
        var lastError = "no_candidate"
        for (c in ordered) {
            run.checkActive()
            val permit = synchronized(this) { limiter.acquire(c.p.id, rule.rateLimitOverride[c.p.id]) }
            if (!permit.ok) {
                denied = true
                GateLog.rateLimited(run.reqId, c.p.id, permit.waitMs)
                continue
            }
            val at = System.nanoTime()
            var code: Int? = null
            run.provider = c.p.id
            run.sent = false
            try {
                if (permit.waitMs > 0) Thread.sleep(kotlin.math.ceil(permit.waitMs).toLong())
                run.checkActive()
                attempted = true
                val conn = openUpstream(c, transformed, config.keys[c.p.apiKeyRef].orEmpty(), run)
                code = conn.responseCode
                run.checkActive()
                if (code in 200..299) {
                    relay(output, conn, stream)
                    run.markSuccess()
                    val latency = (System.nanoTime() - at) / 1_000_000.0
                    synchronized(c.p) {
                        c.p.latEwma = c.p.latEwma * 0.8 + latency * 0.2
                        c.p.errors = 0; c.p.breaker = BreakerState.CLOSED; c.p.openUntilMs = 0L
                        c.p.lastUsedMs = System.currentTimeMillis()
                    }
                    limiter.noteSuccess(c.p.id, permit.cooldownGeneration)
                    latestRoute = c.m.modelId + "@" + c.p.id
                    SwitchPolicy.onSuccess(rule.switchGranularity, ctx.sessionId, ctx.turnIndex, c.p.id)
                    GateLog.attempt(run.reqId, c.p.id, modelId, true, latency, code, null)
                    return
                }
                // Status only: never place upstream error bodies (possibly secrets/prompts) in logs.
                lastError = "http_$code"
                synchronized(c.p) { c.p.errors++ }
                RuntimeState.noteError(c.p.id)
                if (code == 429) {
                    val seconds = limiter.noteSoftRateLimit(c.p.id)
                    synchronized(c.p) { c.p.breaker = BreakerState.OPEN; c.p.openUntilMs = System.currentTimeMillis() + (seconds * 1000).toLong() }
                }
                GateLog.attempt(run.reqId, c.p.id, modelId, false, (System.nanoTime() - at) / 1_000_000.0, code, lastError)
                // Only explicit rejection/unavailability permits first-policy failover.
                // Transport failures after dispatch (ambiguous billing) NEVER retry.
                if (code !in setOf(429, 503)) break
            } catch (e: Exception) {
                val cancelled = e is DownstreamClosed || e is RequestCancelled || e is InterruptedException || run.cancelled
                if (cancelled) {
                    GateLog.attempt(run.reqId, c.p.id, modelId, false, (System.nanoTime() - at) / 1_000_000.0,
                        code, "cancelled", cancelled = true)
                    if (e is InterruptedException) Thread.currentThread().interrupt()
                    if (e is DownstreamClosed) throw e
                    throw RequestCancelled()
                }
                synchronized(c.p) { c.p.errors++ }
                RuntimeState.noteError(c.p.id)
                lastError = e.javaClass.simpleName
                GateLog.attempt(run.reqId, c.p.id, modelId, false, (System.nanoTime() - at) / 1_000_000.0, code, lastError)
                if (run.sent || run.committed) break
            } finally {
                val conn = run.upstream.getAndSet(null)
                try { conn?.disconnect() } catch (_: Exception) {}
                limiter.release(c.p.id)
            }
        }
        run.checkActive()
        if (!attempted && denied) { reject(429, "All eligible providers are locally rate limited", "rate_limit"); return }
        run.reason = lastError
        if (!run.committed) writeJson(output, 502,
            "{\"error\":{\"message\":\"upstream failed\",\"type\":\"upstream\"}}")
    }

    private fun openUpstream(c: Cand, tr: TransformedRequest, apiKey: String, run: RequestRun): HttpURLConnection {
        val conn = URL(c.p.baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        try {
            run.attach(conn) // ownership precedes EVERY operation that may open a socket
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.connectTimeout = 20_000; conn.readTimeout = upstreamReadTimeoutMs
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream, application/json")
            tr.setHeaders.forEach { (k, v) -> if (v.isNotEmpty()) conn.setRequestProperty(k, v) }
            if (apiKey.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
            val bytes = org.json.JSONObject(String(tr.body, Charsets.UTF_8)).put("model", c.m.modelId)
                .toString().toByteArray(Charsets.UTF_8)
            // Fixed streaming prevents HttpURLConnection transparently replaying buffered POSTs.
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.connect()
            run.checkActive()
            run.sent = true // outputStream may partially transmit before throwing
            conn.outputStream.use { it.write(bytes) }
            return conn
        } catch (e: Exception) {
            if (run.upstream.get() !== conn) runCatching { conn.disconnect() }
            throw e
        }
    }

    /** Byte-preserving relay. Successful headers are not successful completion. */
    private fun relay(output: OutputStream, conn: HttpURLConnection, stream: Boolean) {
        val code = conn.responseCode
        val isSse = conn.contentType.orEmpty().substringBefore(';').trim().equals("text/event-stream", true)
        if (stream && !isSse) throw java.io.IOException("Expected SSE response")
        val expected = SocketIo.responseLength(conn)
        conn.inputStream.use { input ->
            output.write(("HTTP/1.1 $code OK\r\nContent-Type: " +
                (if (isSse) "text/event-stream" else "application/json") +
                "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            output.flush()
            val tracker = if (isSse) SocketIo.SseCompletion() else null
            val buf = ByteArray(4096)
            var count = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                tracker?.accept(buf, n)
                output.write(buf, 0, n); output.flush()
                count += n
            }
            if (expected != null && count != expected) throw java.io.EOFException("Truncated upstream body")
            tracker?.requireComplete()
        }
    }

    // ==================== HTTP 工具 ====================
    // readHeader / readExactly 已抽到 SocketIo（原与 McpServer 各实现一份，逻辑相同）
    private fun readHeader(input: InputStream): String? = SocketIo.readHeader(input)

    private fun readExactly(input: InputStream, len: Int): ByteArray =
        SocketIo.readExactly(input, len)

    /**
     * 统一失败出口。
     * @param countAsUpstreamFail true=计入 totalFail（上游问题）
     *                            false=本地拒绝（401/404 等），只计数不污染成功率
     */
    private fun fail(
        output: OutputStream,
        code: Int,
        json: String,
        reason: String,
        countAsUpstreamFail: Boolean = true,
    ) {
        if (countAsUpstreamFail) {
            totalFail++
            noteFailureTs()   // 本地拒绝（401/404）不算上游失败，不记入窗口
        } else {
            localRejects++
        }
        latestFailure = reason
        latestFailureReason = classifyFailure(reason)
        GateLog.warn("gw", "fail code=" + code + " reason=" + reason)
        writeJson(output, code, json)
    }

    private fun writeJson(output: OutputStream, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        output.write("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n".toByteArray())
        if (code == 401) output.write("WWW-Authenticate: Bearer realm=\"GatewayRules\"\r\n".toByteArray())
        output.write("Cache-Control: no-store\r\n".toByteArray())
        output.write("Content-Type: application/json\r\n".toByteArray())
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }


    /**
     * 诊断端点 —— 供外部（AI/curl）自助排查，无需人工手测。
     * 暴露：日志目录、各配置文件路径与条目数、设置快照、限流实时状态。
     */
    private fun diagJson(): String {
        val config=synchronized(this){live}
        val providers=config.providers
        val rules=config.rules
        val virtualModels=config.virtualModels
        val sb = StringBuilder()
        sb.append("{\"running\":").append(running.get())
        sb.append(",\"port\":").append(port)
        sb.append(",\"logDir\":\"").append(esc(esc(logDirPath))).append("\"")
        sb.append(",\"configDir\":\"").append(esc(esc(configDirPath))).append("\"")
        sb.append(",\"configRevision\":").append(config.revision)
        sb.append(",\"providers\":").append(providers.size)
        sb.append(",\"rules\":").append(rules.size)
        sb.append(",\"virtualModels\":").append(virtualModels.size)
        sb.append(",\"requests\":").append(totalRequests)
        sb.append(",\"ok\":").append(totalOk)
        sb.append(",\"fail\":").append(totalFail)
        sb.append(",\"localRejects\":").append(localRejects)
        sb.append(",\"inflight\":").append(inflight.get())
        val sw = SwitchPolicy.stats()
        sb.append(",\"switch\":{\"bindings\":").append(sw["bindings"])
        sb.append(",\"sessions\":").append(sw["sessions"]).append("}")

        // ---- 诊断块（对应 DeepSeek 本地 API 的实时监控面板）----
        sb.append(",\"diag\":{")
        sb.append("\"actualBind\":\"").append(esc(esc(actualBind))).append("\"")
        sb.append(",\"acceptAlive\":").append(acceptThread?.isAlive == true)
        sb.append(",\"tcpConnections\":").append(tcpConnections.get())
        sb.append(",\"clientDisconnects\":").append(clientDisconnects)
        sb.append(",\"httpWorkers\":").append(pool.activeCount)
        sb.append(",\"httpQueued\":").append(pool.queue?.size ?: 0)
        sb.append(",\"poolSize\":").append(pool.poolSize)
        val upSec = if (startedAtMs > 0)
            (System.currentTimeMillis() - startedAtMs) / 1000 else 0
        sb.append(",\"uptimeSec\":").append(upSec)
        sb.append(",\"latestRoute\":\"").append(esc(esc(latestRoute))).append("\"")
        sb.append(",\"latestFailure\":\"").append(esc(esc(latestFailure))).append("\"")
        sb.append(",\"latestFailureReason\":\"")
            .append(esc(esc(latestFailureReason))).append("\"")
        sb.append(",\"pathDiagnosis\":\"").append(esc(esc(pathDiagnosis()))).append("\"")
        sb.append(",\"lanHint\":\"").append(esc(esc(lanHint()))).append("\"")
        // 失败窗口：悬浮球「中断」状态的判据来源，便于排查
        sb.append(",\"failWin60s\":").append(recentFailureCountPublic(60_000L))
        sb.append(",\"lastFailAgoMs\":").append(lastFailureAgoMsPublic())
        // 共享 WakeLock 状态：两个服务共用一把物理锁，ref 应为 2 而非各自一把
        sb.append(",\"wakeRef\":").append(WakeLockHolder.refCountPublic())
        sb.append(",\"wakeHeld\":").append(WakeLockHolder.isHeldPublic())
        // 看门狗持有者：两个服务各自持有，全部退出才会停
        val wdHolders = FloatingBall.watchdogHoldersPublic()
        sb.append(",\"watchdogHolders\":\"")
            .append(esc(wdHolders.joinToString(","))).append("\"")
        sb.append(",\"watchdogCount\":").append(wdHolders.size)
        sb.append("}")
        // 限流实时
        sb.append(",\"rate\":[")
        providers.forEachIndexed { i, p ->
            if (i > 0) sb.append(",")
            sb.append("{\"id\":\"").append(esc(esc(p.id))).append("\",")
            sb.append("\"peek\":").append(peekJson(p.id))
            sb.append(",\"softRestSec\":").append(limiter.softRestRemainingSec(p.id))
            sb.append("}")
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
            sb.append(",\"ratePerMin\":").append(limiter.policyOf(p.id).ratePerMin)
            sb.append(",\"minIntervalMs\":").append(limiter.policyOf(p.id).minIntervalMs)
            sb.append(",\"maxInflight\":").append(limiter.policyOf(p.id).maxInflight)
            sb.append("}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /** 日志目录（由 MainActivity 注入，供 diag 暴露） */
    @Volatile var logDirPath: String = ""
    @Volatile var configDirPath: String = ""

    private fun statusJson(): String {
        val config=synchronized(this){live}
        val providers=config.providers
        val rules=config.rules
        val virtualModels=config.virtualModels
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

    /**
     * 失败原因分类 —— 面板上直接显示人话，不用翻日志猜。
     * 对应 DeepSeek 本地 API 的 "Failure reasons" 一栏。
     */
    private fun classifyFailure(err: String?): String {
        val e = (err ?: "").lowercase()
        return when {
            e.isEmpty() -> "未知"
            e.contains("no enabled rule") -> "没有启用的规则"
            e.contains("no_candidate") -> "规则把候选全过滤了"
            e.contains("model_not_found") -> "模型不存在（检查模型名或 provider 配置）"
            e.contains("rule error") -> "规则执行出错"
            e.contains("unauthorized") -> "鉴权失败（本地 API Key 不匹配）"
            e.contains("path not found") -> "路径错误（base_url 应带 /v1）"
            e.contains("rate_limited") || e.contains("soft_rest") -> "被限流，已进入休息"
            e.contains("connection refused") -> "连接被拒（上游端口不通）"
            e.contains("failed to connect") || e.contains("connectexception") -> "连不上上游（网络或域名）"
            e.contains("timed out") || e.contains("timeout") -> "上游超时"
            e.contains("unknownhost") || e.contains("no address") -> "域名解析失败"
            e.contains("ssl") || e.contains("certificate") -> "SSL 证书错误"
            e.contains("http_401") -> "鉴权失败（Key 无效）"
            e.contains("http_403") -> "被拒绝（权限/风控）"
            e.contains("http_404") -> "路径错误（baseUrl 可能不对）"
            e.contains("http_429") -> "被限流（429）"
            e.contains("http_5") -> "上游服务端错误"
            e.contains("model_not_found") -> "模型不存在"
            else -> "其他：" + (err ?: "").take(40)
        }
    }

    /**
     * 路径诊断 —— 直接给出"为什么连不上"的结论。
     *
     * 这是 DeepSeek 本地 API 面板里最值钱的一条：
     *   "Listener is healthy but no TCP connection arrived;
     *    use 127.0.0.1 on-device or the LAN endpoint from another device"
     *
     * 它把"服务在跑"和"没人连进来"区分开，省掉大量盲猜。
     */
    private fun pathDiagnosis(): String {
        if (!running.get()) return "服务未启动"
        if (acceptThread?.isAlive != true) return "accept 线程已死，需重启网关"

        val conns = tcpConnections.get()
        val reqs = totalRequests
        return when {
            conns == 0 && reqs == 0L ->
                "监听正常但尚无任何 TCP 连接到达；" +
                    "本机请用 http://127.0.0.1:$port/v1，" +
                    "局域网设备请用本机 LAN IP:$port"
            conns == 0 && reqs > 0 ->
                "监听正常，已有 $reqs 次请求处理完毕，当前无活跃连接（空闲状态，正常）"
            conns > 0 && reqs == 0L ->
                "有 $conns 个 TCP 连接但未产生请求；可能是客户端握手失败或被中间设备干扰"
            inflight.get() >= 32 ->
                "并发已接近上限（${inflight.get()}），慢请求可能拖慢响应"
            totalFail > 0 && totalFail >= totalOk ->
                "失败数已超过成功数（失败 $totalFail / 成功 $totalOk），检查上游 key 与网络"
            else ->
                "运行正常：$totalOk 成功 / $totalFail 失败，当前 $conns 个连接"
        }
    }

    /** 局域网访问提示：告诉用户别的设备该填什么地址 */
    private fun lanHint(): String {
        return try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
            val ips = mutableListOf<String>()
            while (ifaces.hasMoreElements()) {
                val nif = ifaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is java.net.Inet4Address && !a.isLoopbackAddress) {
                        ips.add("http://" + a.hostAddress + ":" + port + "/v1")
                    }
                }
            }
            if (ips.isEmpty()) "未检测到局域网地址（仅本机 127.0.0.1 可用）"
            else ips.joinToString(" | ")
        } catch (e: Exception) {
            "局域网地址探测失败: " + e.message
        }
    }

    // 转义已抽到 JsonUtil（原与 GateLog 各一份，且都漏了控制字符）
    private fun esc(s: String) = JsonUtil.esc(s)


}

/** API Key 解析：契约里只存 apiKeyRef，实际密钥由调用方注入 */
object SecretStore {
    @Volatile private var map:Map<String,String> = emptyMap()
    @Synchronized fun put(ref:String,key:String) { map=map+(ref to key) }
    fun resolve(p:Provider):String=map[p.apiKeyRef]?:""
    @Synchronized fun replace(keys:Map<String,String>) { map=keys.toMap() }
    fun snapshot():Map<String,String> = map
    @Synchronized fun clear() { map=emptyMap() }
}
