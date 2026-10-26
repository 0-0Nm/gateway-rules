package com.aqi.ccb.gateway

import java.util.concurrent.ConcurrentHashMap

/**
 * 模型切换粒度控制器 —— 决定"同一会话里多久可以换后端 provider"。
 *
 * 三种粒度（对应 SwitchGranularity）：
 *   SESSION  整个会话固定同一 provider（键 = sessionId）
 *   TURN     同一 user turn 内固定（键 = sessionId#turnIndex），
 *            避开 tool_call_id / function calling 格式不兼容
 *   CALL     不粘，每次都允许重新选路
 *
 * 设计原则：
 *  1 粘性只影响"排序"，不做硬绑定 —— 首选被过滤/熔断/限流时应自动降级到次选，
 *    否则一个号挂了整会话卡死
 *  2 TURN 粒度下 turnIndex 从 messages 里 user 角色消息数量推导，
 *    不需要客户端额外配合
 *  3 CALL 粒度下仍然记录最后使用的 provider，供日志审计
 */
object SwitchPolicy {

    /** 键 -> providerId */
    private val bindings = ConcurrentHashMap<String, String>()

    /** 会话 -> 最后使用的 provider（CALL 粒度审计用） */
    private val lastUsed = ConcurrentHashMap<String, String>()

    /**
     * 计算本次请求的粘性键。返回 null 表示不粘（CALL 粒度）。
     */
    fun stickKey(granularity: SwitchGranularity, sessionId: String, turnIndex: Int): String? {
        if (sessionId.isEmpty()) return null
        return when (granularity) {
            SwitchGranularity.SESSION -> "s:$sessionId"
            SwitchGranularity.TURN -> "t:$sessionId#$turnIndex"
            SwitchGranularity.CALL -> null
        }
    }

    /**
     * 按粘性重排候选：被绑定的 provider 提到首位。
     * 若绑定对象不在候选里（被过滤/熔断），自动降级——不动原序。
     */
    fun reorder(cands: List<Cand>, granularity: SwitchGranularity,
                sessionId: String, turnIndex: Int): List<Cand> {
        if (cands.isEmpty()) return cands
        val key = stickKey(granularity, sessionId, turnIndex) ?: return cands
        val bound = bindings[key] ?: return cands
        val hit = cands.firstOrNull { it.p.id == bound }
        if (hit == null) {
            // 绑定的 provider 不在候选里 -> 自动降级，同时清掉失效绑定
            bindings.remove(key)
            GateLog.info("switch", "binding stale key=$key bound=$bound -> degrade")
            return cands
        }
        hit.why.add("stick:$granularity")
        return listOf(hit) + cands.filter { it !== hit }
    }

    /** 请求成功后记录绑定（CALL 粒度只记 lastUsed 不建粘性） */
    fun onSuccess(granularity: SwitchGranularity, sessionId: String,
                  turnIndex: Int, providerId: String) {
        if (sessionId.isEmpty()) return
        lastUsed[sessionId] = providerId
        val key = stickKey(granularity, sessionId, turnIndex) ?: return
        bindings[key] = providerId
    }

    fun lastProvider(sessionId: String): String? = lastUsed[sessionId]

    fun clear() {
        bindings.clear()
        lastUsed.clear()
    }

    fun stats(): Map<String, Int> = mapOf(
        "bindings" to bindings.size,
        "sessions" to lastUsed.size,
    )

    /** 从 OpenAI 格式 messages 里数 user 消息数，推导 turnIndex（0 起） */
    fun deriveTurnIndex(messagesJson: String): Int {
        // 轻量计数：找 "role":"user" 出现次数减 1
        var count = 0
        var idx = 0
        while (true) {
            val at = messagesJson.indexOf("\"role\"", idx)
            if (at < 0) break
            val colon = messagesJson.indexOf(':', at)
            if (colon < 0) break
            var i = colon + 1
            while (i < messagesJson.length && messagesJson[i].isWhitespace()) i++
            if (i < messagesJson.length && messagesJson[i] == '"') {
                val end = messagesJson.indexOf('"', i + 1)
                if (end > i) {
                    val role = messagesJson.substring(i + 1, end)
                    if (role == "user") count++
                    idx = end
                } else break
            } else idx = colon + 1
        }
        return (count - 1).coerceAtLeast(0)
    }
}
