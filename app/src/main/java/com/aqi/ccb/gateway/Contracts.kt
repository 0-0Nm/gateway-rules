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

data class Model(
    val modelId: String,
    val displayName: String = "",
    val type: ModelType = ModelType.CHAT,
    val abilities: List<ModelAbility> = listOf(ModelAbility.TOOL),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val tools: List<BuiltInTool> = emptyList(),
    val contextLength: Int = 128_000
) {
    companion object {
        fun fromJson(m: Map<String, Any?>): Model = Model(
            modelId = m["modelId"] as? String ?: error("model.modelId required"),
            displayName = m["displayName"] as? String ?: "",
            type = runCatching {
                ModelType.valueOf((m["type"] as? String ?: "CHAT").uppercase())
            }.getOrDefault(ModelType.CHAT),
            abilities = (m["abilities"] as? List<*>)?.mapNotNull {
                runCatching { ModelAbility.valueOf(it.toString().uppercase()) }.getOrNull()
            } ?: listOf(ModelAbility.TOOL),
            tools = (m["tools"] as? List<*>)?.mapNotNull {
                runCatching { BuiltInTool.valueOf(it.toString().uppercase()) }.getOrNull()
            } ?: emptyList(),
            contextLength = (m["contextLength"] as? Number)?.toInt() ?: 128_000
        )
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
    val execute: OpCall = OpCall("race"),
    val rateLimitOverride: Map<String, RatePolicy> = emptyMap(),
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
    var dryRun: Boolean = false
)
