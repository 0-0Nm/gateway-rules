package com.aqi.ccb.data

import android.content.Context
import java.io.File
import org.json.JSONObject

private var cases=0
private var failed=0
private fun test(name:String,fn:()->Unit){cases++;try{fn();println("PASS|$name")}catch(t:Throwable){failed++;println("FAIL|$name|${t.javaClass.simpleName}")}}
private fun rejects(fn:()->Unit){var rejected=false;try{fn()}catch(_:IllegalArgumentException){rejected=true};check(rejected)}
private fun servers(name:String="GatewayRulesAI",port:Int=38081,token:String="fixture-token-only")=
    JSONObject(McpClientConfig.json(name,port,token)).getJSONObject("mcpServers")

fun main(args:Array<String>){
 val root=File(args[0])
 fun ctx(name:String)=Context(File(root,name).apply{mkdirs()})
 test("default_name_uses_no_special_characters"){
  check(McpClientConfig.DEFAULT_NAME=="gatewayrules");check(McpClientConfig.nameError(McpClientConfig.DEFAULT_NAME)==null)
 }
 test("valid_ascii_names_keep_case_and_digits"){
  for(v in listOf("A","a","MyMcp42","GatewayRulesAI"))check(McpClientConfig.validateName(v)==v)
 }
 test("name_length_boundaries"){
  check(McpClientConfig.validateName("A".repeat(64)).length==64);rejects{McpClientConfig.validateName("A".repeat(65))};rejects{McpClientConfig.validateName("")}
 }
 test("special_characters_and_non_ascii_rejected"){
  for(v in listOf("gateway-rules","gateway_rules","my.mcp","my mcp","my/mcp","my\\mcp","名字","ＭＣＰ","Mcp🚀","a\nb","a\rb","a\tb","a\"b","a:b","a{b}","a|b","a\$b"))rejects{McpClientConfig.validateName(v)}
 }
 test("first_character_must_be_ascii_letter"){for(v in listOf("1Mcp","0","_Mcp","-Mcp"))rejects{McpClientConfig.validateName(v)}}
 test("spaces_not_silently_trimmed"){rejects{McpClientConfig.validateName(" GatewayRulesAI")};rejects{McpClientConfig.validateName("GatewayRulesAI ")}}
 test("custom_name_is_the_only_json_server_key"){
  val a=servers();check(a.length()==1&&a.has("GatewayRulesAI")&&!a.has("gateway-rules")&&!a.has("gatewayrules"))
 }
 test("different_names_produce_different_export_keys"){
  check(servers("ClientOne").has("ClientOne"));check(servers("ClientTwo").has("ClientTwo"));check(!servers("ClientTwo").has("ClientOne"))
 }
 test("json_export_validates_name_again"){rejects{McpClientConfig.json("bad-name",38081,"fixture")}}
 test("http_type_actual_port_and_auth_preserved"){
  val a=servers(port=43123).getJSONObject("GatewayRulesAI");check(a.getString("type")=="http");check(a.getString("url")=="http://127.0.0.1:43123/mcp")
  check(a.getJSONObject("headers").getString("Authorization")=="Bearer fixture-token-only")
 }
 test("json_builder_escapes_token_without_extra_fields"){
  val token="fixture\"\\value";val top=JSONObject(McpClientConfig.json("Alpha42",38081,token));check(top.length()==1)
  val item=top.getJSONObject("mcpServers").getJSONObject("Alpha42");check(item.length()==3&&item.getJSONObject("headers").length()==1)
  check(item.getJSONObject("headers").getString("Authorization")=="Bearer "+token)
 }
 test("invalid_ports_rejected"){for(v in listOf(-1,0,1023,65536,Int.MAX_VALUE))rejects{McpClientConfig.json("Valid",v,"fixture")}}
 test("invalid_header_tokens_rejected"){for(v in listOf(""," ","x\ny","x\ry","x\u0000y"))rejects{McpClientConfig.json("Valid",38081,v)}}
 test("preferences_default_read_is_side_effect_free"){
  val c=ctx("default");val prefs=AppSettings.prefs(c);check(prefs.values.isEmpty());check(AppSettings.mcpClientName(c)=="gatewayrules");check(prefs.values.isEmpty())
 }
 test("saved_name_is_read_back_from_same_preferences"){
  val c=ctx("save");AppSettings.setMcpClientName(c,"MyMcp7");check(AppSettings.mcpClientName(c)=="MyMcp7")
  check(c.getSharedPreferences("app_settings",Context.MODE_PRIVATE).getString(AppSettings.KEY_MCP_CLIENT_NAME,null)=="MyMcp7")
 }
 test("invalid_save_preserves_previous_name"){
  val c=ctx("reject-save");AppSettings.setMcpClientName(c,"KeepMe");rejects{AppSettings.setMcpClientName(c,"bad-name")};check(AppSettings.mcpClientName(c)=="KeepMe")
 }
 test("invalid_stored_value_falls_back_without_overwriting"){
  val c=ctx("corrupt");AppSettings.prefs(c).edit().putString(AppSettings.KEY_MCP_CLIENT_NAME,"old-name").commit()
  check(AppSettings.mcpClientName(c)=="gatewayrules");check(AppSettings.prefs(c).getString(AppSettings.KEY_MCP_CLIENT_NAME,null)=="old-name")
 }
 test("preference_write_failure_not_reported_success"){
  val c=ctx("unwritable");AppSettings.setMcpClientName(c,"KeepMe");AppSettings.prefs(c).writable=false
  var rejected=false;try{AppSettings.setMcpClientName(c,"NewName")}catch(_:IllegalStateException){rejected=true};check(rejected&&AppSettings.mcpClientName(c)=="KeepMe")
 }
 test("name_change_does_not_touch_ports_or_service_switch"){
  val c=ctx("isolation");AppSettings.setMcpPort(c,43123);AppSettings.setMcpEnabled(c,true);val before=AppSettings.prefs(c).values.toMap()
  AppSettings.setMcpClientName(c,"NewAlias");check(AppSettings.mcpPort(c)==43123&&AppSettings.mcpEnabled(c))
  check(AppSettings.prefs(c).values.filterKeys{it!=AppSettings.KEY_MCP_CLIENT_NAME}==before)
 }
 test("saved_alias_is_used_in_export"){
  val c=ctx("saved-export");AppSettings.setMcpClientName(c,"ExportName99");check(servers(AppSettings.mcpClientName(c)).has("ExportName99"))
 }
 test("independent_preferences_do_not_share_alias"){
  val a=ctx("one");val b=ctx("two");AppSettings.setMcpClientName(a,"OnlyOne");check(AppSettings.mcpClientName(b)=="gatewayrules")
 }
 println("SUMMARY|cases=$cases|passed=${cases-failed}|failed=$failed")
 if(failed>0)kotlin.system.exitProcess(1)
}
