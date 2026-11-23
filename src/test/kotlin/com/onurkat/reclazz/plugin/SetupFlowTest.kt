/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.onurkat.reclazz.plugin.hybris.SetupCheck
import com.onurkat.reclazz.plugin.hybris.SetupCheck.Level
import com.onurkat.reclazz.plugin.settings.McpClient
import com.onurkat.reclazz.plugin.setup.AssistantFact
import com.onurkat.reclazz.plugin.setup.SetupAction
import com.onurkat.reclazz.plugin.setup.SetupFacts
import com.onurkat.reclazz.plugin.setup.SetupFlow
import com.onurkat.reclazz.plugin.setup.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/** The setup wizard's decisions, every branch, without an IDE. */
class SetupFlowTest {
    private val home = Path.of("/opt/hybris")
    private val none = McpClient.entries.map { AssistantFact(it, detected = it == McpClient.CURSOR, connected = false) }

    private fun sap(vararg findings: SetupCheck.Finding, enabled: Boolean = true, antRunning: Boolean = false, antReady: Boolean = true,
                    antFailure: String? = null, assistants: List<AssistantFact> = none, skipped: Boolean = false) =
        SetupFacts(enabled = enabled, pluginVersion = "2.0.0", hybrisHome = home, findings = findings.toList(), antReady = antReady,
            antRunning = antRunning, antFailure = antFailure, assistants = assistants, assistantSkipped = skipped)

    private val line = SetupCheck.Finding("agent-line", Level.OK, "Both launch modes contain one managed Reclazz agent.")
    private val jar = SetupCheck.Finding("agent-jar", Level.OK, "Installed agent jar matches plugin 2.0.0.")
    private val antOk = SetupCheck.Finding("ant-server", Level.OK, "Both generated launch modes match.")
    private val antStale = SetupCheck.Finding("ant-server", Level.FAIL, "wrapper.conf does not match.")

    private fun status(facts: SetupFacts, id: String) = SetupFlow.evaluate(facts).first { it.step.id == id }.status
    private fun ids(facts: SetupFacts) = SetupFlow.evaluate(facts).map { it.step.id }

    @Test fun sapCommerceSetupIsInstallAntServerAndAnOptionalAssistant() {
        assertEquals(listOf("install", "ant", "assistant"), ids(sap()))
    }

    @Test fun anyOtherProjectIsTurnOnAndAnOptionalAssistant() {
        assertEquals(listOf("turn-on", "assistant"), ids(SetupFacts(enabled = false, pluginVersion = "2.0.0", assistants = none)))
    }

    @Test fun aFreshSapCommerceProjectStartsAtInstallAndNothingLaterIsReachable() {
        val steps = SetupFlow.evaluate(sap(enabled = false))
        assertEquals("install", SetupFlow.current(steps).step.id)
        assertTrue(steps.drop(1).none { it.reachable })
        assertTrue((steps[0].status as StepStatus.NeedsAction).actions.single() == SetupAction.Install)
    }

    @Test fun anInstalledJavaAgentIsDoneAndAntServerIsNext() {
        val facts = sap(line, jar, antStale)
        assertTrue(status(facts, "install") is StepStatus.Done)
        val ant = status(facts, "ant") as StepStatus.NeedsAction
        assertEquals(SetupAction.RunAntServer, ant.actions.single())
    }

    @Test fun aHandMadeInstallationIsAcceptedAsItIs() {
        val manual = SetupCheck.Finding("agent-line", Level.WARN, "Reclazz is installed manually outside its managed block.")
        val done = status(sap(manual, jar, antOk), "install") as StepStatus.Done
        assertTrue(done.summary, done.summary.contains("leaves it as it is"))
    }

    @Test fun anInstalledButSwitchedOffProjectOnlyNeedsTurningOn() {
        assertEquals(SetupAction.TurnOn, (status(sap(line, jar, enabled = false), "install") as StepStatus.NeedsAction).actions.single())
    }

    @Test fun anOlderOrMissingJavaAgentIsUpdatedInPlace() {
        val older = SetupCheck.Finding("agent-jar", Level.WARN, "Agent jar version 1.9.0; plugin 2.0.0.")
        val missing = SetupCheck.Finding("agent-jar", Level.FAIL, "A configured Reclazz jar is missing.", "Reinstall Agent before starting the server.")
        val unsafe = SetupCheck.Finding("jdk", Level.FAIL, "JDK 24 needs the Unsafe access flag in both launch modes.")
        for (finding in listOf(older, missing)) assertTrue((status(sap(line, finding), "install") as StepStatus.NeedsAction).actions.single() == SetupAction.Update)
        assertTrue((status(sap(line, jar, unsafe), "install") as StepStatus.NeedsAction).actions.single() == SetupAction.Update)
    }

    @Test fun antServerRunningWaitsAndCanBeStopped() {
        val waiting = status(sap(line, jar, antStale, antRunning = true), "ant") as StepStatus.Waiting
        assertEquals(SetupAction.CancelAntServer, waiting.actions.single())
    }

    @Test fun aFailedAntServerSaysSoAndOffersAnotherRun() {
        val failed = status(sap(line, jar, antStale, antFailure = "exit code 1"), "ant") as StepStatus.NeedsAction
        assertTrue(failed.problem.contains("exit code 1"))
        assertEquals(SetupAction.RunAntServer, failed.actions.single())
    }

    @Test fun withoutSetantenvTheUserRunsAntServerThemselves() {
        val manual = status(sap(line, jar, antStale, antReady = false), "ant") as StepStatus.NeedsAction
        assertEquals(SetupAction.CheckAgain, manual.actions.single())
    }

    @Test fun assistantsFoundOnThisComputerAreOfferedFirstAndTheStepCanBeSkipped() {
        val assistant = status(sap(line, jar, antOk), "assistant") as StepStatus.NeedsAction
        assertEquals(McpClient.CURSOR, (assistant.actions.first() as SetupAction.ConnectAssistant).client)
        assertEquals(SetupAction.SkipAssistant, assistant.actions.last())
        assertEquals(McpClient.entries.size + 1, assistant.actions.size)
    }

    @Test fun aConnectedOrSkippedAssistantCompletesSetup() {
        val connected = none.map { if (it.client == McpClient.CLAUDE) it.copy(connected = true) else it }
        val steps = SetupFlow.evaluate(sap(line, jar, antOk, assistants = connected))
        assertTrue(SetupFlow.complete(steps))
        assertEquals("Connected: Claude Code.", (steps.last().status as StepStatus.Done).summary)
        assertTrue(SetupFlow.complete(SetupFlow.evaluate(sap(line, jar, antOk, skipped = true))))
        assertFalse(SetupFlow.complete(SetupFlow.evaluate(sap(line, jar, antOk))))
    }

    @Test fun noUserFacingTextSaysAgentWithoutSayingWhichOne() {
        val texts = SetupFlow.steps.flatMap { listOf(it.title, it.explanation) } +
            listOf(SetupAction.Install, SetupAction.Update, SetupAction.TurnOn, SetupAction.RunAntServer).map { it.label }
        for (text in texts) {
            val bare = Regex("(?i)(?<!java )(?<!ai )\\bagents?\\b").find(text.replace("-javaagent", ""))
            assertTrue("bare 'agent' in: $text", bare == null)
        }
    }

    private val manual = SetupCheck.Finding("agent-line", Level.WARN, "Reclazz is installed manually outside its managed block.")
    private val olderJar = SetupCheck.Finding("agent-jar", Level.WARN, "Agent jar version 1.9.0; plugin 2.0.0.")
    private val antUnverifiable = SetupCheck.Finding("ant-server", Level.WARN, "Cannot verify a configured agent path or placeholder.")

    @Test fun aHandMadeSetupIsNeverRewrittenEvenWithAnOlderJar() {
        assertTrue(status(sap(manual, olderJar), "install") is StepStatus.Done)
    }

    @Test fun aHandMadeSetupThatCannotBeComparedDoesNotBlockAntServer() {
        assertTrue(status(sap(manual, jar, antUnverifiable), "ant") is StepStatus.Done)
    }

    @Test fun aSuccessfulAntRunThatStillDoesNotMatchSaysSoInsteadOfNotRun() {
        val after = status(sap(line, jar, antStale).copy(antRan = true), "ant") as StepStatus.NeedsAction
        assertFalse(after.problem, after.problem.contains("Not run"))
    }

    @Test fun noStatusTextSaysAgentWithoutSayingWhichOne() {
        val broken = SetupCheck.Finding("agent-line", Level.FAIL, "Exactly one Reclazz agent is required in tomcat.javaoptions.", "Use Install Agent / Reinstall in Settings")
        val missing = SetupCheck.Finding("agent-jar", Level.FAIL, "A configured Reclazz jar is missing.", "Reinstall Agent before starting the server.")
        val unsafe = SetupCheck.Finding("jdk", Level.FAIL, "JDK 24 needs the Unsafe access flag in both launch modes.")
        val facts = listOf(sap(), sap(enabled = false), sap(line, broken), sap(line, missing), sap(line, olderJar), sap(line, jar, unsafe),
            sap(manual, jar, enabled = false), sap(manual, olderJar, antUnverifiable), sap(line, jar, antStale), sap(line, jar, antStale, antRunning = true),
            sap(line, jar, antStale, antFailure = "exit code 1"), sap(line, jar, antStale, antReady = false), sap(line, jar, antStale).copy(antRan = true),
            sap(line, jar, antOk), sap(line, jar, antOk, skipped = true), SetupFacts(enabled = false, pluginVersion = "2.0.0", assistants = none))
        for (f in facts) for (step in SetupFlow.evaluate(f)) {
            val text = when (val s = step.status) {
                is StepStatus.Done -> s.summary; is StepStatus.Skipped -> s.why
                is StepStatus.NeedsAction -> s.problem; is StepStatus.Waiting -> s.what
            }
            assertTrue("bare 'agent' in ${step.step.id}: $text",
                Regex("(?i)(?<!java )(?<!ai )\\bagents?\\b").find(text.replace("-javaagent", "")) == null)
        }
    }
}
