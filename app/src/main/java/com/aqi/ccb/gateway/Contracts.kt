package com.aqi.ccb.gateway

/**
 * Rule contract v1 -- mirrors schema/gateway-rules.schema.json ruleSpecVersion=1.
 * Single source of truth: do not rename fields; breaking changes bump ruleSpecVersion.
 */

const val RULE_SPEC_VERSION = 1

enum class Modality { TEXT, IMAGE }
enum class ModelType { CHAT, IMAGE, EMBEDDING }
enum class ModelAbility { TOOL, REASONING, VISION }
enum class BuiltInTool { SEARCH, URL_CONTEXT, IMAGE_GENERATION }
enum class Tier { free, paid }
enum class BreakerState { CLOSED, OPEN, HALF_OPEN }

/**
 * 模型切换粒度 —— 决定"同一个会话里多久可以换一次后端模型"。
 *
 * SESSION 对话级：整个会话固定，缓存命中最高，但单号排队会拖死
 * TURN    轮次级（默认/推荐）：同一 user turn 内（含全部 tool call 往返）固定，
 *          下一轮才可能换。避开 tool_call_id / function calling 格式不兼容问题
 * CALL    调用级：每次请求都可能换，负载最分散但 tool call 多轮会崩，
 *          必须由用户显式选择并承担风险
 */
enum class SwitchGranularity {
    SESSION, TURN, CALL;

    companion object {
        fun parse(v: String?): SwitchGranularity = runCatching {
            valueOf((v ?: "TURN").uppercase())
        }.getOrDefault(TURN)
    }
}

/** Shared price semantics for runtime and editor. Versionless double-zero was historically unknown. */
object ModelPricing {
    const val VERSION = 1
    fun decode(m: Map<String, Any?>): Pair<Double?, Double?> {
        fun value(key: String): Double? = (m[key] as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }
        val input = value("priceInPerM"); val output = value("priceOutPerM")
        if ((m["priceVersion"] as? Number)?.toDouble() == VERSION.toDouble()) return input to output
        // Preserve legacy positive-price comparisons; never reinterpret ambiguous old 0/0 as free.
        return if ((input ?: 0.0) > 0.0 || (output ?: 0.0) > 0.0) (input ?: 0.0) to (output ?: 0.0)
            else null to null
    }
    fun known(input: Double?, output: Double?): Boolean =
        input != null && output != null && input.isFinite() && output.isFinite() && input >= 0.0 && output >= 0.0
    fun blended(input: Double?, output: Double?): Double =
        if (known(input, output)) input!! / 2.0 + output!! / 2.0 else Double.POSITIVE_INFINITY
    /** Locale independent display: preserve explicit zero and tiny nonzero prices. */
    fun format(value: Double?): String {
        if (value == null) return ""
        require(value.isFinite() && value >= 0.0) { "Invalid price" }
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
    /** Accept decimal drafts without trimming a trailing dot or silently deleting signs. */
    fun editorText(proposed: String): String {
        if (proposed.isEmpty()) return ""
        require(Regex("[0-9]*([.][0-9]*)?").matches(proposed)) { "Use a nonnegative decimal price" }
        val text = if (proposed.startsWith(".")) "0$proposed" else proposed
        parseInput(text)
        return text
    }
    fun parseInput(text: String): Double? {
        if (text.isBlank()) return null
        val value = text.toDoubleOrNull()
        require(value != null && value.isFinite() && value >= 0.0) { "Price must be a finite nonnegative number" }
        return value
    }
}

data class Model(
    val modelId: String,
    val displayName: String = "",
    val type: ModelType = ModelType.CHAT,
    /**
     * 能力位。**默认为空表示「未探测」**，不假设任何能力。
     *
     * 这个默认值必须与 UI 层 ModelInfo.abilities = emptyList() 保持一致。
     * 原来这里默认 listOf(TOOL)，而 UI 默认空 —— 导致未探测的模型
     * 在网关静默通过 require(tools)，筛选条件形同虚设。
     *
     * 要标记支持 TOOL，必须由「探测」实际确认后写入，不能靠默认值。
     */
    val abilities: List<ModelAbility> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val tools: List<BuiltInTool> = emptyList(),
    /**
     * 上下文长度。0 表示「未知/未测」。
     *
     * 原来默认 128_000，而 UI 层默认 0 —— 用户看到 0，网关却按 128K 处理，
     * require(min_ctx) 会误判。
     * 未测到就是未知，不该凭空给一个乐观值。
     */
    val contextLength: Int = 0,
    /** Price per million tokens: null=unknown; explicit zero=free. Both sides are needed to compare. */
    val priceInPerM: Double? = null,
    val priceOutPerM: Double? = null,
    /**
     * 用户在本 App 的「初次筛选」里是否勾选。
     *
     * 语义（按 GREED 澄清）：
     *   App 模型列表 -> 展示上游全部模型及其支持的调用形式（一次性拉全）
     *   enabled      -> 只是「我们可能会调用它」的标记
     *   /v1/models   -> 只列 enabled 的（这才是对外真正可用的）
     *   选路         -> 只用 enabled 的
     * 默认 true 兼容旧配置。
     */
    val enabled: Boolean = true
) {
    val hasPrice: Boolean get() = ModelPricing.known(priceInPerM, priceOutPerM)
    val blendedPrice: Double get() = ModelPricing.blended(priceInPerM, priceOutPerM)
    companion object {
        fun fromJson(m: Map<String, Any?>): Model {
            val pricing = ModelPricing.decode(m)
            return Model(
            modelId = m["modelId"] as? String ?: error("model.modelId required"),
            displayName = m["displayName"] as? String ?: "",
            type = runCatching {
                ModelType.valueOf((m["type"] as? String ?: "CHAT").uppercase())
            }.getOrDefault(ModelType.CHAT),
            // 默认不假设支持任何能力。
            //
            // 原来默认 listOf(TOOL) —— 意味着没探测过的模型一律被当作
            // 支持 tool call，require(tools) 会全部通过，等于该过滤条件失效。
            // 而 UI 层 ModelInfo.abilities 默认 emptyList()，两边各说各话。
            // 现在统一为「未探测 = 不声称支持」，要 TOOL 就得先探测确认。
            abilities = (m["abilities"] as? List<*>)?.mapNotNull {
                runCatching { ModelAbility.valueOf(it.toString().uppercase()) }.getOrNull()
            } ?: emptyList(),
            inputModalities = if (!m.containsKey("inputModalities")) listOf(Modality.TEXT) else
                (m["inputModalities"] as? List<*>)?.mapNotNull { runCatching { Modality.valueOf(it.toString().uppercase()) }.getOrNull() } ?: emptyList(),
            outputModalities = if (!m.containsKey("outputModalities")) listOf(Modality.TEXT) else
                (m["outputModalities"] as? List<*>)?.mapNotNull { runCatching { Modality.valueOf(it.toString().uppercase()) }.getOrNull() } ?: emptyList(),
            tools = (m["tools"] as? List<*>)?.mapNotNull {
                runCatching { BuiltInTool.valueOf(it.toString().uppercase()) }.getOrNull()
            } ?: emptyList(),
            // 与 Model 默认值保持一致：未提供就是 0（未知），不凭空给 128K
            contextLength = (m["contextLength"] as? Number)?.toInt() ?: 0,
            priceInPerM = pricing.first,
            priceOutPerM = pricing.second,
            // 必须读：否则 UI 勾选对网关完全无效
            enabled = m["enabled"] as? Boolean ?: true
        )
        }
    }
}

data class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val kind: String = "openai",
    val enabled: Boolean = true,
    val tier: Tier = Tier.paid,
    val weight: Double = 1.0,
    val quota: Double = 1e9,
    val ratePolicy: RatePolicy = RatePolicy(),
    val models: Map<String, Model> = emptyMap(),
    val apiKeyRef: String = "",
    var latEwma: Double = 500.0,
    var errors: Int = 0,
    var breaker: BreakerState = BreakerState.CLOSED,
    var openUntilMs: Long = 0L,
    var lastUsedMs: Long = 0L,
    var spendToday: Double = 0.0
) {
    companion object {
        fun fromJson(p: Map<String, Any?>): Provider {
            val models = (p["models"] as? List<*>)?.mapNotNull { raw ->
                (raw as? Map<*, *>)?.let { mm ->
                    val conv = mm.entries.associate { it.key.toString() to it.value }
                    runCatching { Model.fromJson(conv) }.getOrNull()
                }
            }?.associateBy { it.modelId } ?: emptyMap()

            val rl = (p["rateLimit"] as? Map<*, *>)?.let { mm ->
                RatePolicy.fromJson(mm.entries.associate { it.key.toString() to it.value })
            } ?: RatePolicy()

            return Provider(
                id = p["id"] as? String ?: error("provider.id required"),
                name = p["name"] as? String ?: (p["id"] as? String ?: "?"),
                baseUrl = p["baseUrl"] as? String ?: error("provider.baseUrl required"),
                kind = p["kind"] as? String ?: "openai",
                enabled = p["enabled"] as? Boolean ?: true,
                tier = runCatching {
                    Tier.valueOf((p["tier"] as? String ?: "paid").lowercase())
                }.getOrDefault(Tier.paid),
                weight = (p["weight"] as? Number)?.toDouble() ?: 1.0,
                quota = (p["quota"] as? Number)?.toDouble() ?: 1e9,
                ratePolicy = rl,
                models = models,
                apiKeyRef = p["apiKeyRef"] as? String ?: ""
            )
        }
    }
}

data class OpCall(val op: String, val args: Map<String, Any?> = emptyMap()) {
    companion object {
        fun fromJson(o: Map<String, Any?>): OpCall = OpCall(
            op = o["op"] as? String ?: error("op required"),
            args = (o["args"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()
        )
    }
}

/**
 * select = ordered pure operators (composable, explainable)
 * execute = exactly one side-effecting operator (not composable)
 */
data class RuleSpec(
    val id: String,
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val select: List<OpCall> = emptyList(),
    /**
     * 执行算子。默认 first —— 这是当前唯一真正实现的算子。
     *
     * 原来默认 OpCall("race")，而 race 尚未实现，
     * 新建规则会立刻触发「未实现，降级为 first」的警告，等于默认值本身就在报错。
     */
    val execute: OpCall = OpCall("first"),
    val rateLimitOverride: Map<String, RatePolicy> = emptyMap(),
    /** 切换粒度，默认 TURN（安全） */
    val switchGranularity: SwitchGranularity = SwitchGranularity.TURN,
    val version: Int = RULE_SPEC_VERSION
) {
    companion object {
        fun fromJson(r: Map<String, Any?>): RuleSpec {
            val v = (r["ruleSpecVersion"] as? Number)?.toInt() ?: RULE_SPEC_VERSION
            require(v == RULE_SPEC_VERSION) { "unsupported ruleSpecVersion=$v" }

            val sel = (r["select"] as? List<*>)?.mapNotNull { raw ->
                (raw as? Map<*, *>)?.let { mm ->
                    runCatching {
                        OpCall.fromJson(mm.entries.associate { it.key.toString() to it.value })
                    }.getOrNull()
                }
            } ?: emptyList()

            val exRaw = (r["execute"] as? Map<*, *>)
                ?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()
            val ex = OpCall.fromJson(exRaw)

            val ov = (r["rateLimitOverride"] as? Map<*, *>)?.entries?.mapNotNull { e ->
                (e.value as? Map<*, *>)?.let { mm ->
                    e.key.toString() to RatePolicy.fromJson(
                        mm.entries.associate { it.key.toString() to it.value }
                    )
                }
            }?.toMap() ?: emptyMap()

            return RuleSpec(
                id = r["id"] as? String ?: error("rule.id required"),
                name = r["name"] as? String ?: "",
                description = r["description"] as? String ?: "",
                enabled = r["enabled"] as? Boolean ?: true,
                select = sel,
                execute = ex,
                rateLimitOverride = ov,
                switchGranularity = SwitchGranularity.parse(r["switchGranularity"] as? String),
                version = v
            )
        }
    }
}

/** Runtime request context */
data class RequestCtx(
    val modelId: String,
    var reqId: String = "",
    var ruleKey: String = "",
    var sessionId: String = "",
    var promptPrefix: String = "",
    var estTokens: Int = 0,
    var needTools: Boolean = false,
    var needVision: Boolean = false,
    var dryRun: Boolean = false,
    /** 当前是会话里的第几轮（0 起），用于 TURN 粒度粘性 */
    var turnIndex: Int = 0
)
