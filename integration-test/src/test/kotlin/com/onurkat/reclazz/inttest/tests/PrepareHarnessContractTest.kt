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
private class PrepareContractServer(private val source:Path,private val mode:String):AutoCloseable {
    private val socket=ServerSocket(0,1,InetAddress.getLoopbackAddress())
    val portFile=Files.createTempFile("reclazz-prepare-contract-",".port").also {Files.writeString(it,socket.localPort.toString())}
    private var connection:java.net.Socket?=null
    private lateinit var writer:java.io.PrintWriter
    private val http=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    @Volatile private var watching=true
    @Volatile var rows=0
    @Volatile var cleaned=false
    private var nonce=""
    private val steps=linkedMapOf<String,Map<String,Any>>()
    private val baseline=Files.readAllBytes(source)
    init {
        Thread({connection=socket.accept();writer=java.io.PrintWriter(connection!!.getOutputStream(),true);emit("CONNECTED","prepare-contract")},
            "prepare-contract-accept").apply {isDaemon=true;start()}
        http.createContext("/") {exchange ->
            try {
                val form=if(exchange.requestMethod=="POST") exchange.requestBody.readBytes().toString(Charsets.UTF_8) else exchange.requestURI.rawQuery
                val params=form.split('&').associate {part -> part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='),Charsets.UTF_8)}
                val run=params.getValue("run");check(run.matches(Regex("^reclazz-prep-[0-9a-f-]{36}$")))
                val version=if(Files.readString(source).contains("return \"v2\";")) "v2" else "v1"
                var status=200
                val ready=mapOf("run" to if(mode=="wrong-run") "wrong" else run,"version" to version,
                    "sameRegistry" to (mode!="foreign-registry"),"registryClass" to "de.hybris.platform.servicelayer.interceptor.impl.DefaultInterceptorRegistry",
                    "registryIdentity" to if(mode=="replaced-registry" && version=="v2") 99 else 11,
                    "targetCount" to when(mode) {"missing-target"->0;"duplicate-target"->2;else->1},"mappingMatches" to true)
                val result:Map<String,Any> = when(exchange.requestURI.path.substringAfterLast('/')) {
                    "guard" -> mapOf("run" to run,"nonces" to listOf("reclazz-dao-${run.removePrefix("reclazz-prep-")}-a-1",
                        "reclazz-dyn-${run.removePrefix("reclazz-prep-")}-row","reclazz-schema-${run.removePrefix("reclazz-prep-")}"),"recorderRemaining" to 0)
                    "ready" -> ready
                    "seed" -> {rows=if(mode=="partial-seed") 1 else 2;nonce="$run-seed";if(mode=="partial-seed") status=500;mapOf("run" to run)}
                    "save" -> {
                        val step=params.getValue("step");val applied=if(mode=="stale-value" && step=="2") "v1" else version
                        nonce="$run-s$step-prepared-$applied"
                        steps[step]=mapOf("calls" to when(mode) {"zero-calls"->0;"duplicate-calls"->2;else->1},"version" to version)
                        mapOf("run" to run,"step" to step.toInt(),"pk" to params.getValue("pk"))
                    }
                    "read" -> ready+mapOf("rows" to listOf(
                        mapOf("pk" to if(mode=="wrong-pk" && steps.isNotEmpty()) "different" else "target-pk","nonce" to nonce),
                        mapOf("pk" to "control-pk","nonce" to if(mode=="mutated-control") "$run-mutated" else "$run-control")),
                        "steps" to steps.toMap(),"globalRemaining" to rows)
                    "cleanup" -> {val removed=rows;rows=if(mode=="leftover") 1 else 0;steps.clear();cleaned=true;mapOf("run" to run,"removed" to removed)}
                    else -> mapOf("run" to run,"ownedRemaining" to rows,"globalRemaining" to rows,"recorderRemaining" to if(steps.isEmpty()) 0 else 1,"steps" to steps.toMap())
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
                        "wrong-owner" -> {emit("COMPILE","Compiled ProbePrepareInterceptor.java (1ms)");emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.OtherPrepareInterceptor (1ms)")}
                        "reversed" -> {emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.ProbePrepareInterceptor (1ms)");emit("COMPILE","Compiled ProbePrepareInterceptor.java (1ms)")}
                        else -> receipt()
                    } else receipt()
                }
                Thread.sleep(5)
            }
        },"prepare-contract-watcher").apply {isDaemon=true;start()}
    }
    val baseUrl get()="http://127.0.0.1:${http.address.port}"
    @Synchronized fun emit(level:String,message:String) {writer.println(batchJson.writeValueAsString(mapOf("level" to level,"message" to message,"timestamp" to "contract")))}
    fun receipt() {emit("COMPILE","Compiled ProbePrepareInterceptor.java (1ms)");emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.ProbePrepareInterceptor (1ms)")}
    override fun close() {watching=false;http.stop(0);connection?.close();socket.close();Files.deleteIfExists(portFile)}
}

fun runPrepareHarnessContracts():Int {
    var cases=0
    for(mode in listOf("normal","stale-receipt","wrong-owner","reversed","withheld","stale-value","wrong-run","wrong-pk",
        "mutated-control","zero-calls","duplicate-calls","foreign-registry","replaced-registry","missing-target","duplicate-target","leftover","partial-seed")) {
        val root=Files.createTempDirectory("reclazz-prepare-harness-")
        val relative="src/com/onurkat/reclazztest/batch/ProbePrepareInterceptor.java"
        val baseline=PrepareInterceptorLiveTest::class.java.classLoader.getResourceAsStream("templates/batch/$relative")!!.use {it.readBytes()}
        val file=root.resolve(relative);Files.createDirectories(file.parent);Files.write(file,baseline)
        val copy=Files.createTempFile("reclazz-prepare-withheld-",".java")
        try {PrepareContractServer(file,mode).use {server ->
            val client=AgentEventClient(server.portFile.toString());client.connect()
            try {
                server.receipt()
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while(client.getEvents().none {it.level=="RELOAD"}) {check(System.nanoTime()<deadline);Thread.sleep(5)}
                val config=TestConfig(server.baseUrl,root.toString(),server.portFile.toString(),eventTimeoutMs=450)
                val result=CompletableFuture.supplyAsync {
                    PrepareInterceptorLiveTest(config,client,HttpVerifier(server.baseUrl,1000),if(mode=="withheld") copy else null).run()
                }.get(8,TimeUnit.SECONDS)
                check(result.status==if(mode=="normal") TestStatus.PASS else TestStatus.FAIL) {"$mode: $result"}
                check(server.cleaned && Files.readAllBytes(file).contentEquals(baseline)) {"$mode missed cleanup/restore: $result"}
                check(server.rows==if(mode=="leftover") 1 else 0) {"$mode rows remained"}
                if(mode in setOf("stale-receipt","wrong-owner","reversed","withheld"))
                    check("Timeout waiting for COMPILE then RELOAD" in result.notes) {"$mode: $result"}
                cases++;println("PREPARE_HARNESS PASS $mode")
            } finally {client.disconnect()}
        }} finally {root.toFile().deleteRecursively();Files.deleteIfExists(copy)}
    }
    return cases
}
