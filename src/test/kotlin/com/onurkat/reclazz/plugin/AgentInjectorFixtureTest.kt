/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.intellij.execution.ExecutionException
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.configurations.JavaParameters
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.onurkat.reclazz.plugin.agent.AgentInjector
import com.onurkat.reclazz.plugin.agent.AgentJarLocator
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import java.io.File
import kotlin.test.assertEquals as expectEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue as expectTrue

/** Real launch preparation, with only the installed jar location supplied by the test. */
class AgentInjectorFixtureTest : BasePlatformTestCase() {
    private val jar = File("/fixture tools/reclazz-agent.jar")
    private lateinit var injector: AgentInjector
    private lateinit var configuration: ApplicationConfiguration

    override fun setUp() {
        super.setUp()
        ReclazzSettings.getInstance(project).state.apply {
            enabled = true
            autoDetectJdk = false
        }
        injector = AgentInjector { jar }
        configuration = ApplicationConfiguration("Fixture", project)
    }

    fun testRepeatedInjectionPreservesOtherAgentsAndArguments() {
        val params = JavaParameters()
        val existing = listOf("-Xmx512m", "-javaagent:/coverage tools/jacoco.jar=destfile=a b",
            "-javaagent:C:\\tools\\trace.jar", "-Dnote=-javaagent:/reclazz-agent-1.0.jar")
        existing.forEach { params.vmParametersList.add(it) }
        injector.updateJavaParameters(configuration, params, null)
        val once = params.vmParametersList.parameters.toList()
        expectEquals(existing + expected(), once)
        injector.updateJavaParameters(configuration, params, null)
        expectEquals(once, params.vmParametersList.parameters)
    }

    fun testExplicitQuotedIdenticalAgentIsReused() {
        val params = JavaParameters()
        params.vmParametersList.addParametersString("-Xmx512m \"${expected()}\" -Dkeep=yes")
        val original = params.vmParametersList.parameters.toList()
        expectEquals(listOf("-Xmx512m", expected(), "-Dkeep=yes"), original)
        injector.updateJavaParameters(configuration, params, null)
        expectEquals(original, params.vmParametersList.parameters)
    }

    fun testDifferentOptionsOrJarAndDuplicateFlagsRejectLaunchWithoutChangingArguments() {
        for (entries in listOf(
            listOf(expected() + ",verbose=true"),
            listOf("-javaagent:/older/reclazz-agent-1.2.0.jar"),
            listOf("-javaagent:C:\\old tools\\reclazz-agent.jar=verbose=true"),
            listOf(expected(), expected()),
            listOf(expected(), "-javaagent:/old/reclazz-agent-1.2.0-all.jar")
        )) {
            val params = JavaParameters()
            val original = listOf("-Xmx512m") + entries
            original.forEach { params.vmParametersList.add(it) }
            val error = assertFailsWith<ExecutionException> {
                injector.updateJavaParameters(configuration, params, null)
            }
            expectTrue(error.message!!.contains("Conflicting Reclazz"))
            expectTrue(error.message!!.contains("remove the manual"))
            expectEquals(original, params.vmParametersList.parameters)
        }
    }

    fun testOtherAgentNamesAndOptionValuesArePreserved() {
        val params = JavaParameters()
        val original = listOf("-javaagent:/reclazz-agent-helper.jar",
            "-javaagent:/other.jar=path=/reclazz-agent.jar", "-Dagent=-javaagent:/reclazz-agent.jar")
        original.forEach { params.vmParametersList.add(it) }
        injector.updateJavaParameters(configuration, params, null)
        expectEquals(original + expected(), params.vmParametersList.parameters)
    }

    fun testAgentInParameterGroupIsRecognized() {
        val params = JavaParameters()
        params.vmParametersList.addParamsGroup("manual").addParameter(expected())
        injector.updateJavaParameters(configuration, params, null)
        expectEquals(listOf(expected()), params.vmParametersList.list)
        params.vmParametersList.addParamsGroup("conflict")
            .addParameter("-javaagent:/old/reclazz-agent-1.2.0.jar")
        val original = params.vmParametersList.list.toList()
        assertFailsWith<ExecutionException> {
            injector.updateJavaParameters(configuration, params, null)
        }
        expectEquals(original, params.vmParametersList.list)
    }

    private fun expected() = "-javaagent:${jar.absolutePath}=" +
        AgentJarLocator.buildAgentArgs(project, ReclazzSettings.getInstance(project).state)
}
