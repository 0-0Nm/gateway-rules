package com.aqi.ccb.gateway

import org.json.JSONObject

/**
 * baseUrl 后缀自动探测 —— 在 rikka 路径规范化基础上再加「真发请求验证」。
 *
 * rikka 的做法（ProviderConfigure.kt）是纯字符串改写：
 *   判断结尾是 /v1 还是 /v1beta，截断后拼目标路径。
 *   它不真发请求，所以无法判断「这个 baseUrl 到底通不通」。
 *
 * 这里是加强版：按候选后缀逐个试 /models，返回第一个 200 的。
 *
 * 候选来源（按顺序）：
 *   1 用户输入原样（如果已含 /v1 或 /v1beta）
 *   2 原样 + /v1        （OpenAI 兼容最常见）
 *   3 原样 + /v1beta    （Google Gemini 风格）
 *   4 原样 + /openai    （部分中转站）
 *   5 原样 + /compatible-mode/v1 （阿里云百炼等）
 *   6 若原样已含 /v1 或 /v1beta，则截断后再试一次
 */
object BaseUrlProbe {

    enum class Style(val label: String) {
        INPUT("原样"),
        OPENAI("OpenAI 兼容 /v1"),
        GOOGLE("Google /v1beta"),
        OPENAI_COMPAT("中转 /openai"),
        ALIYUN("阿里云 /compatible-mode/v1"),
        STRIPPED("截断后缀");
    }

    data class Candidate(val url: String, val style: Style)

    data class Result(
        val ok: Boolean,
        val resolvedUrl: String?,
        val style: Style?,
        val modelCount: Int,
        val tried: List<Pair<String, String>>,   // url -> 结果摘要
        val error: String? = null,
    ) {
        fun summary(): String {
            if (!ok) {
                return "全部失败（试了 ${tried.size} 个）\n" +
                    tried.joinToString("\n") { (u, r) -> "· $u → $r" }
            }
            return "可用：$resolvedUrl（${style?.label}）· 发现 $modelCount 个模型"
        }
    }

    /** 生成候选列表（去重保序） */
    fun candidates(input: String): List<Candidate> {
        val raw = input.trim().trimEnd('/')
        if (raw.isEmpty()) return emptyList()

        val out = LinkedHashSet<Candidate>()

        // 已含后缀 -> 先用原样
        val hasSuffix = raw.lowercase().endsWith("/v1") ||
            raw.lowercase().endsWith("/v1beta") ||
            raw.lowercase().endsWith("/v1/completions")

        if (hasSuffix) {
            out.add(Candidate(raw, Style.INPUT))
        }

        // 追加常见后缀
        out.add(Candidate("$raw/v1", Style.OPENAI))
        out.add(Candidate("$raw/v1beta", Style.GOOGLE))
        out.add(Candidate("$raw/openai", Style.OPENAI_COMPAT))
        out.add(Candidate("$raw/compatible-mode/v1", Style.ALIYUN))

        // 若原样已带后缀，再试截断后重拼
        if (hasSuffix) {
            val stripped = stripSuffix(raw)
            if (stripped.isNotEmpty() && stripped != raw) {
                out.add(Candidate("$stripped/v1", Style.STRIPPED))
            }
        }

        // 原样（未带后缀时放最后兜底）
        if (!hasSuffix) {
            out.add(Candidate(raw, Style.INPUT))
        }

        return out.toList()
    }

    /** 按 rikka 的 normalizePath 规则截断已知后缀 */
    fun stripSuffix(url: String): String {
        val u = url.trim().trimEnd('/')
        return when {
            u.lowercase().endsWith("/v1beta") -> u.dropLast("/v1beta".length)
            u.lowercase().endsWith("/v1") -> u.dropLast("/v1".length)
            else -> u
        }
    }

    /**
     * 逐个候选试 /models，返回第一个可用的。
     * 全同步执行，调用方放后台线程。
     */
    fun probe(input: String, apiKey: String, timeoutMs: Int = 12000, cancelled: () -> Boolean = { false }): Result {
        val cands = candidates(input)
        if (cands.isEmpty()) {
            return Result(false, null, null, 0, emptyList(), "baseUrl 为空")
        }
        val tried = mutableListOf<Pair<String, String>>()

        for (c in cands) {
            if(cancelled() || Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("Discovery cancelled")
            val r = tryFetchModels(c.url, apiKey, timeoutMs)
            tried.add(c.url to r.second)
            if (r.first > 0) {
                return Result(true, c.url, c.style, r.first, tried)
            }
        }
        return Result(false, null, null, 0, tried, "无可用端点")
    }

    /** 返回 (模型数, 结果摘要)；模型数 0 表示失败 */
    private fun tryFetchModels(baseUrl: String, apiKey: String, timeoutMs: Int): Pair<Int, String> {
        val r = HttpUtil.get("$baseUrl/models", apiKey, timeoutMs)
        if (r.error != null) return 0 to r.error
        if (!r.ok) return 0 to "http_" + r.code
        val arr = runCatching { JSONObject(r.body).optJSONArray("data") }
            .getOrNull()
            ?: runCatching { JSONObject(r.body).optJSONArray("models") }.getOrNull()
            ?: return 0 to "无 data 字段"
        var n = 0
        for (i in 0 until arr.length()) {
            val id = arr.optJSONObject(i)?.optString("id")
                ?: arr.optJSONObject(i)?.optString("name")
            if (!id.isNullOrBlank()) n++
        }
        return if (n == 0) 0 to "模型列表为空" else n to "ok(" + n + ")"
    }

    /** 只拉模型列表（不探测后缀），供「探测模型」按钮用 */
    fun listModels(baseUrl: String, apiKey: String, timeoutMs: Int = 15000): List<String> {
        val r = HttpUtil.get("${baseUrl.trimEnd('/')}/models", apiKey, timeoutMs)
        if (!r.ok) return emptyList()
        val json=runCatching{JSONObject(r.body)}.getOrNull() ?: return emptyList()
        val arr=json.optJSONArray("data") ?: json.optJSONArray("models") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val item=arr.optJSONObject(i)
            ((item?.opt("id") as? String) ?: (item?.opt("name") as? String))?.takeIf{it.isNotBlank()}
        }.distinct()
    }
}
