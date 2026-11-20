/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.plugin.settings

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.onurkat.reclazz.plugin.PluginPresence
import com.onurkat.reclazz.plugin.agent.AgentJarLocator
import com.onurkat.reclazz.plugin.hybris.HybrisProjectDetector
import com.onurkat.reclazz.plugin.hybris.SetupCheck
import com.onurkat.reclazz.plugin.hybris.SetupCheckServer
import com.onurkat.reclazz.plugin.notifications.ReloadNotifications
import com.onurkat.reclazz.plugin.reload.ReloadManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.util.concurrent.CompletableFuture
import java.util.jar.JarFile
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

internal object SetupCheckUi {
    fun collect(project:Project,bundledAgent:()->java.io.File? = AgentJarLocator::findAgentJar):List<SetupCheck.Finding> {
        ProgressManager.checkCanceled()
        val home=HybrisProjectDetector.findHybrisHome(project)
            ?: return listOf(SetupCheck.Finding("project",SetupCheck.Level.SKIP,"No SAP Commerce installation was found."))
        val bundled=bundledAgent()
        val version=try { bundled?.let { JarFile(it).use { jar -> jar.manifest?.mainAttributes?.getValue("Implementation-Version") } } } catch (_:java.io.IOException) { null }
            ?: PluginPresence.enabledDescriptor(AgentJarLocator.RECLAZZ_PLUGIN_ID.idString)?.version.orEmpty()
        val settings=ReclazzSettings.getInstance(project).state.copy()
        val input=SetupCheck.Input(home,version,settings.watchExtensions.split(';').map(String::trim).filter(String::isNotEmpty).toSet(),settings.autoCompile)
        return SetupCheck.check(input,server={
            SetupCheckServer.probe(ReloadManager.getInstance(project).readPortFile(checkPortOpen=false),checkCancelled=ProgressManager::checkCanceled)
        },checkCancelled=ProgressManager::checkCanceled)
    }
    fun summary(rows:List<SetupCheck.Finding>):String = "Setup check: ${rows.count { it.level==SetupCheck.Level.FAIL }} failed, ${rows.count { it.level==SetupCheck.Level.WARN }} warnings, ${rows.count { it.level==SetupCheck.Level.SKIP }} skipped."
    fun run(project:Project,showResults:Boolean,installedMessage:String?=null,
            collect:()->List<SetupCheck.Finding> = { collect(project) },
            present:(List<SetupCheck.Finding>)->Unit = { show(project,it) },
            notify:(String)->Unit = { ReloadNotifications.info(project,"Reclazz",it) },
            captureModality:()->ModalityState = { ModalityState.current() },
            post:(ModalityState,()->Unit)->Unit = { state,action -> ApplicationManager.getApplication().invokeLater(action,state) },
            startTask:(Task.Backgroundable)->Unit = { ProgressManager.getInstance().run(it) }):CompletableFuture<List<SetupCheck.Finding>> {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val capturedModality=captureModality()
        if(installedMessage!=null)notify(installedMessage)
        val done=CompletableFuture<List<SetupCheck.Finding>>()
        val completionQueued=java.util.concurrent.atomic.AtomicBoolean()
        fun complete(action:()->Unit) {
            if(completionQueued.compareAndSet(false,true))post(capturedModality,action)
        }
        fun cancelled() = complete {
            if(installedMessage!=null && !project.isDisposed)notify("Setup check cancelled; use Check setup to run it again.")
            done.cancel(false)
        }
        fun failed(error:Throwable) = complete {
            if(!project.isDisposed)notify("Setup check could not complete. Try Check setup again.")
            done.completeExceptionally(error)
        }
        val task=object:Task.Backgroundable(project,"Checking Reclazz setup",true) {
            override fun run(indicator:ProgressIndicator) {
                try {
                    indicator.checkCanceled();val rows=collect();indicator.checkCanceled()
                    // Backgroundable's framework onSuccess can itself wait for NON_MODAL.
                    // Queue directly from the worker, before that callback boundary.
                    complete {
                        if(project.isDisposed){done.cancel(false);return@complete}
                        try {
                            if(showResults)present(rows)
                            if(installedMessage!=null)notify(summary(rows))
                            done.complete(rows)
                        } catch(failure:Throwable){done.completeExceptionally(failure);throw failure}
                    }
                } catch(cancel:com.intellij.openapi.progress.ProcessCanceledException){cancelled()}
                  catch(error:Throwable){failed(error)}
            }
            override fun onCancel() { cancelled() }
            override fun onThrowable(error:Throwable) { failed(error) }
        }
        // Explicitly use a worker even when the test/headless progress manager runs a task inline.
        ApplicationManager.getApplication().executeOnPooledThread {
            // ProgressManager.run may reschedule startup onto the EDT. Carry the
            // Settings modality through that hop as well as through completion.
            ProgressManager.getInstance().runProcess(Runnable { startTask(task) },
                com.intellij.openapi.progress.EmptyProgressIndicator(capturedModality))
        }
        return done
    }
    fun panel(rows:List<SetupCheck.Finding>):JComponent {
        val content=JPanel().apply { layout=BoxLayout(this,BoxLayout.Y_AXIS) }
        for(row in rows)content.add(JTextArea("${row.level}: ${row.message}"+(if(row.fix.isBlank()) "" else "\n${row.fix}")).apply {
            isEditable=false;isFocusable=false;lineWrap=true;wrapStyleWord=true;isOpaque=false
            border=BorderFactory.createEmptyBorder(8,8,8,8)
            accessibleContext.accessibleName="${row.id}: ${row.level}"
        })
        return JPanel(BorderLayout()).apply {
            add(JScrollPane(content),BorderLayout.CENTER);preferredSize=Dimension(640,480)
            accessibleContext.accessibleName="Reclazz setup findings"
        }
    }
    private fun show(project:Project,rows:List<SetupCheck.Finding>) {
        object:DialogWrapper(project,false) {
            init { title="Check setup";setOKButtonText("Close");init() }
            override fun createCenterPanel()=panel(rows)
            override fun createActions():Array<Action> = arrayOf(okAction)
        }.show()
    }
}
