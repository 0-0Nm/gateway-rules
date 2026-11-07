package com.aqi.ccb.gateway

import com.aqi.ccb.data.ModelInfo
import com.aqi.ccb.application.ProviderWorkflows
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

private fun costObservation(input:Long=1_000_000,output:Long=500_000,cached:Long?=250_000,reasoning:Long?=100_000)=
    UsageObservation("reported",UsageCounts(input,output,cached,reasoning))
private fun calculate(currency:String?="USD",input:String?="2",output:String?="4",cached:String?="0.2",
                      observation:UsageObservation=costObservation(),sent:Boolean=true,complete:Boolean=true,status:Int?=200)=
    TokenCostEstimator.estimate(sent,complete,status,observation,currency,input,output,cached)
private fun pricedRecord(currency:String?="USD",inPrice:Double=2.0):UsageRequestRecord {
    val p=Provider("p","P","http://127.0.0.1")
    val m=Model("m",priceInPerM=inPrice,priceOutPerM=4.0,priceCurrency=currency,priceCachedInPerM=0.2)
    val r=UsageRequestCapture("r-price001");val a=r.begin(p,m,"test-only-credential")!!
    a.observer.observe(JSONObject().put("usage",JSONObject().put("prompt_tokens",1_000_000).put("completion_tokens",500_000)
        .put("prompt_tokens_details",JSONObject().put("cached_tokens",250_000)).put("completion_tokens_details",JSONObject().put("reasoning_tokens",100_000))))
    a.finish(200,true,true);return r.finish("success")!!
}
private fun priceContext(root:File,name:String,model:JSONObject=ModelInfo("m",priceInPerM=2.0,priceOutPerM=4.0).toJson()):android.content.Context {
    val c=android.content.Context(File(root,name).apply{mkdirs()})
    ConfigStore.transact(c){it.providers.add(JSONObject().put("id","p").put("name","P").put("baseUrl","http://127.0.0.1")
        .put("models",JSONArray().put(model)))}
    return c
}
private fun rejectsPrice(fn:()->Unit){var rejected=false;try{fn()}catch(_:Exception){rejected=true};check(rejected)}

fun runPriceAudit(test:(String,()->Unit)->Unit,root:File) {
    test("cost_decimal_split_cache_and_output") {check(calculate()==TokenCostEstimate("USD","3.55","estimated"))}
    test("cost_reasoning_is_not_double_charged") {check(calculate(observation=costObservation(reasoning=0)).amount==calculate(observation=costObservation(reasoning=400_000)).amount)}
    test("cost_unknown_currency_is_not_inferred") {check(calculate(currency=null).status=="currency_unknown"&&calculate(currency="EUR").amount==null)}
    test("cost_missing_price_is_not_free") {check(calculate(input=null).status=="price_unknown"&&calculate(output=null).amount==null)}
    test("cost_explicit_zero_preserved") {check(calculate(input="0",output="0",cached="0").amount=="0")}
    test("cost_unknown_cache_split_is_not_guessed") {check(calculate(observation=costObservation(cached=null)).status=="cache_usage_unknown")}
    test("cost_missing_cache_price_is_not_guessed") {check(calculate(cached=null).status=="cache_price_unknown")}
    test("cost_no_cache_use_needs_no_cache_price") {check(calculate(cached=null,observation=costObservation(cached=0)).amount=="4")}
    test("cost_equal_tariffs_allow_unknown_cache_split") {check(calculate(cached="2.00",observation=costObservation(cached=null)).amount=="4")}
    test("cost_zero_input_no_cache_split_required") {check(calculate(cached=null,observation=costObservation(input=0,cached=null)).amount=="2")}
    test("cost_incomplete_invalid_unsent_are_not_estimated") {
        check(calculate(complete=false).status=="usage_unknown");check(calculate(status=503).amount==null)
        check(calculate(observation=UsageObservation("invalid",null)).amount==null);check(calculate(sent=false).status=="not_sent")
    }
    test("cost_tiny_nonzero_keeps_decimal_precision") {check(calculate(input="0.00000001",output="0",cached=null,observation=costObservation(input=1,output=0,cached=0,reasoning=0)).amount=="0.00000000000001")}
    test("cost_invalid_decimal_prices_fail_closed") {for(p in listOf("-1","NaN","Infinity","1e999999"))check(calculate(input=p).status=="invalid_price")}
    test("cost_ledger_separates_usd_and_cny") {
        val l=UsageLedger(File(root,"currencies"));l.append(pricedRecord("USD"));l.append(pricedRecord("CNY"));l.append(pricedRecord(null))
        val s=l.snapshot();check(s.estimatedCosts.associate{it.currency to it.amount}==mapOf("USD" to "3.55","CNY" to "3.55"))
        check(s.estimatedAttempts==2L&&s.unestimatedSentAttempts==1L&&s.json().isNull("cost")&&!s.json().getBoolean("budgetEnforced"))
    }
    test("cost_ledger_freezes_each_attempt_price") {
        val d=File(root,"frozen");val l=UsageLedger(d);val first=pricedRecord(inPrice=2.0);l.append(first);l.append(pricedRecord(inPrice=10.0))
        check(first.attempts.single().costEstimate?.amount=="3.55")
        check(l.snapshot().estimatedCosts.single().amount=="13.1")
        check(UsageLedger(d).snapshot().estimatedCosts==l.snapshot().estimatedCosts)
    }
    test("cost_legacy_rows_are_not_repriced") {
        val d=File(root,"legacy").apply{mkdirs()};val j=pricedRecord().json();val a=j.getJSONArray("attempts").getJSONObject(0);a.remove("tokenCostEstimate")
        File(d,"usage.jsonl").writeText(j.toString()+"\n");val s=UsageLedger(d).snapshot();check(s.requests==1&&s.estimatedCosts.isEmpty()&&s.unestimatedSentAttempts==1L)
    }
    test("cost_tampered_saved_estimate_is_rejected") {
        val d=File(root,"tamper").apply{mkdirs()};val j=pricedRecord().json();j.getJSONArray("attempts").getJSONObject(0).getJSONObject("tokenCostEstimate").put("amount","999")
        File(d,"usage.jsonl").writeText(j.toString()+"\n");val s=UsageLedger(d).snapshot();check(s.requests==0&&s.readErrors==1&&s.storageStatus=="partial")
    }
    test("price_currency_model_roundtrip") {
        val m=ModelInfo("m",priceCurrency="CNY",priceCachedInPerM=0.125,priceInPerM=1.0,priceOutPerM=2.0)
        check(ModelInfo.fromJson(m.toJson())==m);val runtime=Model.fromJson(ConfigStore.jsonToMap(m.toJson()))
        check(runtime.priceCurrency=="CNY"&&runtime.priceCachedInPerM==0.125)
    }
    test("price_legacy_currency_stays_unset") {
        val j=JSONObject().put("modelId","m").put("priceInPerM",1.0).put("priceOutPerM",2.0)
        check(ModelInfo.fromJson(j).priceCurrency==null&&Model.fromJson(ConfigStore.jsonToMap(j)).priceCurrency==null)
    }
    test("price_currency_patch_preserves_old_prices") {
        val c=priceContext(root,"patch");ConfigCommands.updateModel(c,"p","m",JSONObject().put("priceCurrency","USD").put("priceCachedInPerM",0.25))
        val m=ModelInfo.fromJson(ConfigStore.read(c).providers.single().getJSONArray("models").getJSONObject(0))
        check(m.priceInPerM==2.0&&m.priceOutPerM==4.0&&m.priceCurrency=="USD"&&m.priceCachedInPerM==0.25)
    }
    test("price_invalid_currency_patch_does_not_commit") {
        val c=priceContext(root,"reject-currency");val rev=ConfigStore.read(c).revision
        for(v in listOf<Any>("usd","EUR",1,true)){rejectsPrice{ConfigCommands.updateModel(c,"p","m",JSONObject().put("priceCurrency",v))};check(ConfigStore.read(c).revision==rev)}
    }
    test("price_invalid_cache_patch_does_not_commit") {
        val c=priceContext(root,"reject-cache");val rev=ConfigStore.read(c).revision
        for(v in listOf<Any>(-1,"0.1",true)){rejectsPrice{ConfigCommands.updateModel(c,"p","m",JSONObject().put("priceCachedInPerM",v))};check(ConfigStore.read(c).revision==rev)}
    }
    test("price_patch_can_explicitly_clear_currency") {
        val c=priceContext(root,"clear-currency",ModelInfo("m",priceCurrency="USD",priceCachedInPerM=0.1).toJson())
        ConfigCommands.updateModel(c,"p","m",JSONObject().put("priceCurrency",JSONObject.NULL).put("priceCachedInPerM",JSONObject.NULL))
        val m=ModelInfo.fromJson(ConfigStore.read(c).providers.single().getJSONArray("models").getJSONObject(0));check(m.priceCurrency==null&&m.priceCachedInPerM==null)
    }
    test("price_discovery_keeps_currency_and_cache_price") {
        val old=ModelInfo("m",priceCurrency="USD",priceCachedInPerM=0.1);check(ProviderWorkflows.mergeDiscovered(listOf(old),listOf("m","n")).first()==old)
    }
    fun cands(firstCurrency:String?,secondCurrency:String?):List<Cand> = listOf("a" to firstCurrency,"b" to secondCurrency).mapIndexed{i,(id,unit)->
        val model=Model("m",priceInPerM=if(i==0)5.0 else 1.0,priceOutPerM=if(i==0)5.0 else 1.0,priceCurrency=unit)
        Cand(Provider(id,id,"http://127.0.0.1",models=mapOf("m" to model)),model)
    }
    test("price_mixed_currencies_preserve_route_order") {
        val cs=cands("USD","CNY");val result=RuleEngine.opCheapestFirst(cs,RequestCtx("m",dryRun=true),emptyMap());check(result.map{it.p.id}==listOf("a","b")&&result.all{"currency_not_comparable" in it.why})
    }
    test("price_partially_unknown_currency_preserves_order") {
        val cs=cands("USD",null);check(RuleEngine.opCheapestFirst(cs,RequestCtx("m",dryRun=true),emptyMap()).map{it.p.id}==listOf("a","b"))
    }
    test("price_same_currency_still_sorts") {check(RuleEngine.opCheapestFirst(cands("USD","USD"),RequestCtx("m",dryRun=true),emptyMap()).map{it.p.id}==listOf("b","a"))}
    test("price_all_legacy_currency_keeps_legacy_sort") {
        val result=RuleEngine.opCheapestFirst(cands(null,null),RequestCtx("m",dryRun=true),emptyMap());check(result.map{it.p.id}==listOf("b","a")&&result.all{"legacy_currency_unset" in it.why})
    }

    test("price_legacy_zero_currency_patch_stays_unknown") {
        val c=priceContext(root,"legacy-zero",JSONObject().put("modelId","m").put("priceInPerM",0).put("priceOutPerM",0))
        ConfigCommands.updateModel(c,"p","m",JSONObject().put("priceCurrency","USD"))
        val item=ConfigStore.read(c).providers.single().getJSONArray("models").getJSONObject(0)
        check(item.isNull("priceInPerM")&&item.isNull("priceOutPerM"))
        check(!Model.fromJson(ConfigStore.jsonToMap(item)).hasPrice)
    }
    test("price_real_json_keeps_response_bytes_and_estimates") {
        PriceWire(root,"json").use { f -> val response=f.call();check(response.second.contentEquals(f.expected))
            check(response.first==200);val s=f.ledger.snapshot();check(s.estimatedCosts==listOf(CurrencyEstimate("USD","3.55",1)))
            check(s.requests==1&&s.successful==1&&s.estimatedAttempts==1L&&!s.json().getBoolean("budgetEnforced")) }
    }
    test("price_real_sse_uses_one_cumulative_estimate") {
        PriceWire(root,"sse").use { f -> check(f.call().second.contentEquals(f.expected))
            val s=f.ledger.snapshot();check(s.estimatedCosts==listOf(CurrencyEstimate("USD","3.55",1))) }
    }
    test("price_real_incomplete_response_remains_unestimated") {
        PriceWire(root,"cut").use { f -> check(f.call().second.contentEquals(f.expected))
            val s=f.ledger.snapshot();check(s.failed==1&&s.estimatedAttempts==0L&&s.unestimatedSentAttempts==1L&&s.estimatedCosts.isEmpty()) }
    }
    test("price_real_legacy_currency_not_inferred") {
        PriceWire(root,"legacy").use { f -> check(f.call().first==200);check(f.ledger.snapshot().unestimatedSentAttempts==1L&&f.ledger.snapshot().estimatedCosts.isEmpty()) }
    }
    test("price_real_cache_omission_not_assumed_zero") {
        PriceWire(root,"no-cache-count").use { f -> check(f.call().first==200);check(f.ledger.snapshot().unestimatedSentAttempts==1L&&f.ledger.snapshot().estimatedCosts.isEmpty()) }
    }
    test("price_real_failover_keeps_unknown_attempt_separate") {
        PriceWire(root,"fallback").use { f -> check(f.call().first==200);val s=f.ledger.snapshot()
            check(s.requests==1&&s.successful==1&&s.attempts==2L&&s.estimatedAttempts==1L&&s.unestimatedSentAttempts==1L)
            check(s.estimatedCosts==listOf(CurrencyEstimate("USD","3.55",1))) }
    }
    test("price_real_config_reload_does_not_reprice_inflight") {
        PriceWire(root,"held").use { f ->
            val task=java.util.concurrent.FutureTask<Pair<Int,ByteArray>> { f.call() }
            val worker=Thread(task,"price-audit-client").apply { isDaemon=true;start() }
            try {
                check(f.entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
                f.reprice("CNY",10.0);f.release.countDown();check(task.get(5,java.util.concurrent.TimeUnit.SECONDS).first==200)
                check(f.ledger.snapshot().estimatedCosts==listOf(CurrencyEstimate("USD","3.55",1)))
                check(f.call().first==200)
                check(f.ledger.snapshot().estimatedCosts.associate { it.currency to it.amount }==mapOf("USD" to "3.55","CNY" to "9.55"))
                check(UsageLedger(f.storeDir).snapshot().estimatedCosts==f.ledger.snapshot().estimatedCosts)
            } finally { f.release.countDown();worker.join(1000) }
        }
    }
    test("price_real_mcp_snapshot_matches_runtime_and_disk") {
        PriceWire(root,"json-mcp").use { f ->
            val old=GatewayService.current
            try {
                GatewayService.current=f.gateway;check(f.call().first==200)
                val j=JSONObject(com.aqi.ccb.data.McpBridge(f.context).debugTool("gw_debug_snapshot","{}"))
                val totals=j.getJSONObject("usage").getJSONArray("estimatedCosts")
                check(totals.length()==1&&totals.getJSONObject(0).getString("amount")=="3.55")
                check(j.getJSONObject("runtime").getJSONObject("usage").getJSONArray("estimatedCosts").toString()==totals.toString())
                check(!j.getJSONObject("usage").getBoolean("budgetEnforced"))
            } finally { GatewayService.current=old }
        }
    }
    test("price_real_ledger_retains_no_body_or_secret") {
        PriceWire(root,"privacy").use { f -> check(f.call().first==200)
            val text=File(f.storeDir,"usage.jsonl").readText()
            check(!text.contains("PRIVATE_PRICE_PROMPT")&&!text.contains("PRIVATE_PRICE_RESPONSE")&&!text.contains("PRIVATE_PRICE_KEY"))
            val a=JSONObject(text.trim()).getJSONArray("attempts").getJSONObject(0)
            check(a.getString("currency")=="USD"&&a.getString("priceInputPerM")=="2"&&a.getString("priceCachedInputPerM")=="0.2")
            check(a.getJSONObject("tokenCostEstimate").getString("amount")=="3.55") }
    }
}

/** End-to-end fixtures: real gateway transport, temporary private storage, no provider network. */
private class PriceWire(root:File, private val mode:String):AutoCloseable {
    val context=android.content.Context(File(root,"wire-"+mode).apply { mkdirs() })
    val storeDir=File(context.filesDir,"usage-v1")
    val ledger=UsageLedgerStore.open(storeDir)
    val entered=java.util.concurrent.CountDownLatch(1)
    val release=java.util.concurrent.CountDownLatch(1)
    private val hits=java.util.concurrent.atomic.AtomicInteger()
    private val pool=java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon=true } }
    private val up=com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
    private val policy=RatePolicy(ratePerMin=0.0,minIntervalMs=0.0,jitterMs=0.0,maxInflight=4,softRateSec=0.0)
    private val model=Model("m",priceInPerM=2.0,priceOutPerM=4.0,priceCurrency=if(mode=="legacy")null else "USD",priceCachedInPerM=0.2)
    private fun provider(id:String,path:String,m:Model)=Provider(id,id,"http://127.0.0.1:${up.address.port}/$path",ratePolicy=policy,models=mapOf("m" to m),apiKeyRef="price-fixture")
    private var providers=if(mode=="fallback")listOf(provider("a","bad",model),provider("b","good",model)) else listOf(provider("p","good",model))
    private val streamed=mode in setOf("sse","cut")
    private val usage=JSONObject().put("prompt_tokens",1_000_000).put("completion_tokens",500_000)
        .put("total_tokens",1_500_000).put("completion_tokens_details",JSONObject().put("reasoning_tokens",100_000)).apply {
            if(mode!="no-cache-count")put("prompt_tokens_details",JSONObject().put("cached_tokens",250_000))
        }
    val expected=if(streamed) {
        val event="data: "+JSONObject().put("choices",JSONArray()).put("usage",usage).toString()+"\n\n"
        ("data: {\"choices\":[{\"delta\":{\"content\":\"PRIVATE_PRICE_RESPONSE\"}}]}\n\n"+event+event+if(mode=="sse")"data: [DONE]\n\n" else "").toByteArray()
    } else JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("content","PRIVATE_PRICE_RESPONSE")))).put("usage",usage).toString().toByteArray()
    val gateway=GatewayServer(0,providers,listOf(RuleSpec("r")),RateLimiter(providers.associate { it.id to policy }),"price-local-only",usageLedger=ledger)
    init {
        up.executor=pool
        up.createContext("/") { e -> try {
            e.requestBody.use { it.readBytes() }
            if(mode=="held"&&hits.getAndIncrement()==0) { entered.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS)) }
            val bad=e.requestURI.path.startsWith("/bad")
            val bytes=if(bad)"PRIVATE_PRICE_REJECTION".toByteArray() else expected
            e.responseHeaders.set("Content-Type",if(streamed&&!bad)"text/event-stream" else "application/json")
            e.sendResponseHeaders(if(bad)429 else 200,bytes.size.toLong())
            e.responseBody.use { dst -> if(streamed)bytes.forEach { dst.write(it.toInt());dst.flush() } else dst.write(bytes) }
        } finally { e.close() } }
        up.start();gateway.update(providers,listOf(RuleSpec("r")),revision=1,keys=mapOf("price-fixture" to "PRIVATE_PRICE_KEY"));check(gateway.start())
    }
    fun reprice(currency:String,input:Double) {
        providers=listOf(provider("p","good",model.copy(priceCurrency=currency,priceInPerM=input)))
        gateway.update(providers,listOf(RuleSpec("r")),revision=2,keys=mapOf("price-fixture" to "PRIVATE_PRICE_KEY"))
    }
    fun call():Pair<Int,ByteArray> {
        val c=java.net.URL("http://127.0.0.1:${gateway.listeningPortPublic()}/v1/chat/completions").openConnection() as java.net.HttpURLConnection
        try {
            c.requestMethod="POST";c.doOutput=true;c.connectTimeout=3000;c.readTimeout=15000
            c.setRequestProperty("Authorization","Bearer price-local-only");c.setRequestProperty("Content-Type","application/json")
            val body=JSONObject().put("model","m").put("stream",streamed).put("messages",JSONArray().put(JSONObject().put("role","user").put("content","PRIVATE_PRICE_PROMPT"))).toString().toByteArray()
            c.setFixedLengthStreamingMode(body.size);c.outputStream.use { it.write(body) }
            val code=c.responseCode;val bytes=(if(code>=400)c.errorStream else c.inputStream).use { it.readBytes() }
            val until=System.nanoTime()+5_000_000_000L
            while(gateway.inflightPublic()!=0&&System.nanoTime()<until)Thread.sleep(1)
            check(gateway.inflightPublic()==0) { "Request terminal state not reached" }
            return code to bytes
        } finally { c.disconnect() }
    }
    override fun close(){release.countDown();gateway.stop();up.stop(0);pool.shutdownNow()}
}
