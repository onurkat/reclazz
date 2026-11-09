package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.util.UUID

class CronJobLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):BatchLiveTest("Native CronJobService execution",c,a,h) {
    override fun scenario() {
        val relative="src/com/onurkat/reclazztest/batch/NativeProbeJob.java"
        val owner="com.onurkat.reclazztest.batch.NativeProbeJob"
        var since=agentClient.cursor()
        fun verify(version:String) {
            val nonce=UUID.randomUUID().toString()
            afterVersionConverges(httpVerifier,config.eventTimeoutMs,version,{ budget ->
                val response=httpVerifier.get("$endpoint/native/cronjob-version",mapOf("nonce" to nonce),budget)
                if(response.statusCode==200) check(json(response).path("nonce").asText()==nonce) {"Wrong pure-read nonce: $response"}
                response
            },{ budget ->
                val events=agentClient.getEvents().filter { it.sequence>since &&
                    (it.message.contains(owner) || it.message.startsWith("Compiled NativeProbeJob.java")) }
                "events=$events receipt="+latestVerificationSample(config.portFile,owner,budget)
            },{
                val raw=post("$endpoint/native/cronjob",mapOf("nonce" to nonce))
                requireOwnedCleanup(raw)
                val response=json(raw)
                check(response["version"].asText()==version && response["calls"].asInt()==1 && response["result"].asText()=="SUCCESS" &&
                    response["status"].asText()=="FINISHED" && response["ownedRemaining"].asLong()==0L) { "Native job execution/cleanup failed: $response" }
            })
        }
        verify("job-v1")
        javaEdit(relative,replaceOnce(original(relative).toString(Charsets.UTF_8),"job-v1","job-v2"),{verify("job-v1")},beforeWrite={since=it}) {verify("job-v2")}
    }
}
