package com.aqi.ccb.gateway

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 双轨日志：调用日志(JSONL) + 系统运行日志。
 * 硬约束：密钥强制脱敏，任何 header/key 不落原文。
 */
object GateLog {

    private val seq = AtomicLong(0)
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile var enabled: Boolean = true
    @Volatile private var baseDir: File? = null
    private var callMaxBytes: Long = 8L * 1024 * 1024
    private var sysMaxBytes: Long = 4L * 1024 * 1024

    private val SENSITIVE = setOf(
        "authorization", "api-key", "apikey", "x-api-key", "token",
        "access_token", "refresh_token", "cookie", "set-cookie", "secret"
    )

    fun init(dir: File, callMax: Long = 8L * 1024 * 1024, sysMax: Long = 4L * 1024 * 1024) {
        baseDir = dir
        callMaxBytes = callMax
        sysMaxBytes = sysMax
        dir.mkdirs()
        File(dir, "calls").mkdirs()
        File(dir, "system").mkdirs()
        system("INFO", "log", "gatelog init base=${dir.absolutePath}")
    }

    fun mask(v: String?): String? {
        if (v == null) return null
        if (v.length <= 8) return "***"
        return v.take(4) + "***" + v.takeLast(4)
    }

    fun maskHeaders(h: Map<String, String>?): Map<String, String> {
        if (h == null) return emptyMap()
        return h.mapValues { (k, v) ->
            if (SENSITIVE.contains(k.lowercase())) mask(v) ?: "***" else v
        }
    }

    // ---- 系统运行日志 ----
    fun system(level: String, tag: String, msg: String, fields: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        val dir = baseDir ?: return
        val sb = StringBuilder()
        sb.append(ts.format(Date())).append(" [").append(level).append("] ")
            .append(tag).append(": ").append(msg)
        if (fields.isNotEmpty()) {
            sb.append(" | ")
            sb.append(fields.entries.joinToString(",") { "${it.key}=${it.value}" })
        }
        append(File(dir, "system/gateway.log"), sb.toString(), sysMaxBytes)
    }

    fun info(tag: String, msg: String, f: Map<String, Any?> = emptyMap()) = system("INFO", tag, msg, f)
    fun warn(tag: String, msg: String, f: Map<String, Any?> = emptyMap()) = system("WARN", tag, msg, f)

    fun error(tag: String, msg: String, e: Throwable? = null, f: Map<String, Any?> = emptyMap()) {
        val m = if (e == null) emptyMap() else mapOf("exc" to (e::class.java.simpleName + ": " + (e.message ?: "")))
        system("ERROR", tag, msg, f + m)
    }

    // ---- 调用日志(JSONL) ----
    fun call(rec: Map<String, Any?>) {
        if (!enabled) return
        val dir = baseDir ?: return
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val sb = StringBuilder("{")
        sb.append("\"ts\":\"").append(ts.format(Date())).append("\",")
        sb.append("\"seq\":").append(seq.incrementAndGet())
        for ((k, v) in rec) {
            sb.append(",\"").append(esc(k)).append("\":").append(jsonVal(v))
        }
        sb.append("}")
        append(File(dir, "calls/$day.jsonl"), sb.toString(), callMaxBytes)
    }

    fun requestStart(reqId: String, method: String, path: String, client: String,
                     headers: Map<String, String>, bodySize: Int) {
        call(mapOf(
            "kind" to "request", "req_id" to reqId, "method" to method, "path" to path,
            "client" to client, "headers" to maskHeaders(headers), "body_size" to bodySize
        ))
        info("http", "$method $path from=$client rid=$reqId")
    }

    fun attempt(reqId: String, provider: String, model: String, ok: Boolean,
                latencyMs: Double, httpStatus: Int?, err: String?, cancelled: Boolean = false) {
        call(mapOf(
            "kind" to "attempt", "req_id" to reqId, "provider" to provider, "model" to model,
            "ok" to ok, "latency_ms" to String.format(Locale.US, "%.1f", latencyMs),
            "http_status" to httpStatus, "err" to err, "cancelled" to cancelled
        ))
        if (ok) info("upstream", "ok provider=$provider model=$model ${"%.0f".format(latencyMs)}ms rid=$reqId")
        else warn("upstream", "fail provider=$provider status=$httpStatus err=${(err ?: "").take(80)} rid=$reqId")
    }

    fun rateLimited(reqId: String, provider: String, waitMs: Double) {
        call(mapOf("kind" to "rate_limit", "req_id" to reqId, "provider" to provider,
            "wait_ms" to String.format(Locale.US, "%.1f", waitMs)))
        warn("ratelimit", "throttled provider=$provider wait=${"%.0f".format(waitMs)}ms rid=$reqId")
    }

    fun requestEnd(reqId: String, ok: Boolean, provider: String, totalMs: Double, trace: List<String>) {
        call(mapOf("kind" to "result", "req_id" to reqId, "ok" to ok, "provider" to provider,
            "total_ms" to String.format(Locale.US, "%.1f", totalMs), "trace" to trace))
        info("http", "done rid=$reqId ok=$ok provider=$provider ${"%.0f".format(totalMs)}ms")
    }

    // ---- 内部 ----
    private fun append(f: File, line: String, maxBytes: Long) {
        try {
            f.parentFile?.mkdirs()
            if (f.exists() && f.length() > maxBytes) rotate(f)
            f.appendText(line + "\n", Charsets.UTF_8)
        } catch (e: Exception) {
            System.err.println("[gatelog] write failed: $e")
        }
    }

    private fun rotate(f: File) {
        try {
            for (i in 2 downTo 1) {
                val s = File(f.parentFile, f.name + "." + i)
                if (s.exists()) s.renameTo(File(f.parentFile, f.name + "." + (i + 1)))
            }
            f.renameTo(File(f.parentFile, f.name + ".1"))
        } catch (_: Exception) {}
    }

    // 转义已抽到 JsonUtil（原与 GatewayServer 各一份，且都漏了控制字符）
    private fun esc(s: String) = JsonUtil.esc(s)
    private fun jsonVal(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Number -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") {
            "\"${esc(it.key.toString())}\":${jsonVal(it.value)}"
        }
        is List<*> -> v.joinToString(",", "[", "]") { jsonVal(it) }
        else -> "\"${esc(v.toString())}\""
    }
}
