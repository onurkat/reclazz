/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.intellij.openapi.project.Project
import com.onurkat.reclazz.plugin.hybris.HybrisAgentInstaller
import com.onurkat.reclazz.plugin.hybris.HybrisProjectDetector
import com.onurkat.reclazz.plugin.notifications.ReloadNotifications
import com.onurkat.reclazz.plugin.settings.ReclazzSettings

/**
 * Turning Reclazz on, in one place.
 *
 * Three entry points lead here: the first-run notification, the welcome
 * dialog's accept button, and the per-project "Reclazz is off here" offer.
 * They used to carry their own copies of this, which drifted, so the
 * SAP Commerce failure path told you to retry in Settings from one of
 * them and not from the other.
 */
object ReclazzActivation {

    /**
     * Switch Reclazz on for [project] and take the single next step that
     * project needs: SAP Commerce servers start outside the IDE so the
     * agent has to be installed into the platform properties, everything
     * else gets the agent injected into its run configurations already.
     */
    fun enable(project: Project) {
        ReclazzSettings.getInstance(project).state.enabled = true

        if (!HybrisProjectDetector.isHybrisProject(project)) {
            ReloadNotifications.info(
                project, "Reclazz",
                "Reclazz is on. Run your application from the IDE and the agent " +
                "attaches itself; build after an edit to hot-reload it."
            )
            return
        }

        when (val result = HybrisAgentInstaller.install(project)) {
            is HybrisAgentInstaller.Result.Success ->
                HybrisAgentInstaller.targetFile(project)?.let {
                    ReloadNotifications.installed(project, result.message, it)
                } ?: ReloadNotifications.info(project, "Reclazz", result.message)

            is HybrisAgentInstaller.Result.Error ->
                ReloadNotifications.warn(
                    project, "Reclazz",
                    result.message + " You can retry from Settings > Tools > Reclazz."
                )
        }
    }
}
