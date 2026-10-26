package com.aqi.ccb.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 虚拟模型编辑态。
 * 客户端看到的 model 名 = [name]，成员 = 若干 (provider, model) 组合，绑定一条规则。
 */
data class VirtualModelDraft(
    val name: String = "",
    val description: String = "",
    val ruleId: String = "default",
    val enabled: Boolean = true,
    /** "providerId|modelId" 形式的成员列表 */
    val members: List<String> = emptyList(),
) {
    val isComplete: Boolean get() = name.isNotBlank() && members.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("enabled", enabled)
        put("ruleId", ruleId)
        put("members", JSONArray().apply {
            members.forEachIndexed { idx, m ->
                val parts = m.split('|')
                if (parts.size >= 2) {
                    put(JSONObject().apply {
                        put("providerId", parts[0])
                        put("modelId", parts[1])
                        put("priority", idx)
                        put("weight", 1.0)
                    })
                }
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
                name = o.optString("name", ""),
                description = o.optString("description", ""),
                ruleId = o.optString("ruleId", "default"),
                enabled = o.optBoolean("enabled", true),
                members = members,
            )
        }
    }
}
