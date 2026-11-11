package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestStatus
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private class BatchContractServer:AutoCloseable {
    private val socket=ServerSocket(0,1,InetAddress.getLoopbackAddress())
    val portFile=Files.createTempFile("reclazz-batch-contract-",".port").also {Files.writeString(it,socket.localPort.toString())}
    private var connection:java.net.Socket?=null
    private lateinit var writer:java.io.PrintWriter
    val http=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    @Volatile var cleanup="{\"ownedRemaining\":0}"
    init {
        Thread({
            try {connection=socket.accept();writer=java.io.PrintWriter(connection!!.getOutputStream(),true);emit("CONNECTED","batch-contract")}
            catch(e:java.net.SocketException) {if(!socket.isClosed) throw e}
        },"batch-contract-accept").apply {isDaemon=true;start()}
        http.createContext("/") {exchange->
            val body=if(exchange.requestURI.path.endsWith("/gates")) "{\"autoUpdateRunningSystem\":true,\"impexAllowRemove\":true}" else cleanup
            val bytes=body.toByteArray();exchange.sendResponseHeaders(200,bytes.size.toLong());exchange.responseBody.use {it.write(bytes)}
        };http.start()
    }
    val baseUrl get()="http://127.0.0.1:${http.address.port}"
    @Synchronized fun emit(level:String,message:String) {writer.println(batchJson.writeValueAsString(mapOf("level" to level,"message" to message,"timestamp" to "contract")))}
    override fun close() {http.stop(0);connection?.close();socket.close();Files.deleteIfExists(portFile)}
}
private fun waitUntil(description:String, condition:()->Boolean) {
    val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
    while(!condition()) {check(System.nanoTime()<deadline) {description};Thread.sleep(5)}
}
private class ResourceContract(c:TestConfig,a:AgentEventClient,h:HttpVerifier,private val mode:String):BatchLiveTest("resource-contract",c,a,h) {
    override fun scenario() {
        val relative="project.properties";val baseline=original(relative)
        val edited=replaceOnce(baseline.toString(Charsets.UTF_8),"reclazztest.batch.value=config-v1","reclazztest.batch.value=config-v2")
        fixtureEdit(relative,edited.toByteArray(),{p,b->resourceWrite(p,b,listOf("reclazztest.batch.value"),
            {it.level=="INFO" && it.message=="Applied 1 property change: [reclazztest.batch.value]"})},
            {},beforeRestore={if(mode=="cleanup-failure") error("cleanup-failure")}) {
            if(mode=="cleanup-failure") error("body-failure")
        }
    }
}
fun runBatchHarnessContracts():Int {
    var cases=0
    fun case(name:String,action:()->Unit) {action();cases++;println("BATCH_HARNESS PASS $name")}
    fun resourceCase(mode:String, expected:TestStatus, events:(BatchContractServer,AgentEventClient,CompletableFuture<com.onurkat.reclazz.inttest.report.TestResult>,Path,ByteArray)->Unit) {
        val root=Files.createTempDirectory("reclazz-batch-contract-")
        val baseline=BatchLiveTest::class.java.classLoader.getResourceAsStream("templates/batch/project.properties")!!.use {it.readBytes()}
        val file=root.resolve("project.properties");Files.write(file,baseline)
        try {BatchContractServer().use {server->
            val client=AgentEventClient(server.portFile.toString());client.connect()
            try {
                val config=TestConfig(server.baseUrl,root.toString(),server.portFile.toString(),eventTimeoutMs=800)
                server.emit("INFO","Applied 1 property change: [reclazztest.batch.value]")
                waitUntil("old event not received") {client.getEvents().any {it.message.contains("reclazztest.batch.value")}}
                val future=CompletableFuture.supplyAsync {ResourceContract(config,client,HttpVerifier(server.baseUrl,1000),mode).run()}
                waitUntil("edit was not written") {!Files.readAllBytes(file).contentEquals(baseline)}
                events(server,client,future,file,baseline)
                val result=future.get(3,TimeUnit.SECONDS)
                check(result.status==expected) {result.toString()};check(Files.readAllBytes(file).contentEquals(baseline))
                if(mode=="cleanup-failure") check("body-failure" in result.notes && "cleanup-failure" in result.notes) {result.toString()}
            } finally {client.disconnect()}
        }} finally {root.toFile().deleteRecursively()}
    }
    fun acknowledgeRestore(server:BatchContractServer,file:Path,baseline:ByteArray) {
        waitUntil("restore was not written") {Files.readAllBytes(file).contentEquals(baseline)}
        server.emit("INFO","Applied 1 property change: [reclazztest.batch.value]")
    }
    case("resourceCursorRejectsStaleAndOtherKeyThenWaitsForRestore") {
        resourceCase("normal",TestStatus.PASS) {s,_,future,file,baseline->
            s.emit("INFO","Applied 1 property change: [reclazztest.batch.other]")
            Thread.sleep(100);check(!future.isDone) {"Unrelated key satisfied resource write"}
            s.emit("INFO","Applied 1 property change: [reclazztest.batch.value]")
            waitUntil("baseline bytes not written") {Files.readAllBytes(file).contentEquals(baseline)}
            Thread.sleep(100);check(!future.isDone) {"Previous write outcome satisfied restoration"}
            s.emit("INFO","Applied 1 property change: [reclazztest.batch.value]")
        }
    }
    case("unexpectedScopedWarningFailsAndStillRestores") {
        resourceCase("warning",TestStatus.FAIL) {s,_,_,file,baseline->
            s.emit("WARN","reclazztest.batch.value failed to apply")
            acknowledgeRestore(s,file,baseline)
        }
    }
    case("missingScopedTerminalFailsDespiteAdmissionAndUnrelatedSuccess") {
        resourceCase("missing",TestStatus.FAIL) {s,_,_,file,baseline->
            s.emit("INFO","Config file changed: project.properties")
            s.emit("INFO","Applied 1 property change: [reclazztest.batch.other]")
            acknowledgeRestore(s,file,baseline)
        }
    }
    case("cleanupFailureDoesNotSkipByteRestoreOrHideOriginalFailure") {
        resourceCase("cleanup-failure",TestStatus.FAIL) {s,_,_,file,baseline->
            s.emit("INFO","Applied 1 property change: [reclazztest.batch.value]")
            acknowledgeRestore(s,file,baseline)
        }
    }
    case("onlyExactExpectedRefusalIsAllowed") {
        val allowed={event:com.onurkat.reclazz.inttest.client.AgentEvent->event.level=="WARN" && event.message=="owned-file refused REMOVE"}
        val exact=com.onurkat.reclazz.inttest.client.AgentEvent("WARN","owned-file refused REMOVE","contract")
        check(resourceFailure(listOf(exact),listOf("owned-file"),allowed)==null)
        check(resourceFailure(listOf(exact.copy(message="owned-file import failed")),listOf("owned-file"),allowed)!=null)
    }
    case("cleanupMustProveNumericZeroThroughRealHttp") {BatchContractServer().use {s->
        val http=HttpVerifier(s.baseUrl,1000)
        for(body in listOf("{\"ownedRemaining\":1}","{}","{\"ownedRemaining\":\"0\"}")) {
            s.cleanup=body;check(runCatching {requireOwnedCleanup(http.post("/cleanup"))}.isFailure) {"False cleanup PASS: $body"}
        }
        s.cleanup="{\"ownedRemaining\":0}";requireOwnedCleanup(http.post("/cleanup"))
    }}
    case("unsafeOptionsSkipWithReasonBeforeWritingFixtures") {BatchContractServer().use {s->
        val config=TestConfig(s.baseUrl,"/fixture-not-accessed",s.portFile.toString())
        val client=AgentEventClient(s.portFile.toString());val http=HttpVerifier(s.baseUrl,1000)
        for(test in listOf(LocalizationLiveTest(config,client,http),ImpexRemoveRefusalLiveTest(config,client,http))) {
            val result=test.run();check(result.status==TestStatus.SKIP && "requires " in result.notes) {result.toString()}
        }
    }}
    case("allNewTemplateBytesCheckedOnExceptionPath") {
        val directories=listOf("batch","constraints-stage1").map {Path.of(BatchLiveTest::class.java.classLoader.getResource("templates/$it")!!.toURI())}
        val root=Files.createTempDirectory("reclazz-batch-all-")
        try {
            for(directory in directories) Files.walk(directory).use {paths->paths.filter(Files::isRegularFile).forEach {file->
                val target=root.resolve(directory.relativize(file));Files.createDirectories(target.parent);Files.copy(file,target)
            }}
            val checker=BatchFixtureBaseline(TestConfig(extPath=root.toString(),portFile="/unused"));checker.checkBytes()
            for(directory in directories) Files.walk(directory).use {paths->paths.filter(Files::isRegularFile).forEach {file->
                val target=root.resolve(directory.relativize(file));val bytes=Files.readAllBytes(target)
                try {Files.writeString(target,"dirty");check(runCatching {checker.checkBytes()}.isFailure)} finally {Files.write(target,bytes)}
            }}
            checker.checkBytes()
        } finally {root.toFile().deleteRecursively()}
    }
    println("BATCH_HARNESS: $cases cases, 0 failures");return cases
}
