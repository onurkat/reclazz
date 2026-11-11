package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestStatus
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Drives the real Kotlin oracle, not the SAP callback implementation. */
private class RemoveContractServer(private val source:Path,private val mode:String):AutoCloseable {
    private val socket=ServerSocket(0,1,InetAddress.getLoopbackAddress())
    val portFile=Files.createTempFile("reclazz-remove-contract-",".port").also {Files.writeString(it,socket.localPort.toString())}
    private var connection:java.net.Socket?=null
    private lateinit var writer:java.io.PrintWriter
    private val http=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    @Volatile private var watching=true
    @Volatile var rows=0
    @Volatile var cleaned=false
    private var held=0
    private val steps=linkedMapOf<String,Map<String,Any>>()
    private val baseline=Files.readAllBytes(source)
    init {
        Thread({connection=socket.accept();writer=java.io.PrintWriter(connection!!.getOutputStream(),true);emit("CONNECTED","remove-contract")},
            "remove-contract-accept").apply {isDaemon=true;start()}
        http.createContext("/") {exchange ->
            try {
                val form=if(exchange.requestMethod=="POST") exchange.requestBody.readBytes().toString(Charsets.UTF_8) else exchange.requestURI.rawQuery
                val params=form.split('&').associate {part -> part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='),Charsets.UTF_8)}
                val run=params.getValue("run");check(run.matches(Regex("^reclazz-remove-[0-9a-f-]{36}$")))
                val version=if(Files.readString(source).contains("return \"v2\";")) "v2" else "v1"
                var status=200
                val ready=mapOf("run" to if(mode=="wrong-run") "wrong" else run,"version" to version,
                    "sameRegistry" to (mode!="foreign-registry"),"registryClass" to "de.hybris.platform.servicelayer.interceptor.impl.DefaultInterceptorRegistry",
                    "registryIdentity" to if(mode=="replaced-registry" && version=="v2") 99 else 11,
                    "targetCount" to when(mode) {"missing-target"->0;"duplicate-target"->2;else->1},"mappingMatches" to true,
                    "heldCount" to held,"scopeCount" to 0)
                fun proof(step:Int):Map<String,Any> = mapOf("run" to run,"step" to if(mode=="wrong-step") 9 else step,
                    "pk" to if(mode=="wrong-pk") "wrong" else "target-$step",
                    "nonce" to if(mode=="wrong-nonce") "foreign" else "$run-s$step","steps" to steps.toMap())
                val result:Map<String,Any> = when(exchange.requestURI.path.substringAfterLast('/')) {
                    "guard" -> mapOf("run" to run,"nonces" to listOf("reclazz-dao-${run.removePrefix("reclazz-remove-")}-a-1",
                        "reclazz-dyn-${run.removePrefix("reclazz-remove-")}-row","reclazz-schema-${run.removePrefix("reclazz-remove-")}",
                        "reclazz-prep-${run.removePrefix("reclazz-remove-")}-seed","reclazz-init-${run.removePrefix("reclazz-remove-")}-s0-default-v1",
                        "reclazz-load-${run.removePrefix("reclazz-remove-")}-target"),"removed" to 6,
                        "recorderRemaining" to if(mode=="guard-changed") 1 else 0)
                    "ready" -> ready
                    "seed" -> {rows=4;if(mode=="partial-seed") status=500;mapOf("run" to run,"pks" to listOf("target-0","target-1","target-2"),"controlPk" to "control-pk")}
                    "remove" -> {
                        val step=params.getValue("step").toInt()
                        val omit=params["omit"]=="true"
                        if(mode=="erased-prior") steps.clear()
                        if(!omit && mode!="absent-no-callback") {
                            val value=if(mode=="stale-value" && step==1) "v1" else version
                            steps[step.toString()]=mapOf("calls" to when(mode) {"zero-calls"->0;"duplicate-calls"->2;else->1},"version" to value)
                        }
                        held=steps.size
                        if(!omit) rows--
                        if(mode=="partial-remove" || mode=="commit-failure") status=500
                        proof(step)+mapOf("recordedSame" to (!omit && mode!="unrecorded-model"),"isRemoved" to (!omit && mode!="not-removed"))
                    }
                    "persisted" -> {val step=params.getValue("step").toInt();mapOf("run" to run,"step" to step,"pk" to "target-$step",
                        "targetRemaining" to if(mode=="still-persisted") 1 else 0,"controlRemaining" to 1,"controlPk" to "control-pk",
                        "controlNonce" to if(mode=="control-changed") "wrong" else "$run-control",
                        "recorderUnchanged" to (mode!="unscoped-callback"),"steps" to steps.toMap())}
                    "cleanup" -> {val removed=rows;rows=if(mode=="leftover") 1 else 0;steps.clear();held=0;cleaned=true;
                        mapOf("run" to run,"removed" to removed,"recorderUnchanged" to (mode!="cleanup-callback"))}
                    else -> mapOf("run" to run,"ownedRemaining" to rows,"globalRemaining" to rows,
                        "recorderRemaining" to if(mode=="recorder-leak" && cleaned) 1 else steps.size,
                        "heldCount" to if(mode=="held-leak" && cleaned) 1 else held,"scopeCount" to if(mode=="scope-leak") 1 else 0,"steps" to steps.toMap())
                }
                val bytes=batchJson.writeValueAsBytes(result);exchange.sendResponseHeaders(status,bytes.size.toLong());exchange.responseBody.use {it.write(bytes)}
            } catch(e:Exception) {exchange.sendResponseHeaders(500,-1);exchange.close()}
        };http.start()
        Thread({
            var previous=baseline
            while(watching) {
                val next=Files.readAllBytes(source)
                if(!next.contentEquals(previous)) {
                    previous=next
                    if(!next.contentEquals(baseline)) when(mode) {
                        "stale-receipt" -> Unit
                        "wrong-owner" -> {emit("COMPILE","Compiled ProbeRemoveInterceptor.java (1ms)");emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.OtherRemoveInterceptor (1ms)")}
                        "reversed" -> {emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.ProbeRemoveInterceptor (1ms)");emit("COMPILE","Compiled ProbeRemoveInterceptor.java (1ms)")}
                        else -> receipt()
                    } else if(mode!="restore-receipt") receipt()
                }
                Thread.sleep(5)
            }
        },"remove-contract-watcher").apply {isDaemon=true;start()}
    }
    val baseUrl get()="http://127.0.0.1:${http.address.port}"
    @Synchronized fun emit(level:String,message:String) {writer.println(batchJson.writeValueAsString(mapOf("level" to level,"message" to message,"timestamp" to "contract")))}
    fun receipt() {emit("COMPILE","Compiled ProbeRemoveInterceptor.java (1ms)");emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.ProbeRemoveInterceptor (1ms)")}
    override fun close() {watching=false;http.stop(0);connection?.close();socket.close();Files.deleteIfExists(portFile)}
}

fun runRemoveHarnessContracts():Int {
    var cases=0
    for(mode in listOf("normal","restore-receipt","stale-receipt","wrong-owner","reversed","withheld","omit-native","stale-value","wrong-run","wrong-pk",
        "wrong-nonce","wrong-step","zero-calls","duplicate-calls","foreign-registry","replaced-registry","missing-target","duplicate-target",
        "unrecorded-model","not-removed","erased-prior","still-persisted","absent-no-callback","control-changed","unscoped-callback",
        "guard-changed","partial-seed","partial-remove","commit-failure","cleanup-callback","leftover","held-leak","scope-leak","recorder-leak")) {
        val root=Files.createTempDirectory("reclazz-remove-harness-")
        val relative="src/com/onurkat/reclazztest/batch/ProbeRemoveInterceptor.java"
        val baseline=RemoveInterceptorLiveTest::class.java.classLoader.getResourceAsStream("templates/batch/$relative")!!.use {it.readBytes()}
        val file=root.resolve(relative);Files.createDirectories(file.parent);Files.write(file,baseline)
        val copy=Files.createTempFile("reclazz-remove-withheld-",".java")
        try {RemoveContractServer(file,mode).use {server ->
            val client=AgentEventClient(server.portFile.toString());client.connect()
            try {
                server.receipt()
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while(client.getEvents().none {it.level=="RELOAD"}) {check(System.nanoTime()<deadline);Thread.sleep(5)}
                val config=TestConfig(server.baseUrl,root.toString(),server.portFile.toString(),eventTimeoutMs=450)
                val result=CompletableFuture.supplyAsync {
                    RemoveInterceptorLiveTest(config,client,HttpVerifier(server.baseUrl,1000),if(mode=="withheld") copy else null,mode=="omit-native").run()
                }.get(8,TimeUnit.SECONDS)
                check(result.status==if(mode=="normal") TestStatus.PASS else TestStatus.FAIL) {"$mode: $result"}
                check(server.cleaned && Files.readAllBytes(file).contentEquals(baseline)) {"$mode missed cleanup/restore: $result"}
                check(server.rows==if(mode=="leftover") 1 else 0) {"$mode rows remained"}
                if(mode in setOf("stale-receipt","wrong-owner","reversed","withheld"))
                    check("Timeout waiting for COMPILE then RELOAD" in result.notes) {"$mode: $result"}
                cases++;println("REMOVE_HARNESS PASS $mode")
            } finally {client.disconnect()}
        }} finally {root.toFile().deleteRecursively();Files.deleteIfExists(copy)}
    }
    return cases
}
