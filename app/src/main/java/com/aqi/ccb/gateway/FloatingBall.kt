package com.aqi.ccb.gateway

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 状态悬浮球：待命白 / 生成蓝 / 中断红。
 *
 * 借鉴知识库 K-4-08（扫描型悬浮窗）的省电要点：
 *  - 状态不变时不重绘（只在 setState 变化时更新文本和颜色）
 *  - View 本身是静态 TextView，无动画无逐帧，静止时零功耗
 *
 * 生命周期：attach() 由 MainActivity 在网关启动后调用；
 * detach() 在网关停止或 Activity 销毁时调用。
 */
object FloatingBall {

    private var view: TextView? = null
    private var wm: WindowManager? = null
    private val main = Handler(Looper.getMainLooper())

    const val STATE_IDLE = "待命"
    const val STATE_RUNNING = "生成"
    const val STATE_ERROR = "中断"

    @Volatile private var currentState: String = STATE_IDLE

    fun attach(context: Context) {
        if (view != null) return
        try {
            val w = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val tv = TextView(context).apply {
                text = "● 待命"
                textSize = 11f
                setTextColor(0xFF888888.toInt())
                setPadding(24, 16, 24, 16)
                setBackgroundResource(android.R.drawable.dialog_frame)
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 40
            params.y = 200
            w.addView(tv, params)
            view = tv
            wm = w
            GateLog.info("float", "floating ball attached")
        } catch (e: Exception) {
            // 没有 SYSTEM_ALERT_WINDOW 权限会到这，静默降级：悬浮窗不可用但网关照常
            GateLog.warn("float", "attach failed: ${e.message}")
        }
    }

    fun detach() {
        val v = view ?: return
        try { wm?.removeView(v) } catch (_: Exception) {}
        view = null
        wm = null
        GateLog.info("float", "floating ball detached")
    }

    /** 状态机：白(待命) -> 蓝(生成中) -> 红(上游错误/全失败) */
    fun setState(state: String) {
        if (state == currentState) return   // K-4-08: 状态不变不重绘
        currentState = state
        main.post {
            val v = view ?: return@post
            when (state) {
                STATE_RUNNING -> {
                    v.text = "● 生成中"
                    v.setTextColor(0xFF2196F3.toInt())   // 蓝
                }
                STATE_ERROR -> {
                    v.text = "● 中断"
                    v.setTextColor(0xFFF44336.toInt())   // 红
                }
                else -> {
                    v.text = "● 待命"
                    v.setTextColor(0xFF888888.toInt())   // 白/灰
                }
            }
        }
    }

    fun currentState(): String = currentState
}
