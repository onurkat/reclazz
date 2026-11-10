package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEvent
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal fun solrReindexReceipt(events:List<AgentEvent>,after:Long,tag:String):Boolean {
    val seen=events.filter {it.sequence>after}
    check(seen.none {it.level=="ERROR"}) {"Native Solr error: $seen"}
    val owned=seen.filter {it.message.startsWith("SAP Solr $tag: ")}
    check(owned.none {it.message.contains(": FAILED") || it.message.contains("FINISHED_UNVERIFIED") || it.message.contains("no stored documents")}) {"Solr did not verify an owned operation: $owned"}
    return owned.any {it.level=="INFO" && it.message.startsWith("SAP Solr $tag: FINISHED partial properties=1 documents=1 durationMs=")}
}

/** The exact native document/control proof is shared with portable HTTP contracts. */
internal fun requireSolrStored(proof:JsonNode,nonce:String,value:String,baseline:JsonNode?=null) {
    check(proof.path("nonce").asText()==nonce && proof.path("pk").asText().matches(Regex("[1-9][0-9]*"))) {"Owned nonce/PK missing: $proof"}
    check(proof.path("tag").asText().matches(Regex("owner[1-9][0-9]*/config1/type[12]"))) {"Owned discovery tag missing: $proof"}
    val target=proof.path("target");val control=proof.path("control")
    check(target.path("core").asText()!=control.path("core").asText()) {"Shared physical index"}
    for(row in listOf(target,control)) {
        check(row.path("core").asText().contains(nonce) && row.path("pk")==proof.path("pk") &&
            row.path("uniqueKey").asText().matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) &&
            !row.path("documentId").isMissingNode && !row.path("documentId").isNull &&
            row.path("document").path(row.path("uniqueKey").asText())==row.path("documentId") &&
            row.path("document").path("pk").asText()==proof.path("pk").asText()) {"Native physical identity proof missing: $row"}
        check(row.path("fields").isObject && row.path("fields").size()==row.path("values").size())
        for((name,field) in row.path("fields").fields()) {
            check(field.isTextual && field.asText().isNotBlank() && row.path("document").path(field.asText())==row.path("values").path(name)) {"Stored physical field mismatch: $row"}
        }
    }
    check(target.path("values")==batchJson.readTree("""{"changedValue":"$value","stableValue":"stable-v1"}""") &&
        control.path("values")==batchJson.readTree("""{"controlValue":"control-v1"}""")) {"Native stored field is stale/missing: $proof"}
    if(baseline!=null) {
        check(proof.path("pk")==baseline.path("pk") && proof.path("tag")==baseline.path("tag") && control==baseline.path("control")) {"Unrelated index/document changed: $proof"}
        for(field in listOf("core","qualifier","uniqueKey","documentId","pk","fields"))
            check(target.path(field)==baseline.path("target").path(field)) {"Target physical identity changed: $field"}
    }
}

class SolrTargetedReindexLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):
    BaseTest("Solr targeted reindex attempt (outer recovery required)",c,a,h) {
    private val endpoint="/reclazztestsolr/v2/test/batch/solr"
    private val relative="src/com/onurkat/reclazztestsolr/ReclazzChangedValueProvider.java"
    private val owner="com.onurkat.reclazztestsolr.ReclazzChangedValueProvider"
    private lateinit var fixture:SolrFixtureBaseline
    private lateinit var nonce:String
    private lateinit var baseline:JsonNode
    private var deadline=0L
    private fun left()=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()).also {check(it>0){"Solr attempt deadline"}}
    private fun facts()=json(httpVerifier.get("$endpoint/facts",mapOf("nonce" to nonce),config.httpTimeoutMs))
    private fun preflight():String? {
        profileGateReason(json(httpVerifier.get("$endpoint/gates",emptyMap(),config.httpTimeoutMs)),linkedMapOf(
            "agentArgsFound" to true,"agentOptionsMatchConfig" to true,"autoCompile" to true,"autoSolrReindex" to true,
            "resetStatics" to false,"autoUpdateRunningSystem" to false,"impexAllowRemove" to false,
            "solrserverLoaded" to true,"solrFixtureLoaded" to true,"solrReachable" to true))?.let{return it}
        val home=System.getenv("RECLAZZ_TEST_HYBRIS_HOME")?.let {Path.of(it).toRealPath()} ?: return "Clean home missing"
        val repo=Path.of(config.extPath).toRealPath().parent
        if(home!=repo.parent.resolve("reclazz-sap/hybris") || !Files.isSameFile(home.resolve("bin/custom/reclazztest"),Path.of(config.extPath))) return "Foreign clean install"
        val root=repo.resolve("reclazztestsolr");fixture=SolrFixtureBaseline(root)
        if(!Files.isSymbolicLink(home.resolve("bin/custom/reclazztestsolr")) || !Files.isSameFile(home.resolve("bin/custom/reclazztestsolr"),root)) return "Foreign Solr fixture symlink"
        fixture.checkBytes()
        nonce=System.getenv("RECLAZZ_SOLR_NONCE") ?: return "Owned Solr nonce missing"
        if(!nonce.matches(Regex("reclazz-solr-[a-f0-9-]{36}"))) return "Invalid owned Solr nonce"
        val recovery=System.getenv("RECLAZZ_SOLR_RECOVERY")?.let {Path.of(it)} ?: return "Stopped recovery missing; use outer driver"
        val state=batchJson.readTree(Files.readString(recovery.resolve("state.json")))
        if(state.path("home").asText()!=home.toString() || !state.path("snapshotsVerified").asBoolean()) return "Solr recovery identity mismatch"
        // Verify the actual immutable stopped snapshots, not only a boolean supplied by the driver.
        for(kind in listOf("config","data")) {
            val snap=recovery.resolve(kind);val manifest=batchJson.readTree(Files.readString(snap.resolve("manifest.json")))
            val files=snap.resolve("files")
            val actual=Files.walk(files).use {paths->paths.filter {Files.isRegularFile(it)}.map {files.relativize(it).toString()}.toList().toSet()}
            if(actual.isEmpty() || actual!=manifest.fieldNames().asSequence().toSet()) return "Solr recovery membership mismatch"
            for(name in actual) {
                val path=files.resolve(name).normalize();if(!path.startsWith(files) || Files.isSymbolicLink(path)) return "Invalid recovery member"
                val bytes=Files.readAllBytes(path);val sha=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
                if(bytes.size.toLong()!=manifest[name]["size"].asLong() || sha!=manifest[name]["sha256"].asText()) return "Solr recovery checksum mismatch"
            }
        }
        baseline=facts();requireSolrStored(baseline,nonce,"v1")
        return null
    }
    override fun run():TestResult=runGatedProfile(name,::preflight,::scenario)
    private fun scenario() {
        deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3)
        val source=fixture.root.resolve(relative);val original=SolrFixtureBaseline.original(relative)
        val edited=replaceOnce(original.toString(Charsets.UTF_8),"\"v1\"","\"v2\"").toByteArray()
        fun apply(bytes:ByteArray) {
            val mark=agentClient.cursor();if(!writeChanged(source,bytes)) return
            val reload=agentClient.waitForSourceReload(left(),source.fileName.toString(),owner,mark) ?: error("Missing exact provider COMPILE/reload after $mark")
            while(!solrReindexReceipt(agentClient.getEvents(),reload.sequence,baseline.path("tag").asText())) {left();Thread.sleep(30)}
        }
        fun verify(value:String) {
            val proof=json(httpVerifier.poll(left(),"Solr stored $value",{httpVerifier.get("$endpoint/facts",mapOf("nonce" to nonce),it)}) {
                it.statusCode==200 && runCatching {requireSolrStored(json(it),nonce,value,baseline)}.isSuccess
            })
            requireSolrStored(proof,nonce,value,baseline);println("    SolrJ stored proof: $proof")
        }
        withRestore({
            apply(edited);verify("v2")
            check(System.getenv("RECLAZZ_SOLR_INJECT_FAILURE")!="true") {"Injected Solr failure after real FINISHED and stored v2"}
        }) {
            deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3)
            apply(original);verify("v1");fixture.checkBytes()
        }
    }
}
