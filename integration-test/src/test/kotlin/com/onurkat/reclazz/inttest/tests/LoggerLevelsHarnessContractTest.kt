package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestStatus
import com.sun.net.httpserver.HttpServer
import java.net.*
import java.nio.file.*
import java.util.concurrent.TimeUnit

private class LoggerContractServer(private val source:Path,private val mode:String):AutoCloseable {
    private val socket=ServerSocket(0,1,InetAddress.getLoopbackAddress())
    val portFile=Files.createTempFile("reclazz-logger-contract-",".port").also {Files.writeString(it,socket.localPort.toString())}
    private var connection:Socket?=null
    private lateinit var writer:java.io.PrintWriter
    private val http=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    private val events=mutableListOf<Map<String,String>>()
    @Volatile private var watching=true
    @Volatile var cleaned=false
    @Volatile private var debug=false
    private val target="com.onurkat.reclazztest.logprobe.target"
    private val control="com.onurkat.reclazztest.logprobe.control"
    init {
        Thread({connection=socket.accept();writer=java.io.PrintWriter(connection!!.getOutputStream(),true);emit("CONNECTED","logger-contract");receipt()},"logger-contract-accept").apply {isDaemon=true;start()}
        http.createContext("/") {x ->
            try {
                val form=if(x.requestMethod=="POST") x.requestBody.readBytes().toString(Charsets.UTF_8) else x.requestURI.rawQuery
                val p=form.split('&').associate {it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='),Charsets.UTF_8)}
                val run=p.getValue("run");val action=x.requestURI.path.substringAfterLast('/')
                var status=200
                if(action=="emit") {
                    val phase=p.getValue("phase")
                    if(p["omit"]!="true") {
                        for((logger,level) in listOf(target to "DEBUG",target to "INFO",control to "DEBUG",control to "INFO")) {
                            if(level=="DEBUG" && !(logger==target && debug) && !(mode=="control-output" && logger==control)) continue
                            if(mode=="missing-info" && level=="INFO") continue
                            if(mode=="level-only" && level=="DEBUG") continue
                            val role=if(logger==target) "target" else "control"
                            val event=mapOf("logger" to if(mode=="wrong-logger") "foreign" else logger,"level" to level,
                                "message" to if(mode=="wrong-nonce") "foreign" else "$run:${if(mode=="wrong-phase") "wrong" else phase}:$role:$level")
                            events.add(event);if(mode=="duplicate") events.add(event)
                        }
                    }
                }
                val result=if(action=="cleanup") {
                    cleaned=true
                    mapOf("run" to run,"active" to (mode=="active-leak"),"attachments" to if(mode=="sink-leak") 1 else 0,"restored" to (mode!="cleanup-failure" && mode!="combined-failure"))
                } else {
                    if(action=="begin" && mode=="partial-setup") status=500
                    mapOf("run" to if(mode=="wrong-run") "foreign" else run,
                        "jvm" to if(mode=="changed-jvm" && debug) "different" else "jvm-1",
                        "sameContext" to (mode!="wrong-context"),"unchanged" to (mode !in setOf("root-mutation","appender-mutation","unrelated-mutation","combined-failure")),
                        "targetLevel" to if(debug && mode!="stale-level") "DEBUG" else "INFO",
                        "controlLevel" to if(mode=="control-level") "DEBUG" else "INFO","events" to events.toList())
                }
                val bytes=batchJson.writeValueAsBytes(result);x.sendResponseHeaders(status,bytes.size.toLong());x.responseBody.use {it.write(bytes)}
            } catch(e:Exception) {x.sendResponseHeaders(500,-1);x.close()}
        };http.start()
        Thread({
            var previous=Files.readAllBytes(source)
            while(watching) {
                val bytes=Files.readAllBytes(source)
                if(!bytes.contentEquals(previous)) {
                    previous=bytes;debug=bytes.toString(Charsets.UTF_8).contains("reclazzProbeTarget.level=DEBUG")
                    when {
                        debug && mode in setOf("missing-receipt","stale-receipt") -> Unit
                        debug && mode=="wrong-receipt" -> emit("OK","Logger level applied: [foreign]")
                        debug && mode=="error-event" -> {emit("ERROR","$target failure");receipt()}
                        !debug && mode=="restore-receipt" -> Unit
                        else -> receipt()
                    }
                }
                Thread.sleep(5)
            }
        },"logger-contract-watcher").apply {isDaemon=true;start()}
    }
    val baseUrl get()="http://127.0.0.1:${http.address.port}"
    @Synchronized fun emit(level:String,message:String) {writer.println(batchJson.writeValueAsString(mapOf("level" to level,"message" to message,"timestamp" to "contract")))}
    fun receipt()=emit("OK","Logger level applied: [$target]")
    override fun close() {watching=false;http.stop(0);connection?.close();socket.close();Files.deleteIfExists(portFile)}
}

fun runLoggerHarnessContracts():Int {
    var count=0
    for(mode in listOf("normal","missing-receipt","stale-receipt","wrong-receipt","error-event","restore-receipt","withheld","omit-native",
        "stale-level","level-only","wrong-run","wrong-logger","wrong-nonce","wrong-phase","duplicate","missing-info","control-output",
        "control-level","root-mutation","appender-mutation","unrelated-mutation","changed-jvm","wrong-context","cleanup-failure","combined-failure","sink-leak","active-leak","partial-setup")) {
        val dir=Files.createTempDirectory("reclazz-logger-harness-");val file=dir.resolve("project.properties")
        val baseline=LoggerLevelsLiveTest::class.java.classLoader.getResourceAsStream("templates/batch/project.properties")!!.use {it.readBytes()}
        Files.write(file,baseline)
        try {LoggerContractServer(file,mode).use {server ->
            val client=AgentEventClient(server.portFile.toString());client.connect()
            try {
                val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while(client.getEvents().none {it.level=="OK"}) {check(System.nanoTime()<until);Thread.sleep(5)}
                val config=TestConfig(server.baseUrl,dir.toString(),server.portFile.toString(),eventTimeoutMs=250)
                val result=LoggerLevelsLiveTest(config,client,HttpVerifier(server.baseUrl,1000),mode=="withheld",mode=="omit-native").run()
                check(result.status==if(mode=="normal") TestStatus.PASS else TestStatus.FAIL) {"$mode: $result"}
                check(server.cleaned && Files.readAllBytes(file).contentEquals(baseline)) {"$mode cleanup/bytes: $result"}
                if(mode=="combined-failure") check("restore:" in result.notes)
                if(mode in setOf("level-only","stale-level","omit-native","withheld"))
                    check("restore:" !in result.notes) {"$mode primary failure blocked independent restoration: $result"}
                println("LOGGER_HARNESS PASS $mode");count++
            } finally {client.disconnect()}
        }} finally {dir.toFile().deleteRecursively()}
    }
    return count
}
