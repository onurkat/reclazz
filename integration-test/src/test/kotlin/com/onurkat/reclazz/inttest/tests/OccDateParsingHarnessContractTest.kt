package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestStatus
import com.sun.net.httpserver.HttpServer
import java.net.*
import java.nio.file.*
import java.util.concurrent.TimeUnit

private class OccDateContractServer(private val source:Path,private val mode:String):AutoCloseable {
    private val socket=ServerSocket(0,1,InetAddress.getLoopbackAddress())
    val portFile=Files.createTempFile("reclazz-date-contract-",".port").also {Files.writeString(it,socket.localPort.toString())}
    private var connection:Socket?=null
    private lateinit var writer:java.io.PrintWriter
    private val http=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    @Volatile private var watching=true
    @Volatile private var v2=false
    @Volatile private var edited=false
    private val watcher:Thread
    init {
        Thread({connection=socket.accept();writer=java.io.PrintWriter(connection!!.getOutputStream(),true);emit("CONNECTED","date-contract");receipt()},"date-contract-accept").apply {isDaemon=true;start()}
        http.createContext("/") {x ->
            try {
                val params=x.requestURI.rawQuery.split('&').associate {it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='),Charsets.UTF_8)}
                val token=params.getValue("token");val invalid=params["timestamp"]!="2026-11-12T03:04:05+02:30"
                var status=if(invalid&&mode!="invalid-status")400 else 200
                val result=linkedMapOf<String,Any>("token" to if(mode=="wrong-token")"foreign" else token,
                    "contextId" to "occ-context","contextIdentity" to 11,"jvmStart" to 22L,
                    "webContext" to true,"sameServingContext" to (mode!="wrong-context"),
                    "localBean" to (mode!="missing-bean"),"sameBean" to (mode!="wrong-bean"),
                    "parserClass" to if(mode=="wrong-class")"com.example.Foreign" else "com.onurkat.reclazztest.batch.ProbeOccDateParser",
                    "converterContract" to true,"parserLoader" to "core-loader","contextLoader" to "occ-loader",
                    "occPath" to "/occ","targetCalls" to if(mode=="zero-calls")0 else 1,
                    "controlEpoch" to if(mode=="control")0L else 1794443645000L,
                    "locale" to if(mode=="environment"&&v2)"changed" else "en","zone" to "UTC")
                if(invalid&&mode!="invalid-accepted")result["error"]="invalid-timestamp"
                else result["epoch"]=1794443645000L+if((v2&&mode!="wrong-epoch")||(mode=="withheld-false-pass"&&token.endsWith("-withheld")))1000L else 0L
                val bytes=batchJson.writeValueAsBytes(result);x.sendResponseHeaders(status,bytes.size.toLong());x.responseBody.use {it.write(bytes)}
            }catch(e:Exception){x.sendResponseHeaders(500,-1);x.close()}
        };http.start()
        watcher=Thread({
            var previous=Files.readAllBytes(source)
            while(watching) {
                val next=Files.readAllBytes(source)
                if(!next.contentEquals(previous)) {
                    previous=next
                    val nextV2=next.toString(Charsets.UTF_8).contains(" + 1000L)")
                    v2=nextV2||(mode=="restore-stale"&&edited);if(nextV2)edited=true
                    when {
                        nextV2&&mode in setOf("missing-receipt","stale-receipt") -> Unit
                        !nextV2&&mode=="restore-receipt" -> Unit
                        nextV2&&mode=="wrong-owner" -> {emit("COMPILE","Compiled ProbeOccDateParser.java (1ms)");emit("RELOAD","Reloaded com.example.Foreign (1ms)")}
                        nextV2&&mode=="reversed" -> {emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.ProbeOccDateParser (1ms)");emit("COMPILE","Compiled ProbeOccDateParser.java (1ms)")}
                        else -> receipt()
                    }
                }
                Thread.sleep(5)
            }
        },"date-contract-watcher").apply {isDaemon=true;start()}
    }
    val baseUrl get()="http://127.0.0.1:${http.address.port}"
    @Synchronized private fun emit(level:String,message:String) {writer.println(batchJson.writeValueAsString(mapOf("level" to level,"message" to message,"timestamp" to "contract")))}
    private fun receipt(){emit("COMPILE","Compiled ProbeOccDateParser.java (1ms)");emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.ProbeOccDateParser (1ms)")}
    override fun close(){watching=false;watcher.join(1000);http.stop(0);connection?.close();socket.close();Files.deleteIfExists(portFile)}
}

fun runOccDateParsingHarnessContracts():Int {
    val modes=listOf("normal","wrong-epoch","missing-bean","wrong-bean","wrong-context","wrong-class","wrong-token","zero-calls","control","invalid-status","invalid-accepted","environment","withheld-false-pass","withhold-save","missing-receipt","stale-receipt","wrong-owner","reversed","restore-receipt","restore-stale")
    for(mode in modes) {
        val root=Files.createTempDirectory("reclazz-date-harness-")
        val relative="src/com/onurkat/reclazztest/batch/ProbeOccDateParser.java";val file=root.resolve(relative)
        val baseline=OccDateParsingLiveTest::class.java.classLoader.getResourceAsStream("templates/batch/$relative")!!.use {it.readBytes()}
        Files.createDirectories(file.parent);Files.write(file,baseline)
        try {OccDateContractServer(file,mode).use {server ->
            val client=AgentEventClient(server.portFile.toString());client.connect()
            try {
                val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while(client.getEvents().none {it.level=="RELOAD"}) {check(System.nanoTime()<until);Thread.sleep(5)}
                val config=TestConfig(server.baseUrl,root.toString(),server.portFile.toString(),eventTimeoutMs=300)
                val result=OccDateParsingLiveTest(config,client,HttpVerifier(server.baseUrl,1000),mode=="withhold-save").run()
                check(result.status==if(mode=="normal")TestStatus.PASS else TestStatus.FAIL){"$mode: $result"}
                check(Files.readAllBytes(file).contentEquals(baseline)){"$mode leaked source"}
                if(mode in setOf("missing-receipt","stale-receipt","wrong-owner","reversed","restore-receipt"))check("Timeout waiting for COMPILE then RELOAD" in result.notes){"$mode wrong receipt failure: $result"}
                if(mode=="withheld-false-pass")check("WITHHELD_SAVE_FALSE_PASS" in result.notes){result}
                println("OCC_DATE_HARNESS PASS $mode")
            }finally {client.disconnect()}
        }}finally {root.toFile().deleteRecursively()}
    }
    return modes.size
}
