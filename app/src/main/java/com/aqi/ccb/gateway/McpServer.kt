package com.aqi.ccb.gateway

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 轻量 MCP 服务端（JSON-RPC 2.0 over HTTP，streamable-http 风格）。
 *
 * 目的：让 AI（如 RikkaHub / Claude Desktop / 任意 MCP 客户端）
 * 能直接连上来调试网关：
 *   - 批量测试模型
 *   - 查看 provider / 规则 / 虚拟模型
 *   - 改动配置
 *   - 查日志
 *
 * 实现的是 MCP 核心方法子集，够 AI 调试用：
 *   initialize / tools/list / tools/call
 *
 * 端口默认 38081（与网关 38080 分开，避免混淆），可配置。
 *
 * 协议要点：
 *   POST /mcp  body={"jsonrpc":"2.0","id":1,"method":"...","params":{...}}
 *   Content-Type: application/json
 *   返回 {"jsonrpc":"2.0","id":1,"result":{...}}
 */
class McpServer(
    val port: Int,   // 公开：供 UI 显示「实际监听的端口」
    private val gatewayPort: Int,
    private val accessToken: String,
    private val configProvider: () -> McpConfig,
) {
    private val accessPolicy = ServerAccessPolicy(accessToken)
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    // 同 GatewayServer：固定池 + 长任务（gw_test_models 可跑 30s+）会堵死 MCP
    private val pool = java.util.concurrent.ThreadPoolExecutor(
        2, 16, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue(),
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
    )

    @Volatile var lastError: String? = null
    @Volatile var totalCalls: Long = 0
    @Volatile var totalErrors: Long = 0
    @Volatile var lastCallAt: Long = 0L
    @Volatile var lastMethod: String = ""
    @Volatile var lastClient: String = ""
    @Volatile var lastLatencyMs: Long = 0L
    @Volatile var lastResult: String = ""

    /** 供设置页显示的状态快照 */
    fun snapshot(): Map<String, Any?> = mapOf(
        "running" to running.get(),
        "port" to port,
        "gatewayPort" to gatewayPort,
        "url" to "http://127.0.0.1:$port/mcp",
        "calls" to totalCalls,
        "errors" to totalErrors,
        "lastMethod" to lastMethod,
        "lastClient" to lastClient,
        "lastLatencyMs" to lastLatencyMs,
        "lastResult" to lastResult,
        "lastCallAt" to lastCallAt,
        "lastError" to lastError,
    )

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            // 关键修复：ServerSocket(port) 在某些 Android 上只绑 [::]（IPv6-only），
            // 导致客户端用 127.0.0.1 连不上。显式绑 0.0.0.0 保证 IPv4 + IPv6 双栈可用。
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), port))
            serverSocket = ss
            running.set(true)
            acceptThread = Thread({
                while (running.get()) {
                    try {
                        val s = ss.accept()
                        clients.add(s)
                        try { pool.execute { handle(s) } }
                        catch (_: java.util.concurrent.RejectedExecutionException) {
                            clients.remove(s)
                            s.close()
                        }
                    } catch (e: Exception) {
                        if (running.get()) GateLog.error("mcp", "accept failed", e)
                    }
                }
            }, "mcp-accept").apply { isDaemon = true; start() }
            GateLog.info("mcp", "listening 127.0.0.1:$port gateway=$gatewayPort")
            true
        } catch (e: Exception) {
            lastError = e.toString()
            GateLog.error("mcp", "start failed port=$port", e)
            false
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        pool.shutdownNow()
        GateLog.info("mcp", "stopped")
    }

    fun isRunning(): Boolean = running.get()

    // ==================== 连接处理 ====================
    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 60_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val head = readHeader(input) ?: return
            val request = SocketIo.parseRequestHead(head)
            val headers = request.headers
            val method = request.method
            val path = request.path
            val denied = accessPolicy.rejectStatus(headers, socket.localPort, socket.inetAddress.isLoopbackAddress)
            if (denied != null) {
                writeJson(output, denied, "{\"error\":\"access denied\"}")
                return
            }
            val bodyLen = SocketIo.contentLength(headers, SocketIo.MAX_MCP_BODY_BYTES)
            if (method == "GET" && path == "/health") {
                writeJson(output, 200, "{\"status\":\"ok\",\"mcp\":true,\"port\":$port,\"gateway\":$gatewayPort}")
                return
            }
            if (method != "POST" || path != "/mcp") {
                writeJson(output, 404, "{\"error\":\"not found\"}")
                return
            }
            if (!headers["content-type"].orEmpty().substringBefore(';').trim().equals("application/json", true)) {
                writeJson(output, 415, "{\"error\":\"application/json required\"}")
                return
            }
            val body = readExactly(input, bodyLen)

            totalCalls++
            lastCallAt = System.currentTimeMillis()
            lastClient = socket.inetAddress?.hostAddress ?: "?"
            val reqStr = String(body, Charsets.UTF_8)
            val m0 = System.currentTimeMillis()
            val rpcMethod = runCatching {
                JSONObject(reqStr).optString("method")
            }.getOrDefault("?")
            lastMethod = rpcMethod
            val resp = handleRpc(reqStr)
            lastLatencyMs = System.currentTimeMillis() - m0
            if (resp == null) {
                // 通知：202 Accepted，空响应体
                lastResult = "notification"
                writeEmpty(output, 202)
            } else {
                val isErr = resp.contains("\"error\"")
                lastResult = if (isErr) "error" else "ok"
                if (isErr) totalErrors++
                GateLog.info(
                    "mcp", "rpc " + rpcMethod + " -> " + lastResult +
                        " " + lastLatencyMs + "ms from=" + lastClient +
                        " bytes=" + body.size
                )
                writeJson(output, 200, resp)
            }
        } catch (e: SocketIo.HttpInputException) {
            runCatching { writeJson(socket.getOutputStream(), e.status, "{\"error\":\"invalid HTTP framing\"}") }
        } catch (_: java.io.EOFException) {
            runCatching { writeJson(socket.getOutputStream(), 400, "{\"error\":\"truncated body\"}") }
        } catch (_: java.net.SocketTimeoutException) {
            runCatching { writeJson(socket.getOutputStream(), 408, "{\"error\":\"request timeout\"}") }
        } catch (e: Exception) {
            GateLog.warn("mcp", "handle failed: " + e.javaClass.simpleName)
        } finally {
            clients.remove(socket)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ==================== JSON-RPC ====================
    /** 返回 null 表示这是 JSON-RPC 通知，不得回包（HTTP 202 空体） */
    private fun handleRpc(reqStr: String): String? {
        val id: Any? = try {
            val o = JSONObject(reqStr)
            o.opt("id")
        } catch (e: Exception) {
            null
        }
        return try {
            val req = JSONObject(reqStr)
            val method = req.optString("method")
            val params = req.optJSONObject("params") ?: JSONObject()

            // STRX 同款：没有 id 即通知，不返回任何响应体
            if (!req.has("id")) {
                GateLog.info("mcp", "notification " + method + " -> 202 (no body)")
                return null
            }

            when (method) {
                "initialize" -> rpcOk(id, initializeResult())
                "notifications/initialized" -> rpcOk(id, JSONObject())
                "tools/list" -> rpcOk(id, toolsList())
                "tools/call" -> rpcOk(id, toolsCall(params))
                "ping" -> rpcOk(id, JSONObject())
                else -> rpcErr(id, -32601, "method not found: $method")
            }
        } catch (e: Exception) {
            rpcErr(id, -32603, "internal error: ${e.message}")
        }
    }

    private fun initializeResult(): JSONObject = JSONObject().apply {
        put("protocolVersion", "2024-11-05")
        put("capabilities", JSONObject().apply {
            put("tools", JSONObject())
        })
        put("serverInfo", JSONObject().apply {
            put("name", "gateway-rules-mcp")
            put("version", "0.5.0")
        })
    }

    private fun toolsList(): JSONObject {
        val arr = JSONArray()
        arr.put(tool(
            "gw_list_providers",
            "列出所有已配置的提供商及其模型（含测试结果）",
            JSONObject().put("type", "object").put("properties", JSONObject())
        ))
        arr.put(tool(
            "gw_add_provider",
            "添加或更新一个提供商。会先自动探测 baseUrl 后缀，再拉取模型列表",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("name", schema("string", "提供商名称"))
                    put("baseUrl", schema("string", "Base URL，如 https://api.openai.com"))
                    put("apiKey", schema("string", "API Key"))
                })
                put("required", JSONArray().put("name").put("baseUrl"))
            }
        ))
        arr.put(tool(
            "gw_test_models",
            "测试已勾选模型，最多20个，可能产生上游费用；必须confirm=true。目录可达不等于生成能力，取消不保证零费用。",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("providerId", schema("string", "提供商 ID"))
                    put("confirm", schema("boolean", "明确同意测试最多20个已勾选模型，可能计费"))
                })
                put("required", JSONArray().put("providerId").put("confirm"))
                put("additionalProperties",false)
            }
        ))
        arr.put(tool(
            "gw_list_rules",
            "列出所有规则（含切换粒度与限流配置）",
            JSONObject().put("type", "object").put("properties", JSONObject())
        ))
        arr.put(tool(
            "gw_list_virtual_models",
            "列出所有虚拟模型及其成员解析状态",
            JSONObject().put("type", "object").put("properties", JSONObject())
        ))
        arr.put(tool(
            "gw_get_status",
            "获取网关运行状态：请求数、限流、熔断、切换粒度绑定",
            JSONObject().put("type", "object").put("properties", JSONObject())
        ))
        arr.put(tool(
            "gw_reload_config",
            "热加载配置：重新读取 providers/rules/virtual_models 并推给运行中的网关，" +
                "无需重启 App。改完配置文件后调一次即可生效。",
            JSONObject().put("type", "object").put("properties", JSONObject())
        ))
        // ==================== 写操作工具 ====================
        // 存在理由：原来 MCP 只有读和 add_provider，改限流/开关模型/增删规则
        // 全得点 UI。有 MCP 还模拟点击是设计缺陷。

        arr.put(tool(
            "gw_update_provider",
            "修改提供商属性（限流/开关/tier/weight/quota/baseUrl 等）。" +
                "只传要改的字段，其余保持原值。保存后自动热加载。",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("providerId", str("提供商 ID 或 name 派生出的 idOr"))
                    put("patch", obj("要修改的字段。可用：name baseUrl apiKey enabled tier " +
                        "weight quota ratePerMin burst minIntervalMs jitterMs expectedRevision " +
                        "maxInflight softRateSec softRateMaxSec suffixStyle"))
                })
                put("required", JSONArray().put("providerId").put("patch"))
            }
        ))

        arr.put(tool(
            "gw_delete_provider",
            "删除提供商。保存后自动热加载。",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("providerId", str("提供商 ID"))
                })
                put("required", JSONArray().put("providerId"))
            }
        ))

        arr.put(tool(
            "gw_update_model",
            "修改单个模型属性（启停/类型/能力/模态/内置工具/上下文/单价）。" +
                "abilities 可选 TOOL,REASONING；type 可选 CHAT,IMAGE,EMBEDDING；" +
                "tools 可选 SEARCH,URL_CONTEXT,IMAGE_GENERATION。保存后自动热加载。",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("providerId", str("提供商 ID"))
                    put("modelId", str("模型 ID"))
                    put("patch", obj("要修改的字段。可用：displayName enabled type abilities " +
                        "inputModalities outputModalities tools contextLength " +
                        "priceInPerM priceOutPerM expectedRevision。数组字段传 JSON 数组。价格null=未知，0=明确零价；输入输出两项齐全才参与价格排序。"))
                })
                put("required", JSONArray().put("providerId").put("modelId").put("patch"))
            }
        ))

        arr.put(tool(
            "gw_set_models_enabled",
            "批量设置模型启用状态。传模型 ID 数组，一次开关多个。保存后自动热加载。",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("providerId", str("提供商 ID"))
                    put("modelIds", arr2("模型 ID 数组，如 [\"gpt-oss-20b\",\"BAAI/bge-m3\"]"))
                    put("enabled", bool("true=启用，false=禁用"))
                })
                put("required", JSONArray().put("providerId").put("modelIds").put("enabled"))
            }
        ))

        arr.put(tool(
            "gw_save_rule",
            "保存规则（传完整规则 JSON，id 存在则覆盖）。保存后自动热加载。",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("rule", obj("完整规则对象，至少含 id"))
                })
                put("required", JSONArray().put("rule"))
            }
        ))

        arr.put(tool(
            "gw_delete_rule",
            "删除规则。保存后自动热加载。",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("ruleId", str("规则 ID"))
                })
                put("required", JSONArray().put("ruleId"))
            }
        ))

        arr.put(tool(
            "gw_save_virtual_model",
            "保存虚拟模型（name 存在则覆盖）。会先校验每个 member 的 providerId 是否真实存在，" +
                "避免写错 ID 导致虚拟模型静默失效。保存后自动热加载。",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("virtualModel", obj("完整虚拟模型对象：{name, description, enabled, " +
                        "ruleId, members:[{providerId, modelId, priority, weight}]}"))
                })
                put("required", JSONArray().put("virtualModel"))
            }
        ))

        arr.put(tool(
            "gw_delete_virtual_model",
            "删除虚拟模型。保存后自动热加载。",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("name", str("虚拟模型名称"))
                })
                put("required", JSONArray().put("name"))
            }
        ))

        arr.put(tool(
            "gw_read_log",
            "读取系统日志尾部",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("lines", JSONObject().apply {
                        put("type", "integer")
                        put("description", "读取行数，默认 50")
                    })
                })
            }
        ))
        arr.put(tool(
            "gw_get_ui_style",
            "读取当前 UI 样式配置：基准圆角、收缩下限、右下角固定角、透明度等。" +
                "圆角算法为行数自适应：行数≤2 用基准角，行数>2 每行收缩，右下角恒为小角。",
            JSONObject().put("type", "object").put("properties", JSONObject())
        ))
        arr.put(tool(
            "gw_set_ui_style",
            "修改 UI 样式（只传要改的字段，其余保持不变）。改完即时生效，无需重启。" +
                "可用字段：baseRadius(基准圆角dp,默认22) / minRadius(收缩下限,12) / " +
                "bottomEndRadius(右下角固定,8) / shrinkStep(每行收缩dp,2) / " +
                "shrinkAfterLines(超过几行开始收缩,2) / dynamicRadius(是否启用自适应,true) / " +
                "cardAlpha(卡片不透明度0-100,100) / innerCornerEnabled(分组内小圆角,true)",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("baseRadius", schema("integer", "基准圆角 dp，默认 22"))
                    put("minRadius", schema("integer", "圆角收缩下限 dp，默认 12"))
                    put("bottomEndRadius", schema("integer", "右下角固定圆角 dp，默认 8"))
                    put("shrinkStep", schema("integer", "每多一行收缩的 dp，默认 2"))
                    put("shrinkAfterLines", schema("integer", "超过几行开始收缩，默认 2"))
                    put("dynamicRadius", schema("boolean", "是否启用行数自适应"))
                    put("cardAlpha", schema("integer", "卡片不透明度 0-100"))
                    put("innerCornerEnabled", schema("boolean", "分组内是否用小圆角"))
                })
            }
        ))
        fun debugSchema(properties: JSONObject, required: List<String> = emptyList()) = JSONObject().put("type","object")
            .put("properties",properties).put("additionalProperties",false).put("required",JSONArray(required))
        fun integer(min: Int,max: Int,default:Int) = JSONObject().put("type","integer").put("minimum",min).put("maximum",max).put("default",default)
        val logFields=JSONObject().put("stream",str("system or calls; default system").put("enum",JSONArray(listOf("system","calls"))))
            .put("limit",integer(1,200,50)).put("requestId",str("Exact X-Gateway-Request-Id, optional"))
            .put("kind",str("request/attempt/rate_limit/result/diagnostic; calls only"))
            .put("level",str("INFO/WARN/ERROR; system only")).put("contains",str("Case-insensitive substring AFTER redaction"))
            .put("cursor",str("Opaque continuation from the previous matching query; omit for newest snapshot"))
        arr.put(tool("gw_log_query","后台读取脱敏系统/调用日志；限1MiB扫描、200条、16文件，返回游标与截断信息；不返回认证头或原始正文。",debugSchema(logFields)))
        arr.put(tool("gw_trace_request","按响应X-Gateway-Request-Id追踪request/attempt/result；有游标须继续读，未找到终态不代表成功。",debugSchema(JSONObject()
            .put("requestId",str("r-xxxxxxxx response id")).put("limit",integer(1,200,50)).put("cursor",str("Opaque cursor")),listOf("requestId"))))
        arr.put(tool("gw_debug_snapshot","后台快照：持久化与运行版本、进程内网关状态、连接、限流、熔断；不暴露密钥，不启动Activity。",debugSchema(JSONObject())))
        arr.put(tool("gw_explain_route","后台只读选路解释，支持虚拟模型绑定规则；不发网络请求、不修改轮转状态；不代表实际限流准入。",debugSchema(JSONObject()
            .put("modelId",str("Requested real or virtual model")) .put("sessionId",str("Optional routing session id"))
            .put("requestId",str("Optional deterministic routing identity")).put("turnIndex",integer(0,1000000,0)),listOf("modelId"))))
        arr.put(tool("gw_gateway_control","启动/停止网关服务，不切前台Activity、不停止MCP。必须confirm；LAN启动另需allowLan。accepted不等于completed，轮询快照核实。",debugSchema(JSONObject()
            .put("action",str("start or stop").put("enum",JSONArray(listOf("start","stop"))))
            .put("confirm",bool("Explicit permission to change gateway runtime")) .put("allowLan",bool("Explicit approval only when configured LAN binding is enabled")),listOf("action","confirm"))))
        arr.put(tool("gw_self_test","后台隔离自检：真实临时网关和回环上游，认证/列表/JSON/SSE/失败/单次尝试；零外部请求，不改真实配置。一次仅一个诊断，结果声明未覆盖范围。",debugSchema(JSONObject())))
        arr.put(tool("gw_probe_request","通过运行中的本机网关执行一次受限真实请求，可能计费；confirm必填，maxTokens1..64，超时1..15秒，不返回提示词/响应正文。返回requestId供追踪；禁止自动重试。",debugSchema(JSONObject()
            .put("modelId",str("Configured model or virtual model")) .put("confirm",bool("Explicit consent to one potentially billable request"))
            .put("stream",bool("Whether to exercise SSE; default false")) .put("maxTokens",integer(1,64,16))
            .put("timeoutMs",integer(1000,15000,10000)) .put("prompt",str("Optional prompt up to512 chars; default Reply with OK.")),listOf("modelId","confirm"))))
        return JSONObject().apply { put("tools", arr) }
    }

    private fun toolsCall(params: JSONObject): JSONObject {
        val name = params.optString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val cfg = configProvider()

        val text: String = try {
            val required=when(name) {
                "gw_update_provider" -> mapOf("providerId" to "string","patch" to "object")
                "gw_delete_provider", "gw_test_models" -> mapOf("providerId" to "string")
                "gw_update_model" -> mapOf("providerId" to "string","modelId" to "string","patch" to "object")
                "gw_set_models_enabled" -> mapOf("providerId" to "string","modelIds" to "array","enabled" to "boolean")
                "gw_save_rule" -> mapOf("rule" to "object")
                "gw_delete_rule" -> mapOf("ruleId" to "string")
                "gw_save_virtual_model" -> mapOf("virtualModel" to "object")
                "gw_delete_virtual_model" -> mapOf("name" to "string")
                else -> emptyMap()
            }
            required.forEach { (key,type) ->
                val value=args.opt(key)
                require(when(type){"string"->value is String && value.isNotBlank();"object"->value is JSONObject;"array"->value is JSONArray;"boolean"->value is Boolean;else->false}) { "Invalid or missing argument: $key" }
            }
            when (name) {
                "gw_list_providers" -> cfg.listProvidersJson()
                "gw_add_provider" -> cfg.addProvider(
                    args.optString("name"),
                    args.optString("baseUrl"),
                    args.optString("apiKey"),
                )
                "gw_test_models" -> {
                    require(args.opt("confirm")==true) { "confirm=true required: selected-model tests may incur upstream charges" }
                    require(args.keys().asSequence().all{it in setOf("providerId","confirm")}) { "Unknown test argument" }
                    cfg.testModels(args.getString("providerId"))
                }
                "gw_list_rules" -> cfg.listRulesJson()
                "gw_list_virtual_models" -> cfg.listVirtualModelsJson()
                "gw_get_status" -> cfg.statusJson(gatewayPort)
                "gw_reload_config" -> cfg.reloadConfig()
                "gw_update_provider" -> cfg.updateProvider(
                    args.optString("providerId"), args.optJSONObject("patch")?.toString() ?: "{}"
                )
                "gw_delete_provider" -> cfg.deleteProvider(args.optString("providerId"))
                "gw_update_model" -> cfg.updateModel(
                    args.optString("providerId"), args.optString("modelId"),
                    args.optJSONObject("patch")?.toString() ?: "{}"
                )
                "gw_set_models_enabled" -> cfg.setModelsEnabled(
                    args.optString("providerId"),
                    args.optJSONArray("modelIds")?.toString() ?: "[]",
                    args.optBoolean("enabled", true)
                )
                "gw_save_rule" -> cfg.saveRule(
                    args.optJSONObject("rule")?.toString() ?: "{}"
                )
                "gw_delete_rule" -> cfg.deleteRule(args.optString("ruleId"))
                "gw_save_virtual_model" -> cfg.saveVirtualModel(
                    args.optJSONObject("virtualModel")?.toString() ?: "{}"
                )
                "gw_delete_virtual_model" -> cfg.deleteVirtualModel(args.optString("name"))
                "gw_read_log" -> cfg.readLog(args.optInt("lines", 50))
                "gw_log_query", "gw_trace_request", "gw_debug_snapshot", "gw_explain_route",
                "gw_gateway_control", "gw_self_test", "gw_probe_request" -> cfg.debugTool(name,args.toString())
                "gw_get_ui_style" -> cfg.getUiStyleJson()
                "gw_set_ui_style" -> cfg.setUiStyle(args.toString())
                else -> """{"error":"unknown tool: $name"}"""
            }
        } catch (e: Exception) {
            JSONObject().put("ok",false).put("code","INVALID_ARGUMENT").put("error",e.message ?: e.javaClass.simpleName).toString()
        }

        return JSONObject().apply {
            put("content", JSONArray().put(JSONObject().apply {
                put("type", "text")
                put("text", text)
            }))
            val payload = runCatching { JSONObject(text) }.getOrNull()
            put("isError", payload == null ||
                (payload.has("ok") && !payload.optBoolean("ok", false)) ||
                (payload.has("error") && !payload.isNull("error")))
        }
    }

    // ==================== 工具方法 ====================
    private fun str(desc: String): JSONObject =
        JSONObject().put("type", "string").put("description", desc)

    private fun obj(desc: String): JSONObject =
        JSONObject().put("type", "object").put("description", desc)

    private fun arr2(desc: String): JSONObject =
        JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", desc)

    private fun bool(desc: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", desc)

    private fun tool(name: String, desc: String, schema: JSONObject): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("description", desc)
            put("inputSchema", schema)
        }

    private fun schema(type: String, desc: String): JSONObject =
        JSONObject().apply { put("type", type); put("description", desc) }

    private fun rpcOk(id: Any?, result: JSONObject): String =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            put("result", result)
        }.toString()

    private fun rpcErr(id: Any?, code: Int, msg: String): String =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", msg)
            })
        }.toString()

    // readHeader / readExactly 已抽到 SocketIo（原与 GatewayServer 各实现一份）
    private fun readHeader(input: InputStream): String? = SocketIo.readHeader(input)

    private fun readExactly(input: InputStream, len: Int): ByteArray =
        SocketIo.readExactly(input, len)

    private fun writeJson(output: OutputStream, code: Int, json: String) {
        writeResp(output, code, json)
    }

    /** 202 / 204 这类无体响应 */
    private fun writeEmpty(output: OutputStream, code: Int) {
        val head = "HTTP/1.1 " + code + " Accepted\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun httpReason(code: Int): String = when (code) {
        200 -> "OK"; 202 -> "Accepted"; 204 -> "No Content"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        408 -> "Request Timeout"; 413 -> "Content Too Large"; 415 -> "Unsupported Media Type"
        417 -> "Expectation Failed"; 431 -> "Request Header Fields Too Large"
        else -> "Error"
    }

    private fun writeResp(output: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(" ").append(httpReason(code)).append("\r\n")
        if (code == 401) sb.append("WWW-Authenticate: Bearer realm=\"GatewayRules-MCP\"\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Content-Type: application/json\r\n")
        sb.append("Content-Length: ").append(bytes.size).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        output.write(sb.toString().toByteArray())
        if (bytes.isNotEmpty()) output.write(bytes)
        output.flush()
    }
}

/**
 * MCP 服务端与 App 数据层的桥接。
 * 由 MainActivity 实现，避免 MCP 层直接依赖 Android Context。
 */
interface McpConfig {
    fun listProvidersJson(): String
    fun addProvider(name: String, baseUrl: String, apiKey: String): String
    fun testModels(providerId: String): String
    fun listRulesJson(): String
    fun listVirtualModelsJson(): String
    fun statusJson(gatewayPort: Int): String
    fun readLog(lines: Int): String
    fun debugTool(name:String,argsJson:String):String = "{\"ok\":false,\"code\":\"DIAGNOSTICS_UNAVAILABLE\",\"error\":\"Bridge does not implement diagnostics\"}"
    /** 读取 UI 样式配置（圆角/透明度等） */
    fun getUiStyleJson(): String
    /** 按 JSON patch 修改 UI 样式，返回改后完整配置 */
    fun setUiStyle(patchJson: String): String

    /**
     * 热加载配置：重新读取 providers/rules/virtual_models 并推给运行中的网关，
     * 不需要重启 App。
     *
     * 为什么需要：改一次规则要 force-stop + 重启（约 15 秒），
     * 实测 20 条规则要等 5 分钟，而且 force-stop 不触发 onDestroy，
     * 用户看到的是「App 突然消失」，会误以为崩溃。
     */
    fun reloadConfig(): String

    // ==================== 写操作：让 MCP 成为完整 CLI ====================
    //
    // 下面这些接口存在的原因：原来 MCP 只有「读」和「add_provider」，
    // 改限流、开关模型、调价格、增删规则、改虚拟模型全得点 UI。
    // 有 MCP 还要模拟点击是设计缺陷，不是使用方式问题。

    /** 按 JSON patch 修改提供商；返回改后完整对象 */
    fun updateProvider(providerId: String, patchJson: String): String

    /** 删除提供商 */
    fun deleteProvider(providerId: String): String

    /**
     * 修改单个模型的属性。
     * patch 支持：enabled / type / abilities / inputModalities /
     *             outputModalities / tools / contextLength /
     *             priceInPerM / priceOutPerM / displayName
     */
    fun updateModel(providerId: String, modelId: String, patchJson: String): String

    /** 批量设置模型 enabled（一次开关多个） */
    fun setModelsEnabled(providerId: String, modelIdsJson: String, enabled: Boolean): String

    /** 保存规则（传入完整规则 JSON；id 存在则覆盖） */
    fun saveRule(ruleJson: String): String

    /** 删除规则 */
    fun deleteRule(ruleId: String): String

    /** 保存虚拟模型（传入完整 JSON；name 存在则覆盖） */
    fun saveVirtualModel(vmJson: String): String

    /** 删除虚拟模型 */
    fun deleteVirtualModel(name: String): String
}
