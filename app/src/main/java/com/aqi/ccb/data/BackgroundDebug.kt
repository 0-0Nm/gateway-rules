package com.aqi.ccb.data

import android.content.Context
import com.aqi.ccb.gateway.ConfigStore
import com.aqi.ccb.gateway.GateLog
import com.aqi.ccb.gateway.GatewayRuntime
import com.aqi.ccb.gateway.McpConfig
import com.aqi.ccb.gateway.Provider
import com.aqi.ccb.gateway.VirtualModelStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bounded, foreground-independent diagnostics. Real probes and isolated tests never run concurrently. */
internal object BackgroundDebug {
    private val busy=java.util.concurrent.atomic.AtomicBoolean(false)
    fun integer(o:JSONObject,key:String,default:Int,min:Int,max:Int):Int {
        if(!o.has(key))return default
        val raw=o.opt(key);require(raw is Number && raw.toDouble().isFinite() && raw.toDouble()%1.0==0.0 && raw.toDouble() in min.toDouble()..max.toDouble()){ "$key out of range" }
        return raw.toInt()
    }
    private data class Response(val code:Int,val body:ByteArray,val requestId:String?,val contentType:String)
    private class ProbeReadFailure(val status:Int,val requestId:String?,val received:Int,cause:Exception):
        java.io.IOException("Diagnostic response incomplete",cause)

    private fun request(port:Int,path:String,key:String,body:String?=null,timeout:Int=2500):Response {
        require(port in 1..65535)
        val deadline=System.nanoTime()+timeout*1_000_000L
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1",port),minOf(timeout,2000));s.soTimeout=timeout
            val bytes=body?.toByteArray(Charsets.UTF_8)?:ByteArray(0)
            val head=(if(body==null)"GET" else "POST")+" $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $key\r\nX-Gateway-Max-Attempts: 1\r\nConnection: close\r\n"+
                (if(body==null)"" else "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")+"\r\n"
            s.getOutputStream().write(head.toByteArray(Charsets.US_ASCII));if(bytes.isNotEmpty())s.getOutputStream().write(bytes);s.getOutputStream().flush()
            val rawInput=s.getInputStream()
            val input=object:java.io.InputStream() {
                private fun remaining() {
                    val left=(deadline-System.nanoTime())/1_000_000
                    if(left<=0)throw java.net.SocketTimeoutException("Diagnostic deadline")
                    s.soTimeout=minOf(left,timeout.toLong()).toInt().coerceAtLeast(1)
                }
                override fun read():Int { remaining();return rawInput.read() }
                override fun read(b:ByteArray,off:Int,len:Int):Int { remaining();return rawInput.read(b,off,len) }
            }
            val header=com.aqi.ccb.gateway.SocketIo.readHeader(input)?:throw java.io.EOFException("Missing response headers")
            val lines=header.split("\r\n");val code=lines[0].split(' ').getOrNull(1)?.toIntOrNull()?:throw java.io.IOException("Invalid response")
            val headers=lines.drop(1).mapNotNull{val k=it.indexOf(':');if(k<1)null else it.substring(0,k).lowercase(java.util.Locale.ROOT) to it.substring(k+1).trim()}.toMap()
            val out=java.io.ByteArrayOutputStream();val buf=ByteArray(4096)
            val responseId=headers["x-gateway-request-id"]?.takeIf{it.matches(Regex("r-[a-f0-9]{8}"))}
            try {
                while(true){val left=(deadline-System.nanoTime())/1_000_000; if(left<=0)throw java.net.SocketTimeoutException("Diagnostic deadline")
                    s.soTimeout=minOf(left,timeout.toLong()).toInt().coerceAtLeast(1);val n=input.read(buf);if(n<0)break;if(n==0)continue
                    if(out.size()+n>1024*1024)throw java.io.IOException("Diagnostic response too large");out.write(buf,0,n)
                }
                headers["content-length"]?.let{if(it.toLongOrNull()!=out.size().toLong())throw java.io.EOFException("Incomplete diagnostic response")}
            } catch(e:Exception) {throw ProbeReadFailure(code,responseId,out.size(),e)}
            return Response(code,out.toByteArray(),responseId,headers["content-type"].orEmpty())
        }
    }
    private fun body(model:String,stream:Boolean,prompt:String="Reply OK.",maxTokens:Int=8):String = JSONObject()
        .put("model",model).put("stream",stream).put("max_tokens",maxTokens)
        .put("messages",JSONArray().put(JSONObject().put("role","user").put("content",prompt))).toString()
    fun probe(port:Int,key:String,model:String,prompt:String,stream:Boolean,maxTokens:Int,timeout:Int):JSONObject {
        if(!busy.compareAndSet(false,true))return JSONObject().put("ok",false).put("code","DIAGNOSTIC_BUSY").put("error","Another diagnostic is running")
        val start=System.nanoTime()
        try {
            val r=request(port,"/v1/chat/completions",key,body(model,stream,prompt,maxTokens),timeout)
            val protocolOk=runCatching {
                if(stream){check(r.contentType.substringBefore(';').trim().equals("text/event-stream",true));val tracker=com.aqi.ccb.gateway.SocketIo.SseCompletion();tracker.accept(r.body,r.body.size);tracker.requireComplete()}
                else {val tokener=org.json.JSONTokener(String(r.body,Charsets.UTF_8));val obj=tokener.nextValue();check(obj is JSONObject && tokener.nextClean()==0.toChar() && (!obj.has("error") || obj.isNull("error")) && obj.optJSONArray("choices")?.length()?.let{it>0}==true)}
            }.isSuccess
            val ok=r.code in 200..299 && protocolOk
            return JSONObject().put("ok",ok).put("httpStatus",r.code).put("requestId",r.requestId?:JSONObject.NULL)
                .put("receivedBytes",r.body.size).put("traceAvailable",r.requestId!=null).put("protocolComplete",protocolOk).put("stream",stream)
                .put("maxTokensRequested",maxTokens).put("gatewayRequests",1).put("maxUpstreamAttempts",1)
                .put("bodyReturned",false).put("elapsedMs",(System.nanoTime()-start)/1_000_000)
                .put("note","May incur one upstream request; transport cancellation cannot guarantee provider-side billing cancellation")
        }catch(e:Exception){
            val partial=e as? ProbeReadFailure
            return JSONObject().put("ok",false).put("error",(partial?.cause?:e).javaClass.simpleName).put("code","PROBE_FAILED")
                .put("requestId",partial?.requestId?:JSONObject.NULL).put("traceAvailable",partial?.requestId!=null)
                .put("httpStatus",partial?.status?:JSONObject.NULL).put("receivedBytes",partial?.received?:0)
                .put("failureStage",if(partial==null)"connect_or_headers" else "response_body")
                .put("gatewayRequests",1).put("maxUpstreamAttempts",1).put("bodyReturned",false).put("elapsedMs",(System.nanoTime()-start)/1_000_000)
        }
        finally{busy.set(false)}
    }
    fun selfTest(context:Context):JSONObject {
        if(!busy.compareAndSet(false,true))return JSONObject().put("ok",false).put("code","DIAGNOSTIC_BUSY").put("error","Another diagnostic is running")
        val real=com.aqi.ccb.gateway.GatewayService.current
        val id="self-"+java.util.UUID.randomUUID().toString().take(8);val token=java.util.UUID.randomUUID().toString()
        val cases=JSONArray();val stop=java.util.concurrent.atomic.AtomicBoolean(false);val hits=java.util.concurrent.atomic.AtomicInteger();val fallbackHits=java.util.concurrent.atomic.AtomicInteger()
        var upstream:java.net.ServerSocket?=null;var gateway:com.aqi.ccb.gateway.GatewayServer?=null;var worker:Thread?=null
        fun test(name:String,fn:()->Boolean){val start=System.nanoTime();try{cases.put(JSONObject().put("name",name).put("passed",fn()).put("elapsedMs",(System.nanoTime()-start)/1_000_000))}
            catch(e:Exception){cases.put(JSONObject().put("name",name).put("passed",false).put("errorType",e.javaClass.simpleName))}}
        try {
            val before=ConfigStore.read(context).revision
            val up=java.net.ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"));up.soTimeout=250;upstream=up
            worker=Thread({while(!stop.get())try{up.accept().use{s->s.soTimeout=1500;val input=s.getInputStream()
                val h=com.aqi.ccb.gateway.SocketIo.readHeader(input)?:return@use
                val request=com.aqi.ccb.gateway.SocketIo.parseRequestHead(h)
                val raw=com.aqi.ccb.gateway.SocketIo.readExactly(input,com.aqi.ccb.gateway.SocketIo.contentLength(request.headers,4096))
                hits.incrementAndGet();if(request.path.startsWith("/fallback"))fallbackHits.incrementAndGet()
                val j=JSONObject(String(raw));val isStream=j.optBoolean("stream");val fail=request.path.startsWith("/reject")
                val text=if(fail)"{}" else if(isStream)"data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n" else "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}"
                val b=text.toByteArray();s.getOutputStream().write(("HTTP/1.1 ${if(fail)429 else 200} Result\r\nContent-Type: ${if(isStream)"text/event-stream" else "application/json"}\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray()+b);s.getOutputStream().flush()
            }}catch(_:java.net.SocketTimeoutException){}catch(_:Exception){if(!stop.get())Thread.yield()}},"gw-selftest-upstream").apply{isDaemon=true;start()}
            val model=com.aqi.ccb.gateway.Model("self-model")
            val policy=com.aqi.ccb.gateway.RatePolicy(ratePerMin=0.0,minIntervalMs=0.0,jitterMs=0.0,maxInflight=2)
            fun provider(name:String,path:String)=com.aqi.ccb.gateway.Provider(name,name,"http://127.0.0.1:${up.localPort}/$path",ratePolicy=policy,models=mapOf(model.modelId to model))
            val p=provider(id,"ok");val rule=com.aqi.ccb.gateway.RuleSpec(id)
            val limiter=com.aqi.ccb.gateway.RateLimiter(mapOf(p.id to policy));val gw=com.aqi.ccb.gateway.GatewayServer(0,listOf(p),listOf(rule),limiter,token,upstreamReadTimeoutMs=1500);gateway=gw
            check(gw.start());val port=gw.listeningPortPublic();check(port>0)
            test("auth_rejected_before_upstream"){val n=hits.get();request(port,"/health","wrong").code==401 && hits.get()==n}
            test("health_loopback"){request(port,"/health",token).code==200}
            test("models_json"){val r=request(port,"/v1/models",token);r.code==200 && JSONObject(String(r.body)).getJSONArray("data").getJSONObject(0).getString("id")==model.modelId}
            test("chat_json_with_request_id"){val r=request(port,"/v1/chat/completions",token,body(model.modelId,false));r.code==200 && JSONObject(String(r.body)).getJSONArray("choices").length()==1 && r.requestId?.matches(Regex("r-[a-f0-9]{8}"))==true}
            test("chat_sse_complete"){val r=request(port,"/v1/chat/completions",token,body(model.modelId,true));val tr=com.aqi.ccb.gateway.SocketIo.SseCompletion();tr.accept(r.body,r.body.size);tr.requireComplete();r.code==200}
            test("unknown_model_no_upstream"){val n=hits.get();val r=request(port,"/v1/chat/completions",token,body("absent",false));r.code==404 && hits.get()==n}
            test("invalid_json_no_upstream"){val n=hits.get();val r=request(port,"/v1/chat/completions",token,"[]");r.code==400 && hits.get()==n}
            test("explicit_single_attempt_no_fallback"){
                val a=provider(id+"-reject","reject");val b=provider(id+"-fallback","fallback");gw.update(listOf(a,b),listOf(rule),revision=1,keys=emptyMap())
                val n=hits.get();val r=request(port,"/v1/chat/completions",token,body(model.modelId,false));r.code==502 && hits.get()==n+1 && fallbackHits.get()==0
            }
            test("health_snapshot_scopes_429_provider_cooldown"){
                val a=provider(id+"-health","reject");gw.update(listOf(a),listOf(rule),revision=2,keys=emptyMap())
                val r=request(port,"/v1/chat/completions",token,body(model.modelId,false));val h=gw.healthSnapshotPublic().single()
                r.code==502 && h.providerId==a.id && h.state=="cooldown" && h.kind=="rate_limit" && h.scope=="provider" &&
                    h.modelId==model.modelId && h.remainingMs>0 && h.retrySafe && h.localImpact=="provider_cooldown"
            }
            test("real_config_and_gateway_unchanged"){ConfigStore.read(context).revision==before && com.aqi.ccb.gateway.GatewayService.current===real}
            val failed=(0 until cases.length()).count{!cases.getJSONObject(it).getBoolean("passed")}
            return JSONObject().put("ok",failed==0).put("scope","isolated_real_gateway_loopback").put("cases",cases)
                .put("passed",cases.length()-failed).put("failed",failed).put("externalRequests",0).put("testUpstreamHits",hits.get())
                .put("persistentConfigChanged",false).put("foregroundActivityStarted",false).put("testId",id)
                .put("notCovered",JSONArray(listOf("real upstream","Android service start restrictions","UI rendering","long duration")))
        }finally{
            stop.set(true);runCatching{gateway?.stop()};runCatching{upstream?.close()};runCatching{worker?.join(1000)}
            for(suffix in listOf("","-reject","-fallback","-health"))com.aqi.ccb.gateway.RuntimeState.errorWindow.remove(id+suffix)
            busy.set(false)
        }
    }
}
