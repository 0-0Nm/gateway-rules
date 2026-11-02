package com.aqi.ccb.gateway

import com.aqi.ccb.data.*
import java.io.*
import java.net.*
import java.lang.reflect.*
import java.util.concurrent.atomic.AtomicInteger
import com.sun.net.httpserver.HttpServer
import org.json.*

private var count=0
private var failed=0
private fun case(name:String, body:()->Boolean) {
    count++
    try { if(body()) println("PASS|$name") else {failed++;println("FAIL|$name")} }
    catch(t:Throwable){failed++;println("FAIL|$name|${t.javaClass.simpleName}:${t.message}")}
}
private val callsA=AtomicInteger()
private val callsB=AtomicInteger()
private fun chat(gw:GatewayServer,body:String,out:OutputStream=ByteArrayOutputStream()):String {
    val method=GatewayServer::class.java.getDeclaredMethod("handleChat",OutputStream::class.java,String::class.java,ByteArray::class.java)
    method.isAccessible=true
    try {method.invoke(gw,out,"audit-local",body.toByteArray())}
    catch(t:InvocationTargetException){throw t.targetException}
    return if(out is ByteArrayOutputStream) out.toString("UTF-8") else ""
}
private val body="""{"model":"audit-model","messages":[{"role":"user","content":"audit"}],"stream":false}"""
fun main(args:Array<String>) {
    val root=File(args[0]);root.mkdirs()
    val upstream=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    for((path,counter) in listOf("/a/chat/completions" to callsA,"/b/chat/completions" to callsB)){
        upstream.createContext(path) { e ->
            e.requestBody.use{it.readBytes()};counter.incrementAndGet()
            val response="""{"choices":[{"message":{"content":"audit-ok"}}]}""".toByteArray()
            e.responseHeaders.add("Content-Type","application/json")
            e.sendResponseHeaders(200,response.size.toLong())
            e.responseBody.use{it.write(response)}
        }
    }
    upstream.start()
    fun p(id:String,weight:Double=1.0,policy:RatePolicy=RatePolicy(ratePerMin=0.0,minIntervalMs=0.0,jitterMs=0.0)) =
        Provider(id,id,"http://127.0.0.1:${upstream.address.port}/$id",weight=weight,ratePolicy=policy,
            models=mapOf("audit-model" to Model("audit-model")))
    fun gw(ps:List<Provider>, rules:List<RuleSpec> = listOf(RuleSpec("default")), vms:List<VirtualModel> = emptyList()):GatewayServer =
        GatewayServer(0,ps,rules,RateLimiter(ps.associate{it.id to it.ratePolicy}),localAuth="audit-fixture-only",virtualModels=vms)
    case("hot_reload_updates_enforced_rate_not_just_display") {
        callsA.set(0)
        val a=p("a");val g=gw(listOf(a));chat(g,body)
        val next=a.copy(ratePolicy=a.ratePolicy.copy(minIntervalMs=60_000.0))
        g.update(listOf(next),listOf(RuleSpec("default")))
        val response=chat(g,body)
        println("DETAIL|hot_reload_upstream_calls=${callsA.get()}|second_status=${response.lineSequence().firstOrNull()}")
        callsA.get()==1
    }
    case("downstream_disconnect_must_not_retry_other_provider") {
        callsA.set(0);callsB.set(0)
        val a=p("a");val b=p("b");val g=gw(listOf(a,b))
        val broken=object:OutputStream(){override fun write(b:Int){throw SocketException("audit downstream disconnected")}}
        try{chat(g,body,broken)}catch(_:IOException){}
        println("DETAIL|disconnect_calls_a=${callsA.get()}|b=${callsB.get()}|upstream_error_counters=${a.errors},${b.errors}")
        callsA.get()==1 && callsB.get()==0 && a.errors==0 && b.errors==0
    }
    case("virtual_model_uses_bound_rule_id") {
        callsA.set(0);callsB.set(0)
        val a=p("a",10.0);val b=p("b",1.0)
        val r1=RuleSpec("first-rule",select=listOf(OpCall("take",mapOf("n" to 1))))
        val r2=RuleSpec("bound-rule",select=listOf(OpCall("where",mapOf("expr" to "weight < 2"))))
        val vm=VirtualModel("alias",ruleId="bound-rule",members=listOf(VirtualModel.Member("a","audit-model"),VirtualModel.Member("b","audit-model")))
        chat(gw(listOf(a,b),listOf(r1,r2),listOf(vm)),body.replace("audit-model","alias"))
        println("DETAIL|virtual_rule_calls_a=${callsA.get()}|b=${callsB.get()}")
        callsA.get()==0 && callsB.get()==1
    }
    case("nested_model_field_must_not_override_root_model") {
        callsA.set(0)
        val response=chat(gw(listOf(p("a"))),"""{"messages":[{"role":"user","model":"wrong-model","content":"audit"}],"model":"audit-model"}""")
        println("DETAIL|nested_model_status=${response.lineSequence().firstOrNull()}")
        callsA.get()==1
    }
    case("truncated_upstream_stream_must_not_report_success") {
        val conn=object:HttpURLConnection(URL("http://127.0.0.1/")){
            override fun getResponseCode()=200
            override fun getContentType()="text/event-stream"
            override fun connect(){}
            override fun disconnect(){}
            override fun usingProxy()=false
            override fun getInputStream():InputStream=object:InputStream(){override fun read():Int{throw IOException("audit truncated upstream")}}
        }
        val method=GatewayServer::class.java.getDeclaredMethod("relay",OutputStream::class.java,HttpURLConnection::class.java,Boolean::class.javaPrimitiveType)
        method.isAccessible=true
        try{method.invoke(gw(listOf(p("a"))),ByteArrayOutputStream(),conn,true);false}
        catch(t:InvocationTargetException){t.targetException is IOException}
    }
    case("save_failure_propagates_to_caller") {
        val notDirectory=File(root,"not-a-directory");notDirectory.writeText("audit")
        val ctx=android.content.Context(notDirectory)
        val draft=ProviderDraft(id="audit",name="audit",baseUrl="http://127.0.0.1:1",models=listOf(ModelInfo("audit-model")))
        try { val ok=ProviderRepo.save(ctx,draft);println("DETAIL|save_to_invalid_directory_returned=$ok");!ok }
        catch(_:IOException){true}
    }
    case("deleting_last_provider_keeps_runtime_empty") {
        val dir=File(root,"delete-last");dir.mkdirs();val ctx=android.content.Context(dir)
        ProviderRepo.save(ctx,ProviderDraft(id="audit",name="audit",baseUrl="http://127.0.0.1:1",models=listOf(ModelInfo("audit-model"))))
        ProviderRepo.delete(ctx,"audit")
        val ps=ProviderRepo.runtimeProviders(ctx)
        println("DETAIL|runtime_after_delete=${ps.map{it.id}}")
        ps.isEmpty()
    }
    case("provider_edit_preserves_soft_rate_policy") {
        val raw=JSONObject("""{"id":"audit","name":"audit","baseUrl":"http://127.0.0.1:1","models":[{"modelId":"audit"}],"rateLimit":{"softRateSec":13,"softRateMaxSec":29}}""")
        val updated=ProviderDraft.fromJson(raw).copy(weight="2").toJson()
        updated.getJSONObject("rateLimit").optInt("softRateSec",-1)==13
    }
    case("empty_modality_roundtrip_preserves_explicit_empty") {
        val initial=ModelInfo("audit",inputModalities=emptyList(),outputModalities=emptyList())
        val again=ModelInfo.fromJson(initial.toJson())
        again.inputModalities.isEmpty() && again.outputModalities.isEmpty()
    }
    case("mcp_business_failure_has_error_flag") {
        val cfg=java.lang.reflect.Proxy.newProxyInstance(McpConfig::class.java.classLoader,arrayOf(McpConfig::class.java)) { _,_,_ ->
            """{"ok":false,"error":"audit validation failure"}"""
        } as McpConfig
        val server=McpServer(0,0,"audit-fixture-only"){cfg}
        val method=McpServer::class.java.getDeclaredMethod("handleRpc",String::class.java);method.isAccessible=true
        val response=method.invoke(server,"""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"gw_update_provider","arguments":{"providerId":"missing","patch":{}}}}""") as String
        val result=JSONObject(response).getJSONObject("result")
        println("DETAIL|mcp_failure_isError=${result.getBoolean("isError")}")
        result.getBoolean("isError")
    }
    upstream.stop(0)
    println("SUMMARY|cases=$count|passed=${count-failed}|failed=$failed")
    if(failed>0) kotlin.system.exitProcess(1)
}
