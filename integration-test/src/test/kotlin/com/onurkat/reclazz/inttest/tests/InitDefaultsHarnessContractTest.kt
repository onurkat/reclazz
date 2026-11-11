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
private class InitDefaultsContractServer(private val source:Path,private val mode:String):AutoCloseable {
    private val socket=ServerSocket(0,1,InetAddress.getLoopbackAddress())
    val portFile=Files.createTempFile("reclazz-init-defaults-contract-",".port").also {Files.writeString(it,socket.localPort.toString())}
    private var connection:java.net.Socket?=null
    private lateinit var writer:java.io.PrintWriter
    private val http=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    @Volatile private var watching=true
    @Volatile var rows=0
    @Volatile var cleaned=false
    private val held=linkedMapOf<Int,String>()
    private val saved=linkedMapOf<Int,String>()
    private val steps=linkedMapOf<String,Map<String,Any>>()
    private val baseline=Files.readAllBytes(source)
    init {
        Thread({connection=socket.accept();writer=java.io.PrintWriter(connection!!.getOutputStream(),true);emit("CONNECTED","init-defaults-contract")},
            "init-defaults-contract-accept").apply {isDaemon=true;start()}
        http.createContext("/") {exchange ->
            try {
                val form=if(exchange.requestMethod=="POST") exchange.requestBody.readBytes().toString(Charsets.UTF_8) else exchange.requestURI.rawQuery
                val params=form.split('&').associate {part -> part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='),Charsets.UTF_8)}
                val run=params.getValue("run");check(run.matches(Regex("^reclazz-init-[0-9a-f-]{36}$")))
                val version=if(Files.readString(source).contains("return \"v2\";")) "v2" else "v1"
                var status=200
                val ready=mapOf("run" to if(mode=="wrong-run") "wrong" else run,"version" to version,
                    "sameRegistry" to (mode!="foreign-registry"),"registryClass" to "de.hybris.platform.servicelayer.interceptor.impl.DefaultInterceptorRegistry",
                    "registryIdentity" to if(mode=="replaced-registry" && version=="v2") 99 else 11,
                    "targetCount" to when(mode) {"missing-target"->0;"duplicate-target"->2;else->1},"mappingMatches" to true,
                    "heldCount" to held.size,"scopeCount" to 0)
                fun proof(step:Int):Map<String,Any> = mapOf("run" to run,"step" to step,
                    "nonce" to if(mode=="old-changed" && step==0 && version=="v2") "$run-s0-default-v2" else held.getValue(step),
                    "isNew" to (step !in saved),"identity" to if(mode=="wrong-identity" && step==0 && version=="v2") 999 else step+100,
                    "pk" to if(step in saved) "pk-$step" else "","steps" to steps.toMap())
                val result:Map<String,Any> = when(exchange.requestURI.path.substringAfterLast('/')) {
                    "guard" -> mapOf("run" to run,"nonces" to listOf("reclazz-dao-${run.removePrefix("reclazz-init-")}-a-1",
                        "reclazz-dyn-${run.removePrefix("reclazz-init-")}-row","reclazz-schema-${run.removePrefix("reclazz-init-")}",
                        "reclazz-prep-${run.removePrefix("reclazz-init-")}-seed"),"recorderRemaining" to if(mode=="guard-changed") 1 else 0)
                    "ready" -> ready
                    "create" -> {
                        val step=params.getValue("step").toInt()
                        val value=if(mode=="stale-value" && step==2) "v1" else version
                        held[step]=if(mode=="save-only") "" else "$run-s$step-default-$value"
                        steps[step.toString()]=mapOf("calls" to when(mode) {"zero-calls"->0;"duplicate-calls"->2;else->1},"version" to version)
                        if(mode=="partial-create") status=500
                        proof(step)
                    }
                    "held" -> proof(params.getValue("step").toInt())
                    "save" -> {
                        val step=params.getValue("step").toInt();saved[step]=held.getValue(step);rows=saved.size
                        if(mode=="save-callback") steps[step.toString()]=mapOf("calls" to 2,"version" to version)
                        if(mode=="partial-save") status=500
                        proof(step)
                    }
                    "read" -> {
                        val step=params.getValue("step").toInt()
                        mapOf("run" to run,"step" to step,"pk" to if(mode=="wrong-pk") "other" else "pk-$step",
                            "nonce" to if(mode=="persisted-stale" && step==2) "$run-s2-default-v1" else saved.getValue(step),
                            "detachedRead" to (mode!="same-object"),"steps" to steps.toMap())
                    }
                    "cleanup" -> {val removed=rows;rows=if(mode=="leftover") 1 else 0;steps.clear();held.clear();saved.clear();cleaned=true;mapOf("run" to run,"removed" to removed)}
                    else -> mapOf("run" to run,"ownedRemaining" to rows,"globalRemaining" to rows,"recorderRemaining" to steps.size,
                        "heldCount" to if(mode=="held-leak" && cleaned) 1 else held.size,"scopeCount" to if(mode=="scope-leak") 1 else 0,"steps" to steps.toMap())
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
                        "wrong-owner" -> {emit("COMPILE","Compiled ProbeInitDefaultsInterceptor.java (1ms)");emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.OtherInitDefaultsInterceptor (1ms)")}
                        "reversed" -> {emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.ProbeInitDefaultsInterceptor (1ms)");emit("COMPILE","Compiled ProbeInitDefaultsInterceptor.java (1ms)")}
                        else -> receipt()
                    } else receipt()
                }
                Thread.sleep(5)
            }
        },"init-defaults-contract-watcher").apply {isDaemon=true;start()}
    }
    val baseUrl get()="http://127.0.0.1:${http.address.port}"
    @Synchronized fun emit(level:String,message:String) {writer.println(batchJson.writeValueAsString(mapOf("level" to level,"message" to message,"timestamp" to "contract")))}
    fun receipt() {emit("COMPILE","Compiled ProbeInitDefaultsInterceptor.java (1ms)");emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.ProbeInitDefaultsInterceptor (1ms)")}
    override fun close() {watching=false;http.stop(0);connection?.close();socket.close();Files.deleteIfExists(portFile)}
}

fun runInitDefaultsHarnessContracts():Int {
    var cases=0
    for(mode in listOf("normal","stale-receipt","wrong-owner","reversed","withheld","stale-value","wrong-run","wrong-pk",
        "zero-calls","duplicate-calls","foreign-registry","replaced-registry","missing-target","duplicate-target","leftover",
        "partial-create","partial-save","save-only","save-callback","old-changed","wrong-identity","persisted-stale","same-object",
        "held-leak","scope-leak","guard-changed")) {
        val root=Files.createTempDirectory("reclazz-init-defaults-harness-")
        val relative="src/com/onurkat/reclazztest/batch/ProbeInitDefaultsInterceptor.java"
        val baseline=InitDefaultsInterceptorLiveTest::class.java.classLoader.getResourceAsStream("templates/batch/$relative")!!.use {it.readBytes()}
        val file=root.resolve(relative);Files.createDirectories(file.parent);Files.write(file,baseline)
        val copy=Files.createTempFile("reclazz-init-defaults-withheld-",".java")
        try {InitDefaultsContractServer(file,mode).use {server ->
            val client=AgentEventClient(server.portFile.toString());client.connect()
            try {
                server.receipt()
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while(client.getEvents().none {it.level=="RELOAD"}) {check(System.nanoTime()<deadline);Thread.sleep(5)}
                val config=TestConfig(server.baseUrl,root.toString(),server.portFile.toString(),eventTimeoutMs=450)
                val result=CompletableFuture.supplyAsync {
                    InitDefaultsInterceptorLiveTest(config,client,HttpVerifier(server.baseUrl,1000),if(mode=="withheld") copy else null).run()
                }.get(8,TimeUnit.SECONDS)
                check(result.status==if(mode=="normal") TestStatus.PASS else TestStatus.FAIL) {"$mode: $result"}
                check(server.cleaned && Files.readAllBytes(file).contentEquals(baseline)) {"$mode missed cleanup/restore: $result"}
                check(server.rows==if(mode=="leftover") 1 else 0) {"$mode rows remained"}
                if(mode in setOf("stale-receipt","wrong-owner","reversed","withheld"))
                    check("Timeout waiting for COMPILE then RELOAD" in result.notes) {"$mode: $result"}
                cases++;println("INIT_DEFAULTS_HARNESS PASS $mode")
            } finally {client.disconnect()}
        }} finally {root.toFile().deleteRecursively();Files.deleteIfExists(copy)}
    }
    return cases
}
