/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.setup

import com.onurkat.reclazz.plugin.hybris.SetupCheck
import com.onurkat.reclazz.plugin.settings.McpClient
import java.nio.file.Path

/**
 * The setup wizard's model. Setup is short on purpose:
 *
 * - SAP Commerce: install the Reclazz Java agent into the properties, run `ant server`, and optionally connect an AI
 *   assistant to the Reclazz MCP server.
 * - Any other project: turn Reclazz on (the IDE adds the Java agent to Java run configurations), and optionally connect
 *   an AI assistant.
 *
 * Everything here is a pure function of [SetupFacts], so every branch is unit-tested without an IDE. The wizard does not
 * judge the user's own configuration; a step is Done when its own result is in place.
 */
internal data class AssistantFact(val client: McpClient, val detected: Boolean, val connected: Boolean)

internal data class SetupFacts(
    val enabled: Boolean,
    val pluginVersion: String,
    /** The SAP Commerce installation this project builds, or null for any other project. */
    val hybrisHome: Path? = null,
    val findings: List<SetupCheck.Finding> = emptyList(),
    val antReady: Boolean = false,
    val antRunning: Boolean = false,
    val antFailure: String? = null,
    /** ant server finished with exit 0 while the wizard was open. */
    val antRan: Boolean = false,
    /** What Install would write (target file, then the property lines), or the installer's reason to refuse. */
    val installPreview: String? = null,
    val assistants: List<AssistantFact> = emptyList(),
    val assistantSkipped: Boolean = false,
) {
    val sapCommerce: Boolean get() = hybrisHome != null
    fun finding(id: String): SetupCheck.Finding? = findings.firstOrNull { it.id == id }
}

/** Something the user can do from a step. [preview] says exactly what will change before anything does. */
internal sealed class SetupAction(val label: String) {
    object TurnOn : SetupAction("Turn on Reclazz")
    object Install : SetupAction("Install Reclazz")
    object Update : SetupAction("Update the installation")
    object RunAntServer : SetupAction("Run ant server")
    object CancelAntServer : SetupAction("Stop ant server")
    class ConnectAssistant(val client: McpClient) : SetupAction("Connect ${client.display.substringBefore(" (")}")
    object SkipAssistant : SetupAction("Skip")
    object CheckAgain : SetupAction("Check again")
}

internal sealed class StepStatus {
    data class Done(val summary: String) : StepStatus()
    data class NeedsAction(val problem: String, val actions: List<SetupAction>) : StepStatus()
    /** Something is running (ant server); rechecked automatically. */
    data class Waiting(val what: String, val actions: List<SetupAction>) : StepStatus()
    data class Skipped(val why: String) : StepStatus()

    val finished: Boolean get() = this is Done || this is Skipped
}

internal class SetupStep(
    val id: String,
    val title: String,
    val explanation: String,
    val optional: Boolean = false,
    private val appliesTo: (SetupFacts) -> Boolean = { true },
    private val evaluate: (SetupFacts) -> StepStatus,
) {
    fun applies(facts: SetupFacts) = appliesTo(facts)
    fun status(facts: SetupFacts): StepStatus = evaluate(facts)
}

internal data class EvaluatedStep(val step: SetupStep, val status: StepStatus, val reachable: Boolean)

internal object SetupFlow {

    val steps: List<SetupStep> = listOf(
        SetupStep("install", "Install Reclazz",
            "Adds the Reclazz Java agent to tomcat.javaoptions and tomcat.debugjavaoptions in a marked block of " +
                "local.properties, so it survives `ant clean all`. Uninstall removes exactly that block.",
            appliesTo = { it.sapCommerce }) { f -> install(f) },
        SetupStep("ant", "Run ant server",
            "The server starts from the configuration `ant server` generates. Run it once so the next server start " +
                "loads the Java agent. Then restart your server the way you always do.",
            appliesTo = { it.sapCommerce }) { f ->
            val ant = f.finding("ant-server")
            val restart = "Restart your SAP Commerce server and Reclazz starts with it."
            when {
                f.antRunning -> StepStatus.Waiting("ant server is running.", listOf(SetupAction.CancelAntServer))
                ant?.level == SetupCheck.Level.OK -> StepStatus.Done("Done. $restart")
                // A hand-made setup (placeholders, own paths) cannot always be compared; the user's own run is trusted.
                ant?.level == SetupCheck.Level.WARN && f.antRan -> StepStatus.Done("ant server finished. $restart")
                ant?.level == SetupCheck.Level.WARN && manual(f) ->
                    StepStatus.Done("Your own setup cannot be compared with the generated configuration. Run `ant server` after you change it. $restart")
                f.antFailure != null -> StepStatus.NeedsAction("The last run failed (${f.antFailure}). See the output below.", listOf(SetupAction.RunAntServer))
                !f.antReady -> StepStatus.NeedsAction("Run `ant server` in bin/platform yourself, then check again.", listOf(SetupAction.CheckAgain))
                f.antRan -> StepStatus.NeedsAction("ant server finished, but the generated configuration still does not load the Java agent. " +
                    "Check that no other properties file overrides tomcat.javaoptions, then run it again.", listOf(SetupAction.RunAntServer))
                else -> StepStatus.NeedsAction("Not run since the install.", listOf(SetupAction.RunAntServer))
            }
        },
        SetupStep("turn-on", "Turn on Reclazz",
            "Reclazz adds its Java agent to the Java run configurations you start from IntelliJ. Run or debug your " +
                "application as usual and save a change to reload it.",
            appliesTo = { !it.sapCommerce }) { f ->
            if (f.enabled) StepStatus.Done("On. Run or debug your application as usual.")
            else StepStatus.NeedsAction("Reclazz is off in this project.", listOf(SetupAction.TurnOn))
        },
        SetupStep("assistant", "Connect your AI assistant",
            "Adds the Reclazz MCP server to your AI assistant, so it can see your reloads and check its own edits " +
                "without a restart.", optional = true) { f ->
            val connected = f.assistants.filter { it.connected }
            when {
                connected.isNotEmpty() -> StepStatus.Done("Connected: ${connected.joinToString { it.client.display.substringBefore(" (") }}.")
                f.assistantSkipped -> StepStatus.Skipped("Skipped. You can connect one later in Settings > Tools > Reclazz.")
                else -> StepStatus.NeedsAction("Pick the assistant you use.",
                    f.assistants.sortedByDescending { it.detected }.map { SetupAction.ConnectAssistant(it.client) } + SetupAction.SkipAssistant)
            }
        },
    )

    /** The Java agent was put into the properties by hand, outside the block Reclazz manages: Reclazz leaves it alone. */
    private fun manual(f: SetupFacts) = f.finding("agent-line")?.level == SetupCheck.Level.WARN

    private fun install(f: SetupFacts): StepStatus {
        val line = f.finding("agent-line")
        val jar = f.finding("agent-jar")
        val jdk = f.finding("jdk")
        val installed = line != null && !(line.message.startsWith("Exactly one") && jar?.level == SetupCheck.Level.SKIP)
        // The wizard words every problem itself: finding texts are written for Settings and name its buttons.
        return when {
            !installed -> StepStatus.NeedsAction("Not installed yet.", listOf(SetupAction.Install))
            manual(f) && !f.enabled -> StepStatus.NeedsAction("Your own Java agent setup is in place, but Reclazz is off in this project.", listOf(SetupAction.TurnOn))
            manual(f) -> StepStatus.Done("Already set up by hand in your properties. Reclazz leaves it as it is.")
            line!!.level == SetupCheck.Level.FAIL -> StepStatus.NeedsAction("The Reclazz block in your properties is incomplete. " +
                "Update the installation to write it again.", listOf(SetupAction.Update))
            jar?.level == SetupCheck.Level.FAIL -> StepStatus.NeedsAction("The Java agent file your properties point to is missing or unreadable. " +
                "Update the installation to copy it again.", listOf(SetupAction.Update))
            jar?.level == SetupCheck.Level.WARN && jar.message.startsWith("Agent jar version") ->
                StepStatus.NeedsAction("The installed Java agent is older than this plugin (${f.pluginVersion}).", listOf(SetupAction.Update))
            jdk?.level == SetupCheck.Level.FAIL && jdk.message.contains("Unsafe") -> StepStatus.NeedsAction("Your server's JDK needs one more " +
                "launch flag for the Java agent. Update the installation to add it.", listOf(SetupAction.Update))
            !f.enabled -> StepStatus.NeedsAction("Installed, but Reclazz is off in this project.", listOf(SetupAction.TurnOn))
            else -> StepStatus.Done("Installed.")
        }
    }

    /** All applicable steps with their status; a step is reachable when every step before it is finished. */
    fun evaluate(facts: SetupFacts): List<EvaluatedStep> {
        var open = true
        return steps.filter { it.applies(facts) }.map { step ->
            val status = step.status(facts)
            val evaluated = EvaluatedStep(step, status, open)
            if (!status.finished) open = false
            evaluated
        }
    }

    /** The step the wizard should show: the first one that is not finished, or the last one. */
    fun current(evaluated: List<EvaluatedStep>): EvaluatedStep = evaluated.firstOrNull { !it.status.finished } ?: evaluated.last()

    fun complete(evaluated: List<EvaluatedStep>) = evaluated.all { it.status.finished }
}
