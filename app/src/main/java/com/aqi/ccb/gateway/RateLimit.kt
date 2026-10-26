package com.aqi.ccb.gateway

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 上游发送频率准入层（防风控）。
 *
 * 四层闸：
 *  1 令牌桶  ratePerMin + burst      平均速率与突发
 *  2 最小间隔 minIntervalMs          防连发（风控最敏感）
 *  3 抖动    jitterMs                防固定节奏被识别为机器人
 *  4 并发上限 maxInflight            同时进行中的请求数
 *
 * 设计约束：扣令牌是副作用，因此不进 select 纯算子，只做准入层。
 * peek() 为只读快照，供 explain 使用，不消耗令牌。
 */
data class RatePolicy(
    val ratePerMin: Double = 60.0,   // <=0 表示不限速
    val burst: Int = 5,
    val minIntervalMs: Double = 0.0,
    val jitterMs: Double = 0.0,
    val maxInflight: Int = 4
) {
    init {
        require(ratePerMin >= 0) { "ratePerMin must be >= 0" }
        require(burst >= 1) { "burst must be >= 1" }
        require(minIntervalMs >= 0) { "minIntervalMs must be >= 0" }
        require(jitterMs >= 0) { "jitterMs must be >= 0" }
        require(maxInflight >= 1) { "maxInflight must be >= 1" }
    }

    companion object {
        fun fromJson(m: Map<String, Any?>): RatePolicy = RatePolicy(
            ratePerMin = (m["ratePerMin"] as? Number)?.toDouble() ?: 60.0,
            burst = (m["burst"] as? Number)?.toInt() ?: 5,
            minIntervalMs = (m["minIntervalMs"] as? Number)?.toDouble() ?: 0.0,
            jitterMs = (m["jitterMs"] as? Number)?.toDouble() ?: 0.0,
            maxInflight = (m["maxInflight"] as? Number)?.toInt() ?: 4
        )
    }
}

/** 准入结果：ok=是否可发；waitMs=建议等待；reason=原因 */
data class Permit(val ok: Boolean, val waitMs: Double, val reason: String)

private class Bucket(p: RatePolicy) {
    var tokens: Double = p.burst.toDouble()
    var lastRefill: Long = System.nanoTime()
    var lastSend: Long = 0L
    var inflight: Int = 0
}

class RateLimiter(
    private val policies: Map<String, RatePolicy> = emptyMap(),
    private val default: RatePolicy = RatePolicy()
) {
    private val buckets = HashMap<String, Bucket>()

    fun policyOf(providerId: String): RatePolicy = policies[providerId] ?: default

    @Synchronized
    private fun bucketOf(providerId: String): Bucket =
        buckets.getOrPut(providerId) { Bucket(policyOf(providerId)) }

    private fun refill(b: Bucket, p: RatePolicy) {
        if (p.ratePerMin <= 0) return
        val now = System.nanoTime()
        val dtSec = (now - b.lastRefill) / 1_000_000_000.0
        if (dtSec > 0) {
            val rate = p.ratePerMin / 60.0
            b.tokens = min(p.burst.toDouble(), b.tokens + dtSec * rate)
            b.lastRefill = now
        }
    }

    /** 只读快照，不消耗令牌。 */
    @Synchronized
    fun peek(providerId: String): Map<String, Any?> {
        val p = policyOf(providerId)
        val b = buckets[providerId]
        if (b == null) {
            return mapOf("provider" to providerId, "tokens" to p.burst.toDouble(),
                "inflight" to 0, "can_send" to true, "wait_ms" to 0.0)
        }
        var tokens = b.tokens
        if (p.ratePerMin > 0) {
            val dtSec = (System.nanoTime() - b.lastRefill) / 1_000_000_000.0
            tokens = min(p.burst.toDouble(), b.tokens + dtSec * (p.ratePerMin / 60.0))
        }
        val tokensOk = p.ratePerMin <= 0 || tokens >= 1.0
        val inflightOk = b.inflight < p.maxInflight
        var waitMs = 0.0
        var gapOk = true
        if (p.minIntervalMs > 0 && b.lastSend > 0) {
            val elapsedMs = (System.nanoTime() - b.lastSend) / 1_000_000.0
            if (elapsedMs < p.minIntervalMs) {
                gapOk = false
                waitMs = p.minIntervalMs - elapsedMs
            }
        }
        return mapOf(
            "provider" to providerId, "tokens" to tokens, "inflight" to b.inflight,
            "can_send" to (tokensOk && inflightOk && gapOk), "wait_ms" to waitMs
        )
    }

    /**
     * 尝试取得发送许可。
     * 不阻塞：返回 ok=false 时由调用方决定是等待重试还是换路。
     */
    @Synchronized
    fun acquire(providerId: String): Permit {
        val p = policyOf(providerId)
        val b = bucketOf(providerId)
        refill(b, p)

        if (b.inflight >= p.maxInflight) {
            return Permit(false, 0.0, "max_inflight(${p.maxInflight})")
        }

        var needWaitMs = 0.0
        if (p.ratePerMin > 0 && b.tokens < 1.0) {
            val rate = p.ratePerMin / 60.0
            needWaitMs = max(needWaitMs, (1.0 - b.tokens) / rate * 1000.0)
        }
        if (p.minIntervalMs > 0 && b.lastSend > 0) {
            val elapsedMs = (System.nanoTime() - b.lastSend) / 1_000_000.0
            if (elapsedMs < p.minIntervalMs) {
                needWaitMs = max(needWaitMs, p.minIntervalMs - elapsedMs)
            }
        }
        if (needWaitMs > 0.0) {
            return Permit(false, needWaitMs, "rate_limited")
        }

        if (p.ratePerMin > 0) b.tokens = max(0.0, b.tokens - 1.0)
        b.lastSend = System.nanoTime()
        b.inflight += 1

        val jitter = if (p.jitterMs > 0) Random.nextDouble(0.0, p.jitterMs) else 0.0
        return Permit(true, jitter, "ok")
    }

    /** 请求结束后必须归还并发槽。 */
    @Synchronized
    fun release(providerId: String) {
        buckets[providerId]?.let { if (it.inflight > 0) it.inflight -= 1 }
    }
}
