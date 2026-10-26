package com.aqi.ccb.gateway

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 配置持久化：providers.json + rules.json 落在 app filesDir。
 *
 * 用 Android 内置 org.json，零第三方依赖（compile_java / Gradle 都不受限）。
 * 密钥不落这里 —— 只存 apiKeyRef，真实 key 见 SecretStore（内存），
 * 用户若勾选"记住密钥"则另存 keys.json（明确告知风险）。
 */
object ConfigStore {

    private const val F_PROVIDERS = "providers.json"
    private const val F_RULES = "rules.json"
    private const val F_KEYS = "keys.json"

    // ---------------- Provider ----------------

    fun loadProviders(ctx: Context): MutableList<JSONObject> {
        val f = File(ctx.filesDir, F_PROVIDERS)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            MutableList(arr.length()) { arr.getJSONObject(it) }
        } catch (e: Exception) {
            GateLog.error("config", "loadProviders failed", e)
            mutableListOf()
        }
    }

    fun saveProviders(ctx: Context, list: List<JSONObject>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            File(ctx.filesDir, F_PROVIDERS).writeText(arr.toString(2), Charsets.UTF_8)
            GateLog.info("config", "providers saved n=${list.size}")
        } catch (e: Exception) {
            GateLog.error("config", "saveProviders failed", e)
        }
    }

    // ---------------- Rule ----------------

    fun loadRules(ctx: Context): MutableList<JSONObject> {
        val f = File(ctx.filesDir, F_RULES)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            MutableList(arr.length()) { arr.getJSONObject(it) }
        } catch (e: Exception) {
            GateLog.error("config", "loadRules failed", e)
            mutableListOf()
        }
    }

    fun saveRules(ctx: Context, list: List<JSONObject>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            File(ctx.filesDir, F_RULES).writeText(arr.toString(2), Charsets.UTF_8)
            GateLog.info("config", "rules saved n=${list.size}")
        } catch (e: Exception) {
            GateLog.error("config", "saveRules failed", e)
        }
    }

    // ---------------- Keys ----------------

    fun loadKeys(ctx: Context): MutableMap<String, String> {
        val f = File(ctx.filesDir, F_KEYS)
        if (!f.exists()) return mutableMapOf()
        return try {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val m = mutableMapOf<String, String>()
            o.keys().forEach { k -> m[k] = o.optString(k, "") }
            m
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    fun saveKeys(ctx: Context, keys: Map<String, String>) {
        try {
            val o = JSONObject()
            keys.forEach { (k, v) -> o.put(k, v) }
            File(ctx.filesDir, F_KEYS).writeText(o.toString(2), Charsets.UTF_8)
            GateLog.info("config", "keys saved n=${keys.size}")
        } catch (e: Exception) {
            GateLog.error("config", "saveKeys failed", e)
        }
    }

    // ---------------- 转换成运行时对象 ----------------

    fun toProviders(list: List<JSONObject>): List<Provider> =
        list.mapNotNull { o ->
            runCatching { Provider.fromJson(jsonToMap(o)) }
                .onFailure { GateLog.error("config", "provider parse failed", it) }
                .getOrNull()
        }

    fun toRules(list: List<JSONObject>): List<RuleSpec> =
        list.mapNotNull { o ->
            runCatching { RuleSpec.fromJson(jsonToMap(o)) }
                .onFailure { GateLog.error("config", "rule parse failed", it) }
                .getOrNull()
        }

    /** JSONObject -> Map<String, Any?>，供 Contracts.fromJson 使用 */
    fun jsonToMap(o: JSONObject): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        o.keys().forEach { k -> m[k] = unwrap(o.opt(k)) }
        return m
    }

    private fun unwrap(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonToMap(v)
        is JSONArray -> (0 until v.length()).map { unwrap(v.opt(it)) }
        else -> v
    }

    // ---------------- 默认模板 ----------------

    fun defaultProviderJson(name: String, baseUrl: String, modelId: String, apiKeyRef: String): JSONObject {
        val models = JSONArray().put(JSONObject().apply {
            put("modelId", modelId)
            put("abilities", JSONArray().put("TOOL").put("REASONING"))
            put("contextLength", 128000)
        })
        return JSONObject().apply {
            put("id", name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'))
            put("name", name)
            put("kind", "openai")
            put("baseUrl", baseUrl)
            put("enabled", true)
            put("tier", "free")
            put("weight", 1)
            put("apiKeyRef", apiKeyRef)
            put("models", models)
            put("rateLimit", JSONObject().apply {
                put("ratePerMin", 30)
                put("burst", 3)
                put("minIntervalMs", 800)
                put("jitterMs", 150)
                put("maxInflight", 2)
            })
        }
    }

    fun defaultRuleJson(id: String = "default"): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", "默认规则")
        put("ruleSpecVersion", 1)
        put("enabled", true)
        put("select", JSONArray().apply {
            put(JSONObject().put("op", "breaker_filter"))
            put(JSONObject().apply {
                put("op", "require")
                put("args", JSONObject().put("tools", true))
            })
            put(JSONObject().put("op", "fastest_first"))
        })
        put("execute", JSONObject().apply {
            put("op", "first")
            put("args", JSONObject())
        })
    }
}
