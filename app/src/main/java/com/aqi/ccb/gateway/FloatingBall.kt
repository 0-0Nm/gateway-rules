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
 * 状态悬浮球：待命白 / 生成蓝 / 中断红。
 *
 * 交互状态机（GREED 要求）：
 *
 *  【未锁定】
 *    单击          -> 锁定（变成无边框）
 *    长按 400ms    -> 进入拖动，实时跟手
 *    移动 >12px    -> 直接进入拖动（不用等长按，更跟手）
 *
 *  【已锁定】
 *    单击          -> 无反应（防误触，这是本版关键修正）
 *    长按 2000ms   -> 解锁（带振动反馈），恢复边框与可拖动
 *    拖动          -> 禁止
 *
 * 省电（知识库 K-4-08）：状态不变不重绘，无动画，静止零功耗。
 * 锁定视觉：无边框（透明背景）；解锁视觉：带背景框，便于发现。
 */
object FloatingBall {

    private var view: TextView? = null
    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var appContext: Context? = null

    private val main = Handler(Looper.getMainLooper())

    const val STATE_IDLE = "待命"
    const val STATE_RUNNING = "生成"
    const val STATE_ERROR = "中断"

    private const val PREF = "float_ball"
    private const val KEY_ALPHA = "alpha_percent"
    private const val KEY_X = "pos_x"
    private const val KEY_Y = "pos_y"
    private const val KEY_LOCKED = "locked"

    /** 未锁定态长按进入拖动的阈值 */
    private const val DRAG_HOLD_MS = 400L

    /** 锁定态长按解锁的阈值 —— 2 秒，防误触 */
    private const val UNLOCK_HOLD_MS = 2000L

    /** 位移阈值：超过即视为拖动 */
    private const val DRAG_SLOP_PX = 12f

    @Volatile private var currentState: String = STATE_IDLE
    @Volatile private var locked: Boolean = false
    private var alphaPercent: Int = 80

    // ---- 触摸状态 ----
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

    fun attach(context: Context) {
        if (view != null) return
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        alphaPercent = prefs.getInt(KEY_ALPHA, 80).coerceIn(10, 100)
        locked = prefs.getBoolean(KEY_LOCKED, false)

        try {
            val w = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val tv = TextView(app).apply {
                text = label()
                textSize = 11f
                setTextColor(colorFor(currentState))
                setPadding(24, 16, 24, 16)
                alpha = alphaPercent / 100f
            }
            applyBorder(tv)

            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.TOP or Gravity.START
            p.x = prefs.getInt(KEY_X, 40)
            p.y = prefs.getInt(KEY_Y, 200)

            tv.setOnTouchListener(makeTouchListener(prefs))
            w.addView(tv, p)

            view = tv
            wm = w
            params = p
            GateLog.info("float", "attached alpha=$alphaPercent locked=$locked pos=${p.x},${p.y}")
        } catch (e: Exception) {
            GateLog.warn("float", "attach failed: ${e.message}")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeTouchListener(prefs: android.content.SharedPreferences) =
        View.OnTouchListener { v, ev ->
            val p = params ?: return@OnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                    longPressFired = false
                    unlockFired = false

                    if (locked) {
                        // 锁定态：只等 2 秒解锁，不进入拖动
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
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(dragHoldRunnable)
                    main.removeCallbacks(unlockHoldRunnable)

                    // 刚由 2 秒长按解锁：本次手势已消费，直接结束，避免抬指又触发锁定
                    if (unlockFired) {
                        unlockFired = false
                        return@OnTouchListener true
                    }

                    if (locked) {
                        // 锁定态：单击不解锁；只有长按 2 秒那条路（已由 runnable 处理）
                        if (!unlockFired) {
                            GateLog.info("float", "locked: tap ignored (hold 2s to unlock)")
                        }
                        return@OnTouchListener true
                    }

                    if (dragging) {
                        prefs.edit().putInt(KEY_X, p.x).putInt(KEY_Y, p.y).apply()
                        GateLog.info("float", "moved to ${p.x},${p.y}")
                    } else if (!longPressFired) {
                        // 未锁定 + 未拖动 = 单击 -> 锁定
                        performLock(prefs)
                    }
                    true
                }

                else -> false
            }
        }

    /** 锁定：变无边框，停止一切交互响应（除 2 秒长按） */
    private fun performLock(prefs: android.content.SharedPreferences) {
        locked = true
        prefs.edit().putBoolean(KEY_LOCKED, true).apply()
        view?.let { v ->
            v.text = label()
            applyBorder(v)
        }
        vibrate(30)
        GateLog.info("float", "locked (borderless); hold 2s to unlock")
    }

    /** 解锁：恢复边框与可拖动 */
    private fun performUnlock() {
        locked = false
        appContext?.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_LOCKED, false)?.apply()
        view?.let { v ->
            v.text = label()
            applyBorder(v)
            v.setTextColor(colorFor(currentState))
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

    /** 透明度 10..100 百分比 */
    fun setAlphaPercent(percent: Int) {
        alphaPercent = percent.coerceIn(10, 100)
        appContext?.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            ?.edit()?.putInt(KEY_ALPHA, alphaPercent)?.apply()
        main.post { view?.alpha = alphaPercent / 100f }
    }

    fun alphaPercent(): Int = alphaPercent

    fun isLocked(): Boolean = locked

    fun detach() {
        val v = view ?: return
        try { wm?.removeView(v) } catch (_: Exception) {}
        view = null
        wm = null
        params = null
        GateLog.info("float", "detached")
    }

    /** 状态机：白(待命) / 蓝(生成中) / 红(中断)；锁定后不改色 */
    fun setState(state: String) {
        if (state == currentState) return
        currentState = state
        main.post {
            val v = view ?: return@post
            v.text = label()
            if (!locked) v.setTextColor(colorFor(state))
        }
    }

    fun currentState(): String = currentState

    private fun label(): String = "● " + currentState

    /** 锁定 = 无边框（透明背景）；解锁 = 带背景框 */
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
}
