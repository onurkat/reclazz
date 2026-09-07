/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.reload

import com.intellij.compiler.server.BuildManagerListener
import com.intellij.openapi.project.Project
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import java.util.UUID

/** Build starts hold output; only the compiler's result can release it. */
class CompilationListener : BuildManagerListener {
    override fun buildStarted(project: Project, sessionId: UUID, isAutomake: Boolean) {
        if (ReclazzSettings.getInstance(project).state.enabled) ReloadManager.getInstance(project).buildStarted()
    }

    override fun buildFinished(project: Project, sessionId: UUID, isAutomake: Boolean) {
        if (!ReclazzSettings.getInstance(project).state.enabled) return
        val manager = ReloadManager.getInstance(project)
        if (!manager.isConnected) manager.connectToAgent()
    }
}

internal fun buildSucceeded(aborted: Boolean, errors: Int): Boolean = !aborted && errors == 0

class CompilationResultListener : com.intellij.openapi.compiler.CompilationStatusListener {
    override fun compilationFinished(aborted: Boolean, errors: Int, warnings: Int,
                                     compileContext: com.intellij.openapi.compiler.CompileContext) {
        val project = compileContext.project
        if (!ReclazzSettings.getInstance(project).state.enabled) return
        ReloadManager.getInstance(project).buildFinished(buildSucceeded(aborted, errors))
    }

    override fun automakeCompilationFinished(errors: Int, warnings: Int,
                                             compileContext: com.intellij.openapi.compiler.CompileContext) {
        compilationFinished(false, errors, warnings, compileContext)
    }
}
