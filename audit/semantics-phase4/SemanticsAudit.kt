package com.aqi.ccb.gateway

import com.aqi.ccb.data.*
import org.json.*
import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import com.sun.net.httpserver.HttpServer

private var count=0
private var failures=0
private fun test(name:String, fn:()->Unit){count++;try{fn();println("PASS|$name")}catch(t:Throwable){failures++;println("FAIL|$name|${t.javaClass.simpleName}:${t.message}");t.printStackTrace()}}
private fun rejected(fn:()->Unit){var caught=false;try{fn()}catch(_:IllegalArgumentException){caught=true}catch(_:ConfigStore.ConfigException){caught=true};check(caught)}
private fun runtime(o:JSONObject)=Model.fromJson(ConfigStore.jsonToMap(o))
private fun p(id:String,model:Model=Model("m"),tier:Tier=Tier.paid,lat:Double=1.0)=Provider(id,id,"http://127.0.0.1:1",models=mapOf(model.modelId to model),tier=tier,latEwma=lat)
private fun candidates()=listOf(p("canary"),p("stable1"),p("stable2")).map{Cand(it,it.models.getValue("m"))}
private fun context(root:File,name:String)=android.content.Context(File(root,name).apply{mkdirs()})
private fun seed(ctx:android.content.Context, model:JSONObject=JSONObject("""{"modelId":"m","priceInPerM":0,"priceOutPerM":0}""")){
    ConfigStore.transact(ctx){s->s.providers.add(JSONObject().put("id","p").put("name","P").put("baseUrl","http://127.0.0.1:1").put("models",JSONArray().put(model)))}
}
private fun stored(ctx:android.content.Context)=ConfigStore.read(ctx).providers[0].getJSONArray("models").getJSONObject(0)
private val content="""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
private val delta="data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"
private val done="data: [DONE]\n\n"
private fun tool(fn:String="get_weather",args:String="{\"city\":\"Beijing\"}",legacy:Boolean=false):String {
    val function=JSONObject().put("name",fn).put("arguments",args)
    val msg=JSONObject().put("role","assistant").put("content",JSONObject.NULL)
    if(legacy)msg.put("function_call",function)
    else msg.put("tool_calls",JSONArray().put(JSONObject().put("id","call_1").put("type","function").put("function",function)))
    return JSONObject().put("choices",JSONArray().put(JSONObject().put("message",msg))).toString()
}
private class ProbeServer(val mode:String):AutoCloseable {
    val s=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    val pool=Executors.newCachedThreadPool{r->Thread(r).apply{isDaemon=true}}
    val hits=AtomicInteger();val redirects=AtomicInteger()
    init {
        s.executor=pool
        s.createContext("/sink"){e->redirects.incrementAndGet();e.sendResponseHeaders(200,0);e.close()}
        s.createContext("/chat/completions"){e->
            hits.incrementAndGet()
            try {
                val req=JSONObject(e.requestBody.use{it.readBytes().toString(Charsets.UTF_8)})
                val stream=req.optBoolean("stream");val tools=req.has("tools")
                var code=200;var mime=if(stream)"text/event-stream" else "application/json"
                var response=if(stream)delta+done else if(tools)tool() else content
                if(!stream && !tools)response=when(mode){
                    "first_keyword"->"""{"metadata":"choices","content":"ok"}"""
                    "first_empty"->"""{"choices":[]}"""
                    "first_error"->"""{"error":{"message":"private"},"choices":[{"message":{"content":"ok"}}]}"""
                    "first_role"->"""{"choices":[{"message":{"role":"user","content":"ok"}}]}"""
                    "first_array_text"->"""{"choices":[{"message":{"content":[{"type":"text","text":"ok"}]}}]}"""
                    "first_trailing"->content+" {}"
                    else->response
                }
                if(stream)when(mode){
                    "done_only"->response=done
                    "role_only"->response="data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n"+done
                    "no_done"->response=delta
                    "sse_error"->response=delta+"data: {\"error\":{\"message\":\"private\"}}\n\n"+done
                    "sse_invalid"->response="data: broken\n\n"+done
                    "wrong_mime"->mime="application/json"
                    "after_done"->response=done+delta
                    "sse_redirect"->{code=307;e.responseHeaders.set("Location","http://127.0.0.1:${s.address.port}/sink")}
                    "sse_500"->code=500
                    "sse_multiline"->response="data: {\r\ndata: \"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\r\n\r\n"+done
                    "sse_empty_content"->response="data: {\"choices\":[{\"delta\":{\"content\":\"\"}}]}\n\n"+done
                }
                if(tools)response=when(mode){
                    "tool_null"->"""{"choices":[{"message":{"tool_calls":null,"function_call":null}}]}"""
                    "tool_empty"->"""{"choices":[{"message":{"tool_calls":[]}}]}"""
                    "tool_keyword"->"""{"choices":[{"message":{"content":"tool_calls function_call get_weather"}}]}"""
                    "tool_wrong_function"->tool("other")
                    "tool_invalid_args"->tool(args="broken")
                    "tool_missing_city"->tool(args="{}")
                    "tool_numeric_city"->tool(args="{\"city\":4}")
                    "tool_blank_city"->tool(args="{\"city\":\" \"}")
                    "tool_missing_id"->tool().replace("call_1","")
                    "tool_legacy"->tool(legacy=true)
                    else->response
                }
                val bytes=response.toByteArray();e.responseHeaders.set("Content-Type",mime)
                if(stream&&mode=="sse_timeout"){
                    e.sendResponseHeaders(200,200);e.responseBody.write("data: ".toByteArray());e.responseBody.flush();Thread.sleep(500)
                }else{e.sendResponseHeaders(code,bytes.size.toLong());e.responseBody.use{it.write(bytes)}}
            }catch(_:Exception){}finally{e.close()}
        }
        s.start()
    }
    fun probe()=ModelProbe.probe("http://127.0.0.1:${s.address.port}","isolated-test-token","m",if(mode=="sse_timeout")100 else 1500)
    override fun close(){s.stop(0);pool.shutdownNow()}
}
fun main(args:Array<String>){
    val root=File(args[0]);GateLog.init(File(root,"logs"));ConfigStore.publisher=null
    test("default_tier_constructor_parser_ui_agree"){
        check(p("p").tier==Tier.paid);check(Provider.fromJson(mapOf("id" to "p","baseUrl" to "http://127.0.0.1:1")).tier==Tier.paid)
        check(ProviderDraft().tier=="paid");check(ProviderDraft.fromJson(JSONObject()).tier=="paid")
    }
    test("explicit_free_tier_preserved"){
        check(Provider.fromJson(mapOf("id" to "p","baseUrl" to "http://127.0.0.1:1","tier" to "free")).tier==Tier.free)
        check(ProviderDraft.fromJson(JSONObject().put("tier","free")).tier=="free")
    }
    test("default_prices_unknown_not_zero"){check(!Model("m").hasPrice);check(!ModelInfo("m").hasPrice);check(Model("m").priceInPerM==null)}
    test("explicit_zero_constructor_is_priced"){check(Model("m",priceInPerM=0.0,priceOutPerM=0.0).hasPrice);check(ModelInfo("m",priceInPerM=0.0,priceOutPerM=0.0).hasPrice)}
    test("legacy_double_zero_remains_unknown"){
        val j=JSONObject("""{"modelId":"m","priceInPerM":0,"priceOutPerM":0}""");check(!runtime(j).hasPrice);check(!ModelInfo.fromJson(j).hasPrice)
    }
    test("versioned_double_zero_roundtrips_as_free"){
        val j=ModelInfo("m",priceInPerM=0.0,priceOutPerM=0.0).toJson();check(j.getInt("priceVersion")==1);check(runtime(j).hasPrice);check(runtime(j).blendedPrice==0.0);check(ModelInfo.fromJson(j).hasPrice)
    }
    test("legacy_positive_price_preserves_zero_other_side"){
        val j=JSONObject("""{"modelId":"m","priceInPerM":2}""");check(runtime(j).blendedPrice==1.0);check(ModelInfo.fromJson(j).blendedPrice==1.0)
    }
    test("partial_versioned_price_not_assumed_complete"){
        val j=JSONObject("""{"modelId":"m","priceVersion":1,"priceInPerM":2}""");check(!runtime(j).hasPrice);check(!ModelInfo.fromJson(j).hasPrice)
    }
    test("unknown_prices_roundtrip_as_null"){
        val j=ModelInfo("m").toJson();check(j.isNull("priceInPerM")&&j.isNull("priceOutPerM"));check(!runtime(j).hasPrice);check(!ModelInfo.fromJson(j).hasPrice)
    }
    test("price_order_free_paid_unknown_stable"){
        val models=listOf(Model("u1"),Model("paid",priceInPerM=2.0,priceOutPerM=2.0),Model("u2"),Model("free",priceInPerM=0.0,priceOutPerM=0.0))
        val cs=models.map{Cand(p(it.modelId,it),it)};check(RuleEngine.opCheapestFirst(cs,RequestCtx("m"),emptyMap()).map{it.p.id}==listOf("free","paid","u1","u2"))
    }
    test("unknown_price_order_is_unchanged"){
        val cs=listOf("z","a","x").map{Cand(p(it),Model("m"))};check(RuleEngine.opCheapestFirst(cs,RequestCtx("m"),emptyMap()).map{it.p.id}==listOf("z","a","x"))
    }
    test("huge_finite_price_blend_does_not_overflow"){check(Model("m",priceInPerM=Double.MAX_VALUE,priceOutPerM=Double.MAX_VALUE).blendedPrice.isFinite())}
    test("price_editor_empty_unknown_zero_explicit"){check(ModelPricing.parseInput("")==null);check(ModelPricing.parseInput("0")==0.0);check(ModelPricing.parseInput("0.25")==0.25)}
    for(x in listOf("-1","NaN","Infinity","not-a-price"))test("invalid_editor_price_$x"){rejected{ModelPricing.parseInput(x)}}
    test("price_roundtrip_property_matrix"){
        val values=listOf<Double?>(null,0.0,0.25,100.0)
        for(a in values)for(b in values){val j=ModelInfo("m",priceInPerM=a,priceOutPerM=b).toJson();val m=runtime(j);val ui=ModelInfo.fromJson(j);check(m.priceInPerM==a&&m.priceOutPerM==b&&ui.priceInPerM==a&&ui.priceOutPerM==b)}
    }
    test("legacy_read_does_not_write_or_infer_free"){
        val c=context(root,"legacy-read");seed(c);val f=File(c.filesDir,"config-state-v1.json");val before=f.readBytes()
        check(!ProviderRepo.list(c)[0].models[0].hasPrice);check(!ProviderRepo.runtimeProviders(c)[0].models.getValue("m").hasPrice);check(f.readBytes().contentEquals(before))
    }
    test("price_patch_migrates_without_inventing_other_zero"){
        val c=context(root,"partial");seed(c);ConfigCommands.updateModel(c,"p","m",JSONObject().put("priceInPerM",0));val j=stored(c)
        check(j.getInt("priceVersion")==1&&j.getDouble("priceInPerM")==0.0&&j.isNull("priceOutPerM"));check(!runtime(j).hasPrice)
        ConfigCommands.updateModel(c,"p","m",JSONObject().put("priceOutPerM",0));check(runtime(stored(c)).hasPrice)
    }
    test("null_price_patch_clears_only_requested_field"){
        val c=context(root,"clear");seed(c,ModelInfo("m",priceInPerM=2.0,priceOutPerM=3.0).toJson())
        ConfigCommands.updateModel(c,"p","m",JSONObject().put("priceInPerM",JSONObject.NULL));val j=stored(c);check(j.isNull("priceInPerM")&&j.getDouble("priceOutPerM")==3.0&&!runtime(j).hasPrice)
    }
    test("invalid_price_patch_rejected_without_revision_change"){
        val c=context(root,"invalid-price");seed(c);val rev=ConfigStore.read(c).revision
        rejected{ConfigCommands.updateModel(c,"p","m",JSONObject().put("priceInPerM",-1))};check(ConfigStore.read(c).revision==rev)
    }
    test("versioned_price_ui_save_preserves_free_and_unknown"){
        val c=context(root,"ui-save");seed(c,ModelInfo("m",priceInPerM=0.0,priceOutPerM=0.0).toJson());ProviderRepo.save(c,ProviderRepo.list(c)[0].copy(name="renamed"));check(runtime(stored(c)).hasPrice)
        val c2=context(root,"ui-unknown");seed(c2);ProviderRepo.save(c2,ProviderRepo.list(c2)[0].copy(name="renamed"));check(!runtime(stored(c2)).hasPrice)
    }
    test("unsupported_price_version_rejected"){
        val c=context(root,"bad-version");rejected{seed(c,JSONObject("""{"modelId":"m","priceVersion":2,"priceInPerM":0,"priceOutPerM":0}"""))}
    }
    test("modalities_empty_and_image_roundtrip"){
        for(xs in listOf(emptyList<String>(),listOf("IMAGE"),listOf("TEXT","IMAGE"))){val j=ModelInfo("m",inputModalities=xs,outputModalities=xs).toJson();check(runtime(j).inputModalities.map{it.name}==xs&&runtime(j).outputModalities.map{it.name}==xs)}
    }
    test("absent_modalities_keep_text_default"){check(Model.fromJson(mapOf("modelId" to "m")).inputModalities==listOf(Modality.TEXT))}
    test("unknown_modality_does_not_invent_text"){check(Model.fromJson(mapOf("modelId" to "m","inputModalities" to listOf("unknown"))).inputModalities.isEmpty())}
    test("explain_and_execution_share_sort_skip_chain"){
        val ps=listOf(p("free",tier=Tier.free,lat=200.0),p("fast",lat=1.0));val ctx=RequestCtx("m",ruleKey="r",dryRun=true)
        val steps=listOf(OpCall("fastest_first"),OpCall("tier_first"),OpCall("take",mapOf("n" to 1)))
        val explained=RuleEngine.explain(RuleSpec("r",select=steps),ps,ctx)
        check(explained["final"]==RuleEngine.applySelect(RuleEngine.buildCands(ps,ctx),ctx,steps).map{it.p.id})
        val trace=explained["select_trace"] as List<*>;check((trace[1] as Map<*,*>)["status"]=="skipped")
    }
    test("explain_round_robin_does_not_advance_state"){
        RuntimeState.rr["audit-rr"]=1;val rule=RuleSpec("audit-rr",select=listOf(OpCall("round_robin")));val ps=listOf(p("a"),p("b"));val ctx=RequestCtx("m",ruleKey=rule.id)
        val x=RuleEngine.explain(rule,ps,ctx);check(RuntimeState.rr[rule.id]==1)
        check(x["final"]==RuleEngine.applySelect(RuleEngine.buildCands(ps,ctx),ctx,rule.select).map{it.p.id});check(RuntimeState.rr[rule.id]==0)
    }
    test("explain_sticky_does_not_create_binding"){
        RuntimeState.prefixMap.clear();RuleEngine.explain(RuleSpec("r",select=listOf(OpCall("sticky_session"))),listOf(p("p")),RequestCtx("m",sessionId="new"));check(RuntimeState.prefixMap.isEmpty())
    }
    test("random_explain_explicitly_marks_sample"){check(RuleEngine.explain(RuleSpec("r",select=listOf(OpCall("random"))),listOf(p("p")),RequestCtx("m"))["previewOnly"]==true)}
    test("evaluate_does_not_mutate_input_candidate_trace"){
        val cs=candidates();RuleEngine.applySelect(cs,RequestCtx("m"),listOf(OpCall("fastest_first")));check(cs.all{it.why.isEmpty()})
    }
    for(percent in listOf(0,100))test("canary_${percent}_isolates_pool"){
        val ids=RuleEngine.opCanary(candidates(),RequestCtx("m"),mapOf("percent" to percent)).map{it.p.id};check(ids==if(percent==0)listOf("stable1","stable2")else listOf("canary"))
    }
    test("canary_stable_bucket_is_repeatable_and_distributed"){
        var hits=0
        for(i in 0 until 10000){val ctx=RequestCtx("m",sessionId="session-$i");val a=RuleEngine.opCanary(candidates(),ctx,mapOf("percent" to 20)).map{it.p.id};val b=RuleEngine.opCanary(candidates(),ctx,mapOf("percent" to 20)).map{it.p.id};check(a==b);if(a==listOf("canary"))hits++ else check(a==listOf("stable1","stable2"))}
        println("DETAIL|canary20_hits=$hits/10000");check(hits in 1500..2500)
    }
    test("canary_no_identity_middle_percentage_uses_stable"){check(RuleEngine.opCanary(candidates(),RequestCtx("m"),mapOf("percent" to 50)).none{it.p.id=="canary"})}
    test("canary_invalid_percentage_rejected"){rejected{RuleEngine.opCanary(candidates(),RequestCtx("m"),mapOf("percent" to 101))};rejected{RuleEngine.opCanary(candidates(),RequestCtx("m"),mapOf("percent" to 0.5))}}
    test("ab_min_hash_and_nonpositive_buckets"){
        check("polygenelubricants".hashCode()==Int.MIN_VALUE);check(RuleEngine.opAbSplit(candidates(),RequestCtx("m",sessionId="polygenelubricants"),mapOf("buckets" to 3)).size==3)
        rejected{RuleEngine.opAbSplit(candidates(),RequestCtx("m",sessionId="x"),mapOf("buckets" to 0))}
    }
    test("error_window_query_order_does_not_destroy_history"){
        val id="history";val now=System.currentTimeMillis();RuntimeState.errorWindow[id]=mutableListOf(now-120000,now-1000,now+100000)
        check(RuntimeState.errorRate(id,60000)==1);check(RuntimeState.errorRate(id,300000)==2);check(RuntimeState.errorRate(id,1000)<=1);check(RuntimeState.errorRate(id,300000)==2);check(RuntimeState.errorWindow[id]!!.size==3)
    }
    test("error_retention_bounded_at_write_not_query"){
        val id="retention";RuntimeState.errorWindow.remove(id);repeat(600){RuntimeState.noteError(id)};check(RuntimeState.errorWindow[id]!!.size==500);RuntimeState.errorRate(id,0);check(RuntimeState.errorWindow[id]!!.size==500)
    }
    test("round_robin_concurrent_advance_is_atomic"){
        RuntimeState.rr["concurrent"]=0;val buckets=(0..4).map{AtomicInteger()};val pool=Executors.newFixedThreadPool(8)
        try{(0..7).map{pool.submit{repeat(1000){buckets[RuntimeState.rrNext("concurrent",5,false)].incrementAndGet()}}}.forEach{it.get(5,TimeUnit.SECONDS)}}finally{pool.shutdownNow()}
        check(buckets.all{it.get()==1600})
    }
    for(mode in listOf("valid","tool_legacy","sse_multiline","first_array_text"))test("probe_positive_$mode"){
        ProbeServer(mode).use{f->val r=f.probe();check(r.nonStreaming&&r.streaming&&r.toolCall&&r.error==null){r.toString()};check(f.hits.get()==3)}
    }
    for(mode in listOf("first_keyword","first_empty","first_error","first_role","first_trailing"))test("probe_reject_$mode"){
        ProbeServer(mode).use{f->val r=f.probe();check(!r.nonStreaming&&!r.streaming&&!r.toolCall&&r.error!=null);check(f.hits.get()==1)}
    }
    for(mode in listOf("done_only","role_only","no_done","sse_error","sse_invalid","wrong_mime","after_done","sse_redirect","sse_500","sse_empty_content","sse_timeout"))test("probe_reject_stream_$mode"){
        ProbeServer(mode).use{f->val r=f.probe();check(r.nonStreaming&&!r.streaming&&r.toolCall){r.toString()};check(f.redirects.get()==0)}
    }
    for(mode in listOf("tool_null","tool_empty","tool_keyword","tool_wrong_function","tool_invalid_args","tool_missing_city","tool_numeric_city","tool_blank_city","tool_missing_id"))test("probe_reject_$mode"){
        ProbeServer(mode).use{f->val r=f.probe();check(r.nonStreaming&&r.streaming&&!r.toolCall){r.toString()}}
    }
    test("models_list_escapes_real_virtual_unicode_and_controls"){
        val real="quote\"\\汉字\n\t";val virtual="v\"\\😀";val model=Model(real)
        val provider=Provider("p","p","http://127.0.0.1:1",models=mapOf(real to model,"disabled" to Model("disabled",enabled=false)))
        val g=GatewayServer(0,listOf(provider),listOf(RuleSpec("default")),RateLimiter(),"isolated",listOf(VirtualModel(virtual)))
        val method=GatewayServer::class.java.getDeclaredMethod("handleModels",OutputStream::class.java,String::class.java);method.isAccessible=true
        val out=ByteArrayOutputStream();method.invoke(g,out,"serialize-audit");val response=out.toString("UTF-8");val payload=response.substringAfter("\r\n\r\n")
        val a=JSONObject(payload).getJSONArray("data");check(a.length()==2&&a.getJSONObject(0).getString("id")==virtual&&a.getJSONObject(1).getString("id")==real)
        val declared=Regex("Content-Length: ([0-9]+)").find(response)!!.groupValues[1].toInt();check(declared==payload.toByteArray().size)
    }
    test("price_display_zero_and_unknown_are_distinct"){
        check(ModelPricing.format(0.0)=="0");check(ModelPricing.format(null)=="");check(ModelPricing.format(0.00000001)=="0.00000001")
    }
    test("price_display_ignores_device_locale"){
        val previous=java.util.Locale.getDefault()
        try{java.util.Locale.setDefault(java.util.Locale.GERMANY);check(ModelPricing.format(1.25)=="1.25")}
        finally{java.util.Locale.setDefault(previous)}
    }
    test("price_draft_decimal_sequence_keeps_trailing_dot"){
        val steps=listOf("0","0.","0.2","0.25","")
        check(steps.map{ModelPricing.editorText(it)}==steps)
        check(ModelPricing.editorText(".")=="0.");check(ModelPricing.editorText(".5")=="0.5")
    }
    test("price_draft_does_not_silently_remove_signs_or_letters"){
        for(text in listOf("-1","+1","1,5","abc2","0..2","1e3","NaN"))rejected{ModelPricing.editorText(text)}
    }
    test("price_draft_rejects_numeric_overflow"){
        rejected{ModelPricing.editorText("9".repeat(400))}
    }
    test("price_display_roundtrip_preserves_numeric_value"){
        for(value in listOf(0.0,0.00000001,0.25,999.12345,Double.MAX_VALUE)){
            check(ModelPricing.parseInput(ModelPricing.format(value))==value)
        }
    }
    println("SUMMARY|cases=$count|passed=${count-failures}|failed=$failures")
    if(failures>0)kotlin.system.exitProcess(1)
}
