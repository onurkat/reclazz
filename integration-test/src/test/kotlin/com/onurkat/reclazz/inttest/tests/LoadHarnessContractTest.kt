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
private class LoadContractServer(private val source:Path,private val mode:String):AutoCloseable {
    private val socket=ServerSocket(0,1,InetAddress.getLoopbackAddress())
    val portFile=Files.createTempFile("reclazz-load-contract-",".port").also {Files.writeString(it,socket.localPort.toString())}
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
        Thread({connection=socket.accept();writer=java.io.PrintWriter(connection!!.getOutputStream(),true);emit("CONNECTED","load-contract")},
            "load-contract-accept").apply {isDaemon=true;start()}
        http.createContext("/") {exchange ->
            try {
                val form=if(exchange.requestMethod=="POST") exchange.requestBody.readBytes().toString(Charsets.UTF_8) else exchange.requestURI.rawQuery
                val params=form.split('&').associate {part -> part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='),Charsets.UTF_8)}
                val run=params.getValue("run");check(run.matches(Regex("^reclazz-load-[0-9a-f-]{36}$")))
                val version=if(Files.readString(source).contains("return \"v2\";")) "v2" else "v1"
                var status=200
                val ready=mapOf("run" to if(mode=="wrong-run") "wrong" else run,"version" to version,
                    "sameRegistry" to (mode!="foreign-registry"),"registryClass" to "de.hybris.platform.servicelayer.interceptor.impl.DefaultInterceptorRegistry",
                    "registryIdentity" to if(mode=="replaced-registry" && version=="v2") 99 else 11,
                    "targetCount" to when(mode) {"missing-target"->0;"duplicate-target"->2;else->1},"mappingMatches" to true,
                    "heldCount" to held,"scopeCount" to 0)
                fun proof(step:Int):Map<String,Any> = mapOf("run" to run,"step" to step,
                    "pk" to if(mode=="wrong-pk") "wrong" else "target-pk",
                    "nonce" to if(mode=="wrong-nonce") "foreign" else "$run-target",
                    "isNew" to (mode=="new-model"),"steps" to steps.toMap())
                val result:Map<String,Any> = when(exchange.requestURI.path.substringAfterLast('/')) {
                    "guard" -> mapOf("run" to run,"nonces" to listOf("reclazz-dao-${run.removePrefix("reclazz-load-")}-a-1",
                        "reclazz-dyn-${run.removePrefix("reclazz-load-")}-row","reclazz-schema-${run.removePrefix("reclazz-load-")}",
                        "reclazz-prep-${run.removePrefix("reclazz-load-")}-seed","reclazz-init-${run.removePrefix("reclazz-load-")}-s0-default-v1"),
                        "recorderRemaining" to if(mode=="guard-changed") 1 else 0)
                    "ready" -> ready
                    "seed" -> {rows=2;if(mode=="partial-seed") status=500;mapOf("run" to run,"pk" to "target-pk","controlPk" to "control-pk")}
                    "load" -> {
                        val step=params.getValue("step").toInt()
                        if(params["reuse"]=="true") status=500
                        if(mode=="erased-prior") steps.clear()
                        val value=if(mode=="stale-value" && step==1) "v1" else version
                        steps[step.toString()]=mapOf("calls" to when(mode) {"zero-calls"->0;"duplicate-calls"->2;else->1},"version" to value)
                        held=steps.size
                        if(mode=="partial-load" || mode=="detach-failure") status=500
                        proof(step)+mapOf("recordedSame" to (mode!="unrecorded-model"),"freshDistinct" to (mode!="same-object"),
                            "warmSame" to (mode!="warm-different"),"warmUnchanged" to (mode!="warm-callback"))
                    }
                    "warm" -> proof(0)+mapOf("warmSame" to (mode!="old-replaced"),"warmUnchanged" to (mode!="reattach-callback"))
                    "persisted" -> mapOf("run" to run,"pk" to "target-pk","controlPk" to "control-pk",
                        "nonce" to if(mode=="persisted-changed") "wrong" else "$run-target",
                        "controlNonce" to if(mode=="control-changed") "wrong" else "$run-control",
                        "recorderUnchanged" to (mode!="unscoped-callback"),"steps" to steps.toMap())
                    "cleanup" -> {val removed=rows;rows=if(mode=="leftover") 1 else 0;steps.clear();held=0;cleaned=true;mapOf("run" to run,"removed" to removed)}
                    else -> mapOf("run" to run,"ownedRemaining" to rows,"globalRemaining" to rows,"recorderRemaining" to steps.size,
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
                        "wrong-owner" -> {emit("COMPILE","Compiled ProbeLoadInterceptor.java (1ms)");emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.OtherLoadInterceptor (1ms)")}
                        "reversed" -> {emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.ProbeLoadInterceptor (1ms)");emit("COMPILE","Compiled ProbeLoadInterceptor.java (1ms)")}
                        else -> receipt()
                    } else receipt()
                }
                Thread.sleep(5)
            }
        },"load-contract-watcher").apply {isDaemon=true;start()}
    }
    val baseUrl get()="http://127.0.0.1:${http.address.port}"
    @Synchronized fun emit(level:String,message:String) {writer.println(batchJson.writeValueAsString(mapOf("level" to level,"message" to message,"timestamp" to "contract")))}
    fun receipt() {emit("COMPILE","Compiled ProbeLoadInterceptor.java (1ms)");emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.ProbeLoadInterceptor (1ms)")}
    override fun close() {watching=false;http.stop(0);connection?.close();socket.close();Files.deleteIfExists(portFile)}
}

fun runLoadHarnessContracts():Int {
    var cases=0
    for(mode in listOf("normal","stale-receipt","wrong-owner","reversed","withheld","reuse-held","stale-value","wrong-run","wrong-pk",
        "wrong-nonce","new-model","zero-calls","duplicate-calls","foreign-registry","replaced-registry","missing-target","duplicate-target",
        "unrecorded-model","same-object","warm-different","warm-callback","old-replaced","reattach-callback","erased-prior",
        "persisted-changed","control-changed","unscoped-callback","guard-changed","partial-seed","partial-load","detach-failure","leftover","held-leak","scope-leak")) {
        val root=Files.createTempDirectory("reclazz-load-harness-")
        val relative="src/com/onurkat/reclazztest/batch/ProbeLoadInterceptor.java"
        val baseline=LoadInterceptorLiveTest::class.java.classLoader.getResourceAsStream("templates/batch/$relative")!!.use {it.readBytes()}
        val file=root.resolve(relative);Files.createDirectories(file.parent);Files.write(file,baseline)
        val copy=Files.createTempFile("reclazz-load-withheld-",".java")
        try {LoadContractServer(file,mode).use {server ->
            val client=AgentEventClient(server.portFile.toString());client.connect()
            try {
                server.receipt()
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while(client.getEvents().none {it.level=="RELOAD"}) {check(System.nanoTime()<deadline);Thread.sleep(5)}
                val config=TestConfig(server.baseUrl,root.toString(),server.portFile.toString(),eventTimeoutMs=450)
                val result=CompletableFuture.supplyAsync {
                    LoadInterceptorLiveTest(config,client,HttpVerifier(server.baseUrl,1000),if(mode=="withheld") copy else null,mode=="reuse-held").run()
                }.get(8,TimeUnit.SECONDS)
                check(result.status==if(mode=="normal") TestStatus.PASS else TestStatus.FAIL) {"$mode: $result"}
                check(server.cleaned && Files.readAllBytes(file).contentEquals(baseline)) {"$mode missed cleanup/restore: $result"}
                check(server.rows==if(mode=="leftover") 1 else 0) {"$mode rows remained"}
                if(mode in setOf("stale-receipt","wrong-owner","reversed","withheld"))
                    check("Timeout waiting for COMPILE then RELOAD" in result.notes) {"$mode: $result"}
                cases++;println("LOAD_HARNESS PASS $mode")
            } finally {client.disconnect()}
        }} finally {root.toFile().deleteRecursively();Files.deleteIfExists(copy)}
    }
    return cases
}
