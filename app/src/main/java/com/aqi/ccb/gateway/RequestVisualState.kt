package com.aqi.ccb.gateway

import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.json.JSONArray

/** Per-GatewayServer observation only; never decides routing, retries or HTTP success. */
class RequestVisualState(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onChanged: () -> Unit = {},
    private val redHoldMs: Long = 3_000L,
    private val greenHoldMs: Long = 3_000L,
    private val maxTracked: Int = 1024,
    private val maxFaultScopes: Int = 256,
) {
    init { require(redHoldMs in 1..60_000 && greenHoldMs in 1..60_000 && maxTracked > 0 && maxFaultScopes > 0) }
    enum class Phase(val label: String, val argb: Int) {
        IDLE("待命", 0xFF1E88E5.toInt()), BUSY("生成", 0xFFFF9800.toInt()),
        SUCCESS("完成", 0xFF43A047.toInt()), ERROR("中断", 0xFFE53935.toInt())
    }
    enum class Outcome { SUCCESS, FAILURE, CANCELLED, LOCAL_REJECT }
    class Ticket internal constructor(
        internal val owner: RequestVisualState, internal val generation: Long,
        internal val id: Long, internal val scope: String, internal val tracked: Boolean,
    ) { internal val ended = AtomicBoolean(false) }
    private data class Active(val ticket: Ticket, var progressed: Boolean = false)
    private data class Alarm(val epoch: Long, val redUntil: Long, var recovered: Boolean = false)
    data class Snapshot(
        val phase: Phase, val activeLogicalRequests: Int, val unresolvedFaultScopes: Int,
        val recoveredWaitingScopes: Int, val nextTransitionMs: Long, val redRemainingMs: Long,
        val greenRemainingMs: Long, val faultEpoch: Long, val observationLimited: Boolean,
        val overflowFaultLatched: Boolean, val progressEvidenceLimited: Long, val callbackFailures: Long,
        val faultScopeSample: List<String>,
    ) {
        fun asMap(): Map<String, Any> = mapOf(
            "phase" to phase.name, "label" to phase.label, "argb" to phase.argb,
            "activeLogicalRequests" to activeLogicalRequests, "unresolvedFaultScopes" to unresolvedFaultScopes,
            "recoveredWaitingScopes" to recoveredWaitingScopes, "nextTransitionMs" to nextTransitionMs,
            "redRemainingMs" to redRemainingMs, "greenRemainingMs" to greenRemainingMs,
            "faultEpoch" to faultEpoch, "observationLimited" to observationLimited,
            "overflowFaultLatched" to overflowFaultLatched, "progressEvidenceLimited" to progressEvidenceLimited,
            "callbackFailures" to callbackFailures, "faultScopeSample" to faultScopeSample,
        )
    }
    private var generation = 0L
    private var seq = 0L
    private var epoch = 0L
    private var lastNow = Long.MIN_VALUE
    private val active = mutableMapOf<Long, Active>()
    private val alarms = linkedMapOf<String, Alarm>()
    private val greens = linkedMapOf<String, Long>()
    private var overflowActive = 0
    private var limited = false
    private var overflowFault = false
    private var evidenceLimited = 0L
    private var callbackErrors = 0L
    private fun now(): Long = clockMs().also { if (it > lastNow) lastNow = it }.let { lastNow }
    private fun until(now: Long, hold: Long): Long = if (now > Long.MAX_VALUE - hold) Long.MAX_VALUE else now + hold
    private fun remaining(deadline: Long, now: Long): Long = if (deadline <= now) 0 else (deadline - now).coerceAtLeast(0)
    private fun prune(t: Long) {
        alarms.entries.removeAll { it.value.recovered && it.value.redUntil <= t }
        greens.entries.removeAll { it.value <= t }
    }
    private fun notifyChanged() {
        try { onChanged() } catch (_: Exception) { synchronized(this) { callbackErrors++ } }
    }
    fun begin(requestId: String, scope: String): Ticket {
        // requestId stays in the gateway trace; the visual observer stores no prompts, keys or raw bodies.
        require(requestId.isNotEmpty() && scope.isNotBlank())
        val ticket = synchronized(this) {
            val t = now(); prune(t); seq++
            val tracked = active.size < maxTracked
            Ticket(this, generation, seq, scope, tracked).also {
                if (tracked) active[it.id] = Active(it) else { overflowActive++; limited = true }
            }
        }
        notifyChanged(); return ticket
    }
    fun progress(ticket: Ticket?) {
        if (ticket == null) return
        val changed = synchronized(this) {
            if (ticket.owner !== this || ticket.generation != generation || ticket.ended.get()) return@synchronized false
            val record = active[ticket.id] ?: return@synchronized false
            if (record.ticket !== ticket) return@synchronized false
            record.progressed = true
            val alarm = alarms[ticket.scope]
            if (alarm != null && !alarm.recovered) { alarm.recovered = true; true } else false
        }
        if (changed) notifyChanged()
    }
    fun evidenceUnavailable(ticket: Ticket?) {
        if (ticket == null) return
        synchronized(this) {
            if (ticket.owner === this && ticket.generation == generation && !ticket.ended.get()) evidenceLimited++
        }
    }
    fun finish(ticket: Ticket?, outcome: Outcome) {
        if (ticket == null) return
        val changed = synchronized(this) {
            if (ticket.owner !== this || ticket.generation != generation || !ticket.ended.compareAndSet(false, true)) return@synchronized false
            val t = now()
            if (!ticket.tracked) {
                overflowActive = (overflowActive - 1).coerceAtLeast(0)
                if (outcome == Outcome.FAILURE) overflowFault = true
                return@synchronized true
            }
            val record = active.remove(ticket.id) ?: return@synchronized false
            if (record.ticket !== ticket) return@synchronized false
            when (outcome) {
                Outcome.FAILURE -> {
                    epoch++
                    if (ticket.scope in alarms || alarms.size < maxFaultScopes) alarms[ticket.scope] = Alarm(epoch, until(t, redHoldMs))
                    else { limited = true; overflowFault = true }
                    greens.remove(ticket.scope)
                }
                Outcome.SUCCESS -> if (record.progressed) {
                    val alarm = alarms[ticket.scope]
                    val start = if (alarm?.recovered == true) maxOf(t, alarm.redUntil) else t
                    greens[ticket.scope] = until(start, greenHoldMs)
                    if (greens.size > maxFaultScopes) greens.remove(greens.keys.first())
                }
                Outcome.CANCELLED, Outcome.LOCAL_REJECT -> Unit // neither new failure nor successful generation
            }
            prune(t); true
        }
        if (changed) notifyChanged()
    }
    @Synchronized fun snapshot(scope: String? = null): Snapshot {
        val t = now(); prune(t)
        val relevant = if (scope == null) alarms.values.toList() else listOfNotNull(alarms[scope])
        val busy = if (scope == null) active.size + overflowActive else active.values.count { it.ticket.scope == scope }
        val green = if (scope == null) greens.values.maxOrNull() ?: t else greens[scope] ?: t
        val red = relevant.maxOfOrNull { remaining(it.redUntil, t) } ?: 0
        val phase = when {
            overflowFault || relevant.isNotEmpty() -> Phase.ERROR
            busy > 0 -> Phase.BUSY
            green > t -> Phase.SUCCESS
            else -> Phase.IDLE
        }
        val nextRed = relevant.filter { it.recovered }.map { remaining(it.redUntil, t) }.filter { it > 0 }.minOrNull() ?: 0
        val next = if (phase == Phase.SUCCESS) remaining(green, t) else nextRed
        return Snapshot(phase, busy, relevant.count { !it.recovered }, relevant.count { it.recovered }, next,
            red, remaining(green, t), epoch, limited, overflowFault, evidenceLimited, callbackErrors,
            if (scope == null) alarms.keys.take(16) else if (scope in alarms) listOf(scope) else emptyList())
    }
    fun reset() {
        synchronized(this) {
            generation++; active.clear(); alarms.clear(); greens.clear(); overflowActive = 0
            limited = false; overflowFault = false; evidenceLimited = 0; epoch = 0
        }
        notifyChanged()
    }
}

/** Recognize observed OpenAI-compatible generation, not mere HTTP200, role/usage/ping or DONE. */
object GenerationProgress {
    const val MAX_NON_STREAM_BYTES = 256 * 1024
    private fun meaningful(value: Any?): Boolean = value is String && value.isNotBlank()
    private fun function(fn: JSONObject?): Boolean = fn != null && (meaningful(fn.opt("name")) || meaningful(fn.opt("arguments")))
    private fun message(m: JSONObject): Boolean {
        if (m.has("role") && m.opt("role") != "assistant") return false
        if (meaningful(m.opt("content")) || meaningful(m.opt("reasoning_content")) || meaningful(m.opt("reasoning"))) return true
        val parts = m.optJSONArray("content")
        if (parts != null && (0 until parts.length()).any { i ->
            val p = parts.optJSONObject(i)
            p != null && ((p.opt("type") in setOf("text", "output_text") && meaningful(p.opt("text"))) ||
                (p.opt("type") == "image_url" && (meaningful(p.opt("image_url")) || meaningful(p.optJSONObject("image_url")?.opt("url")))))
        }) return true
        if (function(m.optJSONObject("function_call"))) return true
        val calls = m.optJSONArray("tool_calls") ?: return false
        return (0 until calls.length()).any { function(calls.optJSONObject(it)?.optJSONObject("function")) }
    }
    fun event(json: JSONObject, streaming: Boolean): Boolean {
        if (json.has("error") && !json.isNull("error")) return false
        val choices = json.optJSONArray("choices") ?: return false
        return (0 until choices.length()).any { i ->
            val m = choices.optJSONObject(i)?.optJSONObject(if (streaming) "delta" else "message")
            m != null && message(m)
        }
    }
    fun response(bytes: ByteArray): Boolean = try {
        val tokener = org.json.JSONTokener(bytes.toString(Charsets.UTF_8))
        val value = tokener.nextValue()
        value is JSONObject && tokener.nextClean() == 0.toChar() && event(value, false)
    } catch (_: Exception) { false }
}
