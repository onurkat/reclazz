package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.config.TestConfig
import java.nio.file.Files
import java.nio.file.Path

/** Includes every baseline a Batch A scenario edits, including its exception paths. */
class BatchFixtureBaseline(private val config:TestConfig) {
    private val relative=listOf("project.properties","resources/reclazztest-batch-spring.xml",
        "resources/reclazztest-process-resources-spring.xml","resources/process/reclazz-process-existing.xml",
        "resources/impex/reclazz-batch-refusal.impex","resources/localization/reclazztest-locales_en.properties",
        "resources/occ/v2/reclazztestocc/web/spring/reclazztestocc-web-spring.xml",
        "src/com/onurkat/reclazztest/occ/ReclazzProbeOccController.java",
        "web/src/com/onurkat/reclazztest/controllers/NativeExecutionController.java",
        "web/src/com/onurkat/reclazztest/controllers/BatchVerificationController.java","resources/reclazztest-beans.xml",
        "web/src/com/onurkat/reclazztest/controllers/ItemsSchemaController.java",
        "web/src/com/onurkat/reclazztest/controllers/StaticProbeController.java","resources/reclazztest-items.xml")+
        listOf("StaticProbe","ProbePopulator","NativeProbeJob","NativeProbeListener","ProbeValidator","ValidatedPayload","OrikaRollbackData","OrikaRollbackWsDTO")
            .map {"src/com/onurkat/reclazztest/batch/$it.java"}
    fun checkBytes() {
        for(rel in relative) {
            val baseline=javaClass.classLoader.getResourceAsStream("templates/batch/$rel")!!.use {it.readBytes()}
            check(Files.readAllBytes(Path.of(config.extPath,rel)).contentEquals(baseline)) {"Batch A fixture not restored: $rel"}
        }
    }
}
