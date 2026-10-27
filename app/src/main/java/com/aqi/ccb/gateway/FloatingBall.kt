package com.aqi.ccb.gateway

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * 状态悬浮球：待命白 / 生成蓝 / 中断红。
 *
 * 交互三件套（GREED 要求）：
 *  1 透明度可调百分比  —— setAlpha(percent)，持久化到 SharedPreferences
 *  2 长按可拖动位置    —— onTouchEvent 里 long press 后进入拖动态，
 *                        WindowManager.updateViewLayout 实时跟手
 *  3 单击锁定          —— 单击在「锁定/解锁」间切换，锁定后不可拖动、不变色
 *
 * 省电（知识库 K-4-08）：状态不变不重绘，无动画，静止零功耗。
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

    @Volatile private var currentState: String = STATE_IDLE
    @Volatile private var locked: Boolean = false
    private var alphaPercent: Int = 80

    // ---- 拖动状态 ----
    private var dragStarted = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var longPressTriggered = false
    private val longPressRunnable = Runnable { longPressTriggered = true; dragStarted = true }

    /** 挂载悬浮球；务必在主线程或由主线程 post 调用 */
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
                setBackgroundResource(android.R.drawable.dialog_frame)
                alpha = alphaPercent / 100f
            }
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
                    dragStarted = false
                    longPressTriggered = false
                    // 锁定态不允许长按拖动
                    if (!locked) {
                        main.postDelayed(longPressRunnable, 400L)
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (locked) return@OnTouchListener true
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    // 超过阈值也视为拖动（不必等长按），体验更顺
                    if (abs(dx) > 12f || abs(dy) > 12f) {
                        dragStarted = true
                        main.removeCallbacks(longPressRunnable)
                    }
                    if (dragStarted) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        try {
                            wm?.updateViewLayout(v, p)
                        } catch (_: Exception) {}
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPressRunnable)
                    if (dragStarted) {
                        // 拖动结束：持久化位置
                        prefs.edit().putInt(KEY_X, p.x).putInt(KEY_Y, p.y).apply()
                        GateLog.info("float", "moved to ${p.x},${p.y}")
                    } else if (!longPressTriggered) {
                        // 未拖动 = 单击 -> 切换锁定
                        toggleLock(prefs)
                    }
                    true
                }

                else -> false
            }
        }

    private fun toggleLock(prefs: android.content.SharedPreferences) {
        locked = !locked
        prefs.edit().putBoolean(KEY_LOCKED, locked).apply()
        view?.let { v ->
            v.alpha = (alphaPercent / 100f) * if (locked) 1f else 1f
            v.text = label()
        }
        GateLog.info("float", if (locked) "locked" else "unlocked")
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

    /** 状态机：白(待命) / 蓝(生成中) / 红(中断)；锁定后不改变颜色 */
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

    private fun label(): String = "● " + currentState + (if (locked) " 🔒" else "")

    private fun colorFor(state: String): Int = when (state) {
        STATE_RUNNING -> 0xFF2196F3.toInt()   // 蓝
        STATE_ERROR -> 0xFFF44336.toInt()     // 红
        else -> 0xFF888888.toInt()            // 白/灰
    }
}
