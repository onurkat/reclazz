package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEvent
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal fun itemsReceipt(events:List<AgentEvent>, after:Long, owner:String):Boolean {
    val seen=events.filter {it.sequence>after}
    check(seen.none{it.level=="ERROR"}) {"Items codegen error: $seen"}
    resourceFailure(seen,listOf("Codegen","items.xml","Update Running System")){false}?.let {error(it)}
    val changed=seen.firstOrNull {it.level=="INFO" && it.message=="Items XML changed: reclazztest-items.xml [reclazztest]"} ?: return false
    val ant=seen.firstOrNull {it.sequence>changed.sequence && it.level=="OK" && it.message.startsWith("Codegen regen+compile done for reclazztest, model classes ready (")} ?: return false
    val schema=seen.any {it.sequence>ant.sequence && it.level=="OK" && it.message=="items.xml changed, ran Update Running System (schema only); the new attributes and enum values are live. No restart, no HAC click."}
    val applied=seen.any {it.sequence>changed.sequence && !it.message.contains("(inner class)") && ((it.level=="RELOAD" && it.message.startsWith("Reloaded $owner ")) ||
        (it.level=="STRUCTURAL_RELOAD" && it.message.startsWith("Structural reload: $owner ")))}
    return schema && applied
}

/** Inner attempt only. The Python profile driver must stop/restore/reboot before accepting it. */
class ItemsSchemaLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):BatchLiveTest("Items schema attempt (outer recovery required)",c,a,h) {
    private val relative="resources/reclazztest-items.xml"
    private val owner="de.hybris.platform.core.model.ReclazzSchemaProbeModel"
    private var deadline=0L
    private fun left()=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()).also {check(it>0){"Items schema attempt deadline"}}
    private fun preflight():String? {
        profileGateReason(json(get("$endpoint/gates")),linkedMapOf("agentArgsFound" to true,"agentOptionsMatchConfig" to true,
            "autoCompile" to true,"autoUpdateRunningSystem" to true,"resetStatics" to false,
            "autoSolrReindex" to false,"impexAllowRemove" to false,"codegenExtensionLoaded" to true,"codegenGeneratedRoots" to true))?.let{return it}
        val home=System.getenv("RECLAZZ_TEST_HYBRIS_HOME")?.let{Path.of(it).toRealPath()} ?: return "RECLAZZ_TEST_HYBRIS_HOME missing"
        if(home!=Path.of(config.extPath).toRealPath().parent.parent.resolve("reclazz-sap/hybris").normalize())
            return "Items requires the disposable clean install beside this repository"
        if(!Files.isSameFile(home.resolve("bin/custom/reclazztest"),Path.of(config.extPath))) return "Items fixture/install mismatch"
        val recovery=System.getenv("RECLAZZ_ITEMS_RECOVERY")?.let{Path.of(it)} ?: return "Stopped DB recovery snapshot missing; use scripts/test-sap-items-live.py"
        val state=batchJson.readTree(Files.readString(recovery.resolve("state.json")))
        if(state["home"]?.asText()!=home.toString() || state["snapshotVerified"]?.asBoolean()!=true) return "Items recovery identity mismatch"
        val snapshot=recovery.resolve("db")
        val manifest=batchJson.readTree(Files.readString(snapshot.resolve("manifest.json")))
        val expected=manifest.fieldNames().asSequence().toSet()
        val actual=Files.walk(snapshot.resolve("files")).use {paths->paths.filter{Files.isRegularFile(it)}.map{snapshot.resolve("files").relativize(it).toString()}.toList().toSet()}
        if(expected.isEmpty() || expected!=actual) return "Items recovery membership mismatch"
        for(name in expected) {
            val file=snapshot.resolve("files").resolve(name).normalize()
            if(!file.startsWith(snapshot.resolve("files").normalize()) || Files.isSymbolicLink(file)) return "Invalid recovery member"
            val bytes=Files.readAllBytes(file)
            val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            if(bytes.size.toLong()!=manifest[name]["size"].asLong() || digest!=manifest[name]["sha256"].asText()) return "Items recovery checksum mismatch"
        }
        if(!Files.readAllBytes(Path.of(config.extPath,relative)).contentEquals(original(relative))) return "Dirty items fixture"
        val facts=json(get("$endpoint/items/facts"))
        if(!facts.path("typePresent").asBoolean() || facts.path("batchValuePresent").asBoolean() || facts.path("collisions").asLong()!=0L ||
            facts.path("typeCode").asInt()!=31991 || facts.path("rows").asInt(-1)!=0) return "Items baseline metadata unavailable: $facts"
        return null
    }
    override fun run():TestResult=runGatedProfile(name,::preflight,::scenario)
    override fun scenario() {
        deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(10)
        val xml=original(relative).toString(Charsets.UTF_8)
        val edited=replaceOnce(xml,"            </attributes>","""                <attribute qualifier="batchValue" type="java.lang.String">
                    <modifiers read="true" write="true" optional="true"/>
                    <persistence type="property"/>
                </attribute>
            </attributes>""").toByteArray()
        val mark=agentClient.cursor()
        check(writeChanged(Path.of(config.extPath,relative),edited))
        // No live inverse schema change: every exit is recovered by the stopped-DB outer finally.
        while(!itemsReceipt(agentClient.getEvents(),mark,owner)) {left();Thread.sleep(50)}
        val facts=json(httpVerifier.get("$endpoint/items/facts",emptyMap(),left()))
        check(facts.path("batchValuePresent").asBoolean() && facts.path("qualifier").asText()=="batchValue" &&
            facts.path("attributeType").asText()=="java.lang.String") {"Native descriptor missing: $facts"}
        val nonce="reclazz-schema-"+java.util.UUID.randomUUID()
        val first=json(httpVerifier.post("$endpoint/items/roundtrip",mapOf("nonce" to nonce),left()))
        check(first.path("differentInstance").asBoolean() && first.path("value").asText()=="schema-v2" && first.path("nonce").asText()==nonce) {"First detached round trip failed: $first"}
        val second=json(httpVerifier.get("$endpoint/items/roundtrip",mapOf("nonce" to nonce,"pk" to first["pk"].asText()),left()))
        check(second.path("differentInstance").asBoolean() && second.path("value").asText()=="schema-v2" && second.path("databaseValue").asText()=="schema-v2" &&
            second.path("pk").asText()==first["pk"].asText() && second.path("nonce").asText()==nonce) {"Second request persisted round trip failed: $second"}
        check(System.getenv("RECLAZZ_ITEMS_INJECT_FAILURE")!="true") {"Injected items failure after persisted write; outer recovery required"}
        println("    Native descriptor and detached model + independent DB read: $first; $second")
    }
}
