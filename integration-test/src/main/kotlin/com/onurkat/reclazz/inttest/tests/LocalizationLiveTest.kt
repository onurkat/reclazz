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
        fun changedWrite(path:java.nio.file.Path,bytes:ByteArray) = resourceWrite(path,bytes,listOf(file),
            {it.level=="OK" && it.message=="Type and enum names re-read from $file."},
            acceptEvents={localizationChangeOutcome(it,file)})
        fixtureEdit(relative,changed.toByteArray(),{p,b->changedWrite(p,b)},
            {pollGet("$endpoint/localization",expectedBody="localized-v1")}) {
            pollGet("$endpoint/localization",expectedBody="localized-v2")
            val path=java.nio.file.Path.of(config.extPath,relative)
            // Equal property values still have changed raw bytes when a comment is added.
            val commented=(changed+"# byte-change control\n").toByteArray()
            changedWrite(path,commented)
            pollGet("$endpoint/localization",expectedBody="localized-v2")
            // Deliberate identical save, not an append; ordinary no-op writes remain skipped.
            resourceWrite(path,commented,listOf(file),
                {it.level=="INFO" && it.message=="Localization file saved with no text changes: $file"},
                forceWrite=true,acceptEvents={localizationNoChangeOutcome(it,file)})
            pollGet("$endpoint/localization",expectedBody="localized-v2")
        }
    }
}
