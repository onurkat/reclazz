package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

fun main() {
    var checks=0
    val before=batchJson.createObjectNode().put("tenant","master").put("jvmStart",123).put("collisions",0)
    before.set<JsonNode>("relations",batchJson.createObjectNode())
    val models=batchJson.createObjectNode()
    for((i,type) in relationTypes.withIndex()) {
        val model=batchJson.createObjectNode().put("className","com.onurkat.reclazztest.model.${type}Model").put("classId",11+i).put("sameClass",true)
        val fields=if(i==0) listOf("children","rightPeers") else listOf("relationParent","leftPeers")
        for(kind in listOf("attributes","accessors")) {val values=batchJson.createObjectNode();for(q in fields)values.put(q,false);model.set<JsonNode>(kind,values)}
        models.set<JsonNode>(type,model)
    }
    before.set<JsonNode>("models",models);requireRelationSetup(before);checks++
    check(runCatching{requireRelationLive(before,before)}.isFailure);checks++
    val live=before.deepCopy();val rel=batchJson.createObjectNode();rel.set<JsonNode>(relationNames[0],batchJson.createObjectNode().putNull("table").put("typecode",0));rel.set<JsonNode>(relationNames[1],batchJson.createObjectNode().put("table","reclazzrellinks").put("typecode",31995));live.set<JsonNode>("relations",rel)
    for(model in live["models"])for(kind in listOf("attributes","accessors"))for(q in model[kind].fieldNames().asSequence().toList())(model[kind] as ObjectNode).put(q,true)
    requireRelationLive(live,before);checks++
    for(change in listOf("classId","sameClass","attribute","accessor","relation","table","jvm")) {
        val bad=live.deepCopy();val model=bad["models"][relationTypes[0]] as ObjectNode
        when(change){"classId"->model.put("classId",99);"sameClass"->model.put("sameClass",false);"attribute"->(model["attributes"] as ObjectNode).remove("children");"accessor"->(model["accessors"] as ObjectNode).put("children",false);"relation"->(bad["relations"] as ObjectNode).remove(relationNames[0]);"table"->(bad["relations"][relationNames[1]] as ObjectNode).put("table","foreign");"jvm"->bad.put("jvmStart",456)}
        check(runCatching{requireRelationLive(bad,before)}.isFailure){"Accepted $change"};checks++
    }
    val nonce="reclazz-c33c-00000000-0000-0000-0000-000000000001"
    for(shape in listOf("one","many")) {
        val row=batchJson.readTree(if(shape=="one") """{"shape":"one","nonce":"$nonce","detached":true,"ids":{"l1":"1","r1":"2","r2":"3"},"links":{"l1":["2","3"],"r1":["1"],"r2":["1"]}}""" else """{"shape":"many","nonce":"$nonce","detached":true,"ids":{"l1":"1","l2":"4","r1":"2","r2":"3"},"links":{"l1":["2","3"],"l2":["3"],"r1":["1"],"r2":["1","4"]}}""") as ObjectNode
        row.set<JsonNode>("facts",live);requireRelationRows(row,row,before,nonce,shape);checks++
        for(change in listOf("links","pk","detached")) {
            val bad=row.deepCopy();when(change){"links"->(bad["links"] as ObjectNode).set<JsonNode>("r1",batchJson.createArrayNode());"pk"->(bad["ids"] as ObjectNode).put("r1","999");"detached"->bad.put("detached",false)}
            check(runCatching{requireRelationRows(row,bad,before,nonce,shape)}.isFailure);checks++
        }
    }
    val xml=NewRelationLiveTest::class.java.classLoader.getResourceAsStream("templates/batch/resources/reclazztest-items.xml")!!.use{it.readBytes().toString(Charsets.UTF_8)}
    val setup=relationSetupXml(xml);val measured=relationMeasuredXml(setup);check(measured.indexOf("<relations>")<measured.indexOf("<itemtypes>") && "qualifier=\"relationParent\"" in measured && "qualifier=\"owner\"" !in measured);checks++
    check(runCatching{relationSetupXml(setup)}.isFailure && runCatching{relationMeasuredXml(measured)}.isFailure);checks++
    requireDefaultMembership(committedDefaultMembership.toList());checks++
    println("NEW_RELATION_CONTRACT $checks checks PASS")
}
