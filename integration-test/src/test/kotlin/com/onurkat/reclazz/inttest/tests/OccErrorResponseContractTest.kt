package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestStatus
import com.sun.net.httpserver.HttpServer
import java.net.*
import java.nio.file.*
import java.util.concurrent.TimeUnit

private class OccErrorContractServer(private val source:Path,private val mode:String):AutoCloseable {
    private val socket=ServerSocket(0,1,InetAddress.getLoopbackAddress())
    val portFile=Files.createTempFile("reclazz-occ-error-contract-",".port").also {Files.writeString(it,socket.localPort.toString())}
    private var connection:Socket?=null
    private lateinit var writer:java.io.PrintWriter
    private val http=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    @Volatile private var watching=true
    @Volatile private var v2=false
    @Volatile private var edited=false
    private val watcher:Thread
    init {
        Thread({connection=socket.accept();writer=java.io.PrintWriter(connection!!.getOutputStream(),true);emit("CONNECTED","occ-error-contract");receipt()},"occ-error-contract-accept").apply {isDaemon=true;start()}
        http.createContext("/") {x ->
            try {
                check(x.requestHeaders.getFirst("Accept")=="application/json")
                val path=x.requestURI.path
                var status=if(path.endsWith("success"))200 else 400
                val result:Any
                if(path.endsWith("success")) {
                    result=linkedMapOf<String,Any>("success" to "reclazz-c67-stable","jvmStart" to if(mode=="jvm"&&v2)23L else 22L,
                        "serviceIdentity" to 12,"contextIdentity" to 11,"sameBean" to (mode!="wrong-bean"),
                        "sameServingContext" to (mode!="wrong-context"),"localResolver" to (mode!="missing-resolver"),
                        "resolver" to if(mode=="resolver-class")"com.example.Foreign" else "de.hybris.platform.webservicescommons.resolver.RestExceptionResolver",
                        "factory" to "de.hybris.platform.webservicescommons.errors.factory.impl.DefaultWebserviceErrorFactory",
                        "converter" to "de.hybris.platform.webservicescommons.errors.converters.ExceptionConverter")
                    if(mode=="success"&&v2)result["success"]="changed"
                } else {
                    val control=path.endsWith("control")
                    var type=if(control)"ReclazzOccControlError" else if(v2&&mode!="stale-type")"ReclazzOccSecondError" else "ReclazzOccFirstError"
                    if(control&&mode=="control"&&v2)type="ReclazzOccSecondError"
                    val error=linkedMapOf<String,Any>("type" to type,"message" to if(mode=="message"&&!control)"wrong" else "The application has encountered an error")
                    if(mode=="extra-field"&&!control)error["subject"]="unexpected"
                    val errors=mutableListOf<Map<String,Any>>(error)
                    if(mode=="duplicate"&&!control)errors.add(error)
                    if(mode=="empty"&&!control)errors.clear()
                    result=if(mode=="missing-errors"&&!control)mapOf("wrong" to errors) else mapOf("errors" to errors)
                    if(mode=="status"&&!control)status=200
                }
                val bytes=batchJson.writeValueAsBytes(result);x.sendResponseHeaders(status,bytes.size.toLong());x.responseBody.use {it.write(bytes)}
            }catch(e:Exception){x.sendResponseHeaders(500,-1);x.close()}
        };http.start()
        watcher=Thread({
            var previous=Files.readAllBytes(source)
            while(watching) {
                val next=Files.readAllBytes(source)
                if(!next.contentEquals(previous)) {
                    previous=next
                    val nextV2=next.toString(Charsets.UTF_8).contains("throw new ReclazzOccSecondException(\"reclazz-c67-owned\")")
                    v2=nextV2||(mode=="restore-stale"&&edited);if(nextV2)edited=true
                    when {
                        nextV2&&mode in setOf("missing-receipt","stale-receipt") -> Unit
                        !nextV2&&mode=="restore-receipt" -> Unit
                        nextV2&&mode=="wrong-owner" -> {emit("COMPILE","Compiled ProbeOccErrorService.java (1ms)");emit("RELOAD","Reloaded com.example.Foreign (1ms)")}
                        nextV2&&mode=="reversed" -> {emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.ProbeOccErrorService (1ms)");emit("COMPILE","Compiled ProbeOccErrorService.java (1ms)")}
                        else -> receipt()
                    }
                }
                Thread.sleep(5)
            }
        },"occ-error-contract-watcher").apply {isDaemon=true;start()}
    }
    val baseUrl get()="http://127.0.0.1:${http.address.port}"
    @Synchronized private fun emit(level:String,message:String) {writer.println(batchJson.writeValueAsString(mapOf("level" to level,"message" to message,"timestamp" to "contract")))}
    private fun receipt(){emit("COMPILE","Compiled ProbeOccErrorService.java (1ms)");emit("RELOAD","Reloaded com.onurkat.reclazztest.batch.ProbeOccErrorService (1ms)")}
    override fun close(){watching=false;watcher.join(1000);http.stop(0);connection?.close();socket.close();Files.deleteIfExists(portFile)}
}

fun main() {
    val modes=listOf("normal","stale-type","message","extra-field","duplicate","empty","missing-errors","status","success","control","jvm","wrong-bean","wrong-context","missing-resolver","resolver-class","withhold-save","missing-receipt","stale-receipt","wrong-owner","reversed","restore-receipt","restore-stale")
    for(mode in modes) {
        val root=Files.createTempDirectory("reclazz-occ-error-harness-")
        val relative="src/com/onurkat/reclazztest/batch/ProbeOccErrorService.java";val file=root.resolve(relative)
        val baseline=OccErrorResponseLiveTest::class.java.classLoader.getResourceAsStream("templates/batch/$relative")!!.use {it.readBytes()}
        Files.createDirectories(file.parent);Files.write(file,baseline)
        try {OccErrorContractServer(file,mode).use {server ->
            val client=AgentEventClient(server.portFile.toString());client.connect()
            try {
                val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while(client.getEvents().none {it.level=="RELOAD"}) {check(System.nanoTime()<until);Thread.sleep(5)}
                val config=TestConfig(server.baseUrl,root.toString(),server.portFile.toString(),eventTimeoutMs=300)
                val result=OccErrorResponseLiveTest(config,client,HttpVerifier(server.baseUrl,1000),mode=="withhold-save").run()
                check(result.status==if(mode=="normal")TestStatus.PASS else TestStatus.FAIL){"$mode: $result"}
                check(Files.readAllBytes(file).contentEquals(baseline)){"$mode leaked source"}
                if(mode in setOf("missing-receipt","stale-receipt","wrong-owner","reversed","restore-receipt"))check("Timeout waiting for COMPILE then RELOAD" in result.notes){"$mode wrong receipt failure: $result"}
                println("OCC_ERROR_CONTRACT PASS $mode")
            }finally {client.disconnect()}
        }}finally {root.toFile().deleteRecursively()}
    }
    requireDefaultMembership(committedDefaultMembership.toList());check(committedDefaultMembership.size==62&&"OccErrorResponseLiveTest" in committedDefaultMembership)
    println("OCC_ERROR_CONTRACT ${modes.size+1} checks PASS")
}
