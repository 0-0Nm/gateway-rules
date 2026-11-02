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
            .put("gatewayRunning",GatewayRuntime.isRunning()).put("runtime",com.aqi.ccb.gateway.GatewayService.current?.debugSnapshot()?:JSONObject.NULL)
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

/** Bounded, foreground-independent diagnostics. Real probes and isolated tests never run concurrently. */
private object BackgroundDebug {
    private val busy=java.util.concurrent.atomic.AtomicBoolean(false)
    fun integer(o:JSONObject,key:String,default:Int,min:Int,max:Int):Int {
        if(!o.has(key))return default
        val raw=o.opt(key);require(raw is Number && raw.toDouble().isFinite() && raw.toDouble()%1.0==0.0 && raw.toDouble() in min.toDouble()..max.toDouble()){ "$key out of range" }
        return raw.toInt()
    }
    private data class Response(val code:Int,val body:ByteArray,val requestId:String?,val contentType:String)
    private class ProbeReadFailure(val status:Int,val requestId:String?,val received:Int,cause:Exception):
        java.io.IOException("Diagnostic response incomplete",cause)

    private fun request(port:Int,path:String,key:String,body:String?=null,timeout:Int=2500):Response {
        require(port in 1..65535)
        val deadline=System.nanoTime()+timeout*1_000_000L
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1",port),minOf(timeout,2000));s.soTimeout=timeout
            val bytes=body?.toByteArray(Charsets.UTF_8)?:ByteArray(0)
            val head=(if(body==null)"GET" else "POST")+" $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $key\r\nX-Gateway-Max-Attempts: 1\r\nConnection: close\r\n"+
                (if(body==null)"" else "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")+"\r\n"
            s.getOutputStream().write(head.toByteArray(Charsets.US_ASCII));if(bytes.isNotEmpty())s.getOutputStream().write(bytes);s.getOutputStream().flush()
            val rawInput=s.getInputStream()
            val input=object:java.io.InputStream() {
                private fun remaining() {
                    val left=(deadline-System.nanoTime())/1_000_000
                    if(left<=0)throw java.net.SocketTimeoutException("Diagnostic deadline")
                    s.soTimeout=minOf(left,timeout.toLong()).toInt().coerceAtLeast(1)
                }
                override fun read():Int { remaining();return rawInput.read() }
                override fun read(b:ByteArray,off:Int,len:Int):Int { remaining();return rawInput.read(b,off,len) }
            }
            val header=com.aqi.ccb.gateway.SocketIo.readHeader(input)?:throw java.io.EOFException("Missing response headers")
            val lines=header.split("\r\n");val code=lines[0].split(' ').getOrNull(1)?.toIntOrNull()?:throw java.io.IOException("Invalid response")
            val headers=lines.drop(1).mapNotNull{val k=it.indexOf(':');if(k<1)null else it.substring(0,k).lowercase(java.util.Locale.ROOT) to it.substring(k+1).trim()}.toMap()
            val out=java.io.ByteArrayOutputStream();val buf=ByteArray(4096)
            val responseId=headers["x-gateway-request-id"]?.takeIf{it.matches(Regex("r-[a-f0-9]{8}"))}
            try {
                while(true){val left=(deadline-System.nanoTime())/1_000_000; if(left<=0)throw java.net.SocketTimeoutException("Diagnostic deadline")
                    s.soTimeout=minOf(left,timeout.toLong()).toInt().coerceAtLeast(1);val n=input.read(buf);if(n<0)break;if(n==0)continue
                    if(out.size()+n>1024*1024)throw java.io.IOException("Diagnostic response too large");out.write(buf,0,n)
                }
                headers["content-length"]?.let{if(it.toLongOrNull()!=out.size().toLong())throw java.io.EOFException("Incomplete diagnostic response")}
            } catch(e:Exception) {throw ProbeReadFailure(code,responseId,out.size(),e)}
            return Response(code,out.toByteArray(),responseId,headers["content-type"].orEmpty())
        }
    }
    private fun body(model:String,stream:Boolean,prompt:String="Reply OK.",maxTokens:Int=8):String = JSONObject()
        .put("model",model).put("stream",stream).put("max_tokens",maxTokens)
        .put("messages",JSONArray().put(JSONObject().put("role","user").put("content",prompt))).toString()
    fun probe(port:Int,key:String,model:String,prompt:String,stream:Boolean,maxTokens:Int,timeout:Int):JSONObject {
        if(!busy.compareAndSet(false,true))return JSONObject().put("ok",false).put("code","DIAGNOSTIC_BUSY").put("error","Another diagnostic is running")
        val start=System.nanoTime()
        try {
            val r=request(port,"/v1/chat/completions",key,body(model,stream,prompt,maxTokens),timeout)
            val protocolOk=runCatching {
                if(stream){check(r.contentType.substringBefore(';').trim().equals("text/event-stream",true));val tracker=com.aqi.ccb.gateway.SocketIo.SseCompletion();tracker.accept(r.body,r.body.size);tracker.requireComplete()}
                else {val tokener=org.json.JSONTokener(String(r.body,Charsets.UTF_8));val obj=tokener.nextValue();check(obj is JSONObject && tokener.nextClean()==0.toChar() && (!obj.has("error") || obj.isNull("error")) && obj.optJSONArray("choices")?.length()?.let{it>0}==true)}
            }.isSuccess
            val ok=r.code in 200..299 && protocolOk
            return JSONObject().put("ok",ok).put("httpStatus",r.code).put("requestId",r.requestId?:JSONObject.NULL)
                .put("receivedBytes",r.body.size).put("traceAvailable",r.requestId!=null).put("protocolComplete",protocolOk).put("stream",stream)
                .put("maxTokensRequested",maxTokens).put("gatewayRequests",1).put("maxUpstreamAttempts",1)
                .put("bodyReturned",false).put("elapsedMs",(System.nanoTime()-start)/1_000_000)
                .put("note","May incur one upstream request; transport cancellation cannot guarantee provider-side billing cancellation")
        }catch(e:Exception){
            val partial=e as? ProbeReadFailure
            return JSONObject().put("ok",false).put("error",(partial?.cause?:e).javaClass.simpleName).put("code","PROBE_FAILED")
                .put("requestId",partial?.requestId?:JSONObject.NULL).put("traceAvailable",partial?.requestId!=null)
                .put("httpStatus",partial?.status?:JSONObject.NULL).put("receivedBytes",partial?.received?:0)
                .put("failureStage",if(partial==null)"connect_or_headers" else "response_body")
                .put("gatewayRequests",1).put("maxUpstreamAttempts",1).put("bodyReturned",false).put("elapsedMs",(System.nanoTime()-start)/1_000_000)
        }
        finally{busy.set(false)}
    }
    fun selfTest(context:Context):JSONObject {
        if(!busy.compareAndSet(false,true))return JSONObject().put("ok",false).put("code","DIAGNOSTIC_BUSY").put("error","Another diagnostic is running")
        val real=com.aqi.ccb.gateway.GatewayService.current
        val id="self-"+java.util.UUID.randomUUID().toString().take(8);val token=java.util.UUID.randomUUID().toString()
        val cases=JSONArray();val stop=java.util.concurrent.atomic.AtomicBoolean(false);val hits=java.util.concurrent.atomic.AtomicInteger();val fallbackHits=java.util.concurrent.atomic.AtomicInteger()
        var upstream:java.net.ServerSocket?=null;var gateway:com.aqi.ccb.gateway.GatewayServer?=null;var worker:Thread?=null
        fun test(name:String,fn:()->Boolean){val start=System.nanoTime();try{cases.put(JSONObject().put("name",name).put("passed",fn()).put("elapsedMs",(System.nanoTime()-start)/1_000_000))}
            catch(e:Exception){cases.put(JSONObject().put("name",name).put("passed",false).put("errorType",e.javaClass.simpleName))}}
        try {
            val before=ConfigStore.read(context).revision
            val up=java.net.ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"));up.soTimeout=250;upstream=up
            worker=Thread({while(!stop.get())try{up.accept().use{s->s.soTimeout=1500;val input=s.getInputStream()
                val h=com.aqi.ccb.gateway.SocketIo.readHeader(input)?:return@use
                val request=com.aqi.ccb.gateway.SocketIo.parseRequestHead(h)
                val raw=com.aqi.ccb.gateway.SocketIo.readExactly(input,com.aqi.ccb.gateway.SocketIo.contentLength(request.headers,4096))
                hits.incrementAndGet();if(request.path.startsWith("/fallback"))fallbackHits.incrementAndGet()
                val j=JSONObject(String(raw));val isStream=j.optBoolean("stream");val fail=request.path.startsWith("/reject")
                val text=if(fail)"{}" else if(isStream)"data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n" else "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}"
                val b=text.toByteArray();s.getOutputStream().write(("HTTP/1.1 ${if(fail)429 else 200} Result\r\nContent-Type: ${if(isStream)"text/event-stream" else "application/json"}\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray()+b);s.getOutputStream().flush()
            }}catch(_:java.net.SocketTimeoutException){}catch(_:Exception){if(!stop.get())Thread.yield()}},"gw-selftest-upstream").apply{isDaemon=true;start()}
            val model=com.aqi.ccb.gateway.Model("self-model")
            val policy=com.aqi.ccb.gateway.RatePolicy(ratePerMin=0.0,minIntervalMs=0.0,jitterMs=0.0,maxInflight=2)
            fun provider(name:String,path:String)=com.aqi.ccb.gateway.Provider(name,name,"http://127.0.0.1:${up.localPort}/$path",ratePolicy=policy,models=mapOf(model.modelId to model))
            val p=provider(id,"ok");val rule=com.aqi.ccb.gateway.RuleSpec(id)
            val limiter=com.aqi.ccb.gateway.RateLimiter(mapOf(p.id to policy));val gw=com.aqi.ccb.gateway.GatewayServer(0,listOf(p),listOf(rule),limiter,token,upstreamReadTimeoutMs=1500);gateway=gw
            check(gw.start());val port=gw.listeningPortPublic();check(port>0)
            test("auth_rejected_before_upstream"){val n=hits.get();request(port,"/health","wrong").code==401 && hits.get()==n}
            test("health_loopback"){request(port,"/health",token).code==200}
            test("models_json"){val r=request(port,"/v1/models",token);r.code==200 && JSONObject(String(r.body)).getJSONArray("data").getJSONObject(0).getString("id")==model.modelId}
            test("chat_json_with_request_id"){val r=request(port,"/v1/chat/completions",token,body(model.modelId,false));r.code==200 && JSONObject(String(r.body)).getJSONArray("choices").length()==1 && r.requestId?.matches(Regex("r-[a-f0-9]{8}"))==true}
            test("chat_sse_complete"){val r=request(port,"/v1/chat/completions",token,body(model.modelId,true));val tr=com.aqi.ccb.gateway.SocketIo.SseCompletion();tr.accept(r.body,r.body.size);tr.requireComplete();r.code==200}
            test("unknown_model_no_upstream"){val n=hits.get();val r=request(port,"/v1/chat/completions",token,body("absent",false));r.code==404 && hits.get()==n}
            test("invalid_json_no_upstream"){val n=hits.get();val r=request(port,"/v1/chat/completions",token,"[]");r.code==400 && hits.get()==n}
            test("explicit_single_attempt_no_fallback"){
                val a=provider(id+"-reject","reject");val b=provider(id+"-fallback","fallback");gw.update(listOf(a,b),listOf(rule),revision=1,keys=emptyMap())
                val n=hits.get();val r=request(port,"/v1/chat/completions",token,body(model.modelId,false));r.code==502 && hits.get()==n+1 && fallbackHits.get()==0
            }
            test("real_config_and_gateway_unchanged"){ConfigStore.read(context).revision==before && com.aqi.ccb.gateway.GatewayService.current===real}
            val failed=(0 until cases.length()).count{!cases.getJSONObject(it).getBoolean("passed")}
            return JSONObject().put("ok",failed==0).put("scope","isolated_real_gateway_loopback").put("cases",cases)
                .put("passed",cases.length()-failed).put("failed",failed).put("externalRequests",0).put("testUpstreamHits",hits.get())
                .put("persistentConfigChanged",false).put("foregroundActivityStarted",false).put("testId",id)
                .put("notCovered",JSONArray(listOf("real upstream","Android service start restrictions","UI rendering","long duration")))
        }finally{
            stop.set(true);runCatching{gateway?.stop()};runCatching{upstream?.close()};runCatching{worker?.join(1000)}
            for(suffix in listOf("","-reject","-fallback"))com.aqi.ccb.gateway.RuntimeState.errorWindow.remove(id+suffix)
            busy.set(false)
        }
    }
}
