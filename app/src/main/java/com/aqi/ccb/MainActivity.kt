package com.aqi.ccb

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.aqi.ccb.data.ProviderDraft
import com.aqi.ccb.data.ProviderRepo
import com.aqi.ccb.data.VirtualModelDraft
import com.aqi.ccb.data.VirtualModelRepo
import com.aqi.ccb.gateway.VirtualModelStore
import com.aqi.ccb.ui.VirtualModelEditScreen
import com.aqi.ccb.ui.SettingsScreen
import com.aqi.ccb.ui.SettingsState
import com.aqi.ccb.ui.PagerTabs
import com.aqi.ccb.ui.TabItem
import com.aqi.ccb.gateway.ConfigStore
import com.aqi.ccb.gateway.GateLog
import com.aqi.ccb.gateway.FloatingBall
import com.aqi.ccb.gateway.GatewayServer
import com.aqi.ccb.gateway.ModelProbe
import com.aqi.ccb.gateway.BatchTester
import com.aqi.ccb.gateway.McpServer
import com.aqi.ccb.data.AppSettings
import com.aqi.ccb.data.McpBridge
import com.aqi.ccb.data.ModelInfo
import com.aqi.ccb.gateway.RateLimiter
import com.aqi.ccb.gateway.RatePolicy
import com.aqi.ccb.ui.MainScreen
import com.aqi.ccb.ui.MainUiState
import com.aqi.ccb.ui.ProviderEditScreen
import com.aqi.ccb.ui.ProviderRow
import com.aqi.ccb.ui.RuleDraft
import com.aqi.ccb.ui.RuleEditScreen
import com.aqi.ccb.ui.RuleRow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {

    private var server: GatewayServer? = null
    private var appLogDir: File = File("/dev/null")
    private var mcpServer: McpServer? = null
    private val ui = Handler(Looper.getMainLooper())

    // ---- 顶层导航状态 ----
    private var route by mutableStateOf("main")

    // ---- 主界面 tab（聚合/提供商/规则/虚拟模型）----
    private var mainTab by mutableStateOf("agg")

    // ---- 设置页可观察状态（关键：不能用 AppSettings 每次重组新建快照，
    //      否则受控输入框 value 会回滚导致打不进字，开关也不生效）----
    private var settingsState by mutableStateOf(
        com.aqi.ccb.ui.SettingsState(
            gatewayPort = "38080",
            floatEnabled = true,
            mcpEnabled = false,
            mcpPort = "38081",
            toastEnabled = true,
            mcpRunning = false,
        )
    )

    // ---- 聚合页状态 ----
    private var mainState by mutableStateOf(MainUiState())

    // ---- 编辑态 ----
    private var editingProviderIndex by mutableStateOf(-1)
    private var providerDraft by mutableStateOf(ProviderDraft())
    private var providerTest by mutableStateOf<String?>(null)

    private var editingRuleId by mutableStateOf<String?>(null)
    private var ruleDraft by mutableStateOf(RuleDraft())

    // ---- 虚拟模型编辑态 ----
    private var editingVmName by mutableStateOf<String?>(null)
    private var vmDraft by mutableStateOf(VirtualModelDraft())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 日志写到外部私有目录（/sdcard/Android/data/<pkg>/files/logs）：
        // 应用无需额外权限即可写，adb/shell 也能直接读，便于自助排查。
        // 取不到外部目录时回退到内部 filesDir。
        val extDir = try {
            getExternalFilesDir(null) ?: filesDir
        } catch (e: Exception) {
            filesDir
        }
        appLogDir = File(extDir, "logs")
        GateLog.init(appLogDir)
        com.aqi.ccb.ui.UiToast.init(this)
        ProviderRepo.warmUpSecrets(this)

        setContent {
            AppTheme {
                when (route) {
                    "provider" -> ProviderEditScreen(
                        draft = providerDraft,
                        isNew = editingProviderIndex < 0,
                        onDraftChange = { providerDraft = it },
                        onBack = { route = "main"; refreshMain() },
                        onSave = { saveProvider() },
                        onDelete = if (editingProviderIndex >= 0) ({ deleteProvider() }) else null,
                        onTest = { testProvider() },
                        onProbeModels = { probeModels() },
                        onTestAll = { testAllModels() },
                        onToggleModel = { mid ->
                            val cur = providerDraft.models.toMutableList()
                            val i = cur.indexOfFirst { it.modelId == mid }
                            if (i >= 0) {
                                cur[i] = cur[i].copy(enabled = !cur[i].enabled)
                                providerDraft = providerDraft.copy(models = cur)
                            }
                        },
                        onSuffixChange = { st ->
                            providerDraft = providerDraft.copy(suffixStyle = st)
                        },
                        testResult = providerTest,
                    )
                    "settings" -> SettingsScreen(
                        state = settingsState.copy(
                            mcpRunning = mcpServer?.isRunning() == true,
                            // 实际监听端口（来自 McpServer），与草稿分开，避免显示未生效值
                            mcpPortApplied = mcpServer?.port?.toString() ?: "",
                            mcpCalls = mcpServer?.totalCalls ?: 0,
                            mcpErrors = mcpServer?.totalErrors ?: 0,
                            mcpLastMethod = mcpServer?.lastMethod ?: "",
                            mcpLastLatencyMs = mcpServer?.lastLatencyMs ?: 0,
                            mcpLastResult = mcpServer?.lastResult ?: "",
                            mcpLastError = mcpServer?.lastError,
                        ),
                        onGatewayPort = { p ->
                            // 先更新 UI 状态（否则受控输入会回滚，表现为打不进字）
                            settingsState = settingsState.copy(gatewayPort = p)
                            mainState = mainState.copy(port = p)
                            AppSettings.setGatewayPort(this, p.toIntOrNull() ?: 38080)
                        },
                        onFloatToggle = { v ->
                            settingsState = settingsState.copy(floatEnabled = v)
                            AppSettings.setFloatEnabled(this, v)
                            if (v && server?.isRunning() == true) {
                                FloatingBall.attach(this)
                            } else if (!v) {
                                FloatingBall.detach()
                            }
                        },
                        onMcpToggle = { v ->
                            settingsState = settingsState.copy(mcpEnabled = v)
                            toggleMcp(v)
                            settingsState = settingsState.copy(
                                mcpRunning = mcpServer?.isRunning() == true
                            )
                        },
                        onMcpPort = { p ->
                            // 关键修复：输入阶段只更新草稿，绝不重启 MCP。
                            // 原实现每次击键都 setMcpPort，"3"/"38"/"380" 逐个 BindException，
                            // 最后 MCP 停在半截端口，UI 却显示完整值 -> "连不上"
                            settingsState = settingsState.copy(mcpPort = p)
                        },
                        onMcpPortApply = {
                            val target = settingsState.mcpPort.toIntOrNull() ?: 38081
                            if (target < 1024 || target > 65535) {
                                com.aqi.ccb.ui.UiToast.error("端口需在 1024-65535 之间")
                            } else {
                                setMcpPort(target)
                                val srv = mcpServer
                                settingsState = settingsState.copy(
                                    mcpRunning = srv?.isRunning() == true,
                                )
                                if (srv?.isRunning() == true) {
                                    com.aqi.ccb.ui.UiToast.show("MCP 已重启于 " + srv.port)
                                } else {
                                    com.aqi.ccb.ui.UiToast.error(
                                        "MCP 启动失败：" + (srv?.lastError ?: "端口被占用")
                                    )
                                }
                            }
                        },
                        onToastToggle = { v ->
                            settingsState = settingsState.copy(toastEnabled = v)
                            AppSettings.setToastEnabled(this, v)
                        },
                        onBack = { route = "main"; refreshMain() },
                        onMcpCopyRaw = {
                            val cfg = """{
  "mcpServers": {
    "gateway-rules": {
      "type": "http",
      "url": "http://127.0.0.1:${settingsState.mcpPort}/mcp"
    }
  }
}"""
                            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("mcp", cfg)
                            )
                            com.aqi.ccb.ui.UiToast.show("MCP 配置 JSON 已复制")
                        },
                    )
                    "vmodel" -> VirtualModelEditScreen(
                        draft = vmDraft,
                        isNew = editingVmName == null,
                        providerModels = VirtualModelRepo.providerModels(this),
                        ruleIds = ConfigStore.loadRules(this).map { it.optString("id", "default") }
                            .ifEmpty { listOf("default") },
                        onDraftChange = { vmDraft = it },
                        onBack = { route = "main"; refreshMain() },
                        onSave = { saveVirtualModel() },
                        onDelete = if (editingVmName != null) ({ deleteVirtualModel() }) else null,
                    )
                    "rule" -> RuleEditScreen(
                        draft = ruleDraft,
                        isNew = editingRuleId == null,
                        onDraftChange = { ruleDraft = it },
                        onBack = { route = "main"; refreshMain() },
                        onSave = { saveRule() },
                        onDelete = if (editingRuleId != null) ({ deleteRule() }) else null,
                    )
                    else -> MainScreen(
                        state = mainState,
                        currentTab = mainTab,
                        onTabChange = { tab ->
                            if (tab == "settings") {
                                route = "settings"
                            } else {
                                mainTab = tab
                            }
                        },
                        onStart = { startGateway() },
                        onStop = { stopGateway() },
                        onPortChange = { mainState = mainState.copy(port = it) },
                        onFloatAlpha = { pct ->
                            com.aqi.ccb.gateway.FloatingBall.setAlphaPercent(pct)
                            mainState = mainState.copy(floatAlpha = pct)
                        },
                        onAddProvider = {
                            editingProviderIndex = -1
                            providerDraft = ProviderDraft()
                            providerTest = null
                            route = "provider"
                        },
                        onEditProvider = { idx -> openProvider(idx) },
                        onAddRule = {
                            editingRuleId = null
                            ruleDraft = RuleDraft()
                            route = "rule"
                        },
                        onEditRule = { id -> openRule(id) },
                        onAddVirtual = {
                            editingVmName = null
                            vmDraft = VirtualModelDraft()
                            route = "vmodel"
                        },
                        onEditVirtual = { name -> openVirtualModel(name) },
                    )
                }
            }
        }

        refreshMain()
        ui.postDelayed({ runCatching { startGateway() } }, 600)
        startTicker()
    }

    override fun onResume() {
        super.onResume()
        refreshMain()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        server?.stop()
        mcpServer?.stop()
        FloatingBall.detach()
        super.onDestroy()
    }

    private fun startTicker() {
        ui.postDelayed(object : Runnable {
            override fun run() {
                if (route == "main") refreshMain()
                ui.postDelayed(this, 1000)
            }
        }, 1000)
    }

    // ==================== 刷新聚合页 ====================

    private fun refreshMain() {
        val drafts = ProviderRepo.list(this)
        val ruleJsons = ConfigStore.loadRules(this)

        val rows = drafts.mapIndexed { i, d ->
            ProviderRow(
                index = i,
                id = d.idOr(),
                name = d.name.ifBlank { d.idOr() },
                baseUrl = d.baseUrl,
                modelCount = d.modelId.split(',', ' ').count { it.isNotBlank() },
                rateSummary = "${d.ratePerMin}/min · gap ${d.minIntervalMs}ms",
                enabled = d.enabled,
            )
        }
        val ruleRows = ruleJsons.map { o ->
            RuleRow(
                id = o.optString("id", "?"),
                name = o.optString("name", ""),
                selectCount = o.optJSONArray("select")?.length() ?: 0,
                executeOp = o.optJSONObject("execute")?.optString("op") ?: "-",
                enabled = o.optBoolean("enabled", true),
            )
        }

        val vmRows = VirtualModelRepo.list(this).map { d ->
            com.aqi.ccb.ui.VirtualRow(
                name = d.name,
                memberCount = d.members.size,
                ruleId = d.ruleId,
                enabled = d.enabled,
            )
        }

        val s = server
        mainState = mainState.copy(
            virtualModels = vmRows,
            floatAlpha = com.aqi.ccb.gateway.FloatingBall.alphaPercent(),
            floatLocked = com.aqi.ccb.gateway.FloatingBall.isLocked(),
            running = s?.isRunning() == true,
            requests = s?.totalRequests ?: 0,
            ok = s?.totalOk ?: 0,
            fail = s?.totalFail ?: 0,
            providers = rows,
            rules = ruleRows,
            logTail = tailLog(),
        )
    }

    private fun tailLog(): String = try {
        val f = File(filesDir, "logs/system/gateway.log")
        if (!f.exists()) "" else f.readLines().takeLast(25).joinToString("\n")
    } catch (e: Exception) {
        "读取失败: $e"
    }

    // ==================== 网关 ====================

    private fun startGateway() {
        if (server?.isRunning() == true) return
        val port = mainState.port.toIntOrNull() ?: 38080
        val providers = ProviderRepo.runtimeProviders(this)
        val rules = ProviderRepo.runtimeRules(this)
        if (providers.isEmpty() || rules.isEmpty()) {
            mainState = mainState.copy(statusNote = "配置不完整，请先添加提供商与规则")
            return
        }
        val policies = HashMap<String, RatePolicy>()
        providers.forEach { policies[it.id] = it.ratePolicy }
        val limiter = RateLimiter(policies, RatePolicy())

        val vmodels = VirtualModelStore.load(this)
        val srv = GatewayServer(port, providers, rules, limiter, "", vmodels)
        // 注入路径供 /v1/_gw/diag 暴露，便于外部自助排查
        srv.logDirPath = appLogDir.absolutePath
        srv.configDirPath = filesDir.absolutePath
        if (srv.start()) {
            server = srv
            mainState = mainState.copy(statusNote = null)
            if (AppSettings.floatEnabled(this)) {
                FloatingBall.attach(this)
                FloatingBall.setState(FloatingBall.STATE_IDLE)
            }
            startMcpIfEnabled()
        } else {
            mainState = mainState.copy(statusNote = "启动失败：${srv.lastError}（端口被占用？）")
        }
        refreshMain()
    }

    private fun stopGateway() {
        server?.stop()
        server = null
        FloatingBall.detach()
        refreshMain()
    }

    // ==================== 提供商 ====================

    private fun openProvider(index: Int) {
        val list = ProviderRepo.list(this)
        if (index !in list.indices) return
        editingProviderIndex = index
        providerDraft = list[index]
        providerTest = null
        route = "provider"
    }

    private fun saveProvider() {
        if (!providerDraft.isComplete) {
            providerTest = "名称 / Base URL / 模型 ID 必填"
            return
        }
        if (ProviderRepo.save(this, providerDraft)) {
            editingProviderIndex = -1
            providerDraft = ProviderDraft()
            providerTest = null
            route = "main"
            refreshMain()
            server?.let { srv ->
                srv.update(ProviderRepo.runtimeProviders(this), ProviderRepo.runtimeRules(this))
            }
        } else {
            providerTest = "保存失败"
        }
    }

    private fun deleteProvider() {
        val list = ProviderRepo.list(this)
        if (editingProviderIndex !in list.indices) return
        ProviderRepo.delete(this, list[editingProviderIndex].idOr())
        editingProviderIndex = -1
        providerDraft = ProviderDraft()
        route = "main"
        refreshMain()
    }

    // ==================== MCP ====================

    private fun startMcpIfEnabled() {
        if (!AppSettings.mcpEnabled(this)) {
            mcpServer?.stop()
            mcpServer = null
            return
        }
        if (mcpServer?.isRunning() == true) return
        val port = AppSettings.mcpPort(this)
        val srv = McpServer(port, mainState.port.toIntOrNull() ?: 38080) { McpBridge(this) }
        if (srv.start()) {
            mcpServer = srv
            settingsState = settingsState.copy(mcpRunning = true, mcpLastError = null)
            GateLog.info("mcp", "started on port " + srv.port)
        } else {
            mcpServer = srv
            settingsState = settingsState.copy(
                mcpRunning = false,
                mcpLastError = srv.lastError,
            )
            GateLog.error("mcp", "start failed port=" + port + " err=" + srv.lastError)
        }
    }

    fun toggleMcp(enabled: Boolean) {
        AppSettings.setMcpEnabled(this, enabled)
        if (enabled) startMcpIfEnabled() else { mcpServer?.stop(); mcpServer = null }
    }

    fun setMcpPort(port: Int) {
        AppSettings.setMcpPort(this, port)
        if (AppSettings.mcpEnabled(this)) {
            mcpServer?.stop()
            mcpServer = null
            startMcpIfEnabled()
        }
    }

    // ==================== 批量测试 ====================

    /** 批量测试当前提供商的所有模型（进度回填到 draft.testingProgress） */
    private fun testAllModels() {
        val d = providerDraft
        if (d.models.isEmpty()) {
            providerTest = "没有模型可测，先点『探测模型』"
            return
        }
        if (!d.baseUrl.startsWith("http")) {
            providerTest = "Base URL 需以 http(s):// 开头"
            return
        }
        providerDraft = providerDraft.copy(testingProgress = "0/${d.models.size}")
        providerTest = "批量测试中…"

        val current = d.models.toList()
        // 用固定线程池并发测，最多 4 并发，避免打爆上游
        BatchTester.testAll(
            baseUrl = d.baseUrl,
            apiKey = d.apiKey,
            models = current,
            concurrency = 4,
            onItem = { tested ->
                ui.post {
                    val cur = providerDraft.models.toMutableList()
                    val idx = cur.indexOfFirst { it.modelId == tested.modelId }
                    if (idx >= 0) {
                        cur[idx] = tested
                        providerDraft = providerDraft.copy(models = cur)
                    }
                }
            },
            onProgress = { prog ->
                ui.post {
                    providerDraft = providerDraft.copy(
                        testingProgress = "${prog.done}/${prog.total}"
                    )
                    if (prog.finished) {
                        val ok = providerDraft.models.count { it.available == true }
                        providerTest = "测试完成：$ok / ${prog.total} 可用"
                        providerDraft = providerDraft.copy(testingProgress = "")
                    }
                }
            },
        )
    }

    /**
     * 探测模型 —— 先按 suffixStyle 决定端点，再拉 /models 列表。
     * AUTO 模式下依次尝试 /v1、/v1beta、/openai、/compatible-mode/v1。
     */
    private fun probeModels() {
        val d = providerDraft
        if (!d.baseUrl.startsWith("http")) {
            providerTest = "Base URL 需以 http(s):// 开头"
            return
        }
        providerTest = "探测中…"
        Thread {
            val results = StringBuilder()
            var resolvedBase: String? = null
            var modelList: List<String> = emptyList()

            if (d.suffixStyle == "AUTO") {
                val r = com.aqi.ccb.gateway.BaseUrlProbe.probe(d.baseUrl, d.apiKey)
                if (r.ok) {
                    resolvedBase = r.resolvedUrl
                    results.append(r.summary())
                    modelList = com.aqi.ccb.gateway.BaseUrlProbe
                        .listModels(r.resolvedUrl!!, d.apiKey)
                } else {
                    results.append(r.summary())
                }
            } else {
                // 手动指定：按类型拼后缀
                val base = when (d.suffixStyle) {
                    "OPENAI" -> d.baseUrl.trimEnd('/') + "/v1"
                    "GOOGLE" -> d.baseUrl.trimEnd('/') + "/v1beta"
                    "OPENAI_COMPAT" -> d.baseUrl.trimEnd('/') + "/openai"
                    "ALIYUN" -> d.baseUrl.trimEnd('/') + "/compatible-mode/v1"
                    else -> d.baseUrl.trimEnd('/')
                }
                modelList = com.aqi.ccb.gateway.BaseUrlProbe.listModels(base, d.apiKey)
                if (modelList.isNotEmpty()) {
                    resolvedBase = base
                    results.append("可用：").append(base)
                        .append(" · 发现 ").append(modelList.size).append(" 个模型")
                } else {
                    results.append("失败：").append(base).append(" 无模型列表")
                }
            }

            ui.post {
                if (resolvedBase != null && modelList.isNotEmpty()) {
                    // 保留已测过的模型结果，新发现的补进来
                    val old = providerDraft.models.associateBy { it.modelId }
                    val merged = modelList.map { id ->
                        old[id] ?: ModelInfo(modelId = id)
                    }
                    providerDraft = providerDraft.copy(
                        baseUrl = resolvedBase!!,
                        models = merged,
                    )
                }
                providerTest = results.toString()
            }
        }.start()
    }

    /**
     * 连接测试 —— rikka 三件套：非流式 / 流式 / 工具调用。
     * 未填模型 ID 时先拉 /models 自动选第一个。
     */
    private fun testProvider() {
        val d = providerDraft
        if (!d.baseUrl.startsWith("http")) {
            providerTest = "Base URL 需以 http(s):// 开头"
            return
        }
        providerTest = "测试中…（非流式/流式/工具）"
        Thread {
            // 没填模型就先去拉列表
            var modelId = d.models.firstOrNull()?.modelId ?: ""
            if (modelId.isEmpty()) {
                val ids = runCatching { fetchModelIds(d.baseUrl.trimEnd('/'), d.apiKey) }.getOrNull()
                if (ids != null && !ids.startsWith("ERR:") && ids.isNotBlank()) {
                    val list = ids.split("\n").filter { it.isNotBlank() }
                    modelId = list.firstOrNull() ?: ""
                    ui.post {
                        providerDraft = providerDraft.copy(
                            models = list.map { ModelInfo(modelId = it) }
                        )
                    }
                }
            }
            if (modelId.isEmpty()) {
                ui.post { providerTest = "无法确定模型 ID" }
                return@Thread
            }
            val res = ModelProbe.probe(d.baseUrl.trimEnd('/'), d.apiKey, modelId)
            ui.post {
                providerTest = res.summary()
                providerDraft = providerDraft.copy(probeResult = res.summary())
            }
        }.start()
    }

    private fun fetchModelIds(base: String, key: String): String {
        val conn = java.net.URL("$base/models").openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Accept", "application/json")
        if (key.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $key")
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            return "ERR:http_$code"
        }
        val body = conn.inputStream.readBytes().toString(Charsets.UTF_8)
        conn.disconnect()
        val arr = JSONObject(body).optJSONArray("data") ?: return "ERR:no data field"
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val id = arr.optJSONObject(i)?.optString("id")
            if (!id.isNullOrBlank()) sb.append(id).append("\n")
        }
        return if (sb.isEmpty()) "ERR:empty model list" else sb.toString()
    }

    // ==================== 规则 ====================

    private fun openRule(id: String) {
        val all = ConfigStore.loadRules(this)
        val o = all.firstOrNull { it.optString("id") == id } ?: return
        editingRuleId = id
        ruleDraft = ruleJsonToDraft(o)
        route = "rule"
    }

    private fun ruleJsonToDraft(o: JSONObject): RuleDraft {
        val ops = mutableListOf<String>()
        o.optJSONArray("select")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("op")?.takeIf { it.isNotBlank() }?.let { ops.add(it) }
            }
        }
        return RuleDraft(
            id = o.optString("id", "default"),
            name = o.optString("name", "默认规则"),
            selectedOps = ops,
            executeOp = o.optJSONObject("execute")?.optString("op") ?: "first",
            enabled = o.optBoolean("enabled", true),
            switchGranularity = o.optString("switchGranularity", "TURN"),
        )
    }

    private fun saveRule() {
        val d = ruleDraft
        val sel = JSONArray()
        d.selectedOps.forEach { op ->
            val o = JSONObject().put("op", op)
            when (op) {
                "require" -> o.put("args", JSONObject().put("tools", true))
                "quota_filter" -> o.put("args", JSONObject().put("min_quota", 0))
                "tier_first" -> o.put("args", JSONObject().put("tier", "free"))
                "where" -> o.put("args", JSONObject().put("expr", "lat < 3000"))
                "ab_split" -> o.put("args", JSONObject().put("buckets", 2))
                "canary" -> o.put("args", JSONObject().put("percent", 10))
                "error_rate" -> o.put("args", JSONObject().apply {
                    put("max_errors", 5); put("window_s", 300)
                })
                "budget_cap" -> o.put("args", JSONObject().put("max_spend", 100))
                "take" -> o.put("args", JSONObject().put("n", 1))
            }
            sel.put(o)
        }
        val rule = JSONObject().apply {
            put("id", d.id.ifBlank { "default" })
            put("name", d.name.ifBlank { d.id })
            put("ruleSpecVersion", 1)
            put("enabled", d.enabled)
            put("select", sel)
            put("execute", JSONObject().put("op", d.executeOp).put("args", JSONObject()))
            put("switchGranularity", d.switchGranularity)
        }
        ProviderRepo.saveRule(this, rule)
        ProviderRepo.syncRateLimit(this, JSONObject().apply {
            put("ratePerMin", d.ratePerMin.toIntOrNull() ?: 30)
            put("burst", d.burst.toIntOrNull() ?: 3)
            put("minIntervalMs", d.minIntervalMs.toIntOrNull() ?: 800)
            put("jitterMs", d.jitterMs.toIntOrNull() ?: 150)
            put("maxInflight", d.maxInflight.toIntOrNull() ?: 2)
        })
        editingRuleId = null
        ruleDraft = RuleDraft()
        route = "main"
        refreshMain()
        server?.let { srv ->
            srv.update(ProviderRepo.runtimeProviders(this), ProviderRepo.runtimeRules(this))
        }
    }

    // ==================== 虚拟模型 ====================

    private fun openVirtualModel(name: String) {
        val list = VirtualModelRepo.list(this)
        val d = list.firstOrNull { it.name == name } ?: return
        editingVmName = name
        vmDraft = d
        route = "vmodel"
    }

    private fun saveVirtualModel() {
        if (VirtualModelRepo.save(this, vmDraft)) {
            editingVmName = null
            vmDraft = VirtualModelDraft()
            route = "main"
            refreshMain()
            server?.let { srv ->
                srv.update(
                    ProviderRepo.runtimeProviders(this),
                    ProviderRepo.runtimeRules(this),
                    VirtualModelStore.load(this),
                )
            }
        }
    }

    private fun deleteVirtualModel() {
        val name = editingVmName ?: return
        VirtualModelRepo.delete(this, name)
        editingVmName = null
        vmDraft = VirtualModelDraft()
        route = "main"
        refreshMain()
    }

    private fun deleteRule() {
        val id = editingRuleId ?: return
        ProviderRepo.deleteRule(this, id)
        editingRuleId = null
        ruleDraft = RuleDraft()
        route = "main"
        refreshMain()
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}
