package com.aqi.ccb.data

import android.content.Context
import com.aqi.ccb.gateway.ConfigStore
import com.aqi.ccb.gateway.GateLog
import com.aqi.ccb.gateway.McpConfig
import com.aqi.ccb.gateway.Provider
import com.aqi.ccb.gateway.VirtualModelStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AppSettings —— 应用级开关与配置（非 provider/rule 类）。
 * 存 SharedPreferences，简单可靠。
 */
object AppSettings {
    private const val PREF = "app_settings"

    // 悬浮窗
    const val KEY_FLOAT_ENABLED = "float_enabled"
    // MCP
    const val KEY_MCP_ENABLED = "mcp_enabled"
    const val KEY_MCP_PORT = "mcp_port"
    // 网关
    const val KEY_GATEWAY_PORT = "gateway_port"
    // 提示弹窗
    const val KEY_TOAST_ENABLED = "toast_enabled"

    const val DEFAULT_MCP_PORT = 38081
    const val DEFAULT_GATEWAY_PORT = 38080

    fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun floatEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLOAT_ENABLED, true)

    fun setFloatEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_FLOAT_ENABLED, v).apply()

    fun mcpEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MCP_ENABLED, false)

    fun setMcpEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_MCP_ENABLED, v).apply()

    fun mcpPort(context: Context): Int =
        prefs(context).getInt(KEY_MCP_PORT, DEFAULT_MCP_PORT)

    fun setMcpPort(context: Context, p: Int) =
        prefs(context).edit().putInt(KEY_MCP_PORT, p).apply()

    fun gatewayPort(context: Context): Int =
        prefs(context).getInt(KEY_GATEWAY_PORT, DEFAULT_GATEWAY_PORT)

    fun setGatewayPort(context: Context, p: Int) =
        prefs(context).edit().putInt(KEY_GATEWAY_PORT, p).apply()

    /** 界面提示弹窗（Toast/Snackbar）开关 */
    fun toastEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TOAST_ENABLED, true)

    fun setToastEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_TOAST_ENABLED, v).apply()
}

/**
 * MCP 桥接实现 —— 让 McpServer 能读写 App 数据，而不直接依赖 Context。
 */
class McpBridge(private val context: Context) : McpConfig {

    private fun providers(): List<Provider> = ProviderRepo.runtimeProviders(context)

    override fun listProvidersJson(): String {
        val drafts = ProviderRepo.list(context)
        val arr = JSONArray()
        drafts.forEach { d ->
            val p = JSONObject().apply {
                put("id", d.idOr())
                put("name", d.name)
                put("baseUrl", d.baseUrl)
                put("enabled", d.enabled)
                put("modelCount", d.models.size)
                put("models", JSONArray().apply {
                    d.models.forEach { m ->
                        put(JSONObject().apply {
                            put("modelId", m.modelId)
                            put("available", m.available ?: JSONObject.NULL)
                            put("latencyMs", m.latencyMs)
                            put("lastError", m.lastError)
                            put("abilities", JSONArray(m.abilities))
                        })
                    }
                })
                put("rateLimit", JSONObject().apply {
                    put("ratePerMin", d.ratePerMin.toIntOrNull() ?: 30)
                    put("burst", d.burst.toIntOrNull() ?: 3)
                    put("minIntervalMs", d.minIntervalMs.toIntOrNull() ?: 800)
                    put("jitterMs", d.jitterMs.toIntOrNull() ?: 150)
                    put("maxInflight", d.maxInflight.toIntOrNull() ?: 2)
                })
            }
            arr.put(p)
        }
        return JSONObject().apply { put("providers", arr) }.toString(2)
    }

    override fun addProvider(name: String, baseUrl: String, apiKey: String): String {
        if (name.isBlank() || baseUrl.isBlank()) {
            return """{"error":"name 和 baseUrl 必填"}"""
        }
        // 自动探测后缀
        val probe = com.aqi.ccb.gateway.BaseUrlProbe.probe(baseUrl, apiKey)
        if (!probe.ok) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "端点探测失败")
                put("tried", JSONArray().apply {
                    probe.tried.forEach { (u, r) -> put("$u → $r") }
                })
            }.toString(2)
        }
        val resolved = probe.resolvedUrl!!
        val models = com.aqi.ccb.gateway.BaseUrlProbe.listModels(resolved, apiKey)
        if (models.isEmpty()) {
            return """{"ok":false,"error":"端点可用但无模型列表","resolved":"$resolved"}"""
        }
        val draft = ProviderDraft(
            name = name,
            baseUrl = resolved,
            apiKey = apiKey,
            models = models.map { ModelInfo(modelId = it) },
            suffixStyle = probe.style?.name ?: "AUTO",
        )
        val saved = ProviderRepo.save(context, draft)
        return JSONObject().apply {
            put("ok", saved)
            put("id", draft.idOr())
            put("resolvedBaseUrl", resolved)
            put("style", probe.style?.name ?: "AUTO")
            put("modelCount", models.size)
            put("models", JSONArray(models))
        }.toString(2)
    }

    override fun testModels(providerId: String): String {
        val d = ProviderRepo.list(context).firstOrNull { it.idOr() == providerId }
            ?: return """{"error":"provider not found: $providerId"}"""
        if (d.models.isEmpty()) return """{"ok":false,"error":"该提供商无模型"}"""

        val results = JSONArray()
        var okCount = 0
        // MCP 里同步执行，最多测 20 个避免超时
        d.models.take(20).forEach { m ->
            val tested = com.aqi.ccb.gateway.BatchTester.testOne(d.baseUrl, d.apiKey, m)
            if (tested.available == true) okCount++
            results.put(JSONObject().apply {
                put("modelId", tested.modelId)
                put("available", tested.available ?: false)
                put("latencyMs", tested.latencyMs)
                put("lastError", tested.lastError)
            })
        }
        // 回写测试结果
        val tested = d.models.map { m ->
            val r = results.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .firstOrNull { it.optString("modelId") == m.modelId }
            }
            if (r != null) m.copy(
                available = r.optBoolean("available"),
                latencyMs = r.optLong("latencyMs"),
                lastError = r.optString("lastError"),
                testedAt = System.currentTimeMillis(),
            ) else m
        }
        ProviderRepo.save(context, d.copy(models = tested))

        return JSONObject().apply {
            put("providerId", providerId)
            put("total", d.models.size)
            put("tested", results.length())
            put("available", okCount)
            put("results", results)
        }.toString(2)
    }

    override fun listRulesJson(): String =
        JSONObject().apply {
            put("rules", JSONArray(ConfigStore.loadRules(context)))
        }.toString(2)

    override fun listVirtualModelsJson(): String {
        val vms = VirtualModelStore.load(context)
        val provs = providers()
        val arr = JSONArray()
        com.aqi.ccb.gateway.VirtualModelResolver.status(vms, provs).forEach { m ->
            arr.put(JSONObject(m as Map<*, *>))
        }
        return JSONObject().apply { put("virtualModels", arr) }.toString(2)
    }

    override fun statusJson(gatewayPort: Int): String = JSONObject().apply {
        put("gatewayPort", gatewayPort)
        put("mcpPort", AppSettings.mcpPort(context))
        put("providers", ProviderRepo.list(context).size)
        put("rules", ConfigStore.loadRules(context).length())
        put("virtualModels", VirtualModelStore.load(context).size)
        put("switch", JSONObject().apply {
            put("bindings", com.aqi.ccb.gateway.SwitchPolicy.stats()["bindings"] ?: 0)
            put("sessions", com.aqi.ccb.gateway.SwitchPolicy.stats()["sessions"] ?: 0)
        })
    }.toString(2)

    override fun readLog(lines: Int): String = try {
        val f = File(context.filesDir, "logs/system/gateway.log")
        if (!f.exists()) """{"log":"(empty)"}"""
        else JSONObject().apply {
            put("lines", JSONArray(f.readLines().takeLast(lines.coerceIn(1, 500))))
        }.toString(2)
    } catch (e: Exception) {
        """{"error":"${e.message}"}"""
    }
}
