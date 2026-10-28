package com.aqi.ccb.gateway

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * 双悬浮球：主球显示网关状态，卫星球显示 MCP 状态。
 *
 * 主球（gateway）：待命白 / 生成蓝 / 中断红
 * 卫星球（MCP）：MA = MCPAlive（绿） / MD = MCPdied（红）
 *
 * 相对位置锁定（GREED 要求）：
 *  两球共享一个"锚点 + 偏移"模型。
 *  拖动任一球时，另一球按固定偏移跟随，相对位置永远不变。
 *  锚点存主球坐标，偏移存 (gapX, gapY)，都可持久化。
 *
 * 交互（沿用原设计）：
 *  未锁定：单击=锁定（变无边框），长按 400ms 或移动>12px=拖动
 *  已锁定：单击无反应，长按 2000ms=解锁，不可拖动
 *
 * 省电（知识库 K-4-08）：状态不变不重绘，无动画，静止零功耗。
 */
object FloatingBall {

    // ---- 视图 ----
    private var mainView: TextView? = null      // 网关状态球
    private var satView: TextView? = null       // MCP 状态球（卫星）
    private var wm: WindowManager? = null
    private var mainParams: WindowManager.LayoutParams? = null
    private var satParams: WindowManager.LayoutParams? = null
    private var appContext: Context? = null

    private val main = Handler(Looper.getMainLooper())

    const val STATE_IDLE = "待命"
    const val STATE_RUNNING = "生成"
    const val STATE_ERROR = "中断"

    /** MCP 状态文案（GREED 指定） */
    const val MCP_ALIVE = "MA"
    const val MCP_DIED = "MD"

    private const val PREF = "float_ball"
    private const val KEY_ALPHA = "alpha_percent"
    private const val KEY_X = "pos_x"
    private const val KEY_Y = "pos_y"
    private const val KEY_GAP_X = "gap_x"
    private const val KEY_GAP_Y = "gap_y"
    private const val KEY_LOCKED = "locked"

    private const val DRAG_HOLD_MS = 400L
    private const val UNLOCK_HOLD_MS = 2000L
    private const val DRAG_SLOP_PX = 12f

    /** 卫星球默认相对主球的偏移（dp 折算在 attach 时算） */
    private const val DEFAULT_GAP_X_DP = 64
    private const val DEFAULT_GAP_Y_DP = 0

    @Volatile private var currentState: String = STATE_IDLE
    @Volatile private var mcpAlive: Boolean = false

    // ---- 外部状态提供者（唯一真源）----
    //
    // 为什么需要这个：
    //   原来 FloatingBall 用 @Volatile 字段缓存「网关状态」和「MCP 状态」，
    //   由各处调用 setState()/setMcpAlive() 推送。问题有两个：
    //     1 detach() 不清缓存 -> 重新 attach 时用旧值建视图
    //        （表现为「MCP 没开却显示 MA」）
    //     2 推送源分散，任何时候都可能有人推错值，没有以实际状态为准
    //   改成「提供者」模式后：视图要显示时**实时问**，不依赖推送时序。
    @Volatile private var mcpAliveProvider: (() -> Boolean)? = null
    @Volatile private var gatewayStateProvider: (() -> String)? = null

    /**
     * 绑定状态提供者。应在 Application/Service 初始化时调用一次。
     * 提供者必须返回**实时真值**（如 McpService.current?.isRunning()），
     * 而不是另一个缓存字段。
     */
    fun bindStateProviders(
        mcpAlive: () -> Boolean,
        gatewayState: () -> String,
    ) {
        mcpAliveProvider = mcpAlive
        gatewayStateProvider = gatewayState
        GateLog.info("float", "state providers bound")
    }

    /** 实时读取 MCP 存活；无 provider 时才退回缓存值 */
    private fun isMcpAliveNow(): Boolean =
        mcpAliveProvider?.invoke() ?: mcpAlive

    /** 实时读取网关状态；无 provider 时才退回缓存值 */
    private fun gatewayStateNow(): String =
        gatewayStateProvider?.invoke() ?: currentState
    @Volatile private var currentMcpLabel: String = MCP_DIED
    @Volatile private var locked: Boolean = false
    private var alphaPercent: Int = 80
    private var gapX: Int = 0
    private var gapY: Int = 0

    // ---- 拖动状态（两球共用一套手势状态）----
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var longPressFired = false
    private var unlockFired = false

    private val dragHoldRunnable = Runnable {
        longPressFired = true
        dragging = true
    }

    private val unlockHoldRunnable = Runnable {
        unlockFired = true
        performUnlock()
    }

    /**
     * 挂载双悬浮球。
     * @return true=成功；false=失败（通常是没授予悬浮窗权限），调用方应引导用户授权。
     */
    fun attach(context: Context): Boolean {
        if (mainView != null) return true
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        alphaPercent = prefs.getInt(KEY_ALPHA, 80).coerceIn(10, 100)
        locked = prefs.getBoolean(KEY_LOCKED, false)

        // 前置检查：没权限就直接返回 false，不要等到 addView 抛异常
        if (!PermissionHelper.canDrawOverlays(app)) {
            GateLog.warn("float", "attach aborted: SYSTEM_ALERT_WINDOW not granted")
            lastAttachError = "未授予悬浮窗权限"
            return false
        }

        val density = app.resources.displayMetrics.density
        gapX = prefs.getInt(KEY_GAP_X, (DEFAULT_GAP_X_DP * density).toInt())
        gapY = prefs.getInt(KEY_GAP_Y, (DEFAULT_GAP_Y_DP * density).toInt())
        val x = prefs.getInt(KEY_X, 40)
        val y = prefs.getInt(KEY_Y, 200)

        try {
            val w = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // ---- 主球：网关状态 ----
            // 建视图前先问真值，不用缓存（否则 detach/attach 后会显示旧状态）
            val gwStateNow = gatewayStateNow()
            currentState = gwStateNow
            val mv = TextView(app).apply {
                text = label()
                textSize = 11f
                setTextColor(colorFor(gwStateNow))
                setPadding(24, 16, 24, 16)
                alpha = alphaPercent / 100f
            }
            applyBorder(mv)

            val mp = baseParams(x, y)
            mv.setOnTouchListener(makeTouchListener(prefs, isSatellite = false))
            w.addView(mv, mp)

            // ---- 卫星球：MCP 状态 ----
            // 关键修复：原来直接用缓存的 mcpAlive 建视图，
            // MCP 实际没开也会显示 MA。现在先问真值。
            val mcpNow = isMcpAliveNow()
            mcpAlive = mcpNow
            currentMcpLabel = if (mcpNow) MCP_ALIVE else MCP_DIED
            val sv = TextView(app).apply {
                text = mcpLabel()
                textSize = 10f
                setTextColor(mcpColor())
                setPadding(20, 14, 20, 14)
                alpha = alphaPercent / 100f
            }
            applyBorder(sv)

            val sp = baseParams(x + gapX, y + gapY)
            sv.setOnTouchListener(makeTouchListener(prefs, isSatellite = true))
            w.addView(sv, sp)

            mainView = mv
            satView = sv
            wm = w
            mainParams = mp
            satParams = sp

            GateLog.info(
                "float",
                "attached main=${mp.x},${mp.y} sat=${sp.x},${sp.y} " +
                    "gap=${gapX},${gapY} alpha=$alphaPercent locked=$locked"
            )
            lastAttachError = null
            app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ATTACHED_ONCE, true).apply()
            return true
        } catch (e: Exception) {
            // 常见原因：appops 为 ignore（权限声明 granted 但实际被拒）
            lastAttachError = e.message ?: e::class.java.simpleName
            GateLog.error("float", "attach failed: " + lastAttachError, e)
            return false
        }
    }

    /** 最近一次 attach 失败原因；null 表示无错误 */
    @Volatile var lastAttachError: String? = null
        private set

    private const val KEY_ATTACHED_ONCE = "attached_once"

    /**
     * 是否曾经成功挂载过（持久化）。
     *
     * 为什么不能用 isMounted() 当权限证据：
     *   权限被撤销后，已 addView 的 View 不会立刻被移除，
     *   isMounted() 仍返回 true —— 于是「权限已关」被误判成「已授予」。
     * 只有「真的 addView 成功过」这个历史事实才可作为 ROM 假阴性的旁证。
     */
    fun everAttached(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_ATTACHED_ONCE, false)

    /**
     * 幂等挂载入口 —— 供 Service 在进程重启后自愈挂载。
     *
     * 为什么需要这个：
     *   原来 attach 只被 MainActivity 调用。两个前台服务都是 START_STICKY，
     *   进程被杀后系统会重启服务，但服务里没有任何代码重新挂球，
     *   于是「球跟 Activity 一起死，进程重启也不回来」。
     *
     * 行为：
     *   - 用户关了悬浮窗开关 -> 确保不残留，返回 false
     *   - 已挂载 -> 直接返回 true（不改状态，避免闪烁）
     *   - 未挂载 -> 走完整 attach（含权限检查）
     *
     * 注意：内部全部使用 applicationContext，Service 传 this 也不会泄漏。
     */
    fun ensureAttached(context: Context): Boolean {
        val app = context.applicationContext
        if (!com.aqi.ccb.data.AppSettings.floatEnabled(app)) {
            if (mainView != null) detach()
            return false
        }
        if (mainView != null) return true
        GateLog.info("float", "ensureAttached: not mounted, attaching (caller=" +
            context.javaClass.simpleName + ")")
        return attach(app)
    }

    private fun baseParams(x: Int, y: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
    }

    /**
     * 拖动逻辑：两球共用。
     * 拖动任一球时，另一球按 [gapX, gapY] 跟随，相对位置不变。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeTouchListener(
        prefs: android.content.SharedPreferences,
        isSatellite: Boolean,
    ) = View.OnTouchListener { v, ev ->
        val mp = mainParams ?: return@OnTouchListener false
        val sp = satParams ?: return@OnTouchListener false

        // 本球与另一球的起始位置
        val selfP = if (isSatellite) sp else mp
        val otherP = if (isSatellite) mp else sp

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                startX = selfP.x
                startY = selfP.y
                dragging = false
                longPressFired = false
                unlockFired = false
                if (locked) {
                    main.postDelayed(unlockHoldRunnable, UNLOCK_HOLD_MS)
                } else {
                    main.postDelayed(dragHoldRunnable, DRAG_HOLD_MS)
                }
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (locked) return@OnTouchListener true
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (!dragging && (abs(dx) > DRAG_SLOP_PX || abs(dy) > DRAG_SLOP_PX)) {
                    dragging = true
                    main.removeCallbacks(dragHoldRunnable)
                }
                if (dragging) {
                    selfP.x = startX + dx.toInt()
                    selfP.y = startY + dy.toInt()
                    try { wm?.updateViewLayout(v, selfP) } catch (_: Exception) {}

                    // 另一球跟随：主球动则卫星=主球+偏移；卫星动则主球=卫星-偏移
                    if (isSatellite) {
                        otherP.x = selfP.x - gapX
                        otherP.y = selfP.y - gapY
                        try { wm?.updateViewLayout(mainView ?: return@OnTouchListener true, otherP) }
                        catch (_: Exception) {}
                    } else {
                        otherP.x = selfP.x + gapX
                        otherP.y = selfP.y + gapY
                        try { wm?.updateViewLayout(satView ?: return@OnTouchListener true, otherP) }
                        catch (_: Exception) {}
                    }
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(dragHoldRunnable)
                main.removeCallbacks(unlockHoldRunnable)

                if (unlockFired) {
                    unlockFired = false
                    return@OnTouchListener true
                }

                if (locked) {
                    GateLog.info("float", "locked: tap ignored (hold 2s to unlock)")
                    return@OnTouchListener true
                }

                if (dragging) {
                    // 以主球坐标为锚持久化
                    prefs.edit()
                        .putInt(KEY_X, mp.x)
                        .putInt(KEY_Y, mp.y)
                        .putInt(KEY_GAP_X, sp.x - mp.x)
                        .putInt(KEY_GAP_Y, sp.y - mp.y)
                        .apply()
                    GateLog.info("float", "moved main=${mp.x},${mp.y} sat=${sp.x},${sp.y}")
                } else if (!longPressFired) {
                    performLock(prefs)
                }
                true
            }

            else -> false
        }
    }

    private fun performLock(prefs: android.content.SharedPreferences) {
        locked = true
        prefs.edit().putBoolean(KEY_LOCKED, true).apply()
        mainView?.let { applyBorder(it); it.text = label() }
        satView?.let { applyBorder(it); it.text = mcpLabel() }
        vibrate(30)
        GateLog.info("float", "locked (borderless); hold 2s to unlock")
    }

    private fun performUnlock() {
        locked = false
        appContext?.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_LOCKED, false)?.apply()
        mainView?.let {
            applyBorder(it); it.text = label(); it.setTextColor(colorFor(currentState))
        }
        satView?.let {
            applyBorder(it); it.text = mcpLabel(); it.setTextColor(mcpColor())
        }
        vibrate(60)
        GateLog.info("float", "unlocked by 2s hold")
    }

    private fun vibrate(ms: Long) {
        try {
            val vib = appContext?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            @Suppress("DEPRECATION")
            vib?.vibrate(ms)
        } catch (_: Exception) {}
    }

    fun setAlphaPercent(percent: Int) {
        alphaPercent = percent.coerceIn(10, 100)
        appContext?.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            ?.edit()?.putInt(KEY_ALPHA, alphaPercent)?.apply()
        main.post {
            val a = alphaPercent / 100f
            mainView?.alpha = a
            satView?.alpha = a
        }
    }

    fun alphaPercent(): Int = alphaPercent

    fun isLocked(): Boolean = locked

    fun detach() {
        try { mainView?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { satView?.let { wm?.removeView(it) } } catch (_: Exception) {}
        mainView = null
        satView = null
        wm = null
        mainParams = null
        satParams = null
        // 清缓存：防止下次 attach 用旧值建视图
        // （这正是「MCP 没开却显示 MA」的根因）
        mcpAlive = false
        currentMcpLabel = MCP_DIED
        currentState = STATE_IDLE
        GateLog.info("float", "detached (state cache cleared)")
    }

    // ---------------- 看门狗 ----------------

    private var watchdog: Runnable? = null
    private var watchdogCtx: Context? = null

    /**
     * 启动悬浮球看门狗。
     *
     * 解决的问题：vivo 等 ROM 会 cgroup 冻结进程，解冻后 overlay 图层
     * 可能已被 WindowManager 清理，而进程内引用仍非 null——
     * 导致「以为挂着，其实没画面」。看门狗定期核对真实窗口状态并补挂。
     *
     * 只在 Service 里启动（进程级），Activity 不参与，避免双重定时器。
     */
    fun startWatchdog(context: Context, intervalMs: Long = 30_000L) {
        stopWatchdog()
        val app = context.applicationContext
        watchdogCtx = app
        val r = object : Runnable {
            override fun run() {
                runCatching { verifyAndHeal(app) }
                main.postDelayed(this, intervalMs)
            }
        }
        watchdog = r
        main.postDelayed(r, intervalMs)
        GateLog.info("float", "watchdog started interval=${intervalMs}ms")
    }

    fun stopWatchdog() {
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
        GateLog.info("float", "watchdog stopped")
    }

    /**
     * 核对真实状态：先看进程内引用，再看窗口是否真的在屏幕上。
     * 不一致就重建（先 detach 清引用，再 ensureAttached 重挂）。
     */
    private fun verifyAndHeal(ctx: Context) {
        if (!com.aqi.ccb.data.AppSettings.floatEnabled(ctx)) {
            if (mainView != null) detach()
            return
        }
        val hasRef = mainView != null
        val windowAlive = if (hasRef) isViewAttached(mainView) else false

        if (hasRef && windowAlive) {
            // 窗口正常，但仍要核对状态文字是否落后于真值：
            // 某次 setMcpAlive/setState 推送丢失会导致球上显示旧状态。
            refreshFromProviders()
            return
        }

        if (hasRef && !windowAlive) {
            GateLog.warn("float", "watchdog: view ref exists but window gone -> rebuild")
            detach()   // 清掉失效引用
        } else {
            GateLog.warn("float", "watchdog: not mounted -> attach")
        }
        ensureAttached(ctx)
    }

    /**
     * 对外查询：悬浮球此刻是否真的挂在窗口上。
     *
     * 供 PermissionHelper 做「权限判定第二信号」——
     * 有些 ROM 的 canDrawOverlays() 会假阴性，但只要球能挂上就说明权限确实有效。
     */
    fun isMounted(): Boolean = isViewAttached(mainView)

    /** 判断 View 是否真的挂在窗口上 */
    private fun isViewAttached(v: android.view.View?): Boolean {
        if (v == null) return false
        return try {
            v.isAttachedToWindow && v.windowToken != null && v.isShown
        } catch (_: Exception) {
            false
        }
    }

    /** 网关状态：待命 / 生成中 / 中断 */
    fun setState(state: String) {
        if (state == currentState) return
        currentState = state
        main.post {
            val v = mainView ?: return@post
            v.text = label()
            if (!locked) v.setTextColor(colorFor(state))
        }
    }

    fun currentState(): String = currentState

    /**
     * MCP 状态：true=MA（存活），false=MD（已死）。
     * 只在状态翻转时重绘，避免每秒刷新。
     */
    fun setMcpAlive(alive: Boolean) {
        if (alive == mcpAlive && currentMcpLabel == mcpLabel()) return
        mcpAlive = alive
        currentMcpLabel = mcpLabel()
        main.post {
            val v = satView ?: return@post
            v.text = currentMcpLabel
            if (!locked) v.setTextColor(mcpColor())
        }
    }

    fun isMcpAlive(): Boolean = isMcpAliveNow()

    /**
     * 从提供者拉取最新状态并重绘两球。
     * 供看门狗周期调用，也供 Activity onResume 调用。
     * 这样即使某次推送丢失，也会在一个周期内自愈。
     */
    fun refreshFromProviders() {
        main.post {
            val m = isMcpAliveNow()
            val g = gatewayStateNow()
            mcpAlive = m
            currentMcpLabel = if (m) MCP_ALIVE else MCP_DIED
            currentState = g
            satView?.let { v ->
                v.text = currentMcpLabel
                if (!locked) v.setTextColor(mcpColor())
            }
            mainView?.let { v ->
                v.text = label()
                if (!locked) v.setTextColor(colorFor(g))
            }
        }
    }

    private fun label(): String = "● " + gatewayStateNow()

    private fun mcpLabel(): String = if (isMcpAliveNow()) MCP_ALIVE else MCP_DIED

    private fun applyBorder(v: TextView) {
        if (locked) {
            v.setBackgroundColor(0x00000000)
        } else {
            v.setBackgroundResource(android.R.drawable.dialog_frame)
        }
    }

    private fun colorFor(state: String): Int = when (state) {
        STATE_RUNNING -> 0xFF2196F3.toInt()
        STATE_ERROR -> 0xFFF44336.toInt()
        else -> 0xFF888888.toInt()
    }

    private fun mcpColor(): Int =
        if (isMcpAliveNow()) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
}

/**
 * 悬浮球状态的真值来源。
 *
 * 为什么单独抽出来：
 *   Activity 和两个前台服务都需要绑定悬浮球的状态提供者
 *   （Activity 销毁后球还在，必须由服务继续喂正确状态）。
 *   统一放这里，避免各处各写一套判据。
 *
 * 判据一律读服务单例的**实时状态**，不读任何缓存字段。
 */
object FloatingBallState {

    /** MCP 是否存活：看真实 server 实例，不看静态 running 标志 */
    fun mcpAlive(): Boolean = McpService.current?.isRunning() == true

    /** 失败窗口：60 秒 */
    private const val FAIL_WINDOW_MS = 60_000L
    /** 窗口内失败达到这个次数就判为「中断」，与熔断阈值一致 */
    private const val FAIL_THRESHOLD = 3

    /**
     * 网关状态：
     *   未运行                    -> 待命
     *   60s 内失败 >= 3 次        -> 中断（真实数据驱动）
     *   运行且有处理中请求        -> 生成
     *   运行但空闲                -> 待命
     *
     * 「中断」不再是死状态：由 GatewayServer 的失败时间戳窗口驱动，
     * 60 秒自然衰减，不会卡住不恢复。
     * 优先级：中断 > 生成 > 待命 —— 连续失败比「正在跑」更需要被看见。
     */
    fun gatewayState(): String {
        val gw = GatewayService.current ?: return FloatingBall.STATE_IDLE
        if (!gw.isRunning()) return FloatingBall.STATE_IDLE
        if (gw.recentFailureCountPublic(FAIL_WINDOW_MS) >= FAIL_THRESHOLD) {
            return FloatingBall.STATE_ERROR
        }
        return if (gw.inflightPublic() > 0) FloatingBall.STATE_RUNNING
        else FloatingBall.STATE_IDLE
    }

    /** 给 Activity / Service 共用的一键绑定 */
    fun bind() {
        FloatingBall.bindStateProviders(
            mcpAlive = { mcpAlive() },
            gatewayState = { gatewayState() },
        )
    }
}
