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
    val enabled: Boolean = true,
    val tier: String = "free",
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

    fun idOr(nameFallback: String = "provider"): String =
        (name.ifBlank { nameFallback })
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "provider" }

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
            put("weight", 1)
            if (apiKey.isNotEmpty()) put("apiKeyRef", "ref-" + idOr())
            put("models", modelsArr)
            put("rateLimit", JSONObject().apply {
                put("ratePerMin", ratePerMin.toIntOrNull() ?: 30)
                put("burst", burst.toIntOrNull() ?: 3)
                put("minIntervalMs", minIntervalMs.toIntOrNull() ?: 800)
                put("jitterMs", jitterMs.toIntOrNull() ?: 150)
                put("maxInflight", maxInflight.toIntOrNull() ?: 2)
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
                name = o.optString("name", ""),
                baseUrl = o.optString("baseUrl", ""),
                apiKey = "",
                models = models,
                ratePerMin = (rl?.optInt("ratePerMin", 30) ?: 30).toString(),
                burst = (rl?.optInt("burst", 3) ?: 3).toString(),
                minIntervalMs = (rl?.optInt("minIntervalMs", 800) ?: 800).toString(),
                jitterMs = (rl?.optInt("jitterMs", 150) ?: 150).toString(),
                maxInflight = (rl?.optInt("maxInflight", 2) ?: 2).toString(),
                enabled = o.optBoolean("enabled", true),
                tier = o.optString("tier", "free"),
                suffixStyle = o.optString("suffixStyle", "AUTO"),
            )
        }
    }
}
