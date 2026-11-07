package com.aqi.ccb.gateway

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID

private const val MAX_USAGE_TOKENS = 1_000_000_000_000L
private fun usageInteger(value: Any?): Long {
    require(value is Number)
    return BigDecimal(value.toString()).longValueExact().also { require(it in 0..MAX_USAGE_TOKENS) }
}
private fun boundedId(value: String): String = value.filter { !it.isISOControl() }.take(256)

data class UsageCounts(val input: Long, val output: Long, val cached: Long? = null, val reasoning: Long? = null) {
    init { require(input in 0..MAX_USAGE_TOKENS && output in 0..MAX_USAGE_TOKENS)
        require(cached == null || cached in 0..input); require(reasoning == null || reasoning in 0..output) }
    fun json(): JSONObject = JSONObject().put("prompt_tokens", input).put("completion_tokens", output)
        .put("cached_tokens", cached ?: JSONObject.NULL).put("reasoning_tokens", reasoning ?: JSONObject.NULL)
}

data class UsageObservation(val status: String, val counts: UsageCounts?)

/** OpenAI-compatible cumulative usage only. A repeated final usage event replaces, never adds. */
class UsageObserver {
    private var counts: UsageCounts? = null
    private var status = "not_reported"
    fun observe(json: JSONObject) {
        try {
            if (!json.has("usage") || json.isNull("usage")) return
            val u = json.getJSONObject("usage")
            val input = usageInteger(u.opt("prompt_tokens")); val output = usageInteger(u.opt("completion_tokens"))
            if (u.has("total_tokens") && !u.isNull("total_tokens"))
                require(usageInteger(u.opt("total_tokens")) == input + output)
            fun detail(parent: String, name: String): Long? {
                if (!u.has(parent) || u.isNull(parent)) return null
                val d = u.getJSONObject(parent)
                return if (!d.has(name) || d.isNull(name)) null else usageInteger(d.opt(name))
            }
            counts = UsageCounts(input, output, detail("prompt_tokens_details", "cached_tokens"),
                detail("completion_tokens_details", "reasoning_tokens"))
            status = "reported"
        } catch (_: Exception) { counts = null; status = "invalid" }
    }
    fun observeBody(bytes: ByteArray) {
        if (bytes.size > BODY_LIMIT) { limit(); return }
        try {
            val t = JSONTokener(String(bytes, Charsets.UTF_8)); val obj = t.nextValue()
            require(obj is JSONObject && t.nextClean() == 0.toChar()); observe(obj)
        } catch (_: Exception) { counts = null; status = "invalid" }
    }
    fun limit() { counts = null; status = "observation_limit" }
    fun finish(complete: Boolean): UsageObservation = UsageObservation(if (complete) status else "incomplete", counts)
    companion object { const val BODY_LIMIT = 256 * 1024 }
}

data class TokenCostEstimate(val currency: String?, val amount: String?, val status: String) {
    fun json(): JSONObject = JSONObject().put("version", 1).put("currency", currency ?: JSONObject.NULL)
        .put("amount", amount ?: JSONObject.NULL).put("status", status)
}

/** v1: standard Chat token tariffs only; deliberately no currency inference or FX conversion. */
object TokenCostEstimator {
    private fun decimal(value: String?): BigDecimal? {
        if (value == null) return null
        require(value.length <= 512)
        val n = BigDecimal(value)
        require(n.signum() >= 0 && n.precision() <= 324 && n.scale() in -324..324)
        require(n <= BigDecimal.valueOf(Double.MAX_VALUE))
        return n
    }
    fun estimate(sent: Boolean, complete: Boolean, httpStatus: Int?, observation: UsageObservation,
                 currency: String?, inputPrice: String?, outputPrice: String?, cachedPrice: String?): TokenCostEstimate {
        val unit = ModelPricing.currency(currency)
        fun unknown(reason: String) = TokenCostEstimate(unit, null, reason)
        if (!sent) return unknown("not_sent")
        val u = observation.counts
        if (!complete || httpStatus == null || httpStatus !in 200..299 || observation.status != "reported" || u == null)
            return unknown("usage_unknown")
        if (unit == null) return unknown("currency_unknown")
        return try {
            val input = decimal(inputPrice) ?: return unknown("price_unknown")
            val output = decimal(outputPrice) ?: return unknown("price_unknown")
            val cache = decimal(cachedPrice)
            val cacheTokens: Long = when {
                u.input == 0L -> 0L
                u.cached != null -> u.cached
                cache != null && cache.compareTo(input) == 0 -> 0L // tariff independent of the unreported split
                else -> return unknown("cache_usage_unknown")
            }
            if (cacheTokens > 0 && cache == null) return unknown("cache_price_unknown")
            val value = input.multiply(BigDecimal.valueOf(u.input - cacheTokens))
                .add((cache ?: BigDecimal.ZERO).multiply(BigDecimal.valueOf(cacheTokens)))
                .add(output.multiply(BigDecimal.valueOf(u.output))) // reasoning is already included in output
                .movePointLeft(6).stripTrailingZeros().toPlainString()
            TokenCostEstimate(unit, value, "estimated")
        } catch (_: Exception) { unknown("invalid_price") }
    }
}

data class CurrencyEstimate(val currency: String, val amount: String, val attempts: Long) {
    fun json(): JSONObject = JSONObject().put("currency", currency).put("amount", amount).put("attempts", attempts)
}

data class UsageAttemptRecord(
    val providerId: String, val modelId: String, val credentialId: String?, val httpStatus: Int?,
    val sent: Boolean, val responseComplete: Boolean, val observation: UsageObservation,
    val priceInput: String?, val priceOutput: String?,
    val currency: String? = null, val priceCachedInput: String? = null, val costEstimate: TokenCostEstimate? = null,
) {
    fun json(): JSONObject = JSONObject().put("providerId", providerId).put("modelId", modelId)
        .put("credentialId", credentialId ?: JSONObject.NULL).put("httpStatus", httpStatus ?: JSONObject.NULL)
        .put("sent", sent).put("responseComplete", responseComplete).put("usageStatus", observation.status)
        .put("usage", observation.counts?.json() ?: JSONObject.NULL)
        .put("priceInputPerM", priceInput ?: JSONObject.NULL).put("priceOutputPerM", priceOutput ?: JSONObject.NULL)
        .put("currency", currency ?: JSONObject.NULL).put("priceCachedInputPerM", priceCachedInput ?: JSONObject.NULL)
        .put("cost", JSONObject.NULL).put("tokenCostEstimate", costEstimate?.json() ?: JSONObject.NULL)
}

class UsageAttemptCapture internal constructor(private val provider: Provider, private val model: Model, credential: String) {
    val observer = UsageObserver()
    private val fingerprint = if (credential.isEmpty()) null else MessageDigest.getInstance("SHA-256")
        .digest((provider.id + "\u0000" + credential).toByteArray(Charsets.UTF_8))
        .take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
    private var record: UsageAttemptRecord? = null
    fun finish(status: Int?, sent: Boolean, complete: Boolean) {
        if (record != null) return
        fun price(v: Double?): String? = v?.takeIf { it.isFinite() && it >= 0 }?.let { ModelPricing.format(it) }
        val usage = if (sent) observer.finish(complete) else UsageObservation("not_sent", null)
        val input = price(model.priceInPerM); val output = price(model.priceOutPerM); val cached = price(model.priceCachedInPerM)
        val currency = ModelPricing.currency(model.priceCurrency)
        record = UsageAttemptRecord(boundedId(provider.id), boundedId(model.modelId), fingerprint, status,
            sent, complete, usage, input, output, currency, cached,
            TokenCostEstimator.estimate(sent, complete, status, usage, currency, input, output, cached))
    }
    internal fun result(): UsageAttemptRecord = record ?: UsageAttemptRecord(boundedId(provider.id), boundedId(model.modelId),
        fingerprint, null, true, false, observer.finish(false), null, null)
}

data class UsageRequestRecord(val id: String, val requestId: String, val timeMs: Long, val outcome: String,
                              val attemptCount: Int, val attempts: List<UsageAttemptRecord>) {
    fun json(): JSONObject = JSONObject().put("schema", 1).put("id", id).put("requestId", requestId)
        .put("timeMs", timeMs).put("outcome", outcome).put("attemptCount", attemptCount)
        .put("omittedAttempts", attemptCount - attempts.size).put("attempts", JSONArray(attempts.map { it.json() }))
}

/** One capture per logical request; bounded independently of the routing candidate count. */
class UsageRequestCapture(private val requestId: String) {
    private val id = UUID.randomUUID().toString()
    private val attempts = ArrayList<UsageAttemptCapture>()
    private var count = 0
    private var closed = false
    fun begin(provider: Provider, model: Model, credential: String): UsageAttemptCapture? {
        if (closed) return null
        if (count < Int.MAX_VALUE) count++
        if (attempts.size >= MAX_ATTEMPTS) return null
        return UsageAttemptCapture(provider, model, credential).also { attempts.add(it) }
    }
    fun finish(outcome: String): UsageRequestRecord? {
        if (closed) return null
        closed = true
        return UsageRequestRecord(id, boundedId(requestId), System.currentTimeMillis(),
            outcome.takeIf { it in OUTCOMES } ?: "upstream_failure", count, attempts.map { it.result() })
    }
    companion object {
        const val MAX_ATTEMPTS = 32
        val OUTCOMES = setOf("success", "upstream_failure", "cancelled", "local_reject")
    }
}

data class UsageRecent(val requestId: String, val outcome: String, val attempts: Int, val timeMs: Long)
data class UsageSummary(
    val requests: Int = 0, val successful: Int = 0, val failed: Int = 0, val cancelled: Int = 0, val localRejected: Int = 0,
    val attempts: Long = 0, val reportedAttempts: Long = 0, val unknownAttempts: Long = 0, val notSentAttempts: Long = 0,
    val inputTokens: Long = 0, val outputTokens: Long = 0, val cachedTokens: Long = 0, val cacheReportedAttempts: Long = 0,
    val reasoningTokens: Long = 0, val reasoningReportedAttempts: Long = 0, val omittedAttempts: Long = 0,
    val retainedLimit: Int = 512, val oldestMs: Long = 0, val storageStatus: String = "ok", val readErrors: Int = 0,
    val unpersisted: Int = 0, val recent: List<UsageRecent> = emptyList(),
    val estimatedCosts: List<CurrencyEstimate> = emptyList(), val estimatedAttempts: Long = 0,
    val unestimatedSentAttempts: Long = 0,
) {
    fun json(): JSONObject = JSONObject().put("scope", "retained_completed_chat_requests").put("requests", requests)
        .put("successful", successful).put("failed", failed).put("cancelled", cancelled).put("localRejected", localRejected)
        .put("attempts", attempts).put("reportedAttempts", reportedAttempts).put("unknownAttempts", unknownAttempts)
        .put("notSentAttempts", notSentAttempts).put("reportedInputTokens", inputTokens).put("reportedOutputTokens", outputTokens)
        .put("reportedCachedTokens", cachedTokens).put("cacheReportedAttempts", cacheReportedAttempts)
        .put("reportedReasoningTokens", reasoningTokens).put("reasoningReportedAttempts", reasoningReportedAttempts)
        .put("omittedAttempts", omittedAttempts).put("retainedLimit", retainedLimit).put("oldestMs", oldestMs)
        .put("storageStatus", storageStatus).put("readErrors", readErrors).put("unpersisted", unpersisted)
        .put("cost", JSONObject.NULL).put("costStatus", "token_estimates_only_not_invoice").put("budgetEnforced", false)
        .put("estimatedCosts", JSONArray(estimatedCosts.map { it.json() })).put("estimatedAttempts", estimatedAttempts)
        .put("unestimatedSentAttempts", unestimatedSentAttempts)
        .put("recent", JSONArray(recent.map { JSONObject().put("requestId", it.requestId).put("outcome", it.outcome)
            .put("attempts", it.attempts).put("timeMs", it.timeMs) }))
}

/** App-private append-only journal with two bounded generations. Not a billing authority. */
class UsageLedger(private val directory: File, private val limit: Int = 512, private val fileLimit: Long = 2L * 1024 * 1024) {
    private data class Stored(val record: UsageRequestRecord, val persisted: Boolean)
    private val retained = ArrayDeque<Stored>()
    private val ids = HashSet<String>()
    private var readErrors = 0
    private var writeError = false
    private var blocked = false
    @Volatile private var summary = UsageSummary(retainedLimit = limit)
    init {
        require(limit in 1..512 && fileLimit in 1024..2L * 1024 * 1024)
        try {
            if (Files.isSymbolicLink(directory.toPath()) || directory.exists() && !directory.isDirectory) throw IllegalStateException()
            for (name in listOf("usage.jsonl.1", "usage.jsonl")) load(File(directory, name))
        } catch (_: Exception) { readErrors++; blocked = true }
        rebuild()
    }
    private fun keep(record: UsageRequestRecord, persisted: Boolean) {
        if (!ids.add(record.id)) return
        retained.addLast(Stored(record, persisted))
        while (retained.size > limit) ids.remove(retained.removeFirst().record.id)
    }
    private fun load(file: File) {
        if (Files.isSymbolicLink(file.toPath())) throw IllegalStateException()
        if (!file.exists()) return
        require(file.isFile && file.length() <= fileLimit)
        file.bufferedReader(Charsets.UTF_8).use { reader -> reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            try { require(line.length <= MAX_RECORD_BYTES); keep(decode(JSONObject(line)), true) }
            catch (_: Exception) { readErrors++ }
        } }
    }
    @Synchronized fun append(record: UsageRequestRecord): Boolean {
        if (record.id in ids) return retained.first { it.record.id == record.id }.persisted
        var persisted = false
        try {
            check(!blocked)
            val bytes = (record.json().toString() + "\n").toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_RECORD_BYTES && bytes.size <= fileLimit)
            if (!directory.exists()) check(directory.mkdirs())
            check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
            val file = File(directory, "usage.jsonl"); val previous = File(directory, "usage.jsonl.1")
            check(!Files.isSymbolicLink(file.toPath()) && !Files.isSymbolicLink(previous.toPath()))
            if (file.exists() && file.length() + bytes.size + 1 > fileLimit)
                Files.move(file.toPath(), previous.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            val needsSeparator = file.exists() && file.length() > 0 && RandomAccessFile(file, "r").use {
                it.seek(it.length() - 1); it.read() != 10
            }
            FileOutputStream(file, true).use { stream ->
                file.setReadable(false, false); file.setWritable(false, false)
                file.setReadable(true, true); file.setWritable(true, true)
                if (needsSeparator) stream.write(10)
                stream.write(bytes); stream.fd.sync()
            }
            persisted = true
        } catch (_: Exception) { writeError = true }
        keep(record, persisted); rebuild()
        return persisted
    }
    fun snapshot(): UsageSummary = summary
    private fun rebuild() {
        var ok = 0; var failed = 0; var cancelled = 0; var rejected = 0
        var attempts = 0L; var reported = 0L; var unknown = 0L; var notSent = 0L
        var input = 0L; var output = 0L; var cached = 0L; var cacheN = 0L; var reasoning = 0L; var reasoningN = 0L; var omitted = 0L
        val costs = sortedMapOf<String, BigDecimal>(); val costCounts = mutableMapOf<String, Long>()
        var estimated = 0L; var unestimated = 0L
        retained.forEach { stored ->
            val r = stored.record
            when (r.outcome) { "success" -> ok++; "cancelled" -> cancelled++; "local_reject" -> rejected++; else -> failed++ }
            attempts += r.attemptCount
            omitted += r.attemptCount - r.attempts.size
            r.attempts.forEach { a ->
                val estimate = a.costEstimate
                if (a.sent) {
                    if (estimate?.status == "estimated" && estimate.amount != null && estimate.currency in ModelPricing.CURRENCIES) {
                        val unit = estimate.currency!!
                        costs[unit] = (costs[unit] ?: BigDecimal.ZERO).add(BigDecimal(estimate.amount))
                        costCounts[unit] = (costCounts[unit] ?: 0L) + 1L; estimated++
                    } else unestimated++
                }
                val u = a.observation.counts
                when {
                    !a.sent -> notSent++
                    a.observation.status == "reported" && a.responseComplete && u != null -> {
                        reported++; input += u.input; output += u.output
                        u.cached?.let { cached += it; cacheN++ }; u.reasoning?.let { reasoning += it; reasoningN++ }
                    }
                    else -> unknown++
                }
            }
        }
        val unpersisted = retained.count { !it.persisted }
        summary = UsageSummary(retained.size, ok, failed, cancelled, rejected, attempts, reported, unknown, notSent,
            input, output, cached, cacheN, reasoning, reasoningN, omitted, limit, retained.peekFirst()?.record?.timeMs ?: 0L,
            if (blocked || writeError) "error" else if (readErrors > 0) "partial" else "ok", readErrors, unpersisted,
            retained.toList().takeLast(5).asReversed().map { UsageRecent(it.record.requestId, it.record.outcome, it.record.attemptCount, it.record.timeMs) },
            costs.map { (unit, amount) -> CurrencyEstimate(unit, amount.stripTrailingZeros().toPlainString(), costCounts.getValue(unit)) },
            estimated, unestimated)
    }
    companion object {
        const val MAX_RECORD_BYTES = 96 * 1024
        private val STATUSES = setOf("reported", "not_reported", "invalid", "incomplete", "observation_limit", "not_sent")
        private fun decode(j: JSONObject): UsageRequestRecord {
            require(j.getInt("schema") == 1)
            val id = UUID.fromString(j.getString("id")).toString(); val req = boundedId(j.getString("requestId"))
            val at = j.getLong("timeMs").also { require(it >= 0) }
            val outcome = j.getString("outcome").also { require(it in UsageRequestCapture.OUTCOMES) }
            val count = j.getInt("attemptCount").also { require(it >= 0) }
            val arr = j.getJSONArray("attempts"); require(arr.length() <= UsageRequestCapture.MAX_ATTEMPTS && arr.length() <= count)
            val attempts = (0 until arr.length()).map { n ->
                val a = arr.getJSONObject(n); val state = a.getString("usageStatus").also { require(it in STATUSES) }
                val sent = a.get("sent").also { require(it is Boolean) } as Boolean
                val complete = a.get("responseComplete").also { require(it is Boolean) } as Boolean
                val u = if (a.isNull("usage")) null else a.getJSONObject("usage").let { v ->
                    UsageCounts(usageInteger(v.opt("prompt_tokens")), usageInteger(v.opt("completion_tokens")),
                        if (v.isNull("cached_tokens")) null else usageInteger(v.opt("cached_tokens")),
                        if (v.isNull("reasoning_tokens")) null else usageInteger(v.opt("reasoning_tokens")))
                }
                require(state != "reported" || sent && complete && u != null)
                fun price(key: String): String? = if (a.isNull(key)) null else a.getString(key).also {
                    require(it.length <= 512 && BigDecimal(it).signum() >= 0)
                }
                val credential = if (a.isNull("credentialId")) null else a.getString("credentialId").also { require(it.matches(Regex("[a-f0-9]{32}"))) }
                val http = if (a.isNull("httpStatus")) null else a.getInt("httpStatus").also { require(it in 100..599) }
                val input = price("priceInputPerM"); val output = price("priceOutputPerM"); val cached = price("priceCachedInputPerM")
                val currency = if (a.isNull("currency")) null else a.getString("currency").also { require(it in ModelPricing.CURRENCIES) }
                val observation = UsageObservation(state, u)
                // Legacy records have no estimate. Never apply current configuration to historical rows.
                val estimate = if (a.isNull("tokenCostEstimate")) null else a.getJSONObject("tokenCostEstimate").let { e ->
                    require(e.get("version") is Number && BigDecimal(e.get("version").toString()).compareTo(BigDecimal.ONE) == 0)
                    val saved = TokenCostEstimate(if (e.isNull("currency")) null else e.getString("currency"),
                        if (e.isNull("amount")) null else e.getString("amount"), e.getString("status"))
                    require(saved == TokenCostEstimator.estimate(sent, complete, http, observation, currency, input, output, cached))
                    saved
                }
                UsageAttemptRecord(boundedId(a.getString("providerId")), boundedId(a.getString("modelId")), credential,
                    http, sent, complete, observation, input, output, currency, cached, estimate)
            }
            return UsageRequestRecord(id, req, at, outcome, count, attempts)
        }
    }
}

/** One shared ledger per application directory; diagnostics do not create files. */
object UsageLedgerStore {
    private val ledgers = LinkedHashMap<String, UsageLedger>(8, 0.75f, true)
    @Synchronized fun open(directory: File): UsageLedger {
        val key = directory.absoluteFile.normalize().path
        return ledgers[key] ?: UsageLedger(directory).also {
            if (ledgers.size >= 8) ledgers.remove(ledgers.keys.first())
            ledgers[key] = it
        }
    }
}
