package com.aqi.ccb.gateway

import java.io.ByteArrayInputStream

private var cases = 0
private var failures = 0
private fun checkCase(name: String, action: () -> Boolean) {
    cases++
    try {
        if (action()) println("PASS|$name")
        else { failures++; println("FAIL|$name") }
    } catch (t: Throwable) {
        failures++; println("FAIL|$name|${t.javaClass.simpleName}:${t.message}")
    }
}
private fun provider(id: String, tier: Tier = Tier.paid, lat: Double = 1.0,
                     input: Double = 1.0, output: Double = 1.0): Provider =
    Provider(id, id, "http://127.0.0.1:1/v1", tier = tier, latEwma = lat,
        models = mapOf("audit-model" to Model("audit-model", priceInPerM = input, priceOutPerM = output)))
private fun cand(p: Provider) = Cand(p, p.models.getValue("audit-model"))
fun main() {
    checkCase("model_missing_abilities_is_unknown") {
        Model.fromJson(mapOf("modelId" to "audit-model")).abilities.isEmpty()
    }
    checkCase("provider_constructor_and_parser_tier_agree") {
        val p = Provider.fromJson(mapOf("id" to "audit", "baseUrl" to "http://127.0.0.1:1"))
        p.tier == provider("audit").tier
    }
    checkCase("rate_defaults_match_documented_ui_defaults") {
        val p = RatePolicy()
        p.ratePerMin == 30.0 && p.burst == 3 && p.minIntervalMs == 800.0 &&
            p.jitterMs == 150.0 && p.maxInflight == 2
    }
    checkCase("model_modalities_survive_runtime_parser") {
        val p = Model.fromJson(mapOf("modelId" to "audit", "inputModalities" to listOf("IMAGE"),
            "outputModalities" to listOf("IMAGE")))
        p.inputModalities == listOf(Modality.IMAGE) && p.outputModalities == listOf(Modality.IMAGE)
    }
    checkCase("disabled_model_not_selected") {
        val p = provider("a").copy(models = mapOf("audit-model" to Model("audit-model", enabled = false)))
        RuleEngine.buildCands(listOf(p), RequestCtx("audit-model")).isEmpty()
    }
    checkCase("explicit_free_price_precedes_paid_price") {
        val free = provider("free", input=0.0, output=0.0)
        val paid = provider("paid", input=2.0, output=2.0)
        RuleEngine.opCheapestFirst(listOf(cand(paid), cand(free)), RequestCtx("audit-model"), emptyMap())[0].p.id == "free"
    }
    checkCase("canary_percent_changes_candidate_routing") {
        val cs=listOf(cand(provider("a")),cand(provider("b")))
        val ctx=RequestCtx("audit-model", sessionId="audit-session")
        val zero=RuleEngine.opCanary(cs,ctx,mapOf("percent" to 0)).map { it.p.id }
        val full=RuleEngine.opCanary(cs,ctx,mapOf("percent" to 100)).map { it.p.id }
        zero != full
    }
    checkCase("rule_explain_matches_actual_chain") {
        val ps=listOf(provider("free",tier=Tier.free,lat=200.0),provider("fast",lat=1.0))
        val rule=RuleSpec("audit",select=listOf(OpCall("fastest_first"),OpCall("tier_first")))
        val ctx=RequestCtx("audit-model",dryRun=true)
        val actual=RuleEngine.applySelect(RuleEngine.buildCands(ps,ctx),ctx,rule.select).map{it.p.id}
        val explained=RuleEngine.explain(rule,ps,ctx)["final"]
        println("DETAIL|sort_actual=$actual|sort_explain=$explained")
        actual == explained
    }
    checkCase("short_error_window_does_not_erase_long_window") {
        val id="audit-errors"
        RuntimeState.errorWindow[id]=mutableListOf(System.currentTimeMillis()-120_000)
        RuleEngine.opErrorRate(listOf(cand(provider(id))), RequestCtx("audit-model"),
            mapOf("max_errors" to 5,"window_s" to 60))
        val longCount=RuntimeState.errorRate(id,300_000)
        RuntimeState.errorWindow.remove(id)
        longCount==1
    }
    checkCase("ab_split_handles_minimum_int_hash") {
        val key="polygenelubricants"
        check(key.hashCode()==Int.MIN_VALUE)
        val cs=listOf(cand(provider("a")),cand(provider("b")),cand(provider("c")))
        RuleEngine.opAbSplit(cs, RequestCtx("audit-model",sessionId=key),mapOf("buckets" to 3)).isNotEmpty()
    }
    checkCase("rate_peek_respects_soft_rest") {
        val l=RateLimiter(mapOf("p" to RatePolicy()))
        l.noteSoftRateLimit("p")
        val preview=l.peek("p")["can_send"]
        val actual=l.acquire("p").ok
        println("DETAIL|soft_rest_peek=$preview|soft_rest_acquire=$actual")
        preview==actual
    }
    checkCase("socket_exact_read_rejects_truncated_body") {
        try { SocketIo.readExactly(ByteArrayInputStream(byteArrayOf(65)),4); false }
        catch (_: java.io.EOFException) { true }
    }
    checkCase("socket_header_rejects_incomplete_request") {
        val bytes="GET /health HTTP/1.1\r\nHost: localhost".toByteArray()
        val result=SocketIo.readHeader(ByteArrayInputStream(bytes))
        result==null
    }
    checkCase("json_escape_roundtrip_structural_chars") {
        JsonUtil.esc("line1\nline2\t\"\\") == "line1\\nline2\\t\\\"\\\\"
    }
    println("SUMMARY|cases=$cases|passed=${cases-failures}|failed=$failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
