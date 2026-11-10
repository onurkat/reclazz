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

private class DaoContractServer(private val source:Path,private val mode:String):AutoCloseable {
    private val socket=ServerSocket(0,1,InetAddress.getLoopbackAddress())
    val portFile=Files.createTempFile("reclazz-dao-contract-",".port").also {Files.writeString(it,socket.localPort.toString())}
    private var connection:java.net.Socket?=null
    private lateinit var writer:java.io.PrintWriter
    private val http=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    @Volatile var rows=0
    @Volatile var cleaned=false
    init {
        Thread({connection=socket.accept();writer=java.io.PrintWriter(connection!!.getOutputStream(),true);emit("CONNECTED","dao-contract")},
            "dao-contract-accept").apply {isDaemon=true;start()}
        http.createContext("/") {exchange ->
            val form=if(exchange.requestMethod=="POST") exchange.requestBody.readBytes().toString(Charsets.UTF_8) else exchange.requestURI.rawQuery
            val run=URLDecoder.decode(form.substringAfter("run="),Charsets.UTF_8)
            check(run.matches(Regex("^reclazz-dao-[0-9a-f-]{36}$")))
            val all=listOf("a-1","a-2","b-1","c-1").take(rows).map {"$run-$it"}
            var code=200
            val result:Map<String,Any> = when(exchange.requestURI.path.substringAfterLast('/')) {
                "seed" -> {rows=if(mode=="partial-seed") 1 else 4;if(mode=="partial-seed") code=500;mapOf("run" to run)}
                "find" -> mapOf("run" to run,"beanIdentity" to if(Files.readString(source).contains("-b-%")) 2 else 1,
                    "nonces" to if(Files.readString(source).contains("-b-%")) listOf("$run-b-1") else listOf("$run-a-1","$run-a-2"))
                "cleanup" -> {val removed=rows;rows=if(mode=="leftover") 1 else 0;cleaned=true;mapOf("run" to run,"removed" to removed)}
                else -> mapOf("run" to run,"ownedRemaining" to rows,"globalRemaining" to rows,"nonces" to all)
            }
            val bytes=batchJson.writeValueAsBytes(result);exchange.sendResponseHeaders(code,bytes.size.toLong());exchange.responseBody.use {it.write(bytes)}
        };http.start()
    }
    val baseUrl get()="http://127.0.0.1:${http.address.port}"
    @Synchronized fun emit(level:String,message:String) {writer.println(batchJson.writeValueAsString(mapOf("level" to level,"message" to message,"timestamp" to "contract")))}
    fun receipt() {emit("COMPILE","Compiled ProbeQueryDao.java (1ms)");emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.ProbeQueryDao (1ms)")}
    override fun close() {http.stop(0);connection?.close();socket.close();Files.deleteIfExists(portFile)}
}

fun runDaoHarnessContracts():Int {
    var cases=0
    for(mode in listOf("normal","stale","wrong-owner","leftover","partial-seed","withheld")) {
        val root=Files.createTempDirectory("reclazz-dao-harness-")
        val relative="src/com/onurkat/reclazztest/batch/ProbeQueryDao.java"
        val baseline=FlexibleSearchLiveTest::class.java.classLoader.getResourceAsStream("templates/batch/$relative")!!.use {it.readBytes()}
        val file=root.resolve(relative);Files.createDirectories(file.parent);Files.write(file,baseline)
        val copy=Files.createTempFile("reclazz-dao-withheld-",".java")
        fun waitFor(description:String,condition:()->Boolean) {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(!condition()) {check(System.nanoTime()<deadline) {description};Thread.sleep(5)}
        }
        try {DaoContractServer(file,mode).use {s ->
            val client=AgentEventClient(s.portFile.toString());client.connect()
            try {
                s.receipt();waitFor("stale receipt not read") {client.getEvents().any {it.level=="RELOAD"}}
                val config=TestConfig(s.baseUrl,root.toString(),s.portFile.toString(),eventTimeoutMs=700)
                val future=CompletableFuture.supplyAsync {
                    FlexibleSearchLiveTest(config,client,HttpVerifier(s.baseUrl,1000),if(mode=="withheld") copy else null).run()
                }
                if(mode !in setOf("partial-seed","withheld")) {
                    waitFor("DAO edit not saved") {!Files.readAllBytes(file).contentEquals(baseline)}
                    when(mode) {
                        "stale" -> {s.emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.ProbeQueryDao (1ms)");s.emit("COMPILE","Compiled ProbeQueryDao.java (1ms)")}
                        "wrong-owner" -> {s.emit("COMPILE","Compiled ProbeQueryDao.java (1ms)");s.emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.ProbeQueryDaoOther (1ms)")}
                        else -> s.receipt()
                    }
                    waitFor("DAO restore not saved") {Files.readAllBytes(file).contentEquals(baseline)}
                    s.receipt()
                }
                val result=future.get(6,TimeUnit.SECONDS)
                check(result.status==if(mode=="normal") TestStatus.PASS else TestStatus.FAIL) {"$mode: $result"}
                check(s.cleaned && Files.readAllBytes(file).contentEquals(baseline)) {"$mode missed cleanup/restore"}
                check(s.rows==if(mode=="leftover") 1 else 0)
                if(mode in setOf("stale","wrong-owner","withheld")) check("Timeout waiting for COMPILE then RELOAD" in result.notes) {result.toString()}
                if(mode=="leftover") check("cleanup" in result.notes.lowercase()) {result.toString()}
                cases++;println("DAO_HARNESS PASS $mode")
            } finally {client.disconnect()}
        }} finally {root.toFile().deleteRecursively();Files.deleteIfExists(copy)}
    }
    return cases
}
