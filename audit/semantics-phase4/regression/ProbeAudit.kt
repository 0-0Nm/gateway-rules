package com.aqi.ccb.gateway
import java.net.*
import java.io.*
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
private var count=0
private var failures=0
private fun case(name:String, body:()->Boolean){
 count++
 try{if(body())println("PASS|$name") else{failures++;println("FAIL|$name")}}
 catch(t:Throwable){failures++;println("FAIL|$name|${t.javaClass.simpleName}:${t.message}")}
}
fun main(){
 val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
 server.createContext("/chat/completions"){e->
  val body=JSONObject(e.requestBody.use{String(it.readBytes())})
  val stream=body.optBoolean("stream",false)
  val text=if(stream) "data: [DONE]\n\n" else """{"choices":[{"message":{"content":"not a tool call","tool_calls":null,"function_call":null}}]}"""
  val bytes=text.toByteArray()
  e.responseHeaders.add("Content-Type",if(stream)"text/event-stream" else "application/json")
  e.sendResponseHeaders(200,bytes.size.toLong());e.responseBody.use{it.write(bytes)}
 }
 server.start()
 val probe=ModelProbe.probe("http://127.0.0.1:${server.address.port}","","audit-model",2000)
 println("DETAIL|null_tool_fields_result=${probe.toolCall}|done_only_stream_result=${probe.streaming}")
 case("null_tool_calls_do_not_prove_tool_support"){!probe.toolCall}
 case("done_only_sse_does_not_prove_stream_generation"){!probe.streaming}
 server.stop(0)
 val pair=ServerSocket(0,1,InetAddress.getLoopbackAddress())
 val worker=Thread{
  pair.accept().use{s->
   val ins=s.getInputStream();val header=StringBuilder()
   while(!header.endsWith("\r\n\r\n")){val b=ins.read();if(b<0)break;header.append(b.toChar())}
   s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{".toByteArray())
   s.getOutputStream().flush();Thread.sleep(600)
  }
 }
 worker.isDaemon=true;worker.start()
 val response=HttpUtil.get("http://127.0.0.1:${pair.localPort}/audit",timeoutMs=100)
 println("DETAIL|body_timeout_code=${response.code}|ok=${response.ok}|error=${response.error}|bodySize=${response.body.length}")
 case("response_body_timeout_is_not_success"){!response.ok && response.error!=null}
 worker.join(2000);pair.close()
 val m=Model("quote\"model")
 val p=Provider("audit","audit","http://127.0.0.1:1",models=mapOf(m.modelId to m))
 val gw=GatewayServer(0,listOf(p),listOf(RuleSpec("default")),RateLimiter(),"audit-fixture-only")
 val method=GatewayServer::class.java.getDeclaredMethod("handleModels",OutputStream::class.java,String::class.java)
 method.isAccessible=true;val out=ByteArrayOutputStream();method.invoke(gw,out,"audit")
 case("models_response_escapes_model_ids"){
  val payload=out.toString("UTF-8").substringAfter("\r\n\r\n")
  try{JSONObject(payload).getJSONArray("data").getJSONObject(0).getString("id")==m.modelId}catch(_:Exception){false}
 }
 println("SUMMARY|cases=$count|passed=${count-failures}|failed=$failures")
 if(failures>0)kotlin.system.exitProcess(1)
}
