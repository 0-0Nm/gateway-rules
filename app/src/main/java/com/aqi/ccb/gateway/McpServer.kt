package com.aqi.ccb.gateway

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
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
    private val port: Int,
    private val gatewayPort: Int,
    private val configProvider: () -> McpConfig,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(4)

    @Volatile var lastError: String? = null
    @Volatile var totalCalls: Long = 0

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val ss = ServerSocket(port)
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
                writeJson(output, 404, """{"error":"not found"}""")
                return
            }

            totalCalls++
            val reqStr = String(body, Charsets.UTF_8)
            val resp = handleRpc(reqStr)
            writeJson(output, 200, resp)
        } catch (e: Exception) {
            GateLog.error("mcp", "handle error", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ==================== JSON-RPC ====================
    private fun handleRpc(reqStr: String): String {
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
                "gw_read_log" -> cfg.readLog(args.optInt("lines", 50))
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

    private fun readHeader(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(b.toChar())
            if (prev == '\r'.code && b == '\n'.code) {
                if (sb.toString().endsWith("\r\n\r\n")) return sb.toString().dropLast(2)
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
        writeResp(output, code, json)
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
}
