package com.aqi.ccb

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
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
import com.aqi.ccb.gateway.GatewayService
import com.aqi.ccb.gateway.ModelProbe
import com.aqi.ccb.gateway.BatchTester
import com.aqi.ccb.gateway.McpServer
import com.aqi.ccb.gateway.McpService
import com.aqi.ccb.data.AppSettings
import com.aqi.ccb.data.DemoFallback
import com.aqi.ccb.data.McpBridge
import com.aqi.ccb.application.ProviderTaskGate
import com.aqi.ccb.application.ProviderWorkflows
import com.aqi.ccb.data.ModelInfo
import com.aqi.ccb.gateway.RateLimiter
import com.aqi.ccb.gateway.RatePolicy
import com.aqi.ccb.ui.MainScreen
import com.aqi.ccb.ui.MainUiState
import com.aqi.ccb.ui.ProviderEditScreen
import com.aqi.ccb.ui.ProviderRow
import com.aqi.ccb.ui.ProviderHealthRow
import com.aqi.ccb.ui.RuleDraft
import com.aqi.ccb.ui.RuleEditScreen
import com.aqi.ccb.ui.RuleRow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {

    // 网关由前台服务托管（照 McpService 范式）。
    // 实测依据：Activity 销毁后网关线程随之结束，但 MCP 前台服务仍活。
    private val server: GatewayServer?
        get() = GatewayService.current
    private var appLogDir: File = File("/dev/null")

    /** 网关服务状态监听：UI 以服务实际状态为准，不靠本地推测 */
    private val gwStateListener = object : GatewayService.StateListener {
        override fun onGatewayState(running: Boolean, port: Int, error: String?) {
            mainState = mainState.copy(
                running = running,
                statusNote = error,
            )
            GateLog.info(
                "gw", "state running=" + running + " port=" + port +
                    (if (error != null) " err=" + error else "")
            )
        }
    }

    /** MCP 服务状态监听（照 STRX 的 StateListener），UI 显示实际监听端口 */
    private val mcpStateListener = object : McpService.StateListener {
        override fun onStateChanged(running: Boolean, host: String, port: Int) {
            val applied = if (running && port > 0) port.toString() else ""
            settingsState = settingsState.copy(
                mcpRunning = running,
                mcpPortApplied = applied,
            )
            // 卫星球：MA = alive / MD = died
            com.aqi.ccb.gateway.FloatingBall.setMcpAlive(running)
            GateLog.info("mcp", "state running=" + running + " host=" + host + " port=" + port)
        }
    }
    // MCP 由前台服务托管（照 STRX 的 McpService 范式）。
    // 这里从服务读当前实例，别处引用 mcpServer 无需大改。
    private val mcpServer: McpServer?
        get() = McpService.current
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

    // ---- 启动权限引导 ----
    /** 待处理的缺失权限 key 列表；非空时弹窗 */
    private var permPrompt by mutableStateOf<List<Pair<String, String>>>(emptyList())
    /** 弹窗里「不再提示」的记忆 KV */
    private val PERM_PROMPT_PREF = "perm_prompt"
    private val PERM_PROMPT_KEY = "dismissed_at"

    // ---- 编辑态 ----
    private var editingProviderIndex by mutableStateOf(-1)
    private var providerDraft by mutableStateOf(ProviderDraft())
    private var providerTest by mutableStateOf<String?>(null)
    private var providerBusy by mutableStateOf(false)
    private val providerTasks = ProviderTaskGate()
    private var providerWorker: Thread? = null
    private var providerBatch: BatchTester.Handle? = null

    private var editingRuleId by mutableStateOf<String?>(null)
    private var ruleDraft by mutableStateOf(RuleDraft())

    // ---- 虚拟模型编辑态 ----
    private var editingVmName by mutableStateOf<String?>(null)
    private var vmDraft by mutableStateOf(VirtualModelDraft())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Load persisted values after Context attachment, before the first composition.
        settingsState = settingsState.copy(
            gatewayPort = AppSettings.gatewayPort(this).toString(),
            gatewayLanEnabled = AppSettings.gatewayLanEnabled(this),
            floatEnabled = AppSettings.floatEnabled(this),
            mcpEnabled = AppSettings.mcpEnabled(this),
            mcpPort = AppSettings.mcpPort(this).toString(),
            toastEnabled = AppSettings.toastEnabled(this),
        )
        mainState = mainState.copy(port = AppSettings.gatewayPort(this).toString())
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
        com.aqi.ccb.gateway.GatewayRuntime.configurePublisher()
        runCatching { ProviderRepo.warmUpSecrets(this) }.onFailure {
            com.aqi.ccb.ui.UiToast.error("配置读取失败，未使用演示数据替代")
        }
        // 订阅前台服务的真实状态：UI 端口显示以实际监听为准
        McpService.addListener(mcpStateListener)
        GatewayService.addListener(gwStateListener)
        // 注入日志目录：GatewayService 每次 onStartCommand 都会用它填 diag.logDir，
        // 之前从未调用导致 diag 里 logDir 恒为空串（这是上一轮的回归）。
        GatewayService.setPendingLogDir(appLogDir.absolutePath)

        // 绑定悬浮球的状态提供者：球上显示的 MCP / 网关状态
        // 一律实时向服务询问，不用缓存 —— 这修掉「MCP 没开却显示 MA」。
        com.aqi.ccb.gateway.FloatingBallState.bind()

        setContent {
            AppTheme {
                // UI 样式（圆角/透明度）由 MCP 的 gw_set_ui_style 可实时修改；
                // 这里读一次并随重组生效，改完无需重启
                val uiStyle = com.aqi.ccb.ui.UiStyle.load(this)
                androidx.compose.runtime.CompositionLocalProvider(
                    com.aqi.ccb.ui.LocalUiStyle provides uiStyle
                ) {
                // ---- 启动权限弹窗 ----
                // 缺权限时主动弹，不再静默（悬浮窗是特殊权限，系统永不自动弹）
                if (permPrompt.isNotEmpty()) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { dismissPermPrompt() },
                        title = {
                            androidx.compose.material3.Text(
                                "需要 ${permPrompt.size} 项权限"
                            )
                        },
                        text = {
                            androidx.compose.foundation.layout.Column {
                                androidx.compose.material3.Text(
                                    "缺少以下权限时，悬浮球不显示 / 后台会被冻结导致客户端连不上："
                                )
                                androidx.compose.foundation.layout.Spacer(
                                    androidx.compose.ui.Modifier.padding(4.dp)
                                )
                                permPrompt.forEach { (key, title) ->
                                    androidx.compose.material3.Text(
                                        "· " + title,
                                        modifier = androidx.compose.ui.Modifier.padding(
                                            vertical = 2.dp
                                        ),
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    val first = permPrompt.firstOrNull()?.first
                                    if (first != null) {
                                        val ok2 = com.aqi.ccb.gateway.PermissionHelper
                                            .openSettings(this, first)
                                        val deg2 = com.aqi.ccb.gateway.PermissionHelper
                                            .lastOpenDegraded
                                        if (!ok2) {
                                            com.aqi.ccb.ui.UiToast.error(
                                                "打不开设置页，请手动到系统设置里授权"
                                            )
                                        } else if (deg2 && first == "battery") {
                                            com.aqi.ccb.ui.UiToast.error(
                                                "请手动到 设置→电池→后台耗电管理，" +
                                                    "把本应用设为「允许后台运行」"
                                            )
                                        } else if (deg2) {
                                            com.aqi.ccb.ui.UiToast.error(
                                                "已打开应用详情，请手动开启对应权限"
                                            )
                                        }
                                    }
                                }
                            ) { androidx.compose.material3.Text("去开启") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { dismissPermPrompt() }
                            ) { androidx.compose.material3.Text("稍后") }
                        },
                    )
                }

                when (route) {
                    "provider" -> ProviderEditScreen(
                        draft = providerDraft,
                        isNew = editingProviderIndex < 0,
                        onDraftChange = { updateProviderDraft(it) },
                        onBack = { cancelProviderWork(); route = "main"; refreshMain() },
                        onSave = { configAction { saveProvider() } },
                        onDelete = if (editingProviderIndex >= 0) ({ configAction { deleteProvider() } }) else null,
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
                        // 价格等字段整条回传更新
                        onUpdateModel = { updated ->
                            val cur = providerDraft.models.toMutableList()
                            val i = cur.indexOfFirst { it.modelId == updated.modelId }
                            if (i >= 0) {
                                cur[i] = updated
                                providerDraft = providerDraft.copy(models = cur)
                            }
                        },
                        onSuffixChange = { st -> updateProviderDraft(providerDraft.copy(suffixStyle = st)) },
                        busy = providerBusy,
                        onCancelTest = { cancelProviderWork(); providerTest = "测试已取消；已发送的上游请求可能仍产生费用" },
                        testResult = providerTest,
                    )
                    "settings" -> SettingsScreen(
                        state = settingsState.copy(
                            // ---- 权限状态（每次渲染重新读取，用户改完设置回来即刷新）----
                            permOverlay = com.aqi.ccb.gateway.PermissionHelper
                                .canDrawOverlays(this),
                            // 悬浮窗三态：权限被撤销后已挂载的 View 不会立刻消失，
                            // 所以不能只看"是不是还挂着"，要看系统 API + 历史挂载记录
                            permOverlayStatus = com.aqi.ccb.gateway.PermissionHelper
                                .overlayStatusName(this),
                            permNotification = com.aqi.ccb.gateway.PermissionHelper
                                .canPostNotification(this),
                            permBattery = com.aqi.ccb.gateway.PermissionHelper
                                .isIgnoringBatteryOptimizations(this),
                            // 电池项精确三态与判定依据（vivo 上 API 会假阴性，
                            // 单靠布尔会把"已加入"误报成"未加入"）
                            permBatteryStatus = com.aqi.ccb.gateway.PermissionHelper
                                .batteryStatusName(this),
                            permBatteryEvidence = com.aqi.ccb.gateway.PermissionHelper
                                .batteryEvidence(this),
                            // 自启动：Android 无标准 API，只能按"是否前往过"推断
                            permAutostartStatus = com.aqi.ccb.gateway.PermissionHelper
                                .autostartStatusName(this),
                            permAutostartVisited = com.aqi.ccb.gateway.PermissionHelper
                                .autostartVisited(this),
                            floatError = com.aqi.ccb.gateway.FloatingBall.lastAttachError,
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
                            p.toIntOrNull()?.takeIf { it in 1024..65535 }?.let {
                                AppSettings.setGatewayPort(this, it)
                            }
                        },
                        onGatewayLanToggle = { enabled ->
                            AppSettings.setGatewayLanEnabled(this, enabled)
                            settingsState = settingsState.copy(gatewayLanEnabled = enabled)
                            if (GatewayService.running) {
                                GatewayService.start(this, AppSettings.gatewayPort(this))
                            }
                        },
                        onCopyGatewayToken = {
                            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Gateway access token",
                                com.aqi.ccb.gateway.LocalAccessTokens.gateway(this))
                            clip.description.extras = android.os.PersistableBundle().apply {
                                putBoolean("android.content.extra.IS_SENSITIVE", true)
                            }
                            cm.setPrimaryClip(clip)
                            com.aqi.ccb.ui.UiToast.show("网关访问令牌已复制，请仅交给可信客户端")
                        },
                        onFloatToggle = { v ->
                            settingsState = settingsState.copy(floatEnabled = v)
                            AppSettings.setFloatEnabled(this, v)
                            if (v && server?.isRunning() == true) {
                                if (!FloatingBall.attach(this)) {
                                    com.aqi.ccb.ui.UiToast.error(
                                        "悬浮球未显示：" +
                                            (FloatingBall.lastAttachError ?: "缺少悬浮窗权限")
                                    )
                                } else {
                                    FloatingBall.setMcpAlive(McpService.running)
                                }
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
                        onOpenAutoStart = {
                            val ok = com.aqi.ccb.gateway.PermissionHelper.openAutoStart(this)
                            if (!ok) {
                                com.aqi.ccb.ui.UiToast.error(
                                    "打不开自启动管理，请手动到 设置→应用管理→权限管理→自启动"
                                )
                            }
                        },
                        freezeProneRom = com.aqi.ccb.gateway.PermissionHelper.isFreezeProneRom(),
                        onOpenPerm = { key ->
                            val ok = com.aqi.ccb.gateway.PermissionHelper.openSettings(this, key)
                            val degraded = com.aqi.ccb.gateway.PermissionHelper.lastOpenDegraded
                            when {
                                !ok -> com.aqi.ccb.ui.UiToast.error(
                                    "打不开设置页，请手动到 系统设置→应用→GatewayRules 里授权"
                                )
                                degraded && key == "battery" -> com.aqi.ccb.ui.UiToast.error(
                                    "本机没有标准电池页，已为你打开应用详情。请手动到 " +
                                        "设置→电池→后台耗电管理 找到本应用并设为「允许后台运行」"
                                )
                                degraded -> com.aqi.ccb.ui.UiToast.error(
                                    "已打开应用详情，请在该页手动找到「" +
                                        (if (key == "overlay") "显示在其他应用上层" else "通知") +
                                        "」并开启"
                                )
                            }
                        },
                        onRequestAllPerms = {
                            val missing = com.aqi.ccb.gateway.PermissionHelper.missingKeys(this)
                            if (missing.isEmpty()) {
                                com.aqi.ccb.ui.UiToast.show("所有权限已授予")
                            } else {
                                // 逐个引导：先开第一个，用户返回后可再来一次
                                com.aqi.ccb.gateway.PermissionHelper
                                    .openSettings(this, missing.first())
                                com.aqi.ccb.ui.UiToast.show(
                                    "还有 ${missing.size} 项待授权，请逐个开启"
                                )
                            }
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
                            val actualPort = McpService.current?.port ?: AppSettings.mcpPort(this)
                            val cfg = JSONObject().put("mcpServers", JSONObject().put("gateway-rules",
                                JSONObject().put("type", "http")
                                    .put("url", "http://127.0.0.1:$actualPort/mcp")
                                    .put("headers", JSONObject().put("Authorization",
                                        "Bearer " + com.aqi.ccb.gateway.LocalAccessTokens.mcp(this)))
                            )).toString(2)
                            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("mcp", cfg)
                            clip.description.extras = android.os.PersistableBundle().apply {
                                putBoolean("android.content.extra.IS_SENSITIVE", true)
                            }
                            cm.setPrimaryClip(clip)
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
                        onSave = { configAction { saveVirtualModel() } },
                        onDelete = if (editingVmName != null) ({ configAction { deleteVirtualModel() } }) else null,
                    )
                    "rule" -> RuleEditScreen(
                        draft = ruleDraft,
                        isNew = editingRuleId == null,
                        onDraftChange = { ruleDraft = it },
                        onBack = { route = "main"; refreshMain() },
                        onSave = { configAction { saveRule() } },
                        onDelete = if (editingRuleId != null) ({ configAction { deleteRule() } }) else null,
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
                        onCopyApiKey = {
                            // 网关当前用 DemoFallback.LOCAL_AUTH；为空表示不鉴权
                            val key = com.aqi.ccb.gateway.LocalAccessTokens.gateway(this)
                            if (key.isEmpty()) {
                                com.aqi.ccb.ui.UiToast.show(
                                    "网关未设访问密钥，客户端 API Key 可留空"
                                )
                            } else {
                                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(
                                    android.content.ClipData.newPlainText("apikey", key)
                                )
                                com.aqi.ccb.ui.UiToast.show("已复制 API Key")
                            }
                        },
                        onFloatAlpha = { pct ->
                            com.aqi.ccb.gateway.FloatingBall.setAlphaPercent(pct)
                            mainState = mainState.copy(floatAlpha = pct)
                        },
                        onAddProvider = {
                            cancelProviderWork()
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
                }   // 关闭 CompositionLocalProvider
            }
        }

        refreshMain()
        // Activity 重建（如旋屏/系统回收后恢复）时，若球应显示但已丢失，补挂一次。
        // attach 内部有 `if (mainView != null) return true` 幂等保护，重复调用安全。
        if (AppSettings.floatEnabled(this)) {
            runCatching {
                if (FloatingBall.attach(this)) {
                    FloatingBall.setState(FloatingBall.STATE_IDLE)
                    FloatingBall.setMcpAlive(McpService.running)
                }
            }
        }
        // 启动即检查权限：SYSTEM_ALERT_WINDOW 是特殊权限，系统永不自动弹窗，
        // 必须应用自己弹 AlertDialog 引导。checkPermissionsOnStart 之前
        // 写了完整实现但从未被调用，导致缺权限时用户完全无感知。
        ui.postDelayed({ runCatching { checkPermissionsOnStart() } }, 500)

        ui.postDelayed({ runCatching { startGateway() } }, 600)
        startTicker()

        // 启动即检查权限：缺任何一项就弹窗引导，不静默。
        // 之前只有设置页里能看到权限状态，用户根本不知道要授权。
        ui.postDelayed({ runCatching { checkPermissionsOnStart() } }, 1200)
    }

    /**
     * 启动时检查关键权限，缺任何一项就弹窗。
     *
     * 为什么必须做：SYSTEM_ALERT_WINDOW 是特殊权限，系统**永远不会**自动弹窗，
     * 必须应用自己引导到设置页。之前只在设置页显示状态，用户不知道要开，
     * 表现就是「悬浮球设了却不显示」「后台被冻结」。
     */
    /** 上次执行 checkPermissionsOnStart 的时刻，用于防抖（onResume 会频繁触发） */
    private var lastPermCheckMs = 0L

    private fun checkPermissionsOnStart() {
        // 防抖：5 秒内重复调用直接返回，避免日志刷屏（曾出现连续 8-10 条重复记录）
        val now = System.currentTimeMillis()
        if (now - lastPermCheckMs < 5_000L) return
        lastPermCheckMs = now

        val list = com.aqi.ccb.gateway.PermissionHelper.checkAll(this)
        // 只对「确实不可用」的项弹窗：
        //   DENIED   真的缺，功能受影响 -> 必须提示
        //   CONFLICT 系统报未授予但功能正常 -> 不打扰，设置页里说明即可
        //   UNKNOWN  无法确认 -> 不打扰
        val missing = list.filter {
            it.status == com.aqi.ccb.gateway.PermissionHelper.PermStatus.DENIED
        }
        if (missing.isEmpty()) {
            permPrompt = emptyList()
            return
        }
        // 用户点过「稍后」就当天不再打扰（但换天或新增缺失项会再提示）
        val prefs = getSharedPreferences(PERM_PROMPT_PREF, MODE_PRIVATE)
        val dismissedAt = prefs.getLong(PERM_PROMPT_KEY, 0L)
        val today = System.currentTimeMillis() / 86_400_000L
        val dismissedToday = dismissedAt / 86_400_000L == today
        if (dismissedToday) {
            GateLog.info("perm", "missing=" + missing.joinToString(",") { it.key } +
                " (dismissed today, skip prompt)")
            return
        }
        permPrompt = missing.map { it.key to it.title }
        GateLog.warn(
            "perm",
            "startup missing permissions: " +
                missing.joinToString(",") { it.key + "(" + it.status + " " + it.evidence + ")" }
        )
    }

    private fun dismissPermPrompt() {
        getSharedPreferences(PERM_PROMPT_PREF, MODE_PRIVATE)
            .edit()
            .putLong(PERM_PROMPT_KEY, System.currentTimeMillis())
            .apply()
        permPrompt = emptyList()
    }

    override fun onResume() {
        super.onResume()
        refreshMain()
        // 用户从权限设置页返回后重新核对，已授予项应从提示里消失
        runCatching { checkPermissionsOnStart() }
        // ---- 冻结自愈 ----
        // vivo 会在后台对进程设 cgroup.freeze=1（内核级挂起，线程 utime 停止增长），
        // 表现为：两个前台服务都 isForeground=true、进程活着，但网关不响应。
        // 回到前台后必须主动探测并重启，否则要等系统下一次调度才恢复。
        ui.postDelayed({ runCatching { thawCheck() } }, 800)
    }

    /** 回到前台时检查网关是否真在服务；被冻结过则重启 */
    private fun thawCheck() {
        val active = GatewayService.current ?: return
        if (!active.isRunning()) return
        val port = active.port
        Thread {
            val alive = com.aqi.ccb.gateway.HttpUtil
                .get("http://127.0.0.1:$port/health", timeoutMs = 1500,
                    headers = mapOf("Authorization" to "Bearer " + com.aqi.ccb.gateway.LocalAccessTokens.gateway(this)))
                .ok
            if (!alive && GatewayService.current === active && active.isRunning()) {
                GateLog.warn("gw", "thaw: gateway unresponsive, restarting service")
                ui.post {
                    GatewayService.start(this, port)
                    com.aqi.ccb.ui.UiToast.show("检测到后台被冻结，已重启网关")
                }
                // 服务重启后球可能也没了，补挂
                ui.postDelayed({
                    runCatching {
                        if (AppSettings.floatEnabled(this)) {
                            if (FloatingBall.ensureAttached(this)) {
                                FloatingBall.setMcpAlive(McpService.running)
                            }
                        }
                    }
                }, 1500)
            }
        }.start()
    }

    override fun onDestroy() {
        cancelProviderWork()
        ui.removeCallbacksAndMessages(null)
        McpService.removeListener(mcpStateListener)
        GatewayService.removeListener(gwStateListener)

        // 网关 / MCP 由前台服务托管，Activity 销毁时都不停止。
        //
        // 悬浮球同样**不能**在这里 detach —— 这是之前退后台球消失的根因：
        //   TYPE_APPLICATION_OVERLAY 的 View 归 WindowManager 服务所有，
        //   生命周期跟进程走，不跟 Activity 走；
        //   attach 时用的是 applicationContext，不持有 Activity 引用，无泄漏。
        // 所以只要进程活着，球就该一直在。真正的 detach 时机只有两个：
        //   1) 用户在设置里关掉悬浮窗开关
        //   2) 进程真正退出（WindowManager 自动回收，无需手动）
        super.onDestroy()
    }

    private fun startTicker() {
        ui.postDelayed(object : Runnable {
            private var tick = 0
            override fun run() {
                tick++
                if (route == "main") refreshMain()
                // 每 3 秒核对一次 MCP 存活，防止 Service 状态未及时回调导致球显示失真
                if (tick % 3 == 0) {
                    val alive = McpService.current?.isRunning() == true
                    if (alive != FloatingBall.isMcpAlive()) {
                        FloatingBall.setMcpAlive(alive)
                        GateLog.info("float", "mcp probe -> " + (if (alive) "MA" else "MD"))
                    }
                }
                ui.postDelayed(this, 1000)
            }
        }, 1000)
    }

    // ==================== 刷新聚合页 ====================

    private fun refreshMain() {
        try { refreshMainChecked() } catch (e: Exception) {
            mainState=mainState.copy(statusNote="配置读取失败："+e.javaClass.simpleName)
        }
    }

    private fun refreshMainChecked() {
        val drafts = ProviderRepo.list(this)
        val ruleJsons = ConfigStore.loadRules(this)
        val s = server
        val runningNow=s?.isRunning()==true
        val now = System.currentTimeMillis()
        val healthSnapshots=if(runningNow)s!!.healthSnapshotPublic() else emptyList()
        val healthById=healthSnapshots.associateBy{it.providerId}
        fun kindLabel(v:String?)=when(v){"rate_limit"->"限流";"credit_exhausted"->"额度不足";"auth_rejected"->"认证失败";"access_rejected"->"访问拒绝";"model_or_route_missing"->"模型/路由缺失";"upstream_timeout"->"上游超时";"upstream_unavailable"->"上游不可用";"upstream_failure"->"上游故障";"connectivity"->"连接失败";"ambiguous_transport"->"发送后中断";else->"异常"}
        fun summary(id:String,enabled:Boolean):String {
            if(!enabled)return "健康：已禁用"
            if(!runningNow)return "健康：未运行"
            val h=healthById[id]?:return "健康：等待运行态"
            return when(h.state){"ready"->"健康：可用";"cooldown"->"健康：冷却 ${(h.remainingMs+999)/1000}s · ${kindLabel(h.kind)}";"degraded"->"健康：${kindLabel(h.kind)}";else->"健康：${h.state}"}
        }

        val rows = drafts.mapIndexed { i, d ->
            ProviderRow(
                index = i,
                id = d.idOr(),
                name = d.name.ifBlank { d.idOr() },
                baseUrl = d.baseUrl,
                modelCount = d.modelId.split(',', ' ').count { it.isNotBlank() },
                rateSummary = "${d.ratePerMin}/min · gap ${d.minIntervalMs}ms",
                enabled = d.enabled,
                healthSummary = summary(d.idOr(),d.enabled),
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

        val healthRows=if(!runningNow)drafts.map{d->ProviderHealthRow(d.idOr(),d.name.ifBlank{d.idOr()},if(d.enabled)"stopped" else "disabled")}else healthSnapshots.map{h->
            ProviderHealthRow(h.providerId,h.providerName,h.state,h.kind.orEmpty(),h.scope.orEmpty(),h.modelId.orEmpty(),
                (h.remainingMs+999)/1000,if(h.observedAtMs>0)(now-h.observedAtMs).coerceAtLeast(0)/1000 else -1,
                h.retrySafe,h.localImpact.orEmpty(),h.httpStatus)
        }
        // 诊断数据：直接把 GatewayServer 的运行时指标搬进 UI 状态
        val diagPath = s?.pathDiagnosisPublic() ?: "未启动"
        val diagLan = s?.lanHintPublic() ?: ""
        mainState = mainState.copy(
            virtualModels = vmRows,
            health = healthRows,
            usage = com.aqi.ccb.gateway.UsageLedgerStore.open(File(filesDir, "usage-v1")).snapshot(),
            diagPath = diagPath,
            diagBind = s?.actualBind ?: "",
            diagAcceptAlive = s?.acceptAlivePublic() == true,
            diagTcpConns = s?.tcpConnectionsPublic() ?: 0,
            diagDisconnects = s?.clientDisconnects ?: 0,
            diagWorkers = s?.workerCountPublic() ?: 0,
            diagQueued = s?.queuedCountPublic() ?: 0,
            diagUptimeSec = s?.uptimeSecPublic() ?: 0,
            diagLatestRoute = s?.latestRoute ?: "",
            diagLatestFailure = s?.latestFailure ?: "",
            diagFailureReason = s?.latestFailureReason ?: "",
            diagLanHint = diagLan,
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

    /**
     * 读系统日志尾部。
     *
     * 修复：之前写的是 appLogDir（外部目录），读的却是 filesDir（内部目录），
     * 两者路径不同，所以 UI 日志块永远空白。
     * 现在统一读 appLogDir，并对外部目录读不到时回退内部目录（兼容旧装）。
     */
    private fun tailLog(): String = try {
        val candidates = listOf(
            File(appLogDir, "system/gateway.log"),
            File(filesDir, "logs/system/gateway.log"),
        )
        val f = candidates.firstOrNull { it.exists() && it.length() > 0 }
        if (f == null) {
            // 明确告诉用户查了哪些路径，不再静默空白
            "（日志未生成）已查找：\n" + candidates.joinToString("\n") { it.absolutePath }
        } else {
            f.readLines().takeLast(25).joinToString("\n")
        }
    } catch (e: Exception) {
        "读取失败: $e"
    }

    // ==================== 网关 ====================

    private fun startGateway() {
        try { startGatewayChecked() } catch (e: Exception) {
            mainState=mainState.copy(statusNote="无法启动：配置无效")
            com.aqi.ccb.ui.UiToast.error("配置校验失败，网关未启动")
        }
    }

    private fun startGatewayChecked() {
        if (GatewayService.running) return
        val port = AppSettings.gatewayPort(this)
        val providers = ProviderRepo.runtimeProviders(this)
        val rules = ProviderRepo.runtimeRules(this)
        if (providers.isEmpty() || rules.isEmpty()) {
            mainState = mainState.copy(statusNote = "配置不完整，请先添加提供商与规则")
            startMcpIfEnabled() // management must be available before the first data-plane configuration
            return
        }

        // 交给前台服务：Activity 销毁也不停（这是本轮核心改动）
        GatewayService.start(this, port)
        GateLog.info("gw", "service start requested port=" + port)

        if (AppSettings.floatEnabled(this)) {
            val ok = FloatingBall.attach(this)
            if (!ok) {
                val err = FloatingBall.lastAttachError ?: "未知原因"
                GateLog.warn("float", "attach failed -> " + err)
                com.aqi.ccb.ui.UiToast.error("悬浮球未显示：" + err + "（设置→权限里开启悬浮窗）")
            } else {
                FloatingBall.setState(FloatingBall.STATE_IDLE)
                FloatingBall.setMcpAlive(McpService.running)
            }
        }
        startMcpIfEnabled()
        refreshMain()
    }

    private fun stopGateway() {
        GatewayService.stop(this)
        // 停网关不等于要隐藏悬浮球：球显示「待命」即可，
        // 重新启动时无需重建视图（避免反复 add/remove 造成闪烁）。
        FloatingBall.setState(FloatingBall.STATE_IDLE)
        refreshMain()
    }

    // ==================== 提供商 ====================

    private fun openProvider(index: Int) {
        val list = ProviderRepo.list(this)
        if (index !in list.indices) return
        cancelProviderWork()
        editingProviderIndex = index
        providerDraft = list[index]
        providerTest = null
        route = "provider"
    }

    private fun configAction(action:()->Unit) {
        try { action() } catch(e:Exception) {
            providerTest="保存未完成："+(e.message ?: e.javaClass.simpleName)
            com.aqi.ccb.ui.UiToast.error(providerTest ?: "配置失败")
        }
    }

    private fun showCommit(subject:String,commit:ConfigStore.Commit) {
        val persisted=if(commit.changed)"已保存" else "配置未变"
        val runtime=when {commit.applied->"运行态已应用";commit.applyError!=null->"运行态应用失败，配置已保留";else->"运行态尚未应用"}
        mainState=mainState.copy(statusNote="$subject：$persisted · 版本${commit.revision} · $runtime")
    }

    private fun saveProvider() {
        cancelProviderWork()
        if (!providerDraft.isComplete) {
            providerTest = "名称 / Base URL / 模型 ID 必填"
            return
        }
        val commit = ProviderRepo.saveCommitted(this,providerDraft)
        showCommit("提供商",commit)
        editingProviderIndex = -1
        providerDraft = ProviderDraft()
        providerTest = null
        route = "main"
        refreshMain()
    }

    private fun deleteProvider() {
        cancelProviderWork()
        showCommit("删除提供商",com.aqi.ccb.gateway.ConfigCommands.deleteProvider(this,providerDraft.idOr()))
        editingProviderIndex = -1
        providerDraft = ProviderDraft()
        route = "main"
        refreshMain()
    }

    // ==================== MCP ====================

    private fun startMcpIfEnabled() {
        if (!AppSettings.mcpEnabled(this)) {
            McpService.stop(this)
            return
        }
        if (McpService.running) return
        val port = AppSettings.mcpPort(this)
        // 交给前台服务：退后台 / Activity 回收都不会断
        McpService.start(this, "127.0.0.1", port)
        GateLog.info("mcp", "service start requested port=" + port)
    }

    fun toggleMcp(enabled: Boolean) {
        AppSettings.setMcpEnabled(this, enabled)
        if (enabled) startMcpIfEnabled() else McpService.stop(this)
    }

    fun setMcpPort(port: Int) {
        AppSettings.setMcpPort(this, port)
        if (AppSettings.mcpEnabled(this)) {
            // 服务内部先停旧实例再启新实例，避免 bind 冲突
            McpService.start(this, "127.0.0.1", port)
            GateLog.info("mcp", "service restart on port " + port)
        }
    }

    // ==================== 批量测试 ====================

    /** Invalidate old tasks before changing editor identity, connection or Activity lifecycle. */
    private fun cancelProviderWork() {
        providerTasks.invalidate()
        providerBatch?.cancel(); providerBatch = null
        providerWorker?.interrupt(); providerWorker = null
        providerBusy = false
        providerDraft = providerDraft.copy(testingProgress = "")
    }
    private fun updateProviderDraft(next: ProviderDraft) {
        val old = providerDraft
        val changed = old.idOr() != next.idOr() || old.baseUrl != next.baseUrl || old.apiKey != next.apiKey ||
            old.suffixStyle != next.suffixStyle || old.configRevision != next.configRevision
        if (changed) cancelProviderWork()
        providerDraft = if (changed) next.copy(testingProgress = "") else next
    }
    private fun beginProviderWork(message: String): ProviderTaskGate.Ticket {
        cancelProviderWork()
        providerBusy = true; providerTest = message
        return providerTasks.begin(providerDraft.idOr())
    }
    private fun ownsProviderWork(ticket: ProviderTaskGate.Ticket): Boolean =
        route == "provider" && providerTasks.accepts(ticket,providerDraft.idOr())
    private fun finishProviderWork(ticket: ProviderTaskGate.Ticket) {
        if (providerTasks.complete(ticket,providerDraft.idOr())) {
            providerBusy = false; providerWorker = null; providerBatch = null
            providerDraft = providerDraft.copy(testingProgress = "")
        }
    }
    private fun testAllModels() {
        val d = providerDraft
        val selected = ProviderWorkflows.selectedForTest(d.models)
        if(selected.isEmpty()) { providerTest = "先勾选要测试的模型；不会自动测试所有已发现模型"; return }
        val ticket = beginProviderWork("测试选中模型，最多${ProviderWorkflows.MAX_BATCH_MODELS}个，可能产生费用")
        providerDraft = providerDraft.copy(testingProgress = "0/${selected.size}")
        providerBatch = BatchTester.testAll(d.baseUrl,d.apiKey,selected,concurrency=2,
            onItem = { tested -> ui.post {
                if(ownsProviderWork(ticket)) providerDraft = providerDraft.copy(models=providerDraft.models.map { current ->
                    if(current.modelId==tested.modelId) ProviderWorkflows.mergeTestFields(current,tested) else current
                })
            } },
            onProgress = { progress -> ui.post {
                if(ownsProviderWork(ticket)) {
                    providerDraft = providerDraft.copy(testingProgress="${progress.done}/${progress.total}")
                    if(progress.finished) {
                        providerTest = "选中模型测试完成：${progress.done}/${progress.total}；未自动改变勾选"
                        finishProviderWork(ticket)
                    }
                }
            } })
    }
    private fun probeModels() {
        val d = providerDraft
        val ticket = beginProviderWork("获取模型列表中；不执行生成能力测试")
        providerWorker = Thread({
            try {
                val result = ProviderWorkflows.discover(d.baseUrl,d.apiKey,d.suffixStyle,cancelled=ticket::isCancelled)
                ui.post {
                    if(ownsProviderWork(ticket)) {
                        if(result.ok) providerDraft = providerDraft.copy(baseUrl=result.resolvedUrl,
                            models=ProviderWorkflows.mergeDiscovered(providerDraft.models,result.models))
                        providerTest = result.summary(); finishProviderWork(ticket)
                    }
                }
            } catch (_: java.util.concurrent.CancellationException) { /* invalidated; no new UI state */ }
            catch (e: Exception) { ui.post {
                if(ownsProviderWork(ticket)) {providerTest="发现失败："+e.javaClass.simpleName;finishProviderWork(ticket)}
            } }
        },"provider-discovery").apply { start() }
    }
    private fun testProvider() {
        val d = providerDraft
        val model = d.models.firstOrNull { it.enabled }
        if(model==null) {providerTest="先明确勾选一个模型；能力测试可能计费";return}
        val ticket = beginProviderWork("能力测试：${model.modelId}；一次非流式、流式与工具调用")
        providerWorker = Thread({
            try {
                val result = ProviderWorkflows.probeOne(d.baseUrl,d.apiKey,model.modelId,cancelled=ticket::isCancelled)
                ui.post {
                    if(ownsProviderWork(ticket)) {
                        providerDraft = providerDraft.copy(models=providerDraft.models.map { current ->
                            if(current.modelId==model.modelId) ProviderWorkflows.applyCapabilityResult(current,result) else current
                        },probeResult=result.summary())
                        providerTest=result.summary();finishProviderWork(ticket)
                    }
                }
            } catch (_: java.util.concurrent.CancellationException) { }
            catch (e: Exception) { ui.post {
                if(ownsProviderWork(ticket)) {providerTest="测试失败："+e.javaClass.simpleName;finishProviderWork(ticket)}
            } }
        },"provider-capability").apply { start() }
    }

    // ==================== 规则 ====================

    private fun openRule(id: String) {
        val state = ConfigStore.read(this)
        val o = state.rules.firstOrNull { it.optString("id") == id } ?: return
        editingRuleId = id
        ruleDraft = ruleJsonToDraft(o, state.revision)
        route = "rule"
    }

    private fun ruleJsonToDraft(o: JSONObject, revision:Long): RuleDraft {
        val ops = mutableListOf<String>()
        o.optJSONArray("select")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("op")?.takeIf { it.isNotBlank() }?.let { ops.add(it) }
            }
        }
        return RuleDraft(
            id = o.optString("id", "default"),
            originalJson = o.toString(),
            configRevision = revision,
            name = o.optString("name", "默认规则"),
            selectedOps = ops,
            executeOp = o.optJSONObject("execute")?.optString("op") ?: "first",
            enabled = o.optBoolean("enabled", true),
            switchGranularity = o.optString("switchGranularity", "TURN"),
        )
    }

    private fun saveRule() {
        val d = ruleDraft
        val original=if(d.originalJson.isBlank())JSONObject() else JSONObject(d.originalJson)
        val previous=original.optJSONArray("select") ?: JSONArray()
        val sel=JSONArray()
        d.selectedOps.forEach { op ->
            val saved=(0 until previous.length()).map{previous.getJSONObject(it)}.firstOrNull{it.optString("op")==op}
            val value=if(saved!=null)JSONObject(saved.toString()) else JSONObject().put("op",op).put("args",JSONObject())
            sel.put(value)
        }
        val rule=JSONObject(original.toString()).apply {
            put("id",d.id.ifBlank{"default"});put("name",d.name);put("ruleSpecVersion",1)
            put("enabled",d.enabled);put("select",sel);put("switchGranularity",d.switchGranularity)
            val oldExec=original.optJSONObject("execute")
            put("execute",if(oldExec?.optString("op")==d.executeOp)oldExec else JSONObject().put("op",d.executeOp).put("args",JSONObject()))
        }
        d.configRevision?.let { rule.put("expectedRevision",it) }
        val commit = com.aqi.ccb.gateway.ConfigCommands.saveRule(this,rule)
        showCommit("规则",commit)
        editingRuleId = null
        ruleDraft = RuleDraft()
        route = "main"
        refreshMain()
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
        val commit=VirtualModelRepo.saveCommitted(this,vmDraft)
        showCommit("虚拟模型",commit)
        editingVmName = null
        vmDraft = VirtualModelDraft()
        route = "main"
        refreshMain()
    }

    private fun deleteVirtualModel() {
        val name = editingVmName ?: return
        showCommit("删除虚拟模型",com.aqi.ccb.gateway.ConfigCommands.deleteVirtualModel(this,name))
        editingVmName = null
        vmDraft = VirtualModelDraft()
        route = "main"
        refreshMain()
    }

    private fun deleteRule() {
        val id = editingRuleId ?: return
        showCommit("删除规则",com.aqi.ccb.gateway.ConfigCommands.deleteRule(this,id))
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
