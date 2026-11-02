package com.aqi.ccb.gateway

import android.content.Context
import com.aqi.ccb.data.*
import java.io.*
import java.net.*
import java.util.concurrent.atomic.AtomicInteger
import org.json.*
import com.sun.net.httpserver.HttpServer

private var total=0;private var bad=0
private fun test(name:String,action:()->Boolean){total++;try{if(action())println("PASS|$name")else{bad++;println("FAIL|$name")}}catch(t:Throwable){bad++;println("FAIL|$name|${t.javaClass.simpleName}:${t.message}")}}
private fun rule(id:String="default")=JSONObject().put("id",id).put("ruleSpecVersion",1).put("enabled",true).put("select",JSONArray()).put("execute",JSONObject().put("op","first"))
private fun prov(id:String="a")=JSONObject().put("id",id).put("name",id).put("baseUrl","http://127.0.0.1:1/v1").put("models",JSONArray().put(JSONObject().put("modelId","audit-model").put("enabled",true))).put("rateLimit",JSONObject().put("ratePerMin",0).put("burst",3).put("minIntervalMs",0).put("jitterMs",0).put("maxInflight",2))
private fun ctx(root:File,name:String):Context=Context(File(root,name).apply{mkdirs()})
private fun seed(c:Context)=ConfigStore.transact(c){it.providers.add(prov());it.rules.add(rule())}
private fun rejected(action:()->Unit):Boolean=try{action();false}catch(_:Exception){true}
private fun chat(g:GatewayServer):String {
 val m=GatewayServer::class.java.getDeclaredMethod("handleChat",OutputStream::class.java,String::class.java,ByteArray::class.java);m.isAccessible=true
 val out=ByteArrayOutputStream();m.invoke(g,out,"audit","""{"model":"audit-model","messages":[{"role":"user","content":"audit"}]}""".toByteArray());return out.toString("UTF-8")
}
fun main(args:Array<String>){
 val root=File(args[0]);ConfigStore.publisher=null
 test("first_empty_state_has_no_demo"){val c=ctx(root,"empty");ProviderRepo.runtimeProviders(c).isEmpty()&&ProviderRepo.runtimeRules(c).isEmpty()}
 test("legacy_read_does_not_write_or_delete"){val c=ctx(root,"legacy-read");val f=File(c.filesDir,"providers.json");f.writeText(JSONArray().put(prov()).toString());val before=f.readText();ConfigStore.read(c).providers.size==1 && f.readText()==before && !File(c.filesDir,"config-state-v1.json").exists()}
 test("legacy_migrates_on_first_mutation_with_keys"){val c=ctx(root,"legacy-migrate");val p=prov().put("apiKeyRef","ref-a");File(c.filesDir,"providers.json").writeText(JSONArray().put(p).toString());File(c.filesDir,"keys.json").writeText("""{"ref-a":"audit-key"}""");ConfigCommands.updateProvider(c,"a",JSONObject().put("weight",2));val s=ConfigStore.read(c);s.revision==1L&&s.keys["ref-a"]=="audit-key"&&File(c.filesDir,"providers.json").exists()}
 test("bad_legacy_config_is_not_overwritten"){val c=ctx(root,"bad-legacy");val f=File(c.filesDir,"providers.json");f.writeText("[");rejected{seed(c)}&&f.readText()=="["&&!File(c.filesDir,"config-state-v1.json").exists()}
 test("failed_write_is_not_success"){val f=File(root,"not-dir");f.writeText("x");val c=Context(f);rejected{ProviderRepo.save(c,ProviderDraft(id="a",name="a",baseUrl="http://127.0.0.1:1",models=listOf(ModelInfo("audit-model"))))}}
 val c=ctx(root,"main");seed(c)
 test("committed_revision_advances"){val old=ConfigStore.read(c).revision;val r=ConfigCommands.updateProvider(c,"a",JSONObject().put("weight",2));r.revision==old+1&&r.changed&&!r.applied}
 test("no_op_does_not_advance_revision"){val old=ConfigStore.read(c).revision;val r=ConfigCommands.updateProvider(c,"a",JSONObject().put("weight",2));r.revision==old&&!r.changed}
 test("stale_revision_rejected"){val old=ConfigStore.read(c).revision;rejected{ConfigCommands.updateProvider(c,"a",JSONObject().put("expectedRevision",old-1).put("weight",9))}&&ConfigStore.read(c).revision==old}
 test("unknown_patch_field_rejected"){rejected{ConfigCommands.updateProvider(c,"a",JSONObject().put("wieght",9))}}
 test("semantic_noop_does_not_advance_revision") {val before=ConfigStore.read(c).revision;val original=ConfigStore.read(c).rules[0];val reversed=JSONObject();original.keys().asSequence().toList().reversed().forEach{reversed.put(it,original.get(it))};val r=ConfigCommands.saveRule(c,reversed);r.revision==before&&!r.changed}
 test("invalid_rate_rejected_without_file_change"){val f=File(c.filesDir,"config-state-v1.json");val old=f.readBytes();rejected{ConfigCommands.updateProvider(c,"a",JSONObject().put("ratePerMin",-1))}&&old.contentEquals(f.readBytes())}
 test("model_wrong_type_rejected"){rejected{ConfigCommands.updateModel(c,"a","audit-model",JSONObject().put("enabled","yes"))}}
 test("missing_model_in_batch_rejects_all"){val old=ConfigStore.read(c).revision;rejected{ConfigCommands.setModelsEnabled(c,"a",setOf("audit-model","missing"),false)}&&ConfigStore.read(c).revision==old&&ConfigStore.read(c).providers[0].getJSONArray("models").getJSONObject(0).getBoolean("enabled")}
 test("provider_key_and_ref_commit_together"){ConfigCommands.updateProvider(c,"a",JSONObject().put("apiKey","audit-secret"));val s=ConfigStore.read(c);s.keys[s.providers[0].getString("apiKeyRef")]=="audit-secret"}
 test("clear_key_removes_reference_and_value"){ConfigCommands.updateProvider(c,"a",JSONObject().put("apiKey",""));val s=ConfigStore.read(c);!s.providers[0].has("apiKeyRef")&&s.keys.isEmpty()}
 test("rule_stale_full_save_rejected") {val old=ConfigStore.read(c).revision;rejected{ConfigCommands.saveRule(c,rule("new").put("expectedRevision",old-1))}&&ConfigStore.read(c).revision==old}
 test("unknown_rule_rejected"){rejected{ConfigCommands.saveRule(c,rule("bad").put("select",JSONArray().put(JSONObject().put("op","nonexistent"))))}}
 test("unimplemented_execute_rejected"){rejected{ConfigCommands.saveRule(c,rule("race").put("execute",JSONObject().put("op","race")))}}
 test("virtual_missing_model_rejected"){rejected{ConfigCommands.saveVirtualModel(c,JSONObject("""{"name":"vm","ruleId":"default","members":[{"providerId":"a","modelId":"missing"}]}"""))}}
 test("virtual_missing_rule_rejected"){rejected{ConfigCommands.saveVirtualModel(c,JSONObject("""{"name":"vm","ruleId":"missing","members":[{"providerId":"a","modelId":"audit-model"}]}"""))}}
 ConfigCommands.saveVirtualModel(c,JSONObject("""{"name":"vm","ruleId":"default","members":[{"providerId":"a","modelId":"audit-model"}]}"""))
 test("delete_referenced_provider_rejected"){val r=ConfigStore.read(c).revision;rejected{ConfigCommands.deleteProvider(c,"a")}&&ConfigStore.read(c).revision==r}
 test("delete_referenced_rule_rejected"){rejected{ConfigCommands.deleteRule(c,"default")}}
 test("delete_last_stays_empty"){ConfigCommands.deleteVirtualModel(c,"vm");ConfigCommands.deleteProvider(c,"a");ConfigCommands.deleteRule(c,"default");ProviderRepo.runtimeProviders(c).isEmpty()&&ProviderRepo.runtimeRules(c).isEmpty()}
 val cc=ctx(root,"parallel");seed(cc)
 test("parallel_patches_do_not_lose_fields"){
  val errors=java.util.Collections.synchronizedList(mutableListOf<String>())
  val threads=(0 until 20).map{i->Thread{try{ConfigStore.transact(cc){s->s.providers[0].put("audit-$i",i)}}catch(e:Exception){errors.add(e.toString())}}.apply{start()}}
  threads.forEach{it.join()};val s=ConfigStore.read(cc);errors.isEmpty()&&s.revision==21L&&(0 until 20).all{s.providers[0].getInt("audit-$it")==it}
 }
 test("stale_ui_draft_rejected") {val draft=ProviderRepo.list(cc)[0];ConfigCommands.updateProvider(cc,"a",JSONObject().put("weight",3));rejected{ProviderRepo.save(cc,draft.copy(name="stale"))}}
 test("soft_rate_settings_survive_draft_edit") {ConfigCommands.updateProvider(cc,"a",JSONObject().put("softRateSec",13).put("softRateMaxSec",29));val draft=ProviderRepo.list(cc)[0];ProviderRepo.save(cc,draft.copy(weight="5"));val r=ConfigStore.read(cc).providers[0].getJSONObject("rateLimit");r.getDouble("softRateSec")==13.0&&r.getDouble("softRateMaxSec")==29.0}
 test("publisher_failure_reports_persisted_not_applied") {ConfigStore.publisher={throw IllegalStateException("audit")};val r=ConfigCommands.updateProvider(cc,"a",JSONObject().put("weight",7));ConfigStore.publisher=null;r.applied==false&&r.applyError!=null&&ConfigStore.read(cc).providers[0].getDouble("weight")==7.0}
 test("publisher_receives_detached_snapshot") {ConfigStore.publisher={s->s.providers.clear();true};val r=ConfigCommands.updateProvider(cc,"a",JSONObject().put("weight",8));ConfigStore.publisher=null;r.applied&&ConfigStore.read(cc).providers.size==1}
 test("corrupt_committed_state_does_not_fall_back") {val f=File(cc.filesDir,"config-state-v1.json");val old=f.readText();f.writeText("bad");val yes=rejected{ConfigStore.read(cc)};f.writeText(old);yes}

 test("provider_custom_key_ref_survives_edit") {
  val c2=ctx(root,"custom-key");ConfigStore.transact(c2){s->s.keys["custom"]="audit";s.providers.add(prov().put("apiKeyRef","custom"))}
  ProviderRepo.save(c2,ProviderRepo.list(c2)[0].copy(weight="2"));val st=ConfigStore.read(c2)
  st.providers[0].getString("apiKeyRef")=="custom"&&st.keys.size==1
 }
 test("create_collision_does_not_overwrite") {val c2=ctx(root,"collision");seed(c2);rejected{ProviderRepo.save(c2,ProviderDraft(name="a",baseUrl="http://127.0.0.1:2",models=listOf(ModelInfo("audit-model"))))}}
 test("bridge_reports_saved_not_applied_when_gateway_off") {val c2=ctx(root,"bridge-off");seed(c2);val bridge=McpBridge(c2);val r=JSONObject(bridge.updateProvider("a","""{"weight":3}"""));r.getBoolean("ok")&&r.getBoolean("persisted")&&!r.getBoolean("applied")&&r.getLong("revision")==2L}
 test("bridge_readback_complete_and_no_key") {val c2=ctx(root,"bridge-read");seed(c2);val bridge=McpBridge(c2);bridge.updateProvider("a","""{"apiKey":"audit-private-value"}""");bridge.updateModel("a","audit-model","""{"enabled":false,"priceInPerM":0.25,"type":"CHAT"}""");val raw=bridge.listProvidersJson();val model=JSONObject(raw).getJSONArray("providers").getJSONObject(0).getJSONArray("models").getJSONObject(0);!raw.contains("audit-private-value")&&!raw.contains("apiKeyRef")&&!model.getBoolean("enabled")&&model.getDouble("priceInPerM")==0.25}
 test("bridge_invalid_patch_has_stable_error_code") {val c2=ctx(root,"bridge-invalid");seed(c2);val r=JSONObject(McpBridge(c2).updateProvider("a","""{"weight":-1}"""));!r.getBoolean("ok")&&!r.getBoolean("persisted")&&r.getString("code")=="INVALID_CONFIG"}
 test("virtual_draft_stale_save_rejected") {val c2=ctx(root,"vm-stale");seed(c2);ConfigCommands.saveVirtualModel(c2,JSONObject("""{"name":"alias","ruleId":"default","members":[{"providerId":"a","modelId":"audit-model","weight":5}]}"""));val old=VirtualModelRepo.list(c2)[0];ConfigCommands.updateProvider(c2,"a",JSONObject().put("weight",2));rejected{VirtualModelRepo.save(c2,old)}}
 test("virtual_draft_preserves_member_weight") {val c2=ctx(root,"vm-weight");seed(c2);ConfigCommands.saveVirtualModel(c2,JSONObject("""{"name":"alias","ruleId":"default","members":[{"providerId":"a","modelId":"audit-model","weight":5}]}"""));VirtualModelRepo.save(c2,VirtualModelRepo.list(c2)[0].copy(description="changed"));ConfigStore.read(c2).virtualModels[0].getJSONArray("members").getJSONObject(0).getDouble("weight")==5.0}
 test("model_empty_modality_roundtrip_is_exact") {ModelInfo.fromJson(ModelInfo("audit",inputModalities=emptyList(),outputModalities=emptyList()).toJson()).inputModalities.isEmpty()}
 test("draft_malformed_numeric_not_silently_defaulted") {val c2=ctx(root,"number");seed(c2);rejected{ProviderRepo.save(c2,ProviderRepo.list(c2)[0].copy(ratePerMin="abc"))}}
 val policy=RatePolicy(ratePerMin=0.0,burst=3,minIntervalMs=0.0,jitterMs=0.0,maxInflight=2)
 test("rate_defaults_match_ui") {RatePolicy().let{it.ratePerMin==30.0&&it.burst==3&&it.minIntervalMs==800.0&&it.jitterMs==150.0&&it.maxInflight==2}}
 test("reload_preserves_inflight"){val l=RateLimiter(mapOf("a" to policy));l.acquire("a");l.updatePolicies(mapOf("a" to policy.copy(maxInflight=1)));val denied=!l.acquire("a").ok;l.release("a");denied&&l.acquire("a").ok}
 test("reload_does_not_refill_tokens") {val p=policy.copy(ratePerMin=0.001,burst=1);val l=RateLimiter(mapOf("a" to p));l.acquire("a");l.release("a");l.updatePolicies(mapOf("a" to p.copy(burst=20)));!l.acquire("a").ok}
 test("reload_preserves_cooldown") {val l=RateLimiter(mapOf("a" to policy));l.noteSoftRateLimit("a");l.updatePolicies(mapOf("a" to policy));!l.acquire("a").ok&&l.peek("a")["can_send"]==false}
 test("old_success_cannot_clear_new_429") {val l=RateLimiter(mapOf("a" to policy));val p=l.acquire("a");l.noteSoftRateLimit("a");l.noteSuccess("a",p.cooldownGeneration);!l.acquire("a").ok}
 test("removed_provider_denied_without_losing_slot") {val l=RateLimiter(mapOf("a" to policy));l.acquire("a");l.updatePolicies(emptyMap());val denied=!l.acquire("a").ok;l.release("a");l.updatePolicies(mapOf("a" to policy));denied&&l.peek("a")["inflight"]==0}
 test("rule_override_cannot_relax_provider_policy") {val l=RateLimiter(mapOf("a" to policy.copy(maxInflight=1)));l.acquire("a");!l.acquire("a",policy.copy(maxInflight=99)).ok}
 test("rule_override_can_tighten_policy") {val l=RateLimiter(mapOf("a" to policy));l.acquire("a");!l.acquire("a",policy.copy(maxInflight=1)).ok}
 val hits=AtomicInteger();val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
 server.createContext("/chat/completions"){e->e.requestBody.use{it.readBytes()};hits.incrementAndGet();val bytes="{}".toByteArray();e.sendResponseHeaders(200,bytes.size.toLong());e.responseBody.use{it.write(bytes)}};server.start()
 test("hot_reload_changes_real_upstream_admission") {val p=Provider("a","a","http://127.0.0.1:${server.address.port}",ratePolicy=policy,models=mapOf("audit-model" to Model("audit-model")));val l=RateLimiter(mapOf("a" to policy));val g=GatewayServer(0,listOf(p),listOf(RuleSpec("default")),l,"audit-only");chat(g);g.update(listOf(p.copy(ratePolicy=policy.copy(minIntervalMs=60000.0))),listOf(RuleSpec("default")));val second=chat(g);hits.get()==1&&g.configRevisionPublic()==1L&&second.startsWith("HTTP/1.1 429")}

 test("bridge_applies_version_and_limits_to_running_gateway") {
  val c2=ctx(root,"bridge-live");seed(c2);val st=ConfigStore.read(c2)
  val p=ConfigStore.toProviders(st.providers);val g=GatewayServer(0,p,ConfigStore.toRules(st.rules),RateLimiter(p.associate{it.id to it.ratePolicy}),"audit-token")
  check(g.start());GatewayService.current=g
  try {
   val result=JSONObject(McpBridge(c2).updateProvider("a","""{"minIntervalMs":4321}"""))
   val revision=result.getLong("revision")
   val f=GatewayServer::class.java.getDeclaredField("limiter");f.isAccessible=true;val l=f.get(g) as RateLimiter
   result.getBoolean("applied")&&g.configRevisionPublic()==revision&&l.policyOf("a").minIntervalMs==4321.0
  } finally {GatewayService.current=null;g.stop();ConfigStore.publisher=null}
 }
 server.stop(0)
 println("SUMMARY|cases=$total|passed=${total-bad}|failed=$bad")
 if(bad>0)kotlin.system.exitProcess(1)
}
