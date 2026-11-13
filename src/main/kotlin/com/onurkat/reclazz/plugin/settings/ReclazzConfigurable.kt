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
            "Agent: connected, $reloadCount reload${if (reloadCount == 1) "" else "s"} this session"
        } else {
            "Agent: not connected"
        }
        val agentInstalled = if (isHybris) HybrisAgentInstaller.isInstalled(project) else false

        // Layout follows the IntelliJ Platform UI guidelines: the switch that
        // governs everything first, one short label per option, the detail
        // behind each option's help icon, sentence-case labels and title-case
        // buttons, and rarely changed options folded into Advanced.
        return panel {
            lateinit var enabledCell: Cell<com.intellij.ui.components.JBCheckBox>
            row {
                enabledCell = checkBox("Enable Reclazz in this project")
                    .bindSelected(::enabledCheckbox)
                contextHelp("Adds the agent to Java run configurations and reloads classes when they compile. " +
                            "Turn it off to only watch an agent attached some other way.")
            }
            row {
                label(statusLine)
                link("Test connection") {
                    applyConnectionSettings()
                    testConnection()
                }
            }

            if (isHybris) {
                group("SAP Commerce") {
                    row {
                        if (agentInstalled) {
                            label("Agent installed in the server startup properties")
                            button("Reinstall") {
                                apply()
                                installAgentIntoWrapperConf()
                            }
                            link("Remove") { uninstallAgentFromWrapperConf() }
                        } else {
                            label("Agent not installed in the server")
                            button("Install Agent") {
                                apply()
                                installAgentIntoWrapperConf()
                            }
                            contextHelp("Writes the agent to the platform properties the server starts from, so it " +
                                    "survives ant clean all. Safe to run again; it takes effect at the next server start.")
                        }
                    }
                    row {
                        checkBox("Compile Java in the IDE instead of ant")
                            .bindSelected(::autoCompileCheckbox)
                        contextHelp("Faster reloads: changed sources are compiled in-process.")
                    }
                    lateinit var autoImpexCheckboxCell: Cell<com.intellij.ui.components.JBCheckBox>
                    row {
                        autoImpexCheckboxCell = checkBox("Import ImpEx files on save")
                            .bindSelected(::autoImpexCheckbox)
                    }
                    indent {
                        row {
                            checkBox("Allow REMOVE statements")
                                .bindSelected(::impexAllowRemoveCheckbox)
                            contextHelp("Lets a saved ImpEx file delete rows from the database. Development only.")
                        }.enabledIf(autoImpexCheckboxCell.selected)
                    }
                    row {
                        checkBox("Reset Backoffice configuration on save")
                            .bindSelected(::backofficeConfigResetCheckbox)
                        contextHelp("Same as F4 in Backoffice, so configuration changed in the Backoffice UI " +
                                    "is reset too. Turn it off to reset by hand.")
                    }
                    row("Watch extensions:") {
                        textField()
                            .bindText(::watchExtensionsField)
                            .columns(COLUMNS_MEDIUM)
                            .also { watchExtensionsTextField = it.component }
                            .comment("Empty watches every custom extension.")
                        link("Choose...") { pickExtensions() }
                    }
                    row {
                        label("Changes to the running database, for development systems only:")
                    }.topGap(TopGap.SMALL)
                    indent {
                        row {
                            checkBox("Update the running system after items.xml changes")
                                .bindSelected(::autoUpdateRunningSystemCheckbox)
                            contextHelp("Adds new columns and type localizations to the live database. " +
                                        "Never drops columns.")
                        }
                        row {
                            checkBox("Update Solr documents after a provider reloads")
                                .bindSelected(::autoSolrReindexCheckbox)
                            contextHelp("Re-indexes the documents that use the reloaded provider through " +
                                        "SAP's own indexer. Field freshness is not verified.")
                        }
                    }
                }
            }

            group("Reloading") {
                row("Apply reloads:") {
                    comboBox(listOf("immediate", "request"),
                        SimpleListCellRenderer.create("") { if (it == "request") "At the next request boundary" else "Immediately" })
                        .bindItem({ reloadBoundaryField }, { reloadBoundaryField = it ?: "immediate" })
                    contextHelp("At the next request boundary waits for running MVC requests to finish. It needs " +
                                "the agent at JVM startup; an attached agent always applies immediately.")
                }
                row {
                    checkBox("Configure the JVM for hot-swap")
                        .bindSelected(::autoDetectJdkCheckbox)
                    contextHelp("Recommended. Adds the JVM options class redefinition needs to run configurations.")
                }.enabledIf(enabledCell.selected)
                row {
                    checkBox("Refresh JPA mappings when an entity changes")
                        .bindSelected(::jpaRefreshCheckbox)
                    contextHelp("Rebuilds the persistence unit and closes open persistence contexts. Changed " +
                                "fields of existing entities need enhanced redefinition; new entities work on any JDK " +
                                "with a prepared schema.")
                }
            }

            collapsibleGroup("Advanced") {
                row {
                    checkBox("Reload added and removed members")
                        .bindSelected(::structuralReloadCheckbox)
                    contextHelp("On by default. Off limits reloads to method bodies.")
                }
                row {
                    checkBox("Re-run changed static initializers")
                        .bindSelected(::resetStaticsCheckbox)
                    contextHelp("Applies at the next launch. Eligible static finals become non-final; " +
                                "copies taken earlier keep their old value.")
                }
                row {
                    checkBox("Verbose logging")
                        .bindSelected(::verboseCheckbox)
                    contextHelp("Useful when reporting a bug.")
                }
                row("Exclude classes:") {
                    textField()
                        .bindText(::excludeClassesField)
                        .columns(COLUMNS_MEDIUM)
                    contextHelp("Class-name globs the agent never instruments, separated by semicolons, " +
                                "for example com.example.Legacy*.")
                }
                row("Ignore files:") {
                    textField()
                        .bindText(::excludePatternsField)
                        .columns(COLUMNS_MEDIUM)
                    contextHelp("Changed files matching these globs are not reloaded, for example *Test.class;*Mock*.")
                }
                row("Debounce:") {
                    spinner(100..5000, 50)
                        .bindIntValue(
                            getter = { debounceMsField.toInt() },
                            setter = { debounceMsField = it.toLong() }
                        )
                    label("ms")
                    contextHelp("How long to wait for more file changes before reloading.")
                }
                row("Startup delay:") {
                    spinner(0..300, 5)
                        .bindIntValue(
                            getter = { startupDelayField },
                            setter = { startupDelayField = it }
                        )
                    label("s")
                    contextHelp("Wait before watching files, so a starting server does not run out of file handles.")
                }
                row("Agent port:") {
                    spinner(0..65535, 1)
                        .bindIntValue(
                            getter = { agentPortField },
                            setter = { agentPortField = it }
                        )
                    contextHelp("0 reads the port from the port file. Set it when the agent port is fixed.")
                }
                row("Port file:") {
                    textField()
                        .bindText(::portFilePathField)
                        .columns(COLUMNS_MEDIUM)
                        .comment("Default: .idea/reclazz/agent.port")
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

            separator().topGap(TopGap.MEDIUM)
            row {
                comment("Reclazz $version")
                browserLink("Documentation", "https://reclazz.com/docs/")
                browserLink("What's new", "https://github.com/onurkat/reclazz/blob/main/CHANGELOG.md")
                browserLink("Report an issue", "https://github.com/onurkat/reclazz/issues")
            }
            // The only place Reclazz mentions sponsorship: one line at the
            // end, opt-out, never shown again once dismissed. No popups, no
            // notifications, no startup banner, a dev tool that nags is a dev
            // tool people uninstall.
            if (!settings.supportLineDismissed) {
                row {
                    comment("Reclazz is free and open source. " +
                            "<a href='https://github.com/sponsors/onurkat'>Sponsor its maintenance</a> " +
                            "or <a href='dismiss'>hide this</a>.") { event ->
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
