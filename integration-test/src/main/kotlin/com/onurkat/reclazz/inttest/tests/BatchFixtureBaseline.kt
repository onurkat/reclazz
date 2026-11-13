package com.onurkat.reclazz.inttest.tests

import com.onurkat.reclazz.inttest.config.TestConfig
import java.nio.file.Files
import java.nio.file.Path

/** Includes every baseline a Batch A scenario edits, including its exception paths. */
class BatchFixtureBaseline(private val config:TestConfig) {
    private val relative=listOf("web/src/com/onurkat/reclazztest/controllers/ImpexUpdateProbeController.java","src/com/onurkat/reclazztest/batch/ProbeOccErrorService.java","src/com/onurkat/reclazztest/occ/OccErrorProbeController.java","web/src/com/onurkat/reclazztest/controllers/NewOccControllerProbeController.java","src/com/onurkat/reclazztest/batch/ProbeFacadeProductService.java","web/src/com/onurkat/reclazztest/controllers/FacadeChainProbeController.java","src/com/onurkat/reclazztest/batch/ProbeSearchRestrictionDao.java","web/src/com/onurkat/reclazztest/controllers/SearchRestrictionProbeController.java","web/src/com/onurkat/reclazztest/controllers/VelocityTemplateProbeController.java","web/src/com/onurkat/reclazztest/controllers/StoredScriptProbeController.java","src/com/onurkat/reclazztest/batch/ProbeProcessAction.java",
        "resources/process/reclazz-process-action.xml",
        "web/src/com/onurkat/reclazztest/controllers/ProcessActionProbeController.java","src/com/onurkat/reclazztest/services/ProbeScheduledMetadataJob.java",
        "src/com/onurkat/reclazztest/batch/ProbeScheduledMetadataState.java",
        "web/src/com/onurkat/reclazztest/controllers/ScheduledMetadataProbeController.java","web/src/com/onurkat/reclazztest/controllers/TriggerProbeController.java",
        "src/com/onurkat/reclazztest/batch/ExecutionProbe.java","src/com/onurkat/reclazztest/batch/ProbeOccDateParser.java",
        "src/com/onurkat/reclazztest/occ/OccDateProbeController.java","src/com/onurkat/reclazztest/batch/ProbeImpexCellDecorator.java",
        "src/com/onurkat/reclazztest/batch/ProbeImpexState.java",
        "src/com/onurkat/reclazztest/batch/ProbeImpexValueTranslator.java",
        "web/src/com/onurkat/reclazztest/controllers/ImpexTranslatorProbeController.java",
        "src/com/onurkat/reclazztest/batch/ProbeLoggerState.java",
        "web/src/com/onurkat/reclazztest/controllers/LoggerProbeController.java","project.properties","resources/reclazztest-spring.xml",
        "src/com/onurkat/reclazztest/batch/ProbeQueryDao.java",
        "src/com/onurkat/reclazztest/batch/ProbeInitDefaultsInterceptor.java",
        "src/com/onurkat/reclazztest/batch/ProbeInitDefaultsState.java",
        "web/src/com/onurkat/reclazztest/controllers/InitDefaultsProbeController.java",
        "src/com/onurkat/reclazztest/batch/ProbeLoadInterceptor.java",
        "src/com/onurkat/reclazztest/batch/ProbeLoadState.java",
        "web/src/com/onurkat/reclazztest/controllers/LoadProbeController.java",
        "src/com/onurkat/reclazztest/batch/ProbeRemoveInterceptor.java",
        "src/com/onurkat/reclazztest/batch/ProbeRemoveState.java",
        "web/src/com/onurkat/reclazztest/controllers/RemoveProbeController.java",
        "src/com/onurkat/reclazztest/batch/ProbePrepareInterceptor.java",
        "src/com/onurkat/reclazztest/batch/ProbePrepareState.java",
        "web/src/com/onurkat/reclazztest/controllers/PrepareProbeController.java",
        "src/com/onurkat/reclazztest/batch/DynamicLabelHandler.java",
        "web/src/com/onurkat/reclazztest/controllers/DynamicProbeController.java",
        "web/src/com/onurkat/reclazztest/controllers/DaoProbeController.java","resources/reclazztest-batch-spring.xml","resources/reclazztest-si-probe-spring.xml",
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
    private val constraints=listOf("src/com/onurkat/reclazztest/batch/ProbeConstraintGroups.java",
        "src/com/onurkat/reclazztest/batch/ProbeValidationState.java",
        "web/src/com/onurkat/reclazztest/controllers/ValidationConstraintsProbeController.java")
    fun checkBytes() {
        for(rel in constraints) {
            val baseline=javaClass.classLoader.getResourceAsStream("templates/constraints-stage1/$rel")!!.use {it.readBytes()}
            check(Files.readAllBytes(Path.of(config.extPath,rel)).contentEquals(baseline)) {"Constraint fixture not restored: $rel"}
        }
        for(rel in relative) {
            val baseline=javaClass.classLoader.getResourceAsStream("templates/batch/$rel")!!.use {it.readBytes()}
            check(Files.readAllBytes(Path.of(config.extPath,rel)).contentEquals(baseline)) {"Batch A fixture not restored: $rel"}
        }
    }
}
