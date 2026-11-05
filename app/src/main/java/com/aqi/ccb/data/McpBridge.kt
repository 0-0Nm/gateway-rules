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
        val discovery = com.aqi.ccb.application.ProviderWorkflows.discover(baseUrl,apiKey)
        if (!discovery.ok) return JSONObject().put("ok",false).put("code",discovery.errorCode)
            .put("error","Provider discovery failed").toString()
        val resolved = discovery.resolvedUrl
        val models = discovery.models
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
            models = com.aqi.ccb.application.ProviderWorkflows.mergeDiscovered(emptyList(),models),
            suffixStyle = discovery.style,
        )
        val committed = ProviderRepo.saveCommitted(context, draft)
        return committed.json().apply {
            put("id", draft.idOr())
            put("resolvedBaseUrl", resolved)
            put("style", discovery.style)
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
        val measured = mutableMapOf<String,ModelInfo>()
        // Same explicit selection/cap as the UI. Never turn a model-list import into paid batch traffic.
        val selected = com.aqi.ccb.application.ProviderWorkflows.selectedForTest(d.models)
        if (selected.isEmpty()) return JSONObject().put("ok",false).put("code","NO_SELECTED_MODELS")
            .put("error","Select models before testing; tests may incur upstream charges").toString()
        selected.forEach { m ->
            val tested = com.aqi.ccb.gateway.BatchTester.testOne(d.baseUrl, d.apiKey, m)
            measured[tested.modelId] = tested
            if (tested.available == true) okCount++
            results.put(JSONObject().apply {
                put("modelId", tested.modelId)
                put("available", tested.available ?: false)
                put("latencyMs", tested.latencyMs)
                put("lastError", tested.lastError)
            })
        }
        // 回写测试结果
        val tested = d.models.map { m -> measured[m.modelId]?.let {
            com.aqi.ccb.application.ProviderWorkflows.mergeTestFields(m,it)
        } ?: m }
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
            .put("gatewayRunning",GatewayRuntime.isRunning()).put("runtime",com.aqi.ccb.gateway.GatewayService.current?.debugSnapshot()?:JSONObject.NULL)
            .put("build",BuildInfo.json())
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

    private fun logRoot(): File = GateLog.directory() ?: File(
        try { context.getExternalFilesDir(null) ?: context.filesDir } catch (_: Exception) { context.filesDir }, "logs")
    private fun diagnosticSecrets(): List<String> = ConfigStore.read(context).keys.values.toList() +
        com.aqi.ccb.gateway.LocalAccessTokens.existingForRedaction(context)

    override fun readLog(lines: Int): String = try {
        val q=GateLog.query(logRoot(),JSONObject().put("stream","system").put("limit",lines.coerceIn(1,200)),diagnosticSecrets())
        val records=q.optJSONArray("records")?:JSONArray()
        q.put("lines",JSONArray((0 until records.length()).map{records.getJSONObject(it).optString("text")}.reversed()))
            .put("file",File(logRoot(),"system/gateway.log").absolutePath).toString()
    } catch (_: Exception) { JSONObject().put("ok",false).put("code","LOG_READ_FAILED").put("error","Cannot read diagnostic logs").toString() }

    override fun debugTool(name: String, argsJson: String): String = try {
        val args=JSONObject(argsJson)
        fun fields(vararg names:String){require(args.keys().asSequence().all{it in names}){"Unknown diagnostic argument"}}
        when(name) {
            "gw_log_query" -> GateLog.query(logRoot(),args,diagnosticSecrets())
            "gw_trace_request" -> {
                fields("requestId","limit","cursor")
                require(args.opt("requestId") is String && args.getString("requestId").matches(Regex("r-[a-f0-9]{8}"))){"Expected response X-Gateway-Request-Id"}
                GateLog.query(logRoot(),JSONObject(args.toString()).put("stream","calls"),diagnosticSecrets())
            }
            "gw_debug_snapshot" -> {
                fields()
                val st=ConfigStore.read(context);val gw=com.aqi.ccb.gateway.GatewayService.current
                JSONObject(statusJson(AppSettings.gatewayPort(context))).put("ok",true)
                    .put("gatewayRunning",gw?.isRunning()==true).put("runtime",gw?.debugSnapshot()?:JSONObject.NULL)
                    .put("usage",com.aqi.ccb.gateway.UsageLedgerStore.open(java.io.File(context.filesDir,"usage-v1")).snapshot().json())
                    .put("configValid",true).put("credentialCount",st.keys.size).put("foregroundActivityRequired",false)
                    .put("logStreams",JSONArray(listOf("system","calls"))).put("liveProbeSingleAttempt",true)
                    .put("notCovered",JSONArray(listOf("UI rendering","system permission dialogs","long-duration/device lifecycle acceptance")))
            }
            "gw_explain_route" -> {
                fields("modelId","sessionId","turnIndex","requestId")
                require(args.opt("modelId") is String && args.getString("modelId").isNotBlank())
                for(k in listOf("sessionId","requestId"))require(!args.has(k) || args.opt(k) is String)
                val st=ConfigStore.read(context);val ps=ConfigStore.toProviders(st.providers);val rs=ConfigStore.toRules(st.rules)
                val vms=st.virtualModels.map{com.aqi.ccb.gateway.VirtualModel.fromJson(it)}
                val mid=args.getString("modelId");val vm=vms.firstOrNull{it.enabled && it.name==mid}
                val rule=if(vm!=null && vm.ruleId.isNotBlank())rs.firstOrNull{it.id==vm.ruleId && it.enabled}else rs.firstOrNull{it.enabled}
                require(rule!=null){"No enabled rule for requested model"}
                val ctx=com.aqi.ccb.gateway.RequestCtx(mid,reqId=args.optString("requestId"),ruleKey=rule.id,
                    sessionId=args.optString("sessionId"),turnIndex=BackgroundDebug.integer(args,"turnIndex",0,0,1000000),dryRun=true)
                val cands=com.aqi.ccb.gateway.VirtualModelResolver.resolve(mid,vms,ps)?:com.aqi.ccb.gateway.RuleEngine.buildCands(ps,ctx)
                JSONObject(com.aqi.ccb.gateway.RuleEngine.explainCandidates(rule,cands,ctx) as Map<*,*>).put("ok",true)
                    .put("configRevision",st.revision).put("virtualModel",vm?.name?:JSONObject.NULL)
                    .put("candidateModels",JSONArray(cands.map{JSONObject().put("providerId",it.p.id).put("modelId",it.m.modelId)}))
                    .put("dryRun",true).put("networkRequests",0).put("scope","select chain before runtime limiter and session/turn reorder")
            }
            "gw_gateway_control" -> {
                fields("action","confirm","allowLan")
                require(args.opt("confirm")==true){"confirm=true required"}
                require(!args.has("allowLan") || args.opt("allowLan") is Boolean)
                val action=args.optString("action");require(action in setOf("start","stop")){"action must be start or stop"}
                val gw=com.aqi.ccb.gateway.GatewayService.current;val already=gw?.isRunning()==true
                if(action=="start") {
                    val st=ConfigStore.read(context);require(st.providers.any{it.optBoolean("enabled",true)} && st.rules.any{it.optBoolean("enabled",true)}){"Configure an enabled provider and rule first"}
                    require(!AppSettings.gatewayLanEnabled(context) || args.opt("allowLan")==true){"LAN binding needs allowLan=true"}
                    if(!already)com.aqi.ccb.gateway.GatewayService.start(context,AppSettings.gatewayPort(context))
                } else if(already)com.aqi.ccb.gateway.GatewayService.stop(context)
                val wanted=action=="start";val end=System.nanoTime()+2_000_000_000L
                while((com.aqi.ccb.gateway.GatewayService.current?.isRunning()==true)!=wanted && System.nanoTime()<end)Thread.sleep(50)
                val observed=com.aqi.ccb.gateway.GatewayService.current?.isRunning()==true
                JSONObject().put("ok",true).put("accepted",true).put("completed",observed==wanted).put("gatewayRunning",observed)
                    .put("noOp",already==wanted).put("action",action).put("foregroundActivityStarted",false)
                    .put("note",if(observed==wanted)"Observed target runtime state" else "Asynchronous service transition not yet observed; poll gw_debug_snapshot; do not blindly retry")
            }
            "gw_self_test" -> {fields();BackgroundDebug.selfTest(context)}
            "gw_probe_request" -> {
                fields("modelId","stream","maxTokens","prompt","timeoutMs","confirm")
                require(args.opt("confirm")==true){"confirm=true required: one real upstream request may incur cost"}
                require(args.opt("modelId") is String && args.getString("modelId").isNotBlank())
                require(!args.has("stream") || args.opt("stream") is Boolean)
                require(!args.has("prompt") || args.opt("prompt") is String)
                val prompt=args.optString("prompt","Reply with OK.");require(prompt.length<=512)
                val tokens=BackgroundDebug.integer(args,"maxTokens",16,1,64)
                val timeout=BackgroundDebug.integer(args,"timeoutMs",10000,1000,15000)
                val gw=com.aqi.ccb.gateway.GatewayService.current;require(gw?.isRunning()==true){"Gateway is not running"}
                val result=BackgroundDebug.probe(gw!!.listeningPortPublic(),com.aqi.ccb.gateway.LocalAccessTokens.gateway(context),args.getString("modelId"),prompt,args.optBoolean("stream",false),tokens,timeout)
                result.put("persistentConfigChanged",false).put("foregroundActivityStarted",false)
            }
            else -> JSONObject().put("ok",false).put("code","UNKNOWN_DIAGNOSTIC_TOOL").put("error","Unknown diagnostic tool")
        }.toString()
    } catch(e:Exception) {
        JSONObject().put("ok",false).put("code",if(e is IllegalArgumentException)"INVALID_ARGUMENT" else "DIAGNOSTIC_FAILED")
            .put("error",if(e is IllegalArgumentException) e.message?.take(180)?:"Invalid diagnostic argument" else e.javaClass.simpleName).toString()
    }
}

