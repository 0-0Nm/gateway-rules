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
    val ratePerMin: Double = 30.0,   // <=0 表示不限速
    val burst: Int = 3,
    val minIntervalMs: Double = 800.0,
    val jitterMs: Double = 150.0,
    val maxInflight: Int = 2,
    /**
     * 软限流：收到上游 429/限流后整个 provider 休息的时长（秒）。
     * 连续被限流时按 2 倍指数退避，封顶 softRateMaxSec。
     * 0 表示不启用。
     */
    val softRateSec: Double = 600.0,
    /** 软限流休息时长上限（秒） */
    val softRateMaxSec: Double = 7200.0,
) {
    init {
        require(listOf(ratePerMin,minIntervalMs,jitterMs,softRateSec,softRateMaxSec).all { it.isFinite() && it >= 0 }) { "Rate values must be finite and nonnegative" }
        require(softRateMaxSec >= softRateSec || softRateMaxSec == 0.0) { "Invalid soft rate cap" }
        require(ratePerMin >= 0) { "ratePerMin must be >= 0" }
        require(burst >= 1) { "burst must be >= 1" }
        require(minIntervalMs >= 0) { "minIntervalMs must be >= 0" }
        require(jitterMs >= 0) { "jitterMs must be >= 0" }
        require(maxInflight >= 1) { "maxInflight must be >= 1" }
    }

    companion object {
        fun fromJson(m: Map<String, Any?>): RatePolicy = RatePolicy(
            ratePerMin = (m["ratePerMin"] as? Number)?.toDouble() ?: 30.0,
            burst = (m["burst"] as? Number)?.toInt() ?: 3,
            minIntervalMs = (m["minIntervalMs"] as? Number)?.toDouble() ?: 800.0,
            jitterMs = (m["jitterMs"] as? Number)?.toDouble() ?: 150.0,
            maxInflight = (m["maxInflight"] as? Number)?.toInt() ?: 2,
            softRateSec = (m["softRateSec"] as? Number)?.toDouble() ?: 600.0,
            softRateMaxSec = (m["softRateMaxSec"] as? Number)?.toDouble() ?: 7200.0,
        )
    }
}

/** A permit owns one in-flight slot, and remembers the cooldown generation at admission. */
data class Permit(val ok: Boolean, val waitMs: Double, val reason: String, val cooldownGeneration: Long = 0)
data class SoftRestSnapshot(
    val untilMs: Long,
    val remainingMs: Long,
    val streak: Int,
    val generation: Long,
)
private class Bucket(p: RatePolicy) {
    var tokens=p.burst.toDouble()
    var lastRefill=System.nanoTime()
    var lastSend=0L
    var inflight=0
    var softUntilMs=0L
    var softStreak=0
    var cooldownGeneration=0L
}
class RateLimiter(policies: Map<String,RatePolicy> = emptyMap(),private val default:RatePolicy=RatePolicy()) {
    private var policies=policies.toMap()
    private val buckets=HashMap<String,Bucket>()
    @Synchronized fun policyOf(id:String):RatePolicy=policies[id]?:default
    private fun bucket(id:String)=buckets.getOrPut(id){Bucket(policyOf(id))}
    private fun refill(b:Bucket,p:RatePolicy,now:Long=System.nanoTime()) {
        val elapsed=((now-b.lastRefill)/1_000_000_000.0).coerceAtLeast(0.0)
        if(p.ratePerMin>0)b.tokens=min(p.burst.toDouble(),b.tokens+elapsed*p.ratePerMin/60.0)
        b.lastRefill=now
    }
    /** Atomic policy replacement without resetting reservations, debt or cooldowns. */
    @Synchronized fun updatePolicies(next:Map<String,RatePolicy>) {
        val now=System.nanoTime()
        buckets.forEach{(id,b)->
            refill(b,policyOf(id),now)
            next[id]?.let{b.tokens=min(b.tokens,it.burst.toDouble())}
        }
        policies=next.toMap()
    }
    private fun effective(id:String,override:RatePolicy?):RatePolicy {
        val p=policyOf(id);val o=override?:return p
        fun stricter(a:Double,b:Double):Double=when {a==0.0->b;b==0.0->a;else->min(a,b)}
        return p.copy(ratePerMin=stricter(p.ratePerMin,o.ratePerMin),burst=min(p.burst,o.burst),
            minIntervalMs=max(p.minIntervalMs,o.minIntervalMs),jitterMs=max(p.jitterMs,o.jitterMs),maxInflight=min(p.maxInflight,o.maxInflight))
    }
    private fun inspect(b:Bucket,p:RatePolicy,tokens:Double,nowNs:Long):Permit {
        val remain=b.softUntilMs-System.currentTimeMillis()
        if(remain>0)return Permit(false,remain.toDouble(),"soft_rest",b.cooldownGeneration)
        if(b.inflight>=p.maxInflight)return Permit(false,0.0,"max_inflight",b.cooldownGeneration)
        var wait=0.0
        if(p.ratePerMin>0 && tokens<1.0)wait=(1.0-tokens)/(p.ratePerMin/60.0)*1000.0
        if(b.lastSend!=0L)wait=max(wait,p.minIntervalMs-(nowNs-b.lastSend)/1_000_000.0)
        return Permit(wait<=0.0,wait.coerceAtLeast(0.0),if(wait>0)"rate_limited" else "ok",b.cooldownGeneration)
    }
    @Synchronized fun peek(id:String):Map<String,Any?> {
        val p=policyOf(id);val b=buckets[id]
        if(b==null)return mapOf("provider" to id,"tokens" to p.burst.toDouble(),"inflight" to 0,"can_send" to true,"wait_ms" to 0.0)
        val now=System.nanoTime()
        val tokens=if(p.ratePerMin>0)min(p.burst.toDouble(),b.tokens+((now-b.lastRefill)/1_000_000_000.0).coerceAtLeast(0.0)*p.ratePerMin/60.0) else b.tokens
        val permit=inspect(b,p,tokens,now)
        return mapOf("provider" to id,"tokens" to tokens,"inflight" to b.inflight,"can_send" to permit.ok,"wait_ms" to permit.waitMs,"reason" to permit.reason)
    }
    @Synchronized fun acquire(id:String,override:RatePolicy?=null):Permit {
        // Removed/disabled providers cannot continue producing new attempts after a reload.
        if(id !in policies)return Permit(false,0.0,"provider_removed")
        val p=effective(id,override);val b=bucket(id);refill(b,p)
        b.tokens=min(b.tokens,p.burst.toDouble())
        val permit=inspect(b,p,b.tokens,System.nanoTime());if(!permit.ok)return permit
        if(p.ratePerMin>0)b.tokens=max(0.0,b.tokens-1.0)
        val jitter=if(p.jitterMs>0)Random.nextDouble(0.0,p.jitterMs)else 0.0
        b.lastSend=System.nanoTime()+(jitter*1_000_000.0).toLong();b.inflight++
        return Permit(true,jitter,"ok",b.cooldownGeneration)
    }
    @Synchronized fun release(id:String){buckets[id]?.let{if(it.inflight>0)it.inflight--}}
    @Synchronized fun noteSoftRateLimit(id:String):Double {
        val p=policyOf(id);if(p.softRateSec<=0)return 0.0
        val b=bucket(id);b.softStreak=(b.softStreak+1).coerceAtMost(63);b.cooldownGeneration++
        val cap=if(p.softRateMaxSec>0)p.softRateMaxSec else p.softRateSec
        val sec=min(p.softRateSec*Math.pow(2.0,(b.softStreak-1).toDouble()),cap)
        b.softUntilMs=System.currentTimeMillis()+(sec*1000).toLong();return sec
    }
    @Synchronized fun noteSuccess(id:String,generation:Long?=null) {
        val b=buckets[id]?:return
        // An older successful request must not cancel a newer request's 429 cooldown.
        if(generation!=null && generation!=b.cooldownGeneration)return
        if(b.softUntilMs>System.currentTimeMillis())return
        b.softStreak=0;b.softUntilMs=0
    }
    @Synchronized fun softRestSnapshot(id:String):SoftRestSnapshot {
        val b=buckets[id]?:return SoftRestSnapshot(0L,0L,0,0L)
        val now=System.currentTimeMillis()
        return SoftRestSnapshot(b.softUntilMs,(b.softUntilMs-now).coerceAtLeast(0),b.softStreak,b.cooldownGeneration)
    }
    @Synchronized fun softRestRemainingSec(id:String):Long =
        ((buckets[id]?.softUntilMs?:0L)-System.currentTimeMillis()).coerceAtLeast(0)/1000
}
