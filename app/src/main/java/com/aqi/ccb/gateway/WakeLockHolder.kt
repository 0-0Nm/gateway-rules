package com.aqi.ccb.gateway

import android.content.Context
import android.os.PowerManager

/**
 * 共享 WakeLock —— 引用计数。
 *
 * 为什么需要：
 *   McpService 和 GatewayService 原来各持一把 PARTIAL_WAKE_LOCK
 *   （名字分别是 McpWakeLock / GwWakeLock），都 acquire(24小时)。
 *   两把同时持有 = 双倍待机耗电，而系统只需要其中任意一把就足以
 *   防止 CPU 休眠。多出来的那把纯属浪费。
 *
 * 做法：
 *   进程内只创建一把物理锁，两个服务通过 acquire/release 增减计数，
 *   计数归零时才真正释放。这样：
 *     - 任一服务存活 -> 锁保持（保持常驻能力）
 *     - 两个都退出   -> 锁释放（不残留耗电）
 *
 * 注意：
 *   这是进程内单例。进程被杀时 WakeLock 会被系统自动回收，
 *   所以不存在"进程死了锁还在"的问题。
 *   使用 applicationContext，不持有 Service 引用，避免泄漏。
 */
object WakeLockHolder {

    private const val TAG = "GatewayRules::SharedWakeLock"
    /** 单把锁最长持有 24 小时，到期由系统回收，服务重启会重新申请 */
    private const val TIMEOUT_MS = 24L * 60 * 60 * 1000

    private var lock: PowerManager.WakeLock? = null
    private var refCount = 0

    /**
     * 当前持有者集合。
     *
     * 为什么必须记名字：单纯整数计数会出现「谁都能减别人」的问题。
     * 实测踩过：gw 先 acquire（ref=1），随后 mcp 服务被 stop 时调用
     * release("mcp")——但 mcp 从未 acquire 过——计数被减到 0，
     * 物理锁被释放，导致仍在运行的 gw 失去锁保护。
     * 用集合记录谁真正持有，release 只认自己那份。
     */
    private val holders = HashSet<String>()

    /** 增加引用；首次调用时真正 acquire */
    @Synchronized
    fun acquire(ctx: Context, who: String) {
        if (!holders.add(who)) {
            // 同一持有者重复 acquire，不重复计数
            GateLog.info("wake", "wake lock already held by " + who +
                " ref=" + refCount)
            return
        }
        refCount = holders.size
        if (lock == null) {
            try {
                val pm = ctx.applicationContext
                    .getSystemService(Context.POWER_SERVICE) as PowerManager
                lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply {
                    setReferenceCounted(false)
                    acquire(TIMEOUT_MS)
                }
                GateLog.info("wake", "wake lock acquired by " + who +
                    " ref=" + refCount)
            } catch (e: Exception) {
                GateLog.warn("wake", "wake lock failed: " + e.message)
                lock = null
                holders.remove(who)
                refCount = holders.size
            }
        } else {
            GateLog.info("wake", "wake lock shared by " + who + " ref=" + refCount)
        }
    }

    /** 减少引用；仅当自己确实持有时才减，归零才真正 release */
    @Synchronized
    fun release(who: String) {
        if (!holders.remove(who)) {
            // 没持有过（如服务启动失败就 onDestroy），不碰别人的计数
            GateLog.info("wake", "release ignored by " + who +
                " (not a holder) ref=" + refCount)
            return
        }
        refCount = holders.size
        if (refCount <= 0) {
            try {
                lock?.let { if (it.isHeld) it.release() }
            } catch (_: Exception) {}
            if (lock != null) {
                GateLog.info("wake", "wake lock released (last one: " + who + ")")
            }
            lock = null
        } else {
            GateLog.info("wake", "wake lock held by " + holders.joinToString(",") +
                ", released by " + who + " ref=" + refCount)
        }
    }

    /** 当前引用计数（供 diag 观察） */
    @Synchronized
    fun refCountPublic(): Int = refCount

    /** 物理锁是否真的持有 */
    @Synchronized
    fun isHeldPublic(): Boolean = lock?.isHeld == true
}
