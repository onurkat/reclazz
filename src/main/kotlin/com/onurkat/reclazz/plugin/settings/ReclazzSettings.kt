/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.PROJECT)
@State(
    name = "ReclazzSettings",
    storages = [Storage("reclazz.xml")]
)
class ReclazzSettings : PersistentStateComponent<ReclazzSettings.State> {

    data class State(
        /**
         * Off until the user says yes. Reclazz puts a `-javaagent` into the
         * JVM that runs their code; defaulting that on meant a freshly
         * installed plugin silently modified every Java run configuration.
         * The welcome dialog asks once and flips this.
         */
        var enabled: Boolean = false,
        var injectIntoTests: Boolean = false,
        var autoCompile: Boolean = false,
        var autoImpex: Boolean = false,
        var autoSolrReindex: Boolean = false,
        /** Native F4-equivalent Backoffice config reset on save; the agent's default is on. */
        var backofficeConfigReset: Boolean = true,
        var autoUpdateRunningSystem: Boolean = false,
        var impexAllowRemove: Boolean = false,
        var reloadBoundary: String = "immediate",
        var structuralReload: Boolean = true,
        /**
         * Null until the user chooses. Unset means on for IDE launches of ordinary projects and off for SAP Commerce
         * projects, which stay opt-in until a live SAP Commerce boot with it on is recorded (owner decision 2026-11-22).
         * See [effectiveResetStatics].
         */
        var resetStatics: Boolean? = null,
        var excludeClasses: String = "",
        var watchExtensions: String = "",
        var excludePatterns: String = "",
        /** Older IDE versions always supplied 500. Retained to read their project files. */
        var debounceMs: Long? = null,
        var fixedDebounceMs: Long? = null,
        var startupDelaySeconds: Int = 30,
        var verbose: Boolean = false,
        var jpaRefresh: Boolean = false,
        var autoDetectJdk: Boolean = true,
        var portFilePath: String = "",
        var agentDir: String = "",
        var agentPort: Int = 0,
        /**
         * Whether the (single, non-modal) sponsorship line in Settings has
         * been dismissed. Reclazz never nags: this is the only place it is
         * mentioned in the UI, and dismissing it is permanent.
         */
        var supportLineDismissed: Boolean = false,
        /** Setup wizard: optional steps the user skipped (";"-separated step ids). */
        var setupSkips: String = "",
        var dismissedSetupGeneration: Long? = null,
        var completedSetupGeneration: Long? = null
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        val restored = state.copy(
            fixedDebounceMs = state.fixedDebounceMs ?: state.debounceMs?.takeUnless { it == 500L },
            debounceMs = null
        )
        XmlSerializerUtil.copyBean(restored, this.state)
    }

    companion object {
        fun getInstance(project: Project): ReclazzSettings =
            project.getService(ReclazzSettings::class.java)

        /** The value Reclazz uses: the user's choice, else on outside SAP Commerce projects. */
        fun effectiveResetStatics(state: State, sapCommerce: Boolean): Boolean = state.resetStatics ?: !sapCommerce
    }
}
