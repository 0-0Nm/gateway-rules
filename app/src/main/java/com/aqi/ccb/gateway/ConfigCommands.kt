package com.aqi.ccb.gateway

import android.content.Context
import org.json.JSONObject

/** Commands merge inside the transaction lock, not against an earlier unversioned list. */
object ConfigCommands {
    private fun fail(code:String,msg:String):Nothing=throw ConfigStore.ConfigException(code,msg)
    private fun expected(p:JSONObject):Long? {
        if(!p.has("expectedRevision"))return null
        val n=p.opt("expectedRevision") as? Number ?: fail("INVALID_PATCH","expectedRevision must be integer")
        if(n.toDouble()!=n.toLong().toDouble()||n.toLong()<0)fail("INVALID_PATCH","Invalid revision")
        return n.toLong()
    }
    private fun fields(p:JSONObject,allowed:Set<String>) {
        p.keys().forEach{if(it !in allowed && it!="expectedRevision")fail("UNKNOWN_FIELD","Unknown patch field: $it")}
    }
    private fun provider(s:ConfigStore.Snapshot,id:String):JSONObject = s.providers.firstOrNull{it.optString("id")==id}
        ?: fail("NOT_FOUND","Provider not found")
    fun updateProvider(ctx:Context,id:String,patch:JSONObject):ConfigStore.Commit {
        val rate=setOf("ratePerMin","burst","minIntervalMs","jitterMs","maxInflight","softRateSec","softRateMaxSec")
        fields(patch,rate+setOf("name","baseUrl","apiKey","enabled","tier","weight","quota","suffixStyle"))
        return ConfigStore.transact(ctx,expected(patch)){s->
            val p=provider(s,id)
            patch.keys().forEach{k->
                val value=patch.get(k)
                when {
                    k=="expectedRevision"->Unit
                    k=="apiKey"->{
                        if(value !is String)fail("INVALID_PATCH","apiKey must be a string")
                        val ref=p.optString("apiKeyRef","").ifEmpty{"ref-$id"}
                        if(value.isEmpty()) {
                            p.remove("apiKeyRef")
                            if(s.providers.none{it.optString("id")!=id && it.optString("apiKeyRef")==ref})s.keys.remove(ref)
                        } else {p.put("apiKeyRef",ref);s.keys[ref]=value}
                    }
                    k in rate->{val r=p.optJSONObject("rateLimit")?:JSONObject();r.put(k,value);p.put("rateLimit",r)}
                    k=="quota" && value===JSONObject.NULL->p.remove(k)
                    else->p.put(k,value)
                }
            }
        }
    }
    fun updateModel(ctx:Context,pid:String,mid:String,patch:JSONObject):ConfigStore.Commit {
        fields(patch,setOf("displayName","enabled","type","abilities","inputModalities","outputModalities","tools","contextLength","priceInPerM","priceOutPerM","priceCurrency","priceCachedInPerM"))
        return ConfigStore.transact(ctx,expected(patch)){s->
            val a=provider(s,pid).getJSONArray("models")
            val model=(0 until a.length()).map{a.getJSONObject(it)}.firstOrNull{it.optString("modelId")==mid}
                ?:fail("NOT_FOUND","Model not found")
            if (listOf("priceInPerM", "priceOutPerM", "priceCurrency", "priceCachedInPerM").any { patch.has(it) }) {
                val old = ModelPricing.decode(ConfigStore.jsonToMap(model))
                model.put("priceVersion", ModelPricing.VERSION)
                model.put("priceInPerM", old.first ?: JSONObject.NULL)
                model.put("priceOutPerM", old.second ?: JSONObject.NULL)
            }
            patch.keys().forEach{k->if(k!="expectedRevision")model.put(k,patch.get(k))}
        }
    }
    fun setModelsEnabled(ctx:Context,pid:String,ids:Set<String>,enabled:Boolean):ConfigStore.Commit {
        if(ids.isEmpty())fail("INVALID_PATCH","At least one model id is required")
        return ConfigStore.transact(ctx){s->
            val a=provider(s,pid).getJSONArray("models");val models=(0 until a.length()).map{a.getJSONObject(it)}
            if(!models.map{it.getString("modelId")}.containsAll(ids))fail("NOT_FOUND","One or more model ids do not exist")
            models.filter{it.getString("modelId") in ids}.forEach{it.put("enabled",enabled)}
        }
    }
    fun deleteProvider(ctx:Context,id:String):ConfigStore.Commit = ConfigStore.transact(ctx){s->
        val p=provider(s,id);val ref=p.optString("apiKeyRef","")
        s.providers.remove(p)
        if(ref.isNotEmpty()&&s.providers.none{it.optString("apiKeyRef")==ref})s.keys.remove(ref)
        // Whole-generation validation rejects dangling virtual-model members.
    }
    fun saveRule(ctx:Context,rule:JSONObject):ConfigStore.Commit = ConfigStore.transact(ctx,expected(rule)){s->
        val value=JSONObject(rule.toString()).apply{remove("expectedRevision")};val id=value.optString("id","")
        val i=s.rules.indexOfFirst{it.optString("id")==id}
        if(i<0)s.rules.add(value)else s.rules[i]=value
    }
    fun deleteRule(ctx:Context,id:String):ConfigStore.Commit = ConfigStore.transact(ctx){s->
        if(!s.rules.removeAll{it.optString("id")==id})fail("NOT_FOUND","Rule not found")
    }
    fun saveVirtualModel(ctx:Context,vm:JSONObject,revision:Long?=null):ConfigStore.Commit = ConfigStore.transact(ctx,revision?:expected(vm)){s->
        val value=JSONObject(vm.toString()).apply{remove("expectedRevision")};val i=s.virtualModels.indexOfFirst{it.optString("name")==value.optString("name")}
        if(i<0)s.virtualModels.add(value)else s.virtualModels[i]=value
    }
    fun deleteVirtualModel(ctx:Context,name:String):ConfigStore.Commit = ConfigStore.transact(ctx){s->
        if(!s.virtualModels.removeAll{it.optString("name")==name})fail("NOT_FOUND","Virtual model not found")
    }
}
