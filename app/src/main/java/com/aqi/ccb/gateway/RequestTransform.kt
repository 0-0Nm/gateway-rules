package com.aqi.ccb.gateway

import org.json.JSONArray
import org.json.JSONObject

/**
 * 请求变换规则 —— 第三类算子（select 纯读 / execute 副作用 / transform 改请求）。
 *
 * 挂在 VirtualModel 上：同一上游 key 可配两个虚拟模型，
 * 「原始」透传、「伪装」清指纹+换 UA+注 prompt，客户端选哪个名就走哪套。
 */
enum class TransformType {
    SANITIZE_FINGERPRINT,
    FAKE_CLIENT_NAME,
    INJECT_SYSTEM_PROMPT,
    REWRITE_PARAM,
    STRIP_PARAM,
    STRIP_HEADERS;

    companion object {
        fun parse(v: String?): TransformType? =
            runCatching { valueOf((v ?: "").uppercase()) }.getOrNull()
    }
}

data class TransformSpec(
    val type: TransformType,
    val enabled: Boolean = true,
    val key: String = "",
    val value: String = "",
    val append: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.name)
        put("enabled", enabled)
        if (key.isNotEmpty()) put("key", key)
        if (value.isNotEmpty()) put("value", value)
        if (append) put("append", true)
    }

    companion object {
        fun fromJson(o: JSONObject): TransformSpec? {
            val t = TransformType.parse(o.optString("type")) ?: return null
            return TransformSpec(
                type = t,
                enabled = o.optBoolean("enabled", true),
                key = o.optString("key", ""),
                value = o.optString("value", ""),
                append = o.optBoolean("append", false),
            )
        }

        fun parseList(arr: JSONArray?): List<TransformSpec> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { fromJson(it) }
            }
        }
    }
}

data class TransformedRequest(
    val body: ByteArray,
    val setHeaders: Map<String, String>,
    val removeHeaders: Set<String>,
    val trace: List<String>,
)

/** 请求变换引擎。纯函数，同样输入必得同样输出，便于审计。 */
object RequestTransform {

    private val FINGERPRINT_PREFIXES = listOf(
        "x-stainless-", "x-client-", "anthropic-",
        "openai-", "x-request-id", "x-sdk-",
    )

    private val ALWAYS_STRIP = setOf(
        "host", "content-length", "connection",
        "accept-encoding", "transfer-encoding",
    )

    fun apply(specs: List<TransformSpec>, body: ByteArray): TransformedRequest {
        var json: JSONObject? = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
        val setHeaders = LinkedHashMap<String, String>()
        val removeHeaders = LinkedHashSet<String>()
        val trace = mutableListOf<String>()

        for (spec in specs) {
            if (!spec.enabled) continue
            when (spec.type) {
                TransformType.SANITIZE_FINGERPRINT -> {
                    FINGERPRINT_PREFIXES.forEach { removeHeaders.add(it) }
                    trace.add("sanitize_fingerprint")
                }

                TransformType.FAKE_CLIENT_NAME -> {
                    val name = spec.value.ifEmpty { spec.key }
                    if (name.isNotEmpty()) {
                        setHeaders["User-Agent"] = name
                        removeHeaders.add("x-stainless-")
                        trace.add("fake_client_name=$name")
                    }
                }

                TransformType.INJECT_SYSTEM_PROMPT -> {
                    val text = spec.value.ifEmpty { spec.key }
                    val j = json
                    if (j != null && text.isNotEmpty()) {
                        if (injectSystem(j, text, spec.append)) {
                            trace.add("inject_prompt(${text.length}字)")
                        }
                    }
                }

                TransformType.REWRITE_PARAM -> {
                    val j = json
                    if (j != null && spec.key.isNotEmpty() && spec.value.isNotEmpty()) {
                        if (j.has(spec.key) && !j.has(spec.value)) {
                            j.put(spec.value, j.get(spec.key))
                            j.remove(spec.key)
                            trace.add("rewrite ${spec.key}->${spec.value}")
                        }
                    }
                }

                TransformType.STRIP_PARAM -> {
                    val j = json
                    if (j != null && spec.key.isNotEmpty() && j.has(spec.key)) {
                        j.remove(spec.key)
                        trace.add("strip_param ${spec.key}")
                    }
                }

                TransformType.STRIP_HEADERS -> {
                    if (spec.key.isNotEmpty()) {
                        removeHeaders.add(spec.key.lowercase())
                        trace.add("strip_header ${spec.key}")
                    }
                }
            }
        }

        val outBody = json?.toString()?.toByteArray(Charsets.UTF_8) ?: body
        return TransformedRequest(outBody, setHeaders, removeHeaders, trace)
    }

    private fun injectSystem(root: JSONObject, text: String, append: Boolean): Boolean {
        val msgs = root.optJSONArray("messages") ?: return false
        val sysObj = JSONObject().apply {
            put("role", "system")
            put("content", text)
        }
        if (append) {
            msgs.put(sysObj)
            return true
        }
        if (msgs.length() > 0) {
            val first = msgs.optJSONObject(0)
            if (first != null && first.optString("role") == "system") {
                val exist = first.optString("content", "")
                first.put("content", if (exist.isEmpty()) text else "$text\n\n$exist")
                return true
            }
        }
        val fresh = JSONArray()
        fresh.put(sysObj)
        for (i in 0 until msgs.length()) fresh.put(msgs.get(i))
        root.put("messages", fresh)
        return true
    }

    fun shouldRemove(name: String, removeHeaders: Set<String>): Boolean {
        val n = name.lowercase()
        if (n in ALWAYS_STRIP) return true
        if (n in removeHeaders) return true
        return removeHeaders.any { it.endsWith("-") && n.startsWith(it) }
    }
}
