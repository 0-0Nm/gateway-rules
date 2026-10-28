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
            val mv = TextView(app).apply {
                text = label()
                textSize = 11f
                setTextColor(colorFor(currentState))
                setPadding(24, 16, 24, 16)
                alpha = alphaPercent / 100f
            }
            applyBorder(mv)

            val mp = baseParams(x, y)
            mv.setOnTouchListener(makeTouchListener(prefs, isSatellite = false))
            w.addView(mv, mp)

            // ---- 卫星球：MCP 状态 ----
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
        GateLog.info("float", "detached")
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

    fun isMcpAlive(): Boolean = mcpAlive

    private fun label(): String = "● " + currentState

    private fun mcpLabel(): String = if (mcpAlive) MCP_ALIVE else MCP_DIED

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
        if (mcpAlive) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
}
