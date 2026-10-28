package com.aqi.ccb.data

import android.content.Context
import com.aqi.ccb.gateway.ConfigStore
import com.aqi.ccb.gateway.GateLog
import com.aqi.ccb.gateway.Provider
import com.aqi.ccb.gateway.RuleSpec
import com.aqi.ccb.gateway.SecretStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置仓库：UI 与 运行时对象 之间的唯一通道。
 * 全部读写都走 ConfigStore（providers.json / rules.json / keys.json）。
 */
object ProviderRepo {

    fun list(context: Context): List<ProviderDraft> {
        // 关键：ProviderDraft.fromJson 不回填 apiKey（避免明文进 UI）。
        // 但 MCP / 批量测试需要真实密钥，所以这里从 keys.json 回填。
        val keys = ConfigStore.loadKeys(context)
        return ConfigStore.loadProviders(context).map { o ->
            val d = ProviderDraft.fromJson(o)
            val ref = o.optString("apiKeyRef", "")
            val key = when {
                ref.isNotEmpty() && keys.containsKey(ref) -> keys[ref] ?: ""
                else -> ""
            }
            if (key.isNotEmpty()) d.copy(apiKey = key) else d
        }
    }

    fun save(context: Context, draft: ProviderDraft): Boolean {
        if (!draft.isComplete) return false
        val all = ConfigStore.loadProviders(context)
        val newJson = draft.toJson()
        val id = newJson.optString("id")
        val idx = all.indexOfFirst { it.optString("id") == id }
        if (idx >= 0) all[idx] = newJson else all.add(newJson)
        ConfigStore.saveProviders(context, all)

        if (draft.apiKey.isNotEmpty()) {
            val ref = "ref-$id"
            SecretStore.put(ref, draft.apiKey)
            val keys = ConfigStore.loadKeys(context)
            keys[ref] = draft.apiKey
            ConfigStore.saveKeys(context, keys)
        }
        return true
    }

    fun delete(context: Context, id: String) {
        val all = ConfigStore.loadProviders(context)
        all.removeAll { it.optString("id") == id }
        ConfigStore.saveProviders(context, all)
        SecretStore.clear()
        ConfigStore.loadKeys(context).forEach { (ref, key) -> SecretStore.put(ref, key) }
    }

    /** 运行时 Provider 列表；无配置时返回内置 mock 保证可自测 */
    fun runtimeProviders(context: Context): List<Provider> {
        val raw = ConfigStore.loadProviders(context)
        return if (raw.isEmpty()) DemoFallback.providers()
        else ConfigStore.toProviders(raw)
    }

    fun runtimeRules(context: Context): List<RuleSpec> {
        val raw = ConfigStore.loadRules(context)
        return if (raw.isEmpty()) DemoFallback.rules()
        else ConfigStore.toRules(raw)
    }

    /** 恢复持久化密钥到内存（App 启动时调用一次） */
    fun warmUpSecrets(context: Context) {
        ConfigStore.loadKeys(context).forEach { (ref, key) -> SecretStore.put(ref, key) }
    }

    // ---------------- 规则读写 ----------------

    fun listRules(context: Context): List<JSONObject> = ConfigStore.loadRules(context)

    fun saveRule(context: Context, rule: JSONObject) {
        val all = ConfigStore.loadRules(context)
        val id = rule.optString("id")
        val idx = all.indexOfFirst { it.optString("id") == id }
        if (idx >= 0) all[idx] = rule else all.add(rule)
        ConfigStore.saveRules(context, all)
    }

    fun deleteRule(context: Context, id: String) {
        val all = ConfigStore.loadRules(context)
        all.removeAll { it.optString("id") == id }
        ConfigStore.saveRules(context, all)
    }

    /**
     * 同步限流参数到 provider。
     *
     * 修复：原来无条件覆盖全部 provider —— 你给免费号配 30/min、
     * 付费号配 120/min，改一次规则全被冲成一个值。
     * 现在只填充「尚未配置限流」的 provider，已有配置一律保留。
     *
     * @return (填充数, 跳过数)
     */
    fun syncRateLimit(context: Context, rate: JSONObject): Pair<Int, Int> {
        val all = ConfigStore.loadProviders(context)
        if (all.isEmpty()) return 0 to 0
        var filled = 0
        var skipped = 0
        all.forEach { o ->
            if (o.has("rateLimit") && o.optJSONObject("rateLimit") != null) {
                skipped++
            } else {
                o.put("rateLimit", rate)
                filled++
            }
        }
        ConfigStore.saveProviders(context, all)
        GateLog.info(
            "config",
            "rateLimit fill=$filled skip=$skipped (已有配置不被覆盖)"
        )
        return filled to skipped
    }
}

/** 首次运行兜底（真实配置存在时不会用到） */
object DemoFallback {
    const val LOCAL_AUTH = ""

    fun providers(): List<Provider> = listOf(
        Provider.fromJson(mapOf(
            "id" to "mock",
            "name" to "Mock Provider",
            "baseUrl" to "http://127.0.0.1:9099/v1",
            "tier" to "free",
            "rateLimit" to mapOf(
                "ratePerMin" to 60, "burst" to 3,
                "minIntervalMs" to 200, "jitterMs" to 80, "maxInflight" to 4
            ),
            "models" to listOf(mapOf(
                "modelId" to "mock-model",
                "abilities" to listOf("TOOL", "REASONING"),
                "contextLength" to 128000
            ))
        ))
    )

    fun rules(): List<RuleSpec> = listOf(
        RuleSpec.fromJson(mapOf(
            "id" to "default",
            "name" to "默认规则",
            "ruleSpecVersion" to 1,
            "select" to listOf(
                mapOf("op" to "breaker_filter"),
                mapOf("op" to "fastest_first")
            ),
            "execute" to mapOf("op" to "first")
        ))
    )

    fun ruleJson(id: String = "default"): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", "默认规则")
        put("ruleSpecVersion", 1)
        put("enabled", true)
        put("select", JSONArray().apply {
            put(JSONObject().put("op", "breaker_filter"))
            put(JSONObject().put("op", "fastest_first"))
        })
        put("execute", JSONObject().put("op", "first").put("args", JSONObject()))
    }
}
