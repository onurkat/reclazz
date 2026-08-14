/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.onurkat.reclazz.plugin.settings.ReclazzSettings

class ReloadStatusWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = ID

    override fun getDisplayName(): String = "Reclazz Status"

    /**
     * Only where Reclazz is switched on.
     *
     * The default is to be available everywhere, which put a permanent status
     * bar item into projects that have nothing to do with this plugin. The
     * tool window already scopes itself the same way; the two surfaces
     * disagreeing was the actual defect.
     */
    override fun isAvailable(project: Project): Boolean =
        ReclazzSettings.getInstance(project).state.enabled

    override fun createWidget(project: Project): StatusBarWidget = ReloadStatusWidget(project)

    companion object {
        const val ID = "ReclazzStatusWidget"

        /**
         * Availability is now a function of a setting, and the platform only
         * asks once unless it is told to ask again. Without this the widget
         * would appear or disappear at the next restart rather than when the
         * user flips the switch.
         */
        fun refreshAvailability(project: Project) {
            if (project.isDisposed) return
            project.getService(StatusBarWidgetsManager::class.java)
                ?.updateWidget(ReloadStatusWidgetFactory::class.java)
        }
    }
}
