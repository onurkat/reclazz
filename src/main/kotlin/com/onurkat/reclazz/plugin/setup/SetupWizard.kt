/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.setup

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.ScreenUtil
import com.intellij.util.ui.JBUI
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.DialogWrapper
import com.onurkat.reclazz.plugin.settings.ReclazzAppState
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.*
import com.intellij.ui.content.ContentFactory
import com.intellij.openapi.util.Disposer

/** All setup entry points share one project session, including any running ant process. */
object SetupWizard {
    const val TAB = "Setup"
    private val SESSION = Key.create<Session>("reclazz.setup.session")

    private class Session(val project: Project) : com.intellij.openapi.Disposable {
        val panel by lazy { SetupPanel(project, wizard = true).also { Disposer.register(this, it) } }
        var dialog: WizardDialog? = null
        var pending = false
        override fun dispose() { dialog?.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun isOpen(project: Project) = project.getUserData(SESSION)?.dialog != null

    fun open(project: Project) {
        val app = ApplicationManager.getApplication()
        if (project.isDisposed || app.isHeadlessEnvironment) return
        app.invokeLater({
            if (project.isDisposed) return@invokeLater
            val session = project.getUserData(SESSION) ?: Session(project).also {
                project.putUserData(SESSION, it)
                Disposer.register(project, it)
            }
            val current = session.dialog
            if (current != null) {
                current.window?.toFront()
                current.window?.requestFocus()
            } else if (!session.pending) {
                session.pending = true
                DumbService.getInstance(project).smartInvokeLater({
                    if (!project.isDisposed) session.panel.prepareDialog {
                        session.pending = false
                        if (!project.isDisposed) {
                            val dialog = WizardDialog(session)
                            session.dialog = dialog
                            dialog.show()
                        }
                    }
                }, ModalityState.nonModal())
            }
        }, ModalityState.any())
    }

    fun addTab(project: Project, window: ToolWindow): com.intellij.ui.content.Content {
        val button = JButton("Set Up Reclazz").apply { addActionListener { open(project) } }
        val page = JPanel().apply { add(button) }
        val content = ContentFactory.getInstance().createContent(page, TAB, false)
        window.contentManager.addContent(content, 0)
        return content
    }

    private class WizardDialog(private val session: Session) : DialogWrapper(session.project, false) {
        private val view = session.panel
        private val dismiss = JCheckBox("Don't show again for this project")
        private fun action(name: String, run: () -> Unit) = object : AbstractAction(name) {
            override fun actionPerformed(event: ActionEvent) = run()
        }
        private val back = action("Back") { view.move(-1) }
        private var finishAvailable = false
        private val next = action("Next") {
            if (finishAvailable) {
                ReclazzSettings.getInstance(session.project).state.completedSetupGeneration = ReclazzAppState.getInstance().state.setupGeneration
                close(OK_EXIT_CODE)
            } else view.move(1)
        }
        private val skip = action("Skip") { view.skipOptional() }

        init {
            title = "Set Up Reclazz"
            isModal = false
            isResizable = true
            setCancelButtonText("Close")
            listOf(back, next, skip).forEach { it.isEnabled = false }
            view.onNavigation = { canGoBack, forward, canSkip ->
                back.isEnabled = canGoBack
                finishAvailable = forward == "Finish"
                next.putValue(Action.NAME, forward ?: "Next")
                next.isEnabled = forward != null
                skip.isEnabled = canSkip
            }
            init()
            view.refresh()
        }

        override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
            val screen = ScreenUtil.getScreenRectangle(WindowManager.getInstance().getFrame(session.project) ?: view.component)
            val wanted = view.preferredPageSize((screen.width * 0.85).toInt() - JBUI.scale(32))
            // Later states add wrapped status messages and an Ant console to the measured cards.
            val centerHeight = maxOf(wanted.height + dismiss.preferredSize.height, JBUI.scale(600))
            preferredSize = Dimension(wanted.width, minOf(centerHeight, ((screen.height * 0.85).toInt() - JBUI.scale(54)).coerceAtLeast(1)))
            add(view.component, BorderLayout.CENTER)
            add(dismiss, BorderLayout.SOUTH)
        }
        override fun getDimensionServiceKey() = "Reclazz.SetupWizard"
        override fun createActions(): Array<Action> = arrayOf(back, next, skip, cancelAction)
        override fun dispose() {
            if (dismiss.isSelected && !session.project.isDisposed) {
                ReclazzSettings.getInstance(session.project).state.dismissedSetupGeneration = ReclazzAppState.getInstance().state.setupGeneration
            }
            session.dialog = null
            view.onNavigation = null
            super.dispose()
        }
    }
}

/** Tools > Set Up Reclazz. */
class OpenSetupWizardAction : AnAction("Set Up Reclazz"), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
    override fun actionPerformed(e: AnActionEvent) { e.project?.let(SetupWizard::open) }
}
