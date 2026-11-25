/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.onurkat.reclazz.plugin.hybris.HybrisAgentInstaller
import com.onurkat.reclazz.plugin.settings.ReclazzConfigurable
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import com.onurkat.reclazz.plugin.support.SyntheticProject
import java.io.File
import java.awt.Component
import java.awt.Container
import javax.swing.*
import kotlin.test.assertEquals as expectEquals
import kotlin.test.assertTrue as expectTrue
import kotlin.test.assertFalse as expectFalse

class ReclazzConfigurableApplyTest : BasePlatformTestCase() {
    fun testFixedDebounceCanBeSetTo500AndClearedInTheForm() {
        val settings = ReclazzSettings.getInstance(project)
        settings.loadState(ReclazzSettings.State())
        val configurable = ReclazzConfigurable(project)
        try {
            val controls = descendants(configurable.createComponent())
            val fixed = controls.filterIsInstance<JCheckBox>().single { it.text == "Override adaptive delay" }
            expectFalse(fixed.isSelected)
            fixed.isSelected = true
            configurable.apply()
            expectEquals(500L, settings.state.fixedDebounceMs)
            fixed.isSelected = false
            configurable.reset()
            expectTrue(fixed.isSelected)
            fixed.isSelected = false
            configurable.apply()
            expectEquals(null, settings.state.fixedDebounceMs)
            expectFalse(configurable.isModified)
        } finally { configurable.disposeUIResources() }
    }

    fun testApplyPreservesSupportLineDismissedAndCommitsActualFormBindings() {
        val settings = ReclazzSettings.getInstance(project)
        settings.loadState(ReclazzSettings.State(supportLineDismissed = true))
        val configurable = ReclazzConfigurable(project)
        try {
            val form = configurable.createComponent()
            val controls = descendants(form)
            controls.filterIsInstance<JCheckBox>().single { it.text.startsWith("Reload added and removed members") }.isSelected = false
            controls.filterIsInstance<JCheckBox>().single { it.text.startsWith("Re-run changed static") }.isSelected = false
            controls.filterIsInstance<JComboBox<*>>().single().selectedItem = "request"
            val exclusion = controls.filterIsInstance<JLabel>().single { it.text?.startsWith("Exclude classes") == true }.labelFor as JTextField
            exclusion.text = "com.example.Legacy*"
            expectTrue(configurable.isModified)
            configurable.apply()
            expectTrue(settings.state.supportLineDismissed)
            expectFalse(settings.state.structuralReload)
            // On by default outside SAP Commerce; unticking stores the explicit choice.
            expectEquals(false, settings.state.resetStatics)
            expectEquals("request", settings.state.reloadBoundary)
            expectEquals("com.example.Legacy*", settings.state.excludeClasses)
            expectFalse(configurable.isModified)
            exclusion.text = "com.example.Unsaved"
            configurable.reset()
            expectEquals("com.example.Legacy*", exclusion.text)
            expectFalse(configurable.isModified)
        } finally { configurable.disposeUIResources() }
    }

    fun testInstallAppliesCurrentSettingsFirstAndRemoveCheckboxFollowsAutoImport() = assertInstallUsesCurrentForm(false)

    fun testReinstallAppliesCurrentSettingsFirst() = assertInstallUsesCurrentForm(true)

    private fun assertInstallUsesCurrentForm(reinstall: Boolean) {
        SyntheticProject(project, sap = true).use { layout ->
            val settings = ReclazzSettings.getInstance(project)
            settings.loadState(ReclazzSettings.State(supportLineDismissed = true))
            if (reinstall) {
                val result = HybrisAgentInstaller.install(layout.project) { File("/synthetic/reclazz-agent.jar") }
                expectTrue(result is HybrisAgentInstaller.Result.Success, result.toString())
            }
            var installed: ReclazzSettings.State? = null
            val configurable = ReclazzConfigurable(layout.project) {
                installed = ReclazzSettings.getInstance(it).state.copy()
                HybrisAgentInstaller.Result.Success("Synthetic install observed")
            }
            try {
                val controls = descendants(configurable.createComponent())
                val autoImpex = controls.filterIsInstance<JCheckBox>().single { it.text.startsWith("Import ImpEx files") }
                val remove = controls.filterIsInstance<JCheckBox>().single { it.text.startsWith("Allow REMOVE") }
                expectFalse(remove.isEnabled)
                autoImpex.isSelected = true
                expectTrue(remove.isEnabled)
                remove.isSelected = true
                controls.filterIsInstance<JCheckBox>().single { it.text.startsWith("Update Solr documents") }.isSelected = true
                controls.filterIsInstance<JCheckBox>().single { it.text.startsWith("Update the running system") }.isSelected = true
                controls.filterIsInstance<JButton>().single { it.text == (if (reinstall) "Reinstall" else "Install Java Agent") }.doClick()
                val captured = checkNotNull(installed)
                expectTrue(captured.autoSolrReindex)
                expectTrue(captured.autoUpdateRunningSystem)
                expectTrue(captured.autoImpex)
                expectTrue(captured.impexAllowRemove)
                expectTrue(captured.supportLineDismissed)
                expectFalse(configurable.isModified)
                autoImpex.isSelected = false
                expectFalse(remove.isEnabled)
                configurable.reset()
                expectTrue(autoImpex.isSelected)
                expectTrue(remove.isEnabled)
            } finally { configurable.disposeUIResources() }
        }
    }

    fun testLiveAgentAckAndErrorAreShownWithoutChangingSavedForm() {
        val configurable = ReclazzConfigurable(project)
        try {
            val form = configurable.createComponent()
            val manager = com.onurkat.reclazz.plugin.reload.ReloadManager.getInstance(project)
            fun show(message: String) {
                val action = { manager.postLocalMessage("LIVE_SETTINGS", message) }
                if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait { action() }
                expectTrue(descendants(form).filterIsInstance<JLabel>().any { it.text.orEmpty().contains(message) })
            }
            show("Running Java agent: ImpEx import: on")
            show("Live settings: failed compiler unavailable")
            expectFalse(configurable.isModified)
        } finally { configurable.disposeUIResources() }
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        (component as? Container)?.components.orEmpty().flatMap { descendants(it) }
}
