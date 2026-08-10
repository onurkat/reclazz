/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.notifications

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.onurkat.reclazz.plugin.hybris.HybrisAgentInstaller
import com.onurkat.reclazz.plugin.hybris.JdkInfo
import com.onurkat.reclazz.plugin.hybris.JdkVendor
import com.onurkat.reclazz.plugin.settings.ReclazzSettings

object ReloadNotifications {

    private const val GROUP_ID = "Reclazz"

    fun info(project: Project, title: String, content: String) {
        notify(project, title, content, NotificationType.INFORMATION)
    }

    /**
     * Report a config change with a way to go and look at it.
     *
     * Reclazz edits a file the user did not open, in a directory they may
     * never have visited. "Show file" is the difference between a tool
     * that configured something and a tool that did something to your
     * project you cannot inspect.
     */
    fun installed(project: Project, content: String, file: java.nio.file.Path) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification("Reclazz", content, NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimple("Show file") {
                com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .refreshAndFindFileByNioFile(file)
                    ?.let { com.intellij.openapi.fileEditor.FileEditorManager
                        .getInstance(project).openFile(it, true) }
            })
            .notify(project)
    }

    fun warn(project: Project, title: String, content: String) {
        notify(project, title, content, NotificationType.WARNING)
    }

    fun error(project: Project, title: String, content: String) {
        notify(project, title, content, NotificationType.ERROR)
    }

    fun notifyJdkDetection(project: Project, jdkInfo: JdkInfo) {
        val message = "Detected ${jdkInfo.displayName} — ${jdkInfo.capabilityDescription}"
        when {
            jdkInfo.vendor == JdkVendor.GRAALVM -> {
                warn(project, "Reclazz", "$message. GraalVM has known limitations with class redefinition.")
            }
            jdkInfo.capabilityLevel == JdkInfo.CapabilityLevel.COMPANION_MODE -> {
                info(project, "Reclazz",
                    "$message. New members reachable from hot-compiled code; " +
                    "reflective caches (e.g. Hybris ModelService) need a restart to see them.")
            }
            else -> {
                info(project, "Reclazz", message)
            }
        }
    }

    /**
     * Offer to switch Reclazz on for a project where it is currently off.
     *
     * Reclazz is enabled per project, while the welcome dialog is shown
     * once per installation — so the second project a user opens would
     * otherwise sit there doing nothing, with no hint why. This carries
     * the action itself rather than pointing at Settings, and the caller
     * shows it once per project per IDE session so it stays an offer
     * instead of a reminder.
     */
    fun offerToEnable(project: Project, isHybris: Boolean) {
        val content = if (isHybris) {
            "SAP Commerce project detected. Reclazz is off for this project."
        } else {
            "Reclazz is off for this project."
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification("Reclazz", content, NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Enable Reclazz") {
                ReclazzSettings.getInstance(project).state.enabled = true
                if (isHybris) {
                    when (val result = HybrisAgentInstaller.install(project)) {
                        is HybrisAgentInstaller.Result.Success ->
                            HybrisAgentInstaller.targetFile(project)?.let {
                                installed(project, result.message, it)
                            } ?: info(project, "Reclazz", result.message)
                        is HybrisAgentInstaller.Result.Error -> warn(project, "Reclazz", result.message)
                    }
                } else {
                    info(project, "Reclazz",
                        "Reclazz is on. Run your application from the IDE and build " +
                        "after an edit to hot-reload it.")
                }
            })
            .notify(project)
    }

    /**
     * Tell the user their own JVM options changed after Reclazz copied
     * them, so Reclazz's file is now overriding a value they have since
     * edited. Silence here would mean their change never reaches the
     * server and nothing says why.
     */
    fun notifyOptionsDrifted(project: Project, properties: List<String>) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                "Reclazz",
                "You changed ${properties.joinToString(" and ")}, but Reclazz's copy still " +
                "has the previous value and takes precedence. Re-sync to keep your change " +
                "and the agent.",
                NotificationType.WARNING
            )
            .addAction(NotificationAction.createSimpleExpiring("Re-sync") {
                when (val result = HybrisAgentInstaller.install(project)) {
                    is HybrisAgentInstaller.Result.Success ->
                        HybrisAgentInstaller.targetFile(project)?.let {
                            installed(project, result.message, it)
                        } ?: info(project, "Reclazz", result.message)
                    is HybrisAgentInstaller.Result.Error -> warn(project, "Reclazz", result.message)
                }
            })
            .addAction(NotificationAction.createSimpleExpiring("Remove Reclazz's override") {
                when (val result = HybrisAgentInstaller.uninstall(project)) {
                    is HybrisAgentInstaller.Result.Success -> info(project, "Reclazz", result.message)
                    is HybrisAgentInstaller.Result.Error -> warn(project, "Reclazz", result.message)
                }
            })
            .notify(project)
    }

    /**
     * The configured agent jar is gone, so the next server start will fail
     * outright rather than merely losing hot-reload. Say so plainly while
     * the developer is still in the IDE, and offer the one-click repair.
     */
    fun notifyAgentJarMissing(project: Project, missing: java.nio.file.Path) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                "Reclazz",
                "Your SAP Commerce server is configured to load an agent that no longer " +
                "exists ($missing). The server will refuse to start until this is fixed.",
                NotificationType.ERROR
            )
            .addAction(NotificationAction.createSimpleExpiring("Repair install") {
                when (val result = HybrisAgentInstaller.install(project)) {
                    is HybrisAgentInstaller.Result.Success ->
                        HybrisAgentInstaller.targetFile(project)?.let {
                            installed(project, result.message, it)
                        } ?: info(project, "Reclazz", result.message)
                    is HybrisAgentInstaller.Result.Error -> warn(project, "Reclazz", result.message)
                }
            })
            .addAction(NotificationAction.createSimpleExpiring("Remove Reclazz from startup") {
                when (val result = HybrisAgentInstaller.uninstall(project)) {
                    is HybrisAgentInstaller.Result.Success -> info(project, "Reclazz", result.message)
                    is HybrisAgentInstaller.Result.Error -> warn(project, "Reclazz", result.message)
                }
            })
            .notify(project)
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }
}
