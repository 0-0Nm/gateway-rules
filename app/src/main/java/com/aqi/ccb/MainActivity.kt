package com.aqi.ccb

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aqi.ccb.gateway.GateLog
import com.aqi.ccb.gateway.GatewayServer
import com.aqi.ccb.gateway.Provider
import com.aqi.ccb.gateway.RateLimiter
import com.aqi.ccb.gateway.RatePolicy
import com.aqi.ccb.gateway.RuleSpec
import java.io.File

/**
 * 状态面板：网关启停 + 端口/请求统计 + 限流状态 + 日志路径。
 * 纯代码 UI（无 layout 依赖），保持最小可运行。
 */
class MainActivity : Activity() {

    private var server: GatewayServer? = null
    private lateinit var statusTv: TextView
    private lateinit var logTv: TextView
    private lateinit var portEt: EditText
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var testBtn: Button

    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val logDir = File(filesDir, "logs")
        GateLog.init(logDir)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val title = TextView(this).apply {
            text = "GatewayRules"
            textSize = 22f
            setPadding(0, 0, 0, 16)
        }
        root.addView(title)

        val hint = TextView(this).apply {
            text = "本地 OpenAI 兼容网关 · 规则引擎 · 限流防风控"
            textSize = 12f
            setPadding(0, 0, 0, 24)
        }
        root.addView(hint)

        // --- 端口 ---
        val portRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        portRow.addView(TextView(this).apply {
            text = "端口 "; textSize = 14f; gravity = Gravity.CENTER_VERTICAL
        })
        portEt = EditText(this).apply {
            setText("38080")
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 14f
            width = 320
        }
        portRow.addView(portEt)
        root.addView(portRow)

        // --- 按钮 ---
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = Button(this).apply { text = "启动"; textSize = 14f }
        stopBtn = Button(this).apply { text = "停止"; textSize = 14f; isEnabled = false }
        testBtn = Button(this).apply { text = "自检"; textSize = 14f }
        btnRow.addView(startBtn); btnRow.addView(stopBtn); btnRow.addView(testBtn)
        root.addView(btnRow)

        // --- 状态 ---
        statusTv = TextView(this).apply {
            text = "未启动"
            textSize = 13f
            setPadding(0, 24, 0, 24)
        }
        root.addView(statusTv)

        // --- 日志预览 ---
        val logLabel = TextView(this).apply {
            text = "系统日志（最近 40 行）"
            textSize = 12f
            setPadding(0, 8, 0, 8)
        }
        root.addView(logLabel)
        logTv = TextView(this).apply {
            textSize = 10f
            text = "-"
        }
        val logScroll = ScrollView(this).apply {
            addView(logTv)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 700)
        }
        root.addView(logScroll)

        setContentView(ScrollView(this).apply { addView(root) })

        startBtn.setOnClickListener { startGateway() }
        stopBtn.setOnClickListener { stopGateway() }
        testBtn.setOnClickListener { selfTest() }

        ui.post(ticker)
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        server?.stop()
        super.onDestroy()
    }

    // ---------------- 网关 ----------------
    private fun startGateway() {
        val port = portEt.text.toString().toIntOrNull() ?: 38080
        if (server?.isRunning() == true) { refresh(); return }

        val providers = DemoConfig.providers()
        val rules = DemoConfig.rules()
        val policies = HashMap<String, RatePolicy>()
        providers.forEach { policies[it.id] = it.ratePolicy }
        val limiter = RateLimiter(policies, RatePolicy())

        val srv = GatewayServer(port, providers, rules, limiter, DemoConfig.LOCAL_AUTH)
        val ok = srv.start()
        if (ok) {
            server = srv
            startBtn.isEnabled = false
            stopBtn.isEnabled = true
        } else {
            statusTv.text = "启动失败: ${srv.lastError}\n（端口被占用？换一个）"
        }
        refresh()
    }

    private fun stopGateway() {
        server?.stop()
        server = null
        startBtn.isEnabled = true
        stopBtn.isEnabled = false
        refresh()
    }

    private fun selfTest() {
        val sb = StringBuilder()
        sb.appendLine("=== 自检 ===")

        // 1 规则引擎
        val providers = DemoConfig.providers()
        val rules = DemoConfig.rules()
        val ctx = com.aqi.ccb.gateway.RequestCtx(modelId = "mock-model", reqId = "selftest")
        val cands = com.aqi.ccb.gateway.RuleEngine.buildCands(providers, ctx)
        sb.appendLine("候选 provider: ${cands.size}")
        val picked = runCatching {
            com.aqi.ccb.gateway.RuleEngine.applySelect(cands, ctx, rules[0].select)
        }.getOrElse { emptyList() }
        sb.appendLine("规则选路后: ${picked.size} -> ${picked.joinToString { it.p.id }}")

        val exp = com.aqi.ccb.gateway.RuleEngine.explain(rules[0], providers, ctx)
        sb.appendLine("explain.final: ${exp["final"]}")

        // 2 安全表达式
        val env = mapOf<String, Any?>("lat" to 120.0, "tier" to "free", "tool_ok" to true)
        sb.appendLine("SafeExpr 'lat<500 and tier==\\'free\\'' = ${com.aqi.ccb.gateway.SafeExpr.eval("lat < 500 and tier == 'free'", env)}")
        sb.appendLine("SafeExpr 注入尝试(应 false) = ${com.aqi.ccb.gateway.SafeExpr.eval("__import__('os')", env)}")

        // 3 限流
        val lim = RateLimiter(mapOf("p" to RatePolicy(ratePerMin = 60.0, burst = 1)), RatePolicy())
        val a1 = lim.acquire("p")
        val a2 = lim.acquire("p")
        sb.appendLine("限流 第1次=${a1.ok} 第2次=${a2.ok}(wait=${"%.0f".format(a2.waitMs)}ms)")
        lim.release("p")

        statusTv.text = sb.toString()
    }

    private fun refresh() {
        val s = server
        statusTv.text = if (s?.isRunning() == true) {
            "运行中 · 127.0.0.1:${portEt.text}\n" +
                "请求 ${s.totalRequests} · 成功 ${s.totalOk} · 失败 ${s.totalFail}\n" +
                "规则 ${DemoConfig.rules().size} 条 · provider ${DemoConfig.providers().size} 个"
        } else {
            "未启动"
        }
        logTv.text = tailLog()
    }

    private fun tailLog(): String {
        return try {
            val f = File(filesDir, "logs/system/gateway.log")
            if (!f.exists()) "-"
            else f.readLines().takeLast(40).joinToString("\n")
        } catch (e: Exception) {
            "读取失败: $e"
        }
    }
}

/**
 * Demo 配置：内置一个 mock provider + 一条 rule。
 * 真实使用时由 UI/导入替换 providers 与 rules。
 */
object DemoConfig {
    const val LOCAL_AUTH = ""   // 留空=不鉴权；线上应设为随机串

    fun providers(): List<Provider> {
        val p = Provider.fromJson(mapOf(
            "id" to "mock",
            "name" to "Mock Provider",
            "baseUrl" to "http://127.0.0.1:9099/v1",
            "tier" to "free",
            "rateLimit" to mapOf(
                "ratePerMin" to 60,
                "burst" to 3,
                "minIntervalMs" to 200,
                "jitterMs" to 80,
                "maxInflight" to 4
            ),
            "models" to listOf(mapOf(
                "modelId" to "mock-model",
                "abilities" to listOf("TOOL", "REASONING"),
                "contextLength" to 128000
            ))
        ))
        return listOf(p)
    }

    fun rules(): List<RuleSpec> {
        return listOf(RuleSpec.fromJson(mapOf(
            "id" to "default",
            "name" to "默认规则",
            "ruleSpecVersion" to 1,
            "select" to listOf(
                mapOf("op" to "breaker_filter"),
                mapOf("op" to "require", "args" to mapOf("tools" to true)),
                mapOf("op" to "where", "args" to mapOf("expr" to "headroom > 0.1 and lat < 5000")),
                mapOf("op" to "fastest_first")
            ),
            "execute" to mapOf("op" to "first")
        )))
    }
}
