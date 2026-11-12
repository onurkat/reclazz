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

/** Native model repository coverage; retained compiled objects are reported separately. */
class StoredScriptLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier)
    :BatchLiveTest("Stored script through native executable cache",c,a,h) {
    override fun scenario() {
        val run=UUID.randomUUID().toString();val params=mapOf("run" to run)
        val url="$endpoint/stored-script";val files=mutableListOf<Path>()
        var attempted=false;var admitted=false;var ordinal=0
        var service:Long?=null;var jvm:Long?=null;var retained:Long?=null;var controlPk:String?=null
        val relative="web/src/com/onurkat/reclazztest/controllers/StoredScriptProbeController.java"
        val fixture=Path.of(config.extPath,relative)
        check(Files.readAllBytes(fixture).contentEquals(original(relative))) {"Dirty script fixture"}
        fun owned(n:JsonNode) {
            check(n["run"]?.asText()==run) {"Wrong script run: $n"}
            check(n["uri"]?.asText()=="model://reclazz-script-$run-target") {"Wrong stored URI: $n"}
            for(key in listOf("serviceIdentity","jvmStart","retainedIdentity"))check(n[key]?.isIntegralNumber==true)
            if(service==null){service=n["serviceIdentity"].asLong();jvm=n["jvmStart"].asLong();retained=n["retainedIdentity"].asLong()}
            check(service==n["serviceIdentity"].asLong()&&jvm==n["jvmStart"].asLong()&&retained==n["retainedIdentity"].asLong()) {"Script scope changed: $n"}
            check(n["warmExecutableSame"]?.isBoolean==true&&n["warmResolutions"]?.isIntegralNumber==true&&n["warmResolutions"].asInt()==2) {"Native URI was not warmed: $n"}
        }
        fun persisted(n:JsonNode,version:String) {
            owned(n)
            for(role in listOf("target","control")) {
                val row=n[role];check(row?.isObject==true&&row["code"]?.asText()=="reclazz-script-$run-$role")
                check(row["pk"]?.isTextual==true&&row["checksum"]?.isTextual==true&&row["version"]?.isIntegralNumber==true)
                check(row["revisions"]?.isArray==true&&row["revisions"].size()>0)
                val prefix=if(role=="control")"control:" else "script-$version:"
                check(row["content"]?.asText()=="return '$prefix' + nonce") {"Persisted script content stale: $n"}
                if(role=="control") {if(controlPk==null)controlPk=row["pk"].asText();check(controlPk==row["pk"].asText()) {"Control script identity changed"}}
            }
        }
        fun phase(label:String,version:String) {
            val input="$run:$label"
            val n=json(post("$url/execute",params+mapOf("input" to input)))
            persisted(n,version)
            check(n["input"]?.asText()==input) {"Wrong script binding: $n"}
            check(n["freshResult"]?.asText()=="script-$version:$input") {"Stale fresh script result: $n"}
            check(n["controlResult"]?.asText()=="control:$input"&&n["retainedControlResult"]?.asText()=="control:$input") {"Independent script control changed: $n"}
            check(n["retainedResult"]?.isTextual==true) {"Missing retained native observation"}
            println("STORED_SCRIPT_PHASE $label expected=$version proof=$n")
        }
        fun importVersion(version:String) {
            val script=get("$url/script",params+mapOf("version" to version));check(script.statusCode==200)
            val path=Path.of(config.impexDir,"reclazz-script-$run-${++ordinal}.impex")
            val mark=agentClient.cursor()
            Files.writeString(path,script.body,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);files.add(path)
            try {
                val until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(config.eventTimeoutMs)
                while(true) {
                    val events=agentClient.getEvents().filter {it.sequence>mark}
                    resourceFailure(events,listOf(path.fileName.toString())){false}?.let {error(it)}
                    if(events.any {it.level=="OK"&&it.message=="ImpEx imported: ${path.fileName}"})break
                    check(System.nanoTime()<until) {"Missing exact script import terminal: ${path.fileName}"}
                    Thread.sleep(10)
                }
                persisted(json(get("$url/read",params)),version)
            }finally {Files.deleteIfExists(path)}
        }
        withRestore({
            attempted=true;val begin=json(post("$url/begin",params));admitted=true;persisted(begin,"v1")
            phase("baseline","v1");phase("withheld","v1")
            withRestore({importVersion("v2");phase("edited","v2")}) {importVersion("v1");phase("restored","v1")}
            println("STORED_SCRIPT_OUTCOME NATIVE_MODEL_URI_ROUND_TRIP")
        }) {
            restoreAll({for(file in files)Files.deleteIfExists(file)}, {
                if(attempted) {
                    val response=post("$url/cleanup",params);val n=json(response)
                    check(n["run"]?.asText()==run);if(admitted)check(n["admitted"]?.isBoolean==true&&n["admitted"].asBoolean())
                    requireOwnedCleanup(response);println("STORED_SCRIPT_CLEANUP $n")
                }
            }, {check(files.none {Files.exists(it)}) {"Owned script import file survived"}},
                {check(Files.readAllBytes(fixture).contentEquals(original(relative))) {"Script fixture changed"}})
        }
    }
}
