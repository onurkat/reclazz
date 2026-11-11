package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestStatus
import com.sun.net.httpserver.HttpServer
import java.net.*
import java.nio.file.*
import java.util.concurrent.TimeUnit

private class ImpexContractServer(private val root:Path,private val mode:String):AutoCloseable {
    private val socket=ServerSocket(0,1,InetAddress.getLoopbackAddress())
    val portFile=Files.createTempFile("reclazz-impex-contract-",".port").also {Files.writeString(it,socket.localPort.toString())}
    private var connection:Socket?=null
    private lateinit var writer:java.io.PrintWriter
    private val http=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    @Volatile private var watching=true
    @Volatile var cleaned=false
    private var run="";private var steps=0
    private val events=mutableListOf<Map<String,Any>>()
    private val values=mutableMapOf<String,String>()
    private val kinds=listOf("translator","decorator")
    private val roles=kinds.flatMap {listOf("$it-warm","$it-fresh")}+"control"
    private val filenames=mapOf("translator" to "ProbeImpexValueTranslator","decorator" to "ProbeImpexCellDecorator")
    private val source=kinds.associateWith {root.resolve("src/com/onurkat/reclazztest/batch/${filenames[it]}.java")}
    private val resourceDir=Files.createDirectories(root.resolve("resources/impex"))
    private fun version(kind:String)=if(Files.readString(source.getValue(kind)).contains("\"$kind-v2:\"")) "v2" else "v1"
    private fun observe(kind:String,warm:Boolean) {
        val v=if(mode=="stale-warm"&&warm) "v1" else version(kind)
        val role="$kind-${if(warm) "warm" else "fresh"}";values[role]="$kind-$v:$run:raw"
        val e=mapOf<String,Any>("kind" to kind,"raw" to if(mode=="wrong-token") "foreign" else "$run:raw","result" to values.getValue(role),
            "warm" to warm,"worker" to warm,"identity" to if(warm) kinds.indexOf(kind)+1 else 100+steps)
        if(mode!="missing-callback")events.add(e)
        if(mode=="duplicate-callback")events.add(e)
    }
    @Synchronized private fun read():Map<String,Any> {
        val rows=roles.associateWith {role -> mapOf("pk" to "pk-$role","code" to "$run-$role",
            "english" to if(mode=="control-mutated"&&role=="control") "changed" else values.getValue(role),
            "ordinary" to if(mode=="ordinary-mutated") "changed" else "$run:ordinary")}
        return mapOf("run" to run,"rows" to rows,"owned" to 5,"baseline" to 100,"global" to if(mode=="global-mutated") 106 else 105,
            "native" to mapOf("run" to run,"steps" to steps,"workerAlive" to true,"failure" to if(mode=="worker-error") "synthetic failure" else "","events" to events.toList()))
    }
    init {
        Thread({connection=socket.accept();writer=java.io.PrintWriter(connection!!.getOutputStream(),true);emit("CONNECTED","impex-contract");emit("OK","ready")},"impex-contract-connect").apply {isDaemon=true;start()}
        http.createContext("/") {x ->
            try {
                val form=if(x.requestMethod=="POST") x.requestBody.readBytes().toString(Charsets.UTF_8) else x.requestURI.rawQuery ?: ""
                val params=form.split('&').filter {it.isNotBlank()}.associate {it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='),Charsets.UTF_8)}
                var status=200;var text:String?=null
                val response=synchronized(this) {
                    when(x.requestURI.path.substringAfterLast('/')) {
                        "begin" -> {
                            run=params.getValue("run");steps=0;events.clear()
                            for(role in roles)values[role]=if(role=="control") "$run:control" else "${role.substringBefore('-')}-v1:$run:raw"
                            if(mode=="partial-seed")status=500
                            read()
                        }
                        "script" -> {text="#kind=${params.getValue("kind")}\n";emptyMap<String,Any>()}
                        "advance" -> {
                            steps++;for(kind in kinds)observe(kind,true)
                            mapOf("run" to run,"step" to steps)+kinds.associateWith {kind -> mapOf("sameReferences" to (mode!="changed-reference"),"worker" to (mode!="changed-thread"),
                                "processed" to steps,"dumped" to if(mode=="dumped") 1 else 0,"pk" to if(mode=="wrong-pk") "foreign" else "pk-$kind-warm")}
                        }
                        "cleanup" -> {cleaned=true;mapOf("run" to params.getValue("run"),"removed" to if(mode=="partial-seed") 2 else 5,"owned" to if(mode=="leftover") 1 else 0,
                            "idle" to (mode!="worker-leak"),"baseline" to 100,"global" to 100)}
                        else -> read()
                    }
                }
                val bytes=(text ?: batchJson.writeValueAsString(response)).toByteArray();x.sendResponseHeaders(status,bytes.size.toLong());x.responseBody.use {it.write(bytes)}
            }catch(e:Exception){x.sendResponseHeaders(500,-1);x.close()}
        };http.start()
        Thread({
            val previous=source.mapValues {Files.readAllBytes(it.value)}.toMutableMap()
            val seen=mutableMapOf<Path,ByteArray>()
            val pending=mutableListOf<Pair<Long,String>>()
            while(watching) {
                if(::writer.isInitialized) {
                    for(kind in kinds) {
                        val next=Files.readAllBytes(source.getValue(kind))
                        if(!next.contentEquals(previous.getValue(kind))) {
                            previous[kind]=next
                            val changed=version(kind)=="v2";val name=filenames.getValue(kind)
                            when {
                                changed&&mode=="missing-receipt" -> Unit
                                changed&&mode=="wrong-owner" -> {emit("COMPILE","Compiled $name.java (1ms)");emit("RELOAD","Reloaded com.example.Foreign (1ms)")}
                                changed&&mode=="reversed" -> {emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.$name (1ms)");emit("COMPILE","Compiled $name.java (1ms)")}
                                else -> {emit("COMPILE","Compiled $name.java (1ms)");emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.$name (1ms)")}
                            }
                        }
                    }
                    Files.list(resourceDir).use {paths -> paths.filter {it.toString().endsWith(".impex")}.forEach {file ->
                        val next=Files.readAllBytes(file)
                        if(seen[file]?.contentEquals(next)!=true) {
                            seen[file]=next;val script=next.toString(Charsets.UTF_8)
                            val kind=kinds.find {script.contains("#kind=$it")}
                            when {
                                kind==null -> emit("OK","ImpEx imported: ${file.fileName}")
                                mode=="delayed-native" -> {
                                    emit("OK","ImpEx imported: earlier-${file.fileName}")
                                    emit("OK","ImpEx imported: ${file.fileName}")
                                    pending.add((System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(80)) to kind)
                                }
                                mode=="stale-current-terminal" -> emit("OK","ImpEx imported: ${file.fileName}")
                                else -> {
                                    synchronized(this) {
                                        observe(if(mode=="wrong-kind-callback") kinds.first {it!=kind} else kind,false)
                                        if(mode=="duplicate-pending-import")observe(kind,false)
                                    }
                                    when(mode) {
                                        "missing-import-receipt" -> Unit
                                        "old-terminal-only" -> emit("OK","ImpEx imported: earlier-${file.fileName}")
                                        else -> emit("OK","ImpEx imported: ${file.fileName}")
                                    }
                                }
                            }
                        }
                    }}
                    val deleted=seen.keys.filter { !Files.exists(it) }
                    for(file in deleted) {
                        seen.remove(file)
                        if(mode=="deleted-file-warning")emit("WARN","ImpEx file not found: $file")
                    }
                    pending.removeIf {item ->
                        if(System.nanoTime()<item.first)false else {synchronized(this){observe(item.second,false)};true}
                    }
                }
                Thread.sleep(3)
            }
        },"impex-contract-watch").apply {isDaemon=true;start()}
    }
    val baseUrl get()="http://127.0.0.1:${http.address.port}"
    @Synchronized fun emit(level:String,message:String) {writer.println(batchJson.writeValueAsString(mapOf("level" to level,"message" to message,"timestamp" to "contract")))}
    override fun close(){watching=false;http.stop(0);connection?.close();socket.close();Files.deleteIfExists(portFile)}
}

fun runImpexTranslatorHarnessContracts():Int {
    val modes=listOf("normal","withheld-translator","withheld-decorator","stale-warm","omit-native","changed-reference","changed-thread","dumped","wrong-pk",
        "wrong-token","missing-callback","duplicate-callback","ordinary-mutated","control-mutated","global-mutated","worker-error","missing-receipt","wrong-owner","reversed","missing-import-receipt","partial-seed","leftover","worker-leak","delayed-native","old-terminal-only","stale-current-terminal","wrong-kind-callback","duplicate-pending-import","deleted-file-warning")
    for(mode in modes) {
        val root=Files.createTempDirectory("reclazz-impex-harness-")
        val relative=listOf("src/com/onurkat/reclazztest/batch/ProbeImpexValueTranslator.java","src/com/onurkat/reclazztest/batch/ProbeImpexCellDecorator.java",
            "src/com/onurkat/reclazztest/batch/ProbeImpexState.java","web/src/com/onurkat/reclazztest/controllers/ImpexTranslatorProbeController.java")
        try {
            for(rel in relative) {val p=root.resolve(rel);Files.createDirectories(p.parent);Files.write(p,ImpexTranslatorLiveTest::class.java.classLoader.getResourceAsStream("templates/batch/$rel")!!.use {it.readBytes()})}
            val before=relative.associateWith {Files.readAllBytes(root.resolve(it))}
            ImpexContractServer(root,mode).use {server ->
                val client=AgentEventClient(server.portFile.toString());client.connect()
                try {
                    val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                    while(client.getEvents().none {it.level=="OK"}) {check(System.nanoTime()<until);Thread.sleep(5)}
                    val config=TestConfig(server.baseUrl,root.toString(),server.portFile.toString(),eventTimeoutMs=350)
                    val result=ImpexTranslatorLiveTest(config,client,HttpVerifier(server.baseUrl,1000),if(mode.startsWith("withheld-")) mode.substringAfter("withheld-") else "none",mode=="omit-native").run()
                    check(result.status==if(mode in setOf("normal","delayed-native")) TestStatus.PASS else TestStatus.FAIL) {"$mode: $result"}
                    check(server.cleaned) {"$mode missed cleanup: $result"}
                    for(rel in relative)check(Files.readAllBytes(root.resolve(rel)).contentEquals(before.getValue(rel))) {"$mode not restored: $rel"}
                    Files.list(root.resolve("resources/impex")).use {paths->
                        check(paths.noneMatch {it.fileName.toString().startsWith("reclazz-translator-probe-reclazz-impex-")}) {"$mode left an owned import file"}
                    }
                    if(mode.startsWith("withheld-"))check("restore:" !in result.notes) {"Withheld failure prevented independent recovery: $result"}
                    println("IMPEX_HARNESS PASS $mode")
                }finally{client.disconnect()}
            }
        }finally{root.toFile().deleteRecursively()}
    }
    return modes.size
}
fun main(){println("IMPEX_HARNESS: ${runImpexTranslatorHarnessContracts()} cases, 0 failures")}
