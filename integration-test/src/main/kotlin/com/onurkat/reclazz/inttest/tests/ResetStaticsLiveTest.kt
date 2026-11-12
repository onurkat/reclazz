package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEvent
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal fun staticResetReceipt(events:List<AgentEvent>, after:Long, owner:String, enabled:Boolean, refusal:Boolean):Boolean {
    val seen=events.filter {it.sequence>after}
    val expected=if(refusal) {
        if(enabled) setOf("Static $owner.COUNT initializer changed, not reset: became-constant (callers inline it); restart to apply",
            "Static $owner.REFUSED initializer changed, not reset: unapproved-call; restart to apply") else emptySet()
    } else if(enabled) setOf("Reset static $owner.CODES (initializer changed)") else
        setOf("Static $owner.CODES initializer changed, not reset: option-off (enable resetStatics=true); restart to apply")
    check(seen.none {it.level=="ERROR" || (it.level=="WARN" && it.message !in expected)}) {"Unexpected static reload event: $seen"}
    return expected.all {message->seen.any {it.message==message && it.level==if(message.startsWith("Reset")) "INFO" else "WARN"}}
}

/** One boot lane only; outer driver proves both lanes and returns to the normal boot. */
class ResetStaticsLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier,private val enabled:Boolean):
    BatchLiveTest("Static reset ${if(enabled) "on" else "off"} attempt",c,a,h) {
    private val relative="src/com/onurkat/reclazztest/batch/StaticProbe.java"
    private val controller="web/src/com/onurkat/reclazztest/controllers/StaticProbeController.java"
    private val owner="com.onurkat.reclazztest.batch.StaticProbe"
    private var deadline=0L
    private fun left()=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()).also {check(it>0){"Static profile deadline"}}
    private fun preflight():String? {
        profileGateReason(json(get("$endpoint/gates")),linkedMapOf("agentArgsFound" to true,"agentOptionsMatchConfig" to true,
            "autoCompile" to true,"resetStatics" to enabled,"autoUpdateRunningSystem" to false,
            "autoSolrReindex" to false,"impexAllowRemove" to false))?.let{return it}
        val home=System.getenv("RECLAZZ_TEST_HYBRIS_HOME")?.let{Path.of(it).toRealPath()} ?: return "Clean install home missing"
        if(home!=cleanSapHybris(Path.of(config.extPath).toRealPath().parent.parent).normalize() ||
            !Files.isSameFile(home.resolve("bin/custom/reclazztest"),Path.of(config.extPath))) return "Clean install fixture mismatch"
        for(rel in listOf(relative,controller)) if(!Files.readAllBytes(Path.of(config.extPath,rel)).contentEquals(original(rel))) return "Dirty static fixture: $rel"
        val state=json(get("$endpoint/statics"))
        if(state.path("direct").asText()!="v1" || state.path("retainedGetter").asText()!="v1" || state.path("version").asText()!="body-v1" ||
            state.path("runtimeValue").asText()!="startup" || state.path("initializerCalls").asInt()!=1 ||
            state.path("count").asInt()!=7 || state.path("refused").asText()!="call-v1") return "Static baseline unavailable: $state"
        return null
    }
    override fun run():TestResult=runGatedProfile(name,::preflight,::scenario)
    override fun scenario() {
        deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3)
        val nonce="reclazz-statics-"+java.util.UUID.randomUUID()
        val warm=json(post("$endpoint/statics/warm",mapOf("nonce" to nonce)))
        val identity=warm.path("identity").asInt()
        fun verify(value:String,version:String) {
            val state=json(httpVerifier.poll(left(),"Static $value/$version",{httpVerifier.get("$endpoint/statics",emptyMap(),it)}) {
                it.statusCode==200 && runCatching {val s=json(it);s.path("direct").asText()==value &&
                    s.path("retainedGetter").asText()==value && s.path("version").asText()==version}.getOrDefault(false)
            })
            check(state.path("runtimeValue").asText()==nonce && state.path("identity").asInt()==identity &&
                state.path("count").asInt()==7 && state.path("refused").asText()=="call-v1" && state.path("initializerCalls").asInt()==1) {"Unrelated state changed: $state"}
            println("    Static native read: $state")
        }
        val baseline=original(relative)
        val edited=replaceOnce(replaceOnce(baseline.toString(Charsets.UTF_8),"Map.of(\"key\", \"v1\")","Map.of(\"key\", \"v2\")"),"return \"body-v1\"","return \"body-v2\"")
        fun apply(bytes:ByteArray, receipt:Boolean=true, refusal:Boolean=false) {
            val mark=agentClient.cursor();check(writeChanged(Path.of(config.extPath,relative),bytes))
            awaitSources(agentClient,listOf(Path.of(config.extPath,relative)),mark,left())
            if(receipt) while(!staticResetReceipt(agentClient.getEvents(),mark,owner,enabled,refusal)) {left();Thread.sleep(20)}
        }
        withRestore({
            verify("v1","body-v1")
            apply(edited.toByteArray());verify(if(enabled) "v2" else "v1","body-v2")
            val refused=replaceOnce(replaceOnce(replaceOnce(edited,"Integer.parseInt(\"7\")","9"),"applicationValue(\"call-v1\")","applicationValue(\"call-v2\")"),"return \"body-v2\"","return \"body-v3\"")
            apply(refused.toByteArray(),refusal=true);verify(if(enabled) "v2" else "v1","body-v3")
            check(System.getenv("RECLAZZ_STATICS_INJECT_FAILURE")!="true") {"Injected static failure after reload and runtime mutation"}
        }) {
            // Outer recovery is still required if the attempt or this live restoration fails.
            apply(baseline,receipt=enabled)
            verify("v1","body-v1")
            check(Files.readAllBytes(Path.of(config.extPath,relative)).contentEquals(baseline))
        }
    }
}
