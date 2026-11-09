package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import java.util.UUID

class CronJobLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):BatchLiveTest("Native CronJobService execution",c,a,h) {
    override fun scenario() {
        val relative="src/com/onurkat/reclazztest/batch/NativeProbeJob.java"
        fun verify(version:String) {
            val raw=post("$endpoint/native/cronjob",mapOf("nonce" to UUID.randomUUID().toString()))
            requireOwnedCleanup(raw)
            val response=json(raw)
            check(response["version"].asText()==version && response["calls"].asInt()==1 && response["result"].asText()=="SUCCESS" &&
                response["status"].asText()=="FINISHED" && response["ownedRemaining"].asLong()==0L) { "Native job execution/cleanup failed: $response" }
        }
        verify("job-v1")
        javaEdit(relative,replaceOnce(original(relative).toString(Charsets.UTF_8),"job-v1","job-v2"),{verify("job-v1")}) {verify("job-v2")}
    }
}
