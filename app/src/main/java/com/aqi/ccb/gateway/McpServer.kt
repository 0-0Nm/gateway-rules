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
    private val configProvider: () -> McpConfig,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    // 同 GatewayServer：固定池 + 长任务（gw_test_models 可跑 30s+）会堵死 MCP
    private val pool = java.util.concurrent.ThreadPoolExecutor(
        2, 16, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue(),
        java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
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
            ss.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("0.0.0.0"), port))
            serverSocket = ss
            running.set(true)
            acceptThread = Thread({
                while (running.get()) {
                    try {
                        val s = ss.accept()
                        pool.execute { handle(s) }
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
            val lines = head.split("\r\n")
            if (lines.isEmpty()) return
            val reqLine = lines[0].split(" ")
            if (reqLine.size < 2) return
            val method = reqLine[0]
            val path = reqLine[1]

            val headers = LinkedHashMap<String, String>()
            for (i in 1 until lines.size) {
                val idx = lines[i].indexOf(':')
                if (idx > 0) headers[lines[i].substring(0, idx).trim().lowercase()] =
                    lines[i].substring(idx + 1).trim()
            }
            val bodyLen = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (bodyLen > 0) readExactly(input, bodyLen) else ByteArray(0)

            // CORS 预检
            if (method == "OPTIONS") {
                writeResp(output, 204, "")
                return
            }

            if (method == "GET" && (path.startsWith("/health") || path == "/")) {
                writeJson(output, 200, """{"status":"ok","mcp":true,"port":$port,"gateway":$gatewayPort}""")
                return
            }

            if (method != "POST" || !path.startsWith("/mcp")) {
                GateLog.warn("mcp", "reject " + method + " " + path + " from=" + socket.inetAddress?.hostAddress)
                writeJson(output, 404, """{"error":"not found"}""")
                return
            }

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
        } catch (e: Exception) {
            GateLog.error("mcp", "handle error", e)
        } finally {
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
            put("version", "0.3.0")
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
            "批量测试某个提供商的全部模型，返回每个模型的延迟与可用性",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("providerId", schema("string", "提供商 ID"))
                })
                put("required", JSONArray().put("providerId"))
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
        return JSONObject().apply { put("tools", arr) }
    }

    private fun toolsCall(params: JSONObject): JSONObject {
        val name = params.optString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val cfg = configProvider()

        val text: String = try {
            when (name) {
                "gw_list_providers" -> cfg.listProvidersJson()
                "gw_add_provider" -> cfg.addProvider(
                    args.optString("name"),
                    args.optString("baseUrl"),
                    args.optString("apiKey"),
                )
                "gw_test_models" -> cfg.testModels(args.optString("providerId"))
                "gw_list_rules" -> cfg.listRulesJson()
                "gw_list_virtual_models" -> cfg.listVirtualModelsJson()
                "gw_get_status" -> cfg.statusJson(gatewayPort)
                "gw_reload_config" -> cfg.reloadConfig()
                "gw_read_log" -> cfg.readLog(args.optInt("lines", 50))
                "gw_get_ui_style" -> cfg.getUiStyleJson()
                "gw_set_ui_style" -> cfg.setUiStyle(args.toString())
                else -> """{"error":"unknown tool: $name"}"""
            }
        } catch (e: Exception) {
            """{"error":"${e.message?.replace("\"", "'")}"}"""
        }

        return JSONObject().apply {
            put("content", JSONArray().put(JSONObject().apply {
                put("type", "text")
                put("text", text)
            }))
            put("isError", false)
        }
    }

    // ==================== 工具方法 ====================
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

    private fun writeResp(output: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(if (code == 200) " OK" else " No Content").append("\r\n")
        sb.append("Content-Type: application/json\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n")
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
}
