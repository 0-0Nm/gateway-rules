package com.aqi.ccb.gateway

import java.io.*
import java.net.*
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import com.sun.net.httpserver.HttpServer
import org.json.*

private var cases = 0
private var failed = 0
private val ids = AtomicInteger()
private lateinit var logDir: File
private val body = """{"model":"m","messages":[{"role":"user","content":"true"}],"stream":false}"""
private fun checkCase(name: String, fn: () -> Unit) {
    cases++
    try { fn(); println("PASS|$name") }
    catch (t: Throwable) { failed++; println("FAIL|$name|${t.javaClass.simpleName}:${t.message}");t.printStackTrace() }
    finally { Thread.interrupted() }
}
private fun records(id: String): List<JSONObject> = File(logDir,"calls").listFiles().orEmpty()
    .flatMap { it.readLines() }.map { JSONObject(it) }.filter { it.optString("req_id") == id }
private fun terminal(id: String): JSONObject {
    val items = records(id).filter { it.optString("kind") == "result" }
    check(items.size == 1) { "terminal_count=${items.size} id=$id" }
    return items.single()
}
private fun chat(g: GatewayServer, data: String = body, out: OutputStream = ByteArrayOutputStream()): Pair<String,String> {
    val id = "life-${ids.incrementAndGet()}"
    val m=GatewayServer::class.java.getDeclaredMethod("handleChat",OutputStream::class.java,String::class.java,ByteArray::class.java)
    m.isAccessible=true
    try { m.invoke(g,out,id,data.toByteArray()) } catch(t:InvocationTargetException){throw t.targetException}
    terminal(id)
    return id to if(out is ByteArrayOutputStream)out.toString("UTF-8") else ""
}
private fun provider(id:String,url:String)=Provider(id,id,url,
    models=mapOf("m" to Model("m")),ratePolicy=RatePolicy(ratePerMin=0.0,minIntervalMs=0.0,jitterMs=0.0,maxInflight=1))
private class Rig(val ps:List<Provider>, val rules:List<RuleSpec> = listOf(RuleSpec("default"))): AutoCloseable {
    val limiter=RateLimiter(ps.associate{it.id to it.ratePolicy})
    val g=GatewayServer(0,ps,rules,limiter,"life-test-token",upstreamReadTimeoutMs=300)
    fun released(){ps.forEach{check(limiter.peek(it.id)["inflight"]==0){"leaked slot ${it.id} ${limiter.peek(it.id)}"}}}
    fun port():Int {
        check(g.start())
        val f=GatewayServer::class.java.getDeclaredField("serverSocket");f.isAccessible=true
        return (f.get(g) as ServerSocket).localPort
    }
    override fun close(){g.stop();released()}
}
private class Upstream:AutoCloseable {
    val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    val executor=Executors.newCachedThreadPool{r->Thread(r).apply{isDaemon=true}}
    val counts=ConcurrentHashMap<String,AtomicInteger>()
    val entered=CountDownLatch(1)
    init {
        server.executor=executor
        server.createContext("/"){e->
            val route=e.requestURI.path.split('/')[1]
            counts.computeIfAbsent(route){AtomicInteger()}.incrementAndGet()
            e.requestBody.use{it.readBytes()}
            try {
                var code=200
                var text="""{"choices":[{"message":{"content":"ok"}}]}"""
                var type="application/json"
                when {
                    route.startsWith("status") -> {code=route.removePrefix("status").toInt();text="SECRET_UPSTREAM_BODY"}
                    route=="slow" -> {entered.countDown();Thread.sleep(1000)}
                    route=="sse" -> {type="text/event-stream";text="data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\r\n\r\ndata: [DONE]\r\n\r\n"}
                    route=="eof" -> {type="text/event-stream";text="data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"}
                    route=="sse-error" -> {type="text/event-stream";text="data: {\"error\":{\"message\":\"SECRET_UPSTREAM_BODY\"}}\n\ndata: [DONE]\n\n"}
                }
                val bytes=text.toByteArray()
                e.responseHeaders.set("Content-Type",type)
                if(code in 300..399)e.responseHeaders.set("Location","http://127.0.0.1:${server.address.port}/target/chat/completions")
                e.sendResponseHeaders(code,bytes.size.toLong())
                e.responseBody.use{os->if(route=="sse")bytes.forEach{os.write(it.toInt());os.flush()}else os.write(bytes)}
            }catch(_:Exception){}finally{e.close()}
        }
        server.start()
    }
    fun p(route:String,id:String=route)=provider(id,"http://127.0.0.1:${server.address.port}/$route")
    fun n(route:String)=counts[route]?.get()?:0
    override fun close(){server.stop(0);executor.shutdownNow()}
}
private class Raw(val reply:String,val pause:Long=0):AutoCloseable {
    val ss=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
    val count=AtomicInteger()
    val worker=Thread{
        try{ss.accept().use{s->
            count.incrementAndGet();s.soTimeout=2000
            val input=s.getInputStream();val h=SocketIo.readHeader(input)?:return@use
            val len=SocketIo.contentLength(SocketIo.parseRequestHead(h).headers,SocketIo.MAX_GATEWAY_BODY_BYTES)
            SocketIo.readExactly(input,len)
            s.getOutputStream().write(reply.toByteArray());s.getOutputStream().flush()
            if(pause>0)Thread.sleep(pause)
        }}catch(_:Exception){}
    }.apply{isDaemon=true;start()}
    fun p()=provider("raw","http://127.0.0.1:${ss.localPort}")
    override fun close(){ss.close();worker.join(2000)}
}
private fun send(s:Socket,body:String= com.aqi.ccb.gateway.body){
    val bytes=body.toByteArray()
    s.getOutputStream().write(("POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1:${s.port}\r\nAuthorization: Bearer life-test-token\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()+bytes)
    s.getOutputStream().flush()
}
private fun waitUntil(fn:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!fn() && System.nanoTime()<end)Thread.sleep(15);check(fn()){"wait deadline"}}
fun main(args:Array<String>){
    logDir=File(args[0],"logs");GateLog.init(logDir)
    checkCase("success_only_after_complete_body_and_one_terminal"){
        Upstream().use{u->Rig(listOf(u.p("ok"))).use{r->
            val (id,out)=chat(r.g);check(out.startsWith("HTTP/1.1 200"));check(terminal(id).getBoolean("ok"));check(r.g.totalOk==1L);r.released()
            check(records(id).count{it.optString("kind")=="attempt" && it.getBoolean("ok")}==1)
            check(r.g.clientDisconnects==0L)
        }}
    }
    for(limit in listOf(0,60,120))checkCase("downstream_write_failure_${limit}_no_retry_no_health_penalty"){
        Upstream().use{u->Rig(listOf(u.p("ok","a"),u.p("other","b"))).use{r->
            val out=object:OutputStream(){var n=0;override fun write(b:Int){if(n++>=limit)throw SocketException("closed")}}
            val(id,_)=chat(r.g,out=out)
            check(u.n("other")==0);check(r.ps.all{it.errors==0});check(!terminal(id).getBoolean("ok"));check(r.g.totalFail==0L);check(r.g.clientDisconnects==1L)
            r.released()
        }}
    }
    checkCase("flush_failure_is_cancellation"){
        Upstream().use{u->Rig(listOf(u.p("ok"),u.p("other"))).use{r->
            val out=object:ByteArrayOutputStream(){override fun flush(){throw SocketException("flush closed")}}
            val(id,_)=chat(r.g,out=out);check(!terminal(id).getBoolean("ok"));check(u.n("other")==0);check(r.ps.all{it.errors==0});r.released()
        }}
    }
    for(route in listOf("sse","eof","sse-error"))checkCase("sse_${route}_terminal_and_no_second_response"){
        Upstream().use{u->Rig(listOf(u.p(route),u.p("other"))).use{r->
            val(id,out)=chat(r.g,body.replace("\"stream\":false","\"stream\":true"))
            check(terminal(id).getBoolean("ok")== (route=="sse"));check(u.n("other")==0)
            check(Regex("HTTP/1.1").findAll(out).count()==1);check(r.g.totalOk==(if(route=="sse")1L else 0L));r.released()
        }}
    }
    for((name,response) in listOf(
        "length_eof" to "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{",
        "chunk_eof" to "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n20\r\nshort"))checkCase("${name}_no_success_or_retry"){
        Raw(response).use{raw->Upstream().use{u->Rig(listOf(raw.p(),u.p("other"))).use{r->
            r.ps[0].errors=3
            val(id,out)=chat(r.g);check(!terminal(id).getBoolean("ok"));check(r.ps[0].errors==4);check(u.n("other")==0)
            check(Regex("HTTP/1.1").findAll(out).count()==1);check(r.g.totalOk==0L);check(r.g.totalFail==1L);r.released()
        }}}
    }
    checkCase("headers_timeout_after_post_does_not_retry_or_replay"){
        Upstream().use{u->Rig(listOf(u.p("slow"),u.p("other"))).use{r->
            val(id,out)=chat(r.g);check(!terminal(id).getBoolean("ok"));check(out.startsWith("HTTP/1.1 502"));check(u.n("slow")==1);check(u.n("other")==0);r.released()
        }}
    }
    checkCase("body_timeout_after_commit_no_second_http"){
        Raw("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{",700).use{raw->Upstream().use{u->Rig(listOf(raw.p(),u.p("other"))).use{r->
            val(id,out)=chat(r.g);check(!terminal(id).getBoolean("ok"));check(!out.contains("502"));check(u.n("other")==0);r.released()
        }}}
    }
    for(code in listOf(400,401,403,500,502,307,429,503))checkCase("explicit_http_${code}_failover_policy"){
        Upstream().use{u->Rig(listOf(u.p("status$code"),u.p("other"))).use{r->
            val(id,out)=chat(r.g);val allowed=code in setOf(429,503)
            check(u.n("other")==if(allowed)1 else 0);check(terminal(id).getBoolean("ok")==allowed);check(u.n("target")==0)
            check(!out.contains("SECRET_UPSTREAM_BODY"));check(records(id).none{it.toString().contains("SECRET_UPSTREAM_BODY")});r.released()
        }}
    }
    checkCase("connection_refused_before_post_can_failover"){
        val ss=ServerSocket(0);val port=ss.localPort;ss.close()
        Upstream().use{u->Rig(listOf(provider("dead","http://127.0.0.1:$port"),u.p("other"))).use{r->
            val(id,_)=chat(r.g);check(terminal(id).getBoolean("ok"));check(u.n("other")==1);r.released()
        }}
    }
    for((label,bad) in listOf("array" to "[]","trailing" to "$body {}","null_model" to "{\"model\":null}","number_model" to "{\"model\":4}","string_stream" to "{\"model\":\"m\",\"stream\":\"true\"}"))checkCase("invalid_${label}_no_dispatch_unique_terminal"){
        Upstream().use{u->Rig(listOf(u.p("ok"))).use{r->
            val(id,out)=chat(r.g,bad);check(out.startsWith("HTTP/1.1 400"));check(u.n("ok")==0);check(!terminal(id).getBoolean("ok"));check(r.g.localRejects==1L);check(r.g.totalFail==0L)
        }}
    }
    checkCase("unimplemented_execute_rejected_not_silently_downgraded"){
        Upstream().use{u->Rig(listOf(u.p("ok")),listOf(RuleSpec("x",execute=OpCall("race")))).use{r->
            val(id,out)=chat(r.g);check(out.startsWith("HTTP/1.1 422"));check(u.n("ok")==0);check(!terminal(id).getBoolean("ok"))
        }}
    }
    checkCase("client_half_close_still_receives_success"){
        Upstream().use{u->Rig(listOf(u.p("ok"))).use{r->
            Socket("127.0.0.1",r.port()).use{s->s.soTimeout=3000;send(s);s.shutdownOutput();val out=s.getInputStream().readBytes().toString(Charsets.UTF_8);check(out.startsWith("HTTP/1.1 200"))}
            waitUntil{r.g.inflightPublic()==0};check(r.g.totalOk==1L);check(r.g.clientDisconnects==0L);r.released()
        }}
    }
    checkCase("real_rst_while_upstream_waiting_cancels_without_retry"){
        Upstream().use{u->Rig(listOf(u.p("slow"),u.p("other"))).use{r->
            val s=Socket("127.0.0.1",r.port());send(s);check(u.entered.await(2,TimeUnit.SECONDS));s.setSoLinger(true,0);s.close()
            waitUntil{r.g.inflightPublic()==0};check(u.n("other")==0);check(r.ps.all{it.errors==0});check(r.g.totalOk==0L);check(r.g.totalFail==0L);check(r.g.clientDisconnects==1L);r.released()
        }}
    }
    checkCase("server_stop_cancels_active_upstream_and_releases"){
        Upstream().use{u->Rig(listOf(u.p("slow"),u.p("other"))).use{r->
            Socket("127.0.0.1",r.port()).use{s->send(s);check(u.entered.await(2,TimeUnit.SECONDS));val start=System.nanoTime();r.g.stop();check(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1));waitUntil{r.g.inflightPublic()==0}}
            check(u.n("other")==0);check(r.ps.all{it.errors==0});check(r.g.totalFail==0L);r.released()
        }}
    }
    checkCase("http_util_truncated_content_length_not_ok"){
        Raw("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{").use{raw->val r=HttpUtil.get("http://127.0.0.1:${raw.ss.localPort}/",timeoutMs=1000);check(!r.ok && r.error!=null && r.code==200)}
    }
    checkCase("sse_chunk_split_multiline_crlf_and_terminal"){
        val t=SocketIo.SseCompletion();val b="data: {\r\ndata: \"choices\":[]}\r\n\r\ndata: [DONE]\r\n\r\n".toByteArray()
        b.forEach{t.accept(byteArrayOf(it),1)};t.requireComplete()
    }
    for((name,text) in listOf("no_done" to "data: {}\n\n","unterminated_done" to "data: [DONE]\n","huge_line" to "data: ${"a".repeat(262145)}"))checkCase("sse_${name}_rejects"){
        var threw=false;try{val t=SocketIo.SseCompletion();val b=text.toByteArray();t.accept(b,b.size);t.requireComplete()}catch(_:IOException){threw=true};check(threw)
    }
    checkCase("concurrent_terminal_jsonl_records_remain_parseable"){
        val pool=Executors.newFixedThreadPool(8)
        try{val fs=(1..100).map{i->pool.submit{GateLog.requestEnd("log-parallel-$i",true,"x",1.0,listOf("ok"))}};fs.forEach{it.get(5,TimeUnit.SECONDS)}}finally{pool.shutdownNow()}
        for(i in 1..100)check(terminal("log-parallel-$i").getBoolean("ok"))
    }
    checkCase("stream_request_with_json_response_rejected_without_failover"){
        Upstream().use{u->Rig(listOf(u.p("ok"),u.p("other"))).use{r->
            val(id,out)=chat(r.g,body.replace("\"stream\":false","\"stream\":true"))
            check(out.startsWith("HTTP/1.1 502"));check(!terminal(id).getBoolean("ok"));check(u.n("other")==0);r.released()
        }}
    }
    checkCase("local_rate_reject_has_one_terminal_and_no_upstream_failure"){
        Upstream().use{u->val p=u.p("ok").copy(ratePolicy=RatePolicy(ratePerMin=1.0,burst=1,minIntervalMs=60000.0,jitterMs=0.0));Rig(listOf(p)).use{r->
            chat(r.g);val(id,out)=chat(r.g);check(out.startsWith("HTTP/1.1 429"));check(!terminal(id).getBoolean("ok"));check(u.n("ok")==1);check(r.g.totalFail==0L);check(r.g.localRejects==1L);r.released()
        }}
    }
    checkCase("preinterrupted_request_never_acquires_or_dispatches"){
        Upstream().use{u->Rig(listOf(u.p("ok"))).use{r->
            Thread.currentThread().interrupt();val(id,_)=chat(r.g);Thread.interrupted()
            check(!terminal(id).getBoolean("ok"));check(u.n("ok")==0);check(r.g.totalFail==0L);r.released()
        }}
    }
    checkCase("no_enabled_rule_is_local_reject_with_terminal"){
        Upstream().use{u->Rig(listOf(u.p("ok")),emptyList()).use{r->
            val(id,out)=chat(r.g);check(out.startsWith("HTTP/1.1 503"));check(u.n("ok")==0);check(!terminal(id).getBoolean("ok"));check(r.g.totalFail==0L);check(r.g.localRejects==1L)
        }}
    }
    checkCase("http_util_oversize_body_rejected_before_allocation"){
        Raw("HTTP/1.1 200 OK\r\nContent-Length: 9000000\r\nConnection: close\r\n\r\n").use{raw->
            val r=HttpUtil.get("http://127.0.0.1:${raw.ss.localPort}/",timeoutMs=1000);check(!r.ok && r.error!=null);check(r.body.isEmpty())
        }
    }
    checkCase("every_socket_chat_request_has_exactly_one_terminal"){
        val all=File(logDir,"calls").listFiles().orEmpty().flatMap{it.readLines()}.map{JSONObject(it)}
        val requests=all.filter{it.optString("kind")=="request" && it.optString("path")=="/v1/chat/completions"}
        check(requests.size==3)
        requests.forEach{terminal(it.getString("req_id"))}
    }
    checkCase("cancel_observer_and_abort_threads_reclaimed"){
        waitUntil{Thread.getAllStackTraces().keys.none{it.isAlive && it.name in setOf("gw-cancel-watch","gw-abort")}}
    }
    println("SUMMARY|cases=$cases|passed=${cases-failed}|failed=$failed")
    if(failed>0)kotlin.system.exitProcess(1)
}
