package com.aqi.ccb.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * ProviderDraft —— UI 编辑态。
 * 与运行时 [com.aqi.ccb.gateway.Provider] 分离：UI 里允许临时的空值/半填状态，
 * 落盘前才校验并转成严格契约对象。
 */
data class ProviderDraft(
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelId: String = "",
    // 限流（project 特有，UI 里可直接调）
    val ratePerMin: String = "30",
    val burst: String = "3",
    val minIntervalMs: String = "800",
    val jitterMs: String = "150",
    val maxInflight: String = "2",
    val enabled: Boolean = true,
    val tier: String = "free"
) {
    fun idOr(nameFallback: String = "provider"): String =
        (name.ifBlank { nameFallback })
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "provider" }

    val isComplete: Boolean
        get() = name.isNotBlank() && baseUrl.isNotBlank() && modelId.isNotBlank()

    fun toJson(): JSONObject {
        val models = JSONArray()
        modelId.split(',', '\n', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { mid ->
                models.put(JSONObject().apply {
                    put("modelId", mid)
                    put("abilities", JSONArray().put("TOOL"))
                    put("contextLength", 128000)
                })
            }
        return JSONObject().apply {
            put("id", idOr())
            put("name", name.ifBlank { idOr() })
            put("kind", "openai")
            put("baseUrl", baseUrl)
            put("enabled", enabled)
            put("tier", tier)
            put("weight", 1)
            if (apiKey.isNotEmpty()) put("apiKeyRef", "ref-" + idOr())
            put("models", models)
            put("rateLimit", JSONObject().apply {
                put("ratePerMin", ratePerMin.toIntOrNull() ?: 30)
                put("burst", burst.toIntOrNull() ?: 3)
                put("minIntervalMs", minIntervalMs.toIntOrNull() ?: 800)
                put("jitterMs", jitterMs.toIntOrNull() ?: 150)
                put("maxInflight", maxInflight.toIntOrNull() ?: 2)
            })
        }
    }

    companion object {
        fun fromJson(o: JSONObject): ProviderDraft {
            val models = o.optJSONArray("models")
            val ids = if (models == null) "" else (0 until models.length())
                .mapNotNull { models.optJSONObject(it)?.optString("modelId") }
                .filter { it.isNotBlank() }
                .joinToString(",")
            val rl = o.optJSONObject("rateLimit")
            return ProviderDraft(
                name = o.optString("name", ""),
                baseUrl = o.optString("baseUrl", ""),
                apiKey = "",   // 密钥单独从 keys.json 取，不回填到 UI 明文
                modelId = ids,
                ratePerMin = (rl?.optInt("ratePerMin", 30) ?: 30).toString(),
                burst = (rl?.optInt("burst", 3) ?: 3).toString(),
                minIntervalMs = (rl?.optInt("minIntervalMs", 800) ?: 800).toString(),
                jitterMs = (rl?.optInt("jitterMs", 150) ?: 150).toString(),
                maxInflight = (rl?.optInt("maxInflight", 2) ?: 2).toString(),
                enabled = o.optBoolean("enabled", true),
                tier = o.optString("tier", "free")
            )
        }
    }
}
