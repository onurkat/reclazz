/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.onurkat.reclazz.plugin.setup.SetupWizard
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.*
import com.onurkat.reclazz.plugin.PluginPresence
import com.onurkat.reclazz.plugin.agent.AgentJarLocator
import com.onurkat.reclazz.plugin.agent.AttachAction
import com.onurkat.reclazz.plugin.hybris.ExtensionResolver
import com.onurkat.reclazz.plugin.hybris.HybrisAgentInstaller
import com.onurkat.reclazz.plugin.hybris.HybrisProjectDetector
import com.onurkat.reclazz.plugin.notifications.ReloadNotifications
import com.onurkat.reclazz.plugin.reload.ReloadManager
import com.onurkat.reclazz.plugin.ui.ReclazzSurfaces
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JTextField
import javax.swing.ListSelectionModel

class ReclazzConfigurable internal constructor(
    private val project: Project,
    private val installAgent: (Project) -> HybrisAgentInstaller.Result
) : Configurable {
    constructor(project: Project) : this(project, HybrisAgentInstaller::install)

    internal var startSetupCheck: (Boolean, String?) -> Unit = { show, message ->
        SetupCheckUi.run(project, showResults=show, installedMessage=message)
    }

    private var dialogPanel: DialogPanel? = null

    private var injectIntoTestsCheckbox = false
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
    private var useFixedDebounce = false
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

    private var liveSettingsManager: ReloadManager? = null
    private var liveSettingsLabel: javax.swing.JLabel? = null
    private var statusLabel: javax.swing.JLabel? = null
    private val liveSettingsListener: (com.onurkat.reclazz.plugin.reload.AgentEvent) -> Unit = { event ->
        if (event.level in setOf("LIVE_SETTINGS", "CONNECTED", "DISCONNECTED", "RELOAD", "STRUCTURAL_RELOAD")) {
            val update = Runnable {
                if (event.level == "LIVE_SETTINGS") liveSettingsLabel?.text = liveSettingsMarkup(event.message)
                updateStatus()
            }
            if (javax.swing.SwingUtilities.isEventDispatchThread()) update.run() else javax.swing.SwingUtilities.invokeLater(update)
        }
    }

    private fun updateStatus() {
        val manager = liveSettingsManager ?: return
        val enabled = if (ReclazzSettings.getInstance(project).state.enabled) "on" else "off"
        val connection = if (manager.isConnected) "Agent connected" else "Agent disconnected"
        val last = manager.lastReload
        val summary = last?.let { "Last reload: " + it.timestamp.ifBlank { it.message } } ?: "No reloads this session"
        statusLabel?.text = "Reclazz is $enabled for this project  ·  $connection  ·  $summary"
    }

    private fun openSetup() {
        val form = dialogPanel ?: return
        val dialog = DialogWrapper.findInstance(form)
        val buttons = dialog?.let { com.intellij.util.ui.UIUtil.findComponentsOfType(it.rootPane, javax.swing.JButton::class.java) }.orEmpty()
        val otherEdits = buttons.any { it.isShowing && it.isEnabled && it.text == com.intellij.CommonBundle.getApplyButtonText() }
        if (isModified || otherEdits) {
            when (Messages.showDialog(project, "Apply changes before setup? This applies to all open Settings pages.",
                "Set Up Reclazz", arrayOf("Apply", "Discard", "Cancel"), 0, Messages.getQuestionIcon())) {
                0 -> if (dialog == null) apply() else {
                    val ok = buttons.firstOrNull { it.isShowing && it.isEnabled && it.text == com.intellij.CommonBundle.getOkButtonText() } ?: return
                    ok.doClick()
                }
                1 -> if (dialog == null) reset() else dialog.doCancelAction()
                else -> return
            }
        } else dialog?.doCancelAction()
        if (dialog == null || dialog.isDisposed) {
            ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) SetupWizard.open(project) },
                com.intellij.openapi.application.ModalityState.nonModal())
        }
    }

    private fun liveSettingsMarkup(text: String): String = "<html><body style='width: 480px'>" +
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</body></html>"

    override fun createComponent(): JComponent {
        liveSettingsManager?.removeEventListener(liveSettingsListener)
        liveSettingsManager = ReloadManager.getInstance(project).also { it.addEventListener(liveSettingsListener) }
        val settings = ReclazzSettings.getInstance(project).state
        val isHybris = HybrisProjectDetector.isHybrisProject(project)

        injectIntoTestsCheckbox = settings.injectIntoTests
        enabledCheckbox = settings.enabled
        autoCompileCheckbox = settings.autoCompile
        autoImpexCheckbox = settings.autoImpex
        autoSolrReindexCheckbox = settings.autoSolrReindex
        backofficeConfigResetCheckbox = settings.backofficeConfigReset
        autoUpdateRunningSystemCheckbox = settings.autoUpdateRunningSystem
        impexAllowRemoveCheckbox = settings.impexAllowRemove
        reloadBoundaryField = settings.reloadBoundary
        structuralReloadCheckbox = settings.structuralReload
        resetStaticsCheckbox = ReclazzSettings.effectiveResetStatics(settings, sapCommerceProject())
        excludeClassesField = settings.excludeClasses
        watchExtensionsField = settings.watchExtensions
        excludePatternsField = settings.excludePatterns
        useFixedDebounce = settings.fixedDebounceMs != null
        debounceMsField = settings.fixedDebounceMs ?: 500L
        startupDelayField = settings.startupDelaySeconds
        verboseCheckbox = settings.verbose
        jpaRefreshCheckbox = settings.jpaRefresh
        autoDetectJdkCheckbox = settings.autoDetectJdk
        portFilePathField = settings.portFilePath
        agentPortField = settings.agentPort

        val version = PluginPresence.enabledDescriptor(AgentJarLocator.RECLAZZ_PLUGIN_ID.idString)
            ?.version ?: "?"
        val agentInstalled = if (isHybris) HybrisAgentInstaller.isInstalled(project) else false

        return panel {
            lateinit var enabledCell: Cell<com.intellij.ui.components.JBCheckBox>
            row { label("").applyToComponent { statusLabel = this; updateStatus() } }
            row {
                button("Set Up Reclazz") { openSetup() }
                if (isHybris) button("Check setup") { startSetupCheck(true, null) }
                button("Connect AI assistant") { ConnectAiAssistantPanel.show(project) }
                link("Test connection") { applyConnectionSettings(); testConnection() }
            }
            row {
                label(liveSettingsMarkup(liveSettingsManager?.liveSettingsStatus.orEmpty())).applyToComponent { liveSettingsLabel = this }
            }
            group("General") {
                row {
                    enabledCell = checkBox("Enable Reclazz in this project").bindSelected(::enabledCheckbox)
                        .comment("Adds the Java agent when you run this project from the IDE. Off by default.")
                }
            }
            if (isHybris) {
                group("SAP Commerce") {
                    row {
                        if (agentInstalled) {
                            label("Java agent installed in server startup properties")
                            button("Reinstall") { apply(); installAgentIntoWrapperConf() }
                            link("Remove") { uninstallAgentFromWrapperConf() }
                        } else {
                            label("Java agent missing from server startup properties")
                            button("Install Java Agent") { apply(); installAgentIntoWrapperConf() }
                        }
                    }.rowComment("Writes the agent options to your SAP Commerce configuration. Run ant server and restart to use them.")
                    row {
                        checkBox("Reclazz compiles saved Java files instead of ant").bindSelected(::autoCompileCheckbox)
                            .comment("Off by default. Build with ant to reload compiled classes.")
                    }
                    row("Watched extensions:") {
                        textField().bindText(::watchExtensionsField).columns(COLUMNS_MEDIUM)
                            .also { watchExtensionsTextField = it.component }
                            .comment("Leave empty to watch all custom extensions (default).")
                        link("Choose...") { pickExtensions() }
                    }
                    group("Data on save (development systems only)") {
                        row { comment("These actions can change running system data. Use them on a development system.") }
                        lateinit var impex: Cell<com.intellij.ui.components.JBCheckBox>
                        row {
                            impex = checkBox("Import ImpEx files on save").bindSelected(::autoImpexCheckbox)
                                .comment("Applies saved ImpEx files to the running database. Off by default.")
                        }
                        indent {
                            row {
                                checkBox("Allow REMOVE statements").bindSelected(::impexAllowRemoveCheckbox)
                                    .comment("Allows an import to delete matching database rows. Off by default.")
                            }.enabledIf(impex.selected)
                        }
                        row {
                            checkBox("Update the running system after items.xml changes").bindSelected(::autoUpdateRunningSystemCheckbox)
                                .comment("Adds columns and type localizations to the database. Off by default.")
                        }
                        row {
                            checkBox("Update Solr documents after a provider reloads").bindSelected(::autoSolrReindexCheckbox)
                                .comment("Reindexes documents that use the changed provider. Off by default.")
                        }
                        row {
                            checkBox("Reset Backoffice configuration on save").bindSelected(::backofficeConfigResetCheckbox)
                                .comment("Resets Backoffice customizations just like pressing F4. On by default.")
                        }
                    }
                }
            }
            group("Spring and JPA") {
                row("Apply reloads:") {
                    comboBox(listOf("immediate", "request"), object : SimpleListCellRenderer<String>() {
                        override fun customize(list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                            text = if (value == "request") "Between web requests" else "Immediately"
                        }
                    }).bindItem({ reloadBoundaryField }, { reloadBoundaryField = it ?: "immediate" })
                        .comment("Immediate by default. Waiting for active MVC requests requires the agent at JVM startup.")
                }
                row {
                    checkBox("Refresh JPA mappings when an entity changes").bindSelected(::jpaRefreshCheckbox)
                        .comment("Off by default. Rebuilds eligible mappings and closes the previous persistence contexts. On a stock JVM, changes to persistent fields require a restart.")
                }
            }
            group("Tests") {
                row {
                    checkBox("Attach Reclazz to IDE test runs (JUnit, TestNG)").bindSelected(::injectIntoTestsCheckbox)
                        .enabledIf(enabledCell.selected).comment("Use reloads while running tests from the IDE. Off by default.")
                }
            }
            collapsibleGroup("Advanced (troubleshooting)") {
                row {
                    checkBox("Reload added or removed fields and methods").bindSelected(::structuralReloadCheckbox)
                        .comment("On by default. Turn off to reload method bodies only.")
                }
                row {
                    checkBox("Re-run changed static field initializers").bindSelected(::resetStaticsCheckbox)
                        .comment("Resets affected static fields. On by default for IDE launches, off for SAP Commerce.")
                }
                row {
                    checkBox("Set JVM options for hot-swap").bindSelected(::autoDetectJdkCheckbox)
                        .enabledIf(enabledCell.selected).comment("Adds class-reload options to IDE run configurations. On by default.")
                }
                row {
                    checkBox("Verbose logging").bindSelected(::verboseCheckbox)
                        .comment("Records additional diagnostic details. Off by default.")
                }
                row("Exclude classes:") {
                    textField().bindText(::excludeClassesField).columns(COLUMNS_MEDIUM)
                        .comment("Skip matching class names, e.g. com.example.Legacy*. Separate patterns with semicolons. Empty by default.")
                }
                row("Ignore files:") {
                    textField().bindText(::excludePatternsField).columns(COLUMNS_MEDIUM)
                        .comment("Skip changed files that match these patterns, e.g. *Test.class. Empty by default.")
                }
                row("Wait after edits:") {
                    val fixed = checkBox("Use a fixed delay").bindSelected(::useFixedDebounce)
                    spinner(0..60_000, 50).bindIntValue({ debounceMsField.toInt() }, { debounceMsField = it.toLong() })
                        .enabledIf(fixed.selected)
                    label("ms")
                }.rowComment("By default, waits 30 ms for Java compiled by Reclazz and 500 ms for class files or mixed changes.")
                row("Startup delay:") {
                    spinner(0..300, 5).bindIntValue({ startupDelayField }, { startupDelayField = it })
                    label("s")
                }.rowComment("Wait before watching files while the server starts. Default 30 s.")
                row("Java agent port:") {
                    spinner(0..65535, 1).bindIntValue({ agentPortField }, { agentPortField = it })
                        .comment("Set a fixed port or use 0 to read the port file (default).")
                }
                row("Port file:") {
                    textField().bindText(::portFilePathField).columns(COLUMNS_MEDIUM)
                        .comment("Leave empty to read .idea/reclazz/agent.port (default).")
                }
                row {
                    button("Attach to Running Server...") { apply(); AttachAction.attachInteractively(project) }
                        .comment("Connect the agent to a Java process that is already running.")
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
                startSetupCheck(false, result.message)
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
                "Java agent is connected (" + com.onurkat.reclazz.plugin.ui.plural(manager.reloadCount, "reload") + ")")
        } else {
            ReloadNotifications.info(project, "Reclazz", "Connecting to the Java agent...")
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
                        ReloadNotifications.info(project, "Reclazz", "Successfully connected to the Java agent")
                    } else {
                        val settings = ReclazzSettings.getInstance(project).state
                        val hint = buildString {
                            append("Java agent not reachable. ")
                            if (settings.agentPort > 0) {
                                append("Port ${settings.agentPort} is not responding. ")
                            } else if (settings.portFilePath.isNotBlank()) {
                                append("Port file '${settings.portFilePath}' not found or port not open. ")
                            } else {
                                append("Port file '.idea/reclazz/agent.port' not found. ")
                            }
                            append("Make sure the server is running with the Reclazz Java agent.")
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
                injectIntoTestsCheckbox != settings.injectIntoTests ||
                autoCompileCheckbox != settings.autoCompile ||
                autoImpexCheckbox != settings.autoImpex ||
                autoSolrReindexCheckbox != settings.autoSolrReindex ||
                backofficeConfigResetCheckbox != settings.backofficeConfigReset ||
                autoUpdateRunningSystemCheckbox != settings.autoUpdateRunningSystem ||
                impexAllowRemoveCheckbox != settings.impexAllowRemove ||
                reloadBoundaryField != settings.reloadBoundary ||
                structuralReloadCheckbox != settings.structuralReload ||
                resetStaticsCheckbox != ReclazzSettings.effectiveResetStatics(settings, sapCommerceProject()) ||
                excludeClassesField != settings.excludeClasses ||
                watchExtensionsField != settings.watchExtensions ||
                excludePatternsField != settings.excludePatterns ||
                selectedDebounce() != settings.fixedDebounceMs ||
                startupDelayField != settings.startupDelaySeconds ||
                verboseCheckbox != settings.verbose ||
                jpaRefreshCheckbox != settings.jpaRefresh ||
                autoDetectJdkCheckbox != settings.autoDetectJdk ||
                portFilePathField != settings.portFilePath ||
                agentPortField != settings.agentPort
    }

    private fun selectedDebounce(): Long? = if (useFixedDebounce) debounceMsField else null

    private fun readForm() {
        dialogPanel?.validateAll()?.firstOrNull()?.let { throw ConfigurationException(it.message) }
        dialogPanel?.apply()
        if (reloadBoundaryField !in listOf("immediate", "request")) {
            throw ConfigurationException("Reload boundary must be immediate or request.")
        }
    }

    private fun sapCommerceProject(): Boolean = HybrisProjectDetector.findHybrisHome(project) != null

    override fun apply() {
        readForm()
        val settings = ReclazzSettings.getInstance(project)
        settings.loadState(
            settings.state.copy(
                enabled = enabledCheckbox,
                injectIntoTests = injectIntoTestsCheckbox,
                autoCompile = autoCompileCheckbox,
                autoImpex = autoImpexCheckbox,
                autoSolrReindex = autoSolrReindexCheckbox,
                backofficeConfigReset = backofficeConfigResetCheckbox,
                autoUpdateRunningSystem = autoUpdateRunningSystemCheckbox,
                impexAllowRemove = impexAllowRemoveCheckbox,
                reloadBoundary = reloadBoundaryField,
                structuralReload = structuralReloadCheckbox,
                resetStatics = if (resetStaticsCheckbox == ReclazzSettings.effectiveResetStatics(settings.state, sapCommerceProject())) settings.state.resetStatics else resetStaticsCheckbox,
                excludeClasses = excludeClassesField,
                watchExtensions = watchExtensionsField,
                excludePatterns = excludePatternsField,
                fixedDebounceMs = selectedDebounce(),
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
        updateStatus()
        ReloadManager.getInstance(project).applyLiveSettings()
    }

    override fun reset() {
        val settings = ReclazzSettings.getInstance(project).state
        injectIntoTestsCheckbox = settings.injectIntoTests
        enabledCheckbox = settings.enabled
        autoCompileCheckbox = settings.autoCompile
        autoImpexCheckbox = settings.autoImpex
        autoSolrReindexCheckbox = settings.autoSolrReindex
        backofficeConfigResetCheckbox = settings.backofficeConfigReset
        autoUpdateRunningSystemCheckbox = settings.autoUpdateRunningSystem
        impexAllowRemoveCheckbox = settings.impexAllowRemove
        reloadBoundaryField = settings.reloadBoundary
        structuralReloadCheckbox = settings.structuralReload
        resetStaticsCheckbox = ReclazzSettings.effectiveResetStatics(settings, sapCommerceProject())
        excludeClassesField = settings.excludeClasses
        watchExtensionsField = settings.watchExtensions
        excludePatternsField = settings.excludePatterns
        useFixedDebounce = settings.fixedDebounceMs != null
        debounceMsField = settings.fixedDebounceMs ?: 500L
        startupDelayField = settings.startupDelaySeconds
        verboseCheckbox = settings.verbose
        jpaRefreshCheckbox = settings.jpaRefresh
        autoDetectJdkCheckbox = settings.autoDetectJdk
        portFilePathField = settings.portFilePath
        agentPortField = settings.agentPort
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        liveSettingsManager?.removeEventListener(liveSettingsListener)
        liveSettingsManager = null
        statusLabel = null
        liveSettingsLabel = null
        dialogPanel = null
        watchExtensionsTextField = null
        supportRow = null
    }
}
