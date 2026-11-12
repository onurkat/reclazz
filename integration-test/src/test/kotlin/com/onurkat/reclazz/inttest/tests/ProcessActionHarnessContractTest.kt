package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestStatus
import com.sun.net.httpserver.HttpServer
import java.net.*
import java.nio.file.*
import java.util.concurrent.TimeUnit

private class ProcessActionContractServer(private val source:Path,private val mode:String):AutoCloseable {
    private val socket=ServerSocket(0,1,InetAddress.getLoopbackAddress())
    val portFile=Files.createTempFile("reclazz-action-contract-",".port").also {Files.writeString(it,socket.localPort.toString())}
    private var connection:Socket?=null
    private lateinit var writer:java.io.PrintWriter
    private val http=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    @Volatile private var watching=true
    @Volatile private var v2=false
    @Volatile private var edited=false
    private val runs=linkedMapOf<String,Int>()
    private val released=mutableSetOf<String>()
    val cleaned=mutableSetOf<String>()
    val started get()=runs.keys.toSet()
    private val watcher:Thread
    private fun read(nonce:String):Map<String,Any> {
        val ordinal=runs.getValue(nonce);val done=nonce in released
        val result=when {
            !done -> ""
            ordinal==2 && mode=="stale-A" -> "action-v1"
            ordinal==3 && v2 && mode=="stale-B" -> "action-v1"
            edited && !v2 && mode=="stale-C" -> "action-v2"
            v2 -> "action-v2"
            else -> "action-v1"
        }
        return mapOf("nonce" to nonce,"code" to "reclazz-action-$nonce",
            "pk" to if(ordinal==2&&v2&&mode=="changed-identity")"foreign" else "pk-$ordinal",
            "version" to if(ordinal==2&&v2&&mode=="changed-version")"other-version" else "graph-v1",
            "state" to if(done)"SUCCEEDED" else "RUNNING", "result" to result,
            "actions" to if(done)emptyList<String>() else listOf(if(mode=="not-waiting")"foreign" else "held"))
    }
    init {
        Thread({connection=socket.accept();writer=java.io.PrintWriter(connection!!.getOutputStream(),true);emit("CONNECTED","action-contract");receipt()},"action-contract-accept").apply {isDaemon=true;start()}
        http.createContext("/") {x ->
            try {
                val query=if(x.requestMethod=="POST")x.requestBody.readBytes().toString(Charsets.UTF_8) else x.requestURI.rawQuery
                val params=query.split('&').associate {it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='),Charsets.UTF_8)}
                val nonce=params.getValue("nonce")
                val response=when(x.requestURI.path.substringAfterLast('/')) {
                    "start" -> {runs[nonce]=runs.size+1;read(nonce)}
                    "read" -> read(nonce)
                    "release" -> {released+=nonce;mapOf("nonce" to nonce,"released" to true)}
                    "cleanup" -> {cleaned+=nonce;mapOf("nonce" to nonce,"ownedRemaining" to if(mode=="cleanup-residue")1 else 0)}
                    else -> error("Unexpected endpoint")
                }
                val bytes=batchJson.writeValueAsBytes(response);x.sendResponseHeaders(200,bytes.size.toLong());x.responseBody.use {it.write(bytes)}
            }catch(e:Exception){x.sendResponseHeaders(500,-1);x.close()}
        };http.start()
        watcher=Thread({
            val baseline=Files.readAllBytes(source)
            val changed=replaceOnce(baseline.toString(Charsets.UTF_8),"action-v1","action-v2").toByteArray()
            var previous=baseline
            while(watching) {
                val next=Files.readAllBytes(source)
                // A partial filesystem read is not a saved source revision or a receipt.
                if(!next.contentEquals(baseline) && !next.contentEquals(changed)) {Thread.sleep(5);continue}
                if(!next.contentEquals(previous)) {
                    previous=next;v2=next.toString(Charsets.UTF_8).contains("action-v2");if(v2)edited=true
                    when {
                        v2&&mode=="missing-receipt" -> Unit
                        !v2&&mode=="restore-receipt" -> Unit
                        v2&&mode=="wrong-owner" -> {emit("COMPILE","Compiled ProbeProcessAction.java (1ms)");emit("RELOAD","Reloaded com.example.Foreign (1ms)")}
                        v2&&mode=="reversed" -> {emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.ProbeProcessAction (1ms)");emit("COMPILE","Compiled ProbeProcessAction.java (1ms)")}
                        else -> receipt()
                    }
                }
                Thread.sleep(5)
            }
        },"action-contract-watcher").apply {isDaemon=true;start()}
    }
    val baseUrl get()="http://127.0.0.1:${http.address.port}"
    @Synchronized private fun emit(level:String,message:String) {writer.println(batchJson.writeValueAsString(mapOf("level" to level,"message" to message,"timestamp" to "contract")))}
    private fun receipt(){emit("COMPILE","Compiled ProbeProcessAction.java (1ms)");emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.ProbeProcessAction (1ms)")}
    override fun close(){watching=false;watcher.join(1000);http.stop(0);connection?.close();socket.close();Files.deleteIfExists(portFile)}
}

fun runProcessActionHarnessContracts():Int {
    val modes=listOf("normal","stale-A","stale-B","stale-C","not-waiting","changed-identity","changed-version",
        "missing-receipt","wrong-owner","reversed","restore-receipt","cleanup-residue")
    for(mode in modes) {
        val root=Files.createTempDirectory("reclazz-action-harness-")
        val relative="src/com/onurkat/reclazztest/batch/ProbeProcessAction.java";val file=root.resolve(relative)
        val baseline=ProcessActionLiveTest::class.java.classLoader.getResourceAsStream("templates/batch/$relative")!!.use {it.readBytes()}
        Files.createDirectories(file.parent);Files.write(file,baseline)
        try {ProcessActionContractServer(file,mode).use {server ->
            val client=AgentEventClient(server.portFile.toString());client.connect()
            try {
                val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while(client.getEvents().none {it.level=="RELOAD"}) {check(System.nanoTime()<until);Thread.sleep(5)}
                val config=TestConfig(server.baseUrl,root.toString(),server.portFile.toString(),eventTimeoutMs=450)
                val result=ProcessActionLiveTest(config,client,HttpVerifier(server.baseUrl,1000)).run()
                check(result.status==if(mode=="normal")TestStatus.PASS else TestStatus.FAIL){"$mode: $result"}
                check(server.started==server.cleaned && server.started.isNotEmpty()) {"$mode skipped owned cleanup: ${server.started-server.cleaned}"}
                check(Files.readAllBytes(file).contentEquals(baseline)){"$mode leaked source"}
                if(mode in setOf("missing-receipt","wrong-owner","reversed","restore-receipt"))check("Timeout waiting for COMPILE then RELOAD" in result.notes){"$mode wrong receipt failure: $result"}
                if(mode.startsWith("stale-"))check("Stale persisted action result" in result.notes){"$mode wrong result failure: $result"}
                println("PROCESS_ACTION_HARNESS PASS $mode")
            }finally {client.disconnect()}
        }}finally {root.toFile().deleteRecursively()}
    }
    return modes.size
}
