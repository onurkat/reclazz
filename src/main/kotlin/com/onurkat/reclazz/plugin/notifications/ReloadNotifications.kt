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
import com.onurkat.reclazz.plugin.ReclazzActivation
import com.onurkat.reclazz.plugin.settings.ReclazzSettings
import com.onurkat.reclazz.plugin.ui.WelcomeDialog

object ReloadNotifications {

    private const val GROUP_ID = "Reclazz"
    private const val WELCOME_GROUP_ID = "Reclazz Welcome"

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
                ReclazzActivation.enable(project)
            })
            .notify(project)
    }

    /**
     * The staged agent was replaced with the one from this plugin version.
     *
     * Worth interrupting for: a SAP Commerce server reads that jar at startup,
     * so until it restarts it keeps running the old agent while the IDE shows
     * the new version. Saying nothing is how someone ends up reporting a bug
     * that was fixed two releases ago.
     */
    fun agentJarRefreshed(project: Project, version: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(WELCOME_GROUP_ID)
            .createNotification(
                "Reclazz agent updated to $version",
                "Your SAP Commerce server loads the agent at startup, so restart it "
                        + "to pick this up. Until then it keeps running the previous agent.",
                NotificationType.INFORMATION
            )
            .notify(project)
    }

    /**
     * First run after installation. This is a notification rather than a
     * dialog on purpose: the startup activity must not block the event
     * thread, and Marketplace review fails a plugin that does. The full
     * introduction is one click away for anyone who wants it.
     *
     * Sticky, because an introduction that fades out after a few seconds
     * is an introduction most people never read.
     */
    fun welcome(project: Project, isHybris: Boolean) {
        val next = if (isHybris) {
            "SAP Commerce project detected. Enabling installs the agent into your " +
            "platform properties; it applies after the next ant server and restart."
        } else {
            "Enabling lets Reclazz inject its agent into the run configurations you " +
            "start from the IDE."
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup(WELCOME_GROUP_ID)
            .createNotification(
                "Reclazz is installed, and switched off",
                "Hot-reload without restarting. Reclazz ships off because it puts a " +
                "java agent into the JVM that runs your code, so it asks first. " + next,
                NotificationType.INFORMATION
            )
            .addAction(NotificationAction.createSimpleExpiring(
                if (isHybris) "Install Agent" else "Enable Reclazz"
            ) {
                ReclazzActivation.enable(project)
            })
            .addAction(NotificationAction.createSimpleExpiring("What it does") {
                WelcomeDialog.showOnDemand(project)
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
