/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.components.ActionLink
import com.onurkat.reclazz.plugin.hybris.SetupCheck
import com.onurkat.reclazz.plugin.settings.ReclazzConfigurable
import com.onurkat.reclazz.plugin.settings.SetupCheckUi
import com.onurkat.reclazz.plugin.support.SyntheticProject
import java.awt.Component
import java.awt.Container
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import kotlin.test.assertContains
import kotlin.test.assertEquals as expectEquals
import kotlin.test.assertFalse as expectFalse
import kotlin.test.assertTrue as expectTrue

class SetupCheckUiTest : BasePlatformTestCase() {
    private val findings=listOf(SetupCheck.Finding("ant-server",SetupCheck.Level.FAIL,"Launch differs","Run ant server"),SetupCheck.Finding("agent-line",SetupCheck.Level.WARN,"Manual install","Keep the manual configuration"))
    private fun descendants(component:Component):List<Component> = listOf(component)+(if(component is Container) component.components.flatMap(::descendants) else emptyList())
    private fun await(done:CompletableFuture<*>) {
        val end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while(!done.isDone && System.nanoTime()<end){PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();Thread.sleep(10)}
        expectTrue(done.isDone,"setup check did not finish");done.join()
    }
    fun testSettingsLinkRunsOffEdtAndRendersFindingsWithFixes() {
        SyntheticProject(project,true).use { fixture ->
            val configurable=ReclazzConfigurable(fixture.project);var done:CompletableFuture<*>?=null;val rendered=AtomicBoolean()
            configurable.startSetupCheck={show,message ->
                expectTrue(show);expectEquals(null,message)
                done=SetupCheckUi.run(fixture.project,show,message,collect={expectFalse(SwingUtilities.isEventDispatchThread());findings},present={rows ->
                    expectTrue(SwingUtilities.isEventDispatchThread());val text=descendants(SetupCheckUi.panel(rows)).filterIsInstance<JTextArea>().joinToString("\n") { it.text }
                    assertContains(text,"FAIL: Launch differs");assertContains(text,"Run ant server");assertContains(text,"Manual install");rendered.set(true)
                },notify={error("Unexpected notification: $it")})
            }
            try {
                val link=descendants(configurable.createComponent()).filterIsInstance<ActionLink>().single { it.text=="Check setup" }
                link.doClick();await(checkNotNull(done));expectTrue(rendered.get())
            } finally { configurable.disposeUIResources() }
        }
    }
    fun testSuccessfulInstallStartsAutomaticCheckAndAppendsSummary() {
        SyntheticProject(project,true).use { fixture ->
            val configurable=ReclazzConfigurable(fixture.project) { com.onurkat.reclazz.plugin.hybris.HybrisAgentInstaller.Result.Success("Synthetic install succeeded") }
            var done:CompletableFuture<*>?=null;val notices=mutableListOf<String>()
            configurable.startSetupCheck={show,message ->
                expectFalse(show);expectEquals("Synthetic install succeeded",message)
                done=SetupCheckUi.run(fixture.project,show,message,collect={expectFalse(SwingUtilities.isEventDispatchThread());findings},present={error("Automatic check opened a dialog")},notify={notices.add(it)})
            }
            try {
                descendants(configurable.createComponent()).filterIsInstance<javax.swing.JButton>().single { it.text=="Install Java Agent" }.doClick()
                await(checkNotNull(done));expectEquals("Synthetic install succeeded",notices.first());assertContains(notices.last(),"1 failed");assertContains(notices.last(),"1 warning")
            } finally { configurable.disposeUIResources() }
        }
    }
    fun testNonSapSettingsDoNotOfferSapSetupCheck() {
        val configurable=ReclazzConfigurable(project)
        try { expectFalse(descendants(configurable.createComponent()).filterIsInstance<ActionLink>().any { it.text=="Check setup" }) }
        finally { configurable.disposeUIResources() }
    }
    fun testOpeningSettingsAndCheckingDoesNotCreateOptionalConfigDirectory() {
        SyntheticProject(project,true).use { fixture ->
            val missing=fixture.home.resolve("config/absent")
            java.nio.file.Files.writeString(fixture.home.resolve("config/local.properties"),"hybris.optional.config.dir=\${HYBRIS_CONFIG_DIR}/absent\n")
            val configurable=ReclazzConfigurable(fixture.project)
            try {
                var done:CompletableFuture<*>?=null
                configurable.startSetupCheck={show,message -> done=SetupCheckUi.run(fixture.project,show,message,present={}) }
                val component=configurable.createComponent()
                expectFalse(java.nio.file.Files.exists(missing),"opening the Check setup surface must remain read-only")
                descendants(component).filterIsInstance<ActionLink>().single{it.text=="Check setup"}.doClick();await(checkNotNull(done))
                expectFalse(java.nio.file.Files.exists(missing),"real Settings path and collect must remain read-only")
            } finally { configurable.disposeUIResources() }
        }
    }

    fun testReinstallAlsoAppendsAutomaticCheckSummary() {
        SyntheticProject(project,true).use { fixture ->
            com.onurkat.reclazz.plugin.hybris.HybrisAgentInstaller.installConfiguration(fixture.home.resolve("bin/platform"),fixture.home.resolve("config"),"-javaagent:/synthetic/reclazz-agent.jar")
            val configurable=ReclazzConfigurable(fixture.project) { com.onurkat.reclazz.plugin.hybris.HybrisAgentInstaller.Result.Success("Synthetic reinstall succeeded") }
            var done:CompletableFuture<*>?=null;val notices=mutableListOf<String>()
            configurable.startSetupCheck={show,message ->
                expectFalse(show)
                done=SetupCheckUi.run(fixture.project,show,message,collect={findings},present={error("Automatic dialog")},notify={notices.add(it)})
            }
            try {
                descendants(configurable.createComponent()).filterIsInstance<javax.swing.JButton>().single { it.text=="Reinstall" }.doClick()
                await(checkNotNull(done));expectEquals("Synthetic reinstall succeeded",notices.first());assertContains(notices.last(),"1 failed")
            } finally { configurable.disposeUIResources() }
        }
    }
    fun testCancelledAutomaticCheckStillReportsSuccessfulInstall() {
        val notices=mutableListOf<String>()
        val done=SetupCheckUi.run(project,false,"Synthetic install succeeded",collect={
            val indicator=checkNotNull(com.intellij.openapi.progress.ProgressManager.getInstance().progressIndicator)
            indicator.cancel();indicator.checkCanceled();findings
        },present={error("Cancelled check presented findings")},notify={notices.add(it)})
        kotlin.test.assertFailsWith<java.util.concurrent.CancellationException> { await(done) }
        expectEquals("Synthetic install succeeded",notices.first());assertContains(notices.last(),"cancelled")
    }

    private fun assertPostingModality(outcome:String) {
        val expected=com.intellij.openapi.application.ModalityState.any();val posted=mutableListOf<com.intellij.openapi.application.ModalityState>();val notices=mutableListOf<String>()
        val done=SetupCheckUi.run(project,false,"Installed",collect={
            if(outcome=="cancel")throw com.intellij.openapi.progress.ProcessCanceledException()
            if(outcome=="error")throw java.io.IOException("synthetic failure")
            findings
        },notify={notices.add(it)},captureModality={expectTrue(SwingUtilities.isEventDispatchThread());expected},post={state,action ->
            posted.add(state);com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(action,state)
        })
        when(outcome){
            "cancel" -> kotlin.test.assertFailsWith<java.util.concurrent.CancellationException>{await(done)}
            "error" -> kotlin.test.assertFailsWith<java.util.concurrent.CompletionException>{await(done)}
            else -> await(done)
        }
        expectTrue(posted.isNotEmpty());expectTrue(posted.all { it===expected },"completion must use captured Settings modality: $posted")
    }
    fun testSuccessUsesCapturedSettingsModality()=assertPostingModality("success")
    fun testCancellationUsesCapturedSettingsModality()=assertPostingModality("cancel")
    fun testFailureUsesCapturedSettingsModality()=assertPostingModality("error")
    fun testInstallSuccessDoesNotWaitForSetupCollector() {
        val release=java.util.concurrent.CountDownLatch(1);val notices=mutableListOf<String>()
        val done=SetupCheckUi.run(project,false,"Installed immediately",collect={release.await(5,java.util.concurrent.TimeUnit.SECONDS);findings},notify={notices.add(it)})
        try {expectEquals("Installed immediately",notices.firstOrNull(),"installation success must be reported before the slow setup check")}
        finally {release.countDown();await(done)}
        assertContains(notices.last(),"Setup check:")
    }

    fun testRealSettingsCollectUsesPluginVersionSingleDoctorConnectionAndSemicolonNames() {
        SyntheticProject(project,true).use { fixture ->
            val version="9.8.7"
            val jar=fixture.home.resolve("reclazz-agent.jar")
            val manifest=java.util.jar.Manifest().apply{mainAttributes.putValue("Manifest-Version","1.0");mainAttributes.putValue("Implementation-Version","synthetic-old")}
            java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar),manifest).use{}
            val bundled=fixture.home.resolve("bundled-agent.jar");val bundleManifest=java.util.jar.Manifest().apply{mainAttributes.putValue("Manifest-Version","1.0");mainAttributes.putValue("Implementation-Version",version)}
            java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(bundled),bundleManifest).use{}
            java.nio.file.Files.writeString(fixture.home.resolve("config/local.properties"),"tomcat.javaoptions=-javaagent:$jar\ntomcat.debugjavaoptions=-javaagent:$jar\n")
            java.nio.file.Files.writeString(fixture.home.resolve("config/localextensions.xml"),"<hybrisconfig><extensions><extension name=\"a\"/><extension name=\"b\"/></extensions></hybrisconfig>")
            java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                val seen=CompletableFuture<String>();server.soTimeout=5000
                val worker=kotlin.concurrent.thread(isDaemon=true){
                    try{server.accept().use{socket ->
                        socket.soTimeout=5000;val reader=socket.getInputStream().bufferedReader();val writer=socket.getOutputStream().bufferedWriter()
                        writer.write("{\"level\":\"CONNECTED\",\"message\":\"ready\",\"agent\":\"$version\",\"version\":1}\n");writer.flush()
                        val command=checkNotNull(reader.readLine()){ "An extra reachability connection was opened" };seen.complete(command);val id=command.substringAfter(' ')
                        val payload="{\"requestId\":\"$id\",\"status\":\"observed\",\"agentVersion\":\"$version\",\"watcherState\":\"watching\",\"watchedDirectoryCount\":2,\"unwatchableDirectoryCount\":0,\"buildHold\":\"idle\"}"
                        val message="DOCTOR_RESULT $id $payload".replace("\\","\\\\").replace("\"","\\\"")
                        writer.write("{\"level\":\"INFO\",\"message\":\"$message\"}\n");writer.flush();reader.readLine()
                    }}catch(error:Throwable){seen.completeExceptionally(error)}
                }
                val state=com.onurkat.reclazz.plugin.settings.ReclazzSettings.getInstance(fixture.project).state;state.agentPort=server.localPort;state.watchExtensions="a,b"
                val configurable=ReclazzConfigurable(fixture.project);var done:CompletableFuture<*>?=null;var rows=listOf<SetupCheck.Finding>()
                configurable.startSetupCheck={show,message -> done=SetupCheckUi.run(fixture.project,show,message,collect={SetupCheckUi.collect(fixture.project,bundledAgent={bundled.toFile()})},present={rows=it}) }
                try{
                    descendants(configurable.createComponent()).filterIsInstance<ActionLink>().single{it.text=="Check setup"}.doClick();await(checkNotNull(done))
                    expectEquals(SetupCheck.Level.WARN,rows.single{it.id=="agent-jar"}.level);assertContains(rows.single{it.id=="agent-jar"}.message,"plugin $version")
                    expectEquals(SetupCheck.Level.OK,rows.single{it.id=="server"}.level);assertContains(rows.single{it.id=="server"}.message,"DOCTOR")
                    expectTrue(seen.get(3,java.util.concurrent.TimeUnit.SECONDS).startsWith("DOCTOR "));worker.join(3000);expectFalse(worker.isAlive)
                    expectEquals(SetupCheck.Level.WARN,rows.single{it.id=="extensions"}.level);assertContains(rows.single{it.id=="extensions"}.message,"a,b")
                }finally{configurable.disposeUIResources()}
            }
        }
    }

    fun testWorkerPostsCompletionBeforeFrameworkEdtCallback() {
        val posted=java.util.concurrent.CountDownLatch(1)
        val done=SetupCheckUi.run(project,false,collect={findings},post={state,action ->
            expectFalse(SwingUtilities.isEventDispatchThread(),"posting from Task.onSuccess would already be blocked by modal Settings")
            posted.countDown();com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(action,state)
        })
        expectTrue(posted.await(5,java.util.concurrent.TimeUnit.SECONDS),"worker must post without an EDT callback first")
        await(done)
    }

    fun testWorkerTaskLaunchRetainsSettingsModality() {
        val expected=com.intellij.openapi.application.ModalityState.any();val launchState=CompletableFuture<com.intellij.openapi.application.ModalityState>()
        val done=SetupCheckUi.run(project,false,collect={findings},captureModality={expected},startTask={task ->
            launchState.complete(com.intellij.openapi.application.ModalityState.defaultModalityState())
            com.intellij.openapi.progress.ProgressManager.getInstance().run(task)
        })
        await(done);expectTrue(launchState.join()===expected,"ProgressManager.run must inherit Settings modality before scheduling the task")
    }

}
