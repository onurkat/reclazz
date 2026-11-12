package com.onurkat.reclazz.inttest.tests

import com.fasterxml.jackson.databind.JsonNode
import com.onurkat.reclazz.inttest.report.TestResult
import com.onurkat.reclazz.inttest.report.TestStatus

/** Before this boundary no restoration, file write or native mutation may run. */
internal fun runGatedProfile(name: String, preflight: () -> String?, attempt: () -> Unit): TestResult {
    val start = System.currentTimeMillis()
    val reason = try { preflight() } catch (e: Exception) { "Preflight unavailable: ${e.message}" }
    if (reason != null) return TestResult(name, TestStatus.SKIP, System.currentTimeMillis()-start, reason)
    return try { attempt(); TestResult(name, TestStatus.PASS, System.currentTimeMillis()-start) }
    catch (e: Exception) { TestResult(name, TestStatus.FAIL, System.currentTimeMillis()-start,
        (listOf(e.message ?: e.toString()) + e.suppressed.map { "restore: ${it.message}" }).joinToString("; ")) }
}
internal fun profileGateReason(gates: JsonNode, required: Map<String, Boolean>): String? {
    for ((field, expected) in required) {
        val value = gates[field]
        if (value == null || !value.isBoolean) return "Gate $field missing or not boolean"
        if (value.asBoolean() != expected) return "Gate $field=${value.asBoolean()}, requires $expected"
    }
    return null
}

/** Fixed committed baseline, so replacing/omitting an old test cannot satisfy a count alone. */
internal val committedDefaultMembership = setOf(
    "MethodBodyChangeTest",
    "AddMethodTest",
    "RemoveMethodTest",
    "AddFieldTest",
    "RemoveFieldTest",
    "ChangeMethodSignatureTest",
    "MultiClassReloadTest",
    "ConstructorChangeTest",
    "BeanRefreshTest",
    "MvcReScanTest",
    "CacheEvictionTest",
    "SchedulerReloadTest",
    "EventListenerTest",
    "InterceptorReloadTest",
    "ImpexAutoImportTest",
    "RapidSuccessiveChangesTest",
    "SyntaxErrorRecoveryTest",
    "LargeClassReloadTest",
    "AnnotationChangeTest",
    "LambdaReloadTest",
    "CacheableConditionTest",
    "EnumTailRemoveTest",
    "InterceptorMappingAddLiveTest",
    "InterceptorMappingEditLiveTest",
    "SpringXmlPropertyLiveTest",
    "SpringXmlRefLiveTest",
    "SpringXmlRecreateLiveTest",
    "SpringXmlNewBeanLiveTest",
    "OccFieldSetLiveTest",
    "OrikaPropertyRollbackLiveTest",
    "OccRequestMappingLiveTest",
    "OccPriorityLiveTest",
    "PlatformConfigLiveTest",
    "LocalizationLiveTest",
    "ProcessXmlLiveTest",
    "NewProcessFileFirstLiveTest",
    "NewProcessBeanFirstLiveTest",
    "CronJobLiveTest",
    "SapEventLiveTest",
    "ImpexRemoveRefusalLiveTest",
    "PopulatorOccLiveTest",
    "ValidatorBodyLiveTest",
    "ValidatorParameterLiveTest",
    "FlexibleSearchLiveTest",
    "DynamicAttributeLiveTest",
    "PrepareInterceptorLiveTest", "InitDefaultsInterceptorLiveTest", "LoadInterceptorLiveTest", "RemoveInterceptorLiveTest", "LoggerLevelsLiveTest", "ImpexTranslatorLiveTest", "ValidationConstraintsLiveTest", "OccDateParsingLiveTest", "TriggerFiringLiveTest", "ScheduledMetadataLiveTest",
)
internal fun requireDefaultMembership(actual: List<String>) {
    check(actual.size == actual.toSet().size && actual.toSet() == committedDefaultMembership) {
        "Default suite lost committed membership: " + (committedDefaultMembership-actual.toSet())
    }
}

internal data class CodegenReceipt(val terminal:com.onurkat.reclazz.inttest.client.AgentEvent,
    val applies:List<com.onurkat.reclazz.inttest.client.AgentEvent>)
/** Generated filesystem events may precede ant's final OK; both must belong to this write. */
internal fun codegenReceipt(events:List<com.onurkat.reclazz.inttest.client.AgentEvent>,after:Long,owners:Set<String>):CodegenReceipt? {
    val seen=events.filter {it.sequence>after}
    check(seen.none {it.level=="ERROR"}) {"Codegen error: "+seen.filter {it.level=="ERROR"}}
    resourceFailure(seen,listOf("Codegen","reclazztest-beans.xml")){false}?.let {error(it)}
    val changed=seen.firstOrNull {it.level=="INFO" && it.message=="Beans XML changed: reclazztest-beans.xml [reclazztest]"} ?: return null
    val terminal=seen.firstOrNull {it.sequence>changed.sequence && it.level=="OK" &&
        it.message.startsWith("Codegen regen+compile done for reclazztest, DTOs ready (")} ?: return null
    val applies=owners.map {owner -> seen.firstOrNull {
        val prefix=when(it.level) {"RELOAD"->"Reloaded ";"STRUCTURAL_RELOAD"->"Structural reload: ";else->return@firstOrNull false}
        it.message.startsWith(prefix) && it.message.removePrefix(prefix).takeWhile {c->c!=' ' && c!=':'}==owner &&
            !it.message.removePrefix(prefix).substringAfter(' ',"").startsWith("(inner class)")
    } ?: return null}
    return CodegenReceipt(terminal,applies)
}
