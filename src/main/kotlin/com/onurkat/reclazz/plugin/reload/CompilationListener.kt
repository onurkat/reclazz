/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.reload

import com.intellij.compiler.server.BuildManagerListener
import com.intellij.openapi.project.Project
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import java.util.UUID

/**
 * Listens for build completion events and re-attempts agent discovery.
 * Covers the case where the IDE was started before the server — there
 * was no port file at startup, so the initial `connectToAgent()` call
 * found nothing and gave up. After a build (often the one that starts
 * the server), we retry.
 *
 * Path discovery lives in `ReloadManager.readPortFile()`, which checks
 * five candidate locations (explicit port, custom port file, the
 * IntelliJ default under `.idea/reclazz/`, the Hybris default under
 * `<hybris>/.reclazz/`, and the project-root `.reclazz/`). This
 * listener used to hardcode only the IntelliJ default path, which meant
 * Hybris users whose agent wrote its port file to `<hybris>/.reclazz/`
 * never got auto-reconnected after a build.
 */
class CompilationListener : BuildManagerListener {

    override fun buildFinished(project: Project, sessionId: UUID, isAutomake: Boolean) {
        if (!ReclazzSettings.getInstance(project).state.enabled) return

        val manager = ReloadManager.getInstance(project)
        if (manager.isConnected) return

        // Defer path resolution to ReloadManager — it knows all candidate
        // port file locations. connectToAgent() is a no-op if no port file
        // is found yet, so it's safe to call unconditionally.
        manager.connectToAgent()
    }
}
