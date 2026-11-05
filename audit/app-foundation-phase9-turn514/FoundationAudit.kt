package com.aqi.ccb.gateway

import com.aqi.ccb.data.*
import com.aqi.ccb.application.*
import org.json.*
import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import com.sun.net.httpserver.HttpServer

private var total=0
private var failed=0
private fun test(name:String, fn:()->Unit){total++;try{fn();println("PASS|$name")}catch(t:Throwable){failed++;println("FAIL|$name|${t.javaClass.simpleName}:${t.message}");t.printStackTrace()}finally{Thread.interrupted()}}
private fun reject(fn:()->Unit){var ok=false;try{fn()}catch(_:IllegalArgumentException){ok=true}catch(_:CancellationException){ok=true};check(ok)}
private class Fake:AutoCloseable{
 val s=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
 val pool=Executors.newCachedThreadPool{r->Thread(r).apply{isDaemon=true}}
 val gets=AtomicInteger();val posts=AtomicInteger();val mode=AtomicReference("normal")
 val cancel=AtomicBoolean();val entered=CountDownLatch(1);val release=CountDownLatch(1)
 init{s.executor=pool;s.createContext("/"){e->try{
  var code=200;var type="application/json";var text="{}"
  if(e.requestMethod=="GET"){
   gets.incrementAndGet();val mode=mode.get()
   if(mode=="blockGet"){entered.countDown();check(release.await(3,TimeUnit.SECONDS))}
   if(e.requestURI.path.endsWith("/models")){
    text=if(mode=="modelsByName")"""{"models":[{"name":"m"},{"name":"m"},{"name":"n"}]}""" else """{"data":[{"id":"m"},{"id":"n"}]}"""
    if(mode=="cancelDiscovery"){cancel.set(true);text="{}";code=404}
   }else{text="""{"id":"m"}"""}
  }else{
   posts.incrementAndGet();val body=JSONObject(e.requestBody.use{it.readBytes().toString(Charsets.UTF_8)})
   if(mode.get()=="cancelFirstPost")cancel.set(true)
   if(body.optBoolean("stream")){type="text/event-stream";text="data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"}
   else if(body.has("tools"))text="""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Beijing\"}"}}]}}]}"""
   else text="""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
  }
  val bytes=text.toByteArray();e.responseHeaders.set("Content-Type",type);e.sendResponseHeaders(code,bytes.size.toLong());e.responseBody.use{it.write(bytes)}
 }catch(_:Exception){}finally{e.close()}};s.start()}
 val base get()="http://127.0.0.1:${s.address.port}/v1"
 override fun close(){release.countDown();s.stop(0);pool.shutdownNow()}
}
private fun ctx(root:File,name:String)=android.content.Context(File(root,name).apply{mkdirs()})
private fun seeded(c:android.content.Context,url:String,count:Int=1,enabled:Boolean=true){
 ConfigStore.transact(c){s->s.providers.add(JSONObject().put("id","p").put("name","P").put("baseUrl",url).put("models",JSONArray((1..count).map{ModelInfo(if(count==1)"m" else "m$it",enabled=enabled).toJson()})))}
}
private fun rpc(c:android.content.Context,args:JSONObject,name:String="gw_test_models"):JSONObject{
 val server=McpServer(0,0,"fixture-control-token"){McpBridge(c)}
 val m=McpServer::class.java.getDeclaredMethod("handleRpc",String::class.java);m.isAccessible=true
 val text=JSONObject().put("jsonrpc","2.0").put("id",1).put("method","tools/call").put("params",JSONObject().put("name",name).put("arguments",args)).toString()
 return JSONObject(m.invoke(server,text) as String).getJSONObject("result")
}
fun main(args:Array<String>){
 val root=File(args[0]);GateLog.init(File(root,"logs"));ConfigStore.publisher=null
 test("old_task_cannot_write_same_model_in_different_provider"){
  val g=ProviderTaskGate();val a=g.begin("a");val b=g.begin("b");check(!g.accepts(a,"b")&&g.accepts(b,"b")&&a.isCancelled())
 }
 test("reopen_same_provider_does_not_accept_old_session"){
  val g=ProviderTaskGate();val a=g.begin("p");g.invalidate();val b=g.begin("p");check(!g.accepts(a,"p")&&g.accepts(b,"p"))
 }
 test("new_operation_invalidates_prior_operation_same_editor"){
  val g=ProviderTaskGate();val a=g.begin("p");val b=g.begin("p");check(!g.accepts(a,"p")&&g.accepts(b,"p"))
 }
 test("invalidated_connection_task_cannot_complete_new_task"){
  val g=ProviderTaskGate();val a=g.begin("p");g.invalidate();val b=g.begin("p");check(!g.complete(a,"p")&&g.accepts(b,"p"))
 }
 test("cancelled_ticket_rejects_late_callback"){
  val g=ProviderTaskGate();val a=g.begin("p");g.invalidate();check(a.isCancelled()&&!g.accepts(a,"p"))
 }
 test("task_completion_is_one_time"){
  val g=ProviderTaskGate();val t=g.begin("p");check(g.complete(t,"p"));check(!g.complete(t,"p")&&!g.accepts(t,"p"))
 }
 test("foreign_activity_ticket_rejected"){
  val a=ProviderTaskGate();val b=ProviderTaskGate();val t=a.begin("p");check(!b.accepts(t,"p")&&!b.complete(t,"p"));check(a.accepts(t,"p"))
 }
 test("task_does_not_accept_changed_provider_identity"){
  val g=ProviderTaskGate();val t=g.begin("p");check(!g.accepts(t,"renamed-new-id"));check(!g.complete(t,"renamed-new-id"))
 }
 test("blank_editor_identity_rejected"){reject{ProviderTaskGate().begin("")}}
 test("concurrent_task_starts_leave_only_latest_ticket_valid"){
  val g=ProviderTaskGate();val tickets=ConcurrentLinkedQueue<ProviderTaskGate.Ticket>();val ts=(1..24).map{Thread{tickets.add(g.begin("p"))}.apply{start()}};ts.forEach{it.join()};check(tickets.count{g.accepts(it,"p")}==1)
 }
 test("model_discovery_never_auto_enables_new_models"){
  val result=ProviderWorkflows.mergeDiscovered(emptyList(),listOf("m","n"));check(result.size==2&&result.none{it.enabled})
 }
 test("discovery_preserves_existing_user_fields"){
  val old=ModelInfo("m",enabled=true,displayName="User name",priceInPerM=2.0,priceOutPerM=3.0,abilities=listOf("VISION"));val r=ProviderWorkflows.mergeDiscovered(listOf(old),listOf("m","n"));check(r[0]==old&&!r[1].enabled)
 }
 test("discovery_deduplicates_and_removes_blank_ids"){check(ProviderWorkflows.mergeDiscovered(emptyList(),listOf("m","","m","n")).map{it.modelId}==listOf("m","n"))}
 test("selected_test_cap_excludes_disabled_and_preserves_order"){
  val models=(1..30).map{ModelInfo("m$it",enabled=it%2==0)};check(ProviderWorkflows.selectedForTest(models).map{it.modelId}==(1..30).filter{it%2==0}.map{"m$it"})
 }
 test("selected_test_cap_is20_not_every_imported_model"){check(ProviderWorkflows.selectedForTest((1..100).map{ModelInfo("m$it")}).size==20)}
 test("selected_test_deduplicates_ids"){check(ProviderWorkflows.selectedForTest(listOf(ModelInfo("m"),ModelInfo("m"))).size==1)}
 test("selected_test_stops_reading_after_budget"){
  var reads=0
  val models=object:AbstractList<ModelInfo>(){
   override val size:Int get()=100000
   override fun get(index:Int):ModelInfo { reads++;return ModelInfo("m$index") }
  }
  val selected=ProviderWorkflows.selectedForTest(models)
  check(selected.map{it.modelId}==(0 until 20).map{"m$it"})
  check(reads==20){"Expected 20 element reads, got $reads"}
 }
 test("selected_test_mixed_catalog_matches_eager_semantics"){
  val models=(0 until 1000).map{ModelInfo("m${it%37}",enabled=it%3!=0,displayName="row$it")}
  val expected=models.filter{it.enabled}.distinctBy{it.modelId}.take(20)
  val actual=ProviderWorkflows.selectedForTest(models)
  check(actual==expected);check(actual.indices.all{actual[it]===expected[it]})
 }
 test("selected_test_empty_and_all_disabled_return_empty"){
  check(ProviderWorkflows.selectedForTest(emptyList()).isEmpty())
  check(ProviderWorkflows.selectedForTest((0 until 100).map{ModelInfo("m$it",enabled=false)}).isEmpty())
 }
 test("health_429_cooldown_is_provider_scoped"){
  val policy=RatePolicy(ratePerMin=0.0,minIntervalMs=0.0,jitterMs=0.0,maxInflight=1,softRateSec=2.0,softRateMaxSec=2.0);val p=Provider("p","P","http://127.0.0.1",ratePolicy=policy,models=mapOf("m" to Model("m")));val l=RateLimiter(mapOf("p" to policy));val h=ProviderHealthRegistry();l.noteSoftRateLimit("p");h.noteHttp("p","m",429,l.softRestSnapshot("p").untilMs);val s=h.snapshots(listOf(p),l).single();check(s.state=="cooldown"&&s.scope=="provider"&&s.localImpact=="provider_cooldown"&&s.remainingMs>0&&s.retrySafe)
 }
 test("health_429_without_cooldown_is_attempt_failover"){
  var now=1000L;val p=Provider("p","P","http://127.0.0.1");val h=ProviderHealthRegistry({now});h.noteHttp("p","m",429,0L);val s=h.snapshots(listOf(p),RateLimiter(mapOf("p" to p.ratePolicy))).single();check(s.state=="degraded"&&s.scope=="provider_model"&&s.localImpact=="attempt_failover"&&s.remainingMs==0L&&s.retrySafe)
 }
 test("health_503_is_model_scoped_and_retry_safe"){
  val p=Provider("p","P","http://127.0.0.1",models=mapOf("m" to Model("m")));val l=RateLimiter(mapOf("p" to p.ratePolicy));val h=ProviderHealthRegistry();h.noteHttp("p","m",503);val s=h.snapshots(listOf(p),l).single();check(s.state=="degraded"&&s.kind=="upstream_unavailable"&&s.scope=="provider_model"&&s.localImpact=="attempt_failover"&&s.retrySafe)
 }
 test("health_sent_transport_is_ambiguous_and_not_retry_safe"){
  val p=Provider("p","P","http://127.0.0.1");val h=ProviderHealthRegistry();h.noteTransport("p","m",true,"SocketException:secret");val s=h.snapshots(listOf(p),RateLimiter(mapOf("p" to p.ratePolicy))).single();check(s.kind=="ambiguous_transport"&&s.scope=="attempt"&&!s.retrySafe&&s.detail=="SocketExceptionsecret")
 }
 test("health_newer_failure_survives_older_success"){
  var now=1000L;val p=Provider("p","P","http://127.0.0.1");val h=ProviderHealthRegistry({now});now=1200;h.noteHttp("p","m",503);now=1300;h.noteSuccess("p",1100);check(h.snapshots(listOf(p),RateLimiter(mapOf("p" to p.ratePolicy))).single().state=="degraded")
 }
 test("health_newer_success_clears_prior_failure"){
  var now=1000L;val p=Provider("p","P","http://127.0.0.1");val h=ProviderHealthRegistry({now});h.noteHttp("p","m",503);now=1200;h.noteSuccess("p",1100);check(h.snapshots(listOf(p),RateLimiter(mapOf("p" to p.ratePolicy))).single().state=="ready")
 }
 test("health_snapshot_only_returns_current_providers"){
  val a=Provider("a","A","http://127.0.0.1");val b=Provider("b","B","http://127.0.0.1");val h=ProviderHealthRegistry();h.noteHttp("a","m",503);check(h.snapshots(listOf(b),RateLimiter(mapOf("b" to b.ratePolicy))).map{it.providerId}==listOf("b"))
 }
 test("successful_capability_probe_does_not_enable_disabled_model"){
  val m=ModelInfo("m",enabled=false);val p=ModelProbe.ProbeResult(true,true,true,10);val r=ProviderWorkflows.applyCapabilityResult(m,p);check(!r.enabled&&r.available==true&&"TOOL" in r.abilities)
 }
 test("capability_probe_preserves_price_modality_name_and_manual_abilities"){
  val m=ModelInfo("m",enabled=false,displayName="mine",priceInPerM=0.0,priceOutPerM=2.0,inputModalities=listOf("IMAGE"),abilities=listOf("VISION","REASONING"));val r=ProviderWorkflows.applyCapabilityResult(m,ModelProbe.ProbeResult(true,true,true,10));check(r.displayName==m.displayName&&r.inputModalities==m.inputModalities&&r.priceInPerM==0.0&&r.priceOutPerM==2.0&&r.abilities.containsAll(listOf("TOOL","VISION","REASONING")))
 }
 test("measured_results_only_merge_test_fields_not_stale_user_edits"){
  val current=ModelInfo("m",enabled=false,displayName="new",priceInPerM=7.0,priceOutPerM=8.0,abilities=listOf("VISION"));val old=ModelInfo("m",enabled=true,displayName="old",priceInPerM=1.0,priceOutPerM=1.0,available=true,latencyMs=9,toolCall=true);val r=ProviderWorkflows.mergeTestFields(current,old);check(!r.enabled&&r.displayName=="new"&&r.priceInPerM==7.0&&r.available==true&&"VISION" in r.abilities&&"TOOL" in r.abilities)
 }
 test("directory_only_measurement_preserves_current_capability_fields"){
  val m=ModelInfo("m",toolCall=true,streaming=true,abilities=listOf("TOOL"));val r=ProviderWorkflows.mergeTestFields(m,ModelInfo("m",available=true,latencyMs=2));check(r.toolCall==true&&r.streaming==true&&"TOOL" in r.abilities)
 }
 test("cross_model_merge_rejected"){reject{ProviderWorkflows.mergeTestFields(ModelInfo("a"),ModelInfo("b"))}}
 test("explicit_input_endpoint_shared_discovery_works"){
  Fake().use{f->val d=ProviderWorkflows.discover(f.base,"",suffixStyle="INPUT");check(d.ok&&d.models==listOf("m","n")&&f.posts.get()==0&&f.gets.get()==1)}
 }
 test("manual_openai_suffix_not_doubled"){
  Fake().use{f->val d=ProviderWorkflows.discover(f.base,"",suffixStyle="OPENAI");check(d.ok&&d.resolvedUrl==f.base)}
 }
 test("models_by_name_discovery_is_normalized_and_deduplicated"){
  Fake().use{f->f.mode.set("modelsByName");val d=ProviderWorkflows.discover(f.base,"",suffixStyle="INPUT");check(d.models==listOf("m","n"))}
 }
 test("invalid_url_with_userinfo_or_query_rejected_before_request"){
  reject{ProviderWorkflows.discover("http://user:pass@127.0.0.1:1/v1","")};reject{ProviderWorkflows.discover("http://127.0.0.1:1/v1?apiKey=not-real","")}
 }
 test("invalid_suffix_rejected"){reject{ProviderWorkflows.discover("http://127.0.0.1:1", "", "UNKNOWN")}}
 test("cancelled_discovery_starts_no_request"){
  Fake().use{f->reject{ProviderWorkflows.discover(f.base,"",cancelled={true})};check(f.gets.get()==0)}
 }
 test("cancel_during_auto_discovery_prevents_more_suffix_attempts"){
  Fake().use{f->f.mode.set("cancelDiscovery");reject{ProviderWorkflows.discover(f.base,"",cancelled={f.cancel.get()})};check(f.gets.get()==1)}
 }
 test("capability_positive_shared_workflow_observes_real_fixture"){
  Fake().use{f->val p=ProviderWorkflows.probeOne(f.base,"","m");check(p.nonStreaming&&p.streaming&&p.toolCall&&f.posts.get()==3)}
 }
 test("cooperative_cancel_after_first_post_prevents_later_generation_calls"){
  Fake().use{f->f.mode.set("cancelFirstPost");val p=ProviderWorkflows.probeOne(f.base,"","m",cancelled={f.cancel.get()});check(p.nonStreaming&&!p.streaming&&!p.toolCall&&f.posts.get()==1)}
 }
 test("batch_cancel_during_first_directory_read_stops_later_calls_and_callbacks"){
  Fake().use{f->f.mode.set("blockGet");val callbacks=AtomicInteger();val h=BatchTester.testAll(f.base,"",(1..20).map{ModelInfo("m$it")},concurrency=1,onItem={callbacks.incrementAndGet()},onProgress={callbacks.incrementAndGet()});check(f.entered.await(2,TimeUnit.SECONDS));h.cancel();f.release.countDown();h.executor?.awaitTermination(3,TimeUnit.SECONDS);check(h.isCancelled()&&f.gets.get()==1&&f.posts.get()==0&&callbacks.get()==0)}
 }
 test("batch_empty_selection_has_no_network"){
  Fake().use{f->val done=AtomicBoolean();BatchTester.testAll(f.base,"",listOf(ModelInfo("m",enabled=false)),onItem={error("not selected")},onProgress={done.set(it.finished)});check(done.get()&&f.gets.get()==0)}
 }
 test("directory_read_not_recorded_as_new_generation_capability"){
  Fake().use{f->val r=BatchTester.testOne(f.base,"",ModelInfo("m",toolCall=true,streaming=true));check(r.available==true&&r.toolCall==null&&r.streaming==null&&f.posts.get()==0)}
 }
 test("MCP_batch_missing_confirm_rejected_before_any_fixture_request"){
  Fake().use{f->val c=ctx(root,"mcp-no-confirm");seeded(c,f.base);val r=rpc(c,JSONObject().put("providerId","p"));check(r.getBoolean("isError")&&f.gets.get()==0&&f.posts.get()==0)}
 }
 test("MCP_batch_false_or_string_confirm_rejected"){
  val c=ctx(root,"bad-confirm");for(confirm in listOf<Any>(false,"true")){check(rpc(c,JSONObject().put("providerId","p").put("confirm",confirm)).getBoolean("isError"))}
 }
 test("MCP_batch_cannot_accept_arbitrary_url_argument"){
  val c=ctx(root,"unknown-arg");check(rpc(c,JSONObject().put("providerId","p").put("confirm",true).put("url","http://other")).getBoolean("isError"))
 }
 test("MCP_batch_with_no_enabled_models_has_no_network"){
  Fake().use{f->val c=ctx(root,"none");seeded(c,f.base,enabled=false);val r=rpc(c,JSONObject().put("providerId","p").put("confirm",true));check(r.getBoolean("isError")&&f.gets.get()==0)}
 }
 test("MCP_batch_selected20_budget_persists_without_changing_selection"){
  Fake().use{f->val c=ctx(root,"budget");seeded(c,f.base,count=25);val r=rpc(c,JSONObject().put("providerId","p").put("confirm",true));check(!r.getBoolean("isError"));check(f.gets.get()==20&&f.posts.get()==0);val m=ConfigStore.read(c).providers[0].getJSONArray("models");check(m.length()==25&&(0 until m.length()).all{m.getJSONObject(it).getBoolean("enabled")})}
 }
 test("MCP_and_UI_discovery_use_same_disabled_default"){
  Fake().use{f->val c=ctx(root,"discovery");val b=McpBridge(c);val r=JSONObject(b.addProvider("p",f.base,"fixture-not-real"));check(r.getBoolean("ok"));check(ProviderRepo.list(c)[0].models.none{it.enabled});check(!b.listProvidersJson().contains("fixture-not-real"));check(f.posts.get()==0)}
 }
 test("MCP_status_build_identity_separate_from_config_and_protocol"){
  val c=ctx(root,"identity");val r=JSONObject(McpBridge(c).statusJson(38080));check(r.getJSONObject("build").getString("versionName")=="0.3.0.0");check(r.getJSONObject("build").getString("buildKind")=="jvm-fixture");check(r.getLong("persistedRevision")==0L)
 }
 test("shared_rule_command_enforces_expected_revision_from_UI_shape"){
  val c=ctx(root,"rules");ConfigCommands.saveRule(c,DemoFallback.ruleJson());val rev=ConfigStore.read(c).revision;ConfigCommands.saveRule(c,DemoFallback.ruleJson().put("expectedRevision",rev));var rejected=false;try{ConfigCommands.saveRule(c,DemoFallback.ruleJson().put("name","changed").put("expectedRevision",rev-1))}catch(_:ConfigStore.ConfigException){rejected=true};check(rejected)
 }
 runUsageAudit(::test, File(root,"usage"))
 println("SUMMARY|cases=$total|passed=${total-failed}|failed=$failed")
 if(failed>0)kotlin.system.exitProcess(1)
}
