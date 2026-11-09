package com.onurkat.reclazz.inttest

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val fixturePaths = listOf(
    "src/com/onurkat/reclazztest/services/TestService.java",
    "src/com/onurkat/reclazztest/services/HelperService.java",
    "src/com/onurkat/reclazztest/services/CacheTestService.java",
    "src/com/onurkat/reclazztest/services/SchedulerTestService.java",
    "src/com/onurkat/reclazztest/services/EventTestService.java",
    "src/com/onurkat/reclazztest/services/TestDao.java",
    "src/com/onurkat/reclazztest/services/TestStatus.java",
    "src/com/onurkat/reclazztest/interceptors/TestValidateInterceptor.java",
    "web/src/com/onurkat/reclazztest/controllers/TestController.java",
    "resources/impex/test-data.impex",
)

private fun committed(relative: String): ByteArray {
    val process = ProcessBuilder("git", "--no-optional-locks", "show", "HEAD:reclazztest/$relative").start()
    val bytes = process.inputStream.readBytes()
    check(process.waitFor() == 0) { "Cannot read committed synthetic fixture: $relative" }
    return bytes
}

private class EventServer : AutoCloseable {
    private val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
    val portFile: Path = Files.createTempFile("reclazz-contract-", ".port").also { Files.writeString(it, server.localPort.toString()) }
    private val output = AtomicReference<java.io.PrintWriter>()
    private var connection: java.net.Socket? = null
    init {
        Thread({
            connection = server.accept()
            output.set(java.io.PrintWriter(connection!!.getOutputStream(), true))
            emit("CONNECTED", "contract")
        }, "contract-agent-accept").apply { isDaemon = true; start() }
    }
    fun emit(level: String, message: String) {
        val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
        val writer = output.get() ?: return
        synchronized(writer) { writer.println("{\"level\":\"$level\",\"message\":\"$escaped\",\"timestamp\":\"contract\"}") }
    }
    override fun close() { connection?.close(); server.close(); Files.deleteIfExists(portFile) }
}

/** Baseline compatibility calls use the actual old public methods, not a simulated client. */
private fun cursor(client: AgentEventClient): Long = try {
    client.javaClass.getMethod("cursor").invoke(client) as Long
} catch (_: NoSuchMethodException) {
    val field = client.javaClass.getDeclaredField("totalReceived"); field.isAccessible = true
    (field.get(client) as AtomicLong).get()
}
private fun reload(client: AgentEventClient, owner: String, since: Long, timeout: Long = 200): Any? = try {
    client.javaClass.getMethod("waitForReload", java.lang.Long.TYPE, String::class.java, java.lang.Long.TYPE)
        .invoke(client, timeout, owner, since)
} catch (_: NoSuchMethodException) { client.waitForReload(timeout, owner) }
private fun writeReload(client: AgentEventClient, owner: String, since: Long): Any? = try {
    client.javaClass.getMethod("waitForSourceReload", java.lang.Long.TYPE, String::class.java, String::class.java, java.lang.Long.TYPE)
        .invoke(client, 200L, "TestService.java", owner, since)
} catch (_: NoSuchMethodException) { client.waitForReload(200, owner) }
/** Run against the real old method for the causal RED control, never a simulated client. */
private fun xmlOutcomes(client: AgentEventClient, file: String, since: Long, owners: Int, timeout: Long = 1000): List<*> = try {
    client.javaClass.getMethod("waitForSpringXmlOutcomes", java.lang.Long.TYPE, String::class.java,
        java.lang.Long.TYPE, Integer.TYPE).invoke(client, timeout, file, since, owners) as List<*>
} catch (_: NoSuchMethodException) {
    listOfNotNull(client.waitForSpringXml(timeout, file, since, allowNoChanges=true))
} catch (e: java.lang.reflect.InvocationTargetException) { throw e.targetException }
private fun stillWaiting(future: java.util.concurrent.CompletableFuture<*>) {
    try { future.get(120, TimeUnit.MILLISECONDS); error("Write completed before every owner outcome arrived") }
    catch (_: java.util.concurrent.TimeoutException) { }
}

private fun receive(client: AgentEventClient, count: Int) {
    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (client.getEvents().size < count && System.nanoTime() < end) Thread.sleep(5)
    check(client.getEvents().size >= count) { "Fake agent events were not received" }
}

private class ChildRun(val mode: String, val scenario: String, val timeout: Long = 1800) : AutoCloseable {
    val root = Files.createTempDirectory("reclazz-harness-contract-")
    val originals = fixturePaths.associateWith(::committed)
    private val eventServer = EventServer()
    private val executor = Executors.newScheduledThreadPool(3) { runnable -> Thread(runnable, "contract-schedule").apply { isDaemon = true } }
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val db = AtomicReference(if (mode.startsWith("impex")) "Reclazz Test Title v2" else "Reclazz Test Title v1")
    val baselineVisible = AtomicLong()
    val edited = AtomicLong()
    val helperReloaded = AtomicLong()
    val firstRequest = AtomicLong()
    val requests = AtomicInteger()
    val helperMtime: Long
    private var watching = true
    private lateinit var watcher: Thread
    init {
        for ((relative, bytes) in originals) {
            val path = root.resolve(relative); Files.createDirectories(path.parent); Files.write(path, bytes)
        }
        if (mode == "delayed-first") {
            val path = root.resolve(fixturePaths.first())
            Files.writeString(path, Files.readString(path).replace("pong-v1", "pong-v3"))
        }
        if (mode == "impex-delayed") Files.writeString(root.resolve(fixturePaths.last()), originals.getValue(fixturePaths.last()).toString(Charsets.UTF_8).replace("Title v1", "Title v2"))
        helperMtime = Files.getLastModifiedTime(root.resolve(fixturePaths[1])).toMillis()
        http.createContext("/") { exchange ->
            val n = requests.incrementAndGet(); val path = exchange.requestURI.path
            if (path.endsWith("/multi") || path.endsWith("/ping")) firstRequest.compareAndSet(0, System.nanoTime())
            val pair = when {
                path.endsWith("/impex-title") -> 200 to db.get()
                path.endsWith("/multi") -> 200 to "helper-v2:service-v2"
                mode == "poll-stale" -> 503 to "last-stale-body"
                mode == "poll-converges" && n == 1 -> 503 to "pong-v2"
                mode == "poll-converges" && n == 2 -> 200 to "old-body"
                mode == "exception" -> 500 to "deliberately-broken"
                else -> 200 to "pong-v2"
            }
            val bytes = pair.second.toByteArray(); exchange.sendResponseHeaders(pair.first, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
    }
    fun run(): Pair<Int, String> {
        val previous = fixturePaths.associateWith { Files.readAllBytes(root.resolve(it)) }.toMutableMap()
        watcher = Thread({
            while (watching) {
                for (relative in fixturePaths) {
                    val path = root.resolve(relative)
                    val bytes = runCatching { Files.readAllBytes(path) }.getOrNull() ?: continue
                    if (bytes.isEmpty() || bytes.contentEquals(previous.getValue(relative))) continue
                    previous[relative] = bytes
                    val text = bytes.toString(Charsets.UTF_8)
                    if (relative.endsWith(".java")) {
                        val name = path.fileName.toString(); val owner = Regex("package\\s+([\\w.]+)\\s*;").find(text)!!.groupValues[1] + "." + name.removeSuffix(".java")
                        val baseline = name == "TestService.java" && text.contains("pong-v1") && !text.contains("service-v2")
                        val delay = when {
                            mode == "delayed-first" && baseline -> 4000L
                            mode == "split" && name == "HelperService.java" && text.contains("helper-v2") -> 800L
                            else -> 10L
                        }
                        if (name == "TestService.java" && text.contains("pong-v2")) edited.compareAndSet(0, System.nanoTime())
                        executor.schedule({
                            eventServer.emit("COMPILE", "Compiled $name (1ms)")
                            if (name == "TestService.java" && text.contains("pong-v2")) {
                                if (mode == "own-refresh-failure") eventServer.emit("ERROR", "Failed to refresh Spring bean for $owner: owner 1/2: synthetic discovery failure")
                                if (mode == "unrelated-refresh-failure") eventServer.emit("ERROR", "Failed to refresh Spring bean for ${owner}Extra: synthetic unrelated failure")
                            }
                            if (name == "HelperService.java" && text.contains("helper-v2")) helperReloaded.set(System.nanoTime())
                            if (mode == "delayed-first" && baseline) baselineVisible.compareAndSet(0, System.nanoTime())
                            eventServer.emit("RELOAD", "Reloaded $owner (1ms)")
                        }, delay, TimeUnit.MILLISECONDS)
                    } else {
                        val v2 = text.contains("Title v2")
                        if (v2) edited.compareAndSet(0, System.nanoTime())
                        val delay = if (mode == "impex-delayed" && !v2) 4000L else 10L
                        executor.schedule({
                            if (mode == "impex-stale") {
                                eventServer.emit("OK", "ImpEx imported: test-data.impex") // an old uncorrelated outcome
                            } else if (mode == "impex-failure" && v2) {
                                eventServer.emit("ERROR", "Failed to import ImpEx test-data.impex: synthetic refusal")
                            } else {
                                db.set(if (v2) "Reclazz Test Title v2" else "Reclazz Test Title v1")
                                if (!v2) baselineVisible.compareAndSet(0, System.nanoTime())
                                eventServer.emit("OK", "ImpEx imported: test-data.impex")
                            }
                        }, delay, TimeUnit.MILLISECONDS)
                    }
                }
                Thread.sleep(5)
            }
        }, "contract-fixture-watch").apply { isDaemon = true; start() }
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val output = root.resolve("runner.log").toFile()
        val builder = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.onurkat.reclazz.inttest.ReclazzTestRunnerKt")
        builder.environment().putAll(mapOf("RECLAZZ_TEST_EXT_PATH" to root.toString(), "RECLAZZ_TEST_PORT_FILE" to eventServer.portFile.toString(),
            "RECLAZZ_TEST_BASE_URL" to "http://127.0.0.1:${http.address.port}", "RECLAZZ_TEST_ONLY" to scenario,
            "RECLAZZ_TEST_EVENT_TIMEOUT" to timeout.toString(), "RECLAZZ_TEST_SETTLE_DELAY" to "0", "RECLAZZ_TEST_HTTP_TIMEOUT" to "500"))
        val process = builder.redirectErrorStream(true).redirectOutput(output).start()
        check(process.waitFor(35, TimeUnit.SECONDS)) { process.destroyForcibly(); "Runner child exceeded contract deadline" }
        return process.exitValue() to output.readText()
    }
    fun assertClean() {
        for ((relative, expected) in originals) check(Files.readAllBytes(root.resolve(relative)).contentEquals(expected)) { "Restoration differs from HEAD: $relative" }
    }
    override fun close() {
        watching = false
        if (::watcher.isInitialized) watcher.join(1000)
        executor.shutdownNow(); http.stop(0); eventServer.close()
        root.toFile().deleteRecursively()
    }
}

fun main() {
    var failures = 0; var cases = 0
    fun case(name: String, test: () -> Unit) {
        cases++
        try { test(); println("PASS $name") }
        catch (e: Throwable) { failures++; println("FAIL $name: ${e.message}") }
    }
    fun clientCase(action: (EventServer, AgentEventClient) -> Unit) {
        EventServer().use { server ->
            val client = AgentEventClient(server.portFile.toString()); client.connect()
            try { client.clearEvents(); action(server, client) } finally { server.close(); client.disconnect() }
        }
    }
    val owner = "com.example.TestService"
    case("ownBeanRefreshFailureFailsTheScenario") {
        ChildRun("own-refresh-failure", "MethodBodyChangeTest").use {
            val result = it.run()
            check(result.first != 0 && result.second.contains("synthetic discovery failure")) { result.second }
            check(it.firstRequest.get() == 0L) { "HTTP body success hid the framework refresh failure" }
            it.assertClean()
        }
    }
    case("unrelatedErrorDoesNotFailTheScenario") {
        ChildRun("unrelated-refresh-failure", "MethodBodyChangeTest").use {
            val result = it.run(); check(result.first == 0) { result.second }; it.assertClean()
        }
    }
    case("ownRefreshErrorRequiresExactClassErrorLevelAndCompileReloadWindow") { clientCase { server,client ->
        val mark=cursor(client)
        server.emit("ERROR", "Failed to refresh Spring bean for $owner: previous write")
        server.emit("COMPILE", "Compiled TestService.java (1ms)")
        server.emit("ERROR", "Failed to refresh Spring bean for ${owner}Extra: another class")
        server.emit("WARN", "Failed to refresh Spring bean for $owner: wrong level")
        server.emit("RELOAD", "Reloaded $owner (1ms)")
        server.emit("ERROR", "Failed to refresh Spring bean for $owner: after reload"); receive(client,6)
        check(client.waitForSourceReload(200,"TestService.java",owner,mark)!=null)
    } }
    case("writeWaitsForNTerminalOutcomes") { clientCase { server, client ->
        val mark=cursor(client); val file="probe-spring.xml"
        val work=java.util.concurrent.CompletableFuture.supplyAsync { xmlOutcomes(client,file,mark,2) }
        try {
            server.emit("INFO","Spring XML $file reloaded: 1 change applied"); receive(client,1)
            stillWaiting(work)
        } finally { server.emit("INFO","Spring XML $file reloaded: 1 change applied") }
        check(work.get(2,TimeUnit.SECONDS).size==2)
    } }
    case("lateOwnerSummaryOfPreviousWriteIsNotTakenForTheNext") { clientCase { server,client ->
        val file="probe-spring.xml"; val mark=cursor(client)
        val previous=java.util.concurrent.CompletableFuture.supplyAsync { xmlOutcomes(client,file,mark,2) }
        server.emit("INFO","Spring XML $file reloaded: 3 changes applied"); receive(client,1)
        try { stillWaiting(previous) }
        finally { server.emit("INFO","Spring XML $file reloaded: 3 changes applied") }
        check(previous.get(2,TimeUnit.SECONDS).size==2)
        val nextMark=cursor(client)
        val next=java.util.concurrent.CompletableFuture.supplyAsync { xmlOutcomes(client,file,nextMark,2) }
        try {
            stillWaiting(next) // both previous-write summaries were consumed before this cursor
            server.emit("INFO","Spring XML $file reloaded: 1 change applied"); receive(client,3)
            stillWaiting(next)
        } finally { server.emit("INFO","Spring XML $file reloaded: 1 change applied") }
        check(next.get(2,TimeUnit.SECONDS).size==2)
    } }
    case("noChangeAndRefusalLinesCountAsOutcomesWithoutCountingWarningsTwice") { clientCase { server,client ->
        val file="probe-spring.xml"; val mark=cursor(client)
        server.emit("INFO","Spring XML $file reloaded: 0 changes applied, 1 require restart")
        server.emit("WARN","Restart required for:\n  - mapping (synthetic refusal)")
        server.emit("INFO","Spring XML ${file}Extra reloaded: 1 change applied"); receive(client,3)
        val work=java.util.concurrent.CompletableFuture.supplyAsync { xmlOutcomes(client,file,mark,2) }
        try { stillWaiting(work) }
        finally { server.emit("INFO","Spring XML $file, parsed OK, no live-applicable changes detected") }
        val result=work.get(2,TimeUnit.SECONDS);check(result.size==2)
        check(result.toString().contains("require restart") && result.toString().contains("no live-applicable"))
    } }
    case("singleOwnerFileNeedsOneOutcome") { clientCase { server,client ->
        val mark=cursor(client);server.emit("INFO","Spring XML probe-spring.xml, parsed OK, no live-applicable changes detected");receive(client,1)
        check(xmlOutcomes(client,"probe-spring.xml",mark,1).size==1)
    } }
    case("fewerOutcomesThanOwnersFailsWithSeenLines") { clientCase { server,client ->
        val mark=cursor(client);server.emit("INFO","Spring XML probe-spring.xml reloaded: 1 change applied");receive(client,1)
        val failure=runCatching { xmlOutcomes(client,"probe-spring.xml",mark,2,100) }.exceptionOrNull()
        check(failure!=null && failure.message.orEmpty().contains("1/2") && failure.message.orEmpty().contains("1 change applied")) { "Missing owner did not report its seen outcomes: $failure" }
    } }
    case("requestedStateBarrierReportsLastStatePerOwner") {
        val row="mappingIdentity=11;mappingType=GenericItem;targetBean=reclazzEditedMappingInterceptor;targetClass=com.onurkat.reclazztest.interceptors.AddedMappingInterceptor;registrySame=true;modelServices=1;registryCount=1;mappingCount=1;typeMappingCount=1;targetCount=1"
        val correct="owner=1;$row\nowner=2;${row.replace("Identity=11","Identity=22")}"
        val stale=correct.substringBefore("\n")+"\n"+correct.substringAfter("\n").replace("mappingType=GenericItem","mappingType=Product")
        val body=AtomicReference(stale)
        val http=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        http.createContext("/") { exchange ->
            val bytes=body.get().toByteArray();exchange.sendResponseHeaders(200,bytes.size.toLong());exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        try {
            val verifier=com.onurkat.reclazz.inttest.http.HttpVerifier("http://127.0.0.1:${http.address.port}",500)
            val failure=runCatching { com.onurkat.reclazz.inttest.tests.awaitMappingOwners(verifier,"/",250,2,"reclazzEditedMappingInterceptor","GenericItem") }.exceptionOrNull()
            check(failure!=null && failure.message.orEmpty().contains("owner=2") && failure.message.orEmpty().contains("mappingType=Product")) { "Missing last per-owner state: $failure" }
            body.set(correct)
            val identity=com.onurkat.reclazz.inttest.tests.awaitMappingOwners(verifier,"/",1000,2,"reclazzEditedMappingInterceptor","GenericItem")
            check(identity==mapOf("1" to "11","2" to "22"))
            val controls=listOf(correct.replace("owner=2","owner=1"), correct.substringBefore("\n"),
                correct.replace("registrySame=true","registrySame=false"),correct.replace("typeMappingCount=1","typeMappingCount=0"),
                correct.replace("targetCount=1","targetCount=2"),correct.replace("modelServices=1","modelServices=0"),
                correct.replace("mappingIdentity=22","mappingIdentity=33"))
            for(control in controls) check(com.onurkat.reclazz.inttest.tests.mappingOwnerStateFailure(
                com.onurkat.reclazz.inttest.http.HttpResult(200,control),2,"reclazzEditedMappingInterceptor","GenericItem",identity)!=null) { "Stale owner proof accepted: $control" }
        } finally { http.stop(0) }
    }
    case("springXmlOutcomeRequiresExactFilenameTerminalSummaryAndFreshCursor") { clientCase { server, client ->
        val file = "probe-spring.xml"
        server.emit("INFO", "Spring XML $file reloaded: 1 change applied"); receive(client,1)
        val mark = cursor(client)
        server.emit("INFO", "Spring XML ${file}Extra reloaded: 1 change applied")
        server.emit("INFO", "Spring XML changed: $file")
        server.emit("WARN", "Spring XML $file reloaded: 1 change applied"); receive(client,4)
        check(client.waitForSpringXml(100,file,mark)==null)
        server.emit("INFO", "Spring XML $file [context 1/2] reloaded: 2 changes applied"); receive(client,5)
        check(client.waitForSpringXml(100,file,mark)!=null)
        val restored = cursor(client)
        server.emit("INFO", "Spring XML $file, parsed OK, no live-applicable changes detected"); receive(client,6)
        check(client.waitForSpringXml(100,file,restored)==null)
        check(client.waitForSpringXml(100,file,restored,allowNoChanges=true)!=null)
    } }
    case("cursorSurvivesClearAndExcludesOlderEvents") { clientCase { server, client ->
        server.emit("RELOAD", "Reloaded $owner (1ms)"); receive(client, 1)
        val mark = cursor(client); check(reload(client, owner, mark) == null) { "Old event satisfied new cursor" }
        client.clearEvents(); check(cursor(client) >= mark) { "Clear reset sequence" }
    } }
    case("exactOwnerRejectsEffectsTextPrefixNameAndInnerOnlyEvents") { clientCase { server, client ->
        val mark = cursor(client)
        server.emit("RELOAD", "Reloaded com.example.Other (1ms): refreshed $owner")
        server.emit("RELOAD", "Reloaded ${owner}Extra (1ms)")
        server.emit("RELOAD", "Reloaded $owner (inner class) (1ms)"); receive(client, 3)
        check(reload(client, owner, mark) == null) { "Another owner or inner event matched" }
    } }
    case("batchReloadWithoutTimingStillMatchesItsExactOwner") { clientCase { server, client ->
        var mark = cursor(client)
        server.emit("RELOAD", "Reloaded $owner: bean reclazzTestService re-created")
        receive(client, 1)
        check(reload(client, owner, mark) != null) { "Batch body reload with effects was rejected" }
        mark = cursor(client)
        server.emit("STRUCTURAL_RELOAD", "Structural reload: $owner: bean re-created")
        receive(client, 2)
        check(reload(client, owner, mark) != null) { "Batch structural reload with effects was rejected" }
    } }
    case("reloadBeforeItsCompileDoesNotSatisfyTheWrite") { clientCase { server, client ->
        val mark = cursor(client); server.emit("RELOAD", "Reloaded $owner (1ms)")
        server.emit("COMPILE", "Compiled TestService.java (1ms)"); receive(client, 2)
        check(writeReload(client, owner, mark) == null) { "Reload preceding compile satisfied write" }
    } }
    case("restoreWaitsPastDelayedFirstEvent") {
        ChildRun("delayed-first", "MethodBodyChangeTest", 7000).use {
            val result = it.run(); check(result.first == 0) { result.second }
            check(it.baselineVisible.get() > 0 && it.edited.get() > it.baselineVisible.get()) { "Scenario started before delayed restore reload" }
        }
    }
    case("restoreWaitsForEveryWrittenClassWhenBatchSplits") {
        ChildRun("split", "MultiClassReloadTest").use {
            val result = it.run(); check(result.first == 0) { result.second }
            check(it.helperReloaded.get() > 0 && it.firstRequest.get() >= it.helperReloaded.get()) { "HTTP verified before helper reload" }
        }
    }
    case("unchangedSourceIsNotWrittenOrWaitedOn") {
        ChildRun("normal", "MethodBodyChangeTest").use {
            val result = it.run(); check(result.first == 0) { result.second }
            check(Files.getLastModifiedTime(it.root.resolve(fixturePaths[1])).toMillis() == it.helperMtime) { "Unchanged helper rewritten" }
        }
    }
    case("pollingRequiresStatusAndBodyAndReportsLastSeenWhenStale") {
        ChildRun("poll-stale", "MethodBodyChangeTest", 600).use {
            val result = it.run(); check(result.first != 0 && result.second.contains("503") && result.second.contains("last-stale-body")) { result.second }
        }
    }
    case("pollingSucceedsOnlyAfterConvergence") {
        ChildRun("poll-converges", "MethodBodyChangeTest").use {
            val result = it.run(); check(result.first == 0 && it.requests.get() >= 3) { result.second }
        }
    }
    case("impexDelayedRestoreOutcomeIsNotTakenForTheEdit") {
        ChildRun("impex-delayed", "ImpexAutoImportTest", 7000).use {
            val result = it.run(); check(result.first == 0) { result.second }
            check(it.baselineVisible.get() > 0 && it.edited.get() > it.baselineVisible.get()) { "V2 save began before v1 restore visible" }
        }
    }
    case("impexAlreadyVisibleV2DoesNotPass") {
        ChildRun("impex-stale", "ImpexAutoImportTest", 600).use {
            val result = it.run(); check(result.first != 0) { "Stale v2 passed without a baseline: ${result.second}" }
        }
    }
    case("impexFailureOutcomeFailsTheScenario") {
        ChildRun("impex-failure", "ImpexAutoImportTest", 2000).use {
            val result = it.run()
            check(result.first != 0 && result.second.contains("synthetic refusal")) { "Terminal refusal was not reported: ${result.second}" }
        }
    }
    case("finalRestorationIsByteIdenticalOnExceptionPath") {
        ChildRun("exception", "MethodBodyChangeTest", 600).use {
            val result = it.run(); check(result.first != 0) { result.second }; it.assertClean()
        }
    }
    println("HARNESS: $cases cases, $failures failures")
    check(failures == 0) { "$failures harness contract cases failed" }
}
