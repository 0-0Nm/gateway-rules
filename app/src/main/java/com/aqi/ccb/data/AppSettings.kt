package com.aqi.ccb.data

import android.content.Context
import com.aqi.ccb.gateway.ConfigStore
import com.aqi.ccb.gateway.GateLog
import com.aqi.ccb.gateway.GatewayRuntime
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

    /**
     * MCP 服务开关。
     *
     * 默认值改为 true，与悬浮窗（默认 true）保持一致。
     * 之前默认 false 导致首次启动「悬浮窗开着、MCP 关着」的割裂状态。
     * MCP 仅管理接口仅回环监听并要求独立令牌；新安装默认关闭，显式配置的旧开关保留。
     */
    fun mcpEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MCP_ENABLED, false)

    fun setMcpEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_MCP_ENABLED, v).apply()

    fun mcpPort(context: Context): Int =
        prefs(context).getInt(KEY_MCP_PORT, DEFAULT_MCP_PORT)

    fun setMcpPort(context: Context, p: Int) {
        require(p in 1024..65535) { "port out of range" }
        prefs(context).edit().putInt(KEY_MCP_PORT, p).apply()
    }

    fun gatewayPort(context: Context): Int =
        prefs(context).getInt(KEY_GATEWAY_PORT, DEFAULT_GATEWAY_PORT)

    fun setGatewayPort(context: Context, p: Int) {
        require(p in 1024..65535) { "port out of range" }
        prefs(context).edit().putInt(KEY_GATEWAY_PORT, p).apply()
    }

    fun gatewayLanEnabled(context: Context): Boolean =
        prefs(context).getBoolean("gateway_lan_enabled", false)

    fun setGatewayLanEnabled(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean("gateway_lan_enabled", enabled).commit())
    }

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
    init { GatewayRuntime.configurePublisher() }

    private fun providers(): List<Provider> = ProviderRepo.runtimeProviders(context)

    override fun listProvidersJson(): String {
        val state=ConfigStore.read(context)
        val list=state.providers.map { p ->
            JSONObject(p.toString()).apply { remove("apiKey");remove("apiKeyRef") }
        }
        return JSONObject().put("revision",state.revision).put("providers",JSONArray(list)).toString(2)
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
        // ---- 初次筛选 ----
        // 上游模型不照单全收。
        //
        // 按 GREED 的要求：拉到的模型先全部标为「未勾选」，
        // 用户在本 App 的模型列表里逐个勾选后，才会出现在 /v1/models、
        // 才参与选路、才可能被调用。
        //
        // 为什么不在这一步就探测每个模型的能力（TOOL/REASONING/VISION）：
        //   实测上游一次返回 104 个模型，逐个探测要发 104 次请求，
        //   按 30s 超时算可能 10 分钟以上，且会大量消耗额度。
        //   所以拆成两步：这里只拉清单，能力探测由「探测」按钮按需触发，
        //   用户可以先勾选少数几个再探测它们。
        val draft = ProviderDraft(
            name = name,
            baseUrl = resolved,
            apiKey = apiKey,
            models = models.map { ModelInfo(modelId = it, enabled = false) },
            suffixStyle = probe.style?.name ?: "AUTO",
        )
        val committed = ProviderRepo.saveCommitted(context, draft)
        return committed.json().apply {
            put("id", draft.idOr())
            put("resolvedBaseUrl", resolved)
            put("style", probe.style?.name ?: "AUTO")
            put("modelCount", models.size)
            put("enabledCount", 0)
            put(
                "note",
                "已拉取 " + models.size + " 个模型，全部为「未勾选」状态。" +
                    "请在模型列表中勾选需要使用的模型，勾选后才会出现在 /v1/models 并参与选路。"
            )
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
        val committed = ProviderRepo.saveCommitted(context, d.copy(models = tested))

        return committed.json().apply {
            put("providerId", providerId)
            put("total", d.models.size)
            put("tested", results.length())
            put("available", okCount)
            put("results", results)
        }.toString(2)
    }

    override fun listRulesJson(): String =
        ConfigStore.read(context).let { JSONObject().put("revision",it.revision).put("rules",JSONArray(it.rules)).toString(2) }

    override fun listVirtualModelsJson():String {
        val state=ConfigStore.read(context)
        val runtime=ConfigStore.toProviders(state.providers)
        val vms=state.virtualModels.map{com.aqi.ccb.gateway.VirtualModel.fromJson(it)}
        return JSONObject().put("revision",state.revision).put("virtualModels",JSONArray(state.virtualModels))
            .put("resolution",JSONArray(com.aqi.ccb.gateway.VirtualModelResolver.status(vms,runtime).map{JSONObject(it as Map<*,*>)})).toString(2)
    }
    override fun statusJson(gatewayPort:Int):String {
        val state=ConfigStore.read(context)
        return JSONObject().put("gatewayPort",GatewayRuntime.listeningPort().takeIf{it>0}?:gatewayPort)
            .put("mcpPort",AppSettings.mcpPort(context)).put("persistedRevision",state.revision)
            .put("runtimeRevision",com.aqi.ccb.gateway.GatewayService.current?.configRevisionPublic()?:JSONObject.NULL)
            .put("providers",state.providers.size).put("rules",state.rules.size).put("virtualModels",state.virtualModels.size).toString(2)
    }

    override fun getUiStyleJson(): String =
        com.aqi.ccb.ui.UiStyle.load(context).toJson().toString(2)

    override fun setUiStyle(patchJson: String): String {
        return try {
            val patch = JSONObject(patchJson)
            // 白名单：只接受已知字段，防止任意键污染
            val allowed = setOf(
                "baseRadius", "minRadius", "bottomEndRadius", "shrinkStep",
                "shrinkAfterLines", "dynamicRadius", "cardAlpha", "innerCornerEnabled"
            )
            val clean = JSONObject()
            patch.keys().forEach { k ->
                if (k in allowed) clean.put(k, patch.get(k))
            }
            if (clean.length() == 0) {
                val list = allowed.joinToString(",") { "\"" + it + "\"" }
                return """{"ok":false,"error":"无有效字段","allowed":[$list]}"""
            }
            val next = com.aqi.ccb.ui.UiStyle.patch(context, clean)
            GateLog.info("ui", "style patched by MCP: " + clean)
            next.toJson().toString(2)
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message?.replace("\"", "'")}"}"""
        }
    }

    /**
     * 读系统日志。
     *
     * 修复：原来读 filesDir（内部），但 GateLog 实际写到
     * getExternalFilesDir（外部，供 shell 读取）——路径不符导致
     * 通过 MCP 永远拿到 {"log":"(empty)"}。
     * 现在优先读外部目录，回退内部（兼容旧装）。
     */
    /**
     * 热加载配置。
     *
     * 通过 GatewayRuntime 门面操作，不直接够 GatewayService.current——
     * 数据层不应依赖具体的 Service 单例，否则网关托管方式一变就要改多处。
     *
     * 注意必须用块体（{}）而非表达式体（= try { ... }）：
     * Kotlin 的表达式体不允许 return。
     */
    private fun command(action: () -> com.aqi.ccb.gateway.ConfigStore.Commit): String = try {
        action().json().toString(2)
    } catch (e: com.aqi.ccb.gateway.ConfigStore.ConfigException) {
        JSONObject().put("ok",false).put("persisted",false).put("applied",false)
            .put("code",e.code).put("error",e.message).toString()
    } catch (e: Exception) {
        JSONObject().put("ok",false).put("persisted",false).put("applied",false)
            .put("code","INVALID_REQUEST").put("error",e.javaClass.simpleName).toString()
    }
    override fun reloadConfig(): String = command { ConfigStore.applyCurrent(context) }
    override fun updateProvider(providerId:String,patchJson:String):String = command {
        com.aqi.ccb.gateway.ConfigCommands.updateProvider(context,providerId,JSONObject(patchJson))
    }
    override fun deleteProvider(providerId:String):String = command {
        com.aqi.ccb.gateway.ConfigCommands.deleteProvider(context,providerId)
    }
    override fun updateModel(providerId:String,modelId:String,patchJson:String):String = command {
        com.aqi.ccb.gateway.ConfigCommands.updateModel(context,providerId,modelId,JSONObject(patchJson))
    }
    override fun setModelsEnabled(providerId:String,modelIdsJson:String,enabled:Boolean):String = command {
        val a=JSONArray(modelIdsJson)
        com.aqi.ccb.gateway.ConfigCommands.setModelsEnabled(context,providerId,
            (0 until a.length()).map{a.getString(it)}.toSet(),enabled)
    }
    override fun saveRule(ruleJson:String):String = command {
        com.aqi.ccb.gateway.ConfigCommands.saveRule(context,JSONObject(ruleJson))
    }
    override fun deleteRule(ruleId:String):String = command {
        com.aqi.ccb.gateway.ConfigCommands.deleteRule(context,ruleId)
    }
    override fun saveVirtualModel(vmJson:String):String = command {
        com.aqi.ccb.gateway.ConfigCommands.saveVirtualModel(context,JSONObject(vmJson))
    }
    override fun deleteVirtualModel(name:String):String = command {
        com.aqi.ccb.gateway.ConfigCommands.deleteVirtualModel(context,name)
    }

    override fun readLog(lines: Int): String = try {
        val n = lines.coerceIn(1, 500)
        val extBase = try {
            context.getExternalFilesDir(null) ?: context.filesDir
        } catch (_: Exception) {
            context.filesDir
        }
        val candidates = listOf(
            File(extBase, "logs/system/gateway.log"),
            File(context.filesDir, "logs/system/gateway.log"),
        )
        val f = candidates.firstOrNull { it.exists() && it.length() > 0 }
        if (f == null) {
            JSONObject().apply {
                put("log", "(empty)")
                put("searched", JSONArray(candidates.map { it.absolutePath }))
            }.toString(2)
        } else {
            JSONObject().apply {
                put("file", f.absolutePath)
                put("lines", JSONArray(f.readLines().takeLast(n)))
            }.toString(2)
        }
    } catch (e: Exception) {
        """{"error":"${e.message}"}"""
    }
}
