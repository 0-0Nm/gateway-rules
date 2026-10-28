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
    /** 请求变换链（指纹清洗/UA伪装/提示词注入等），转发前按序执行 */
    val transforms: List<TransformSpec> = emptyList(),
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
        if (transforms.isNotEmpty()) {
            put("transforms", JSONArray().apply {
                transforms.forEach { put(it.toJson()) }
            })
        }
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
            } ?: emptyList(),
            transforms = TransformSpec.parseList(o.optJSONArray("transforms")),
        )
    }
}

/**
 * 虚拟模型解析：客户端请求 model 名 -> 真实候选列表。
 * 这是 GatewayServer 在 handleChat 之前的第一步。
 */
object VirtualModelResolver {

    /**
     * 返回该虚拟名的候选；非虚拟名返回 null（走普通路由）。
     *
     * 修复：原来用 `cands.sortedBy { it.why.lastOrNull() }` 排序，
     * 而 why.last() 是 "prio=10" 这种字符串——字符串序下
     * "prio=10" < "prio=2"，导致第 10 个成员排到第 2 个前面。
     * 现在按 priority 数值升序。
     */
    fun resolve(name: String, virtuals: List<VirtualModel>, providers: List<Provider>): List<Cand>? {
        val vm = virtuals.firstOrNull { it.enabled && it.name == name } ?: return null
        val ordered = mutableListOf<Pair<Int, Cand>>()
        for (m in vm.members) {
            val p = providers.firstOrNull { it.id == m.providerId && it.enabled } ?: continue
            val model = p.models[m.modelId] ?: continue
            ordered.add(
                m.priority to Cand(
                    p, model,
                    mutableListOf("vmodel=" + vm.name, "prio=" + m.priority)
                )
            )
        }
        if (ordered.isEmpty()) return null
        return ordered.sortedBy { it.first }.map { it.second }
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
