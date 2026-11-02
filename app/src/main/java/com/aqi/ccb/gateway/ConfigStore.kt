package com.aqi.ccb.gateway

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Single committed generation. Legacy files are imported on first successful mutation only. */
object ConfigStore {
    private const val STATE = "config-state-v1.json"
    private const val MAX_BYTES = 8 * 1024 * 1024
    class ConfigException(val code: String, message: String) : IOException(message)
    data class Snapshot(
        val revision: Long,
        val providers: MutableList<JSONObject>,
        val rules: MutableList<JSONObject>,
        val keys: MutableMap<String, String>,
        val virtualModels: MutableList<JSONObject>,
    ) {
        fun json(): JSONObject = JSONObject().put("formatVersion", 1).put("revision", revision)
            .put("providers", JSONArray(providers)).put("rules", JSONArray(rules))
            .put("keys", JSONObject(keys as Map<*, *>)).put("virtualModels", JSONArray(virtualModels))
        fun detached(): Snapshot = decode(json())
    }
    data class Commit(val revision: Long, val changed: Boolean, val applied: Boolean, val applyError: String? = null) {
        fun json(): JSONObject = JSONObject().put("ok", true).put("persisted", true)
            .put("revision", revision).put("changed", changed).put("applied", applied)
            .put("applyError", applyError ?: JSONObject.NULL)
    }
    // The Android service owns this hook. Tests can leave it null or inject a deterministic publisher.
    @Volatile var publisher: ((Snapshot) -> Boolean)? = null

    private fun array(o: JSONObject, key: String): MutableList<JSONObject> {
        val arr = o.getJSONArray(key)
        return MutableList(arr.length()) { JSONObject(arr.getJSONObject(it).toString()) }
    }
    private fun decode(o: JSONObject): Snapshot {
        if (o.getInt("formatVersion") != 1) throw ConfigException("FORMAT_UNSUPPORTED", "Unsupported config format")
        val revision = o.getLong("revision")
        if (revision < 0) throw ConfigException("CORRUPT_CONFIG", "Invalid config revision")
        val keyObject = o.getJSONObject("keys")
        val keys = mutableMapOf<String, String>()
        keyObject.keys().forEach { k -> keys[k] = keyObject.getString(k) }
        return Snapshot(revision, array(o, "providers"), array(o, "rules"), keys, array(o, "virtualModels"))
    }
    private fun text(f: File): String {
        if (!f.isFile || f.length() > MAX_BYTES) throw ConfigException("CONFIG_UNREADABLE", "Config file unreadable or too large")
        return f.readText(Charsets.UTF_8)
    }
    private fun legacyArray(dir: File, name: String): MutableList<JSONObject> {
        val f = File(dir, name)
        if (!f.exists()) return mutableListOf()
        val a = JSONArray(text(f))
        return MutableList(a.length()) { a.getJSONObject(it) }
    }
    private fun readUnlocked(ctx: Context): Snapshot {
        try {
            val f = File(ctx.filesDir, STATE)
            val state = if (f.exists()) decode(JSONObject(text(f))) else {
                val kf = File(ctx.filesDir, "keys.json")
                val ko = if (kf.exists()) JSONObject(text(kf)) else JSONObject()
                val keys = mutableMapOf<String, String>()
                ko.keys().forEach { k -> keys[k] = ko.getString(k) }
                Snapshot(0, legacyArray(ctx.filesDir, "providers.json"), legacyArray(ctx.filesDir, "rules.json"),
                    keys, legacyArray(ctx.filesDir, "virtual_models.json"))
            }
            ConfigValidation.validate(state)
            return state
        } catch (e: ConfigException) { throw e }
        catch (e: Exception) { throw ConfigException("CORRUPT_CONFIG", "Cannot read configuration (${e.javaClass.simpleName}); previous files were not replaced") }
    }
    private fun canonical(v:Any?):String = when(v) {
        null,JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().toList().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonical(v.get(it))}
        is JSONArray -> (0 until v.length()).joinToString(",","[","]"){canonical(v.get(it))}
        is String -> JSONObject.quote(v)
        is Number -> java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
        is Boolean -> v.toString()
        else -> throw ConfigException("INVALID_CONFIG","Unsupported JSON value")
    }
    @Synchronized fun read(ctx: Context): Snapshot = readUnlocked(ctx)

    private fun publish(state: Snapshot, changed: Boolean): Commit = try {
        Commit(state.revision, changed, publisher?.invoke(state.detached()) == true)
    } catch (e: Exception) {
        // Persistence is committed. Do not claim runtime publication succeeded or retry the write.
        Commit(state.revision, changed, false, e.javaClass.simpleName)
    }
    @Synchronized fun applyCurrent(ctx: Context): Commit = publish(readUnlocked(ctx), false)

    @Synchronized
    fun transact(ctx: Context, expectedRevision: Long? = null, edit: (Snapshot) -> Unit): Commit {
        val dir = ctx.filesDir
        if (!dir.exists() && !dir.mkdirs()) throw ConfigException("WRITE_FAILED", "Cannot create config directory")
        if (!dir.isDirectory) throw ConfigException("WRITE_FAILED", "Config path is not a directory")
        RandomAccessFile(File(dir, ".config-write.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                val current = readUnlocked(ctx)
                if (expectedRevision != null && expectedRevision != current.revision)
                    throw ConfigException("REVISION_CONFLICT", "Configuration changed; reload before editing")
                val next = current.detached()
                edit(next)
                ConfigValidation.validate(next)
                val unchanged = canonical(current.json()) == canonical(next.json())
                if (unchanged && File(dir, STATE).exists()) return publish(current, false)
                if (current.revision == Long.MAX_VALUE) throw ConfigException("REVISION_EXHAUSTED", "Configuration revision exhausted")
                val committed = next.copy(revision = current.revision + 1)
                val bytes = committed.json().toString(2).toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_BYTES) throw ConfigException("CONFIG_TOO_LARGE", "Configuration exceeds size limit")
                val target = File(dir, STATE)
                val temp = File.createTempFile(".config-", ".tmp", dir)
                try {
                    // App-private files; no credential-bearing copies in shared storage.
                    temp.setReadable(false, false); temp.setWritable(false, false)
                    temp.setReadable(true, true); temp.setWritable(true, true)
                    FileOutputStream(temp).use { stream -> stream.write(bytes); stream.fd.sync() }
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) {
                    throw ConfigException("WRITE_FAILED", "Atomic configuration commit failed (${e.javaClass.simpleName})")
                } finally { if (temp.exists()) temp.delete() }
                GateLog.info("config", "committed revision=" + committed.revision)
                return publish(committed, true)
            }
        }
    }

    fun loadProviders(ctx: Context): MutableList<JSONObject> = read(ctx).providers
    fun loadRules(ctx: Context): MutableList<JSONObject> = read(ctx).rules
    fun loadKeys(ctx: Context): MutableMap<String, String> = read(ctx).keys
    fun saveProviders(ctx: Context, list: List<JSONObject>) { transact(ctx) { it.providers.clear(); it.providers.addAll(list) } }
    fun saveRules(ctx: Context, list: List<JSONObject>) { transact(ctx) { it.rules.clear(); it.rules.addAll(list) } }
    fun saveKeys(ctx: Context, keys: Map<String, String>) { transact(ctx) { it.keys.clear(); it.keys.putAll(keys) } }
    fun toProviders(list: List<JSONObject>): List<Provider> = list.map { Provider.fromJson(jsonToMap(it)) }
    fun toRules(list: List<JSONObject>): List<RuleSpec> = list.map { RuleSpec.fromJson(jsonToMap(it)) }

    /** JSONObject -> Map<String, Any?>，供 Contracts.fromJson 使用 */
    fun jsonToMap(o: JSONObject): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        o.keys().forEach { k -> m[k] = unwrap(o.opt(k)) }
        return m
    }

    private fun unwrap(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonToMap(v)
        is JSONArray -> (0 until v.length()).map { unwrap(v.opt(it)) }
        else -> v
    }

    // ---------------- 默认模板 ----------------

    fun defaultProviderJson(name: String, baseUrl: String, modelId: String, apiKeyRef: String): JSONObject {
        val models = JSONArray().put(JSONObject().apply {
            put("modelId", modelId)
            put("abilities", JSONArray().put("TOOL").put("REASONING"))
            put("contextLength", 128000)
        })
        return JSONObject().apply {
            put("id", name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'))
            put("name", name)
            put("kind", "openai")
            put("baseUrl", baseUrl)
            put("enabled", true)
            put("tier", "free")
            put("weight", 1)
            put("apiKeyRef", apiKeyRef)
            put("models", models)
            put("rateLimit", JSONObject().apply {
                put("ratePerMin", 30)
                put("burst", 3)
                put("minIntervalMs", 800)
                put("jitterMs", 150)
                put("maxInflight", 2)
            })
        }
    }

    fun defaultRuleJson(id: String = "default"): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", "默认规则")
        put("ruleSpecVersion", 1)
        put("enabled", true)
        put("select", JSONArray().apply {
            put(JSONObject().put("op", "breaker_filter"))
            put(JSONObject().apply {
                put("op", "require")
                put("args", JSONObject().put("tools", true))
            })
            put(JSONObject().put("op", "fastest_first"))
        })
        put("execute", JSONObject().apply {
            put("op", "first")
            put("args", JSONObject())
        })
    }
}
