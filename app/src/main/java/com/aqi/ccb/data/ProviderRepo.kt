package com.aqi.ccb.data

import android.content.Context
import com.aqi.ccb.gateway.*
import org.json.JSONArray
import org.json.JSONObject

object ProviderRepo {
    fun list(context: Context): List<ProviderDraft> {
        val state=ConfigStore.read(context)
        return state.providers.map { o ->
            ProviderDraft.fromJson(o).copy(
                apiKey=state.keys[o.optString("apiKeyRef", "")] ?: "",
                configRevision=state.revision,
            )
        }
    }
    fun save(context: Context, draft: ProviderDraft): Boolean {
        if (!draft.isComplete) return false
        saveCommitted(context,draft)
        return true
    }
    fun saveCommitted(context:Context,draft:ProviderDraft):ConfigStore.Commit {
        require(draft.isComplete) { "Incomplete provider" }
        for(value in listOf(draft.ratePerMin,draft.minIntervalMs,draft.jitterMs,draft.weight)) {
            require(value.toDoubleOrNull()?.let{it.isFinite()&&it>=0}==true) { "Invalid numeric provider value" }
        }
        for(value in listOf(draft.burst,draft.maxInflight))require(value.toIntOrNull()?.let{it>0}==true){"Invalid integer limit"}
        require(draft.quota.isBlank()||draft.quota.toDoubleOrNull()?.let{it.isFinite()&&it>=0}==true){"Invalid quota"}
        return ConfigStore.transact(context,draft.configRevision) { state ->
            val id=draft.idOr();val idx=state.providers.indexOfFirst{it.optString("id")==id}
            if(idx>=0 && draft.configRevision==null)throw ConfigStore.ConfigException("ALREADY_EXISTS","Provider already exists; update by its id")
            val next=if(idx>=0) JSONObject(state.providers[idx].toString()) else JSONObject()
            val oldRef=next.optString("apiKeyRef","")
            val serialized=draft.toJson()
            val oldRate=next.optJSONObject("rateLimit") ?: JSONObject()
            val newRate=serialized.getJSONObject("rateLimit")
            newRate.keys().forEach { k -> oldRate.put(k,newRate.get(k)) }
            serialized.put("rateLimit",oldRate)
            val previousModels=next.optJSONArray("models")?:JSONArray()
            val models=serialized.getJSONArray("models")
            for(i in 0 until models.length()) {
                val model=models.getJSONObject(i)
                val previous=(0 until previousModels.length()).map{previousModels.getJSONObject(it)}.firstOrNull{it.optString("modelId")==model.optString("modelId")}
                if(previous!=null) {
                    val merged=JSONObject(previous.toString())
                    model.keys().forEach{k->merged.put(k,model.get(k))}
                    models.put(i,merged)
                }
            }
            serialized.keys().forEach{k->next.put(k,serialized.get(k))}
            // Explicit credential clearing removes reference and old private value in the same commit.
            if(draft.apiKey.isNotEmpty()) {
                val ref=oldRef.ifEmpty{"ref-$id"};next.put("apiKeyRef",ref);state.keys[ref]=draft.apiKey
            } else {
                next.remove("apiKeyRef")
                if(oldRef.isNotEmpty() && state.providers.none{it.optString("id")!=id && it.optString("apiKeyRef")==oldRef})state.keys.remove(oldRef)
            }
            if(draft.quota.isBlank())next.remove("quota")
            if(idx>=0)state.providers[idx]=next else state.providers.add(next)
        }
    }
    fun delete(context: Context,id:String) { ConfigCommands.deleteProvider(context,id) }
    fun runtimeProviders(context: Context): List<Provider> = ConfigStore.toProviders(ConfigStore.read(context).providers)
    fun runtimeRules(context: Context): List<RuleSpec> = ConfigStore.toRules(ConfigStore.read(context).rules)
    fun warmUpSecrets(context: Context) { SecretStore.replace(ConfigStore.read(context).keys) }
    fun listRules(context: Context): List<JSONObject> = ConfigStore.read(context).rules
    fun saveRule(context: Context,rule:JSONObject) { ConfigCommands.saveRule(context,rule) }
    fun deleteRule(context: Context,id:String) { ConfigCommands.deleteRule(context,id) }
    fun syncRateLimit(context: Context,rate:JSONObject): Pair<Int,Int> {
        var filled=0;var skipped=0
        ConfigStore.transact(context) { state ->
            state.providers.forEach { o ->
                if(o.has("rateLimit"))skipped++ else {o.put("rateLimit",JSONObject(rate.toString()));filled++}
            }
        }
        return filled to skipped
    }
}

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
