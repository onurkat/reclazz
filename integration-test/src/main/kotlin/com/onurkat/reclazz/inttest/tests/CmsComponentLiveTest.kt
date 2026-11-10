package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEvent
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpResult
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal fun requireCmsHtml(response: HttpResult, contentType: String?, nonce: String, marker: String) {
    check(response.statusCode == 200 && contentType?.substringBefore(';')?.trim()?.lowercase() == "text/html") { "CMS response is not HTTP200 text/html" }
    check(response.body.trimStart().startsWith("<!doctype html>", ignoreCase = true)) { "CMS did not render an HTML document" }
    val pattern = Regex("""<div id="reclazz-cms-proof" data-nonce="([^"]+)" data-marker="([^"]+)" data-unsaved="([^"]+)"><span class="content">([^<]*)</span></div>""")
    val matches = pattern.findAll(response.body).toList()
    check(matches.size == 1) { "CMS render missing/duplicate proof element" }
    val fields = matches.single().groupValues
    check(fields[1] == nonce && fields[2] == marker && fields[3] == "true" && fields[4] == "reclazz-cms-content") { "CMS stale/wrong nonce, marker, content or persisted component" }
}

internal fun cmsReloadReceipt(events: List<AgentEvent>, after: Long, file: String, owner: String): AgentEvent? {
    val seen = events.filter { it.sequence > after }
    check(seen.none { it.level == "ERROR" }) { "CMS reload reported an error" }
    val compiled = seen.firstOrNull { it.level == "COMPILE" && (it.message == "Compiled $file" || it.message.startsWith("Compiled $file (")) } ?: return null
    return seen.firstOrNull {
        val prefix = when (it.level) { "RELOAD" -> "Reloaded "; "STRUCTURAL_RELOAD" -> "Structural reload: "; else -> return@firstOrNull false }
        val tail = it.message.removePrefix(prefix)
        it.sequence > compiled.sequence && it.message.startsWith(prefix) && tail.takeWhile { c -> c != ' ' && c != ':' } == owner &&
            !tail.substringAfter(' ', "").startsWith("(inner class)")
    }
}

internal fun requireCmsFacts(facts: JsonNode, baselineCount: Long? = null) {
    check(facts.path("paragraphCount").isIntegralNumber && facts.path("paragraphCount").asLong() >= 0 &&
        facts.path("modelType").asText() == "CMSParagraphComponent" && facts.path("tenant").asText() == "master") { "CMS native model/count/tenant proof missing" }
    if (baselineCount != null) check(facts.path("paragraphCount").asLong() == baselineCount) { "Unsaved CMS probe changed persisted component count" }
}

/** Native inherited request-component/JSP branch only; catalog/UID/page slots stay open. */
class CmsComponentLiveTest(c: TestConfig, a: AgentEventClient, h: HttpVerifier) :
    BaseTest("CMS inherited request-component JSP (outer recovery required)", c, a, h) {
    private val endpoint = "/reclazztestcms/v2/test/batch/cms"
    private val relative = "web/src/com/onurkat/reclazztestcms/controllers/ReclazzParagraphComponentController.java"
    private val owner = "com.onurkat.reclazztestcms.controllers.ReclazzParagraphComponentController"
    private lateinit var fixture: CmsFixtureBaseline
    private lateinit var nonce: String
    private var baselineCount = -1L
    private var deadline = 0L
    private fun left() = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).also { check(it > 0) { "CMS render/restore deadline" } }
    private fun facts() = json(httpVerifier.get("$endpoint/facts", emptyMap(), config.httpTimeoutMs))
    private fun digest(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
    private fun preflight(): String? {
        profileGateReason(json(httpVerifier.get("$endpoint/gates", emptyMap(), config.httpTimeoutMs)), linkedMapOf(
            "agentArgsFound" to true, "agentOptionsMatchConfig" to true, "autoCompile" to true,
            "autoSolrReindex" to false, "resetStatics" to false, "autoUpdateRunningSystem" to false, "impexAllowRemove" to false,
            "profileExtensionLoaded" to true, "cmsExtensionsLoaded" to true, "profileWatched" to true, "masterTenant" to true,
            "cmsModelAvailable" to true, "cmsTypeAvailable" to true, "nativeBeansPresent" to true,
            "nativeSuperclass" to true, "inheritedHandler" to true, "jspViewResolver" to true))?.let { return it }
        val repo = Path.of(config.extPath).toRealPath().parent
        val home = System.getenv("RECLAZZ_TEST_HYBRIS_HOME")?.let { Path.of(it).toRealPath() } ?: return "Clean CMS home missing"
        if (home != repo.parent.resolve("reclazz-sap/hybris") || !Files.isSameFile(home.resolve("bin/custom/reclazztest"), Path.of(config.extPath))) return "Foreign clean home"
        fixture = CmsFixtureBaseline(repo.resolve("reclazztestcms")); fixture.checkBytes()
        if (!Files.isSymbolicLink(home.resolve("bin/custom/reclazztestcms")) || !Files.isSameFile(home.resolve("bin/custom/reclazztestcms"), fixture.root)) return "Foreign CMS symlink"
        nonce = System.getenv("RECLAZZ_CMS_NONCE") ?: return "Owned CMS nonce missing"
        if (!nonce.matches(Regex("reclazz-cms-[a-f0-9-]{36}"))) return "Invalid owned CMS nonce"
        val recovery = System.getenv("RECLAZZ_CMS_RECOVERY")?.let { Path.of(it) } ?: return "Stopped CMS recovery missing"
        val state = batchJson.readTree(Files.readString(recovery.resolve("state.json")))
        if (state.path("home").asText() != home.toString() || state.path("snapshotsVerified").asBoolean() != true || state.path("targets").size() != 17) return "CMS recovery identity/roots missing"
        for (kind in state.path("targets").fieldNames()) {
            val snap = recovery.resolve(kind); val files = snap.resolve("files")
            val manifest = batchJson.readTree(Files.readString(snap.resolve("manifest.json")))
            val actual = Files.walk(files).use { paths -> paths.filter { Files.isRegularFile(it) }.map { files.relativize(it).toString() }.toList().toSet() }
            if (actual.isEmpty() || actual != manifest.fieldNames().asSequence().toSet()) return "CMS recovery membership mismatch"
            for (name in actual) {
                val path = files.resolve(name).normalize()
                if (!path.startsWith(files) || Files.isSymbolicLink(path) || Files.size(path) != manifest[name]["size"].asLong() || digest(path) != manifest[name]["sha256"].asText()) return "CMS recovery checksum mismatch"
            }
        }
        val baseline = facts(); requireCmsFacts(baseline); baselineCount = baseline.path("paragraphCount").asLong()
        val provenance = batchJson.readTree(Files.readString(recovery.resolve("native-provenance.json")))
        val nativeSource = home.resolve("bin/modules/core-accelerator/deprecated/acceleratorstorefrontcommons/commonweb/src/de/hybris/platform/acceleratorstorefrontcommons/controllers/cms/AbstractCMSComponentController.java")
        val copiedSource = fixture.root.resolve("web/commonwebsrc/acceleratorstorefrontcommons/de/hybris/platform/acceleratorstorefrontcommons/controllers/cms/AbstractCMSComponentController.java")
        if (digest(nativeSource) != provenance.path("sourceSha256").asText() || digest(copiedSource) != provenance.path("sourceSha256").asText() ||
            baseline.path("superclassDirectory").asText() != fixture.root.resolve("web/webroot/WEB-INF/classes").toRealPath().toString() ||
            baseline.path("superclassSha256").asText() != provenance.path("classSha256").asText()) return "Native commonweb provenance mismatch"
        return null
    }
    override fun run(): TestResult = runGatedProfile(name, ::preflight, ::scenario)
    private fun scenario() {
        deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3)
        fun verify(marker: String) {
            var last = "not read"
            while (true) {
                left()
                try {
                    val (response, type) = httpVerifier.getWithContentType("$endpoint/probe", mapOf("nonce" to nonce), left())
                    requireCmsHtml(response, type, nonce, marker)
                    requireCmsFacts(facts(), baselineCount)
                    println("    Native CMS JSP proof: marker=$marker nonce=$nonce content=reclazz-cms-content unsaved=true paragraphCount=$baselineCount MIME=$type")
                    return
                } catch (failure: Exception) { last = failure.message ?: failure.javaClass.simpleName }
                check(TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()) > 0) { "CMS $marker did not converge: $last" }
                Thread.sleep(50)
            }
        }
        val source = fixture.root.resolve(relative); val original = CmsFixtureBaseline.original(relative)
        val changed = replaceOnce(original.toString(Charsets.UTF_8), "\"cms-v1\"", "\"cms-v2\"").toByteArray()
        fun apply(bytes: ByteArray) {
            val mark = agentClient.cursor(); check(writeChanged(source, bytes)) { "Expected distinct CMS edit/restore bytes" }
            var receipt: AgentEvent? = null
            while (receipt == null) { left(); receipt = cmsReloadReceipt(agentClient.getEvents(), mark, source.fileName.toString(), owner); if (receipt == null) Thread.sleep(30) }
            println("    CMS exact COMPILE/reload receipt: ${receipt.sequence}:$owner after=$mark")
        }
        verify("cms-v1")
        withRestore({
            apply(changed); verify("cms-v2")
            check(System.getenv("RECLAZZ_CMS_INJECT_FAILURE") != "true") { "Injected CMS failure after real rendered v2" }
        }) {
            deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3)
            apply(original); verify("cms-v1"); fixture.checkBytes()
        }
    }
}
