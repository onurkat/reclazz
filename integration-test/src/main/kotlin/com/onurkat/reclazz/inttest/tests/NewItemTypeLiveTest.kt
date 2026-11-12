package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEvent
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

internal const val newItemTypeName = "New item type attempt (outer recovery required)"
internal fun requireNewTypeAbsent(f:JsonNode) {
    check(f.path("tenant").asText()=="master" && f.path("jvmStart").asLong()>0 && f.path("collisions").asLong(-1)==0L &&
        f.path("present").isBoolean && !f["present"].asBoolean() &&
        f.path("modelResourcePresent").isBoolean && !f["modelResourcePresent"].asBoolean() &&
        f.path("jaloResourcePresent").isBoolean && !f["jaloResourcePresent"].asBoolean() &&
        f.path("matchingTables").isArray && f["matchingTables"].isEmpty) {"New-type baseline collision/class/table: $f"}
}
internal fun requireNewType(f:JsonNode, start:Long) {
    check(f.path("tenant").asText()=="master" && f.path("jvmStart").asLong()==start && f.path("collisions").asLong(-1)==0L &&
        f.path("present").asBoolean() && f.path("modelLoaded").asBoolean() && f.path("jaloLoaded").asBoolean() &&
        f.path("typeCode").asInt()==31992 && f.path("table").asText().equals("ReclazzAddedTypeProbes",true) &&
        f.path("matchingTables").isArray && f["matchingTables"].size()==1 && f["matchingTables"][0].asText().equals("ReclazzAddedTypeProbes",true) &&
        f.path("modelClass").asText()=="com.onurkat.reclazztest.model.ReclazzAddedTypeProbeModel" &&
        f.path("jaloClass").asText()=="com.onurkat.reclazztest.jalo.ReclazzAddedTypeProbe" && f.path("modelLoader").asText().isNotBlank()) {"Native new type/Model/Jalo missing or JVM changed: $f"}
}
internal fun requireNewTypeRoundtrip(first:JsonNode, second:JsonNode, nonce:String, start:Long) {
    for(f in listOf(first,second)) {
        requireNewType(f,start)
        check(f.path("nonce").asText()==nonce && f.path("value").asText()=="new-type-v1" && f.path("differentInstance").asBoolean() &&
            f.path("pk").asText().matches(Regex("[0-9]+"))) {"Typed detached round trip failed: $f"}
    }
    check(first["pk"]==second["pk"] && second.path("databaseValue").asText()=="new-type-v1") {"Independent committed DB read failed: $second"}
}
internal fun newTypeReceipt(events:List<AgentEvent>, after:Long):Boolean {
    val seen=events.filter {it.sequence>after}
    check(seen.none{it.level=="ERROR"}) {"New-type pipeline error: $seen"}
    resourceFailure(seen,listOf("Codegen","items.xml","Update Running System")){false}?.let{error(it)}
    val changed=seen.firstOrNull{it.level=="INFO" && it.message=="Items XML changed: reclazztest-items.xml [reclazztest]"} ?: return false
    val compiled=seen.firstOrNull{it.sequence>changed.sequence && it.level=="OK" && it.message.startsWith("Codegen regen+compile done for reclazztest, model classes ready (")} ?: return false
    return seen.any{it.sequence>compiled.sequence && it.level=="OK" && it.message=="items.xml changed, ran Update Running System (schema only); the new attributes and enum values are live. No restart, no HAC click."}
}
internal fun addNewItemType(xml:String):String {
    check(xml.countSubstring("    </itemtypes>")==1 && "ReclazzAddedTypeProbe" !in xml && "typecode=\"31991\"" in xml) {"Foreign items baseline"}
    return xml.replace("    </itemtypes>","""        <itemtype code="ReclazzAddedTypeProbe" extends="GenericItem" autocreate="true" generate="true">
            <deployment table="ReclazzAddedTypeProbes" typecode="31992"/>
            <attributes>
                <attribute qualifier="nonce" type="java.lang.String">
                    <modifiers read="true" write="true" optional="false" unique="true"/>
                    <persistence type="property"/>
                </attribute>
                <attribute qualifier="value" type="java.lang.String">
                    <modifiers read="true" write="true" optional="true"/>
                    <persistence type="property"/>
                </attribute>
            </attributes>
        </itemtype>
    </itemtypes>""")
}
private fun String.countSubstring(value:String)=windowed(value.length).count{it==value}

/** The outer driver restores the full stopped installation; never undo schema live. */
class NewItemTypeLiveTest(private val c:TestConfig, private val a:AgentEventClient, private val h:HttpVerifier):BatchLiveTest(newItemTypeName,c,a,h) {
    private var start=0L
    private fun preflight():String? {
        ItemsSchemaLiveTest(c,a,h).preflight()?.let{return it}
        val recovery=Path.of(System.getenv("RECLAZZ_ITEMS_RECOVERY"))
        val state=batchJson.readTree(Files.readString(recovery.resolve("state.json")))
        check(state.path("mode").asText()=="new-item-type" && state.path("fullSnapshotVerified").asBoolean()) {"Full new-type recovery manifest required"}
        val facts=json(get("$endpoint/items/new-type/facts")); requireNewTypeAbsent(facts); start=facts["jvmStart"].asLong()
        // Causal negative: the positive oracle must reject the unchanged baseline.
        check(runCatching{requireNewType(facts,start)}.isFailure)
        return null
    }
    override fun run():TestResult=runGatedProfile(name,::preflight,::scenario)
    override fun scenario() {
        val before=json(get("$endpoint/items/facts"));val health=json(get("$endpoint/items/health"))
        val deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(10)
        fun left()=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()).also{check(it>0){"New-type attempt deadline"}}
        val path=Path.of(config.extPath,"resources/reclazztest-items.xml")
        val mark=agentClient.cursor()
        check(writeChanged(path,addNewItemType(original("resources/reclazztest-items.xml").toString(Charsets.UTF_8)).toByteArray()))
        while(!newTypeReceipt(agentClient.getEvents(),mark)){left();Thread.sleep(50)}
        requireNewType(json(httpVerifier.get("$endpoint/items/new-type/facts",emptyMap(),left())),start)
        val nonce="reclazz-c33a-"+UUID.randomUUID()
        val first=json(httpVerifier.post("$endpoint/items/new-type/roundtrip",mapOf("nonce" to nonce),left()))
        val second=json(httpVerifier.get("$endpoint/items/new-type/roundtrip",mapOf("nonce" to nonce,"pk" to first.path("pk").asText()),left()))
        requireNewTypeRoundtrip(first,second,nonce,start)
        check(before==json(get("$endpoint/items/facts")) && health==json(get("$endpoint/items/health"))) {"Existing type/control changed"}
        check(System.getenv("RECLAZZ_ITEMS_INJECT_FAILURE")!="true") {"Injected items failure after persisted write; outer recovery required"}
        println("    Native generated Model/Jalo, same JVM, detached typed accessors and committed JDBC: $first; $second")
    }
}
