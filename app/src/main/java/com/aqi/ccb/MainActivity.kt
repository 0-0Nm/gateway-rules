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
import com.aqi.ccb.gateway.ConfigStore
import com.aqi.ccb.gateway.GateLog
import com.aqi.ccb.gateway.GatewayServer
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
    private val ui = Handler(Looper.getMainLooper())

    // ---- 顶层导航状态 ----
    private var route by mutableStateOf("main")

    // ---- 聚合页状态 ----
    private var mainState by mutableStateOf(MainUiState())

    // ---- 编辑态 ----
    private var editingProviderIndex by mutableStateOf(-1)
    private var providerDraft by mutableStateOf(ProviderDraft())
    private var providerTest by mutableStateOf<String?>(null)

    private var editingRuleId by mutableStateOf<String?>(null)
    private var ruleDraft by mutableStateOf(RuleDraft())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GateLog.init(File(filesDir, "logs"))
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
                        testResult = providerTest,
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
                        onStart = { startGateway() },
                        onStop = { stopGateway() },
                        onPortChange = { mainState = mainState.copy(port = it) },
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

        val s = server
        mainState = mainState.copy(
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

        val srv = GatewayServer(port, providers, rules, limiter, "")
        if (srv.start()) {
            server = srv
            mainState = mainState.copy(statusNote = null)
        } else {
            mainState = mainState.copy(statusNote = "启动失败：${srv.lastError}（端口被占用？）")
        }
        refreshMain()
    }

    private fun stopGateway() {
        server?.stop()
        server = null
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

    /** 探测模型：GET {baseUrl}/models */
    private fun testProvider() {
        val d = providerDraft
        if (!d.baseUrl.startsWith("http")) {
            providerTest = "Base URL 需以 http(s):// 开头"
            return
        }
        providerTest = "探测中…"
        Thread {
            val result = runCatching { fetchModelIds(d.baseUrl.trimEnd('/'), d.apiKey) }
                .getOrElse { "ERR:${it.message}" }
            ui.post {
                if (result.startsWith("ERR:")) {
                    providerTest = "失败 ${result.take(70)}"
                } else {
                    val ids = result.split("\n").filter { it.isNotBlank() }
                    providerDraft = providerDraft.copy(modelId = ids.joinToString(","))
                    providerTest = "发现 ${ids.size} 个模型"
                }
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
