/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class ReloadStatusWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = "ReclazzStatusWidget"

    override fun getDisplayName(): String = "Reclazz Status"

    override fun createWidget(project: Project): StatusBarWidget {
        return ReloadStatusWidget(project)
    }
}
