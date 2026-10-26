package com.aqi.ccb.gateway

import org.json.JSONArray
import org.json.JSONObject

/**
 * 虚拟模型 —— 核心差异化能力。
 *
 * 把若干 provider 的若干模型「融合」成一个全新的 AI 提供商模型，
 * 自定义命名（对客户端就是 model 名），请求时按绑定的规则路由到具体后端。
 *
 * 例：
 *   name = "我的全能助手"
 *   members = [
 *     {provider: "nvidia", model: "deepseek-v4.1", priority: 1},
 *     {provider: "nvidia", model: "glm-5.3",       priority: 2},
 *     {provider: "openai", model: "gpt-4o",        priority: 3}
 *   ]
 *   ruleId = "default"
 *
 * 客户端请求 {"model": "我的全能助手"} 时：
 *   1. 网关查虚拟模型表命中 name
 *   2. 取其 members 构造候选（每个 member = 一个 Cand）
 *   3. 按 ruleId 对应规则 select+execute 选路
 *   4. 转发时把 model 字段改写为 member 的真实 modelId
 */
data class VirtualModel(
    val name: String,                // 客户端看到的 model 名（唯一）
    val description: String = "",
    val enabled: Boolean = true,
    val ruleId: String = "default",  // 绑定哪条规则
    val members: List<Member> = emptyList(),
) {
    data class Member(
        val providerId: String,
        val modelId: String,
        val priority: Int = 0,       // 数字越小越优先（select 层可用）
        val weight: Double = 1.0,    // 加权轮换用
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("enabled", enabled)
        put("ruleId", ruleId)
        put("members", JSONArray().apply {
            members.forEach { m ->
                put(JSONObject().apply {
                    put("providerId", m.providerId)
                    put("modelId", m.modelId)
                    put("priority", m.priority)
                    put("weight", m.weight)
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): VirtualModel = VirtualModel(
            name = o.optString("name") ?: "",
            description = o.optString("description", ""),
            enabled = o.optBoolean("enabled", true),
            ruleId = o.optString("ruleId", "default"),
            members = o.optJSONArray("members")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { m ->
                        Member(
                            providerId = m.optString("providerId") ?: return@mapNotNull null,
                            modelId = m.optString("modelId") ?: return@mapNotNull null,
                            priority = m.optInt("priority", 0),
                            weight = m.optDouble("weight", 1.0),
                        )
                    }
                }
            } ?: emptyList()
        )
    }
}

/**
 * 虚拟模型解析：客户端请求 model 名 -> 真实候选列表。
 * 这是 GatewayServer 在 handleChat 之前的第一步。
 */
object VirtualModelResolver {

    /** 返回该虚拟名的候选；非虚拟名返回 null（走普通路由） */
    fun resolve(name: String, virtuals: List<VirtualModel>, providers: List<Provider>): List<Cand>? {
        val vm = virtuals.firstOrNull { it.enabled && it.name == name } ?: return null
        val cands = mutableListOf<Cand>()
        for (m in vm.members) {
            val p = providers.firstOrNull { it.id == m.providerId && it.enabled } ?: continue
            val model = p.models[m.modelId] ?: continue
            cands.add(Cand(p, model, mutableListOf("vmodel=${vm.name}", "prio=${m.priority}")))
        }
        return if (cands.isEmpty()) null else cands.sortedBy { it.why.lastOrNull() ?: "" }
    }

    /** 聚合页展示：所有虚拟模型 + 每个 member 的可解析性 */
    fun status(virtuals: List<VirtualModel>, providers: List<Provider>): List<Map<String, Any?>> =
        virtuals.map { vm ->
            mapOf(
                "name" to vm.name,
                "enabled" to vm.enabled,
                "ruleId" to vm.ruleId,
                "members" to vm.members.map { m ->
                    val p = providers.firstOrNull { it.id == m.providerId }
                    mapOf(
                        "providerId" to m.providerId,
                        "modelId" to m.modelId,
                        "provider_ok" to (p != null),
                        "model_ok" to (p?.models?.containsKey(m.modelId) == true),
                    )
                },
                "total" to vm.members.size,
            )
        }
}
