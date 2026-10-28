package com.aqi.ccb.ui

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import org.json.JSONObject

/**
 * UI 样式配置 —— 可由 MCP 工具 / 设置页动态调整。
 *
 * 圆角算法移植自 Dynamic-Bubble-Kit（跨端动态圆角气泡）：
 *   单行时全圆角（基准 22），行数越多圆角越小（下限 12），
 *   右下角永远锁死为小圆角（8），形成"尖角"方向感。
 *
 * 为什么用在网关上：
 *   监控面板、日志卡片、规则条目的高度随内容变化，
 *   固定圆角在长内容下会显得笨重；按行数收缩能保持视觉平衡。
 *
 * 后续会扩展主题色（见 FUTURE_THEME 注释）。
 */
data class UiStyle(
    /** 基准圆角（dp），单/两行时使用 */
    val baseRadius: Int = 22,
    /** 圆角收缩下限（dp） */
    val minRadius: Int = 12,
    /** 右下角固定圆角（dp） */
    val bottomEndRadius: Int = 8,
    /** 每多一行收缩多少 dp */
    val shrinkStep: Int = 2,
    /** 超过多少行开始收缩 */
    val shrinkAfterLines: Int = 2,
    /** 是否启用行数自适应（关闭则全部用 baseRadius） */
    val dynamicRadius: Boolean = true,
    /** 卡片底色不透明度（0-100） */
    val cardAlpha: Int = 100,
    /** 是否启用分组内小圆角（CardGroup 内部项） */
    val innerCornerEnabled: Boolean = true,
) {
    /**
     * 按行数计算圆角 —— 核心算法。
     * 对应 skill 里的：radius = max(12, 22 - (lines - 2) * 2)
     */
    fun radiusForLines(lines: Int): Int {
        if (!dynamicRadius) return baseRadius
        if (lines <= shrinkAfterLines) return baseRadius
        val shrunk = baseRadius - (lines - shrinkAfterLines) * shrinkStep
        return maxOf(minRadius, shrunk)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("baseRadius", baseRadius)
        put("minRadius", minRadius)
        put("bottomEndRadius", bottomEndRadius)
        put("shrinkStep", shrinkStep)
        put("shrinkAfterLines", shrinkAfterLines)
        put("dynamicRadius", dynamicRadius)
        put("cardAlpha", cardAlpha)
        put("innerCornerEnabled", innerCornerEnabled)
    }

    companion object {
        // FUTURE_THEME: 主题色配置预留位（下一步加）
        //   primaryColor / accentColor / cardColor / isDarkMode
        //   等服务端下发或用户自选，届时在此扩展并加 MCP 工具 gw_set_theme

        fun fromJson(o: JSONObject): UiStyle = UiStyle(
            baseRadius = o.optInt("baseRadius", 22),
            minRadius = o.optInt("minRadius", 12),
            bottomEndRadius = o.optInt("bottomEndRadius", 8),
            shrinkStep = o.optInt("shrinkStep", 2),
            shrinkAfterLines = o.optInt("shrinkAfterLines", 2),
            dynamicRadius = o.optBoolean("dynamicRadius", true),
            cardAlpha = o.optInt("cardAlpha", 100),
            innerCornerEnabled = o.optBoolean("innerCornerEnabled", true),
        )

        private const val PREF = "ui_style"
        private const val KEY = "json"

        @Volatile
        private var cached: UiStyle? = null

        fun load(ctx: Context): UiStyle {
            cached?.let { return it }
            val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY, null)
            val st = try {
                if (raw.isNullOrBlank()) UiStyle() else fromJson(JSONObject(raw))
            } catch (_: Exception) {
                UiStyle()
            }
            cached = st
            return st
        }

        fun save(ctx: Context, style: UiStyle) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, style.toJson().toString()).apply()
            cached = style
            com.aqi.ccb.gateway.GateLog.info("ui", "style saved " + style.toJson())
        }

        /** 供 MCP 工具直接改字段（JSON patch 风格） */
        fun patch(ctx: Context, patch: JSONObject): UiStyle {
            val cur = load(ctx)
            val merged = cur.toJson()
            patch.keys().forEach { k -> merged.put(k, patch.get(k)) }
            val next = fromJson(merged)
            save(ctx, next)
            return next
        }
    }
}

/** 全局 UI 样式（由 MainActivity 在根部 provide，CardGroup 等组件消费） */
val LocalUiStyle = compositionLocalOf { UiStyle() }
