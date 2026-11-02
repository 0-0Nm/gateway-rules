package com.aqi.ccb.gateway

import java.io.*
import java.net.*
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

private var total=0
private var failed=0
private fun test(name:String, action:()->Boolean) {
    total++
    try { if(action()) println("PASS|$name") else {failed++;println("FAIL|$name")} }
    catch(t:Throwable){failed++;println("FAIL|$name|${t.javaClass.simpleName}:${t.message}")}
}
private fun status(raw:String):Int = raw.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0
private fun bound(server:Any):ServerSocket {
    val f=server.javaClass.getDeclaredField("serverSocket");f.isAccessible=true;return f.get(server) as ServerSocket
}
private fun request(port:Int, token:String?=null, path:String="/mcp", method:String="POST", extra:String="", body:String="{}", declared:String?=null, host:String="127.0.0.1:$port"):String {
    val bytes=body.toByteArray()
    return Socket("127.0.0.1",port).use { s ->
        s.soTimeout=3000
        val headers=buildString {
            append("$method $path HTTP/1.1\r\nHost: $host\r\n")
            if(token!=null)append("Authorization: Bearer $token\r\n")
            append("Content-Type: application/json\r\nContent-Length: ${declared ?: bytes.size.toString()}\r\n")
            append(extra);append("\r\n")
        }
        s.getOutputStream().write(headers.toByteArray());s.getOutputStream().write(bytes);s.getOutputStream().flush();s.shutdownOutput()
        s.getInputStream().readBytes().toString(Charsets.UTF_8)
    }
}
fun main(args:Array<String>) {
    val mcpToken="audit-control-"+java.util.UUID.randomUUID().toString()
    val gatewayToken="audit-data-"+java.util.UUID.randomUUID().toString()
    val calls=AtomicInteger()
    val cfg=java.lang.reflect.Proxy.newProxyInstance(McpConfig::class.java.classLoader,arrayOf(McpConfig::class.java)){_,method,_->
        calls.incrementAndGet()
        if(method.name=="updateProvider") """{"ok":false,"error":"audit-invalid"}"""
        else """{"ok":true,"note":"audit-only"}"""
    } as McpConfig
    val mcp=McpServer(0,38080,mcpToken){cfg}
    val gw=GatewayServer(0,emptyList(),emptyList(),RateLimiter(),gatewayToken)
    check(mcp.start());check(gw.start())
    val mp=bound(mcp).localPort;val gp=bound(gw).localPort
    val rpc="""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"gw_list_rules","arguments":{}}}"""
    test("mcp_loopback_bind"){bound(mcp).inetAddress.isLoopbackAddress}
    test("gateway_default_loopback_bind"){bound(gw).inetAddress.isLoopbackAddress && gw.actualBind.startsWith("127.0.0.1:")}
    test("mcp_requires_token_before_dispatch"){val n=calls.get();status(request(mp,body=rpc))==401 && calls.get()==n}
    test("mcp_bad_token_denied"){val n=calls.get();status(request(mp,token="wrong",body=rpc))==401 && calls.get()==n}
    test("data_token_cannot_manage_mcp"){status(request(mp,token=gatewayToken,body=rpc))==401}
    test("mcp_valid_token_dispatches_once"){val n=calls.get();status(request(mp,token=mcpToken,body=rpc))==200 && calls.get()==n+1}
    test("mcp_initialize_authorized"){val x=request(mp,token=mcpToken,body="""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{}}""");status(x)==200 && x.contains("protocolVersion")}
    test("mcp_tools_list_authorized"){val x=request(mp,token=mcpToken,body="""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""");status(x)==200 && x.contains("gw_update_provider")}
    test("mcp_business_error_flag"){val x=request(mp,token=mcpToken,body="""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"gw_update_provider","arguments":{"providerId":"missing","patch":{}}}}""");JSONObject(x.substringAfter("\r\n\r\n")).getJSONObject("result").getBoolean("isError")}
    for(origin in listOf("https://evil.example","null","http://localhost:$mp")) {
        test("origin_rejected_"+origin.substringBefore(':')){val n=calls.get();status(request(mp,token=mcpToken,extra="Origin: $origin\r\n",body=rpc))==403 && calls.get()==n}
    }
    test("dns_rebinding_host_rejected"){status(request(mp,token=mcpToken,host="evil.example:$mp",body=rpc))==403}
    test("wrong_port_in_host_rejected"){status(request(mp,token=mcpToken,host="localhost:1",body=rpc))==403}
    test("userinfo_host_rejected"){status(request(mp,token=mcpToken,host="evil@localhost:$mp",body=rpc))==400}
    test("localhost_host_allowed"){status(request(mp,token=mcpToken,host="localhost:$mp",body=rpc))==200}
    test("loopback_peer_required") {ServerAccessPolicy(mcpToken).rejectStatus(mapOf("host" to "localhost:$mp","authorization" to "Bearer $mcpToken"),mp,false)==403}
    test("mcp_path_prefix_not_accepted"){status(request(mp,token=mcpToken,path="/mcp-evil",body=rpc))==404}
    test("mcp_query_path_supported"){status(request(mp,token=mcpToken,path="/mcp?client=a",body=rpc))==200}
    test("mcp_health_requires_token"){status(request(mp,path="/health",method="GET",body=""))==401}
    test("mcp_no_wildcard_cors"){!request(mp,token=mcpToken,body=rpc).contains("Access-Control-Allow-Origin")}
    test("mcp_duplicate_host_rejected"){status(request(mp,token=mcpToken,extra="Host: evil.example\r\n",body=rpc))==400}
    test("mcp_duplicate_length_rejected"){status(request(mp,token=mcpToken,extra="Content-Length: 2\r\n",body=rpc))==400}
    test("mcp_duplicate_authorization_rejected"){status(request(mp,token=mcpToken,extra="Authorization: Bearer other\r\n",body=rpc))==400}
    test("mcp_chunked_rejected"){status(request(mp,token=mcpToken,extra="Transfer-Encoding: chunked\r\n",body=rpc))==400}
    test("mcp_negative_length_rejected"){status(request(mp,token=mcpToken,declared="-1"))==400}
    test("mcp_oversize_rejected_before_read"){status(request(mp,token=mcpToken,declared="1048577",body=""))==413}
    test("mcp_truncated_body_never_dispatched"){val n=calls.get();status(request(mp,token=mcpToken,declared="10",body="{}"))==400 && calls.get()==n}
    test("mcp_unauth_large_body_never_read"){status(request(mp,declared="999999999",body=""))==401}
    test("mcp_large_headers_rejected"){status(request(mp,token=mcpToken,extra="X-Audit: "+"x".repeat(33000)+"\r\n"))==431}
    test("exact_body_reader_throws_eof") {try{SocketIo.readExactly(ByteArrayInputStream(byteArrayOf(1)),3);false}catch(_:EOFException){true}}
    test("incomplete_header_not_accepted"){SocketIo.readHeader(ByteArrayInputStream("GET / HTTP/1.1\r\nHost: localhost".toByteArray()))==null}
    test("bad_version_rejected"){try{SocketIo.parseRequestHead("GET / HTTP/1.0\r\nHost: localhost\r\n");false}catch(_:SocketIo.HttpInputException){true}}
    test("gateway_health_requires_data_token"){status(request(gp,method="GET",path="/health",body=""))==401}
    test("gateway_correct_data_token_works"){status(request(gp,token=gatewayToken,method="GET",path="/health",body=""))==200}
    test("mcp_token_cannot_access_gateway"){status(request(gp,token=mcpToken,method="GET",path="/health",body=""))==401}
    test("gateway_origin_rejected"){status(request(gp,token=gatewayToken,method="GET",path="/v1/models",extra="Origin: https://evil.example\r\n",body=""))==403}
    test("gateway_exact_path_enforced"){status(request(gp,token=gatewayToken,method="GET",path="/v1/models-evil",body=""))==404}
    test("gateway_oversize_body_rejected"){status(request(gp,token=gatewayToken,path="/v1/chat/completions",declared="8388609",body=""))==413}
    test("gateway_no_token_leak_in_diag"){val x=request(gp,token=gatewayToken,method="GET",path="/v1/_gw/diag",body="");status(x)==200 && !x.contains(gatewayToken) && !x.contains(mcpToken)}
    test("empty_control_token_rejected"){try{McpServer(0,0,""){cfg};false}catch(_:IllegalArgumentException){true}}
    test("empty_data_token_rejected"){try{GatewayServer(0,emptyList(),emptyList(),RateLimiter(),"");false}catch(_:IllegalArgumentException){true}}
    test("lan_mode_still_requires_token"){ServerAccessPolicy(gatewayToken,false).rejectStatus(mapOf("host" to "192.168.1.2:38080"),38080,false)==401}
    test("explicit_lan_accepts_authorized_native_client"){ServerAccessPolicy(gatewayToken,false).rejectStatus(mapOf("host" to "192.168.1.2:38080","authorization" to "Bearer $gatewayToken"),38080,false)==null}
    val ctx=android.content.Context(File(args[0],"tokens"))
    test("tokens_stable_per_install"){LocalAccessTokens.mcp(ctx)==LocalAccessTokens.mcp(ctx)}
    test("control_and_data_tokens_differ"){LocalAccessTokens.mcp(ctx)!=LocalAccessTokens.gateway(ctx)}
    test("tokens_are_256_bit_random_hex"){Regex("[0-9a-f]{64}").matches(LocalAccessTokens.mcp(ctx))}
    test("token_persist_failure_not_silently_accepted") {
        val bad=android.content.Context(File(args[0],"bad"));bad.getSharedPreferences("local_access_tokens_v1",0).writable=false
        try{LocalAccessTokens.mcp(bad);false}catch(_:IllegalStateException){true}
    }
    test("concurrent_token_creation_returns_single_value") {
        val results=java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val c=android.content.Context(File(args[0],"concurrent"))
        val threads=(1..20).map{Thread{results.add(LocalAccessTokens.mcp(c))}.apply{start()}}
        threads.forEach{it.join()};results.size==1
    }
    val pending=Socket("127.0.0.1",mp);pending.soTimeout=2000
    pending.getOutputStream().write("POST /mcp HTTP/1.1\r\n".toByteArray());Thread.sleep(100)
    mcp.stop();gw.stop()
    test("stop_closes_incomplete_client_connections") {val eof=try{pending.getInputStream().read()<0}catch(_:IOException){true};pending.close();eof}
    test("stopped_servers_report_stopped"){!mcp.isRunning() && !gw.isRunning()}
    println("SUMMARY|cases=$total|passed=${total-failed}|failed=$failed")
    if(failed>0)kotlin.system.exitProcess(1)
}
