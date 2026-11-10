package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class FlexibleSearchLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier,
    private val withheldSave:Path? = null):BatchLiveTest("DAO FlexibleSearch query body",c,a,h) {
    override fun scenario() {
        val relative="src/com/onurkat/reclazztest/batch/ProbeQueryDao.java"
        val path=Path.of(config.extPath,relative)
        val baseline=original(relative)
        check(Files.readAllBytes(path).contentEquals(baseline)) {"Dirty DAO baseline"}
        val run="reclazz-dao-${UUID.randomUUID()}"
        val params=mapOf("run" to run)
        val all=listOf("a-1","a-2","b-1","c-1").map {"$run-$it"}
        fun response(node:JsonNode):JsonNode {
            check(node["run"]?.asText()==run) {"Wrong owned run: $node"};return node
        }
        fun count(expected:Int) {
            val proof=response(json(get("$endpoint/dao/count",params)))
            check(proof["ownedRemaining"]?.isIntegralNumber==true && proof["ownedRemaining"].asInt()==expected &&
                proof["globalRemaining"]?.isIntegralNumber==true && proof["globalRemaining"].asInt()==expected &&
                proof["nonces"]?.isArray==true && proof["nonces"].map {it.asText()}==(if(expected==0) emptyList<String>() else all)) {
                "Owned/control rows changed: $proof"
            }
        }
        fun find(suffixes:List<String>) {
            val expected=suffixes.map {"$run-$it"}
            val proof=pollJson("$endpoint/dao/find",params) {node ->
                response(node); node["nonces"]?.isArray==true && node["nonces"].map {it.asText()}==expected
            }
            check(proof["beanIdentity"]?.isIntegralNumber==true) {"Missing bean diagnostic: $proof"}
            println("    DAO query proof: $proof")
            count(4)
        }
        count(0) // Before claiming this nonce, refuse any pre-existing synthetic data.
        var seeded=false
        withRestore({
            response(json(post("$endpoint/dao/seed",params)));seeded=true
            count(4);find(listOf("a-1","a-2"))
            val changed=replaceOnce(baseline.toString(Charsets.UTF_8),"run + \"-a-%\"","run + \"-b-%\"").toByteArray()
            fixtureEdit(relative,changed,{file,bytes ->
                val cursor=agentClient.cursor()
                if(withheldSave!=null && bytes.contentEquals(changed)) {
                    check(!withheldSave.toAbsolutePath().normalize().startsWith(Path.of(config.extPath).toAbsolutePath().normalize())) {"RED copy must be outside watched fixtures"}
                    Files.write(withheldSave,bytes)
                    awaitSources(agentClient,listOf(file),cursor,config.eventTimeoutMs)
                } else if(writeChanged(file,bytes)) awaitSources(agentClient,listOf(file),cursor,config.eventTimeoutMs)
            },{find(listOf("a-1","a-2"))}) {find(listOf("b-1"))}
        }) {
            restoreAll({
                val proof=response(json(post("$endpoint/dao/cleanup",params)))
                check(proof["removed"]?.isIntegralNumber==true && (!seeded || proof["removed"].asInt()==4)) {"Cleanup removed wrong count: $proof"}
                println("    DAO cleanup proof: $proof")
                requireOwnedCleanup(get("$endpoint/dao/count",params));count(0)
            },{check(Files.readAllBytes(path).contentEquals(baseline)) {"DAO bytes not restored"}})
        }
    }
}
