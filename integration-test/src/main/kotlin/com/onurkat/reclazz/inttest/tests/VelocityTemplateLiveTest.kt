package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Native database template/media coverage through SAP RendererService. */
class VelocityTemplateLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier)
    :BatchLiveTest("Native Velocity RendererTemplate content",c,a,h) {
    override fun scenario() {
        val run=UUID.randomUUID().toString();val params=mapOf("run" to run)
        val url="$endpoint/velocity-template";val files=mutableListOf<Path>()
        var attempted=false;var admitted=false;var ordinal=0
        var service:Long?=null;var jvm:Long?=null;var controlPk:String?=null;var controlMedia:String?=null
        val relative="web/src/com/onurkat/reclazztest/controllers/VelocityTemplateProbeController.java"
        val fixture=Path.of(config.extPath,relative)
        check(Files.readAllBytes(fixture).contentEquals(original(relative))) {"Dirty Velocity fixture"}
        fun owned(n:JsonNode) {
            check(n["run"]?.asText()==run) {"Wrong template run: $n"}
            for(key in listOf("serviceIdentity","jvmStart"))check(n[key]?.isIntegralNumber==true)
            if(service==null){service=n["serviceIdentity"].asLong();jvm=n["jvmStart"].asLong()}
            check(service==n["serviceIdentity"].asLong()&&jvm==n["jvmStart"].asLong()) {"Template scope changed: $n"}
        }
        fun persisted(n:JsonNode,version:String) {
            owned(n)
            for(role in listOf("subject","body","control")) {
                val row=n[role];val code="reclazz-velocity-$run-$role"
                check(row?.isObject==true&&row["code"]?.asText()==code)
                check(row["pk"]?.isTextual==true&&row["mediaPk"]?.isTextual==true&&row["mediaCode"]?.asText()==code+"_defaultTemplateScript") {"Wrong owned media: $n"}
                val prefix=when(role){"subject"->"Subject-$version";"body"->"Body-$version";else->"Control"}
                check(row["content"]?.asText()==prefix+" "+'$'+"ctx.name | "+'$'+"ctx.nonce") {"Persisted template content stale: $n"}
                if(role=="control") {
                    if(controlPk==null){controlPk=row["pk"].asText();controlMedia=row["mediaPk"].asText()}
                    check(controlPk==row["pk"].asText()&&controlMedia==row["mediaPk"].asText()) {"Control template identity changed"}
                }
            }
        }
        fun phase(label:String,version:String) {
            val input="$run:$label"
            val n=json(post("$url/render",params+mapOf("input" to input)))
            persisted(n,version)
            check(n["input"]?.asText()==input) {"Wrong template binding: $n"}
            check(n["locale"]?.asText()=="en") {"Wrong template locale: $n"}
            check(n["subjectResult"]?.asText()=="Subject-$version Örnek <&> | $input"&&n["bodyResult"]?.asText()=="Body-$version Örnek <&> | $input") {"Stale native template result: $n"}
            check(n["controlResult"]?.asText()=="Control Örnek <&> | $input") {"Independent template control changed: $n"}
            println("VELOCITY_TEMPLATE_PHASE $label expected=$version proof=$n")
        }
        fun importVersion(version:String) {
            val script=get("$url/script",params+mapOf("version" to version));check(script.statusCode==200)
            val path=Path.of(config.impexDir,"reclazz-velocity-$run-${++ordinal}.impex")
            val mark=agentClient.cursor()
            Files.writeString(path,script.body,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);files.add(path)
            try {
                val until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(config.eventTimeoutMs)
                while(true) {
                    val events=agentClient.getEvents().filter {it.sequence>mark}
                    resourceFailure(events,listOf(path.fileName.toString())){false}?.let {error(it)}
                    if(events.any {it.level=="OK"&&it.message=="ImpEx imported: ${path.fileName}"})break
                    check(System.nanoTime()<until) {"Missing exact template import terminal: ${path.fileName}"}
                    Thread.sleep(10)
                }
                persisted(json(get("$url/read",params)),version)
            }finally {Files.deleteIfExists(path)}
        }
        withRestore({
            attempted=true;val begin=json(post("$url/begin",params));admitted=true;persisted(begin,"v1")
            phase("baseline","v1");phase("withheld","v1")
            withRestore({importVersion("v2");phase("edited","v2")}) {importVersion("v1");phase("restored","v1")}
            println("VELOCITY_TEMPLATE_OUTCOME NATIVE_RENDERER_ROUND_TRIP")
        }) {
            restoreAll({for(file in files)Files.deleteIfExists(file)}, {
                if(attempted) {
                    val response=post("$url/cleanup",params);val n=json(response)
                    check(n["run"]?.asText()==run);if(admitted)check(n["admitted"]?.isBoolean==true&&n["admitted"].asBoolean())
                    check(n["ownedTemplates"]?.isIntegralNumber==true&&n["ownedTemplates"].asLong()==0L&&n["ownedMedia"]?.isIntegralNumber==true&&n["ownedMedia"].asLong()==0L) {"Owned template/media cleanup did not prove zero: $n"}
                    requireOwnedCleanup(response);println("VELOCITY_TEMPLATE_CLEANUP $n")
                }
            }, {check(files.none {Files.exists(it)}) {"Owned script import file survived"}},
                {check(Files.readAllBytes(fixture).contentEquals(original(relative))) {"Velocity fixture changed"}})
        }
    }
}
