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

    @Synchronized
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
        return "[REDACTED]"
    }

    fun maskHeaders(h: Map<String, String>?): Map<String, String> {
        if (h == null) return emptyMap()
        return h.mapValues { (k, v) ->
            if (SENSITIVE.contains(k.lowercase())) mask(v) ?: "***" else v
        }
    }

    // ---- 系统运行日志 ----
    @Synchronized
    fun system(level: String, tag: String, msg: String, fields: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        val dir = baseDir ?: return
        val sb = StringBuilder()
        sb.append(ts.format(Date())).append(" [").append(level).append("] ")
            .append(redact(tag)).append(": ").append(redact(msg))
        if (fields.isNotEmpty()) {
            sb.append(" | ")
            sb.append(fields.entries.joinToString(",") { "${it.key}=${if (sensitiveName(it.key)) "[REDACTED]" else redact(it.value.toString())}" })
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
    @Synchronized
    fun call(rec: Map<String, Any?>) {
        if (!enabled) return
        val dir = baseDir ?: return
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val sb = StringBuilder("{")
        sb.append("\"ts\":\"").append(ts.format(Date())).append("\",")
        sb.append("\"seq\":").append(seq.incrementAndGet())
        for ((k, v) in rec) {
            sb.append(",\"").append(esc(k)).append("\":").append(jsonVal(if (sensitiveName(k)) "[REDACTED]" else v))
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

    /** Initialize from the Service too; background diagnostics must not depend on Activity creation. */
    @Synchronized fun ensureInitialized(dir: File) { if (baseDir == null) init(dir) }
    fun directory(): File? = baseDir
    private fun sensitiveName(name: String): Boolean = name.lowercase(Locale.ROOT).replace("_", "").replace("-", "") in
        setOf("authorization", "apikey", "key", "token", "accesstoken", "refreshtoken", "cookie", "setcookie", "secret", "password", "body", "prompt", "messages", "content")

    fun redact(text: String, secrets: Collection<String> = emptyList()): String {
        var value = text
        for (secret in secrets.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }) value = value.replace(secret, "[REDACTED]")
        value = Regex("(?i)(?:github_pat_|ghp_|sk-)[a-z0-9_-]{8,}").replace(value, "[REDACTED]")
        value = Regex("(?i)bearer\\s+[^\\s,;\"}]+").replace(value, "Bearer [REDACTED]")
        value = Regex("(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|authorization|cookie|token|key)([\\s\"']*[:=][\\s\"']*)[^\\s,;\"'}]+").replace(value) { it.groupValues[1] + "=[REDACTED]" }
        return value.map { if (it < ' ' || it == 127.toChar()) ' ' else it }.joinToString("")
    }

    /** Bounded reverse scan. Cursor snapshots tolerate appends and reject rotated/replaced content. */
    fun query(root: File, args: org.json.JSONObject, secrets: Collection<String> = emptyList()): org.json.JSONObject {
        val allowed = setOf("stream", "limit", "requestId", "kind", "level", "contains", "cursor")
        require(args.keys().asSequence().all { it in allowed }) { "Unknown log query field" }
        val stream = args.optString("stream", "system"); require(stream in setOf("system", "calls"))
        val rawLimit = args.opt("limit")
        require(rawLimit == null || rawLimit is Number && rawLimit.toDouble().isFinite() && rawLimit.toDouble() % 1.0 == 0.0 && rawLimit.toDouble() in 1.0..200.0) { "limit must be integer in 1..200" }
        val limit = if (rawLimit == null) 50 else (rawLimit as Number).toInt(); require(limit in 1..200)
        for (field in listOf("requestId", "kind", "level", "contains", "cursor")) require(!args.has(field) || args.opt(field) is String) { "Invalid $field" }
        val rid = args.optString("requestId"); require(rid.length <= 100)
        val kind = args.optString("kind"); require(kind.isEmpty() || kind in setOf("request", "attempt", "rate_limit", "result", "diagnostic"))
        val level = args.optString("level").uppercase(Locale.ROOT); require(level.isEmpty() || level in setOf("INFO", "WARN", "ERROR"))
        require(stream == "calls" || kind.isEmpty()) { "kind applies only to calls" }
        require(stream == "system" || level.isEmpty()) { "level applies only to system" }
        val contains = args.optString("contains"); require(contains.length <= 128)
        val dir = File(root, if (stream == "system") "system" else "calls")
        val pattern = if (stream == "system") Regex("gateway[.]log([.][1-3])?") else Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}[.]jsonl([.][1-3])?")
        fun file(name: String): File {
            require(pattern.matches(name)) { "Invalid log file" }
            val f = File(dir, name)
            require(f.canonicalFile == f.absoluteFile && dir.canonicalFile == dir.absoluteFile) { "Log symlinks unsupported" }
            return f
        }
        fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun prefix(f: File, size: Long): String = java.io.RandomAccessFile(f, "r").use { r ->
            val bytes = ByteArray(minOf(size, 256L).toInt()); r.readFully(bytes); hash(bytes)
        }
        val binding = hash((stream + "|" + rid + "|" + kind + "|" + level + "|" + contains).toByteArray())
        val cursor = args.optString("cursor"); require(cursor.length <= 16384)
        val state: org.json.JSONObject
        if (cursor.isEmpty()) {
            val candidates = dir.listFiles().orEmpty().filter { pattern.matches(it.name) && it.isFile }
                .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
            val files = org.json.JSONArray()
            candidates.take(16).forEach { f -> val safe = file(f.name); val size = safe.length()
                files.put(org.json.JSONObject().put("name", f.name).put("size", size).put("prefix", prefix(safe, size))) }
            state = org.json.JSONObject().put("v",1).put("binding",binding).put("files",files).put("index",0)
                .put("offset",if (files.length()>0) files.getJSONObject(0).getLong("size") else 0L)
                .put("filesLimited",candidates.size>16)
        } else {
            val bytes = java.util.Base64.getUrlDecoder().decode(cursor); require(bytes.size <= 12288)
            state = org.json.JSONObject(String(bytes, Charsets.UTF_8))
            require(state.getInt("v")==1 && state.getString("binding")==binding) { "Cursor query mismatch" }
        }
        val files = state.getJSONArray("files"); require(files.length()<=16)
        var index = state.getInt("index"); require(index in 0..files.length())
        var offset = state.getLong("offset"); var scanned=0; var skipped=0; var malformed=0
        val records=org.json.JSONArray(); var returnedChars=0; var limited=false
        val safeFields=setOf("ts","seq","epoch_ms","kind","req_id","method","path","body_size","provider","model","ok","latency_ms","http_status","cancelled","wait_ms","total_ms","trace","err")
        scan@ while(index<files.length() && scanned<1048576 && records.length()<limit && returnedChars<65536) {
            val meta=files.getJSONObject(index); val f=file(meta.getString("name")); val size=meta.getLong("size")
            require(size>=0 && offset in 0..size)
            if(!f.isFile || f.length()<size || prefix(f,size)!=meta.getString("prefix")) return org.json.JSONObject().put("ok",false).put("code","CURSOR_STALE").put("error","Log rotation/replacement detected; restart without cursor")
            if(offset==0L) {index++; offset=if(index<files.length()) files.getJSONObject(index).getLong("size") else 0; continue}
            val n=minOf(offset,65536L,(1048576-scanned).toLong()).toInt();val start=offset-n
            val bytes=ByteArray(n);java.io.RandomAccessFile(f,"r").use { it.seek(start);it.readFully(bytes) };scanned+=n
            val first=if(start==0L)0 else bytes.indexOf(10.toByte()).let { if(it<0)n else it+1 }
            if(first>=n){offset=start;skipped++;continue}
            var end=n
            while(end>first) {
                if(bytes[end-1]==10.toByte())end--
                if(end<=first)break
                var begin=end-1;while(begin>=first && bytes[begin]!=10.toByte())begin--;begin++
                val len=end-begin;offset=start+begin
                if(len>8192){skipped++;end=begin;continue}
                val raw=String(bytes,begin,len,Charsets.UTF_8).trimEnd('\r')
                val rec:org.json.JSONObject
                if(stream=="calls") {
                    val o=try{org.json.JSONObject(raw)}catch(_:Exception){malformed++;end=begin;continue}
                    if(rid.isNotEmpty() && o.optString("req_id")!=rid || kind.isNotEmpty() && o.optString("kind")!=kind){end=begin;continue}
                    rec=org.json.JSONObject()
                    safeFields.forEach { key -> if(o.has(key)) {
                        val v=o.opt(key)
                        rec.put(key,when(v){ is String -> redact(v,secrets).take(512); is org.json.JSONArray -> org.json.JSONArray((0 until minOf(v.length(),20)).map{redact(v.optString(it),secrets).take(256)}); is Number,is Boolean -> v; else -> org.json.JSONObject.NULL })
                    }}
                } else {
                    val text=redact(raw,secrets).take(2048)
                    if(rid.isNotEmpty() && !text.contains("rid=$rid") || level.isNotEmpty() && !text.contains("[$level]")){end=begin;continue}
                    rec=org.json.JSONObject().put("text",text)
                }
                val text=rec.toString()
                // Filter AFTER redaction so secret content cannot be inferred through match counts.
                if(contains.isNotEmpty() && !text.contains(contains,true)){end=begin;continue}
                rec.put("source",f.name);records.put(rec);returnedChars+=text.length
                end=begin
                if(records.length()>=limit || returnedChars>=65536){limited=true;break@scan}
            }
            offset=start+first
        }
        val more=index<files.length() && (offset>0 || index+1<files.length())
        state.put("index",index).put("offset",offset)
        val next=if(more)java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(state.toString().toByteArray())else null
        return org.json.JSONObject().put("ok",true).put("stream",stream).put("order","newest_first").put("records",records)
            .put("hasMore",more).put("nextCursor",next?:org.json.JSONObject.NULL).put("scannedBytes",scanned)
            .put("scanBudgetBytes",1048576).put("skippedOversizeFragments",skipped).put("malformedLines",malformed)
            .put("fileListLimited",state.optBoolean("filesLimited")).put("partial",skipped>0 || malformed>0 || state.optBoolean("filesLimited"))
            .put("limitReached",limited).put("redacted",true).put("rawBodiesIncluded",false)
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
            "\"${esc(it.key.toString())}\":${jsonVal(if (sensitiveName(it.key.toString())) "[REDACTED]" else it.value)}"
        }
        is List<*> -> v.joinToString(",", "[", "]") { jsonVal(it) }
        else -> "\"${esc(redact(v.toString()))}\""
    }
}
