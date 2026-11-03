package com.aqi.ccb.gateway

import com.aqi.ccb.data.ModelInfo
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 批量模型测试 —— 逐个测延迟与可用性，结果逐模型回填。
 *
 * 与 rikka 的 ProviderConnectionTester 的差异：
 *   rikka 一次只测用户选中的一个模型（对话框里选 model 再测）
 *   这里仅测试已选模型（最多20个），默认2并发，并逐个回调测量结果。
 *
 * 测试策略（仍可能产生费用，不作零成本保证）：
 *   1 先试 GET /models/{id}（部分服务支持，最省）
 *   2 不支持则进行最多三阶段生成能力探测；可能计费，不自动重试。
 *
 * 结果写回 ModelInfo.available / latencyMs / lastError / testedAt。
 */
object BatchTester {

    data class Progress(
        val done: Int,
        val total: Int,
        val currentModel: String = "",
        val finished: Boolean = false,
    )

    /**
     * 批量测试。
     * @param onItem 每个模型测完后回调（主线程由调用方切）
     * @param onProgress 进度回调
     */
    class Handle internal constructor() {
        private val cancelled=java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile internal var executor:java.util.concurrent.ExecutorService?=null
        fun cancel(){cancelled.set(true);executor?.shutdownNow()}
        fun isCancelled()=cancelled.get()
    }
    fun testAll(
        baseUrl:String,apiKey:String,models:List<ModelInfo>,concurrency:Int=2,timeoutMs:Int=15000,
        onItem:(ModelInfo)->Unit,onProgress:(Progress)->Unit,
    ):Handle {
        val handle=Handle()
        val selected=com.aqi.ccb.application.ProviderWorkflows.selectedForTest(models)
        if(selected.isEmpty()){onProgress(Progress(0,0,finished=true));return handle}
        val done=AtomicInteger();val next=AtomicInteger();val workers=minOf(concurrency.coerceIn(1,4),selected.size)
        val pool=Executors.newFixedThreadPool(workers);handle.executor=pool
        repeat(workers){pool.execute{
            while(!handle.isCancelled() && !Thread.currentThread().isInterrupted){
                val i=next.getAndIncrement();if(i>=selected.size)break
                val tested=testOne(baseUrl,apiKey,selected[i],timeoutMs,cancelled={handle.isCancelled()})
                if(handle.isCancelled() || Thread.currentThread().isInterrupted)break
                try{onItem(tested)}catch(_:Exception){handle.cancel();break}
                val d=done.incrementAndGet()
                try{onProgress(Progress(d,selected.size,selected[i].modelId,d==selected.size))}catch(_:Exception){handle.cancel();break}
            }
        }}
        pool.shutdown();return handle
    }

    /** 测单个模型：延迟 + 可用性 */
    /**
     * 测试单个模型。
     *
     * 修复：原来只写 available / latencyMs / lastError / testedAt，
     * 从不写 abilities —— 全仓 grep 确认「abilities =」只在反序列化里出现，
     * 没有任何写入点。后果是规则里的 require(tools) 永远匹配不到，
     * 因为没有任何模型带 TOOL 能力位。
     *
     * 现在两级策略：
     *   1 GET /models/{id} —— 仅模型查询，不证明生成能力。只证明存在，测不出能力。
     *   2 ModelProbe.probe() —— 三件套（非流式/流式/工具调用），
     *     结果同时写回 available / latencyMs 与 abilities。
     *
     * @param deep true 时强制走三件套（会消耗少量额度，用于首次配置）
     */
    fun testOne(
        baseUrl: String,
        apiKey: String,
        model: ModelInfo,
        timeoutMs: Int = 15000,
        deep: Boolean = false,
        cancelled: () -> Boolean = { false },
    ): ModelInfo {
        if(cancelled() || Thread.currentThread().isInterrupted) return model.copy(lastError="test_cancelled")
        val base = baseUrl.trimEnd('/')
        val t0 = System.currentTimeMillis()

        // ---- 策略 1：GET /models/{id}（仅模型查询，不证明生成能力）----
        if (!deep) {
            val byGet = tryGetModel(base, apiKey, model.modelId, timeoutMs)
            if (byGet == 0) {
                val lat = System.currentTimeMillis() - t0
                return model.copy(
                    available = true,
                    latencyMs = lat,
                    lastError = "",
                    testedAt = System.currentTimeMillis(),
                    nonStreaming = null, streaming = null, toolCall = null, // a directory GET is not a capability observation
                )
            }
        }

        if(cancelled() || Thread.currentThread().isInterrupted) return model.copy(lastError="test_cancelled")
        val result = try { ModelProbe.probe(base,apiKey,model.modelId,timeoutMs,cancelled) }
            catch(e:Exception) { return model.copy(available=false,latencyMs=-1,lastError="probe_"+e.javaClass.simpleName,testedAt=System.currentTimeMillis()) }
        return com.aqi.ccb.application.ProviderWorkflows.applyCapabilityResult(model,result)
    }

    /** 返回 0 = 成功；否则 HTTP 码或 -1 */
    private fun tryGetModel(base: String, key: String, modelId: String, timeoutMs: Int): Int {
        val r = HttpUtil.get("$base/models/" + java.net.URLEncoder.encode(modelId,"UTF-8").replace("+","%20"), key, timeoutMs)
        return when {
            r.error != null -> -1
            r.ok -> 0
            else -> r.code
        }
    }

}
