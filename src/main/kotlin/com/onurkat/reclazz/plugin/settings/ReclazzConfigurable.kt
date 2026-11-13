/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.settings

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.*
import com.onurkat.reclazz.plugin.agent.AgentJarLocator
import com.onurkat.reclazz.plugin.agent.AttachAction
import com.onurkat.reclazz.plugin.hybris.ExtensionResolver
import com.onurkat.reclazz.plugin.hybris.HybrisAgentInstaller
import com.onurkat.reclazz.plugin.hybris.HybrisProjectDetector
import com.onurkat.reclazz.plugin.notifications.ReloadNotifications
import com.onurkat.reclazz.plugin.reload.ReloadManager
import com.onurkat.reclazz.plugin.ui.ReclazzSurfaces
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.ListSelectionModel

class ReclazzConfigurable internal constructor(
    private val project: Project,
    private val installAgent: (Project) -> HybrisAgentInstaller.Result
) : Configurable {
    constructor(project: Project) : this(project, HybrisAgentInstaller::install)

    private var dialogPanel: DialogPanel? = null

    private var enabledCheckbox = true
    private var autoCompileCheckbox = false
    private var autoImpexCheckbox = false
    private var autoSolrReindexCheckbox = false
    private var backofficeConfigResetCheckbox = true
    private var autoUpdateRunningSystemCheckbox = false
    private var impexAllowRemoveCheckbox = false
    private var reloadBoundaryField = "immediate"
    private var structuralReloadCheckbox = true
    private var resetStaticsCheckbox = false
    private var excludeClassesField = ""
    private var watchExtensionsField = ""
    private var excludePatternsField = ""
    private var debounceMsField = 500L
    private var startupDelayField = 30
    private var verboseCheckbox = false
    private var jpaRefreshCheckbox = false
    private var autoDetectJdkCheckbox = true
    private var portFilePathField = ""
    private var agentPortField = 0

    private var watchExtensionsTextField: JTextField? = null
    private var supportRow: com.intellij.ui.dsl.builder.Row? = null

    override fun getDisplayName(): String = "Reclazz"

    override fun createComponent(): JComponent {
        val settings = ReclazzSettings.getInstance(project).state
        val isHybris = HybrisProjectDetector.isHybrisProject(project)

        enabledCheckbox = settings.enabled
        autoCompileCheckbox = settings.autoCompile
        autoImpexCheckbox = settings.autoImpex
        autoSolrReindexCheckbox = settings.autoSolrReindex
        backofficeConfigResetCheckbox = settings.backofficeConfigReset
        autoUpdateRunningSystemCheckbox = settings.autoUpdateRunningSystem
        impexAllowRemoveCheckbox = settings.impexAllowRemove
        reloadBoundaryField = settings.reloadBoundary
        structuralReloadCheckbox = settings.structuralReload
        resetStaticsCheckbox = settings.resetStatics
        excludeClassesField = settings.excludeClasses
        watchExtensionsField = settings.watchExtensions
        excludePatternsField = settings.excludePatterns
        debounceMsField = settings.debounceMs
        startupDelayField = settings.startupDelaySeconds
        verboseCheckbox = settings.verbose
        jpaRefreshCheckbox = settings.jpaRefresh
        autoDetectJdkCheckbox = settings.autoDetectJdk
        portFilePathField = settings.portFilePath
        agentPortField = settings.agentPort

        val manager = ReloadManager.getInstance(project)
        // Public entry point: PluginManagerCore.getPlugin is internal API and
        // the Marketplace rejects plugins that call it.
        val version = PluginManager.getInstance()
            .findEnabledPlugin(AgentJarLocator.RECLAZZ_PLUGIN_ID)
            ?.version ?: "?"
        val connected = manager.isConnected
        val reloadCount = manager.reloadCount
        val statusLine = if (connected) {
            "Status: Connected, $reloadCount reload${if (reloadCount == 1) "" else "s"} this session"
        } else {
            "Status: Not connected"
        }
        val agentInstalled = if (isHybris) HybrisAgentInstaller.isInstalled(project) else false

        return panel {
            // Status header, always visible, orients the user immediately.
            row {
                label("Reclazz $version").applyToComponent {
                    font = font.deriveFont(font.size + 2f).deriveFont(java.awt.Font.BOLD)
                }
            }
            row {
                label(statusLine)
                link("Test connection") {
                    applyConnectionSettings()
                    testConnection()
                }
            }
            row {
                browserLink("Documentation", "https://reclazz.com/docs/")
                browserLink("Report issue", "https://github.com/onurkat/reclazz/issues")
                browserLink("What's new", "https://github.com/onurkat/reclazz/blob/main/CHANGELOG.md")
            }
            separator()

            // The master switch comes first: every option below either feeds
            // the agent it injects or only matters while it is on.
            lateinit var enabledCell: Cell<com.intellij.ui.components.JBCheckBox>
            row {
                enabledCell = checkBox("Enable Reclazz in this project")
                    .bindSelected(::enabledCheckbox)
                    .comment("Adds the agent to Java run configurations, reloads on compile and sets the JVM " +
                            "flags hot-swap needs. Turn it off to only monitor an agent attached elsewhere.")
            }

            // Hybris hero, only shown when a SAP Commerce project is detected.
            // Promotes the single most important action: "Install Agent".
            if (isHybris) {
                group("SAP Commerce") {
                    if (agentInstalled) {
                        row {
                            label("Agent configured for SAP Commerce startup").applyToComponent {
                                font = font.deriveFont(java.awt.Font.BOLD)
                            }
                        }
                        row {
                            comment("Written to the platform properties your server starts from, so it\n" +
                                    "survives ant clean all. Loaded the next time the server starts.")
                        }
                        row {
                            button("Reinstall") {
                                apply()
                                installAgentIntoWrapperConf()
                            }
                            link("Remove agent") {
                                uninstallAgentFromWrapperConf()
                            }
                        }
                    } else {
                        row {
                            comment("One-time setup: adds the Reclazz agent to your SAP Commerce " +
                                    "server so class changes reload while it runs. Safe to run again.")
                        }
                        row {
                            button("Install Reclazz Agent") {
                                apply()
                                installAgentIntoWrapperConf()
                            }
                        }
                    }

                    row {
                        checkBox("Compile Java in-process (skips ant, faster reloads)")
                            .bindSelected(::autoCompileCheckbox)
                    }
                    lateinit var autoImpexCheckboxCell: Cell<com.intellij.ui.components.JBCheckBox>
                    row {
                        autoImpexCheckboxCell = checkBox("Auto-import ImpEx files on save")
                            .bindSelected(::autoImpexCheckbox)
                    }
                    row {
                        checkBox("Allow REMOVE in auto-imported ImpEx (development only)")
                            .bindSelected(::impexAllowRemoveCheckbox)
                            .comment("Allows deletion of live database rows on save. Off by default.")
                    }.enabledIf(autoImpexCheckboxCell.selected)
                    row {
                        checkBox("Update running system after model changes (development only)")
                            .bindSelected(::autoUpdateRunningSystemCheckbox)
                            .comment("Opt-in schema additions and keyed type localization writes to the live database; never drops columns.")
                    }
                    row {
                        checkBox("Update stored Solr documents after provider reload (development only)")
                            .bindSelected(::autoSolrReindexCheckbox)
                            .comment("Bounded native updates with normal listeners and bookkeeping. Stored field freshness is not verified.")
                    }
                    row {
                        checkBox("Reset Backoffice configuration when its XML is saved")
                            .bindSelected(::backofficeConfigResetCheckbox)
                            .comment("Same as F4 in Backoffice, so it also discards configuration changes made in the UI. " +
                                    "Turn it off to reset by hand.")
                    }
                    row("Watch extensions:") {
                        textField()
                            .bindText(::watchExtensionsField)
                            .comment("Semicolon-separated. Leave empty to watch all custom extensions.")
                            .columns(COLUMNS_LARGE)
                            .also { watchExtensionsTextField = it.component }
                    }
                    row("") {
                        button("Pick from localextensions.xml") { pickExtensions() }
                        button("Clear") {
                            watchExtensionsField = ""
                            watchExtensionsTextField?.text = ""
                        }
                    }
                }
            }

            group("General") {
                row {
                    checkBox("Configure the JVM for hot-swap")
                        .bindSelected(::autoDetectJdkCheckbox)
                        .comment("Recommended. Adds the JVM options class redefinition needs to injected run configurations.")
                }.enabledIf(enabledCell.selected)
                row("Apply reloads:") {
                    comboBox(listOf("immediate", "request"),
                        SimpleListCellRenderer.create("") { if (it == "request") "At the next request boundary" else "Immediately" })
                        .bindItem({ reloadBoundaryField }, { reloadBoundaryField = it ?: "immediate" })
                        .comment("Request boundary waits for supported MVC requests and needs -javaagent at JVM startup; " +
                                "dynamic attach always applies immediately. Takes effect at the next launch or install.")
                }
                row {
                    checkBox("Refresh JPA mapping when an entity field changes")
                        .bindSelected(::jpaRefreshCheckbox)
                        .comment("Rebuilds the persistence unit and closes old persistence contexts. Existing field changes " +
                                "require enhanced redefinition. New entities also work on stock JDKs, including " +
                                "supported Hibernate validate with a prepared schema.")
                }
                row {
                    checkBox("Verbose logging")
                        .bindSelected(::verboseCheckbox)
                        .comment("Useful when reporting a bug.")
                }
            }

            collapsibleGroup("Advanced") {
                row {
                    checkBox("Enable structural reload (added and removed members)")
                        .bindSelected(::structuralReloadCheckbox)
                        .comment("On by default. Turning it off leaves method-body reloads only.")
                }
                row {
                    checkBox("Re-run changed static field initializers")
                        .bindSelected(::resetStaticsCheckbox)
                        .comment("Off by default. Applies at next launch; eligible static finals become non-final. Captured copies keep their old value.")
                }
                row("Exclude classes from transformation:") {
                    textField()
                        .bindText(::excludeClassesField)
                        .comment("Semicolon-separated class-name globs (e.g. com.example.Legacy*). The agent never instruments these classes.")
                        .columns(COLUMNS_LARGE)
                }
                row("Ignore changed files matching:") {
                    textField()
                        .bindText(::excludePatternsField)
                        .comment("Semicolon-separated globs (e.g. *Test.class;*Mock*). Matching files are not reloaded.")
                        .columns(COLUMNS_LARGE)
                }
                row("Debounce (ms):") {
                    spinner(100..5000, 50)
                        .bindIntValue(
                            getter = { debounceMsField.toInt() },
                            setter = { debounceMsField = it.toLong() }
                        )
                        .comment("Delay before reacting to file changes")
                }
                row("Startup delay (seconds):") {
                    spinner(0..300, 5)
                        .bindIntValue(
                            getter = { startupDelayField },
                            setter = { startupDelayField = it }
                        )
                        .comment("Wait before watching files (avoids fd exhaustion during server startup)")
                }
                row("Port file path:") {
                    textField()
                        .bindText(::portFilePathField)
                        .comment("Default: .idea/reclazz/agent.port")
                        .columns(COLUMNS_LARGE)
                }
                row("Agent port:") {
                    spinner(0..65535, 1)
                        .bindIntValue(
                            getter = { agentPortField },
                            setter = { agentPortField = it }
                        )
                        .comment("0 = read from port file. Use when agent port is fixed.")
                }
                row {
                    button("Attach to Running Server...") {
                        apply()
                        // Calls the shared flow directly. Invoking the
                        // action's actionPerformed from here would violate
                        // the platform's @ApiStatus.OverrideOnly contract.
                        AttachAction.attachInteractively(project)
                    }
                }
            }.apply { expanded = false }

            // The only place Reclazz mentions sponsorship: one line at the
            // end, opt-out, never shown again once dismissed. No popups, no
            // notifications, no startup banner, a dev tool that nags is a dev
            // tool people uninstall.
            if (!settings.supportLineDismissed) {
                row {
                    comment("Reclazz is free and open source. " +
                            "<a href='https://github.com/sponsors/onurkat'>Sponsor</a> " +
                            "its maintenance, or <a href='dismiss'>hide this</a>.") { event ->
                        if (event.description == "dismiss") {
                            settings.supportLineDismissed = true
                            supportRow?.visible(false)
                        } else {
                            com.intellij.ide.BrowserUtil.browse(event.url.toString())
                        }
                    }
                }.also { supportRow = it }
            }
        }.also { dialogPanel = it }
    }

    private fun installAgentIntoWrapperConf() {
        when (val result = installAgent(project)) {
            is HybrisAgentInstaller.Result.Success ->
                ReloadNotifications.info(project, "Reclazz", result.message)
            is HybrisAgentInstaller.Result.Error ->
                ReloadNotifications.warn(project, "Reclazz", result.message)
        }
    }

    private fun uninstallAgentFromWrapperConf() {
        when (val result = HybrisAgentInstaller.uninstall(project)) {
            is HybrisAgentInstaller.Result.Success ->
                ReloadNotifications.info(project, "Reclazz", result.message)
            is HybrisAgentInstaller.Result.Error ->
                ReloadNotifications.warn(project, "Reclazz", result.message)
        }
    }

    private fun applyConnectionSettings() {
        readForm()
        val settings = ReclazzSettings.getInstance(project)
        val current = settings.state.copy()
        current.portFilePath = portFilePathField
        current.agentPort = agentPortField
        settings.loadState(current)
    }

    private fun pickExtensions() {
        val extensions = ExtensionResolver.resolveExtensions(project)
        if (extensions.isEmpty()) {
            ReloadNotifications.warn(project, "Reclazz", "No extensions found in localextensions.xml")
            return
        }

        // Pick *replaces* the current selection, prior behavior (merge)
        // trapped users with stale picks they had to manually clear.
        val dialog = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(extensions)
            .setTitle("Select Extensions to Watch")
            .setMovable(true)
            .setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
            .setItemsChosenCallback { selected ->
                watchExtensionsField = selected.joinToString(";")
                watchExtensionsTextField?.text = watchExtensionsField
            }
            .createPopup()
        dialog.showInFocusCenter()
    }

    private fun testConnection() {
        val manager = ReloadManager.getInstance(project)
        if (manager.isConnected) {
            ReloadNotifications.info(project, "Reclazz",
                "Agent is connected (" + com.onurkat.reclazz.plugin.ui.plural(manager.reloadCount, "reload") + ")")
        } else {
            ReloadNotifications.info(project, "Reclazz", "Connecting to agent...")
            manager.connectToAgent()
            ApplicationManager.getApplication().executeOnPooledThread {
                // Wait up to 5 seconds, checking every 500ms
                var connected = false
                for (i in 0 until 10) {
                    try {
                        Thread.sleep(500)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@executeOnPooledThread
                    }
                    if (manager.isConnected) {
                        connected = true
                        break
                    }
                }
                ApplicationManager.getApplication().invokeLater {
                    if (connected) {
                        ReloadNotifications.info(project, "Reclazz", "Successfully connected to agent")
                    } else {
                        val settings = ReclazzSettings.getInstance(project).state
                        val hint = buildString {
                            append("Agent not reachable. ")
                            if (settings.agentPort > 0) {
                                append("Port ${settings.agentPort} is not responding. ")
                            } else if (settings.portFilePath.isNotBlank()) {
                                append("Port file '${settings.portFilePath}' not found or port not open. ")
                            } else {
                                append("Port file '.idea/reclazz/agent.port' not found. ")
                            }
                            append("Make sure the server is running with the Reclazz agent.")
                        }
                        ReloadNotifications.warn(project, "Reclazz", hint)
                    }
                }
            }
        }
    }

    override fun isModified(): Boolean {
        val settings = ReclazzSettings.getInstance(project).state
        return dialogPanel?.isModified() == true || enabledCheckbox != settings.enabled ||
                autoCompileCheckbox != settings.autoCompile ||
                autoImpexCheckbox != settings.autoImpex ||
                autoSolrReindexCheckbox != settings.autoSolrReindex ||
                backofficeConfigResetCheckbox != settings.backofficeConfigReset ||
                autoUpdateRunningSystemCheckbox != settings.autoUpdateRunningSystem ||
                impexAllowRemoveCheckbox != settings.impexAllowRemove ||
                reloadBoundaryField != settings.reloadBoundary ||
                structuralReloadCheckbox != settings.structuralReload ||
                resetStaticsCheckbox != settings.resetStatics ||
                excludeClassesField != settings.excludeClasses ||
                watchExtensionsField != settings.watchExtensions ||
                excludePatternsField != settings.excludePatterns ||
                debounceMsField != settings.debounceMs ||
                startupDelayField != settings.startupDelaySeconds ||
                verboseCheckbox != settings.verbose ||
                jpaRefreshCheckbox != settings.jpaRefresh ||
                autoDetectJdkCheckbox != settings.autoDetectJdk ||
                portFilePathField != settings.portFilePath ||
                agentPortField != settings.agentPort
    }

    private fun readForm() {
        dialogPanel?.validateAll()?.firstOrNull()?.let { throw ConfigurationException(it.message) }
        dialogPanel?.apply()
        if (reloadBoundaryField !in listOf("immediate", "request")) {
            throw ConfigurationException("Reload boundary must be immediate or request.")
        }
    }

    override fun apply() {
        readForm()
        val settings = ReclazzSettings.getInstance(project)
        settings.loadState(
            settings.state.copy(
                enabled = enabledCheckbox,
                autoCompile = autoCompileCheckbox,
                autoImpex = autoImpexCheckbox,
                autoSolrReindex = autoSolrReindexCheckbox,
                backofficeConfigReset = backofficeConfigResetCheckbox,
                autoUpdateRunningSystem = autoUpdateRunningSystemCheckbox,
                impexAllowRemove = impexAllowRemoveCheckbox,
                reloadBoundary = reloadBoundaryField,
                structuralReload = structuralReloadCheckbox,
                resetStatics = resetStaticsCheckbox,
                excludeClasses = excludeClassesField,
                watchExtensions = watchExtensionsField,
                excludePatterns = excludePatternsField,
                debounceMs = debounceMsField,
                startupDelaySeconds = startupDelayField,
                verbose = verboseCheckbox,
                jpaRefresh = jpaRefreshCheckbox,
                autoDetectJdk = autoDetectJdkCheckbox,
                portFilePath = portFilePathField,
                agentPort = agentPortField
            )
        )
        // The tool window and the status bar item both exist only where
        // Reclazz is on, and the platform re-reads that only when asked.
        ReclazzSurfaces.refresh(project)
    }

    override fun reset() {
        val settings = ReclazzSettings.getInstance(project).state
        enabledCheckbox = settings.enabled
        autoCompileCheckbox = settings.autoCompile
        autoImpexCheckbox = settings.autoImpex
        autoSolrReindexCheckbox = settings.autoSolrReindex
        backofficeConfigResetCheckbox = settings.backofficeConfigReset
        autoUpdateRunningSystemCheckbox = settings.autoUpdateRunningSystem
        impexAllowRemoveCheckbox = settings.impexAllowRemove
        reloadBoundaryField = settings.reloadBoundary
        structuralReloadCheckbox = settings.structuralReload
        resetStaticsCheckbox = settings.resetStatics
        excludeClassesField = settings.excludeClasses
        watchExtensionsField = settings.watchExtensions
        excludePatternsField = settings.excludePatterns
        debounceMsField = settings.debounceMs
        startupDelayField = settings.startupDelaySeconds
        verboseCheckbox = settings.verbose
        jpaRefreshCheckbox = settings.jpaRefresh
        autoDetectJdkCheckbox = settings.autoDetectJdk
        portFilePathField = settings.portFilePath
        agentPortField = settings.agentPort
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
        watchExtensionsTextField = null
        supportRow = null
    }
}
