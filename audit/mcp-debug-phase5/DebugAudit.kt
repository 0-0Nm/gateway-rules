package com.aqi.ccb.gateway

import android.content.Context
import com.aqi.ccb.data.*
import org.json.*
import java.io.*
import java.net.*
import java.nio.file.Files
import java.util.concurrent.*
import com.sun.net.httpserver.HttpServer

private var n=0;private var failed=0
private fun test(name:String,fn:()->Unit){n++;try{fn();println("PASS|$name")}catch(t:Throwable){failed++;println("FAIL|$name|${t.javaClass.simpleName}:${t.message}");t.printStackTrace()}}
private fun expectReject(fn:()->Unit){var rejected=false;try{fn()}catch(_:Exception){rejected=true};check(rejected)}
private fun rule(id:String="default")=JSONObject().put("id",id).put("select",JSONArray()).put("execute",JSONObject().put("op","first"))
private fun rpc(server:McpServer,tool:String,args:JSONObject=JSONObject(),token:String="mcp-test-only"):Pair<Int,JSONObject?>{
 val f=McpServer::class.java.getDeclaredField("serverSocket");f.isAccessible=true;val port=(f.get(server) as ServerSocket).localPort
 val req=JSONObject().put("jsonrpc","2.0").put("id",1).put("method","tools/call").put("params",JSONObject().put("name",tool).put("arguments",args)).toString().toByteArray()
 Socket("127.0.0.1",port).use{s->s.soTimeout=30000;s.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $token\r\nContent-Type: application/json\r\nContent-Length: ${req.size}\r\nConnection: close\r\n\r\n").toByteArray()+req);s.getOutputStream().flush()
  val text=s.getInputStream().readBytes().toString(Charsets.UTF_8);val code=text.substringBefore("\r\n").split(' ')[1].toInt()
  val raw=text.substringAfter("\r\n\r\n");return code to runCatching{JSONObject(raw)}.getOrNull()
 }
}
private fun result(pair:Pair<Int,JSONObject?>):JSONObject{check(pair.first==200);val rpc=pair.second!!.getJSONObject("result");return JSONObject(rpc.getJSONArray("content").getJSONObject(0).getString("text"))}
private fun isError(pair:Pair<Int,JSONObject?>):Boolean=pair.second!!.getJSONObject("result").getBoolean("isError")
private fun logArgs(stream:String="calls",limit:Int=50)=JSONObject().put("stream",stream).put("limit",limit)
fun main(args:Array<String>){
 val root=File(args[0]);root.mkdirs();val ctx=Context(File(root,"app").apply{mkdirs()});val log=File(root,"logs");GateLog.init(log)
 val bridge=McpBridge(ctx);val server=McpServer(0,38080,"mcp-test-only"){bridge};check(server.start())
 try {
 test("mcp_background_snapshot_without_activity"){
  val r=result(rpc(server,"gw_debug_snapshot"));check(r.getBoolean("ok")&&!r.getBoolean("gatewayRunning"));check(!r.getBoolean("foregroundActivityRequired"));check(r.getInt("providers")==0)
 }
 test("mcp_tool_schema_contains_all_new_tools"){
  val m=McpServer::class.java.getDeclaredMethod("toolsList");m.isAccessible=true;val a=(m.invoke(server) as JSONObject).getJSONArray("tools");val names=(0 until a.length()).map{a.getJSONObject(it).getString("name")}
  for(name in listOf("gw_log_query","gw_trace_request","gw_debug_snapshot","gw_gateway_control","gw_explain_route","gw_probe_request","gw_self_test"))check(name in names)
  check(names.size==names.distinct().size)
 }
 test("unauthenticated_debug_rejected_before_dispatch"){check(rpc(server,"gw_self_test",token="wrong").first==401)}
 test("control_needs_explicit_confirm"){val r=rpc(server,"gw_gateway_control",JSONObject().put("action","start"));check(isError(r)&&!result(r).getBoolean("ok"))}
 test("control_rejects_unconfigured_start"){check(isError(rpc(server,"gw_gateway_control",JSONObject().put("action","start").put("confirm",true))))}
 test("control_stop_when_stopped_is_observed_noop"){
  val r=result(rpc(server,"gw_gateway_control",JSONObject().put("action","stop").put("confirm",true)));check(r.getBoolean("completed")&&r.getBoolean("noOp")&&!r.getBoolean("gatewayRunning"))
 }
 test("control_cannot_stop_mcp"){check(isError(rpc(server,"gw_gateway_control",JSONObject().put("action","stop_mcp").put("confirm",true))));check(server.isRunning())}
 test("live_probe_requires_explicit_charge_consent"){check(isError(rpc(server,"gw_probe_request",JSONObject().put("modelId","m"))))}
 test("live_probe_wrong_confirm_type_rejected"){check(isError(rpc(server,"gw_probe_request",JSONObject().put("modelId","m").put("confirm","true"))))}
 test("live_probe_requires_running_gateway"){check(isError(rpc(server,"gw_probe_request",JSONObject().put("modelId","m").put("confirm",true))))}
 test("live_probe_cannot_accept_arbitrary_url_or_key"){check(isError(rpc(server,"gw_probe_request",JSONObject().put("modelId","m").put("confirm",true).put("url","http://other/").put("apiKey","not-allowed"))))}
 for(value in listOf(0,65,1.5))test("live_probe_token_bound_$value"){check(isError(rpc(server,"gw_probe_request",JSONObject().put("modelId","m").put("confirm",true).put("maxTokens",value))))}
 test("live_probe_timeout_bound"){check(isError(rpc(server,"gw_probe_request",JSONObject().put("modelId","m").put("confirm",true).put("timeoutMs",16000))))}
 test("live_probe_prompt_bound"){check(isError(rpc(server,"gw_probe_request",JSONObject().put("modelId","m").put("confirm",true).put("prompt","x".repeat(513)))))}
 test("unknown_diagnostic_field_rejected"){check(isError(rpc(server,"gw_debug_snapshot",JSONObject().put("secret",true))))}
 test("unknown_tool_not_silently_success"){check(isError(rpc(server,"gw_not_exists")))}
 val tests=File(root,"query");File(tests,"calls").mkdirs();File(tests,"system").mkdirs()
 val calls=File(tests,"calls/2026-11-02.jsonl");val system=File(tests,"system/gateway.log")
 fun writeRecords(count:Int){calls.writeText((1..count).joinToString("\n",postfix="\n"){JSONObject().put("seq",it).put("kind","attempt").put("req_id","r-12345678").put("provider","p").put("ok",false).put("err","IOException").toString()})}
 test("logs_empty_is_structured"){val r=GateLog.query(File(root,"absent"),logArgs());check(r.getJSONArray("records").length()==0&&!r.getBoolean("hasMore"))}
 test("logs_exact_request_filter"){
  writeRecords(5);calls.appendText(JSONObject().put("kind","result").put("req_id","r-abcdef12").put("ok",true).toString()+"\n")
  val r=GateLog.query(tests,logArgs().put("requestId","r-abcdef12"));check(r.getJSONArray("records").length()==1)
 }
 test("logs_paginated_without_duplicates"){
  writeRecords(20);var cursor="";val seq=mutableListOf<Int>();var pages=0
  do{val q=logArgs(limit=3);if(cursor.isNotEmpty())q.put("cursor",cursor);val r=GateLog.query(tests,q);val a=r.getJSONArray("records");for(i in 0 until a.length())seq.add(a.getJSONObject(i).getInt("seq"));cursor=if(r.getBoolean("hasMore"))r.getString("nextCursor")else "";pages++;check(pages<20)}while(cursor.isNotEmpty())
  check(seq==(20 downTo 1).toList())
 }
 test("logs_cursor_ignores_new_appends"){
  writeRecords(4);val first=GateLog.query(tests,logArgs(limit=2));val cursor=first.getString("nextCursor")
  calls.appendText(JSONObject().put("seq",5).put("kind","attempt").toString()+"\n")
  val next=GateLog.query(tests,logArgs(limit=2).put("cursor",cursor));val a=next.getJSONArray("records");check((0 until a.length()).map{a.getJSONObject(it).getInt("seq")}==listOf(2,1))
 }
 test("logs_rotation_returns_stale_not_false_empty"){
  writeRecords(5);val c=GateLog.query(tests,logArgs(limit=1)).getString("nextCursor");calls.writeText("{}\n");val r=GateLog.query(tests,logArgs(limit=1).put("cursor",c));check(!r.getBoolean("ok")&&r.getString("code")=="CURSOR_STALE")
 }
 test("logs_cursor_binds_filters"){
  writeRecords(5);val c=GateLog.query(tests,logArgs(limit=1)).getString("nextCursor");expectReject{GateLog.query(tests,logArgs(limit=1).put("kind","result").put("cursor",c))}
 }
 test("logs_cursor_rejects_path_traversal"){
  writeRecords(5);val c=GateLog.query(tests,logArgs(limit=1)).getString("nextCursor");val state=JSONObject(String(java.util.Base64.getUrlDecoder().decode(c)));state.getJSONArray("files").getJSONObject(0).put("name","../../secret")
  val forged=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(state.toString().toByteArray());expectReject{GateLog.query(tests,logArgs(limit=1).put("cursor",forged))}
 }
 test("logs_do_not_return_headers_body_or_custom_fields"){
  calls.writeText(JSONObject().put("req_id","r-12345678").put("kind","request").put("headers",JSONObject().put("Authorization","secret-value")).put("body","sensitive prompt").put("custom","secret-value").toString()+"\n")
  val r=GateLog.query(tests,logArgs()).toString();check(!r.contains("secret-value")&&!r.contains("sensitive prompt")&&!r.contains("headers"))
 }
 test("logs_redact_known_keys_before_substring_filter"){
  calls.writeText(JSONObject().put("kind","attempt").put("err","private-key-abc").toString()+"\n")
  val r=GateLog.query(tests,logArgs().put("contains","private-key-abc"),listOf("private-key-abc"));check(r.getJSONArray("records").length()==0)
 }
 test("system_logs_redact_credentials"){
  system.writeText("2026-11-02 00:00:00 [ERROR] http: Authorization: Bearer example-secret-value apiKey=hidden-pw token=private-123\n")
  val r=GateLog.query(tests,logArgs("system"),listOf("private-123")).toString();check(!r.contains("example-secret-value")&&!r.contains("hidden-pw")&&!r.contains("private-123"))
 }
 test("system_logs_level_filter"){
  system.writeText("x [INFO] a: normal\nx [ERROR] a: failed\n");val r=GateLog.query(tests,logArgs("system").put("level","ERROR"));check(r.getJSONArray("records").length()==1)
 }
 test("logs_limit_long_overflow_rejected"){
  expectReject{GateLog.query(tests,logArgs().put("limit",4294967297L))}
 }
 test("system_logs_unknown_token_assignment_is_masked"){
  system.writeText("x [ERROR] component: token=unregistered-private-token key=unregistered-private-key\n")
  val r=GateLog.query(tests,logArgs("system")).toString();check(!r.contains("unregistered-private-token")&&!r.contains("unregistered-private-key"))
 }
 test("wrong_stream_filter_rejected_not_ignored"){
  expectReject{GateLog.query(tests,logArgs("system").put("kind","result"))}
  expectReject{GateLog.query(tests,logArgs("calls").put("level","ERROR"))}
 }
 test("logs_invalid_limit_types_rejected"){
  for(v in listOf<Any>(0,201,-1,1.2,"3"))expectReject{GateLog.query(tests,logArgs().put("limit",v))}
 }
 test("logs_unknown_fields_rejected"){expectReject{GateLog.query(tests,logArgs().put("path","/etc/passwd"))}}
 test("logs_invalid_stream_rejected"){expectReject{GateLog.query(tests,logArgs("../../secret"))}}
 test("logs_symlink_rejected"){
  val separate=File(root,"symlink");File(separate,"system").mkdirs();val target=File(root,"private-data");target.writeText("private-secret\n")
  Files.createSymbolicLink(File(separate,"system/gateway.log").toPath(),target.toPath());expectReject{GateLog.query(separate,logArgs("system"))}
 }
 test("logs_large_record_does_not_break_bound"){
  calls.writeText("x".repeat(200000)+"\n"+JSONObject().put("kind","result").put("req_id","r-12345678").put("ok",true)+"\n")
  val r=GateLog.query(tests,logArgs());check(r.getInt("scannedBytes")<=1048576&&r.getBoolean("partial")&&r.toString().length<80000)
 }
 test("logs_malformed_json_disclosed"){
  calls.writeText("{broken\n{}\n");val r=GateLog.query(tests,logArgs());check(r.getInt("malformedLines")==1&&r.getBoolean("partial"))
 }
 test("trace_invalid_request_id_rejected"){check(isError(rpc(server,"gw_trace_request",JSONObject().put("requestId","../../secret"))))}
 test("trace_missing_record_not_claimed_success"){
  val r=result(rpc(server,"gw_trace_request",JSONObject().put("requestId","r-00000000")));check(r.getJSONArray("records").length()==0&&!r.has("requestSucceeded"))
 }
 test("gatelog_write_masks_sensitive_fields"){
  GateLog.call(mapOf("kind" to "diagnostic","headers" to mapOf("Authorization" to "test-super-secret"),"body" to "private-message","err" to "Bearer no-visible-token"))
  val r=File(log,"calls").listFiles()!!.joinToString(""){it.readText()};check(!r.contains("test-super-secret")&&!r.contains("private-message")&&!r.contains("no-visible-token"))
 }
 test("legacy_read_log_remains_available"){
  GateLog.info("test","background-debug-checkpoint");val r=result(rpc(server,"gw_read_log",JSONObject().put("lines",10)));check(r.has("lines")&&r.getJSONArray("lines").toString().contains("background-debug-checkpoint"))
 }
 test("self_test_runs_real_gateway_without_config_change"){
  val before=ConfigStore.read(ctx).revision;val r=result(rpc(server,"gw_self_test"));check(r.getBoolean("ok")){r.toString()};check(r.getInt("passed")==10&&r.getInt("failed")==0);check(r.getInt("externalRequests")==0&&!r.getBoolean("persistentConfigChanged"));check(ConfigStore.read(ctx).revision==before&&GatewayService.current==null)
 }
 test("self_test_no_worker_leak"){
  Thread.sleep(400);check(Thread.getAllStackTraces().keys.none{it.isAlive&&it.name=="gw-selftest-upstream"})
 }
 test("self_test_repeated_and_real_control_plane_survives"){
  check(result(rpc(server,"gw_self_test")).getBoolean("ok"));check(server.isRunning());check(result(rpc(server,"gw_get_status")).getInt("providers")==0)
 }
 val upstream=HttpServer.create(InetSocketAddress("127.0.0.1",0),0);val hit=java.util.concurrent.atomic.AtomicInteger();val fallback=java.util.concurrent.atomic.AtomicInteger()
 val pool=Executors.newCachedThreadPool{r->Thread(r).apply{isDaemon=true}};upstream.executor=pool
 upstream.createContext("/"){e->val bytes=e.requestBody.readBytes();val req=JSONObject(String(bytes));hit.incrementAndGet();val mode=e.requestURI.path.split('/')[1];if(mode=="fallback")fallback.incrementAndGet()
  val stream=req.optBoolean("stream");val text=if(stream)"data: {\"choices\":[{\"delta\":{\"content\":\"sensitive-output\"}}]}\n\ndata: [DONE]\n\n" else "{\"choices\":[{\"message\":{\"content\":\"sensitive-output\"}}]}";val b=text.toByteArray()
  e.responseHeaders.set("Content-Type",if(stream)"text/event-stream" else "application/json");e.sendResponseHeaders(if(mode=="reject")429 else 200,b.size.toLong());e.responseBody.use{it.write(b)}
 }
 upstream.start()
 val policy=RatePolicy(ratePerMin=0.0,minIntervalMs=0.0,jitterMs=0.0,maxInflight=2)
 fun pp(id:String,path:String)=Provider(id,id,"http://127.0.0.1:${upstream.address.port}/$path",models=mapOf("m" to Model("m")),ratePolicy=policy)
 val p=pp("p","ok");val gw=GatewayServer(0,listOf(p),listOf(RuleSpec("default")),RateLimiter(mapOf("p" to policy)),LocalAccessTokens.gateway(ctx),upstreamReadTimeoutMs=1500);check(gw.start());GatewayService.current=gw
 try {
 test("live_probe_http_json_hides_body_and_returns_trace_id"){
  val r=result(rpc(server,"gw_probe_request",JSONObject().put("modelId","m").put("confirm",true)));check(r.getBoolean("ok")){r.toString()};check(r.getInt("maxUpstreamAttempts")==1&&!r.toString().contains("sensitive-output"));check(hit.get()==1)
  val trace=result(rpc(server,"gw_trace_request",JSONObject().put("requestId",r.getString("requestId"))));check(trace.getJSONArray("records").length()==3){trace.toString()}
 }
 test("live_probe_http_sse_checks_done_without_body_echo"){
  val r=result(rpc(server,"gw_probe_request",JSONObject().put("modelId","m").put("confirm",true).put("stream",true)));check(r.getBoolean("ok")&&!r.toString().contains("sensitive-output"));check(hit.get()==2)
 }
 test("live_probe_never_fails_over_on_429"){
  val a=pp("reject","reject");val b=pp("fallback","fallback");gw.update(listOf(a,b),listOf(RuleSpec("default")),revision=1,keys=emptyMap())
  val before=hit.get();val r=result(rpc(server,"gw_probe_request",JSONObject().put("modelId","m").put("confirm",true)));check(!r.getBoolean("ok")&&r.getInt("httpStatus")==502&&hit.get()==before+1&&fallback.get()==0)
 }
 test("runtime_snapshot_has_limits_not_secrets"){
  val r=result(rpc(server,"gw_debug_snapshot"));check(r.getBoolean("gatewayRunning"));val raw=r.toString();check(r.getJSONObject("runtime").getJSONArray("providers").length()==2);check(r.getJSONObject("runtime").getJSONArray("health").length()==2);check(!raw.contains(LocalAccessTokens.gateway(ctx))&&!raw.contains("apiKey"))
 }
 }finally{gw.stop();GatewayService.current=null;upstream.stop(0);pool.shutdownNow()}
 test("diagnostic_total_deadline_includes_dripping_headers"){
  val ss=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
  val thread=Thread{try{ss.accept().use{socket->
    socket.soTimeout=1000;val input=socket.getInputStream();val h=SocketIo.readHeader(input)!!;val head=SocketIo.parseRequestHead(h)
    SocketIo.readExactly(input,SocketIo.contentLength(head.headers,4096))
    for(c in "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".toByteArray()){socket.getOutputStream().write(c.toInt());socket.getOutputStream().flush();Thread.sleep(30)}
  }}catch(_:Exception){}}.apply{isDaemon=true;start()}
  val clazz=Class.forName("com.aqi.ccb.data.BackgroundDebug");val instance=clazz.getDeclaredField("INSTANCE").apply{isAccessible=true}.get(null)
  val method=clazz.getDeclaredMethod("request",Int::class.javaPrimitiveType,String::class.java,String::class.java,String::class.java,Int::class.javaPrimitiveType).apply{isAccessible=true}
  var threw=false;val start=System.nanoTime()
  try{method.invoke(instance,ss.localPort,"/v1/chat/completions","local-only","{}",200)}catch(_:java.lang.reflect.InvocationTargetException){threw=true}
  finally{ss.close();thread.join(2000)}
  val elapsed=(System.nanoTime()-start)/1_000_000;check(threw&&elapsed<1000){"total deadline exceeded: ${elapsed}ms"}
 }
 for(mode in listOf("timeout","truncated"))test("probe_${mode}_keeps_response_request_id"){
  val ss=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
  val thread=Thread{try{ss.accept().use{socket->
    socket.soTimeout=1000;val input=socket.getInputStream();val h=SocketIo.readHeader(input)!!;val head=SocketIo.parseRequestHead(h)
    SocketIo.readExactly(input,SocketIo.contentLength(head.headers,4096))
    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nX-Gateway-Request-Id: r-1234abcd\r\nContent-Length: 100\r\n\r\n{".toByteArray());socket.getOutputStream().flush()
    if(mode=="timeout")Thread.sleep(600)
  }}catch(_:Exception){}}.apply{isDaemon=true;start()}
  val clazz=Class.forName("com.aqi.ccb.data.BackgroundDebug");val instance=clazz.getDeclaredField("INSTANCE").apply{isAccessible=true}.get(null)
  val method=clazz.getDeclaredMethod("probe",Int::class.javaPrimitiveType,String::class.java,String::class.java,String::class.java,Boolean::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply{isAccessible=true}
  try{
   val r=method.invoke(instance,ss.localPort,"local-only","m","private-prompt",false,8,150) as JSONObject
   check(!r.getBoolean("ok")&&r.getBoolean("traceAvailable")&&r.getString("requestId")=="r-1234abcd"&&r.getInt("receivedBytes")==1){r.toString()}
   check(r.getString("failureStage")=="response_body"&&!r.toString().contains("private-prompt"))
  }finally{ss.close();thread.join(2000)}
 }
 test("probe_connect_failure_does_not_invent_request_id"){
  val ss=ServerSocket(0);val port=ss.localPort;ss.close()
  val clazz=Class.forName("com.aqi.ccb.data.BackgroundDebug");val instance=clazz.getDeclaredField("INSTANCE").apply{isAccessible=true}.get(null)
  val method=clazz.getDeclaredMethod("probe",Int::class.javaPrimitiveType,String::class.java,String::class.java,String::class.java,Boolean::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply{isAccessible=true}
  val r=method.invoke(instance,port,"local-only","m","private-prompt",false,8,150) as JSONObject
  check(!r.getBoolean("ok")&&!r.getBoolean("traceAvailable")&&r.isNull("requestId"))
 }
 test("route_explain_virtual_uses_bound_rule_no_network"){
  val pro=JSONObject().put("id","a").put("baseUrl","http://127.0.0.1:1").put("models",JSONArray().put(JSONObject().put("modelId","m")))
  ConfigStore.transact(ctx){s->s.providers.add(pro);s.rules.add(rule("first"));s.rules.add(rule("bound"));s.virtualModels.add(JSONObject().put("name","alias").put("ruleId","bound").put("members",JSONArray().put(JSONObject().put("providerId","a").put("modelId","m"))))}
  val r=result(rpc(server,"gw_explain_route",JSONObject().put("modelId","alias")));check(r.getString("rule")=="bound"&&r.getInt("networkRequests")==0&&r.getBoolean("dryRun"));check(ConfigStore.read(ctx).revision==1L)
 }
 test("lan_start_requires_extra_confirmation"){
  AppSettings.setGatewayLanEnabled(ctx,true);check(isError(rpc(server,"gw_gateway_control",JSONObject().put("action","start").put("confirm",true))));AppSettings.setGatewayLanEnabled(ctx,false)
 }
 test("android_dispatch_failure_not_reported_running"){
  val r=rpc(server,"gw_gateway_control",JSONObject().put("action","start").put("confirm",true));check(isError(r)&&!result(r).getBoolean("ok"))
 }
 test("self_test_after_config_error_releases_diagnostic_lock"){
  val f=File(ctx.filesDir,"config-state-v1.json");val b=f.readBytes();f.writeText("invalid")
  check(isError(rpc(server,"gw_self_test")));f.writeBytes(b);check(result(rpc(server,"gw_self_test")).getBoolean("ok"))
 }
 }finally{server.stop();GatewayService.current?.stop();GatewayService.current=null}
 println("SUMMARY|cases=$n|passed=${n-failed}|failed=$failed")
 if(failed>0)kotlin.system.exitProcess(1)
}
