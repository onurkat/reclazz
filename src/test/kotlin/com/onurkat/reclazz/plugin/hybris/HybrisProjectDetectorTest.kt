/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.hybris

import com.onurkat.reclazz.plugin.settings.ReclazzConfigurable
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path

class HybrisProjectDetectorTest : BasePlatformTestCase() {
    private val roots = mutableListOf<Path>()
    private fun directory(): Path = Files.createTempDirectory("reclazz-detector-").also { roots.add(it) }
    private fun at(base: Path): Project = Proxy.newProxyInstance(Project::class.java.classLoader,
        arrayOf(Project::class.java)) { _, method, args ->
        if (method.name == "getBasePath") base.toString() else method.invoke(project, *(args ?: emptyArray()))
    } as Project
    private fun install(home: Path): Path {
        Files.createDirectories(home.resolve("bin/platform"))
        Files.writeString(home.resolve("bin/platform/build.xml"), "<project/>")
        return home
    }
    private fun layout(relative: String) {
        val base = directory()
        val home = install(base.resolve(relative))
        val candidate = at(base)
        assertEquals(home, HybrisProjectDetector.findHybrisHome(candidate))
        assertTrue("Resolved installation must expose SAP Commerce settings: $relative",
            HybrisProjectDetector.isHybrisProject(candidate))
    }
    fun testDirectWithoutConfig() = layout("")
    fun testHybrisWithoutConfig() = layout("hybris")
    fun testCoreCustomizeHybrisWithoutConfig() = layout("core-customize/hybris")
    fun testCoreCustomizeWithoutConfig() = layout("core-customize")
    fun testCommerceSuiteWithoutConfig() = layout("commerce-suite")
    fun testCcv2WithoutConfig() = layout("ccv2/hybris")
    fun testNestedBasePath() {
        val home = install(directory())
        val base = Files.createDirectories(home.resolve("bin/custom/example"))
        assertEquals(home, HybrisProjectDetector.findHybrisHome(at(base)))
        assertTrue(HybrisProjectDetector.isHybrisProject(at(base)))
    }
    fun testContentRootOutsideBase() {
        val base = directory()
        val home = install(directory().resolve("hybris"))
        ModuleRootModificationUtil.addContentRoot(module, home.toString())
        val candidate = at(base)
        assertEquals(home, HybrisProjectDetector.findHybrisHome(candidate))
        assertTrue(HybrisProjectDetector.isHybrisProject(candidate))
    }
    fun testBasePathWinsOverModuleRoots() {
        val base = install(directory())
        val other = install(directory())
        ModuleRootModificationUtil.addContentRoot(module, other.toString())
        assertEquals(base, HybrisProjectDetector.findHybrisHome(at(base)))
    }
    fun testPlainProjectIsNotSap() {
        val candidate = at(directory())
        assertNull(HybrisProjectDetector.findHybrisHome(candidate))
        assertFalse(HybrisProjectDetector.isHybrisProject(candidate))
    }
    fun testEmptyLookalikePlatformIsNotAnInstallation() {
        val base = directory()
        Files.createDirectories(base.resolve("hybris/bin/platform"))
        assertNull(HybrisProjectDetector.findHybrisHome(at(base)))
        assertFalse(HybrisProjectDetector.isHybrisProject(at(base)))
    }
    fun testUpwardSearchRemainsBounded() {
        val home = install(directory())
        val base = Files.createDirectories(home.resolve("a/b/c/d/e/f"))
        assertNull(HybrisProjectDetector.findHybrisHome(at(base)))
        assertFalse(HybrisProjectDetector.isHybrisProject(at(base)))
    }
    private fun descendants(component: Component): List<Component> = listOf(component) +
        (component as? Container)?.components.orEmpty().flatMap { descendants(it) }
    private fun assertInstallVisible(candidate: Project) {
        val configurable = ReclazzConfigurable(candidate)
        try {
            assertTrue("SAP Commerce settings must contain Install Agent",
                descendants(configurable.createComponent()).filterIsInstance<JButton>().any { it.text == "Install Agent" })
        } finally { configurable.disposeUIResources() }
    }
    fun testSettingsExposeInstallWithoutConfig() {
        val base = directory()
        install(base.resolve("hybris"))
        assertInstallVisible(at(base))
    }
    fun testSettingsExposeInstallFromContentRoot() {
        val base = directory()
        val home = install(directory())
        ModuleRootModificationUtil.addContentRoot(module, home.toString())
        assertInstallVisible(at(base))
    }
    override fun tearDown() {
        try { super.tearDown() }
        finally { roots.forEach { root -> Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } } }
    }
}
