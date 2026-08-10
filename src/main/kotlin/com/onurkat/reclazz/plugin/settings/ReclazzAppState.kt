/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * State that belongs to the installation rather than to a project.
 *
 * The welcome dialog is the reason this exists: [ReclazzSettings] is a
 * project service, so a "have we introduced ourselves yet" flag stored
 * there would reset for every project the user opens, and Reclazz would
 * greet them again and again.
 */
@Service(Service.Level.APP)
@State(
    name = "ReclazzAppState",
    storages = [Storage("reclazz-global.xml")]
)
class ReclazzAppState : PersistentStateComponent<ReclazzAppState.State> {

    data class State(
        /** Set once the welcome dialog has been shown, whatever the answer. */
        var welcomeShown: Boolean = false
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    companion object {
        fun getInstance(): ReclazzAppState =
            ApplicationManager.getApplication().getService(ReclazzAppState::class.java)
    }
}
