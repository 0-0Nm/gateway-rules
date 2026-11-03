package com.aqi.ccb.application

import com.aqi.ccb.data.ModelInfo
import com.aqi.ccb.gateway.BaseUrlProbe
import com.aqi.ccb.gateway.ModelProbe
import java.util.concurrent.CancellationException

/** Shared UI/MCP provider use cases. No persistent writes and no secret values in results. */
object ProviderWorkflows {
    const val MAX_BATCH_MODELS = 20
    data class Discovery(val ok: Boolean, val resolvedUrl: String = "", val style: String = "INPUT",
                         val models: List<String> = emptyList(), val errorCode: String? = null) {
        fun summary(): String = if (ok) "可用：$resolvedUrl · 发现 ${models.size} 个模型（新模型默认未启用）" else "发现未完成：${errorCode ?: "UNKNOWN"}"
    }
    private fun check(cancelled: () -> Boolean) { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Provider task cancelled") }
    fun discover(baseUrl: String, apiKey: String, suffixStyle: String = "AUTO", timeoutMs: Int = 12000,
                 cancelled: () -> Boolean = { false }): Discovery {
        check(cancelled)
        val uri = runCatching { java.net.URI(baseUrl.trim()) }.getOrNull()
        require(uri != null && uri.scheme in setOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.rawQuery == null) { "Invalid provider base URL" }
        require(timeoutMs in 100..30000)
        val style = suffixStyle.uppercase()
        require(style in setOf("AUTO","OPENAI","GOOGLE","OPENAI_COMPAT","ALIYUN","INPUT")) { "Invalid suffix style" }
        val result = if (style == "AUTO") {
            val probe = BaseUrlProbe.probe(baseUrl.trim(),apiKey,timeoutMs,cancelled)
            check(cancelled)
            if (!probe.ok || probe.resolvedUrl == null) return Discovery(false,errorCode="NO_USABLE_ENDPOINT")
            probe.resolvedUrl to (probe.style?.name ?: "INPUT")
        } else {
            val raw=baseUrl.trim().trimEnd('/')
            val suffix=when(style){"OPENAI"->"/v1";"GOOGLE"->"/v1beta";"OPENAI_COMPAT"->"/openai";"ALIYUN"->"/compatible-mode/v1";else->""}
            (if(suffix.isEmpty() || raw.endsWith(suffix,true)) raw else BaseUrlProbe.stripSuffix(raw)+suffix) to style
        }
        check(cancelled)
        val ids=BaseUrlProbe.listModels(result.first,apiKey,timeoutMs).filter{it.isNotBlank()}.distinct()
        check(cancelled)
        return if(ids.isEmpty()) Discovery(false,errorCode="EMPTY_OR_UNREADABLE_MODEL_LIST") else Discovery(true,result.first,result.second,ids)
    }
    fun probeOne(baseUrl:String,apiKey:String,modelId:String,timeoutMs:Int=15000,
                 cancelled:()->Boolean={false}):ModelProbe.ProbeResult {
        check(cancelled)
        return ModelProbe.probe(baseUrl.trimEnd('/'),apiKey,modelId,timeoutMs,cancelled)
    }
    fun mergeDiscovered(current: List<ModelInfo>, ids: List<String>): List<ModelInfo> {
        val previous=current.associateBy{it.modelId}
        return ids.filter{it.isNotBlank()}.distinct().map{previous[it] ?: ModelInfo(it,enabled=false)}
    }
    fun selectedForTest(models: List<ModelInfo>): List<ModelInfo> = models.filter{it.enabled}.distinctBy{it.modelId}.take(MAX_BATCH_MODELS)
    fun applyCapabilityResult(current: ModelInfo, result: ModelProbe.ProbeResult): ModelInfo {
        val abilities=current.abilities.filter{it!="TOOL"}.toMutableList()
        if(result.toolCall)abilities.add("TOOL")
        return current.copy(available=result.nonStreaming,latencyMs=if(result.nonStreaming)result.latencyMs else -1,
            lastError=result.error ?: "",testedAt=System.currentTimeMillis(),abilities=abilities.distinct(),
            nonStreaming=result.nonStreaming,streaming=result.streaming,toolCall=result.toolCall)
        // enabled/displayName/price/modalities/etc are user choices, never overwritten by test success.
    }
    fun mergeTestFields(current: ModelInfo, measured: ModelInfo): ModelInfo {
        require(current.modelId==measured.modelId)
        val abilities=if(measured.toolCall==null) current.abilities else current.abilities.filter{it!="TOOL"} + if(measured.toolCall==true)listOf("TOOL")else emptyList()
        return current.copy(available=measured.available,latencyMs=measured.latencyMs,lastError=measured.lastError,
            testedAt=measured.testedAt,abilities=abilities.distinct(),nonStreaming=measured.nonStreaming ?: current.nonStreaming,
            streaming=measured.streaming ?: current.streaming,toolCall=measured.toolCall ?: current.toolCall)
    }
}
