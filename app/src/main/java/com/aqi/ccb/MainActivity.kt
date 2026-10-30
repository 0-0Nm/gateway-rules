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
                        // 价格等字段整条回传更新
                        onUpdateModel = { updated ->
                            val cur = providerDraft.models.toMutableList()
                            val i = cur.indexOfFirst { it.modelId == updated.modelId }
                            if (i >= 0) {
                                cur[i] = updated
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
                            AppSettings.setGatewayPort(this, p.toIntOrNull() ?: 38080)
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
                        onCopyApiKey = {
                            // 网关当前用 DemoFallback.LOCAL_AUTH；为空表示不鉴权
                            val key = DemoFallback.LOCAL_AUTH
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
        val port = mainState.port.toIntOrNull() ?: 38080
        Thread {
            val alive = com.aqi.ccb.gateway.HttpUtil
                .get("http://127.0.0.1:$port/health", timeoutMs = 1500)
                .ok
            if (!alive && AppSettings.gatewayPort(this) > 0) {
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
        // 诊断数据：直接把 GatewayServer 的运行时指标搬进 UI 状态
        val diagPath = s?.pathDiagnosisPublic() ?: "未启动"
        val diagLan = s?.lanHintPublic() ?: ""
        mainState = mainState.copy(
            virtualModels = vmRows,
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
        if (GatewayService.running) return
        val port = mainState.port.toIntOrNull() ?: 38080
        val providers = ProviderRepo.runtimeProviders(this)
        val rules = ProviderRepo.runtimeRules(this)
        if (providers.isEmpty() || rules.isEmpty()) {
            mainState = mainState.copy(statusNote = "配置不完整，请先添加提供商与规则")
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
                        // 与 addProvider 保持一致：拉到的模型默认不勾选，
                        // 否则「初次筛选」会被这里的重新拉取绕过。
                        providerDraft = providerDraft.copy(
                            models = list.map { ModelInfo(modelId = it, enabled = false) }
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
                // 关键修复：探测结果必须写回 ModelInfo，
                // 否则 require(tools) 永远匹配不到——网关不知道任何模型带 TOOL。
                val cur = providerDraft.models.toMutableList()
                val idx = cur.indexOfFirst { it.modelId == modelId }
                if (idx >= 0) {
                    val old = cur[idx]
                    val abs = mutableListOf<String>()
                    if (res.toolCall) abs.add("TOOL")
                    if (old.abilities.contains("REASONING")) abs.add("REASONING")
                    if (old.abilities.contains("VISION")) abs.add("VISION")
                    cur[idx] = old.copy(
                        available = res.nonStreaming,
                        latencyMs = if (res.nonStreaming) res.latencyMs else -1,
                        lastError = res.error ?: "",
                        testedAt = System.currentTimeMillis(),
                        abilities = abs,
                        nonStreaming = res.nonStreaming,
                        streaming = res.streaming,
                        toolCall = res.toolCall,
                        // 探测成功即视为用户明确要用它，自动勾选。
                        // 探测失败则不改动，保持原勾选状态（避免误开不可用的模型）。
                        enabled = if (res.nonStreaming) true else old.enabled,
                    )
                    providerDraft = providerDraft.copy(
                        models = cur,
                        probeResult = res.summary(),
                    )
                } else {
                    providerDraft = providerDraft.copy(probeResult = res.summary())
                }
            }
        }.start()
    }

    /** 拉 /models 列表，返回换行分隔的模型 ID；失败返回 "ERR:..." */
    private fun fetchModelIds(base: String, key: String): String {
        val r = com.aqi.ccb.gateway.HttpUtil.get("$base/models", key, 20_000)
        if (r.error != null) return "ERR:" + r.error
        if (!r.ok) return "ERR:http_" + r.code
        val arr = runCatching { JSONObject(r.body).optJSONArray("data") }.getOrNull()
            ?: return "ERR:no data field"
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
        val rateSync = ProviderRepo.syncRateLimit(this, JSONObject().apply {
            put("ratePerMin", d.ratePerMin.toIntOrNull() ?: 30)
            put("burst", d.burst.toIntOrNull() ?: 3)
            put("minIntervalMs", d.minIntervalMs.toIntOrNull() ?: 800)
            put("jitterMs", d.jitterMs.toIntOrNull() ?: 150)
            put("maxInflight", d.maxInflight.toIntOrNull() ?: 2)
        })
        editingRuleId = null
        ruleDraft = RuleDraft()
        route = "main"
        // 限流只填充未配置者，告知用户实际影响范围
        if (rateSync.first > 0 || rateSync.second > 0) {
            com.aqi.ccb.ui.UiToast.show(
                "限流：填充 ${rateSync.first} 个，保留 ${rateSync.second} 个已有配置"
            )
        }
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
