package com.aqi.ccb.gateway

import org.json.JSONArray
import org.json.JSONObject

/** Validate the whole candidate generation before any persistent or runtime change. */
object ConfigValidation {
    private fun fail(message: String): Nothing = throw ConfigStore.ConfigException("INVALID_CONFIG", message)
    private fun id(o: JSONObject, field: String): String {
        val value = o.opt(field)
        if (value !is String || value.isBlank() || value.length > 256 || value.any { it < ' ' }) fail("Invalid $field")
        return value
    }
    private fun flag(o: JSONObject, key: String) { if (o.has(key) && o.opt(key) !is Boolean) fail("$key must be boolean") }
    private fun number(o: JSONObject, key: String, minimum: Double = 0.0, integral: Boolean = false) {
        if (!o.has(key)) return
        val raw = o.opt(key) as? Number ?: fail("$key must be a number")
        val n = raw.toDouble()
        if (!n.isFinite() || n < minimum || (integral && (n % 1.0 != 0.0 || n > Int.MAX_VALUE))) fail("Invalid $key")
    }
    private fun strings(o: JSONObject, key: String, allowed: Set<String>) {
        if (!o.has(key)) return
        val a = o.optJSONArray(key) ?: fail("$key must be an array")
        for (i in 0 until a.length()) if (a.opt(i) !is String || a.getString(i) !in allowed) fail("Unsupported $key value")
    }
    fun validate(s: ConfigStore.Snapshot) {
        if (s.providers.size > 1024 || s.rules.size > 4096 || s.virtualModels.size > 4096) fail("Too many config objects")
        val ps = mutableMapOf<String, Set<String>>()
        for (p in s.providers) {
            val pid = id(p, "id"); if (ps.containsKey(pid)) fail("Duplicate provider id")
            val url = try { java.net.URI(id(p, "baseUrl")) } catch (_: Exception) { fail("Invalid baseUrl") }
            if (url.scheme !in setOf("http", "https") || url.host.isNullOrBlank() || url.userInfo != null || url.fragment != null) fail("Invalid baseUrl")
            flag(p,"enabled");number(p,"weight");number(p,"quota")
            for(key in listOf("name","suffixStyle","apiKeyRef","kind"))if(p.has(key)&&p.opt(key) !is String)fail("$key must be string")
            if (p.has("tier") && p.optString("tier") !in setOf("free","paid")) fail("Invalid tier")
            val ref = p.optString("apiKeyRef", "")
            if (ref.isNotEmpty() && !s.keys.containsKey(ref)) fail("Missing provider credential reference")
            if (p.has("apiKey")) fail("Inline provider API keys are not accepted")
            val models=p.optJSONArray("models") ?: fail("Provider models must be an array")
            val ids=mutableSetOf<String>()
            for(i in 0 until models.length()) {
                val m=models.optJSONObject(i) ?: fail("Model must be an object")
                if(!ids.add(id(m,"modelId"))) fail("Duplicate model id")
                flag(m,"enabled");number(m,"contextLength",integral=true);number(m,"priceInPerM");number(m,"priceOutPerM")
                if(m.has("displayName")&&m.opt("displayName") !is String)fail("displayName must be string")
                if(m.has("type") && m.optString("type") !in ModelType.values().map{it.name}) fail("Unsupported model type")
                strings(m,"abilities",ModelAbility.values().map{it.name}.toSet())
                strings(m,"inputModalities",Modality.values().map{it.name}.toSet())
                strings(m,"outputModalities",Modality.values().map{it.name}.toSet())
                strings(m,"tools",BuiltInTool.values().map{it.name}.toSet())
            }
            p.optJSONObject("rateLimit")?.let { rate ->
                for(k in listOf("ratePerMin","minIntervalMs","jitterMs","softRateSec","softRateMaxSec")) number(rate,k)
                number(rate,"burst",1.0,true);number(rate,"maxInflight",1.0,true)
                RatePolicy.fromJson(ConfigStore.jsonToMap(rate))
            } ?: if(p.has("rateLimit")) fail("rateLimit must be an object") else Unit
            ps[pid]=ids
        }
        val rules=mutableSetOf<String>()
        for(r in s.rules) {
            if(!rules.add(id(r,"id")))fail("Duplicate rule id")
            flag(r,"enabled")
            if(r.has("rateLimitOverride")) {
                val overrides=r.optJSONObject("rateLimitOverride")?:fail("rateLimitOverride must be object")
                overrides.keys().forEach { pid ->
                    if(pid !in ps)fail("Rate override provider does not exist")
                    val rate=overrides.optJSONObject(pid)?:fail("Rate override must be object")
                    for(key in listOf("ratePerMin","minIntervalMs","jitterMs","softRateSec","softRateMaxSec"))number(rate,key)
                    number(rate,"burst",1.0,true);number(rate,"maxInflight",1.0,true)
                }
            }
            val rule=try { RuleSpec.fromJson(ConfigStore.jsonToMap(r)) } catch (_:Exception) { fail("Invalid rule structure") }
            if(rule.execute.op != "first")fail("Execute operator is not implemented")
            val select=r.optJSONArray("select") ?: fail("select must be an array")
            for(i in 0 until select.length()) {
                val op=select.optJSONObject(i) ?: fail("select item must be an object")
                if(id(op,"op") !in RuleEngine.SELECT_OPS)fail("Unknown select operator")
                if(op.has("args") && op.optJSONObject("args")==null)fail("Operator args must be an object")
                val args=op.optJSONObject("args")?:JSONObject()
                for(key in listOf("min_quota","max_spend","window_s"))number(args,key)
                for(key in listOf("min_ctx","max_errors","n","buckets","percent"))number(args,key,integral=true)
                if(args.has("percent")&&args.getInt("percent")>100)fail("percent must be <=100")
                if(args.has("n")&&args.getInt("n")<1)fail("n must be positive")
                if(args.has("buckets")&&args.getInt("buckets")<1)fail("buckets must be positive")
                flag(args,"tools");flag(args,"vision")
            }
        }
        val vnames=mutableSetOf<String>();val realNames=ps.values.flatten().toSet()
        for(v in s.virtualModels) {
            val name=id(v,"name");if(!vnames.add(name)||name in realNames)fail("Virtual model name conflicts")
            flag(v,"enabled")
            if(v.optString("ruleId","default") !in rules)fail("Virtual model rule does not exist")
            if(v.has("transforms")) {
                val transforms=v.optJSONArray("transforms")?:fail("transforms must be array")
                for(i in 0 until transforms.length()) {
                    val t=transforms.optJSONObject(i)?:fail("transform must be object")
                    if(TransformSpec.fromJson(t)==null)fail("Unknown request transform")
                    flag(t,"enabled");flag(t,"append")
                }
            }
            val members=v.optJSONArray("members") ?: fail("members must be an array")
            if(members.length()==0)fail("Virtual model requires members")
            val seen=mutableSetOf<Pair<String,String>>()
            for(i in 0 until members.length()) {
                val m=members.optJSONObject(i) ?: fail("member must be an object")
                val pid=id(m,"providerId");val mid=id(m,"modelId")
                if(ps[pid]?.contains(mid)!=true)fail("Virtual member target does not exist")
                if(!seen.add(pid to mid))fail("Duplicate virtual member")
                number(m,"priority",integral=true);number(m,"weight")
            }
        }
    }
}
