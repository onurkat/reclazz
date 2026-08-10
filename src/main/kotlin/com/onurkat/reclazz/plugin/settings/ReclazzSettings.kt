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
        var autoCompile: Boolean = false,
        var autoImpex: Boolean = false,
        var watchExtensions: String = "",
        var excludePatterns: String = "",
        var debounceMs: Long = 500,
        var startupDelaySeconds: Int = 30,
        var verbose: Boolean = false,
        var autoDetectJdk: Boolean = true,
        var portFilePath: String = "",
        var agentPort: Int = 0,
        /**
         * Whether the (single, non-modal) sponsorship line in Settings has
         * been dismissed. Reclazz never nags: this is the only place it is
         * mentioned in the UI, and dismissing it is permanent.
         */
        var supportLineDismissed: Boolean = false
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    companion object {
        fun getInstance(project: Project): ReclazzSettings =
            project.getService(ReclazzSettings::class.java)
    }
}
