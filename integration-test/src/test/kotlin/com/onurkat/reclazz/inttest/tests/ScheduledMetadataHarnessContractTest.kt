package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestStatus
import com.sun.net.httpserver.HttpServer
import java.net.*
import java.nio.file.*
import java.util.concurrent.TimeUnit

private class ScheduledMetadataServer(private val source:Path,private val mode:String):AutoCloseable {
    private val socket=ServerSocket(0,1,InetAddress.getLoopbackAddress())
    val portFile=Files.createTempFile("reclazz-scheduled-contract-",".port").also {Files.writeString(it,socket.localPort.toString())}
    private var connection:Socket?=null
    private lateinit var writer:java.io.PrintWriter
    private val http=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    @Volatile private var watching=true
    @Volatile private var v2=false
    @Volatile private var edited=false
    var cleaned=false;private var phase=0
    private val watcher:Thread
    private fun data(nonce:String,sample:Boolean):MutableMap<String,Any> {
        val delay=if(v2&&mode!="stale-delay")300L else 1000L
        val step=if(mode=="stale-cadence"&&phase==2)1000L else delay
        val mark=phase*10_000_000_000L+1
        val ticks=if(phase==0||!sample||mode=="no-ticks")emptyList() else (1..4).map {i ->
            mapOf("sequence" to if(mode=="repeated-sequence"&&i==4)1L else (phase-1)*4L+i,
                "nano" to if(mode=="reversed-time"&&i==4)mark else mark+i*step*1_000_000,
                "receiver" to if(mode=="retired-tick"&&i==4)999L else phase.toLong(),"phase" to phase)
        }
        val tasks=listOf(mapOf("method" to if(mode=="wrong-method")"com.example.Foreign.tick" else "com.onurkat.reclazztest.services.ProbeScheduledMetadataJob.tick",
            "fixedDelay" to true,"sameReceiver" to (mode!="wrong-receiver"),"intervalMs" to delay))
        val result=linkedMapOf<String,Any>("nonce" to if(mode=="wrong-nonce")"foreign" else nonce,"active" to true,
            "phase" to if(mode=="stale-phase"&&sample&&phase>0)phase-1 else phase,
            "total" to if(mode=="counter-reversed"&&phase==2)0L else if(sample)phase*4L else (phase-1).coerceAtLeast(0)*4L,
            "windowStart" to if(mode=="stale-window"&&sample&&phase>0)mark-1 else mark,
            "receiver" to phase.toLong(),"samples" to ticks,"ownerContext" to "owned-web",
            "contextIdentity" to if(mode=="changed-context"&&phase>=2)99 else 11,
            "ownedTasks" to if(mode=="duplicate-registration")2 else 1,
            "tasks" to if(mode=="duplicate-registration")tasks+tasks else tasks)
        if(mode=="missing-samples")result.remove("samples")
        return result
    }
    init {
        Thread({connection=socket.accept();writer=java.io.PrintWriter(connection!!.getOutputStream(),true);emit("CONNECTED","scheduled-contract");receipt()},"scheduled-contract-accept").apply {isDaemon=true;start()}
        http.createContext("/") {x ->
            try {
                val query=if(x.requestMethod=="POST")x.requestBody.readBytes().toString(Charsets.UTF_8) else x.requestURI.rawQuery
                val params=query.split('&').associate {it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='),Charsets.UTF_8)}
                val nonce=params.getValue("nonce")
                val result=when(x.requestURI.path.substringAfterLast('/')) {
                    "begin" -> data(nonce,false)
                    "phase" -> {phase=params.getValue("phase").toInt();data(nonce,false)}
                    "read" -> data(nonce,true)
                    "cleanup" -> {cleaned=true;data(nonce,false).apply {
                        this["nonce"]=nonce;this["active"]=false;this["samples"]=if(mode=="recorder-residue")1 else 0
                        this["retainedReceivers"]=0;this["total"]=0L
                    }}
                    else -> error("Unexpected endpoint")
                }
                val bytes=batchJson.writeValueAsBytes(result);x.sendResponseHeaders(200,bytes.size.toLong());x.responseBody.use {it.write(bytes)}
            }catch(e:Exception){x.sendResponseHeaders(500,-1);x.close()}
        };http.start()
        watcher=Thread({
            var previous=Files.readAllBytes(source)
            while(watching) {
                val next=Files.readAllBytes(source)
                if(!next.contentEquals(previous)) {
                    previous=next;val nextV2=next.toString(Charsets.UTF_8).contains("fixedDelay = 300")
                    v2=nextV2||(mode=="restore-stale"&&edited);if(nextV2)edited=true
                    when {
                        nextV2&&mode in setOf("missing-receipt","stale-receipt") -> Unit
                        !nextV2&&mode=="restore-receipt" -> Unit
                        nextV2&&mode=="wrong-owner" -> {emit("COMPILE","Compiled ProbeScheduledMetadataJob.java (1ms)");emit("RELOAD","Reloaded com.example.Foreign (1ms)")}
                        nextV2&&mode=="reversed" -> {emit("RELOAD","Reloaded com.onurkat.reclazztest.services.ProbeScheduledMetadataJob (1ms)");emit("COMPILE","Compiled ProbeScheduledMetadataJob.java (1ms)")}
                        else -> receipt()
                    }
                }
                Thread.sleep(5)
            }
        },"scheduled-contract-watcher").apply {isDaemon=true;start()}
    }
    val baseUrl get()="http://127.0.0.1:${http.address.port}"
    @Synchronized private fun emit(level:String,message:String) {writer.println(batchJson.writeValueAsString(mapOf("level" to level,"message" to message,"timestamp" to "contract")))}
    private fun receipt(){emit("COMPILE","Compiled ProbeScheduledMetadataJob.java (1ms)");emit("RELOAD","Reloaded com.onurkat.reclazztest.services.ProbeScheduledMetadataJob (1ms)")}
    override fun close(){watching=false;watcher.join(1000);http.stop(0);connection?.close();socket.close();Files.deleteIfExists(portFile)}
}

fun runScheduledMetadataHarnessContracts():Int {
    val modes=listOf("normal","stale-delay","stale-cadence","no-ticks","duplicate-registration","wrong-method","wrong-receiver","retired-tick",
        "stale-phase","wrong-nonce","repeated-sequence","reversed-time","missing-samples","counter-reversed","stale-window","changed-context",
        "missing-receipt","stale-receipt","wrong-owner","reversed","restore-receipt","restore-stale","recorder-residue")
    for(mode in modes) {
        val root=Files.createTempDirectory("reclazz-scheduled-harness-")
        val relative="src/com/onurkat/reclazztest/services/ProbeScheduledMetadataJob.java";val file=root.resolve(relative)
        val baseline=ScheduledMetadataLiveTest::class.java.classLoader.getResourceAsStream("templates/batch/$relative")!!.use {it.readBytes()}
        Files.createDirectories(file.parent);Files.write(file,baseline)
        try {ScheduledMetadataServer(file,mode).use {server ->
            val client=AgentEventClient(server.portFile.toString());client.connect()
            try {
                val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while(client.getEvents().none {it.level=="RELOAD"}) {check(System.nanoTime()<until);Thread.sleep(5)}
                val config=TestConfig(server.baseUrl,root.toString(),server.portFile.toString(),eventTimeoutMs=350)
                val result=ScheduledMetadataLiveTest(config,client,HttpVerifier(server.baseUrl,1000)).run()
                check(result.status==if(mode=="normal")TestStatus.PASS else TestStatus.FAIL){"$mode: $result"}
                check(server.cleaned){"$mode skipped cleanup"};check(Files.readAllBytes(file).contentEquals(baseline)){"$mode leaked source"}
                if(mode in setOf("missing-receipt","stale-receipt","wrong-owner","reversed","restore-receipt"))check("Timeout waiting for COMPILE then RELOAD" in result.notes){"$mode wrong receipt failure: $result"}
                println("SCHEDULED_METADATA_HARNESS PASS $mode")
            }finally {client.disconnect()}
        }}finally {root.toFile().deleteRecursively()}
    }
    return modes.size
}
