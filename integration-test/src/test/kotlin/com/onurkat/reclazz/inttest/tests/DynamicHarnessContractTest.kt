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
import java.util.concurrent.TimeUnit

private class DynamicContractServer(private val source:Path,private val mode:String):AutoCloseable {
    private val socket=ServerSocket(0,1,InetAddress.getLoopbackAddress())
    val portFile=Files.createTempFile("reclazz-dynamic-contract-",".port").also {Files.writeString(it,socket.localPort.toString())}
    private var connection:java.net.Socket?=null
    private lateinit var writer:java.io.PrintWriter
    private val http=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    @Volatile var rows=0
    @Volatile var cleaned=false
    @Volatile var writes=0
    @Volatile var sets=0
    @Volatile private var closed=false
    init {
        Thread({
            connection=socket.accept();writer=java.io.PrintWriter(connection!!.getOutputStream(),true)
            emit("CONNECTED","dynamic-contract");receipt(false)
            var previous=Files.readString(source)
            while(!closed) {
                val current=runCatching {Files.readString(source)}.getOrDefault(previous)
                if(current!=previous) {
                    writes++;previous=current
                    if(writes==1 && mode=="stale") {
                        emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.DynamicLabelHandler (1ms)")
                        emit("COMPILE","Compiled DynamicLabelHandler.java (1ms)")
                    } else receipt(writes==1 && mode=="wrong-owner")
                }
                Thread.sleep(2)
            }
        },"dynamic-contract-events").apply {isDaemon=true;start()}
        http.createContext("/") {exchange ->
            val form=if(exchange.requestMethod=="POST") exchange.requestBody.readBytes().toString(Charsets.UTF_8) else exchange.requestURI.rawQuery
            val params=form.orEmpty().split('&').associate {
                val pair=it.split('=',limit=2);URLDecoder.decode(pair[0],Charsets.UTF_8) to URLDecoder.decode(pair.getOrElse(1){""},Charsets.UTF_8)
            }
            val run=params.getValue("run");check(run.matches(Regex("^reclazz-dyn-[0-9a-f-]{36}$")))
            val sourceText=Files.readString(source)
            val version=if("dyn-v2:" in sourceText) "v2" else "v1"
            var code=200
            val result:Map<String,Any> = when(exchange.requestURI.path.substringAfterLast('/')) {
                "seed" -> {rows=1;if(mode=="partial-seed") code=500;mapOf("run" to run)}
                "read" -> mapOf("run" to run,"held" to "dyn-${if(mode=="held-stale") "v1" else version}:$run-row",
                    "fresh" to "dyn-$version:$run-row","heldIdentity" to if(mode=="held-replaced" && version=="v2") 3 else 1,
                    "freshIdentity" to if(mode=="fresh-alias") 1 else 2,"differentInstance" to (mode!="fresh-alias"),
                    "pk" to "1001","freshPk" to if(mode=="wrong-pk") "2002" else "1001",
                    "modelClass" to "de.hybris.platform.core.model.ReclazzSchemaProbeModel",
                    "handler" to "reclazzDynamicLabelHandler","nativeDynamic" to (mode!="missing-descriptor"))
                "set" -> {
                    sets++;val token=params.getValue("token")
                    val prefix=if("set-v2:" in sourceText) "v2" else "v1"
                    mapOf("run" to run,"pk" to "1001","token" to token,
                        "value" to if(mode=="wrong-token" && token.endsWith("-t2")) "set-$prefix:stale-token"
                        else "set-${if(mode=="wrong-version" && token.endsWith("-t2")) "v1" else prefix}:$token")
                }
                "cleanup" -> {val removed=rows;rows=if(mode=="owned-leftover") 1 else 0;cleaned=true;mapOf("run" to run,"removed" to removed)}
                else -> mapOf("run" to run,"ownedRemaining" to rows,
                    "globalRemaining" to if(cleaned && mode=="global-leftover") 1 else rows,
                    "nonces" to if(rows==1) listOf("$run-row") else emptyList<String>(),
                    "heldRemaining" to 0,"mapRemaining" to if(cleaned && mode=="map-leftover") 1 else 0)
            }
            val bytes=batchJson.writeValueAsBytes(result);exchange.sendResponseHeaders(code,bytes.size.toLong());exchange.responseBody.use {it.write(bytes)}
        };http.start()
    }
    val baseUrl get()="http://127.0.0.1:${http.address.port}"
    @Synchronized fun emit(level:String,message:String) {writer.println(batchJson.writeValueAsString(mapOf("level" to level,"message" to message,"timestamp" to "contract")))}
    private fun receipt(wrong:Boolean) {
        emit("COMPILE","Compiled DynamicLabelHandler.java (1ms)")
        emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.DynamicLabelHandler${if(wrong) "Other" else ""} (1ms)")
    }
    override fun close() {closed=true;http.stop(0);connection?.close();socket.close();Files.deleteIfExists(portFile)}
}

fun runDynamicHarnessContracts():Int {
    var cases=0
    for(mode in listOf("normal","stale","wrong-owner","held-stale","fresh-alias","held-replaced","wrong-pk",
        "missing-descriptor","wrong-token","wrong-version","partial-seed","owned-leftover","global-leftover","map-leftover","withheld")) {
        val root=Files.createTempDirectory("reclazz-dynamic-harness-")
        val relative="src/com/onurkat/reclazztest/batch/DynamicLabelHandler.java"
        val baseline=DynamicAttributeLiveTest::class.java.classLoader.getResourceAsStream("templates/batch/$relative")!!.use {it.readBytes()}
        val source=root.resolve(relative);Files.createDirectories(source.parent);Files.write(source,baseline)
        val copy=Files.createTempFile("reclazz-dynamic-withheld-",".java")
        try {DynamicContractServer(source,mode).use {s ->
            val client=AgentEventClient(s.portFile.toString());client.connect()
            try {
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
                while(client.getEvents().none {it.level=="RELOAD"}) {check(System.nanoTime()<deadline);Thread.sleep(2)}
                val config=TestConfig(s.baseUrl,root.toString(),s.portFile.toString(),eventTimeoutMs=500)
                val result=DynamicAttributeLiveTest(config,client,HttpVerifier(s.baseUrl,1000),if(mode=="withheld") copy else null).run()
                check(result.status==if(mode=="normal") TestStatus.PASS else TestStatus.FAIL) {"$mode: $result"}
                check(s.cleaned && Files.readAllBytes(source).contentEquals(baseline)) {"$mode missed cleanup/restore: $result"}
                if(mode=="normal") check(s.sets==3 && s.writes==3) {"Expected three single setter calls and three saves"}
                if(mode in setOf("stale","wrong-owner","withheld")) check("Timeout waiting for COMPILE then RELOAD" in result.notes) {result.toString()}
                if(mode=="global-leftover") check("globalRemaining" in result.notes) {result.toString()}
                cases++;println("DYNAMIC_HARNESS PASS $mode")
            } finally {client.disconnect()}
        }} finally {root.toFile().deleteRecursively();Files.deleteIfExists(copy)}
    }
    return cases
}
