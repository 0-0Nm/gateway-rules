package com.aqi.ccb.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 单个模型的信息 + 测试结果。
 * 从「逗号串」升级成结构化对象，才能逐模型显示延迟/可用性。
 */
data class ModelInfo(
    val modelId: String,
    val displayName: String = "",
    /** 上下文长度（探测不到则 0） */
    val contextLength: Int = 0,
    /** 能力位：TOOL / REASONING / VISION */
    val abilities: List<String> = emptyList(),
    /** 最近一次测试：是否可用 */
    val available: Boolean? = null,
    /** 最近一次测试：延迟 ms；-1 表示未测 */
    val latencyMs: Long = -1,
    /** 最近一次测试：错误摘要 */
    val lastError: String = "",
    /** 最近一次测试时间戳 */
    val testedAt: Long = 0,
    /** 是否启用（参与选路） */
    val enabled: Boolean = true,
    /** 三件套结果：非流式 */
    val nonStreaming: Boolean? = null,
    /** 三件套结果：流式 */
    val streaming: Boolean? = null,
    /** 三件套结果：工具调用 */
    val toolCall: Boolean? = null,
    /**
     * 模型类型：CHAT / IMAGE / EMBEDDING。
     *
     * 补这个字段的原因：网关 Model 有它，UI 一直没有，
     * 用户无法区分对话模型与向量模型，全按 CHAT 处理。
     */
    val type: String = "CHAT",
    /** 输入模态：TEXT / IMAGE（可多选） */
    val inputModalities: List<String> = listOf("TEXT"),
    /** 输出模态：TEXT / IMAGE（可多选） */
    val outputModalities: List<String> = listOf("TEXT"),
    /** 内置工具：搜索/URL/图像生成等，取值见网关 BuiltInTool */
    val tools: List<String> = emptyList(),
    /**
     * 输入单价（每百万 token）。0 = 未填。
     * 单位随意，只要同一批候选之间可比即可（比如都用人民币）。
     * 上游 /v1/models 不返回价格，只能手填。
     */
    val priceInPerM: Double = 0.0,
    /** 输出单价（每百万 token）。0 = 未填。 */
    val priceOutPerM: Double = 0.0,
) {
    /** 是否填了价格 */
    val hasPrice: Boolean get() = priceInPerM > 0 || priceOutPerM > 0

    /** 综合单价，用于横向比较 */
    val blendedPrice: Double get() = (priceInPerM + priceOutPerM) / 2.0
    fun statusLabel(): String = when {
        available == null -> "未测"
        available == true && latencyMs >= 0 -> "${latencyMs}ms"
        available == true -> "可用"
        else -> "失败"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("modelId", modelId)
        if (displayName.isNotEmpty()) put("displayName", displayName)
        if (contextLength > 0) put("contextLength", contextLength)
        if (abilities.isNotEmpty()) put("abilities", JSONArray(abilities))
        available?.let { put("available", it) }
        if (latencyMs >= 0) put("latencyMs", latencyMs)
        if (lastError.isNotEmpty()) put("lastError", lastError)
        if (testedAt > 0) put("testedAt", testedAt)
        put("enabled", enabled)
        put("type", type)
        if (inputModalities.isNotEmpty()) put("inputModalities", JSONArray(inputModalities))
        if (outputModalities.isNotEmpty()) put("outputModalities", JSONArray(outputModalities))
        if (tools.isNotEmpty()) put("tools", JSONArray(tools))
        if (priceInPerM > 0) put("priceInPerM", priceInPerM)
        if (priceOutPerM > 0) put("priceOutPerM", priceOutPerM)
        nonStreaming?.let { put("nonStreaming", it) }
        streaming?.let { put("streaming", it) }
        toolCall?.let { put("toolCall", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): ModelInfo = ModelInfo(
            modelId = o.optString("modelId"),
            displayName = o.optString("displayName", ""),
            contextLength = o.optInt("contextLength", 0),
            abilities = o.optJSONArray("abilities")?.let { a ->
                (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
            } ?: emptyList(),
            available = if (o.has("available")) o.optBoolean("available") else null,
            latencyMs = if (o.has("latencyMs")) o.optLong("latencyMs") else -1,
            lastError = o.optString("lastError", ""),
            testedAt = o.optLong("testedAt", 0),
            enabled = o.optBoolean("enabled", true),
            nonStreaming = if (o.has("nonStreaming")) o.optBoolean("nonStreaming") else null,
            streaming = if (o.has("streaming")) o.optBoolean("streaming") else null,
            toolCall = if (o.has("toolCall")) o.optBoolean("toolCall") else null,
            type = o.optString("type", "CHAT"),
            inputModalities = o.optJSONArray("inputModalities")?.let { a ->
                (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            } ?: listOf("TEXT"),
            outputModalities = o.optJSONArray("outputModalities")?.let { a ->
                (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            } ?: listOf("TEXT"),
            tools = o.optJSONArray("tools")?.let { a ->
                (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            } ?: emptyList(),
            priceInPerM = o.optDouble("priceInPerM", 0.0),
            priceOutPerM = o.optDouble("priceOutPerM", 0.0),
        )
    }
}
