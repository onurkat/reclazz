package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEvent

fun main() {
    var checks=0
    val absent=batchJson.readTree("""{"tenant":"master","jvmStart":123,"collisions":0,"present":false,"modelResourcePresent":false,"jaloResourcePresent":false,"matchingTables":[]}""")
    requireNewTypeAbsent(absent);checks++
    check(runCatching{requireNewType(absent,123)}.isFailure);checks++
    for(field in listOf("present","modelResourcePresent","jaloResourcePresent")) {
        check(runCatching{requireNewTypeAbsent(absent.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put(field,true))}.isFailure);checks++
    }
    val nonce="reclazz-c33a-00000000-0000-0000-0000-000000000001"
    val valid=batchJson.readTree("""{"tenant":"master","jvmStart":123,"collisions":0,"present":true,"modelLoaded":true,"jaloLoaded":true,"matchingTables":["reclazzaddedtypeprobes"],"typeCode":31992,"table":"reclazzaddedtypeprobes","modelClass":"com.onurkat.reclazztest.model.ReclazzAddedTypeProbeModel","jaloClass":"com.onurkat.reclazztest.jalo.ReclazzAddedTypeProbe","modelLoader":"native-loader","nonce":"$nonce","pk":"8796093086968","value":"new-type-v1","databaseValue":"new-type-v1","differentInstance":true}""")
    requireNewTypeRoundtrip(valid,valid,nonce,123);checks++
    for(field in listOf("tenant","jvmStart","collisions","present","modelLoaded","jaloLoaded","matchingTables","typeCode","table","modelClass","jaloClass","modelLoader","nonce","pk","value","databaseValue","differentInstance")) {
        val bad=valid.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>();bad.remove(field)
        check(runCatching{requireNewTypeRoundtrip(valid,bad,nonce,123)}.isFailure){"Missing $field accepted"};checks++
    }
    check(runCatching{requireNewTypeRoundtrip(valid,valid,nonce,456)}.isFailure);checks++
    val changed=AgentEvent("INFO","Items XML changed: reclazztest-items.xml [reclazztest]","",1)
    val compiled=AgentEvent("OK","Codegen regen+compile done for reclazztest, model classes ready (9s)","",2)
    val schema=AgentEvent("OK","items.xml changed, ran Update Running System (schema only); the new attributes and enum values are live. No restart, no HAC click.","",3)
    check(newTypeReceipt(listOf(changed,compiled,schema),0));checks++
    for(events in listOf(listOf(changed,schema),listOf(compiled,schema),listOf(changed,compiled),listOf(changed,compiled,schema.copy(sequence=1)),listOf(changed,compiled,schema.copy(level="WARN")))) {check(runCatching{newTypeReceipt(events,0)}.getOrDefault(false)==false);checks++}
    check(!newTypeReceipt(listOf(changed,compiled,schema),3));checks++
    val xml=NewItemTypeLiveTest::class.java.classLoader.getResourceAsStream("templates/batch/resources/reclazztest-items.xml")!!.use{it.readBytes().toString(Charsets.UTF_8)}
    val edited=addNewItemType(xml);check(edited.replace(edited.substring(xml.indexOf("    </itemtypes>"),edited.indexOf("    </itemtypes>")),"")==xml);checks++
    check(runCatching{addNewItemType(edited)}.isFailure);checks++
    requireDefaultMembership(committedDefaultMembership.toList());check(committedDefaultMembership.size==63);checks++
    println("NEW_ITEM_TYPE_CONTRACT $checks checks PASS")
}
