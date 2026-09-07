/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.intellij.openapi.components.State
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * What the IDE writes to `.idea/reclazz.xml` is a contract with every
 * project that has the plugin: the component name, the file, and the
 * property names of the state class. Rename any of them and the next
 * update silently resets that setting for everyone, with nothing saying so.
 * A change here is a migration to write, not a rename to make.
 */
class SettingsContractTest {

    private val persisted = mapOf(
        "enabled" to false,
        "autoCompile" to false,
        "autoImpex" to false,
        "watchExtensions" to "",
        "excludePatterns" to "",
        "debounceMs" to 500L,
        "startupDelaySeconds" to 30,
        "verbose" to false,
        "jpaRefresh" to false,
        "autoDetectJdk" to true,
        "portFilePath" to "",
        "agentPort" to 0,
        "supportLineDismissed" to false,
    )

    @Test
    fun `the component name and the file it is stored in do not move`() {
        val state = ReclazzSettings::class.java.getAnnotation(State::class.java)
            ?: error("ReclazzSettings has no @State")
        assertEquals("ReclazzSettings", state.name, "the element name in the project's .idea files")
        assertEquals(listOf("reclazz.xml"), state.storages.map { it.value }, "the file under .idea")
    }

    @Test
    fun `the persisted properties are exactly these, with these defaults`() {
        val fields = ReclazzSettings.State::class.java.declaredFields
            .filter { !it.isSynthetic }
            .associate { it.name to it.type.simpleName }
        assertEquals(persisted.keys.toSortedSet(), fields.keys.toSortedSet(),
            "a persisted property was renamed, removed or added without this contract being updated")

        val defaults = ReclazzSettings.State()
        for ((name, expected) in persisted) {
            val field = ReclazzSettings.State::class.java.getDeclaredField(name)
            field.isAccessible = true
            assertEquals(expected, field.get(defaults), "default of $name, which a project without the key gets")
        }
    }
}
