package com.aqi.ccb.ui

import android.content.Context
import android.widget.Toast
import com.aqi.ccb.data.AppSettings

/**
 * 提示框统一入口 —— 受「操作提示弹窗」开关控制。
 *
 * 之前全局零个 Toast 调用，开关只是写 SharedPreferences，纯空转。
 * 现在所有提示都走这里，开关才真正生效。
 */
object UiToast {

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 受开关控制；关闭时静默丢弃 */
    fun show(msg: String) {
        val ctx = appContext ?: return
        if (!AppSettings.toastEnabled(ctx)) return
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    /** 强制显示（错误类，不受开关影响） */
    fun error(msg: String) {
        val ctx = appContext ?: return
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }
}
