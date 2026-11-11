package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class LoggerLevelsLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier,
    private val withhold:Boolean=System.getenv("RECLAZZ_LOGGER_WITHHOLD_EDIT")=="true",
    private val omit:Boolean=System.getenv("RECLAZZ_LOGGER_OMIT_NATIVE")=="true")
    :BatchLiveTest("Native logger level reload",c,a,h) {
    override fun scenario() {
        val baseline=original("project.properties");val path=Path.of(config.extPath,"project.properties")
        check(Files.readAllBytes(path).contentEquals(baseline)) {"Dirty logger property baseline"}
        val target="com.onurkat.reclazztest.logprobe.target";val control="com.onurkat.reclazztest.logprobe.control"
        val key="log4j2.logger.reclazzProbeTarget.level"
        val changed=replaceOnce(baseline.toString(Charsets.UTF_8),"$key=INFO","$key=DEBUG").toByteArray()
        val run="reclazz-logger-${UUID.randomUUID()}";val params=mapOf("run" to run);val url="$endpoint/logger"
        var jvm:String?=null;val expected=mutableListOf<Map<String,String>>()
        var begun=false;var attempted=false;var changedProven=false
        fun yes(n:JsonNode,k:String) {check(n[k]?.isBoolean==true && n[k].asBoolean()) {"Logger $k false: $n"}}
        fun owned(n:JsonNode) {check(n["run"]?.asText()==run) {"Wrong logger run: $n"}}
        fun facts(n:JsonNode,level:String) {
            owned(n);yes(n,"sameContext");yes(n,"unchanged")
            check(n["targetLevel"]?.asText()==level && n["controlLevel"]?.asText()=="INFO") {"Logger level mismatch: $n"}
            val now=n["jvm"]?.takeIf {it.isTextual}?.asText();check(!now.isNullOrBlank())
            check(jvm==null || jvm==now) {"Logger JVM changed"};jvm=now
        }
        fun phase(name:String,level:String) {
            for((logger,kind) in listOf(target to "DEBUG",target to "INFO",control to "INFO")) {
                if(kind=="DEBUG" && level!="DEBUG") continue
                val role=if(logger==target) "target" else "control"
                expected.add(mapOf("logger" to logger,"level" to kind,"message" to "$run:$name:$role:$kind"))
            }
            val reply=json(post("$url/emit",params+mapOf("phase" to name,"omit" to (omit && name=="changed").toString())))
            facts(reply,level)
            val n=pollJson("$url/snapshot",params) {node ->
                facts(node,level)
                val events=node["events"];check(events?.isArray==true) {"Missing logger events"}
                val rows=events.map {event ->
                    check(event.isObject && event.size()==3 && listOf("logger","level","message").all {event[it]?.isTextual==true}) {"Malformed logger event"}
                    mapOf("logger" to event["logger"].asText(),"level" to event["level"].asText(),"message" to event["message"].asText())
                }
                check(rows.all {it in expected} && rows.distinct().size==rows.size) {"Unexpected/duplicate logger delivery: $node"}
                rows.size==expected.size && rows.toSet()==expected.toSet()
            }
            println("    Logger $name native delivery: $n")
        }
        fun save(bytes:ByteArray) = resourceWrite(path,bytes,listOf(key,target),
            {it.level=="OK" && it.message=="Logger level applied: [$target]"})
        withRestore({
            attempted=true
            val n=json(post("$url/begin",params));facts(n,"INFO");begun=true
            check(n["events"]?.isArray==true && n["events"].size()==0) {"Dirty logger sink"}
            phase("before","INFO")
            // Withheld-save control deliberately proceeds to the native DEBUG oracle.
            if(!withhold) save(changed)
            phase("changed","DEBUG");changedProven=true
        }) {
            restoreAll({
                save(baseline)
                if(begun) {
                    if(!changedProven) {
                        // A failed changed phase remains the primary failure. Check restoration
                        // independently of its missing event, preserving all events already seen.
                        val n=json(get("$url/snapshot",params));facts(n,"INFO")
                        val events=n["events"];check(events?.isArray==true)
                        val observed=events.map {e ->
                            check(e.isObject && e.size()==3 && listOf("logger","level","message").all {e[it]?.isTextual==true})
                            check(":restored:" !in e["message"].asText()) {"Premature restored event"}
                            mapOf("logger" to e["logger"].asText(),"level" to e["level"].asText(),"message" to e["message"].asText())
                        }
                        expected.clear();expected.addAll(observed)
                    }
                    phase("restored","INFO")
                }
            },{
                if(attempted) {
                    val n=json(post("$url/cleanup",params));owned(n);yes(n,"restored")
                    check(n["active"]?.isBoolean==true && !n["active"].asBoolean())
                    check(n["attachments"]?.isIntegralNumber==true && n["attachments"].asInt()==0) {"Logger sink leaked: $n"}
                    println("    Logger cleanup: $n")
                }
            },{check(Files.readAllBytes(path).contentEquals(baseline)) {"Logger bytes not restored"}})
        }
    }
}
