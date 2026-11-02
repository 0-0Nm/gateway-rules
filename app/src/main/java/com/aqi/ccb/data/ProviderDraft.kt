package com.aqi.ccb.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * ProviderDraft —— UI 编辑态。
 *
 * 与运行时 [com.aqi.ccb.gateway.Provider] 分离：UI 里允许临时的空值/半填状态，
 * 落盘前才校验并转成严格契约对象。
 *
 * 模型现在是结构化列表 [models]，不再是逗号串 —— 这样才能逐模型显示
 * 延迟 / 可用性 / 三件套结果，并支持批量测试。
 */
data class ProviderDraft(
    /**
     * 持久化 ID。与 gateway 层 Provider.id 对应。
     *
     * 为什么必须显式保存：原来没有这个字段，idOr() 只能从 name 派生，
     * name 是「幻言 A」这种中文时会派生成「幻言-a」，
     * 而配置文件里 id 是 "huanyan-a" —— 两者对不上，
     * 导致通过 MCP 用 providerId 定位提供商永远失败
     * （报 provider not found，但 diag 里明明有这个名字）。
     *
     * 现在：id 非空则直接用它，为空才回退到 name 派生（兼容旧配置）。
     */
    val id: String = "",
    val configRevision: Long? = null,
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    /** 结构化模型列表（替代原来的逗号串 modelId） */
    val models: List<ModelInfo> = emptyList(),
    // 限流（project 特有，UI 里可直接调）
    val ratePerMin: String = "30",
    val burst: String = "3",
    val minIntervalMs: String = "800",
    val jitterMs: String = "150",
    val maxInflight: String = "2",
    val softRateSec: Double = 600.0,
    val softRateMaxSec: Double = 7200.0,
    val enabled: Boolean = true,
    val tier: String = "paid",
    /**
     * 加权轮换权重。仅 weighted_rr 算子会用到。
     *
     * 之前这个值在 toJson 里硬编码为 1，UI 也没有输入框，
     * 导致所有 provider 权重恒为 1，weighted_rr 退化成均匀随机。
     */
    val weight: String = "1",
    /**
     * 余额 / 配额上限。
     *
     * 网关 Provider.quota 默认 1e9 且从不更新，
     * 导致 quota_filter 永远通过（恒大于任何阈值）。
     * 这里让用户手填一个可用额度，至少能按自己的判断做过滤。
     * 空字符串表示未填，序列化时不写该字段，网关沿用默认值。
     */
    val quota: String = "",
    /** 三件套探测结果摘要（UI 展示用） */
    val probeResult: String = "",
    /** baseUrl 后缀类型：AUTO / OPENAI / GOOGLE / OPENAI_COMPAT / ALIYUN / INPUT */
    val suffixStyle: String = "AUTO",
    /** 批量测试进度：已测/总数 */
    val testingProgress: String = "",
) {
    /** 兼容旧代码的模型 ID 串（内部使用，展示请用 models） */
    val modelId: String
        get() = models.joinToString(",") { it.modelId }

    fun idOr(nameFallback: String = "provider"): String {
        // 显式 id 优先：它才是与配置文件、网关 Provider.id 一致的那个标识
        if (id.isNotBlank()) return id
        return (name.ifBlank { nameFallback })
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "provider" }
    }

    val isComplete: Boolean
        get() = name.isNotBlank() && baseUrl.isNotBlank() && models.isNotEmpty()

    fun toJson(): JSONObject {
        val modelsArr = JSONArray()
        models.forEach { modelsArr.put(it.toJson()) }
        return JSONObject().apply {
            put("id", idOr())
            put("name", name.ifBlank { idOr() })
            put("kind", "openai")
            put("baseUrl", baseUrl)
            put("enabled", enabled)
            put("tier", tier)
            put("weight", weight.toDoubleOrNull() ?: 1.0)
            // 只有用户填了才写，未填则网关沿用默认（不污染配置）
            quota.toDoubleOrNull()?.let { put("quota", it) }
            if (apiKey.isNotEmpty()) put("apiKeyRef", "ref-" + idOr())
            put("models", modelsArr)
            put("rateLimit", JSONObject().apply {
                put("ratePerMin", ratePerMin.toDoubleOrNull() ?: 30.0)
                put("burst", burst.toIntOrNull() ?: 3)
                put("minIntervalMs", minIntervalMs.toDoubleOrNull() ?: 800.0)
                put("jitterMs", jitterMs.toDoubleOrNull() ?: 150.0)
                put("maxInflight", maxInflight.toIntOrNull() ?: 2)
                put("softRateSec", softRateSec)
                put("softRateMaxSec", softRateMaxSec)
            })
            put("suffixStyle", suffixStyle)
        }
    }

    companion object {
        fun fromJson(o: JSONObject): ProviderDraft {
            // 优先读结构化 models；否则回退兼容旧的逗号串
            val models = o.optJSONArray("models")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { m ->
                        if (m.has("modelId")) ModelInfo.fromJson(m) else null
                    }
                }
            } ?: emptyList()

            val rl = o.optJSONObject("rateLimit")
            return ProviderDraft(
                id = o.optString("id", ""),
                name = o.optString("name", ""),
                baseUrl = o.optString("baseUrl", ""),
                apiKey = "",
                models = models,
                ratePerMin = (rl?.optDouble("ratePerMin", 30.0) ?: 30.0).toString(),
                burst = (rl?.optInt("burst", 3) ?: 3).toString(),
                minIntervalMs = (rl?.optDouble("minIntervalMs", 800.0) ?: 800.0).toString(),
                jitterMs = (rl?.optDouble("jitterMs", 150.0) ?: 150.0).toString(),
                maxInflight = (rl?.optInt("maxInflight", 2) ?: 2).toString(),
                softRateSec = rl?.optDouble("softRateSec", 600.0) ?: 600.0,
                softRateMaxSec = rl?.optDouble("softRateMaxSec", 7200.0) ?: 7200.0,
                enabled = o.optBoolean("enabled", true),
                tier = o.optString("tier", "paid"),
                weight = o.optDouble("weight", 1.0).toString(),
                quota = if (o.has("quota")) o.optDouble("quota", 0.0).toString() else "",
                suffixStyle = o.optString("suffixStyle", "AUTO"),
            )
        }
    }
}
