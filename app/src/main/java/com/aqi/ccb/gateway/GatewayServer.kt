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
    private var providers: List<Provider>,
    private var rules: List<RuleSpec>,
    private val limiter: RateLimiter,
    private val localAuth: String = "",
    private var virtualModels: List<VirtualModel> = emptyList()
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    // 修复假死：原来 fixed(8) + 无容量控制，慢请求（上游 120s）堆满后
    // accept 仍继续但无工作线程，表现为整个网关无响应（连 /health 都挂）。
    // 改为 cached 池并封顶 64，慢请求不会阻塞新连接的短请求。
    private val pool = java.util.concurrent.ThreadPoolExecutor(
        4, 64, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue(),
        java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
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
            actualBind = "0.0.0.0:$port"
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
                fail(output, 401,
                    """{"error":{"message":"unauthorized","type":"auth"}}""",
                    "http_401 unauthorized",
                    countAsUpstreamFail = false)
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
                    fail(output, 404,
                        """{"error":{"message":"path not found","type":"invalid_request"}}""",
                        "http_404 path not found: " + path,
                        countAsUpstreamFail = false)
                }
            }
            GateLog.info("http", "done rid=$reqId ${System.currentTimeMillis() - t0}ms")
        } catch (e: Exception) {
            GateLog.error("gw", "connection error rid=$reqId", e)
        } finally {
            tcpConnections.decrementAndGet()
            inflight.decrementAndGet()
            // clientDisconnects 语义：每个连接结束就 +1（原来只在连接数归零时 +1，
            // 并发下数字完全失真）。
            clientDisconnects++
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
        val vNames = virtualModels.filter { it.enabled }.map { it.name }
        val ids = LinkedHashSet<String>()
        // 虚拟模型优先（聚合入口）
        ids.addAll(vNames)
        // 只列用户勾选过的模型：初次筛选之外的不对外暴露
        providers.filter { it.enabled }.forEach { p ->
            p.models.values.filter { it.enabled }.forEach { ids.add(it.modelId) }
        }

        val data = ids.joinToString(",") { id ->
            val owner = if (id in vNames) "gateway:virtual" else "gateway"
            """{"id":"$id","object":"model","owned_by":"$owner"}"""
        }
        writeJson(output, 200, """{"object":"list","data":[$data]}""")
        GateLog.info(
            "models",
            "served " + ids.size + " models (virtual=" + vNames.size +
                ") rid=" + reqId
        )
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
            fail(output, 503,
                """{"error":{"message":"no enabled rule","type":"no_rule"}}""",
                "no enabled rule")
            return
        }

        // ---- 虚拟模型优先: 客户端请求的 model 名若命中虚拟模型, 用其绑定的 members 做候选 ----
        val resolved = VirtualModelResolver.resolve(modelId, virtualModels, providers)
        // 该虚拟模型绑定的请求变换链（指纹清洗/UA伪装/prompt 注入等）
        val vmTransforms: List<TransformSpec> = if (resolved != null) {
            virtualModels.firstOrNull { it.name == modelId && it.enabled }?.transforms ?: emptyList()
        } else emptyList()
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
            fail(output, 404,
                """{"error":{"message":"no provider supports model '$modelId'","type":"no_model"}}""",
                "model_not_found: " + modelId)
            return
        }

        cands = try {
            RuleEngine.applySelect(cands, ctx, rule.select)
        } catch (e: Exception) {
            fail(output, 500,
                """{"error":{"message":"rule error: ${esc(e.message ?: "")}","type":"rule"}}""",
                "rule error: " + (e.message ?: ""))
            return
        }

        if (cands.isEmpty()) {
            fail(output, 503,
                """{"error":{"message":"rule filtered out all providers","type":"no_candidate"}}""",
                "rule filtered all: no_candidate")
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

        // ---- 执行算子分派 ----
        // 之前这里完全没读 rule.execute.op，用户在 UI 选 race/hedge 等
        // 实际都跑 first，属于静默忽略配置。
        // 现在：已实现的算子正常分派；未实现的显式降级并记录，绝不静默。
        val execOp = rule.execute.op.ifBlank { "first" }
        val IMPLEMENTED_OPS = setOf("first")
        if (execOp !in IMPLEMENTED_OPS) {
            GateLog.warn(
                "execute",
                "op=" + execOp + " 尚未实现，降级为 first（reqId=" + reqId + "）"
            )
        }

        // ---- 顺序尝试（first 语义）----
        // ---- 请求变换：转发前应用（只影响发往上游的 body 与头，不改本地逻辑）----
        val transformed: TransformedRequest = if (vmTransforms.isNotEmpty()) {
            RequestTransform.apply(vmTransforms, body).also { tr ->
                GateLog.info(
                    "transform",
                    "vmodel=" + modelId + " chain=" + tr.trace.joinToString(",") +
                        " body " + body.size + "->" + tr.body.size
                )
            }
        } else {
            TransformedRequest(body, emptyMap(), emptySet(), emptyList())
        }

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
                val conn = openUpstream(c, transformed, targetModelId)
                val code = conn.responseCode
                if (code in 200..299) {
                    val latency = (System.currentTimeMillis() - at).toDouble()
                    c.p.latEwma = c.p.latEwma * 0.8 + latency * 0.2
                    c.p.errors = 0
                    // 关键修复：成功即恢复熔断。
                    // 原来只清 errors，breaker 永远停在 OPEN，
                    // status 面板一直显示 OPEN 且 opBreakerFilter 长期误判。
                    if (c.p.breaker != BreakerState.CLOSED) {
                        GateLog.info("breaker", "recover provider=" + c.p.id)
                        c.p.breaker = BreakerState.CLOSED
                        c.p.openUntilMs = 0L
                    }
                    c.p.lastUsedMs = System.currentTimeMillis()
                    limiter.noteSuccess(c.p.id)   // 成功即重置软限流退避
                    latestRoute = c.m.modelId + "@" + c.p.id
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
                    // 429 / 限流：软限流指数退避（照 wb.apk 的 soft_rate_sec）
                    // 首次歇 softRateSec（默认600s），连续被限 2 倍递增，封顶 softRateMaxSec
                    val isRateLimited = code == 429 ||
                        (errBody != null && looksRateLimited(errBody))
                    if (isRateLimited) {
                        val restSec = limiter.noteSoftRateLimit(c.p.id)
                        c.p.breaker = BreakerState.OPEN
                        c.p.openUntilMs = System.currentTimeMillis() + (restSec * 1000).toLong()
                        GateLog.warn(
                            "ratelimit",
                            "soft_rest provider=" + c.p.id + " " + restSec.toInt() +
                                "s streak=" + RuntimeState.errorRate(c.p.id, 3_600_000)
                        )
                    }
                    // ---- 参数自动修复：400/422 + 文案指向参数问题时，改名重试一次 ----
                    // 照 wb.apk 的 "max_tokens rejected -> rewritten, retrying in place"
                    if ((code == 400 || code == 422) && errBody != null) {
                        val fix = RequestTransform.autoFixParams(transformed.body, errBody)
                        if (fix != null) {
                            val fixedBody = fix.first
                            val fixDesc = fix.second
                            GateLog.warn(
                                "transform",
                                "auto_fix provider=" + c.p.id + " " + fixDesc + " retrying"
                            )
                            runCatching {
                                conn.disconnect()
                                val retryConn = openUpstream(
                                    c, transformed.copy(body = fixedBody), targetModelId
                                )
                                val retryCode = retryConn.responseCode
                                if (retryCode in 200..299) {
                                    c.p.errors = 0
                                    limiter.noteSuccess(c.p.id)
                                    latestRoute = c.m.modelId + "@" + c.p.id
                                    GateLog.attempt(
                                        reqId, c.p.id, modelId, true,
                                        (System.currentTimeMillis() - at).toDouble(),
                                        retryCode, null
                                    )
                                    relay(output, retryConn, stream)
                                    GateLog.requestEnd(
                                        reqId, true, c.p.id,
                                        (System.currentTimeMillis() - reqStart).toDouble(),
                                        c.why + listOf("auto_fix:" + fixDesc)
                                    )
                                    totalOk++
                                    return
                                } else {
                                    try { retryConn.disconnect() } catch (_: Exception) {}
                                }
                            }
                        }
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

        val failReason = lastErr ?: "unknown"
        totalFail++
        noteFailureTs()   // 记入失败窗口，供«中断»状态判定
        latestFailure = failReason
        latestFailureReason = classifyFailure(failReason)
        GateLog.requestEnd(reqId, false, "-", 0.0, listOf("all_failed"))
        writeJson(output, 502, """{"error":{"message":"all providers failed: ${esc(lastErr ?: "")}","type":"upstream"}}""")
    }

    /**
     * 构造上游请求。
     * @param tr 已应用变换的请求（body + 头注入/删除指令）
     * @param targetModelId 虚拟模型命中时需改写的真实 modelId
     */
    private fun openUpstream(
        c: Cand,
        tr: TransformedRequest,
        targetModelId: String,
    ): HttpURLConnection {
        val base = c.p.baseUrl.trimEnd('/')
        val url = URL("$base/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 90_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream, application/json")

        // 变换注入的头（如伪装 UA）
        tr.setHeaders.forEach { (k, v) ->
            if (v.isNotEmpty()) conn.setRequestProperty(k, v)
        }

        val key = SecretStore.resolve(c.p)
        if (key.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $key")

        // 虚拟模型命中：把 body 里的 model 改写成该 member 的真实 modelId
        var outBody = tr.body
        if (targetModelId.isNotEmpty() && targetModelId != c.m.modelId) {
            // targetModelId 已是首个 member；每个候选应各自改写
        }
        if (targetModelId.isNotEmpty()) {
            outBody = runCatching {
                val j = org.json.JSONObject(String(tr.body, Charsets.UTF_8))
                j.put("model", c.m.modelId)
                j.toString().toByteArray(Charsets.UTF_8)
            }.getOrDefault(tr.body)
        }

        conn.outputStream.use { it.write(outBody) }
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

    /** 上游即使回 200，正文仍可能写着限流——识别出来同样走软限流 */
    private fun looksRateLimited(err: String): Boolean {
        val s = err.lowercase()
        return s.contains("rate limit") || s.contains("rate_limit") ||
            s.contains("too many requests") || s.contains("quota exceeded") ||
            s.contains("请求过于频繁") || s.contains("限流") ||
            s.contains("调用频率")
    }

    // 转义已抽到 JsonUtil（原与 GateLog 各一份，且都漏了控制字符）
    private fun esc(s: String) = JsonUtil.esc(s)

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
