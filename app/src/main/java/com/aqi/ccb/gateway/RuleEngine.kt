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

    /**
     * 配额过滤。
     *
     * ⚠ 当前无数据源：Provider.quota 默认 1e9 且没有任何地方从上游余额更新，
     * 所以这个算子实际上恒通过。保留是为了配置兼容，但每次执行都会记警告，
     * 避免用户以为它真的在拦。
     */
    fun opQuotaFilter(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        val minQ = (a["min_quota"] as? Number)?.toDouble() ?: 0.0
        val passed = cs.filter { it.p.quota > minQ }
        if (!ctx.dryRun && passed.size == cs.size && minQ > 0) {
            GateLog.warn(
                "rule",
                "quota_filter 未生效：无余额数据源，quota 恒为默认值，" +
                    "本次全部通过（min_quota=" + minQ + "）"
            )
        }
        return passed
    }

    /**
     * 标签优先 —— 按用户给 provider 手动打的 free/paid 标签排序。
     *
     * ⚠ 这不是价格排序。标签是二值且 provider 级的：
     *   - 同一 provider 下 9 个模型价格差几十倍也无法区分
     *   - 标签由用户在提供商页选择，与真实账单无关
     * 要按真实价格排序请用 cheapest_first（需先为模型填单价）。
     */
    fun opTierFirst(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        val want = runCatching {
            Tier.valueOf((a["tier"] as? String ?: "free").lowercase())
        }.getOrDefault(Tier.free)
        val matched = cs.count { it.p.tier == want }
        if (!ctx.dryRun && (matched == 0 || matched == cs.size)) {
            GateLog.info(
                "rule",
                "tier_first 无区分度：" + matched + "/" + cs.size +
                    " 个候选是 " + want + " 标签，排序等于原序" +
                    "（这是手动标签不是价格，按价格排序请用 cheapest_first）"
            )
        }
        return cs.sortedBy { if (it.p.tier == want) 0 else 1 }
    }

    /**
     * 最省优先 —— 按真实单价排序。
     *
     * 原实现是 `sortedBy { if (tier == free) 0 else 1 }`，
     * 与 tier_first 完全相同，跟「省」没有任何关系，属于命名欺骗。
     * 现在改为按 Model.blendedPrice（输入+输出单价的均值）升序。
     *
     * 排序规则：
     *   1 填了价格的按价格升序
     *   2 没填价格的排最后（不能因为没填就说它便宜）
     *   3 全部没填价 -> 保持原序并记 WARN，不假装排序
     */
    fun opCheapestFirst(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        val priced = cs.filter { it.m.hasPrice }
        if (priced.isEmpty()) {
            if (!ctx.dryRun) {
                GateLog.warn(
                    "rule",
                    "cheapest_first 无价格数据：所有候选模型都没填单价，" +
                        "保持原序（请在提供商页为模型填写输入/输出价格）"
                )
            }
            return cs
        }
        // 有价的优先且按价升序，无价的垫底并保持原相对顺序
        val withPrice = cs.filter { it.m.hasPrice }
            .sortedBy { it.m.blendedPrice }
        val withoutPrice = cs.filter { !it.m.hasPrice }
        withPrice.forEach { it.why.add("price=%.4f".format(it.m.blendedPrice)) }
        withoutPrice.forEach { it.why.add("no_price") }
        return withPrice + withoutPrice
    }

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

    /**
     * 预算上限。
     *
     * ⚠ 当前无数据源：Provider.spendToday 恒为 0，从未累加。
     * 网关是字节级透传，不解析响应体，拿不到 usage；
     * 即使拿到也需要定价表才能折算成金额。
     * 保留配置兼容，但执行时记警告。
     */
    fun opBudgetCap(cs: List<Cand>, ctx: RequestCtx, a: Map<String, Any?>): List<Cand> {
        val maxSpend = (a["max_spend"] as? Number)?.toDouble() ?: return cs
        val passed = cs.filter {
            if (it.p.spendToday < maxSpend) true
            else { it.why.add("budget_exceeded"); false }
        }
        if (!ctx.dryRun && passed.size == cs.size) {
            GateLog.warn(
                "rule",
                "budget_cap 未生效：spendToday 无累加数据源，本次全部通过" +
                    "（max_spend=" + maxSpend + "）"
            )
        }
        return passed
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

    /**
     * 按请求的 modelId 构建候选。
     *
     * 两道闸：provider.enabled 且 model.enabled。
     * 原来只过滤 provider —— Model.fromJson 压根不读 enabled，
     * 用户在初次筛选里取消勾选的模型照样会被选中转发。
     */
    fun buildCands(providers: List<Provider>, ctx: RequestCtx): List<Cand> =
        providers.filter { it.enabled }
            .mapNotNull { p ->
                p.models[ctx.modelId]
                    ?.takeIf { it.enabled }
                    ?.let { Cand(p, it) }
            }

    /**
     * 「全排序」算子：会重新排列整个候选列表。
     *
     * 为什么单独标记：这些算子互相覆盖 ——
     *   fastest_first 按延迟排完，tier_first 又整个按 tier 重排，
     *   前一次排序完全失效。用户勾了 3 个排序算子，实际只有最后 1 个起作用，
     *   属于静默忽略配置（和之前 execute.op 从不被读取是同一类问题）。
     *
     * 注意 round_robin / weighted_rr / random 不在此列：
     *   它们是「挑一个提到首位」，可以叠在排序之后作为二次选择，
     *   语义上是「先筛出前 N 个，再在其中随机」，有实际意义。
     */
    val FULL_SORT_OPS = setOf(
        "tier_first", "cheapest_first", "fastest_first", "least_errors", "lru",
    )

    /**
     * 编排 select 链。
     *
     * 对「全排序」算子做互斥处理：只让链中第一个生效，其余跳过并记警告。
     * 这样既保证行为可预期，又能让用户在日志里看到自己配错了。
     */
    fun applySelect(cands: List<Cand>, ctx: RequestCtx, steps: List<OpCall>): List<Cand> {
        var cur = cands
        var sortApplied: String? = null
        val skipped = mutableListOf<String>()

        for (step in steps) {
            if (step.op in FULL_SORT_OPS) {
                if (sortApplied != null) {
                    // 已有排序生效，本次跳过
                    skipped.add(step.op)
                    continue
                }
                sortApplied = step.op
            }
            val fn = SELECT_OPS[step.op]
                ?: error("unknown select operator: ${step.op}; known=${SELECT_OPS.keys.sorted()}")
            cur = fn(cur, ctx, step.args)
        }

        if (skipped.isNotEmpty()) {
            GateLog.warn(
                "rule",
                "排序算子互斥：已用 " + sortApplied + "，忽略 " +
                    skipped.joinToString(",") + "（同一条规则里多个排序只有第一个生效）"
            )
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
