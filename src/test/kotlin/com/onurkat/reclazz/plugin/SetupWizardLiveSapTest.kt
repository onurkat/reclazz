/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.onurkat.reclazz.plugin.reload.ReloadManager
import com.onurkat.reclazz.plugin.setup.AntServerRunner
import com.onurkat.reclazz.plugin.setup.SetupAction
import com.onurkat.reclazz.plugin.setup.SetupActionRunner
import com.onurkat.reclazz.plugin.setup.SetupFactsCollector
import com.onurkat.reclazz.plugin.setup.SetupFlow
import com.onurkat.reclazz.plugin.setup.SetupPanel
import com.onurkat.reclazz.plugin.setup.StepStatus
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Live acceptance of the setup wizard on the local SAP Commerce installation, from a project where Reclazz was never set
 * up: the agent lines are removed from local.properties first, then the wizard's own actions are applied step by step
 * (enable, route, extensions, install, ant server), the server is started, and the wizard must see the agent connect,
 * the watcher watch, and a real reload. Each step's panel is painted to a PNG. Everything is restored afterwards.
 *
 * Opt-in: RECLAZZ_WIZARD_LIVE=1, RECLAZZ_SAP_ROOT (default ~/Projects/reclazz-sap), RECLAZZ_WIZARD_SHOTS (PNG directory).
 */
class SetupWizardLiveSapTest : HeavyPlatformTestCase() {
    private val live = System.getenv("RECLAZZ_WIZARD_LIVE") == "1"
    private val root = Path.of(System.getenv("RECLAZZ_SAP_ROOT") ?: (System.getProperty("user.home") + "/Projects/reclazz-sap"))
    private val hybris = root.resolve("hybris")
    private val platform = hybris.resolve("bin/platform")
    private val local = hybris.resolve("config/local.properties")
    private val shots = Path.of(System.getenv("RECLAZZ_WIZARD_SHOTS") ?: "build/wizard-shots")
    private val probeClass = hybris.resolve("bin/custom/reclazztest/classes/com/onurkat/reclazztest/interceptors/TestValidateInterceptor.class")
    private val log = StringBuilder()

    private fun note(text: String) { log.append(text).append('\n'); println("[wizard-live] $text") }

    fun testWizardTakesANeverSetUpSapCommerceProjectToItsFirstReload() {
        if (!live) return
        Files.createDirectories(shots)
        val originalLocal = Files.readString(local)
        val originalClass = Files.readAllBytes(probeClass)
        val wasRunning = listening()
        Files.createSymbolicLink(Files.createDirectories(Path.of(project.basePath!!)).resolve("hybris"), hybris)
        // The test IDE has no installed plugin descriptor or bundled Java agent: use the jar the build staged for the sandbox IDE.
        val built = Path.of("build/idea-sandbox/plugins/reclazz/agent/reclazz-agent.jar")
        val agentCopy = Files.createDirectories(shots.resolve("agent")).resolve("reclazz-agent.jar")
        Files.copy(built, agentCopy, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        SetupFactsCollector.pluginVersionForTests = java.util.jar.JarFile(agentCopy.toFile()).use { it.manifest.mainAttributes.getValue("Implementation-Version") }
        SetupActionRunner.stageAgentForTests = { agentCopy.toFile() }
        val panel = SetupPanel(project)
        val reloads = java.util.concurrent.CopyOnWriteArrayList<String>()
        val listener: (com.onurkat.reclazz.plugin.reload.AgentEvent) -> Unit = { if (it.level == "RELOAD" || it.level == "STRUCTURAL_RELOAD") reloads += it.message }
        ReloadManager.getInstance(project).addEventListener(listener)
        try {
            stopServer()
            // A never-set-up installation: no Reclazz Java agent in local.properties.
            Files.writeString(local, originalLocal.lines().filterNot { it.startsWith("tomcat.javaoptions=") && it.contains("reclazz-agent") ||
                it.startsWith("tomcat.debugjavaoptions=") && it.contains("reclazz-agent") }.joinToString("\n") + "\n" +
                "tomcat.debugjavaoptions=-Djava.locale.providers=COMPAT,CLDR -Xdebug -Xnoagent -Xrunjdwp:transport=dt_socket,server=y,address=8000,suspend=n\n")
            val runner = SetupActionRunner(project)
            var shot = 0
            val seen = mutableListOf<String>()
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(25)
            while (System.nanoTime() < deadline) {
                val steps = SetupFlow.evaluate(SetupFactsCollector.collect(project, observed(panel)))
                val current = SetupFlow.current(steps)
                if (seen.lastOrNull() != current.step.id || SetupFlow.complete(steps)) paint(panel, String.format("%02d-%s.png", ++shot, current.step.id))
                if (SetupFlow.complete(steps)) { note("complete after: " + seen.joinToString(" -> ")); break }
                if (seen.lastOrNull() != current.step.id) { seen += current.step.id; note("step ${current.step.id}: ${current.status}") }
                when (val status = current.status) {
                    is StepStatus.NeedsAction -> {
                        val action = status.actions.first()
                        when {
                            current.step.id == "assistant" -> runner.run(SetupAction.SkipAssistant)
                            action is SetupAction.RunAntServer -> assertEquals("ant server", 0, ant())
                            runner.handles(action) -> runner.run(action)?.let { (text, ok) -> note("${action.label}: $text"); assertTrue(text, ok) }
                            else -> fail("step ${current.step.id} needs an action the wizard cannot run itself: ${status.problem} / ${action.label}")
                        }
                    }
                    else -> Thread.sleep(1000)
                }
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            }
            assertEquals(listOf("install", "ant", "assistant"), seen)
            assertTrue("setup complete", SetupFlow.complete(SetupFlow.evaluate(SetupFactsCollector.collect(project, observed(panel)))))
            // What the three steps promise: a restarted server has the Java agent, and a changed class reloads.
            startServer()
            val connectBy = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
            var connected = false
            while (!connected && System.nanoTime() < connectBy) {
                val result = java.util.concurrent.CompletableFuture<ReloadManager.ConnectResult>()
                ReloadManager.getInstance(project).connectToAgent { result.complete(it) }
                connected = try { result.get(30, TimeUnit.SECONDS) == ReloadManager.ConnectResult.CONNECTED } catch (_: Exception) { false }
                if (!connected) Thread.sleep(3000)
            }
            assertTrue("the restarted server answers", connected)
            Thread.sleep(5000)
            Files.write(probeClass, edited(originalClass)); note("probe class edited")
            val reloadBy = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
            while (reloads.none { it.contains("TestValidateInterceptor") } && System.nanoTime() < reloadBy) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); Thread.sleep(500) }
            assertTrue("reloaded after setup: $reloads", reloads.any { it.contains("TestValidateInterceptor") })
            note("reload: " + reloads.first { it.contains("TestValidateInterceptor") })
        } finally {
            ReloadManager.getInstance(project).removeEventListener(listener)
            Files.writeString(shots.resolve("log.txt"), log)
            SetupFactsCollector.pluginVersionForTests = null
            SetupActionRunner.stageAgentForTests = null
            try { Files.write(probeClass, originalClass) } catch (_: Exception) {}
            ApplicationManager.getApplication().invokeAndWait { panel.dispose() }
            stopServer()
            com.onurkat.reclazz.plugin.hybris.HybrisAgentInstaller.uninstall(project)
            Files.writeString(local, originalLocal)
            assertEquals("ant server (restore)", 0, ant())
            if (wasRunning) startServer()
        }
    }

    /** Paints every wizard state in the light and the dark theme without writing any file (design review). */
    fun testRenderEveryStateInBothThemes() {
        if (System.getenv("RECLAZZ_WIZARD_RENDER") != "1") return
        Files.createDirectories(shots)
        Files.createSymbolicLink(Files.createDirectories(Path.of(project.basePath!!)).resolve("hybris"), hybris)
        val panel = SetupPanel(project)
        val line = com.onurkat.reclazz.plugin.hybris.SetupCheck.Finding("agent-line", com.onurkat.reclazz.plugin.hybris.SetupCheck.Level.OK, "ok")
        val antOk = com.onurkat.reclazz.plugin.hybris.SetupCheck.Finding("ant-server", com.onurkat.reclazz.plugin.hybris.SetupCheck.Level.OK, "ok")
        val assistants = com.onurkat.reclazz.plugin.settings.McpClient.entries.map {
            com.onurkat.reclazz.plugin.setup.AssistantFact(it, it == com.onurkat.reclazz.plugin.settings.McpClient.CLAUDE || it == com.onurkat.reclazz.plugin.settings.McpClient.CURSOR, false) }
        val fresh = com.onurkat.reclazz.plugin.setup.SetupFacts(enabled = false, pluginVersion = "2.0.0", hybrisHome = hybris, antReady = true, assistants = assistants)
        val antLines = (1..14).joinToString("\n") { "     [echo] $it/14 building reclazztest ..." } + "\n     [java] Generating wrapper.conf"
        val states = listOf(
            "1-install" to { panel.showForTests(fresh.copy(installPreview = "Adds a marked block to ${hybris.resolve("config/local.properties")}:\n" +
                "tomcat.javaoptions=-javaagent:/Users/you/.reclazz/agent/reclazz-agent.jar=hybrisHome=${hybris},platform=hybris\n" +
                "tomcat.debugjavaoptions=-Xdebug -Xrunjdwp:transport=dt_socket,server=y,address=8000,suspend=n -javaagent:/Users/you/.reclazz/agent/reclazz-agent.jar=hybrisHome=${hybris},platform=hybris")) },
            "2-ant-running" to { panel.showForTests(fresh.copy(enabled = true, findings = listOf(line), antRunning = true), antLines) },
            "3-assistant" to { panel.showForTests(fresh.copy(enabled = true, findings = listOf(line, antOk))) },
            "4-complete" to { panel.showForTests(fresh.copy(enabled = true, findings = listOf(line, antOk),
                assistants = assistants.map { if (it.detected && it.client == com.onurkat.reclazz.plugin.settings.McpClient.CLAUDE) it.copy(connected = true) else it })) },
            "5-java-project" to { panel.showForTests(fresh.copy(hybrisHome = null)) },
        )
        // The test IDE is headless and installs no theme; load the platform's own New UI themes the way the IDE does.
        val themes = com.intellij.ide.ui.laf.UiThemeProviderListManager.getInstance()
        println("[wizard-render] themes: " + themes.getLaFs().toList().map { it.id })
        val original = javax.swing.UIManager.getLookAndFeel()
        try {
            for ((theme, id) in listOf("light" to "ExperimentalLight", "dark" to "ExperimentalDark")) {
                ApplicationManager.getApplication().invokeAndWait {
                    val info = themes.findThemeById(id) ?: error("theme $id not found")
                    javax.swing.UIManager.setLookAndFeel(if (info.isDark) com.intellij.ide.ui.laf.darcula.DarculaLaf() else com.intellij.ide.ui.laf.IntelliJLaf())
                    info.installTheme(javax.swing.UIManager.getLookAndFeelDefaults())
                    com.intellij.ui.JBColor.setDark(info.isDark)
                    println("[wizard-render] $theme: ${info.name} panel=${com.intellij.util.ui.UIUtil.getPanelBackground()} label=${com.intellij.util.ui.UIUtil.getLabelForeground()}")
                }
                for ((name, state) in states) {
                    ApplicationManager.getApplication().invokeAndWait { state(); javax.swing.SwingUtilities.updateComponentTreeUI(panel.component) }
                    paint(panel, "r-$theme-$name.png", refresh = false)
                }
            }
        } finally {
            ApplicationManager.getApplication().invokeAndWait { javax.swing.UIManager.setLookAndFeel(original); panel.dispose() }
        }
    }

    private fun observed(panel: SetupPanel): SetupFactsCollector.Observed {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return panel.observedForTests()
    }

    private fun paint(panel: SetupPanel, name: String, refresh: Boolean = true) {
        ApplicationManager.getApplication().invokeAndWait {
            if (refresh) panel.refreshNowForTests()
            val c = panel.component
            repeat(3) { c.setSize(900, 760); c.doLayout(); layoutAll(c) }
            val image = BufferedImage(900, 760, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics(); c.paint(g); g.dispose()
            ImageIO.write(image, "png", shots.resolve(name).toFile())
        }
    }

    private fun layoutAll(c: java.awt.Component) {
        if (c is java.awt.Container) { c.doLayout(); c.components.forEach(::layoutAll) }
    }

    private fun edited(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                object : MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    override fun visitLdcInsn(value: Any?) { super.visitLdcInsn(if (value == "none") "none (wizard probe)" else value) }
                }
        }, 0)
        return writer.toByteArray()
    }

    private fun listening() = ProcessBuilder("lsof", "-nP", "-iTCP:9102", "-sTCP:LISTEN").start().waitFor() == 0

    private fun stopServer() {
        ProcessBuilder("./hybrisserver.sh", "stop").directory(platform.toFile()).redirectErrorStream(true).start().waitFor(5, TimeUnit.MINUTES)
        repeat(60) { if (!listening()) return; Thread.sleep(3000) }
    }

    private fun startServer() {
        note("starting the server")
        ProcessBuilder("./hybrisserver.sh", "start").directory(platform.toFile()).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(2, TimeUnit.MINUTES)
    }

    private fun ant(): Int {
        val done = java.util.concurrent.CompletableFuture<Int>()
        val output = StringBuilder()
        AntServerRunner(hybris).start(onLine = { output.append(it).append('\n') }, onExit = { done.complete(it) })
        val code = done.get(20, TimeUnit.MINUTES)
        Files.writeString(shots.resolve("ant-${System.currentTimeMillis()}.log"), output)
        note("ant server exit $code")
        return code
    }
}
