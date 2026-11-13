package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

internal const val newRelationName = "New relation attempt (outer recovery required)"
internal val relationTypes = listOf("ReclazzRelationLeftProbe", "ReclazzRelationRightProbe")
internal val relationNames = listOf("ReclazzOneToManyProbe", "ReclazzManyToManyProbe")
internal fun requireRelationAbsent(f:JsonNode) {
    check(f.path("tenant").asText()=="master" && f.path("jvmStart").asLong()>0 && f.path("collisions").asLong(-1)==0L)
    check(f.path("models").isObject && f["models"].isEmpty && f.path("relations").isObject && f["relations"].isEmpty && f.path("tables").isArray && f["tables"].isEmpty)
    check(relationTypes.all{f.path("resources").path(it).isBoolean && !f["resources"][it].asBoolean()})
}
internal fun requireRelationSetup(f:JsonNode) {
    check(f.path("tenant").asText()=="master" && f.path("collisions").asLong(-1)==0L && f.path("models").size()==2 && f.path("relations").isObject && f["relations"].isEmpty)
    for(type in relationTypes) {
        val model=f.path("models").path(type)
        check(model.path("className").asText()=="com.onurkat.reclazztest.model.${type}Model" && model.path("sameClass").asBoolean() && model.path("classId").asInt()!=0)
        for(kind in listOf("attributes","accessors")) check(model.path(kind).isObject && model[kind].size()==2 && model[kind].all{it.isBoolean && !it.asBoolean()})
    }
}
internal fun requireRelationLive(f:JsonNode, before:JsonNode) {
    check(f.path("tenant").asText()=="master" && f.path("collisions").asLong(-1)==0L && f.path("jvmStart")==before.path("jvmStart"))
    check(relationNames.all{f.path("relations").has(it)})
    check(f.path("relations").path(relationNames[1]).path("table").asText().equals("ReclazzRelLinks",true) && f["relations"][relationNames[1]].path("typecode").asInt()==31995)
    for(type in relationTypes) {
        val model=f.path("models").path(type);val previous=before.path("models").path(type)
        check(model.path("className")==previous.path("className") && model.path("classId")==previous.path("classId") && model.path("sameClass").asBoolean())
        for(kind in listOf("attributes","accessors")) check(model.path(kind).isObject && model[kind].fieldNames().asSequence().toSet()==previous.path(kind).fieldNames().asSequence().toSet() && model[kind].all{it.isBoolean && it.asBoolean()})
    }
}
internal fun requireRelationRows(first:JsonNode, second:JsonNode, before:JsonNode, nonce:String, shape:String) {
    check(shape in listOf("one","many"))
    for(row in listOf(first,second)) {
        requireRelationLive(row.path("facts"),before)
        check(row.path("nonce").asText()==nonce && row.path("shape").asText()==shape && row.path("detached").asBoolean())
        val names=if(shape=="one") listOf("l1","r1","r2") else listOf("l1","l2","r1","r2")
        check(row.path("ids").fieldNames().asSequence().toSet()==names.toSet())
        val ids=names.associateWith{row["ids"][it].asText()};check(ids.values.all{it.matches(Regex("[0-9]+"))} && ids.values.toSet().size==names.size)
        val expected=if(shape=="one") mapOf("l1" to listOf(ids.getValue("r1"),ids.getValue("r2")),"r1" to listOf(ids.getValue("l1")),"r2" to listOf(ids.getValue("l1")))
        else mapOf("l1" to listOf(ids.getValue("r1"),ids.getValue("r2")),"l2" to listOf(ids.getValue("r2")),"r1" to listOf(ids.getValue("l1")),"r2" to listOf(ids.getValue("l1"),ids.getValue("l2")))
        check(row.path("links").fieldNames().asSequence().toSet()==expected.keys)
        for((key,values) in expected) check(row["links"][key].isArray && row["links"][key].map{it.asText()}.sorted()==values.sorted())
    }
    check(first.path("ids")==second.path("ids"))
}
internal fun relationSetupXml(xml:String):String {
    check(xml.split("    </itemtypes>").size==2 && "typecode=\"31991\"" in xml && relationTypes.none{it in xml} && relationNames.none{it in xml})
    return xml.replace("    </itemtypes>",RELATION_SETUP+"    </itemtypes>")
}
internal fun relationMeasuredXml(xml:String):String {
    check("<relations>" !in xml && relationTypes.all{it in xml} && relationNames.none{it in xml} && xml.split("    <itemtypes>").size==2)
    return xml.replace("    <itemtypes>",RELATION_MEASURED+"    <itemtypes>")
}
class NewRelationLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):BatchLiveTest(newRelationName,c,a,h) {
    private fun preflight():String? {
        ItemsSchemaLiveTest(config,agentClient,httpVerifier).preflight()?.let{return it}
        val state=batchJson.readTree(Files.readString(Path.of(System.getenv("RECLAZZ_ITEMS_RECOVERY"),"state.json")))
        check(state.path("mode").asText()=="new-relation" && state.path("fullSnapshotVerified").asBoolean())
        requireRelationAbsent(json(get("$endpoint/items/new-relation/facts")));return null
    }
    override fun run():TestResult=runGatedProfile(name,::preflight,::scenario)
    override fun scenario() {
        val control=json(get("$endpoint/items/facts"));val health=json(get("$endpoint/items/health"))
        val start=json(get("$endpoint/items/new-relation/facts"))["jvmStart"]
        val path=Path.of(config.extPath,"resources/reclazztest-items.xml")
        val original=original("resources/reclazztest-items.xml").toString(Charsets.UTF_8)
        val setup=relationSetupXml(original);val deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(12)
        fun left()=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()).also{check(it>0){"Relation attempt deadline"}}
        fun saveAndWait(xml:String){val mark=agentClient.cursor();check(writeChanged(path,xml.toByteArray()));while(!newTypeReceipt(agentClient.getEvents(),mark)){left();Thread.sleep(50)}}
        saveAndWait(setup)
        val before=json(httpVerifier.get("$endpoint/items/new-relation/facts",emptyMap(),left()));requireRelationSetup(before);check(before["jvmStart"]==start)
        check(runCatching{requireRelationLive(before,before)}.isFailure)
        saveAndWait(relationMeasuredXml(setup))
        requireRelationLive(json(httpVerifier.get("$endpoint/items/new-relation/facts",emptyMap(),left())),before)
        for(shape in listOf("one","many")) {
            val nonce="reclazz-c33c-"+UUID.randomUUID();val args=mapOf("shape" to shape,"nonce" to nonce)
            val first=json(httpVerifier.post("$endpoint/items/new-relation/roundtrip",args,left()));val second=json(httpVerifier.get("$endpoint/items/new-relation/roundtrip",args,left()))
            requireRelationRows(first,second,before,nonce,shape);println("    Relation $shape same-Class typed detached persistence: $first; $second")
        }
        check(control==json(get("$endpoint/items/facts")) && health==json(get("$endpoint/items/health")))
        check(System.getenv("RECLAZZ_ITEMS_INJECT_FAILURE")!="true"){"Injected items failure after persisted write; outer recovery required"}
    }
}

private val RELATION_SETUP = """        <itemtype code="ReclazzRelationLeftProbe" extends="GenericItem" autocreate="true" generate="true">
            <deployment table="ReclazzRelLeftProbes" typecode="31993"/>
            <attributes><attribute qualifier="nonce" type="java.lang.String"><modifiers read="true" write="true" optional="false" unique="true"/><persistence type="property"/></attribute></attributes>
        </itemtype>
        <itemtype code="ReclazzRelationRightProbe" extends="GenericItem" autocreate="true" generate="true">
            <deployment table="ReclazzRelRightProbes" typecode="31994"/>
            <attributes><attribute qualifier="nonce" type="java.lang.String"><modifiers read="true" write="true" optional="false" unique="true"/><persistence type="property"/></attribute></attributes>
        </itemtype>
"""
private val RELATION_MEASURED = """    <relations>
        <relation code="ReclazzOneToManyProbe" autocreate="true" localized="false">
            <sourceElement type="ReclazzRelationLeftProbe" qualifier="relationParent" cardinality="one"><modifiers read="true" write="true" search="true" optional="true"/></sourceElement>
            <targetElement type="ReclazzRelationRightProbe" qualifier="children" cardinality="many" collectiontype="set"><modifiers read="true" write="true" search="true" optional="true"/></targetElement>
        </relation>
        <relation code="ReclazzManyToManyProbe" autocreate="true" localized="false">
            <deployment table="ReclazzRelLinks" typecode="31995"/>
            <sourceElement type="ReclazzRelationLeftProbe" qualifier="leftPeers" cardinality="many" collectiontype="set"><modifiers read="true" write="true" search="true" optional="true"/></sourceElement>
            <targetElement type="ReclazzRelationRightProbe" qualifier="rightPeers" cardinality="many" collectiontype="set"><modifiers read="true" write="true" search="true" optional="true"/></targetElement>
        </relation>
    </relations>
"""
