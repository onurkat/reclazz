/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.plugin

import com.onurkat.reclazz.plugin.settings.ReclazzAppState
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import com.onurkat.reclazz.plugin.setup.SetupOffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SetupOfferTest {
    private val app = ReclazzAppState.State(welcomeShown = true)
    private val project = ReclazzSettings.State(enabled = true)
    private fun open(version: String = "1.10", id: String? = "installation", complete: Boolean = true) =
        SetupOffer.decide(app, project, version, id, complete)

    @Test fun reinstallAndRestartHaveDifferentOffers(@TempDir root: Path) {
        val plugin = Files.createDirectory(root.resolve("plugin"))
        val first = assertNotNull(SetupOffer.artifact(plugin))
        assertEquals(SetupOffer.Offer(true, true), open(id = first))
        assertEquals(SetupOffer.Offer(false, false), open(id = SetupOffer.artifact(plugin)))
        Files.move(plugin, root.resolve("original"))
        Files.createDirectory(plugin)
        val replacement = assertNotNull(SetupOffer.artifact(plugin))
        assertNotEquals(first, replacement)
        project.dismissedSetupGeneration = app.setupGeneration
        assertEquals(SetupOffer.Offer(true, true), open(id = replacement, complete = false))
    }

    @Test fun dismissalAndUpdateRespectProjectState() {
        open(complete = false)
        project.dismissedSetupGeneration = app.setupGeneration
        assertEquals(SetupOffer.Offer(false, false), open(complete = false))
        assertEquals(SetupOffer.Offer(true, false), open("1.11", complete = true))
        assertEquals(SetupOffer.Offer(false, true), open("1.11", complete = false))
    }

    @Test fun incompleteUndismissedProjectOpensOnUpdate() {
        open()
        assertEquals(SetupOffer.Offer(true, true), open("1.11", complete = false))
    }

    @Test fun missingArtifactIdentityFallsBackToActualSetup() {
        assertFalse(open(id = null).dialog)
        assertTrue(open(id = null, complete = false).dialog)
    }
}
