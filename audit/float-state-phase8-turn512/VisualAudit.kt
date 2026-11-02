package com.aqi.ccb.gateway

import org.json.*
import java.io.*
import java.net.*
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.sun.net.httpserver.HttpServer

private var total=0
private var failed=0
private fun test(name:String, action:()->Unit){total++;try{action();println("PASS|$name")}catch(t:Throwable){failed++;println("FAIL|$name|${t.javaClass.simpleName}:${t.message}");t.printStackTrace()}finally{Thread.interrupted()}}
private class Clock(var value:Long=1000){fun read()=value}
private val IDLE=RequestVisualState.Phase.IDLE
private val BUSY=RequestVisualState.Phase.BUSY
private val GREEN=RequestVisualState.Phase.SUCCESS
private val RED=RequestVisualState.Phase.ERROR
private val SUCCESS=RequestVisualState.Outcome.SUCCESS
private val FAILURE=RequestVisualState.Outcome.FAILURE
private val CANCEL=RequestVisualState.Outcome.CANCELLED
private val LOCAL=RequestVisualState.Outcome.LOCAL_REJECT
private fun phase(s:RequestVisualState,expected:RequestVisualState.Phase,scope:String?=null){val got=s.snapshot(scope);check(got.phase==expected){"expected=$expected got=$got scope=$scope"}}
private fun fault(s:RequestVisualState,scope:String="m"){val t=s.begin("fault",scope);s.finish(t,FAILURE)}
private fun event(s:String)=GenerationProgress.event(JSONObject(s),true)
private fun response(s:String)=GenerationProgress.response(s.toByteArray())
private val serial=AtomicInteger()
private fun chat(g:GatewayServer,model:String="m",stream:Boolean=false,out:OutputStream=ByteArrayOutputStream()):Pair<String,String>{
 val id="visual-${serial.incrementAndGet()}"
 val body=JSONObject().put("model",model).put("stream",stream).put("messages",JSONArray().put(JSONObject().put("role","user").put("content","visual-test"))).toString()
 val m=GatewayServer::class.java.getDeclaredMethod("handleChat",OutputStream::class.java,String::class.java,ByteArray::class.java);m.isAccessible=true
 try{m.invoke(g,out,id,body.toByteArray())}catch(e:InvocationTargetException){throw e.targetException}
 return id to if(out is ByteArrayOutputStream)out.toString("UTF-8")else ""
}
private class Up:AutoCloseable{
 val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
 val executor=Executors.newCachedThreadPool{r->Thread(r).apply{isDaemon=true}}
 val counts=ConcurrentHashMap<String,AtomicInteger>()
 val mode=AtomicReference("good")
 val entered=CountDownLatch(1);val release=CountDownLatch(1)
 init{
  server.executor=executor
  server.createContext("/"){e->
   val route=e.requestURI.path.split('/')[1];counts.computeIfAbsent(route){AtomicInteger()}.incrementAndGet()
   try{
    val req=JSONObject(e.requestBody.use{it.readBytes().toString(Charsets.UTF_8)})
    var kind=if(route=="switch")mode.get() else route
    if(route=="selective")kind=if(req.optString("model")=="m")"bad" else "good"
    if(kind=="slow"){entered.countDown();check(release.await(4,TimeUnit.SECONDS));kind="good"}
    var code=200;var type="application/json"
    val text=when(kind){
     "bad"->{code=500;"{}"}
     "retry"->{code=429;"{}"}
     "unavailable"->{code=503;"{}"}
     "sse"->{type="text/event-stream";"data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n"}
     "reasoning"->{type="text/event-stream";"data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"reason\"}}]}\n\ndata: [DONE]\n\n"}
     "sse-tool"->{type="text/event-stream";"data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\"}}]}}]}\n\ndata: [DONE]\n\n"}
     "only-done"->{type="text/event-stream";"data: [DONE]\n\n"}
     "role-only"->{type="text/event-stream";"data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\ndata: [DONE]\n\n"}
     "truncated"->{type="text/event-stream";"data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"}
     "tool"->"""{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"call_1","type":"function","function":{"name":"test","arguments":"{}"}}]}}]}"""
     "unknown"->"{}"
     "huge"->JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("content","x".repeat(GenerationProgress.MAX_NON_STREAM_BYTES+10))))).toString()
     else->"""{"choices":[{"message":{"content":"OK"}}]}"""
    }
    val bytes=text.toByteArray();e.responseHeaders.set("Content-Type",type);e.sendResponseHeaders(code,bytes.size.toLong());e.responseBody.use{it.write(bytes)}
   }catch(_:Exception){}finally{e.close()}
  };server.start()
 }
 fun p(id:String,route:String)=Provider(id,id,"http://127.0.0.1:${server.address.port}/$route",models=mapOf("m" to Model("m"),"n" to Model("n")),ratePolicy=RatePolicy(ratePerMin=0.0,minIntervalMs=0.0,jitterMs=0.0,maxInflight=4))
 fun hits(route:String)=counts[route]?.get()?:0
 override fun close(){release.countDown();server.stop(0);executor.shutdownNow()}
}
private class Rig(val providers:List<Provider>,vms:List<VirtualModel> = emptyList(),rules:List<RuleSpec> = listOf(RuleSpec("default"))):AutoCloseable{
 val callbacks=AtomicInteger()
 val limiter=RateLimiter(providers.associate{it.id to it.ratePolicy})
 val g=GatewayServer(0,providers,rules,limiter,"visual-only-token",virtualModels=vms,upstreamReadTimeoutMs=1800,onVisualStateChanged={callbacks.incrementAndGet()})
 fun clearSlots(){providers.forEach{check(limiter.peek(it.id)["inflight"]==0)}}
 override fun close(){g.stop();clearSlots()}
}
private fun await(fn:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!fn()&&System.nanoTime()<end)Thread.sleep(10);check(fn()){"wait deadline"}}
private fun get(g:GatewayServer,path:String,key:String="visual-only-token"):String{
 if(!g.isRunning())check(g.start())
 Socket("127.0.0.1",g.listeningPortPublic()).use{s->s.soTimeout=3000;s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:${s.port}\r\nAuthorization: Bearer $key\r\nConnection: close\r\n\r\n".toByteArray());return s.getInputStream().readBytes().toString(Charsets.UTF_8)}
}
fun main(args:Array<String>){
 GateLog.init(File(args[0],"logs"))
 test("idle_is_blue_without_work"){val s=RequestVisualState();phase(s,IDLE);check(s.snapshot().activeLogicalRequests==0)}
 test("begin_is_one_logical_busy_request"){val s=RequestVisualState();s.begin("r","alias");phase(s,BUSY);check(s.snapshot().activeLogicalRequests==1)}
 test("success_requires_observed_generation_not_http_status"){val s=RequestVisualState();val t=s.begin("r","m");s.finish(t,SUCCESS);phase(s,IDLE)}
 test("successful_generation_has_three_second_green_hold"){val c=Clock();val s=RequestVisualState(clockMs=c::read);val t=s.begin("r","m");s.progress(t);s.finish(t,SUCCESS);phase(s,GREEN);check(s.snapshot().nextTransitionMs==3000L);c.value+=2999;phase(s,GREEN);c.value++;phase(s,IDLE)}
 test("one_request_end_does_not_hide_other_busy_request"){val s=RequestVisualState();val a=s.begin("a","m");val b=s.begin("b","m");s.progress(a);s.finish(a,SUCCESS);phase(s,BUSY);check(s.snapshot().activeLogicalRequests==1);s.progress(b);s.finish(b,SUCCESS);phase(s,GREEN)}
 test("failure_latches_even_after_minimum_red_time"){val c=Clock();val s=RequestVisualState(clockMs=c::read);fault(s);c.value+=60000;phase(s,RED);check(s.snapshot().unresolvedFaultScopes==1)}
 test("new_request_alone_does_not_clear_failure"){val c=Clock();val s=RequestVisualState(clockMs=c::read);fault(s);c.value+=4000;s.begin("later","m");phase(s,RED)}
 test("terminal_without_progress_cannot_clear_failure"){val c=Clock();val s=RequestVisualState(clockMs=c::read);fault(s);c.value+=4000;val t=s.begin("r","m");s.finish(t,SUCCESS);phase(s,RED)}
 test("same_scope_new_progress_recovers_only_after_red_hold"){val c=Clock();val s=RequestVisualState(clockMs=c::read);fault(s);val t=s.begin("new","m");s.progress(t);phase(s,RED);check(s.snapshot().recoveredWaitingScopes==1);c.value+=3000;phase(s,BUSY);s.finish(t,CANCEL);phase(s,IDLE)}
 test("fast_recovery_finishes_red_then_full_green_hold"){val c=Clock();val s=RequestVisualState(clockMs=c::read);fault(s);val t=s.begin("r","m");s.progress(t);s.finish(t,SUCCESS);phase(s,RED);c.value+=3000;phase(s,GREEN);check(s.snapshot().nextTransitionMs==3000L);c.value+=3000;phase(s,IDLE)}
 test("unrelated_scope_success_does_not_clear_fault"){val c=Clock();val s=RequestVisualState(clockMs=c::read);fault(s,"alpha");c.value+=4000;val t=s.begin("r","beta");s.progress(t);s.finish(t,SUCCESS);phase(s,RED);phase(s,RED,"alpha");phase(s,GREEN,"beta")}
 test("peer_data_before_fault_is_not_recovery_evidence"){val c=Clock();val s=RequestVisualState(clockMs=c::read);val peer=s.begin("peer","m");s.progress(peer);fault(s);c.value+=4000;s.finish(peer,SUCCESS);phase(s,RED)}
 test("peer_data_after_fault_can_recover_same_scope"){val c=Clock();val s=RequestVisualState(clockMs=c::read);val peer=s.begin("peer","m");fault(s);c.value+=4000;s.progress(peer);phase(s,BUSY);s.finish(peer,SUCCESS);phase(s,GREEN)}
 test("new_failure_epoch_restarts_hold_and_recovery_requirement"){val c=Clock();val s=RequestVisualState(clockMs=c::read);fault(s);val t=s.begin("new","m");s.progress(t);c.value+=2000;s.finish(t,FAILURE);check(s.snapshot().faultEpoch==2L);c.value+=4000;phase(s,RED);check(s.snapshot().unresolvedFaultScopes==1)}
 test("completed_ticket_cannot_clear_later_fault"){val c=Clock();val s=RequestVisualState(clockMs=c::read);val old=s.begin("old","m");s.progress(old);s.finish(old,SUCCESS);fault(s);c.value+=4000;s.progress(old);phase(s,RED)}
 test("double_finish_never_underflows_or_overwrites_outcome"){val s=RequestVisualState();val t=s.begin("r","m");s.progress(t);s.finish(t,SUCCESS);s.finish(t,FAILURE);phase(s,GREEN);check(s.snapshot().activeLogicalRequests==0&&s.snapshot().faultEpoch==0L)}
 test("cancel_is_neutral_not_red_or_green"){val s=RequestVisualState();val t=s.begin("r","m");s.finish(t,CANCEL);phase(s,IDLE);check(s.snapshot().faultEpoch==0L)}
 test("local_reject_is_neutral"){val s=RequestVisualState();val t=s.begin("r","m");s.finish(t,LOCAL);phase(s,IDLE)}
 test("neutral_finish_does_not_extend_other_success_hold"){val c=Clock();val s=RequestVisualState(clockMs=c::read);val a=s.begin("a","m");s.progress(a);s.finish(a,SUCCESS);c.value+=2000;val b=s.begin("b","n");s.finish(b,CANCEL);check(s.snapshot().greenRemainingMs==1000L);c.value+=1000;phase(s,IDLE)}
 test("reset_invalidates_old_callbacks_and_clears_counts"){val s=RequestVisualState();val old=s.begin("r","m");s.reset();s.progress(old);s.finish(old,FAILURE);phase(s,IDLE);check(s.snapshot().activeLogicalRequests==0)}
 test("ticket_from_another_gateway_is_ignored"){val a=RequestVisualState();val b=RequestVisualState();val t=a.begin("r","m");b.progress(t);b.finish(t,FAILURE);phase(b,IDLE);check(a.snapshot().activeLogicalRequests==1);a.finish(t,CANCEL)}
 test("red_priority_does_not_destroy_other_active_counts"){val s=RequestVisualState();fault(s,"a");s.begin("b","b");phase(s,RED);check(s.snapshot().activeLogicalRequests==1)}
 test("per_scope_snapshot_does_not_borrow_other_scope_green"){val s=RequestVisualState();val t=s.begin("r","a");s.progress(t);s.finish(t,SUCCESS);phase(s,GREEN,"a");phase(s,IDLE,"b")}
 test("clock_backwards_cannot_bypass_minimum_hold"){val c=Clock();val s=RequestVisualState(clockMs=c::read);fault(s);val t=s.begin("r","m");s.progress(t);c.value=500;phase(s,RED);check(s.snapshot().redRemainingMs==3000L)}
 test("callback_failure_is_observable_but_not_a_transport_error"){val s=RequestVisualState(onChanged={throw IllegalStateException("test observer")});val t=s.begin("r","m");s.finish(t,CANCEL);check(s.snapshot().callbackFailures==2L);phase(s,IDLE)}
 test("unresolved_fault_never_spins_expiry_timer"){val s=RequestVisualState();fault(s);check(s.snapshot().nextTransitionMs==0L)}
 test("recovered_fault_schedules_exact_remaining_red_expiry"){val c=Clock();val s=RequestVisualState(clockMs=c::read);fault(s);c.value+=1000;val t=s.begin("r","m");s.progress(t);check(s.snapshot().nextTransitionMs==2000L)}
 test("observer_memory_limit_does_not_change_business_and_finishes_once"){val s=RequestVisualState(maxTracked=1);val a=s.begin("a","m");val b=s.begin("b","m");check(s.snapshot().activeLogicalRequests==2&&s.snapshot().observationLimited);s.finish(b,CANCEL);s.finish(b,FAILURE);s.finish(a,CANCEL);phase(s,IDLE);check(s.snapshot().activeLogicalRequests==0)}
 test("untracked_failure_fails_closed_until_explicit_reset"){val s=RequestVisualState(maxTracked=1);val a=s.begin("a","m");val b=s.begin("b","n");s.finish(b,FAILURE);s.finish(a,CANCEL);phase(s,RED);check(s.snapshot().overflowFaultLatched);s.reset();phase(s,IDLE)}
 test("fault_scope_budget_does_not_silently_erase_old_errors"){val s=RequestVisualState(maxFaultScopes=1);fault(s,"a");fault(s,"b");check(s.snapshot().overflowFaultLatched&&s.snapshot().observationLimited);phase(s,RED)}
 test("progress_evidence_limit_is_diagnostic_not_fake_success"){val s=RequestVisualState();val t=s.begin("r","m");s.evidenceUnavailable(t);s.finish(t,SUCCESS);phase(s,IDLE);check(s.snapshot().progressEvidenceLimited==1L)}
 test("repeated_progress_does_not_flood_refresh_callbacks"){val hits=AtomicInteger();val s=RequestVisualState(onChanged={hits.incrementAndGet()});val t=s.begin("r","m");repeat(10000){s.progress(t)};s.finish(t,SUCCESS);check(hits.get()==2)}
 test("24_concurrent_requests_balance_once_per_logical_request"){
  val s=RequestVisualState();val ready=CountDownLatch(24);val go=CountDownLatch(1);val errors=ConcurrentLinkedQueue<Throwable>()
  val threads=(1..24).map{i->Thread{try{val t=s.begin("$i","alias");ready.countDown();check(go.await(3,TimeUnit.SECONDS));s.progress(t);s.finish(t,SUCCESS);s.finish(t,FAILURE)}catch(e:Throwable){errors.add(e)}}.apply{start()}}
  check(ready.await(3,TimeUnit.SECONDS));check(s.snapshot().activeLogicalRequests==24);phase(s,BUSY);go.countDown();threads.forEach{it.join(3000)};check(errors.isEmpty());phase(s,GREEN);check(s.snapshot().activeLogicalRequests==0&&s.snapshot().faultEpoch==0L)
 }
 test("four_colors_have_distinct_expected_argb_values"){check(IDLE.argb==0xFF1E88E5.toInt()&&BUSY.argb==0xFFFF9800.toInt()&&GREEN.argb==0xFF43A047.toInt()&&RED.argb==0xFFE53935.toInt())}
 test("snapshot_is_plain_diagnostic_data_not_prompt_or_token"){val s=RequestVisualState();s.begin("not-returned-request-id","m");val j=JSONObject(s.snapshot().asMap());check(!j.toString().contains("not-returned"));check(j.getInt("activeLogicalRequests")==1)}
 test("content_delta_is_real_progress"){check(event("""{"choices":[{"delta":{"content":"hello"}}]}"""))}
 test("reasoning_delta_is_real_progress"){check(event("""{"choices":[{"delta":{"reasoning_content":"thinking"}}]}"""))}
 test("alternative_reasoning_field_is_real_progress"){check(event("""{"choices":[{"delta":{"reasoning":"thinking"}}]}"""))}
 test("modern_tool_only_fragment_is_progress"){check(event("""{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"{"}}]}}]}"""))}
 test("legacy_function_name_is_progress"){check(event("""{"choices":[{"delta":{"function_call":{"name":"lookup"}}}]}"""))}
 test("second_choice_can_have_progress"){check(event("""{"choices":[{}, {"delta":{"content":"hello"}}]}"""))}
 test("structured_text_content_is_progress"){check(event("""{"choices":[{"delta":{"content":[{"type":"text","text":"hello"}]}}]}"""))}
 test("structured_image_reference_is_progress"){check(event("""{"choices":[{"delta":{"content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,AA=="}}]}}]}"""))}
 test("role_only_is_not_progress"){check(!event("""{"choices":[{"delta":{"role":"assistant"}}]}"""))}
 test("usage_or_finish_only_is_not_progress"){check(!event("""{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"total_tokens":5}}"""))}
 test("empty_or_whitespace_content_is_not_progress"){check(!event("""{"choices":[{"delta":{"content":"  ","reasoning_content":null,"tool_calls":[]}}]}"""))}
 test("error_object_never_proves_recovery"){check(!event("""{"error":{"message":"bad"},"choices":[{"delta":{"content":"x"}}]}"""))}
 test("user_role_payload_is_not_assistant_progress"){check(!event("""{"choices":[{"delta":{"role":"user","content":"x"}}]}"""))}
 test("malformed_nonstream_json_is_not_progress"){check(!response("broken"));check(!response("[]"));check(!response("{} {}"))}
 test("nonstream_tool_only_and_text_are_supported"){check(response("""{"choices":[{"message":{"tool_calls":[{"function":{"name":"call"}}]}}]}"""));check(response("""{"choices":[{"message":{"content":"ok"}}]}"""))}
 test("stream_and_nonstream_schemas_are_not_confused"){check(!event("""{"choices":[{"message":{"content":"ok"}}]}"""));check(!response("""{"choices":[{"delta":{"content":"ok"}}]}"""))}
 test("real_health_and_models_never_light_generation"){
  Up().use{u->Rig(listOf(u.p("a","good"))).use{r->check(get(r.g,"/health").startsWith("HTTP/1.1 200"));check(get(r.g,"/v1/models").startsWith("HTTP/1.1 200"));check(r.g.requestVisualStatePublic().phase==IDLE&&r.g.requestVisualStatePublic().activeLogicalRequests==0);check(u.hits("good")==0)}}
 }
 test("unauthenticated_http_does_not_light_or_fail_generation"){
  Up().use{u->Rig(listOf(u.p("a","good"))).use{r->check(get(r.g,"/health","wrong").startsWith("HTTP/1.1 401"));check(r.g.requestVisualStatePublic().phase==IDLE);check(r.g.requestVisualStatePublic().faultEpoch==0L)}}
 }
 test("json_generation_green_after_complete_real_response"){
  Up().use{u->Rig(listOf(u.p("a","good"))).use{r->val(_,out)=chat(r.g);check(out.endsWith("\"OK\"}}]}"));check(r.g.requestVisualStatePublic().phase==GREEN);check(r.callbacks.get()>=2);r.clearSlots()}}
 }
 test("tool_only_json_generation_green"){
  Up().use{u->Rig(listOf(u.p("a","tool"))).use{r->chat(r.g);check(r.g.requestVisualStatePublic().phase==GREEN)}}
 }
 test("sse_content_green_after_done"){
  Up().use{u->Rig(listOf(u.p("a","sse"))).use{r->val(_,out)=chat(r.g,stream=true);check(out.contains("[DONE]"));check(r.g.requestVisualStatePublic().phase==GREEN)}}
 }
 test("sse_reasoning_green_after_done"){
  Up().use{u->Rig(listOf(u.p("a","reasoning"))).use{r->chat(r.g,stream=true);check(r.g.requestVisualStatePublic().phase==GREEN)}}
 }
 test("sse_tool_arguments_green_after_done"){
  Up().use{u->Rig(listOf(u.p("a","sse-tool"))).use{r->chat(r.g,stream=true);check(r.g.requestVisualStatePublic().phase==GREEN)}}
 }
 test("done_only_response_is_not_fake_generation_success"){
  Up().use{u->Rig(listOf(u.p("a","only-done"))).use{r->chat(r.g,stream=true);check(r.g.totalOk==1L);check(r.g.requestVisualStatePublic().phase==IDLE)}}
 }
 test("role_only_stream_is_not_fake_progress"){
  Up().use{u->Rig(listOf(u.p("a","role-only"))).use{r->chat(r.g,stream=true);check(r.g.requestVisualStatePublic().phase==IDLE)}}
 }
 test("A429_B_success_is_one_successful_logical_request"){
  Up().use{u->Rig(listOf(u.p("a","retry"),u.p("b","good"))).use{r->chat(r.g);check(u.hits("retry")==1&&u.hits("good")==1);val v=r.g.requestVisualStatePublic();check(v.phase==GREEN&&v.faultEpoch==0L&&v.activeLogicalRequests==0);check(r.g.totalFail==0L)}}
 }
 test("virtual_model_fallback_does_not_duplicate_logical_count"){
  Up().use{u->val vm=VirtualModel("alias",members=listOf(VirtualModel.Member("a","m",0),VirtualModel.Member("b","m",1)));Rig(listOf(u.p("a","retry"),u.p("b","slow")),listOf(vm)).use{r->
   val error=AtomicReference<Throwable?>();val worker=Thread{try{chat(r.g,"alias")}catch(t:Throwable){error.set(t)}}.apply{start()}
   check(u.entered.await(3,TimeUnit.SECONDS));val during=r.g.requestVisualStatePublic();check(during.phase==BUSY&&during.activeLogicalRequests==1&&during.faultEpoch==0L);u.release.countDown();worker.join(3500);check(error.get()==null);check(r.g.requestVisualStatePublic().phase==GREEN);check(u.hits("retry")==1&&u.hits("slow")==1)
  }}
 }
 test("all_candidates_fail_one_logical_alarm_not_per_attempt"){
  Up().use{u->Rig(listOf(u.p("a","unavailable"),u.p("b","bad"))).use{r->chat(r.g);val v=r.g.requestVisualStatePublic();check(v.phase==RED&&v.faultEpoch==1L&&v.activeLogicalRequests==0);check(r.g.totalFail==1L&&u.hits("unavailable")==1&&u.hits("bad")==1)}}
 }
 test("real_other_model_success_does_not_clear_failed_model"){
  Up().use{u->Rig(listOf(u.p("a","selective"))).use{r->chat(r.g,"m");chat(r.g,"n");check(r.g.requestVisualStatePublic().phase==RED);check(r.g.requestVisualStatePublic().unresolvedFaultScopes==1);check(r.g.totalOk==1L&&r.g.totalFail==1L)}}
 }
 test("same_model_new_generation_records_recovery_not_http200_alone"){
  Up().use{u->Rig(listOf(u.p("a","switch"))).use{r->u.mode.set("bad");chat(r.g);u.mode.set("unknown");chat(r.g);check(r.g.requestVisualStatePublic().unresolvedFaultScopes==1);u.mode.set("good");chat(r.g);check(r.g.requestVisualStatePublic().unresolvedFaultScopes==0)}}
 }
 test("truncated_sse_keeps_failure_despite_prior_content"){
  Up().use{u->Rig(listOf(u.p("a","truncated"),u.p("b","good"))).use{r->val(_,out)=chat(r.g,stream=true);check(out.contains("partial"));check(r.g.requestVisualStatePublic().phase==RED);check(u.hits("good")==0);check(Regex("HTTP/1.1").findAll(out).count()==1)}}
 }
 test("failed_downstream_flush_does_not_supply_recovery_evidence"){
  Up().use{u->Rig(listOf(u.p("a","switch"))).use{r->u.mode.set("bad");chat(r.g);u.mode.set("sse");val broken=object:ByteArrayOutputStream(){var calls=0;override fun flush(){calls++;if(calls>=2)throw SocketException("test body flush")}}
   chat(r.g,stream=true,out=broken);val v=r.g.requestVisualStatePublic();check(v.phase==RED&&v.unresolvedFaultScopes==1&&v.recoveredWaitingScopes==0);check(r.g.totalFail==1L)
  }}
 }
 test("client_write_failure_is_neutral_not_provider_alarm"){
  Up().use{u->Rig(listOf(u.p("a","good"),u.p("b","good2"))).use{r->val broken=object:OutputStream(){override fun write(b:Int){throw SocketException("closed")}};chat(r.g,out=broken);check(r.g.requestVisualStatePublic().phase==IDLE&&r.g.requestVisualStatePublic().faultEpoch==0L);check(u.hits("good2")==0);check(r.providers.all{it.errors==0})}}
 }
 test("nonstream_proof_budget_does_not_truncate_or_reject_response"){
  Up().use{u->Rig(listOf(u.p("a","huge"))).use{r->val(_,out)=chat(r.g);check(JSONObject(out.substringAfter("\r\n\r\n")).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").length==GenerationProgress.MAX_NON_STREAM_BYTES+10);val v=r.g.requestVisualStatePublic();check(v.phase==IDLE&&v.progressEvidenceLimited==1L);check(r.g.totalOk==1L)}}
 }
 test("gateway_stop_clears_visual_state_without_future_green"){
  Up().use{u->Rig(listOf(u.p("a","good"))).use{r->chat(r.g);check(r.g.requestVisualStatePublic().phase==GREEN);r.g.stop();check(r.g.requestVisualStatePublic().phase==IDLE)}}
 }
 test("diagnostic_snapshot_contains_observed_logical_state"){
  Up().use{u->Rig(listOf(u.p("a","good"))).use{r->chat(r.g);val v=r.g.debugSnapshot().getJSONObject("visualState");check(v.getString("phase")=="SUCCESS"&&v.getInt("activeLogicalRequests")==0);check(!v.toString().contains("visual-only-token"))}}
 }
 test("unimplemented_race_still_rejected_without_upstream_or_alarm"){
  Up().use{u->Rig(listOf(u.p("a","good")),rules=listOf(RuleSpec("default",execute=OpCall("race")))).use{r->val(_,out)=chat(r.g);check(out.startsWith("HTTP/1.1 422"));check(u.hits("good")==0);check(r.g.requestVisualStatePublic().phase==IDLE)}}
 }
 test("recovering_one_scope_does_not_erase_other_scope_alarm"){
  val c=Clock();val s=RequestVisualState(clockMs=c::read);fault(s,"a");fault(s,"b");c.value+=4000
  val a=s.begin("ra","a");s.progress(a);s.finish(a,SUCCESS);phase(s,RED);check(s.snapshot().unresolvedFaultScopes==1)
  val b=s.begin("rb","b");s.progress(b);phase(s,BUSY);s.finish(b,SUCCESS);phase(s,GREEN)
 }
 test("fault_scope_names_are_bounded_and_old_model_failures_remain_visible"){
  val s=RequestVisualState();repeat(20){fault(s,"model-$it")};val snap=s.snapshot()
  check(snap.faultScopeSample.size==16&&snap.unresolvedFaultScopes==20);check(snap.asMap()["faultScopeSample"]==snap.faultScopeSample)
 }
 test("many_interleaved_scopes_never_lose_or_underflow_logical_counts"){
  val c=Clock();val s=RequestVisualState(clockMs=c::read);val random=java.util.Random(512);val tickets=mutableListOf<RequestVisualState.Ticket>()
  repeat(1000){i->
   if(tickets.isEmpty()||random.nextBoolean())tickets.add(s.begin("req-$i","vm-${i%7}"))
   else{val idx=random.nextInt(tickets.size);val t=tickets.removeAt(idx);if(random.nextBoolean())s.progress(t);s.finish(t,RequestVisualState.Outcome.values()[random.nextInt(4)])}
   c.value+=1;check(s.snapshot().activeLogicalRequests==tickets.size)
  }
  tickets.forEach{s.finish(it,CANCEL)};check(s.snapshot().activeLogicalRequests==0)
 }
 test("same_scope_progress_is_not_lost_when_peer_fault_occurs_midstream"){
  val c=Clock();val s=RequestVisualState(clockMs=c::read);val live=s.begin("live","vm");s.progress(live);fault(s,"vm");c.value+=4000
  phase(s,RED);s.progress(live);phase(s,BUSY);s.finish(live,SUCCESS);phase(s,GREEN)
 }
 test("local_reject_does_not_clear_existing_fault_even_with_old_peer_progress"){
  val c=Clock();val s=RequestVisualState(clockMs=c::read);val old=s.begin("old","vm");s.progress(old);fault(s,"vm");c.value+=4000;s.finish(old,LOCAL);phase(s,RED)
 }
 test("live_health_during_generation_does_not_increment_logical_active_count"){
  Up().use{u->Rig(listOf(u.p("a","slow"))).use{r->
   check(r.g.start());val error=AtomicReference<Throwable?>();val worker=Thread{try{chat(r.g)}catch(t:Throwable){error.set(t)}}.apply{start()}
   check(u.entered.await(3,TimeUnit.SECONDS));check(r.g.requestVisualStatePublic().activeLogicalRequests==1)
   check(get(r.g,"/health").startsWith("HTTP/1.1 200"));check(r.g.requestVisualStatePublic().activeLogicalRequests==1&&r.g.requestVisualStatePublic().phase==BUSY)
   u.release.countDown();worker.join(3500);check(error.get()==null);check(r.g.requestVisualStatePublic().phase==GREEN)
  }}
 }
 test("real_config_reload_retains_failure_until_same_scope_generation"){
  Up().use{u->Rig(listOf(u.p("a","switch"))).use{r->
   u.mode.set("bad");chat(r.g);check(r.g.requestVisualStatePublic().phase==RED)
   r.g.update(r.providers,listOf(RuleSpec("default")),revision=1,keys=emptyMap())
   check(r.g.requestVisualStatePublic().phase==RED&&r.g.requestVisualStatePublic().faultEpoch==1L)
   u.mode.set("good");chat(r.g);check(r.g.requestVisualStatePublic().unresolvedFaultScopes==0)
  }}
 }
 test("retiring_failed_model_keeps_diagnostic_scope_until_explicit_restart"){
  Up().use{u->Rig(listOf(u.p("a","selective"))).use{r->
   chat(r.g,"m");val onlyN=r.providers.map{p->p.copy(models=p.models.filterKeys{it=="n"})}
   r.g.update(onlyN,listOf(RuleSpec("default")),revision=1,keys=emptyMap());chat(r.g,"n")
   val v=r.g.requestVisualStatePublic();check(v.phase==RED&&v.faultScopeSample==listOf("m"));r.g.stop();check(r.g.requestVisualStatePublic().phase==IDLE)
  }}
 }
 println("SUMMARY|cases=$total|passed=${total-failed}|failed=$failed")
 if(failed>0)kotlin.system.exitProcess(1)
}
