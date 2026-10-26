package com.aqi.ccb.gateway

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/** 候选：provider + 命中的 model */
data class Cand(
    val p: Provider,
    val m: Model,
    val why: MutableList<String> = mutableListOf()
)

/** 执行结果 */
data class ExecResult(
    val ok: Boolean,
    val provider: String? = null,
    val data: String? = null,
    val err: String? = null,
    val trace: List<String> = emptyList(),
    val totalMs: Double = 0.0,
    val degraded: Boolean = false
)

/** 运行时状态：轮转计数器 / 前缀绑定 / 错误窗口 */
object RuntimeState {
    val rr = ConcurrentHashMap<String, Int>()
    val prefixMap = ConcurrentHashMap<String, String>()
    val errorWindow = ConcurrentHashMap<String, MutableList<Long>>()

    fun rrNext(key: String, n: Int, dryRun: Boolean): Int {
        if (n <= 0) return 0
        val cur = (rr[key] ?: 0) % n
        if (!dryRun) rr[key] = (cur + 1) % n
        return cur
    }

    fun noteError(pid: String) {
        val w = errorWindow.getOrPut(pid) { mutableListOf() }
        synchronized(w) {
            w.add(System.currentTimeMillis())
            if (w.size > 500) w.subList(0, w.size - 500).clear()
        }
    }

    fun errorRate(pid: String, windowMs: Long): Int {
        val now = System.currentTimeMillis()
        val w = errorWindow[pid] ?: return 0
        synchronized(w) {
            w.removeAll { now - it > windowMs }
            return w.size
        }
    }
}

/**
 * 规则引擎：select 纯算子（无副作用、可组合）+ execute 执行算子（有副作用、单发）。
 */
object RuleEngine {

    // ==================== select 纯算子 ====================

    fun opRequire(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        val needTools = a["tools"] as? Boolean ?: false
        val needVision = a["vision"] as? Boolean ?: false
        val minCtx = (a["min_ctx"] as? Number)?.toInt()
        return cs.filter { c ->
            (!needTools || c.m.abilities.contains(ModelAbility.TOOL)) &&
            (!needVision || c.m.abilities.contains(ModelAbility.VISION)) &&
            (minCtx == null || c.m.contextLength >= minCtx)
        }
    }

    fun opBreakerFilter(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        val now = System.currentTimeMillis()
        return cs.filter { c ->
            !(c.p.breaker == BreakerState.OPEN && now < c.p.openUntilMs)
        }
    }

    fun opQuotaFilter(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        val minQ = (a["min_quota"] as? Number)?.toDouble() ?: 0.0
        return cs.filter { it.p.quota > minQ }
    }

    fun opTierFirst(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        val want = runCatching {
            Tier.valueOf((a["tier"] as? String ?: "free").lowercase())
        }.getOrDefault(Tier.free)
        return cs.sortedBy { if (it.p.tier == want) 0 else 1 }
    }

    fun opCheapestFirst(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> =
        cs.sortedBy { if (it.p.tier == Tier.free) 0 else 1 }

    fun opFastestFirst(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        cs.forEach { it.why.add("lat=%.0f".format(it.p.latEwma)) }
        return cs.sortedBy { it.p.latEwma }
    }

    fun opLeastErrors(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        cs.forEach { it.why.add("err=${it.p.errors}") }
        return cs.sortedBy { it.p.errors }
    }

    fun opLru(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        cs.forEach { it.why.add("last_used=${it.p.lastUsedMs}") }
        return cs.sortedBy { it.p.lastUsedMs }
    }

    fun opRoundRobin(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        if (cs.isEmpty()) return cs
        val key = (a["key"] as? String) ?: ctx.ruleKey.ifEmpty { "default" }
        val ordered = cs.sortedBy { it.p.id }
        val start = RuntimeState.rrNext(key, ordered.size, ctx.dryRun)
        val rot = ordered.drop(start) + ordered.take(start)
        rot.forEach { it.why.add("rr_start=$start") }
        return rot
    }

    fun opWeightedRr(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        if (cs.isEmpty()) return cs
        val total = cs.sumOf { if (it.p.weight > 0) it.p.weight else 0.0 }.let { if (it <= 0) 1.0 else it }
        var r = Random.nextDouble() * total
        for (c in cs) {
            r -= if (c.p.weight > 0) c.p.weight else 0.0
            if (r <= 0) {
                c.why.add("weighted_pick")
                return listOf(c) + cs.filter { it !== c }
            }
        }
        return cs
    }

    fun opRandom(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        if (cs.isEmpty()) return cs
        val pick = cs.random()
        pick.why.add("random_pick")
        return listOf(pick) + cs.filter { it !== pick }
    }

    fun opStickySession(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        if (ctx.sessionId.isEmpty()) return cs
        val pid = RuntimeState.prefixMap["session:" + ctx.sessionId]
        if (pid != null) {
            val hit = cs.filter { it.p.id == pid }
            if (hit.isNotEmpty()) {
                hit[0].why.add("sticky_hit")
                return hit + cs.filter { it.p.id != pid }
            }
        } else if (cs.isNotEmpty() && !ctx.dryRun) {
            RuntimeState.prefixMap["session:" + ctx.sessionId] = cs[0].p.id
        }
        return cs
    }

    fun opStickyPrefix(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        val pfx = (a["prefix"] as? String) ?: ctx.promptPrefix
        if (pfx.isEmpty() || cs.isEmpty()) return cs
        val h = abs(pfx.hashCode()).toString()
        val pid = RuntimeState.prefixMap[h]
        if (pid == null) {
            val pick = cs[0]
            if (!ctx.dryRun) RuntimeState.prefixMap[h] = pick.p.id
            pick.why.add("prefix_new_bind")
            return listOf(pick) + cs.filter { it !== pick }
        }
        val hit = cs.filter { it.p.id == pid }
        if (hit.isNotEmpty()) {
            hit[0].why.add("prefix_hit")
            return hit + cs.filter { it.p.id != pid }
        }
        return cs
    }

    fun opAbSplit(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        if (cs.isEmpty()) return cs
        val key = (a["key"] as? String) ?: ctx.sessionId
        if (key.isEmpty()) return cs
        val buckets = (a["buckets"] as? Number)?.toInt() ?: 2
        val idx = abs(key.hashCode()) % buckets.coerceAtLeast(1)
        val pick = cs[idx % cs.size]
        pick.why.add("ab_bucket=$idx")
        return listOf(pick) + cs.filter { it !== pick }
    }

    fun opCanary(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        if (cs.isEmpty()) return cs
        val key = (a["key"] as? String) ?: ctx.sessionId
        if (key.isEmpty()) return cs
        val pct = (a["percent"] as? Number)?.toInt() ?: 10
        if (abs(key.hashCode()) % 100 < pct) {
            cs[0].why.add("canary_hit")
            return listOf(cs[0]) + cs.drop(1)
        }
        return cs
    }

    fun opErrorRate(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        val maxErr = (a["max_errors"] as? Number)?.toInt() ?: 5
        val winS = (a["window_s"] as? Number)?.toDouble() ?: 300.0
        return cs.filter { c ->
            val n = RuntimeState.errorRate(c.p.id, (winS * 1000).toLong())
            c.why.add("win_err=$n")
            n < maxErr
        }
    }

    fun opBudgetCap(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        val maxSpend = (a["max_spend"] as? Number)?.toDouble() ?: return cs
        return cs.filter {
            if (it.p.spendToday < maxSpend) true
            else { it.why.add("budget_exceeded"); false }
        }
    }

    fun opTake(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        val n = (a["n"] as? Number)?.toInt() ?: 1
        return cs.take(n.coerceAtLeast(1))
    }

    /** L1 受限表达式过滤：只支持极简比较，避免引入脚本引擎 */
    fun opWhere(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        val expr = a["expr"] as? String ?: return cs
        return cs.filter { c ->
            val env = mapOf(
                "lat" to c.p.latEwma,
                "quota" to c.p.quota,
                "tier" to c.p.tier.name,
                "weight" to c.p.weight,
                "errors" to c.p.errors.toDouble(),
                "ctx" to c.m.contextLength.toDouble(),
                "est_tokens" to ctx.estTokens.toDouble(),
                "tool_ok" to c.m.abilities.contains(ModelAbility.TOOL),
                "vision_ok" to c.m.abilities.contains(ModelAbility.VISION),
                "reason_ok" to c.m.abilities.contains(ModelAbility.REASONING),
                "headroom" to ((c.m.contextLength - ctx.estTokens).toDouble() / c.m.contextLength.coerceAtLeast(1))
            )
            val ok = SafeExpr.eval(expr, env)
            if (ok) c.why.add("where(${expr.take(24)})")
            ok
        }
    }

    val SELECT_OPS: Map<String, (List<Cand>, RequestCtx, Map<String, Any?>) -> List<Cand>> = mapOf(
        "require" to ::opRequire,
        "breaker_filter" to ::opBreakerFilter,
        "quota_filter" to ::opQuotaFilter,
        "tier_first" to ::opTierFirst,
        "cheapest_first" to ::opCheapestFirst,
        "fastest_first" to ::opFastestFirst,
        "least_errors" to ::opLeastErrors,
        "lru" to ::opLru,
        "round_robin" to ::opRoundRobin,
        "weighted_rr" to ::opWeightedRr,
        "random" to ::opRandom,
        "sticky_session" to ::opStickySession,
        "sticky_prefix" to ::opStickyPrefix,
        "ab_split" to ::opAbSplit,
        "canary" to ::opCanary,
        "error_rate" to ::opErrorRate,
        "budget_cap" to ::opBudgetCap,
        "take" to ::opTake,
        "where" to ::opWhere
    )

    // ==================== 编排 ====================

    fun buildCands(providers: List<Provider>, ctx: RequestCtx): List<Cand> =
        providers.filter { it.enabled }
            .mapNotNull { p -> p.models[ctx.modelId]?.let { Cand(p, it) } }

    fun applySelect(cands: List<Cand>, ctx: RequestCtx, steps: List<OpCall>): List<Cand> {
        var cur = cands
        for (step in steps) {
            val fn = SELECT_OPS[step.op]
                ?: error("unknown select operator: ${step.op}; known=${SELECT_OPS.keys.sorted()}")
            cur = fn(cur, ctx, step.args)
        }
        return cur
    }

    /** 决策链解释：只读，不推进轮转/绑定状态 */
    fun explain(rule: RuleSpec, providers: List<Provider>, ctx: RequestCtx): Map<String, Any?> {
        val dry = ctx.copy(dryRun = true, ruleKey = rule.id)
        var cur = buildCands(providers, dry)
        val steps = mutableListOf<Map<String, Any?>>()
        for (step in rule.select) {
            val before = cur.map { it.p.id }
            val fn = SELECT_OPS[step.op]
                ?: return mapOf("error" to "unknown select operator: ${step.op}")
            cur = fn(cur, dry, step.args)
            steps.add(mapOf("op" to step.op, "before" to before, "after" to cur.map { it.p.id }))
        }
        return mapOf(
            "rule" to rule.id,
            "select_trace" to steps,
            "final" to cur.map { it.p.id },
            "execute" to rule.execute.op
        )
    }

    fun pct(v: Double): String = "%.0f".format(v)
}
