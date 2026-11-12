package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestStatus
import com.sun.net.httpserver.HttpServer
import java.net.*
import java.nio.file.*
import java.util.concurrent.TimeUnit

private class TriggerContractServer(private val source:Path,private val mode:String):AutoCloseable {
    private val socket=ServerSocket(0,1,InetAddress.getLoopbackAddress())
    val portFile=Files.createTempFile("reclazz-trigger-contract-",".port").also {Files.writeString(it,socket.localPort.toString())}
    private var connection:Socket?=null
    private lateinit var writer:java.io.PrintWriter
    private val http=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    @Volatile private var watching=true
    @Volatile private var v2=false
    @Volatile private var edited=false
    var cleaned=false;private var phase=0;private var due=0L;private var nonce=""
    private var admission=linkedMapOf<String,Any>()
    private val watcher:Thread
    init {
        Thread({connection=socket.accept();writer=java.io.PrintWriter(connection!!.getOutputStream(),true);emit("CONNECTED","trigger-contract");receipt()},"trigger-contract-accept").apply {isDaemon=true;start()}
        http.createContext("/") {x ->
            try {
                val query=if(x.requestMethod=="POST")x.requestBody.readBytes().toString(Charsets.UTF_8) else x.requestURI.rawQuery
                val params=query.split('&').associate {it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='),Charsets.UTF_8)}
                nonce=params.getValue("nonce")
                var status=200
                val response:Map<String,Any> = when(x.requestURI.path.substringAfterLast('/')) {
                    "begin" -> {
                        if(mode=="partial-begin")status=500
                        mapOf("nonce" to nonce,"code" to "reclazz-trigger-$nonce","cronPk" to "cron-owned","engineRunning" to true)
                    }
                    "arm" -> {
                        phase=params.getValue("phase").toInt();due=System.currentTimeMillis()+90
                        val activation=if(mode=="wrong-date")due+1 else due-(if(mode=="zero-offset")0L else phase*70L)
                        admission=linkedMapOf("nonce" to nonce,"phase" to phase,"cronPk" to if(mode=="wrong-cron")"foreign" else "cron-owned",
                            "triggerPk" to "trigger-$phase","taskPk" to if(mode=="duplicate-task")"same-task" else "task-$phase",
                            "taskTriggerPk" to if(mode=="wrong-trigger")"foreign" else "trigger-$phase",
                            "runner" to if(mode=="wrong-runner")"foreign" else "triggerTaskRunner",
                            "activation" to activation,"executionDate" to due,
                            "admittedAt" to if(mode=="late-admission")due else System.currentTimeMillis(),
                            "offsetMs" to due-activation+(if(mode=="wrong-offset")1L else 0L))
                        if(mode=="missing-admission")admission.remove("admittedAt")
                        if(mode=="no-task")admission.remove("taskPk")
                        admission
                    }
                    "read" -> {
                        val fired=System.currentTimeMillis()>=due&&mode!="missing-fire"
                        val n=LinkedHashMap(admission)
                        n.putAll(mapOf("version" to if(v2&&mode!="stale-version")"job-v2" else "job-v1",
                            "calls" to if(fired)phase+(if(mode=="duplicate-fire")1 else 0) else phase-1,
                            "firedAt" to if(mode=="early-fire")due-1 else due,"thread" to "native-worker",
                            "status" to if(mode=="not-finished")"RUNNING" else "FINISHED",
                            "result" to if(mode=="not-success")"FAILURE" else "SUCCESS"))
                        if(mode=="changed-task")n["taskPk"]="foreign"
                        n
                    }
                    "wake" -> mapOf("nonce" to nonce,"phase" to phase,"woke" to true)
                    "cleanup" -> {cleaned=true;mapOf("nonce" to nonce,"ownedRemaining" to if(mode=="residue")1L else 0L,
                        "calls" to if(mode=="probe-residue")1 else 0,"firedAt" to 0L)}
                    else -> error("Unexpected endpoint")
                }
                val bytes=batchJson.writeValueAsBytes(response);x.sendResponseHeaders(status,bytes.size.toLong());x.responseBody.use {it.write(bytes)}
            }catch(e:Exception){x.sendResponseHeaders(500,-1);x.close()}
        };http.start()
        watcher=Thread({
            var previous=Files.readAllBytes(source)
            while(watching) {
                val next=Files.readAllBytes(source)
                if(!next.contentEquals(previous)) {
                    previous=next;val nextV2=next.toString(Charsets.UTF_8).contains("job-v2")
                    v2=nextV2||(mode=="restore-stale"&&edited);if(nextV2)edited=true
                    when {
                        nextV2&&mode in setOf("missing-receipt","stale-receipt") -> Unit
                        !nextV2&&mode=="restore-receipt" -> Unit
                        nextV2&&mode=="wrong-owner" -> {emit("COMPILE","Compiled NativeProbeJob.java (1ms)");emit("RELOAD","Reloaded com.example.Foreign (1ms)")}
                        nextV2&&mode=="reversed" -> {emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.NativeProbeJob (1ms)");emit("COMPILE","Compiled NativeProbeJob.java (1ms)")}
                        else -> receipt()
                    }
                }
                Thread.sleep(5)
            }
        },"trigger-contract-watcher").apply {isDaemon=true;start()}
    }
    val baseUrl get()="http://127.0.0.1:${http.address.port}"
    @Synchronized private fun emit(level:String,message:String) {writer.println(batchJson.writeValueAsString(mapOf("level" to level,"message" to message,"timestamp" to "contract")))}
    private fun receipt(){emit("COMPILE","Compiled NativeProbeJob.java (1ms)");emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.NativeProbeJob (1ms)")}
    override fun close(){watching=false;watcher.join(1000);http.stop(0);connection?.close();socket.close();Files.deleteIfExists(portFile)}
}

fun runTriggerFiringHarnessContracts():Int {
    val modes=listOf("normal","no-task","wrong-cron","wrong-trigger","wrong-runner","wrong-date","duplicate-task","changed-task",
        "missing-fire","early-fire","duplicate-fire","stale-version","not-finished","not-success","residue","probe-residue","partial-begin",
        "missing-receipt","stale-receipt","wrong-owner","reversed","restore-receipt","restore-stale","missing-admission","late-admission","wrong-offset","zero-offset")
    for(mode in modes) {
        val root=Files.createTempDirectory("reclazz-trigger-harness-")
        val relative="src/com/onurkat/reclazztest/batch/NativeProbeJob.java";val file=root.resolve(relative)
        val baseline=TriggerFiringLiveTest::class.java.classLoader.getResourceAsStream("templates/batch/$relative")!!.use {it.readBytes()}
        Files.createDirectories(file.parent);Files.write(file,baseline)
        try {TriggerContractServer(file,mode).use {server ->
            val client=AgentEventClient(server.portFile.toString());client.connect()
            try {
                val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while(client.getEvents().none {it.level=="RELOAD"}) {check(System.nanoTime()<until);Thread.sleep(5)}
                val config=TestConfig(server.baseUrl,root.toString(),server.portFile.toString(),eventTimeoutMs=350)
                val result=TriggerFiringLiveTest(config,client,HttpVerifier(server.baseUrl,1000)).run()
                check(result.status==if(mode in setOf("normal","zero-offset"))TestStatus.PASS else TestStatus.FAIL){"$mode: $result"}
                check(server.cleaned){"$mode skipped cleanup"}
                check(Files.readAllBytes(file).contentEquals(baseline)){"$mode leaked source"}
                if(mode in setOf("missing-receipt","stale-receipt","wrong-owner","reversed","restore-receipt"))check("Timeout waiting for COMPILE then RELOAD" in result.notes){"$mode wrong receipt failure: $result"}
                println("TRIGGER_HARNESS PASS $mode")
            }finally {client.disconnect()}
        }}finally {root.toFile().deleteRecursively()}
    }
    return modes.size
}
