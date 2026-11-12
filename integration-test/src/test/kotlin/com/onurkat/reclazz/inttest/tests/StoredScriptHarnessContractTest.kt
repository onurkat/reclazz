package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestStatus
import com.sun.net.httpserver.HttpServer
import java.net.*
import java.nio.file.*
import java.util.concurrent.TimeUnit

private class StoredScriptContractServer(private val root:Path,private val mode:String):AutoCloseable {
    private val socket=ServerSocket(0,1,InetAddress.getLoopbackAddress())
    val portFile=Files.createTempFile("reclazz-script-contract-",".port").also {Files.writeString(it,socket.localPort.toString())}
    private val http=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    private var connection:Socket?=null
    private lateinit var writer:java.io.PrintWriter
    @Volatile private var running=true
    @Volatile private var run=""
    @Volatile private var v2=false
    @Volatile private var edited=false
    @Volatile var admitted=false
    @Volatile var cleaned=false
    private val impex=Files.createDirectories(root.resolve("resources/impex"))
    private val watcher:Thread
    private fun script(version:String)="UPDATE Script;code[unique=true];active[unique=true];content\n;reclazz-script-$run-target;true;return 'script-$version:' + nonce\n"
    private fun snapshot():MutableMap<String,Any> {
        fun row(role:String):Map<String,Any> {
            val version=if(role=="control")"control:" else "script-${if(v2)"v2" else "v1"}:"
            return mapOf("code" to "reclazz-script-$run-$role","pk" to "$role-pk","checksum" to "checksum-$version",
                "version" to if(v2)1 else 0,"content" to "return '$version' + nonce","revisions" to listOf("$role-pk"))
        }
        return mutableMapOf("run" to run,"uri" to "model://reclazz-script-$run-target","target" to row("target"),
            "control" to row("control"),"serviceIdentity" to 11,"jvmStart" to 22,"retainedIdentity" to 33,"warmExecutableSame" to false,"warmResolutions" to 2)
    }
    init {
        Thread({connection=socket.accept();writer=java.io.PrintWriter(connection!!.getOutputStream(),true);emit("CONNECTED","stored-script-contract")},"script-contract-accept").apply {isDaemon=true;start()}
        http.createContext("/") {x ->
            try {
                val query=if(x.requestMethod=="POST")x.requestBody.readBytes().toString(Charsets.UTF_8) else x.requestURI.rawQuery.orEmpty()
                val p=query.split('&').filter {it.isNotBlank()}.associate {it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='),Charsets.UTF_8)}
                val action=x.requestURI.path.substringAfterLast('/')
                val response:Any=when(action) {
                    "begin" -> {run=p.getValue("run");admitted=true;snapshot()}
                    "read" -> snapshot()
                    "script" -> script(p.getValue("version"))
                    "cleanup" -> {cleaned=true;mapOf("run" to run,"admitted" to admitted,"ownedRemaining" to if(mode=="cleanup-residue")1 else 0)}
                    "execute" -> {
                        val input=p.getValue("input")
                        val version=when {
                            mode=="stale-fresh-v2"&&v2 -> "v1"
                            mode=="stale-restored"&&edited&&!v2 -> "v2"
                            v2 -> "v2"
                            else -> "v1"
                        }
                        snapshot().apply {
                            put("input",if(mode=="wrong-binding")"foreign" else input)
                            put("freshResult","script-$version:$input");put("retainedResult","script-v1:$input")
                            put("controlResult",if(mode=="changed-control")"changed" else "control:$input")
                            put("retainedControlResult","control:$input")
                            if(mode=="wrong-run")put("run","foreign")
                        }
                    }
                    else -> error("Unknown contract endpoint")
                }
                val body=(if(response is String)response else batchJson.writeValueAsString(response)).toByteArray()
                x.sendResponseHeaders(200,body.size.toLong());x.responseBody.use {it.write(body)}
            }catch(e:Exception){val b=e.toString().toByteArray();x.sendResponseHeaders(500,b.size.toLong());x.responseBody.use {it.write(b)}}finally{x.close()}
        }
        http.start()
        watcher=Thread({
            val seen=mutableSetOf<Path>()
            while(running) {
                Files.list(impex).use {files -> files.filter {it.toString().endsWith(".impex")}.forEach {file ->
                    val body=runCatching {Files.readString(file)}.getOrNull()
                    val version=when(body){script("v1")->"v1";script("v2")->"v2";else->null}
                    // Ignore partial writes, just as the complete native import must finish first.
                    if(run.isNotEmpty()&&version!=null&&seen.add(file)) {
                        v2=version=="v2";if(v2)edited=true
                        if(mode!="missing-import-terminal")emit("OK","ImpEx imported: ${if(mode=="foreign-filename-terminal")"foreign.impex" else file.fileName}")
                    }
                }}
                Thread.sleep(5)
            }
        },"script-contract-watcher").apply {isDaemon=true;start()}
    }
    val baseUrl get()="http://127.0.0.1:${http.address.port}"
    @Synchronized private fun emit(level:String,message:String){writer.println(batchJson.writeValueAsString(mapOf("level" to level,"message" to message,"timestamp" to "contract")))}
    override fun close(){running=false;watcher.join(1000);http.stop(0);connection?.close();socket.close();Files.deleteIfExists(portFile)}
}

fun runStoredScriptHarnessContracts():Int {
    val modes=listOf("normal","stale-fresh-v2","stale-restored","wrong-run","wrong-binding","changed-control",
        "missing-import-terminal","foreign-filename-terminal","cleanup-residue")
    for(mode in modes) {
        val root=Files.createTempDirectory("reclazz-script-harness-")
        val relative="web/src/com/onurkat/reclazztest/controllers/StoredScriptProbeController.java"
        val baseline=StoredScriptLiveTest::class.java.classLoader.getResourceAsStream("templates/batch/$relative")!!.use {it.readBytes()}
        val source=root.resolve(relative);Files.createDirectories(source.parent);Files.write(source,baseline)
        try {StoredScriptContractServer(root,mode).use {server ->
            val client=AgentEventClient(server.portFile.toString());client.connect()
            try {
                val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while(client.getEvents().none {it.level=="CONNECTED"}) {check(System.nanoTime()<until);Thread.sleep(5)}
                val config=TestConfig(server.baseUrl,root.toString(),server.portFile.toString(),eventTimeoutMs=450)
                val result=StoredScriptLiveTest(config,client,HttpVerifier(server.baseUrl,1000)).run()
                check(result.status==if(mode=="normal")TestStatus.PASS else TestStatus.FAIL){"$mode: $result"}
                val failure=when(mode) {
                    "stale-fresh-v2","stale-restored" -> "Stale fresh script result"
                    "wrong-run" -> "Wrong script run"
                    "wrong-binding" -> "Wrong script binding"
                    "changed-control" -> "Independent script control changed"
                    "missing-import-terminal","foreign-filename-terminal" -> "Missing exact script import terminal"
                    "cleanup-residue" -> "Owned cleanup did not prove zero"
                    else -> ""
                }
                check(failure in result.notes){"$mode wrong failure: $result"}
                check(server.admitted&&server.cleaned){"$mode skipped admitted cleanup"}
                check(Files.readAllBytes(source).contentEquals(baseline))
                Files.list(root.resolve("resources/impex")).use {check(it.findAny().isEmpty){"$mode import file leak"}}
                println("STORED_SCRIPT_HARNESS PASS $mode")
            }finally{client.disconnect()}
        }}finally{root.toFile().deleteRecursively()}
    }
    return modes.size
}

fun main(){println("STORED_SCRIPT_CONTRACT: ${runStoredScriptHarnessContracts()} cases, 0 failures")}
