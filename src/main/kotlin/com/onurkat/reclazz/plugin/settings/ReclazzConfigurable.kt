/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.settings

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
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

class ReclazzConfigurable(private val project: Project) : Configurable {

    private var enabledCheckbox = true
    private var autoCompileCheckbox = false
    private var autoImpexCheckbox = false
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
            "Status: Connected — $reloadCount reload${if (reloadCount == 1) "" else "s"} this session"
        } else {
            "Status: Not connected"
        }
        val agentInstalled = if (isHybris) HybrisAgentInstaller.isInstalled(project) else false

        return panel {
            // Status header — always visible, orients the user immediately.
            row {
                label("Reclazz $version").applyToComponent {
                    font = font.deriveFont(font.size + 2f).deriveFont(java.awt.Font.BOLD)
                }
            }
            row {
                label(statusLine)
            }
            row {
                browserLink("Documentation", "https://github.com/onurkat/reclazz")
                browserLink("Report issue", "https://github.com/onurkat/reclazz/issues")
                browserLink("What's new", "https://github.com/onurkat/reclazz/blob/main/CHANGELOG.md")
            }
            separator()

            // Hybris hero — only shown when a SAP Commerce project is detected.
            // Promotes the single most important action: "Install Agent".
            if (isHybris) {
                group("SAP Commerce Cloud") {
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
                                applyConnectionSettings()
                                installAgentIntoWrapperConf()
                            }
                            link("Remove agent") {
                                uninstallAgentFromWrapperConf()
                            }
                        }
                    } else {
                        row {
                            comment("One-time setup: add the Reclazz agent to your SAP " +
                                    "Commerce server so class changes hot-reload at runtime. " +
                                    "Safe to re-run — fully idempotent.")
                        }
                        row {
                            button("Install Reclazz Agent") {
                                applyConnectionSettings()
                                installAgentIntoWrapperConf()
                            }
                        }
                    }

                    row {
                        checkBox("Compile Java in-process (skips ant, faster reloads)")
                            .bindSelected(::autoCompileCheckbox)
                    }
                    row {
                        checkBox("Auto-import ImpEx files on save")
                            .bindSelected(::autoImpexCheckbox)
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

            // The only place Reclazz mentions sponsorship: one line, opt-out,
            // never shown again once dismissed. No popups, no notifications,
            // no startup banner — a dev tool that nags is a dev tool people
            // uninstall.
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

            group("General") {
                row {
                    checkBox("Enable Reclazz features")
                        .bindSelected(::enabledCheckbox)
                        .comment("Auto-inject agent into run configs, trigger reloads on " +
                                "compile, and auto-configure JDK flags. Uncheck if you only " +
                                "want to monitor an externally-attached agent.")
                }
                row {
                    checkBox("Auto-configure JVM for hot-swap (recommended)")
                        .bindSelected(::autoDetectJdkCheckbox)
                }
                row {
                    checkBox("Verbose logging (for bug reports)")
                        .bindSelected(::verboseCheckbox)
                }
                row {
                    checkBox("Refresh JPA mapping when an entity field changes (opt-in)")
                        .bindSelected(::jpaRefreshCheckbox)
                        .comment("Rebuilds the persistence unit so a new @Entity field is mapped, " +
                                "on JetBrains Runtime or DCEVM with ddl-auto at update/create/create-drop. " +
                                "In any other setup the field is named in the log instead. Open persistence " +
                                "contexts from before the rebuild are closed.")
                }
            }

            collapsibleGroup("Advanced") {
                row("Exclude patterns:") {
                    textField()
                        .bindText(::excludePatternsField)
                        .comment("Semicolon-separated globs (e.g. *Test.class;*Mock*)")
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
                    button("Test Agent Connection") {
                        applyConnectionSettings()
                        testConnection()
                    }
                    button("Attach to Running Server...") {
                        applyConnectionSettings()
                        // Calls the shared flow directly. Invoking the
                        // action's actionPerformed from here would violate
                        // the platform's @ApiStatus.OverrideOnly contract.
                        AttachAction.attachInteractively(project)
                    }
                }
            }.apply { expanded = false }
        }
    }

    private fun installAgentIntoWrapperConf() {
        when (val result = HybrisAgentInstaller.install(project)) {
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

        // Pick *replaces* the current selection — prior behavior (merge)
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
        return enabledCheckbox != settings.enabled ||
                autoCompileCheckbox != settings.autoCompile ||
                autoImpexCheckbox != settings.autoImpex ||
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

    override fun apply() {
        val settings = ReclazzSettings.getInstance(project)
        settings.loadState(
            ReclazzSettings.State(
                enabled = enabledCheckbox,
                autoCompile = autoCompileCheckbox,
                autoImpex = autoImpexCheckbox,
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
        watchExtensionsField = settings.watchExtensions
        excludePatternsField = settings.excludePatterns
        debounceMsField = settings.debounceMs
        startupDelayField = settings.startupDelaySeconds
        verboseCheckbox = settings.verbose
        jpaRefreshCheckbox = settings.jpaRefresh
        autoDetectJdkCheckbox = settings.autoDetectJdk
        portFilePathField = settings.portFilePath
        agentPortField = settings.agentPort
    }
}
