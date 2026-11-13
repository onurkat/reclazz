package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestStatus
import com.sun.net.httpserver.HttpServer
import java.net.*
import java.nio.file.*
import java.util.concurrent.TimeUnit

private class ImpexUpdateContractServer(private val folder:Path,private val mode:String):AutoCloseable {
    private val socket=ServerSocket(0,1,InetAddress.getLoopbackAddress())
    val portFile=Files.createTempFile("reclazz-update-contract-",".port").also {Files.writeString(it,socket.localPort.toString())}
    private var connection:Socket?=null
    private lateinit var writer:java.io.PrintWriter
    private val http=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    @Volatile private var watching=true
    @Volatile private var version="v0"
    @Volatile private var run=""
    private val watcher:Thread
    init {
        Thread({connection=socket.accept();writer=java.io.PrintWriter(connection!!.getOutputStream(),true);emit("CONNECTED","update-contract")},"update-contract-accept").apply {isDaemon=true;start()}
        http.createContext("/") {x ->
            try {
                val query=if(x.requestMethod=="POST")x.requestBody.readAllBytes().toString(Charsets.UTF_8) else x.requestURI.rawQuery.orEmpty()
                val params=query.split('&').filter {it.contains('=')}.associate {it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='),Charsets.UTF_8)}
                val path=x.requestURI.path
                val result:Any=when {
                    path.endsWith("gates") -> mapOf("autoImpex" to (mode!="disabled"),"impexAllowRemove" to false)
                    path.endsWith("cleanup") -> mapOf("run" to run,"ownedRemaining" to if(mode=="cleanup")1 else 0)
                    else -> {
                        if(path.endsWith("begin")){run=params.getValue("run");version="v0"}
                        val rows=linkedMapOf<String,Any>()
                        for(role in listOf("target","control"))rows[role]=mapOf("code" to "$run-$role","pk" to if(mode=="pk"&&version=="v2")"999" else if(role=="target")"101" else "102",
                            "english" to "$run:"+if(role=="target")version else if(mode=="control"&&version=="v2")"changed" else "control",
                            "german" to "$run:"+if(mode=="german"&&version=="v2")"changed" else "ordinary")
                        mapOf("run" to if(mode=="nonce")"foreign" else run,"owned" to if(mode=="count")1 else 2,"rows" to rows)
                    }
                }
                val bytes=batchJson.writeValueAsBytes(result);x.sendResponseHeaders(200,bytes.size.toLong());x.responseBody.use {it.write(bytes)}
            }catch(e:Exception){x.sendResponseHeaders(500,-1);x.close()}
        };http.start()
        watcher=Thread({
            val observed=mutableMapOf<Path,String>()
            while(watching) {
                Files.list(folder).use {paths -> paths.filter {it.toString().endsWith(".impex")}.forEach {p ->
                    val text=Files.readString(p)
                    if(text.isNotBlank()&&observed[p]!=text&&::writer.isInitialized) {
                        observed[p]=text
                        val guard=text.contains("\$undefinedHeader")
                        val next=Regex("(?m)^\\\$value=[^\\n]*:(v0|v1|v2|unapplied)$").find(text)?.groupValues?.get(1) ?: error("No value macro: $text")
                        val file=p.fileName.toString()
                        if(guard) {
                            if(mode=="partial")version="unapplied"
                            if(mode=="false-success")emit("OK","ImpEx imported: $file")
                            if(mode!="missing-guard")emit("WARN","ImpEx not imported: ${if(mode=="guard-file")"foreign.impex" else file} has a REMOVE or unresolved macro header at line ${if(mode=="guard-line")8 else 7}. Auto-import will not delete data.")
                        } else {
                            version=when {mode=="stale-v1"&&next=="v1" -> "v0";mode=="stale-v2"&&next=="v2" -> "v1";mode=="restore-stale"&&next=="v0" -> "v2";else -> next}
                            if(!(mode=="missing-receipt"&&next=="v1")&&!(mode=="restore-receipt"&&next=="v0"))emit("OK","ImpEx imported: ${if(mode=="wrong-file"&&next=="v1")"foreign.impex" else file}")
                        }
                    }
                }}
                Thread.sleep(5)
            }
        },"update-contract-watcher").apply {isDaemon=true;start()}
    }
    val baseUrl get()="http://127.0.0.1:${http.address.port}"
    @Synchronized private fun emit(level:String,message:String) {writer.println(batchJson.writeValueAsString(mapOf("level" to level,"message" to message,"timestamp" to "contract")))}
    override fun close(){watching=false;watcher.join(1000);http.stop(0);connection?.close();socket.close();Files.deleteIfExists(portFile)}
}

fun main() {
    val owned="reclazz-impex-00000000-0000-0000-0000-000000000001"
    for(version in listOf("v0","v1","v2")) {
        val lines=impexUpdateScript(owned,version).lines()
        check(lines==listOf("\$type=Title","\$column=name[lang=en]","\$code=$owned-target","\$value=$owned:$version",
            "UPDATE \$type;code[unique=true];\$column",";\$code;\$value","")) {"Native UPDATE grammar must remain literal before header macros"}
    }
    val rejected=impexUpdateScript(owned,"unapplied",true).lines()
    check(rejected[4]=="UPDATE \$type;code[unique=true];\$column"&&rejected[5]==";\$code;\$value"&&rejected[6]=="\$undefinedHeader;code[unique=true];\$column")

    val modes=listOf("normal","stale-v1","stale-v2","nonce","pk","count","control","german","missing-receipt","wrong-file","missing-guard","guard-file","guard-line","false-success","partial","restore-receipt","restore-stale","cleanup","disabled")
    for(mode in modes) {
        val root=Files.createTempDirectory("reclazz-update-harness-");val dir=root.resolve("resources/impex");Files.createDirectories(dir)
        try {ImpexUpdateContractServer(dir,mode).use {server ->
            val client=AgentEventClient(server.portFile.toString());client.connect()
            try {
                val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while(client.getEvents().none {it.level=="CONNECTED"}) {check(System.nanoTime()<until);Thread.sleep(5)}
                val config=TestConfig(server.baseUrl,root.toString(),server.portFile.toString(),eventTimeoutMs=400)
                val result=ImpexUpdateMacrosLiveTest(config,client,HttpVerifier(server.baseUrl,1000)).run()
                val expected=when(mode){"normal"->TestStatus.PASS;"disabled"->TestStatus.SKIP;else->TestStatus.FAIL}
                check(result.status==expected){"$mode: $result"}
                check(Files.list(dir).use {it.count()}==0L){"$mode leaked watched file"}
                println("IMPEX_UPDATE_CONTRACT PASS $mode")
            }finally {client.disconnect()}
        }}finally {root.toFile().deleteRecursively()}
    }
    requireDefaultMembership(committedDefaultMembership.toList());check(committedDefaultMembership.size==64&&"ImpexUpdateMacrosLiveTest" in committedDefaultMembership)
    println("IMPEX_UPDATE_CONTRACT ${modes.size+5} checks PASS")
}
