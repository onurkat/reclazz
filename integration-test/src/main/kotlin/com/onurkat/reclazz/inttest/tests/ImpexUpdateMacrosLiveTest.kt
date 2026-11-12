package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEvent
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult
import com.onurkat.reclazz.inttest.report.TestStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

internal fun impexUpdateScript(run:String,version:String,malformed:Boolean=false):String {
    check(run.matches(Regex("reclazz-impex-[0-9a-f-]{36}"))&&version in setOf("v0","v1","v2","unapplied"))
    val good="\$type=Title\n\$column=name[lang=en]\n\$code=$run-target\n\$value=$run:$version\nUPDATE \$type;code[unique=true];\$column\n;\$code;\$value\n"
    return good+if(malformed)"\$undefinedHeader;code[unique=true];\$column\n;\$code;$run:bad\n" else ""
}
internal fun requireImpexUpdateFacts(n:JsonNode,run:String,version:String,seed:JsonNode?=null) {
    check(n.path("run").asText()==run&&n.path("owned").isIntegralNumber&&n.path("owned").asInt()==2)
    val rows=n.path("rows");check(rows.isObject&&rows.size()==2)
    for(role in listOf("target","control")) {
        val row=rows.path(role);check(row.path("code").asText()=="$run-$role")
        check(row.path("pk").isTextual&&row.path("pk").asText().isNotBlank())
        check(row.path("english").asText()=="$run:"+if(role=="target")version else "control")
        check(row.path("german").asText()=="$run:ordinary")
        if(seed!=null)check(row.path("pk")==seed.path("rows").path(role).path("pk")) {"Owned PK changed"}
    }
}
internal fun requireImpexUpdateReceipt(event:AgentEvent?,file:String,guard:Boolean) {
    check(event!=null) {"Missing ImpEx outcome for $file"}
    if(guard)check(event.level=="WARN"&&event.message.startsWith("ImpEx not imported: $file has a REMOVE or unresolved macro header at line 7.")) {"Wrong guard outcome: $event"}
    else check(event.level=="OK"&&event.message=="ImpEx imported: $file") {"Wrong native import outcome: $event"}
}

class ImpexUpdateMacrosLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):BatchLiveTest("Native ImpEx UPDATE macros and guard recovery",c,a,h) {
    override fun run():TestResult {
        val start=System.currentTimeMillis()
        return try {
            val gates=json(get("$endpoint/impex-update/gates"))
            if(gates.path("autoImpex").isBoolean&&gates.path("autoImpex").asBoolean()&&gates.path("impexAllowRemove").isBoolean&&!gates.path("impexAllowRemove").asBoolean())super.run()
            else result(start,TestStatus.SKIP,"requires autoImpex=true and impexAllowRemove=false; no setup or save attempted")
        }catch(e:Exception){result(start,TestStatus.FAIL,e.message?:e.toString())}
    }
    override fun scenario() {
        val nonce=UUID.randomUUID().toString();val run="reclazz-impex-$nonce";val params=mapOf("run" to run)
        val url="$endpoint/impex-update";val file="reclazz-update-$nonce.impex";val path=Path.of(config.impexDir,file)
        check(!Files.exists(path)) {"Owned file collision"}
        var attempted=false;var begun=false;var created=false;var seed:JsonNode?=null
        fun read(version:String):JsonNode {val n=json(get("$url/read",params));requireImpexUpdateFacts(n,run,version,seed);return n}
        fun apply(version:String,guard:Boolean=false) {
            val mark=agentClient.cursor();val bytes=impexUpdateScript(run,if(guard)"unapplied" else version,guard).toByteArray()
            if(!created) {Files.write(path,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);created=true}
            else Files.write(path,bytes)
            val outcome=agentClient.waitForImpex(config.eventTimeoutMs,file,mark);requireImpexUpdateReceipt(outcome,file,guard)
            val n=read(version)
            if(guard)check(agentClient.getEvents().none {it.sequence>mark&&it.level=="OK"&&it.message=="ImpEx imported: $file"}) {"False import success during rejected phase"}
            println("IMPEX_UPDATE_${if(guard)"GUARD" else "NATIVE"} version=$version receipt=$outcome persisted=$n")
        }
        withRestore({
            attempted=true;val n=json(post("$url/begin",params));begun=true;requireImpexUpdateFacts(n,run,"v0");seed=n
            check(runCatching {requireImpexUpdateFacts(n,run,"v1",seed)}.isFailure) {"WITHHELD_SAVE_FALSE_PASS"}
            println("IMPEX_UPDATE_WITHHELD_RED actual=$n")
            apply("v1");apply("v1",true);apply("v2")
        }) {
            // A fresh successful restore receipt completes before owned rows are removed.
            restoreAll({if(begun&&created)apply("v0")}, {
                if(created)Files.deleteIfExists(path)
                check(!Files.exists(path)) {"Owned ImpEx file remains"}
            }, {if(attempted) {val cleanup=post("$url/cleanup",params);requireOwnedCleanup(cleanup);check(json(cleanup).path("run").asText()==run);println("IMPEX_UPDATE_CLEANUP ${cleanup.body}")}})
        }
        println("IMPEX_UPDATE_OUTCOME NATIVE_MACRO_UPDATE_GUARD_CORRECTION")
    }
}
