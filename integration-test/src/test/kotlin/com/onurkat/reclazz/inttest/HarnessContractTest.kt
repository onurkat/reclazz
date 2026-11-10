package com.onurkat.reclazz.inttest

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.tests.*
import com.onurkat.reclazz.inttest.client.AgentEvent
import com.onurkat.reclazz.inttest.tests.afterVersionConverges
import com.onurkat.reclazz.inttest.tests.latestVerificationSample
import com.onurkat.reclazz.inttest.tests.BatchLiveTest
import com.onurkat.reclazz.inttest.tests.saveResourceBytes
import com.onurkat.reclazz.inttest.tests.localizationChangeOutcome
import com.onurkat.reclazz.inttest.tests.localizationNoChangeOutcome
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.report.TestStatus
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
        if (mode in setOf("delayed-first","profile-skip","items-profile-skip","statics-profile-skip","solr-profile-skip","cms-profile-skip")) {
            val path = root.resolve(fixturePaths.first())
            Files.writeString(path, Files.readString(path).replace("pong-v1", "pong-v3"))
        }
        if (mode == "impex-delayed") Files.writeString(root.resolve(fixturePaths.last()), originals.getValue(fixturePaths.last()).toString(Charsets.UTF_8).replace("Title v1", "Title v2"))
        helperMtime = Files.getLastModifiedTime(root.resolve(fixturePaths[1])).toMillis()
        http.createContext("/") { exchange ->
            val n = requests.incrementAndGet(); val path = exchange.requestURI.path
            if (path.endsWith("/multi") || path.endsWith("/ping")) firstRequest.compareAndSet(0, System.nanoTime())
            val pair = when {
                mode.endsWith("profile-skip") && (path.endsWith("/batch/gates") || path.endsWith("/solr/gates") || path.endsWith("/cms/gates")) -> 200 to "{\"agentArgsFound\":false}"
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
        if(mode.endsWith("profile-skip")) {
            builder.environment().remove("RECLAZZ_TEST_ONLY")
            builder.environment()["RECLAZZ_TEST_PROFILE"]=when(mode) {"items-profile-skip" -> "items-attempt"; "statics-profile-skip" -> "statics-on"; "solr-profile-skip" -> "solr-attempt"; "cms-profile-skip" -> "cms-attempt"; else -> "codegen"}
        } else builder.environment().remove("RECLAZZ_TEST_PROFILE")
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
    fun versionCase(mode: String) {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val reads=AtomicInteger(); val jobs=AtomicInteger(); val current=AtomicReference("job-v1")
        val logs=mutableListOf<String>()
        server.createContext("/version") { exchange ->
            val n=reads.incrementAndGet()
            current.set(if(mode!="permanent" && n>=3) "job-v2" else "job-v1")
            val body="{\"version\":\"${current.get()}\"}".toByteArray()
            exchange.sendResponseHeaders(200,body.size.toLong());exchange.responseBody.use {it.write(body)}
        }
        server.start()
        try {
            val http=HttpVerifier("http://127.0.0.1:${server.address.port}",500)
            val result=runCatching {
                afterVersionConverges(http,if(mode=="permanent") 300 else 1500,"job-v2",
                    {http.get("/version",budgetMs=it)},
                    {if(mode=="pre-write") "applied receipt captured before write, completed after it" else "latest receipt sample"},
                    {check(current.get()=="job-v2");jobs.incrementAndGet();if(mode=="native-failure") error("native-execution-failed");"executed"},
                    {logs.add(it)})
            }
            if(mode=="permanent") {
                check(result.isFailure && jobs.get()==0)
                val message=result.exceptionOrNull()!!.message.orEmpty()
                check("job-v1" in message && "cause=UNVERIFIED" in message) {message}
            } else if(mode=="native-failure") {
                check(result.isFailure && jobs.get()==1 && result.exceptionOrNull()!!.message=="native-execution-failed")
            } else check(result.getOrThrow()=="executed" && jobs.get()==1 && reads.get()>=3)
            check(logs.all {"cause=UNVERIFIED" in it})
            if(mode=="pre-write") check(logs.any {"captured before write" in it && "job-v1" in it})
        } finally {server.stop(0)}
    }
    case("cronJobStaleThenCurrentReadRunsNativeJobOnlyOnce") {versionCase("converges")}
    case("cronJobPermanentlyStaleReadFailsWithLastValueAndNoNativeJob") {versionCase("permanent")}
    case("cronJobPreWriteCaptureCompletingAfterWriteDoesNotProveConvergence") {versionCase("pre-write")}
    case("cronJobNativeExecutionFailureIsNeverRetriedOrHiddenByPolling") {versionCase("native-failure")}
    case("cronJobInterleavedReceiptsRemainLatestOnlyUnverifiedSamples") {
        ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress()).use { socket ->
            val file=Files.createTempFile("cron-latest-contract-",".port")
            Files.writeString(file,socket.localPort.toString())
            val worker=java.util.concurrent.CompletableFuture.runAsync {
                socket.accept().use { connection ->
                    val line=connection.getInputStream().bufferedReader().readLine().split(' ')
                    check(line.size==4 && line[0]=="VERIFY")
                    val mapper=com.fasterxml.jackson.databind.ObjectMapper()
                    val out=java.io.PrintWriter(connection.getOutputStream(),true)
                    fun event(message:String) {out.println(mapper.writeValueAsString(mapOf("level" to "INFO","message" to message,"timestamp" to "contract")))}
                    // Both events predate the query answer. Only B is retained; no A receipt is invented.
                    event("Reloaded com.example.Job (A)");event("Reloaded com.example.Job (B)")
                    event("VERIFY_RESULT ${line[1]} "+mapper.writeValueAsString(mapOf("requestId" to line[1],"className" to line[2],
                        "expectedSha256" to line[3],"observedSha256" to "b".repeat(64),"status" to "mismatch","completedAt" to "contract-B")))
                }
            }
            try {
                val sample=latestVerificationSample(file.toString(),"com.example.Job",1000)
                check("b".repeat(64) in sample && "contract-B" in sample) {sample}
                val logs=mutableListOf<String>();var executed=0
                afterVersionConverges(HttpVerifier("http://127.0.0.1:1",100),100,"job-v2",
                    {com.onurkat.reclazz.inttest.http.HttpResult(200,"{\"version\":\"job-v2\"}")},
                    {sample},{executed++},{logs.add(it)})
                check(executed==1 && logs.single().contains("cause=UNVERIFIED") && logs.single().contains("latest-only"))
                check(!logs.single().contains("stale content compiled after write"))
                worker.get(2,TimeUnit.SECONDS)
            } finally {Files.deleteIfExists(file)}
        }
    }
    case("resourceForceIdenticalSaveWritesExactlyOnce") {
        val path=Files.createTempFile("force-save-contract-",".properties");val bytes="greeting=hello\n".toByteArray()
        try {
            Files.write(path,bytes);var writes=0
            check(saveResourceBytes(path,bytes,true) {p,b->writes++;Files.write(p,b)})
            check(writes==1 && Files.readAllBytes(path).contentEquals(bytes))
        } finally {Files.deleteIfExists(path)}
    }
    fun resourceSaveCase(mode:String, action:(EventServer,AgentEventClient,java.util.concurrent.CompletableFuture<com.onurkat.reclazz.inttest.report.TestResult>)->Unit) {
        val root=Files.createTempDirectory("resource-save-contract-");val path=root.resolve("labels_en.properties")
        val initial="greeting=hello\n".toByteArray();val commented=initial+"# same value, different bytes\n".toByteArray()
        Files.write(path,initial);Files.setLastModifiedTime(path,java.nio.file.attribute.FileTime.fromMillis(1))
        try {EventServer().use {server->
            val client=AgentEventClient(server.portFile.toString());client.connect();client.clearEvents()
            try {
                val old=java.util.Properties().also {it.load(initial.inputStream())}
                val next=java.util.Properties().also {it.load(commented.inputStream())}
                check(old==next && !initial.contentEquals(commented))
                server.emit("INFO","Localization file saved with no text changes: labels_en.properties");receive(client,1)
                val scenario=object:BatchLiveTest("resource-save-contract",TestConfig("http://127.0.0.1:1",root.toString(),server.portFile.toString(),eventTimeoutMs=900),client,HttpVerifier("http://127.0.0.1:1",100)) {
                    override fun scenario() {
                        val change=mode=="comment"
                        resourceWrite(path,if(change) commented else initial,listOf("labels_en.properties"),
                            {e->if(change) e.level=="OK" && e.message=="Type and enum names re-read from labels_en.properties." else
                                e.level=="INFO" && e.message=="Localization file saved with no text changes: labels_en.properties"},
                            forceWrite=mode=="force" || mode=="forbidden",acceptEvents={events->if(change) localizationChangeOutcome(events,"labels_en.properties") else localizationNoChangeOutcome(events,"labels_en.properties")})
                    }
                }
                val future=java.util.concurrent.CompletableFuture.supplyAsync {scenario.run()}
                if(mode!="default") {
                    val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
                    while(Files.getLastModifiedTime(path).toMillis()==1L && System.nanoTime()<end) Thread.sleep(5)
                    check(Files.getLastModifiedTime(path).toMillis()!=1L) {"The explicit save was not performed"}
                    check(Files.readAllBytes(path).contentEquals(if(mode=="comment") commented else initial))
                }
                action(server,client,future)
                val result=future.get(2,TimeUnit.SECONDS)
                check(result.status==if(mode=="forbidden") TestStatus.FAIL else TestStatus.PASS) {result.toString()}
                if(mode=="forbidden") check("Byte-change path appeared" in result.notes) {result.toString()}
                if(mode=="default") check(Files.getLastModifiedTime(path).toMillis()==1L && Files.readAllBytes(path).contentEquals(initial)) {"Default identical save was performed"}
            } finally {client.disconnect()}
        }} finally {root.toFile().deleteRecursively()}
    }
    case("resourceForceIdenticalSaveWaitsForFreshExactNoChangeTerminal") {
        resourceSaveCase("force") {s,_,future->
            stillWaiting(future) // the old pre-write terminal must not pass
            s.emit("INFO","Localization file saved with no text changes: other_en.properties");stillWaiting(future)
            s.emit("INFO","Localization file saved with no text changes: labels_en.properties")
        }
    }
    case("resourceCommentWithEqualValuesRequiresOrderedChangePath") {
        resourceSaveCase("comment") {s,_,future->
            s.emit("OK","Type and enum names re-read from labels_en.properties.");stillWaiting(future)
            s.emit("INFO","Localization file changed: labels_en.properties");stillWaiting(future)
            s.emit("OK","Type and enum names re-read from labels_en.properties.")
        }
    }
    case("resourceIdenticalSaveRejectsInterveningChangePath") {
        resourceSaveCase("forbidden") {s,_,_->
            s.emit("INFO","Localization file changed: labels_en.properties")
            s.emit("INFO","Localization file saved with no text changes: labels_en.properties")
        }
    }
    case("resourceDefaultIdenticalSaveSkipsWriteAndTerminalWait") {resourceSaveCase("default") {_,_,_->}}
    fun gateCase(body:String,required:Map<String,Boolean>,expected:TestStatus,reason:String?=null,breakAfterWrite:Boolean=false) {
        val root=Files.createTempDirectory("profile-gate-contract-");val target=root.resolve("owned");Files.writeString(target,"baseline")
        var attempts=0
        try {
            val result=runGatedProfile("profile-contract",{profileGateReason(batchJson.readTree(body),required)}) {
                attempts++
                withRestore({Files.writeString(target,"changed");if(breakAfterWrite) error("after-write failure")}) {Files.writeString(target,"baseline")}
            }
            check(result.status==expected) {result.toString()}
            check(attempts==if(expected==TestStatus.SKIP) 0 else 1)
            check(Files.readString(target)=="baseline")
            if(reason!=null) check(reason in result.notes) {result.toString()}
        } finally {root.toFile().deleteRecursively()}
    }
    case("profileMainSkipDoesNotRestoreDirtyFixtureBeforePreflight") {
        ChildRun("profile-skip","unused").use {child ->
            val before=fixturePaths.associateWith {Files.readAllBytes(child.root.resolve(it))}
            val result=child.run()
            check(result.first==0 && "SKIP" in result.second && "agentArgsFound=false" in result.second) {result.second}
            for((path,bytes) in before) check(Files.readAllBytes(child.root.resolve(path)).contentEquals(bytes)) {"Preflight wrote $path"}
        }
    }
    case("itemsMainSkipDoesNotRestoreDirtyFixtureBeforePreflight") {
        ChildRun("items-profile-skip","unused").use {child ->
            val before=fixturePaths.associateWith {Files.readAllBytes(child.root.resolve(it))}
            val result=child.run()
            check(result.first==0 && "SKIP" in result.second && "agentArgsFound=false" in result.second) {result.second}
            for((path,bytes) in before) check(Files.readAllBytes(child.root.resolve(path)).contentEquals(bytes)) {"Items preflight wrote $path"}
        }
    }
    case("staticsMainSkipDoesNotRestoreDirtyFixtureBeforePreflight") {
        ChildRun("statics-profile-skip","unused").use {child ->
            val before=fixturePaths.associateWith {Files.readAllBytes(child.root.resolve(it))}
            val result=child.run()
            check(result.first==0 && "SKIP" in result.second && "agentArgsFound=false" in result.second) {result.second}
            for((path,bytes) in before) check(Files.readAllBytes(child.root.resolve(path)).contentEquals(bytes)) {"Static preflight wrote $path"}
        }
    }
    case("profileEnabledAttemptsOnce") {gateCase("{\"flag\":true}",mapOf("flag" to true),TestStatus.PASS)}
    case("profileDisabledSkipsWithoutWrite") {gateCase("{\"flag\":false}",mapOf("flag" to true),TestStatus.SKIP,"flag=false")}
    case("profileMissingFieldSkipsWithoutWrite") {gateCase("{}",mapOf("flag" to true),TestStatus.SKIP,"flag missing")}
    case("profileMissingAgentArgumentSkipsWithoutWrite") {gateCase("{\"agentArgsFound\":false}",mapOf("agentArgsFound" to true),TestStatus.SKIP,"agentArgsFound=false")}
    case("profileWrongTypeIsNotFalseBoolean") {gateCase("{\"flag\":\"false\"}",mapOf("flag" to false),TestStatus.SKIP,"not boolean")}
    case("profileMismatchedOnValueSkipsOffProfile") {gateCase("{\"flag\":true}",mapOf("flag" to false),TestStatus.SKIP,"requires false")}
    case("profileFailureAfterWriteIsFailAndRestores") {gateCase("{\"flag\":true}",mapOf("flag" to true),TestStatus.FAIL,"after-write failure",true)}
    case("profileRestoreFailureIsFail") {
        val result=runGatedProfile("restore-contract",{null}) {withRestore({Unit}) {error("restore failure")}}
        check(result.status==TestStatus.FAIL && "restore failure" in result.notes)
    }
    case("defaultMembershipRetainsCommitted44") {requireDefaultMembership(committedDefaultMembership.toList());check(committedDefaultMembership.size==44)}
    case("defaultMembershipRejectsMissingOrika") {check(runCatching {requireDefaultMembership((committedDefaultMembership-"OrikaPropertyRollbackLiveTest").toList())}.isFailure)}
    case("defaultMembershipRejectsSameCountReplacement") {check(runCatching {requireDefaultMembership((committedDefaultMembership-"OrikaPropertyRollbackLiveTest"+"FakeTest").toList())}.isFailure)}
    val generatedOwners=setOf("com.example.Data","com.example.Dto")
    fun event(level:String,message:String,n:Long)=AgentEvent(level,message,"contract",n)
    val codegenStart=event("INFO","Beans XML changed: reclazztest-beans.xml [reclazztest]",2)
    val codegenOk=event("OK","Codegen regen+compile done for reclazztest, DTOs ready (3s). Structural reloader is applying new bytecode now.",4)
    val codegenApplies=listOf(event("STRUCTURAL_RELOAD","Structural reload: com.example.Data (v1)",3),event("STRUCTURAL_RELOAD","Structural reload: com.example.Dto (v1)",5))
    case("codegenAntSuccessAloneDoesNotPass") {check(codegenReceipt(listOf(codegenStart,codegenOk),1,generatedOwners)==null)}
    case("codegenPartialOrWrongOwnerDoesNotPass") {check(codegenReceipt(listOf(codegenStart,codegenOk,codegenApplies[0],event("RELOAD","Reloaded other.Dto",6)),1,generatedOwners)==null)}
    case("codegenStaleReceiptsDoNotPass") {check(codegenReceipt(listOf(codegenStart,codegenOk)+codegenApplies,5,generatedOwners)==null)}
    case("codegenBothAsyncAppliesAndAntPass") {check(codegenReceipt(listOf(codegenStart,codegenOk)+codegenApplies,1,generatedOwners)?.applies?.size==2)}
    case("codegenWrongExtensionDoesNotPass") {check(codegenReceipt(listOf(codegenStart.copy(message="Beans XML changed: other-beans.xml [other]"),codegenOk)+codegenApplies,1,generatedOwners)==null)}
    case("codegenErrorCannotBeHiddenBySuccess") {check(runCatching {codegenReceipt(listOf(codegenStart,codegenOk,event("ERROR","Codegen: ant exit code 1",6))+codegenApplies,1,generatedOwners)}.isFailure)}
    val itemsStart=event("INFO","Items XML changed: reclazztest-items.xml [reclazztest]",2)
    val itemsAnt=event("OK","Codegen regen+compile done for reclazztest, model classes ready (3s). Structural reloader is applying new bytecode now.",4)
    val itemsApply=event("STRUCTURAL_RELOAD","Structural reload: com.example.Model (v1)",3)
    val itemsSchema=event("OK","items.xml changed, ran Update Running System (schema only); the new attributes and enum values are live. No restart, no HAC click.",5)
    val itemsEvents=listOf(itemsStart,itemsAnt,itemsApply,itemsSchema)
    case("itemsAntAndApplyWithoutSchemaDoNotPass") {check(!itemsReceipt(itemsEvents-itemsSchema,1,"com.example.Model"))}
    case("itemsSchemaAndAntWithoutApplyDoNotPass") {check(!itemsReceipt(itemsEvents-itemsApply,1,"com.example.Model"))}
    case("itemsSchemaAndApplyWithoutAntDoNotPass") {check(!itemsReceipt(itemsEvents-itemsAnt,1,"com.example.Model"))}
    case("itemsStaleAndWrongFileDoNotPass") {
        check(!itemsReceipt(itemsEvents,5,"com.example.Model"))
        check(!itemsReceipt(itemsEvents.map{if(it==itemsStart) it.copy(message="Items XML changed: other-items.xml [other]") else it},1,"com.example.Model"))
    }
    case("itemsWrongOwnerDoesNotPass") {check(!itemsReceipt(itemsEvents,1,"other.Model"))}
    case("itemsAsyncApplySchemaAndAntPass") {check(itemsReceipt(itemsEvents,1,"com.example.Model"))}
    case("itemsFailedSchemaWarnCannotPass") {check(runCatching {itemsReceipt(itemsEvents+event("WARN","Automatic Update Running System could not run",6),1,"com.example.Model")}.isFailure)}
    case("itemsInnerOnlyApplyDoesNotPass") {check(!itemsReceipt(itemsEvents.map{if(it==itemsApply) it.copy(message="Structural reload: com.example.Model (inner class)") else it},1,"com.example.Model"))}
    case("itemsUnscopedErrorCannotPass") {check(runCatching {itemsReceipt(itemsEvents+event("ERROR","unscoped compile failure",6),1,"com.example.Model")}.isFailure)}
    val staticsOwner="com.example.StaticProbe"
    val staticReset=event("INFO","Reset static $staticsOwner.CODES (initializer changed)",2)
    val staticOff=event("WARN","Static $staticsOwner.CODES initializer changed, not reset: option-off (enable resetStatics=true); restart to apply",2)
    val countRefusal=event("WARN","Static $staticsOwner.COUNT initializer changed, not reset: became-constant (callers inline it); restart to apply",2)
    val callRefusal=event("WARN","Static $staticsOwner.REFUSED initializer changed, not reset: unapproved-call; restart to apply",3)
    case("staticOnRequiresExactFreshFieldOutcome") {
        check(staticResetReceipt(listOf(staticReset),1,staticsOwner,true,false))
        check(!staticResetReceipt(listOf(staticReset),2,staticsOwner,true,false))
        check(!staticResetReceipt(listOf(staticReset),1,"other.Owner",true,false))
        check(!staticResetReceipt(listOf(staticReset.copy(message=staticReset.message.replace("CODES","OTHER"))),1,staticsOwner,true,false))
    }
    case("staticOffRequiresAdviceAndNeverAcceptsReset") {
        check(staticResetReceipt(listOf(staticOff),1,staticsOwner,false,false))
        check(!staticResetReceipt(listOf(staticReset),1,staticsOwner,false,false))
        check(runCatching {staticResetReceipt(listOf(staticOff),1,staticsOwner,true,false)}.isFailure)
    }
    case("staticBothRefusalsRequired") {
        check(staticResetReceipt(listOf(countRefusal,callRefusal),1,staticsOwner,true,true))
        check(!staticResetReceipt(listOf(countRefusal),1,staticsOwner,true,true))
        check(!staticResetReceipt(listOf(callRefusal),1,staticsOwner,true,true))
    }
    case("staticUnexpectedWarningOrErrorCannotPass") {
        for(extra in listOf(event("WARN","Static $staticsOwner.CODES initializer changed, not reset: missing-baseline; restart to apply",3),event("ERROR","Compile failed",3)))
            check(runCatching {staticResetReceipt(listOf(staticReset,extra),1,staticsOwner,true,false)}.isFailure)
    }
    case("solrMainSkipLeavesDirtyDefaultFixtureUntouched") {
        ChildRun("solr-profile-skip","unused").use {child ->
            val before=fixturePaths.associateWith {Files.readAllBytes(child.root.resolve(it))}
            val result=child.run()
            check(result.first==0 && "SKIP" in result.second && "agentArgsFound=false" in result.second) {result.second}
            for((path,bytes) in before) check(Files.readAllBytes(child.root.resolve(path)).contentEquals(bytes))
        }
    }
    val solrTag="owner1/config1/type1"
    val solrDone=event("INFO","SAP Solr $solrTag: FINISHED partial properties=1 documents=1 durationMs=9; finished without native errors.",4)
    case("solrRequiresFreshExactOwnerConfigAndType") {
        check(solrReindexReceipt(listOf(solrDone),3,solrTag))
        check(!solrReindexReceipt(listOf(solrDone),4,solrTag))
        for(tag in listOf("owner2/config1/type1","owner1/config2/type1","owner1/config1/type2"))
            check(!solrReindexReceipt(listOf(solrDone),3,tag))
    }
    case("solrRequiresPartialSinglePropertyAndDocument") {
        for(message in listOf(solrDone.message.replace("documents=1","documents=0"),solrDone.message.replace("properties=1","properties=2"),solrDone.message.replace("partial properties=1","whole-type")))
            check(!solrReindexReceipt(listOf(solrDone.copy(message=message)),3,solrTag))
    }
    case("solrUnverifiedFailedAndNoDocumentsCannotPass") {
        for(message in listOf("FINISHED_UNVERIFIED partial properties=1 documents=1 durationMs=1", "FAILED (native-operation-failed)","no stored documents; no indexing call."))
            check(runCatching {solrReindexReceipt(listOf(solrDone.copy(message="SAP Solr $solrTag: $message")),3,solrTag)}.isFailure)
    }
    case("solrUnscopedErrorsCannotHideBehindFinished") {
        check(runCatching {solrReindexReceipt(listOf(solrDone,event("ERROR","native compile failed",5)),3,solrTag)}.isFailure)
    }
    val solrNonce="reclazz-solr-00000000-0000-0000-0000-000000000001"
    fun proof(value:String)=batchJson.readTree("""{"nonce":"$solrNonce","pk":"12345","tag":"$solrTag",
        "target":{"core":"$solrNonce-target","qualifier":"default","uniqueKey":"id","documentId":"target-id","pk":"12345",
        "fields":{"changedValue":"changedValue_string","stableValue":"stableValue_string"},"values":{"changedValue":"$value","stableValue":"stable-v1"},
        "document":{"id":"target-id","pk":12345,"changedValue_string":"$value","stableValue_string":"stable-v1"}},
        "control":{"core":"$solrNonce-control","qualifier":"default","uniqueKey":"id","documentId":"control-id","pk":"12345",
        "fields":{"controlValue":"controlValue_string"},"values":{"controlValue":"control-v1"},"document":{"id":"control-id","pk":12345,"controlValue_string":"control-v1","_version_":7}}}""")
    case("solrAcceptsRealStoredFieldShapeAndStableControl") {requireSolrStored(proof("v1"),solrNonce,"v1");requireSolrStored(proof("v2"),solrNonce,"v2",proof("v1"))}
    case("solrStaleMissingOrWrongPkStoredDataIsRejected") {
        val good=proof("v2").toString()
        for(text in listOf(proof("v1").toString(),good.replace("\"pk\":12345","\"pk\":999"),good.replace("\"changedValue\":\"v2\"","\"changedValue\":null")))
            check(runCatching {requireSolrStored(batchJson.readTree(text),solrNonce,"v2",proof("v1"))}.isFailure)
    }
    case("solrChangedStableFieldOrControlDocumentIsRejected") {
        for(text in listOf(proof("v2").toString().replace("stable-v1","stable-v2"),proof("v2").toString().replace("\"_version_\":7","\"_version_\":8")))
            check(runCatching {requireSolrStored(batchJson.readTree(text),solrNonce,"v2",proof("v1"))}.isFailure)
    }
    case("solrPhysicalFieldMappingCannotChangeDuringReload") {
        val changed=proof("v2").toString().replace("changedValue_string","other_string")
        check(runCatching {requireSolrStored(batchJson.readTree(changed),solrNonce,"v2",proof("v1"))}.isFailure)
    }
    case("solrFixtureTemplatesAreImmutable") {
        val root=Files.createTempDirectory("reclazz-solr-contract-")
        try {
            for(rel in SolrFixtureBaseline.relatives) {val path=root.resolve(rel);Files.createDirectories(path.parent);Files.write(path,SolrFixtureBaseline.original(rel))}
            val baseline=SolrFixtureBaseline(root);baseline.checkBytes()
            Files.writeString(root.resolve("project.properties"),"dirty")
            check(runCatching {baseline.checkBytes()}.isFailure)
        } finally {root.toFile().deleteRecursively()}
    }
    case("cmsMainSkipLeavesDirtyDefaultFixtureUntouched") {
        ChildRun("cms-profile-skip","unused").use { child ->
            val before=fixturePaths.associateWith { Files.readAllBytes(child.root.resolve(it)) }
            val result=child.run()
            check(result.first==0 && "SKIP" in result.second && "agentArgsFound=false" in result.second) { result.second }
            for((path,bytes) in before) check(Files.readAllBytes(child.root.resolve(path)).contentEquals(bytes))
        }
    }
    val cmsNonce="reclazz-cms-00000000-0000-0000-0000-000000000001"
    fun cmsHtml(marker:String)="""<!doctype html><html><body><div id="reclazz-cms-proof" data-nonce="$cmsNonce" data-marker="$marker" data-unsaved="true"><span class="content">reclazz-cms-content</span></div></body></html>"""
    case("cmsReadsRealMimeAndRejectsJsonPlainAndStaleHtml") {
        val body=AtomicReference(cmsHtml("cms-v2"));val mime=AtomicReference("text/html;charset=UTF-8");val status=AtomicInteger(200)
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { exchange ->
            val bytes=body.get().toByteArray();exchange.responseHeaders.set("Content-Type",mime.get())
            exchange.sendResponseHeaders(status.get(),bytes.size.toLong());exchange.responseBody.use { it.write(bytes) }
        };server.start()
        try {
            val verifier=HttpVerifier("http://127.0.0.1:${server.address.port}",1000)
            fun verify() { val (response,type)=verifier.getWithContentType("/");requireCmsHtml(response,type,cmsNonce,"cms-v2") }
            verify()
            for(type in listOf("application/json","text/plain")) { mime.set(type);check(runCatching { verify() }.isFailure) }
            mime.set("text/html");body.set(cmsHtml("cms-v1"));check(runCatching { verify() }.isFailure)
            body.set(cmsHtml("cms-v2"));status.set(500);check(runCatching { verify() }.isFailure)
        } finally { server.stop(0) }
    }
    case("cmsWrongNonceContentPersistedAndDuplicateProofFail") {
        val good=cmsHtml("cms-v2")
        for(body in listOf(good.replace(cmsNonce,"foreign"),good.replace("reclazz-cms-content","other"),good.replace("data-unsaved=\"true\"","data-unsaved=\"false\""),good+good))
            check(runCatching { requireCmsHtml(com.onurkat.reclazz.inttest.http.HttpResult(200,body),"text/html",cmsNonce,"cms-v2") }.isFailure)
    }
    val cmsOwner="com.onurkat.reclazztestcms.controllers.ReclazzParagraphComponentController"
    val cmsFile="ReclazzParagraphComponentController.java"
    val cmsCompile=event("COMPILE","Compiled $cmsFile (9ms)",2)
    val cmsReload=event("RELOAD","Reloaded $cmsOwner (v1)",3)
    case("cmsReceiptRequiresFreshCompileThenExactOwner") {
        check(cmsReloadReceipt(listOf(cmsCompile,cmsReload),1,cmsFile,cmsOwner)==cmsReload)
        check(cmsReloadReceipt(listOf(cmsCompile,cmsReload),3,cmsFile,cmsOwner)==null)
        check(cmsReloadReceipt(listOf(cmsCompile,cmsReload.copy(sequence=1)),0,cmsFile,cmsOwner)==null)
        for(owner in listOf(cmsOwner+"Other",cmsOwner+"$"+"Inner"))
            check(cmsReloadReceipt(listOf(cmsCompile,cmsReload.copy(message="Reloaded $owner (v1)")),1,cmsFile,cmsOwner)==null)
    }
    case("cmsWrongFileInnerOnlyAndErrorsCannotPass") {
        check(cmsReloadReceipt(listOf(cmsCompile.copy(message="Compiled Other.java"),cmsReload),1,cmsFile,cmsOwner)==null)
        check(cmsReloadReceipt(listOf(cmsCompile,cmsReload.copy(message="Reloaded $cmsOwner (inner class)")),1,cmsFile,cmsOwner)==null)
        check(runCatching { cmsReloadReceipt(listOf(cmsCompile,cmsReload,event("ERROR","native failure",4)),1,cmsFile,cmsOwner) }.isFailure)
    }
    case("cmsNativeFactsRejectChangedOrMissingComponentCount") {
        val good=batchJson.readTree("""{"paragraphCount":0,"modelType":"CMSParagraphComponent","tenant":"master"}""")
        requireCmsFacts(good,0)
        for(body in listOf("""{"paragraphCount":1,"modelType":"CMSParagraphComponent","tenant":"master"}""","""{"modelType":"CMSParagraphComponent","tenant":"master"}""","""{"paragraphCount":0,"modelType":"CMSParagraphComponent","tenant":"junit"}"""))
            check(runCatching { requireCmsFacts(batchJson.readTree(body),0) }.isFailure)
    }
    case("cmsTypedGatesSkipBeforeAnyAttemptWrite") {
        val required=linkedMapOf("agentArgsFound" to true,"autoCompile" to true,"autoSolrReindex" to false,"cmsModelAvailable" to true,"nativeBeansPresent" to true,"nativeSuperclass" to true,"inheritedHandler" to true,"jspViewResolver" to true)
        for(key in required.keys) {
            val gates=batchJson.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(required)
            for(kind in 0..2) {
                val candidate=gates.deepCopy()
                when(kind) {0->candidate.remove(key);1->candidate.put(key,!required.getValue(key));else->candidate.put(key,"true")}
                var writes=0
                val result=runGatedProfile("cms",{profileGateReason(candidate,required)},{writes++})
                check(result.status==TestStatus.SKIP && writes==0)
            }
        }
    }
    case("cmsFailureAfterAttemptIsFailNeverSkip") {
        var writes=0
        val result=runGatedProfile("cms",{null},{writes++;error("render failed after edit")})
        check(writes==1 && result.status==TestStatus.FAIL)
    }
    case("cmsOwnedTemplatesExcludeNativeCommonwebBuildOutputs") {
        val root=Files.createTempDirectory("reclazz-cms-contract-")
        try {
            for(relative in CmsFixtureBaseline.relatives) { val path=root.resolve(relative);Files.createDirectories(path.parent);Files.write(path,CmsFixtureBaseline.original(relative)) }
            val baseline=CmsFixtureBaseline(root);baseline.checkBytes();check(CmsFixtureBaseline.relatives.size==9)
            Files.writeString(root.resolve("project.properties"),"dirty");check(runCatching { baseline.checkBytes() }.isFailure)
            check(CmsFixtureBaseline.relatives.none { "commonwebsrc" in it || it.endsWith(".jar") })
        } finally { root.toFile().deleteRecursively() }
    }
    cases += com.onurkat.reclazz.inttest.tests.runBatchHarnessContracts()
    println("HARNESS: $cases cases, $failures failures")
    check(failures == 0) { "$failures harness contract cases failed" }
}
