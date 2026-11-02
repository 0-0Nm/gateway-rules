package com.aqi.ccb.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 虚拟模型编辑态。
 * 客户端看到的 model 名 = [name]，成员 = 若干 (provider, model) 组合，绑定一条规则。
 */
data class VirtualModelDraft(
    val name: String = "",
    val configRevision: Long? = null,
    val originalJson: String = "",
    val description: String = "",
    val ruleId: String = "default",
    val enabled: Boolean = true,
    /** "providerId|modelId" 形式的成员列表 */
    val members: List<String> = emptyList(),
    /** 请求变换链（指纹清洗/UA伪装/prompt 注入），随虚拟模型一起存取 */
    val transforms: List<com.aqi.ccb.gateway.TransformSpec> = emptyList(),
) {
    val isComplete: Boolean get() = name.isNotBlank() && members.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("enabled", enabled)
        put("ruleId", ruleId)
        if (transforms.isNotEmpty()) {
            put("transforms", JSONArray().apply {
                transforms.forEach { put(it.toJson()) }
            })
        }
        val old=if(originalJson.isNotBlank())JSONObject(originalJson).optJSONArray("members") else null
        put("members", JSONArray().apply {
            members.forEachIndexed { index,key ->
                val parts=key.split('|',limit=2)
                require(parts.size==2 && parts.all{it.isNotBlank()}) { "Invalid member" }
                val previous=old?.let{arr->(0 until arr.length()).map{arr.getJSONObject(it)}.firstOrNull{it.optString("providerId")==parts[0]&&it.optString("modelId")==parts[1]}}
                put((previous?.let{JSONObject(it.toString())}?:JSONObject().put("weight",1.0))
                    .put("providerId",parts[0]).put("modelId",parts[1]).put("priority",index))
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): VirtualModelDraft {
            val members = o.optJSONArray("members")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { m ->
                        val p = m.optString("providerId")
                        val mid = m.optString("modelId")
                        if (p.isBlank() || mid.isBlank()) null else "$p|$mid"
                    }
                }
            } ?: emptyList()
            return VirtualModelDraft(
                originalJson = o.toString(),
                transforms = com.aqi.ccb.gateway.TransformSpec
                    .parseList(o.optJSONArray("transforms")),
                name = o.optString("name", ""),
                description = o.optString("description", ""),
                ruleId = o.optString("ruleId", "default"),
                enabled = o.optBoolean("enabled", true),
                members = members,
            )
        }
    }
}
