/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.panel
import com.onurkat.reclazz.plugin.hybris.HybrisProjectDetector
import com.onurkat.reclazz.plugin.ReclazzActivation
import com.onurkat.reclazz.plugin.notifications.ReloadNotifications
import javax.swing.Action
import javax.swing.JComponent

/**
 * The introduction to Reclazz, opened on demand from the first-run
 * notification (see ReloadNotifications.welcome) or from Settings.
 *
 * It used to be shown directly from the startup activity. Marketplace
 * automated review caught that: a modal dialog on the event thread at
 * IDE startup hangs the IDE until something clicks it, so the review run
 * timed out after ten minutes and reported a second, unrelated-looking
 * failure about a missing trial widget, which was just the same IDE stuck
 * behind our dialog.
 *
 * Reclazz starts switched OFF. It works by putting a `-javaagent` into
 * the JVM that runs your code, and a tool that does that deserves to ask
 * first rather than to be discovered later in a command line. So this
 * dialog exists to get one explicit "yes", and to name the single next
 * step, which differs by project:
 *
 *  - SAP Commerce servers start outside the IDE, so the agent has to go
 *    into the Tanuki wrapper config. That is a real action with a real
 *    consequence (a server restart), and it is the step users previously
 *    had to discover on their own inside Settings.
 *  - Everything else runs from the IDE, where the agent is injected into
 *    run configurations automatically once enabled. Nothing else to do.
 */
class WelcomeDialog(private val project: Project) : DialogWrapper(project, false) {

    private val isHybris = HybrisProjectDetector.isHybrisProject(project)

    /** True when the user accepted; the caller acts on this. */
    var accepted: Boolean = false
        private set

    init {
        title = "Reclazz"
        setOKButtonText(if (isHybris) "Install Agent" else "Enable Reclazz")
        setCancelButtonText("Not now")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label("Hot-reload without restarting your server.").applyToComponent {
                font = font.deriveFont(font.size + 3f).deriveFont(java.awt.Font.BOLD)
            }
        }
        row {
            comment(
                "Edit your Java code, build, and the running JVM picks up the " +
                "change: Spring beans are refreshed, MVC mappings re-scanned, " +
                "caches evicted. No restart, and your application state survives."
            )
        }
        separator()

        if (isHybris) {
            row {
                label("SAP Commerce project detected").applyToComponent {
                    font = font.deriveFont(java.awt.Font.BOLD)
                }
            }
            row {
                comment(
                    "Your server starts outside the IDE, so Reclazz adds its agent " +
                    "to the platform properties it starts from, so the setting survives " +
                    "ant clean all instead of being wiped by it. It takes effect after " +
                    "the next config regeneration (ant server) and server restart."
                )
            }
        } else {
            row {
                comment(
                    "Reclazz will add its agent to the run configurations you start " +
                    "from the IDE. Nothing else to set up: run your application and " +
                    "build after an edit."
                )
            }
        }

        separator()
        row {
            comment(
                "Everything stays local: no telemetry, no analytics, no outbound " +
                "requests. You can turn Reclazz off again at any time in " +
                "Settings > Tools > Reclazz."
            )
        }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)

    override fun doOKAction() {
        accepted = true
        super.doOKAction()
    }

    companion object {

        /**
         * Open the introduction because the user asked for it, and apply the
         * answer. Never call this from a startup activity: a modal dialog
         * shown while the IDE is starting blocks the event thread until
         * someone clicks it, which is a hang for anything automated and rude
         * for everyone else. The first-run path posts a notification instead
         * and only lands here if the user clicks through it.
         */
        fun showOnDemand(project: Project) {
            val dialog = WelcomeDialog(project)
            dialog.show()

            if (dialog.accepted) {
                ReclazzActivation.enable(project)
            } else {
                ReloadNotifications.info(
                    project, "Reclazz",
                    "Reclazz is off. Turn it on any time in Settings > Tools > Reclazz."
                )
            }
        }
    }
}
