package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

/** Real retained-header and fresh-import persistence coverage. */
class ImpexTranslatorLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier,
    private val withhold:String=System.getenv("RECLAZZ_IMPEX_WITHHOLD_EDIT") ?: "none",
    private val omit:Boolean=System.getenv("RECLAZZ_IMPEX_OMIT_NATIVE")=="true")
    :BatchLiveTest("Native ImpEx translator and decorator",c,a,h) {
    override fun scenario() {
        check(withhold in setOf("none","translator","decorator"))
        val run="reclazz-impex-${UUID.randomUUID()}";val params=mapOf("run" to run)
        val url="$endpoint/impex-translator";val kinds=listOf("translator","decorator")
        val roles=kinds.flatMap {listOf("$it-warm","$it-fresh")}+"control"
        val eventStart=agentClient.cursor()
        val sources=kinds.associateWith {"src/com/onurkat/reclazztest/batch/ProbeImpex${if(it=="translator") "ValueTranslator" else "CellDecorator"}.java"}
        val immutable=sources.values+listOf("src/com/onurkat/reclazztest/batch/ProbeImpexState.java",
            "web/src/com/onurkat/reclazztest/controllers/ImpexTranslatorProbeController.java")
        for(rel in immutable)check(Files.readAllBytes(Path.of(config.extPath,rel)).contentEquals(original(rel))) {"Dirty ImpEx fixture: $rel"}
        var attempted=false;var begun=false;var phaseFailed=false;var steps=0;var baseline:Long?=null
        val pks=mutableMapOf<String,String>();val expected=mutableListOf<List<String>>()
        val ownedFiles=mutableListOf<Path>();var writeNumber=0
        fun owned(n:JsonNode) {check(n["run"]?.asText()==run) {"Wrong ImpEx run: $n"}}
        fun number(n:JsonNode,key:String,value:Long) {check(n[key]?.isIntegralNumber==true && n[key].asLong()==value) {"ImpEx $key expected $value: $n"}}
        fun yes(n:JsonNode,key:String) {check(n[key]?.isBoolean==true && n[key].asBoolean()) {"ImpEx $key false: $n"}}
        fun read(t:String,d:String,record:Boolean=false):JsonNode {
            val n=json(get("$url/read",params));owned(n);number(n,"owned",5)
            check(n["baseline"]?.isIntegralNumber==true);if(baseline==null)baseline=n["baseline"].asLong()
            number(n,"baseline",baseline!!);number(n,"global",baseline!!+5)
            val rows=n["rows"];check(rows?.isObject==true && rows.size()==5)
            for(role in roles) {
                val row=rows[role] ?: error("Missing role $role");check(row["code"]?.asText()=="$run-$role")
                val pk=row["pk"]?.takeIf {it.isTextual}?.asText();check(!pk.isNullOrBlank())
                if(record)pks[role]=pk else check(pks[role]==pk) {"PK changed: $n"}
                check(row["ordinary"]?.asText()=="$run:ordinary") {"Ordinary column changed: $n"}
                val value=if(role=="control") "$run:control" else "${role.substringBefore('-')}-${if(role.startsWith("translator")) t else d}:$run:raw"
                check(row["english"]?.asText()==value) {"Persisted $role expected $value: $n"}
            }
            val native=n["native"];owned(native);check(native["failure"]?.asText()=="") {"Native worker failed: $n"}
            number(native,"steps",steps.toLong());yes(native,"workerAlive")
            val events=native["events"];check(events?.isArray==true)
            val actual=events.map {e ->
                check(e["warm"]?.isBoolean==true&&e["worker"]?.isBoolean==true&&e["identity"]?.isIntegralNumber==true)
                listOf(e["kind"].asText(),e["raw"].asText(),e["result"].asText(),e["warm"].asText(),e["worker"].asText())
            }
            check(actual==expected) {"Native callback sequence differs: $n"}
            return n
        }
        fun phase(t:String,d:String) {
            try {
            val version=mapOf("translator" to t,"decorator" to d)
            for(kind in kinds)expected.add(listOf(kind,"$run:raw","$kind-${version.getValue(kind)}:$run:raw","true","true"))
            if(!omit) {
                val advanced=json(post("$url/advance",params));owned(advanced);number(advanced,"step",(steps+1).toLong())
                for(kind in kinds) {
                    val k=advanced[kind];yes(k,"sameReferences");yes(k,"worker");number(k,"processed",(steps+1).toLong());number(k,"dumped",0)
                    check(k["pk"]?.asText()==pks["$kind-warm"])
                }
            }
            steps++
            for(kind in kinds) {
                expected.add(listOf(kind,"$run:raw","$kind-${version.getValue(kind)}:$run:raw","false","false"))
                val script=get("$url/script",params+mapOf("kind" to kind));check(script.statusCode==200)
                val target=Path.of(config.impexDir,"reclazz-translator-probe-$run-${++writeNumber}-$kind.impex")
                check(!Files.exists(target)) {"Owned import filename collision: ${target.fileName}"}
                val cursor=agentClient.cursor();val expectedAfter=expected.toList()
                var created=false
                try {
                    Files.write(target,(script.body+"\n# owned phase $steps\n").toByteArray(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)
                    created=true;ownedFiles.add(target)
                    val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(config.eventTimeoutMs)
                    while(true) {
                        val arrivals=agentClient.getEvents().filter {it.sequence>cursor}
                        resourceFailure(arrivals,listOf(target.fileName.toString())) { false }?.let {error(it)}
                        val terminal=arrivals.any {it.level=="OK"&&it.message=="ImpEx imported: ${target.fileName}"}
                        if(terminal) {
                            val snapshot=json(get("$url/read",params));owned(snapshot)
                            val native=snapshot["native"];owned(native);number(native,"steps",steps.toLong());yes(native,"workerAlive")
                            check(native["failure"]?.asText()=="") {"Native worker failed: $snapshot"}
                            val observed=native["events"];check(observed?.isArray==true)
                            check(observed.size()<=expectedAfter.size) {"Duplicate native callback for ${target.fileName}: $snapshot"}
                            for((index,e) in observed.withIndex()) {
                                val want=expectedAfter[index]
                                check(e["kind"]?.asText()==want[0]&&e["raw"]?.asText()==want[1]&&e["warm"]?.isBoolean==true&&e["worker"]?.isBoolean==true
                                    &&e["warm"].asText()==want[3]&&e["worker"].asText()==want[4]) {"Wrong-kind or prior native callback for ${target.fileName}: $snapshot"}
                            }
                            if(observed.size()==expectedAfter.size) {
                                val row=snapshot["rows"]["$kind-fresh"]
                                check(row["pk"]?.asText()==pks["$kind-fresh"]&&row["code"]?.asText()=="$run-$kind-fresh"&&row["ordinary"]?.asText()=="$run:ordinary") {"Fresh import ownership changed: $snapshot"}
                                val want="$kind-${version.getValue(kind)}:$run:raw"
                                check(row["english"]?.asText()==want) {"Persisted $kind-fresh expected $want after ${target.fileName}: $snapshot"}
                                check(observed.last()["result"]?.asText()==want) {"Native callback and persistence differ: $snapshot"}
                                break
                            }
                        }
                        check(System.nanoTime()<deadline) {"Missing per-write import terminal/native callback for ${target.fileName}"}
                        Thread.sleep(10)
                    }
                } finally {if(created)Files.deleteIfExists(target)}
            }
            println("    ImpEx persisted/native phase $steps: ${read(t,d)}")
            } catch(e:Exception) {phaseFailed=true;throw e}
        }
        fun restoredPhase() {
            if(phaseFailed) {
                // Preserve the primary failure while checking recovery independently of its bad history.
                val n=json(get("$url/read",params));owned(n);val native=n["native"];owned(native)
                check(native["steps"]?.isIntegralNumber==true);steps=native["steps"].asInt()
                expected.clear()
                for(e in native["events"]) {
                    val kind=e["kind"].asText();check(kind in kinds && e["raw"].asText()=="$run:raw")
                    check(e["result"].asText() in listOf("$kind-v1:$run:raw","$kind-v2:$run:raw"))
                    check(e["warm"].isBoolean&&e["worker"].isBoolean)
                    expected.add(listOf(kind,e["raw"].asText(),e["result"].asText(),e["warm"].asText(),e["worker"].asText()))
                }
                phaseFailed=false
            }
            phase("v1","v1")
        }
        fun edit(kind:String,t:String,d:String) {
            val relative=sources.getValue(kind);val source=original(relative).toString(Charsets.UTF_8)
            val changed=replaceOnce(source,"\"$kind-v1:\"","\"$kind-v2:\"")
            if(withhold==kind) {
                val scratch=Files.createTempFile("reclazz-impex-withheld-",".java")
                try {Files.writeString(scratch,changed);withRestore({phase(t,d)}) {restoredPhase()}}
                finally {Files.deleteIfExists(scratch)}
            } else {
                val path=Path.of(config.extPath,relative)
                fun apply(bytes:ByteArray) {
                    val mark=agentClient.cursor()
                    if(writeChanged(path,bytes))awaitSources(agentClient,listOf(path),mark,config.eventTimeoutMs)
                }
                withRestore({apply(changed.toByteArray());phase(t,d)}) {
                    restoreAll({apply(original(relative))},{restoredPhase()})
                }
            }
        }
        withRestore({
            println("    ImpEx owned run: $run")
            attempted=true;val n=json(post("$url/begin",params));owned(n);begun=true
            read("v1","v1",true);phase("v1","v1")
            edit("translator","v2","v1");edit("decorator","v1","v2")
        }) {
            restoreAll({for(path in ownedFiles)Files.deleteIfExists(path)}, {
                if(attempted) {
                    val n=json(post("$url/cleanup",params));owned(n);yes(n,"idle");number(n,"owned",0)
                    if(begun) {number(n,"removed",5);number(n,"global",baseline!!);number(n,"baseline",baseline!!)}
                    println("    ImpEx cleanup: $n")
                }
            }, {
                val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(minOf(config.eventTimeoutMs,2000))
                do {
                    resourceFailure(agentClient.getEvents().filter {it.sequence>eventStart},ownedFiles.map {it.fileName.toString()}) {false}?.let {error(it)}
                    Thread.sleep(10)
                } while(System.nanoTime()<deadline)
                check(ownedFiles.none {Files.exists(it)}) {"Owned import file survived cleanup"}
            }, {
                for(rel in immutable)check(Files.readAllBytes(Path.of(config.extPath,rel)).contentEquals(original(rel))) {"ImpEx fixture not restored: $rel"}
            })
        }
    }
}
