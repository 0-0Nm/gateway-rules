package com.aqi.ccb.gateway

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import com.sun.net.httpserver.HttpServer

private fun usageEvent(input: Any = 10, output: Any = 3) = JSONObject().put("usage", JSONObject()
    .put("prompt_tokens", input).put("completion_tokens", output))
private fun recordForUsage(outcome: String = "success", sent: Boolean = true): UsageRequestRecord {
    val p = Provider("p", "P", "http://127.0.0.1")
    val capture = UsageRequestCapture("r-12345678")
    val a = capture.begin(p, Model("m", priceInPerM=1.25, priceOutPerM=3.0), "DO_NOT_STORE_CREDENTIAL")!!
    a.observer.observe(usageEvent()); a.finish(200, sent, true)
    return capture.finish(outcome)!!
}

private class UsageFixture(root: File, val mode: String): AutoCloseable {
    val ledger = UsageLedger(root)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private val up = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val model = Model("m", priceInPerM=1.25, priceOutPerM=3.0)
    private val policy = RatePolicy(ratePerMin=0.0, minIntervalMs=0.0, jitterMs=0.0, maxInflight=8, softRateSec=0.0)
    private fun provider(id: String, path: String) = Provider(id, id, "http://127.0.0.1:${up.address.port}/$path", ratePolicy=policy,
        models=mapOf("m" to model), apiKeyRef="fixture")
    private val providers = if (mode == "fallback") listOf(provider("a", "bad"), provider("b", "good")) else listOf(provider("p", "good"))
    val gateway = GatewayServer(0, providers, listOf(RuleSpec("r")), RateLimiter(providers.associate { it.id to policy }),
        "usage-local-only", usageLedger=ledger)
    val streamed = mode == "sse" || mode == "truncated"
    val expected: ByteArray = when (mode) {
        "sse", "truncated" -> {
            val u = usageEvent().getJSONObject("usage").put("prompt_tokens_details", JSONObject().put("cached_tokens", 2))
                .put("completion_tokens_details", JSONObject().put("reasoning_tokens", 1))
            val tail = "data: " + JSONObject().put("choices", org.json.JSONArray()).put("usage", u).toString() + "\r\n\r\n"
            ("data: {\"choices\":[{\"delta\":{\"content\":\"DO_NOT_STORE_RESPONSE\"}}]}\r\n\r\n" + tail + tail +
                if (mode == "sse") "data: [DONE]\r\n\r\n" else "").toByteArray()
        }
        "missing" -> "{\"choices\":[{\"message\":{\"content\":\"DO_NOT_STORE_RESPONSE\"}}]}".toByteArray()
        "large" -> ("{\"choices\":[{\"message\":{\"content\":\"" + "x".repeat(300000) + "\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3}}").toByteArray()
        else -> "{\"choices\":[{\"message\":{\"content\":\"DO_NOT_STORE_RESPONSE\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3}}".toByteArray()
    }
    init {
        up.executor = pool
        up.createContext("/") { e ->
            try {
                e.requestBody.use { it.readBytes() }
                val rejected = e.requestURI.path.startsWith("/bad")
                val bytes = if (rejected) "DO_NOT_STORE_ERROR".toByteArray() else expected
                e.responseHeaders.set("Content-Type", if (!rejected && streamed) "text/event-stream" else "application/json")
                e.sendResponseHeaders(if (rejected) 429 else 200, bytes.size.toLong())
                e.responseBody.use { output ->
                    if (streamed) bytes.forEach { output.write(it.toInt()); output.flush() } else output.write(bytes)
                }
            } finally { e.close() }
        }
        up.start()
        gateway.update(providers, listOf(RuleSpec("r")), revision=1, keys=mapOf("fixture" to "DO_NOT_STORE_CREDENTIAL"))
        check(gateway.start())
    }
    fun request(modelName: String = "m"): Pair<Int, ByteArray> {
        val c = URL("http://127.0.0.1:${gateway.listeningPortPublic()}/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout=3000; c.readTimeout=10000
            c.setRequestProperty("Authorization", "Bearer usage-local-only"); c.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("model", modelName).put("stream", streamed)
                .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "DO_NOT_STORE_PROMPT"))).toString().toByteArray()
            c.setFixedLengthStreamingMode(body.size); c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val bytes = (if (code >= 400) c.errorStream else c.inputStream).use { it.readBytes() }
            return code to bytes
        } finally { c.disconnect() }
    }
    override fun close() { gateway.stop(); up.stop(0); pool.shutdownNow() }
}

fun runUsageAudit(test: (String, () -> Unit) -> Unit, root: File) {
    fun dir(name: String) = File(root, name)
    test("usage_missing_is_unknown") {
        val o=UsageObserver();o.observe(JSONObject("{\"choices\":[]}"));check(o.finish(true).status=="not_reported" && o.finish(true).counts==null)
    }
    test("usage_explicit_zero_is_reported") {
        val o=UsageObserver();o.observe(usageEvent(0,0));check(o.finish(true)==UsageObservation("reported",UsageCounts(0,0)))
    }
    test("usage_subsets_not_added_to_totals") {
        val o=UsageObserver();val j=usageEvent();j.getJSONObject("usage").put("prompt_tokens_details",JSONObject().put("cached_tokens",2)).put("completion_tokens_details",JSONObject().put("reasoning_tokens",1));o.observe(j)
        check(o.finish(true).counts==UsageCounts(10,3,2,1))
    }
    test("usage_duplicate_frames_replace_not_sum") {
        val o=UsageObserver();repeat(5){o.observe(usageEvent())};check(o.finish(true).counts==UsageCounts(10,3))
    }
    test("usage_invalid_numbers_are_not_coerced") {
        for (v in listOf<Any>(-1,1.5,"10",true,1_000_000_000_001L)) {val o=UsageObserver();o.observe(usageEvent(v,3));check(o.finish(true).status=="invalid")}
    }
    test("usage_invalid_subsets_and_total_rejected") {
        val a=usageEvent();a.getJSONObject("usage").put("total_tokens",14);val b=usageEvent();b.getJSONObject("usage").put("prompt_tokens_details",JSONObject().put("cached_tokens",11))
        for(j in listOf(a,b)){val o=UsageObserver();o.observe(j);check(o.finish(true).status=="invalid")}
    }
    test("usage_incomplete_not_reported_complete") {
        val o=UsageObserver();o.observe(usageEvent());check(o.finish(false).status=="incomplete")
    }
    test("usage_budget_limit_marks_unknown") {
        val o=UsageObserver();o.observeBody(ByteArray(UsageObserver.BODY_LIMIT+1));check(o.finish(true).status=="observation_limit")
    }
    test("usage_latest_cumulative_frame_wins") {
        val o=UsageObserver();o.observe(usageEvent(10,1));o.observe(usageEvent(10,3));check(o.finish(true).counts==UsageCounts(10,3))
    }
    test("usage_invalid_later_frame_does_not_keep_old_totals") {
        val o=UsageObserver();o.observe(usageEvent());o.observe(usageEvent("bad",3));check(o.finish(true).status=="invalid"&&o.finish(true).counts==null)
    }
    test("ledger_restart_restores_reported_usage") {
        val d=dir("restart");val l=UsageLedger(d);check(l.append(recordForUsage()));val s=UsageLedger(d).snapshot();check(s.requests==1&&s.inputTokens==10L&&s.outputTokens==3L&&s.reportedAttempts==1L)
    }
    test("ledger_duplicate_record_not_double_counted") {
        val l=UsageLedger(dir("duplicate"));val r=recordForUsage();check(l.append(r));check(l.append(r));check(l.snapshot().requests==1)
    }
    test("ledger_corrupt_line_keeps_valid_history") {
        val d=dir("corrupt");check(UsageLedger(d).append(recordForUsage()));File(d,"usage.jsonl").appendText("{cut");val l=UsageLedger(d);check(l.snapshot().requests==1&&l.snapshot().readErrors==1&&l.snapshot().storageStatus=="partial")
        check(l.append(recordForUsage()));val read=UsageLedger(d).snapshot();check(read.requests==2&&read.readErrors==1)
    }
    test("ledger_disk_error_is_visible_not_persisted") {
        val d=dir("not-a-dir");d.parentFile.mkdirs();d.writeText("x");val l=UsageLedger(d);check(!l.append(recordForUsage()));check(l.snapshot().storageStatus=="error"&&l.snapshot().unpersisted==1)
    }
    test("ledger_failed_duplicate_not_falsely_persisted") {
        val d=dir("failed-duplicate");d.parentFile.mkdirs();d.writeText("x");val l=UsageLedger(d);val r=recordForUsage();check(!l.append(r));check(!l.append(r));check(l.snapshot().requests==1)
    }
    test("ledger_retention_bounded_and_labelled") {
        val d=dir("bound");val l=UsageLedger(d,limit=3,fileLimit=4096);repeat(18){check(l.append(recordForUsage()))};check(l.snapshot().requests==3&&l.snapshot().retainedLimit==3)
        check(d.listFiles()!!.all{it.length()<=4096});check(UsageLedger(d,limit=3,fileLimit=4096).snapshot().requests==3)
    }
    test("ledger_unsent_attempt_not_counted_as_reported") {
        val l=UsageLedger(dir("unsent"));l.append(recordForUsage(sent=false));check(l.snapshot().notSentAttempts==1L&&l.snapshot().reportedAttempts==0L&&l.snapshot().inputTokens==0L)
    }
    test("ledger_attempt_budget_is_explicit") {
        val p=Provider("p","P","http://127.0.0.1");val c=UsageRequestCapture("r-12345678");repeat(40){c.begin(p,Model("m"),"")?.finish(503,true,false)}
        val r=c.finish("upstream_failure")!!;val l=UsageLedger(dir("attempt-bound"));check(l.append(r));check(l.snapshot().attempts==40L&&l.snapshot().omittedAttempts==8L&&l.snapshot().unknownAttempts==32L)
        check(c.finish("success")==null)
    }
    test("ledger_has_no_credentials_and_no_invented_currency") {
        val d=dir("privacy");val l=UsageLedger(d);l.append(recordForUsage());val text=File(d,"usage.jsonl").readText()
        check(!text.contains("DO_NOT_STORE_CREDENTIAL"));check(text.contains("priceInputPerM"));val s=l.snapshot().json();check(s.isNull("cost")&&!s.getBoolean("budgetEnforced"))
    }
    test("ledger_read_only_open_creates_no_files") {
        val d=dir("read-only");val a=UsageLedgerStore.open(d);val b=UsageLedgerStore.open(d);check(a===b);check(a.snapshot().requests==0&&!d.exists())
    }
    test("usage_real_json_transparently_recorded") {
        val d=dir("real-json");UsageFixture(d,"json").use { f ->val r=f.request();check(r.first==200&&r.second.contentEquals(f.expected));check(f.ledger.snapshot().inputTokens==10L&&f.ledger.snapshot().successful==1)
            val raw=File(d,"usage.jsonl").readText();check(!raw.contains("DO_NOT_STORE_"));check(f.gateway.debugSnapshot().getJSONObject("usage").getLong("reportedOutputTokens")==3L)}
    }
    test("usage_real_sse_arbitrary_chunks_duplicate_usage") {
        UsageFixture(dir("real-sse"),"sse").use { f ->val r=f.request();check(r.second.contentEquals(f.expected));val s=f.ledger.snapshot();check(s.requests==1&&s.inputTokens==10L&&s.outputTokens==3L&&s.cachedTokens==2L&&s.reasoningTokens==1L)}
    }
    test("usage_real_truncated_sse_remains_unknown") {
        UsageFixture(dir("real-truncated"),"truncated").use { f ->val r=f.request();check(r.second.contentEquals(f.expected));val s=f.ledger.snapshot();check(s.failed==1&&s.unknownAttempts==1L&&s.reportedAttempts==0L&&s.inputTokens==0L)}
    }
    test("usage_real_missing_usage_is_not_free") {
        UsageFixture(dir("real-missing"),"missing").use { f ->check(f.request().second.contentEquals(f.expected));check(f.ledger.snapshot().unknownAttempts==1L&&f.ledger.snapshot().successful==1)}
    }
    test("usage_real_fallback_one_request_two_attempts") {
        UsageFixture(dir("real-fallback"),"fallback").use { f ->check(f.request().second.contentEquals(f.expected));val s=f.ledger.snapshot();check(s.requests==1&&s.successful==1&&s.failed==0&&s.attempts==2L&&s.unknownAttempts==1L&&s.reportedAttempts==1L)}
    }
    test("usage_real_large_body_not_truncated_by_observer") {
        UsageFixture(dir("real-large"),"large").use { f ->check(f.request().second.contentEquals(f.expected));check(f.ledger.snapshot().successful==1&&f.ledger.snapshot().unknownAttempts==1L)}
    }
    test("usage_real_local_reject_has_no_attempt") {
        UsageFixture(dir("real-reject"),"json").use { f ->
            val response=f.request("missing");val before=f.ledger.snapshot()
            // Fixed-length responses can finish at the client before the server's finally appends.
            val deadline=System.nanoTime()+3_000_000_000L
            while(f.gateway.inflightPublic()!=0 && System.nanoTime()<deadline)Thread.sleep(1)
            check(f.gateway.inflightPublic()==0){"gateway did not finish request"}
            val s=f.ledger.snapshot()
            println("EVIDENCE|local_reject|http=${response.first}|before=${before.requests}|after=${s.json()}")
            check(response.first==404){"Unexpected HTTP ${response.first}"}
            check(s.localRejected==1&&s.attempts==0L&&s.unknownAttempts==0L){s.json().toString()}
        }
    }
}
