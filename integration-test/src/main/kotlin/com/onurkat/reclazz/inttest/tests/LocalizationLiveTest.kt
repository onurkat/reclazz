package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.client.AgentEventClient
import com.onurkat.reclazz.inttest.config.TestConfig
import com.onurkat.reclazz.inttest.http.HttpVerifier
import com.onurkat.reclazz.inttest.report.TestResult
import com.onurkat.reclazz.inttest.report.TestStatus

class LocalizationLiveTest(c:TestConfig,a:AgentEventClient,h:HttpVerifier):BatchLiveTest("Localization cache",c,a,h) {
    override fun run(): TestResult = if(json(get("$endpoint/gates"))["autoUpdateRunningSystem"].asBoolean())
        result(System.currentTimeMillis(),TestStatus.SKIP,"requires autoUpdateRunningSystem=false; stored type-name writes excluded") else super.run()
    override fun scenario() {
        val file="reclazztest-locales_en.properties";val relative="resources/localization/$file"
        pollGet("$endpoint/localization",expectedBody="localized-v1")
        val changed=replaceOnce(original(relative).toString(Charsets.UTF_8),"localized-v1","localized-v2")
        fixtureEdit(relative,changed.toByteArray(),{p,b->resourceWrite(p,b,listOf(file),
            {it.level=="OK" && it.message=="Type and enum names re-read from $file."})},
            {pollGet("$endpoint/localization",expectedBody="localized-v1")}) {
            pollGet("$endpoint/localization",expectedBody="localized-v2")
            resourceWrite(java.nio.file.Path.of(config.extPath,relative),(changed+"# unchanged text control\n").toByteArray(),listOf(file),
                {it.level=="INFO" && it.message=="Localization file saved with no text changes: $file"})
            pollGet("$endpoint/localization",expectedBody="localized-v2")
        }
    }
}
