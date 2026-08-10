/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.hybris

import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.intellij.openapi.roots.ProjectRootManager
import java.io.File
import java.nio.file.Files
import java.util.Properties

/**
 * Known JDK vendors. Detected from the JDK's release file and path heuristics.
 */
enum class JdkVendor(val displayLabel: String) {
    JETBRAINS("JetBrains Runtime"),
    DCEVM("DCEVM"),
    ORACLE("Oracle JDK"),
    SAP_MACHINE("SapMachine"),
    CORRETTO("Amazon Corretto"),
    TEMURIN("Eclipse Temurin"),
    AZUL_ZULU("Azul Zulu"),
    LIBERICA("BellSoft Liberica"),
    GRAALVM("GraalVM"),
    OPENJDK("OpenJDK"),
    UNKNOWN("Unknown JDK");

    /**
     * Whether this vendor's JVM exposes enhanced class redefinition via
     * `-XX:+AllowEnhancedClassRedefinition`. On enhanced-redefinition VMs the
     * original `Class` object itself gains new methods/fields, so reflection
     * and reflective caches (e.g. Hybris `ModelService`) see them immediately.
     *
     * On any other JDK 17+, structural hot-reload still works via Reclazz's
     * companion-class path — new members live on a hidden nestmate and are
     * reached through invokedynamic rewriting, which means hot-compiled code
     * can call them but reflection on the original class cannot.
     */
    val supportsEnhancedRedefinition: Boolean
        get() = this == JETBRAINS || this == DCEVM
}

data class JdkInfo(
    val version: Int,
    val vendor: JdkVendor,
    val sdkHomePath: String,
    val capabilityLevel: CapabilityLevel,
    val recommendedJvmArgs: List<String>
) {
    enum class CapabilityLevel {
        /** Structural hot-reload via Reclazz companion classes (any JDK 17+). */
        COMPANION_MODE,
        /** Enhanced redefinition — JBR/DCEVM with `-XX:+AllowEnhancedClassRedefinition`. */
        ENHANCED_REDEFINITION
    }

    val displayName: String
        get() = "JDK $version (${vendor.displayLabel})"

    val capabilityDescription: String
        get() = when (capabilityLevel) {
            CapabilityLevel.ENHANCED_REDEFINITION ->
                "structural hot-reload enabled (enhanced redefinition)"
            CapabilityLevel.COMPANION_MODE ->
                "structural hot-reload enabled (companion-class mode)"
        }
}

object JdkDetector {

    fun detect(project: Project): JdkInfo? {
        val sdk = ProjectRootManager.getInstance(project).projectSdk ?: return null
        val javaSdk = JavaSdk.getInstance()
        val sdkVersion = javaSdk.getVersion(sdk) ?: return null
        val homePath = sdk.homePath ?: return null

        val javaVersion = mapSdkVersion(sdkVersion) ?: return null
        val vendor = detectVendor(homePath)
        val hasEnhancedRedefinition = vendor.supportsEnhancedRedefinition

        val capabilityLevel = if (hasEnhancedRedefinition) {
            JdkInfo.CapabilityLevel.ENHANCED_REDEFINITION
        } else {
            JdkInfo.CapabilityLevel.COMPANION_MODE
        }

        val jvmArgs = buildJvmArgs(hasEnhancedRedefinition)

        return JdkInfo(
            version = javaVersion,
            vendor = vendor,
            sdkHomePath = homePath,
            capabilityLevel = capabilityLevel,
            recommendedJvmArgs = jvmArgs
        )
    }

    private fun mapSdkVersion(sdkVersion: JavaSdkVersion): Int? {
        val version = sdkVersion.name.removePrefix("JDK_").toIntOrNull()
        if (version != null && version >= 17) return version
        return null
    }

    /**
     * Detect the JDK vendor from the release file and path heuristics.
     * Checks are ordered from most specific to least specific.
     */
    fun detectVendor(homePath: String): JdkVendor {
        val releaseContent = try {
            Files.readString(File(homePath, "release").toPath())
        } catch (_: Exception) { "" }
        val releaseProps = parseReleaseContent(releaseContent)
        val implementor = releaseProps.getProperty("IMPLEMENTOR", "")
        val implementorVersion = releaseProps.getProperty("IMPLEMENTOR_VERSION", "")
        val normalizedPath = homePath.lowercase()

        // JetBrains Runtime
        if (implementor.contains("JetBrains", ignoreCase = true) ||
            implementorVersion.contains("JBR", ignoreCase = true) ||
            releaseContent.contains("JetBrains", ignoreCase = true) ||
            normalizedPath.contains("jbr") || normalizedPath.contains("jetbrains")) {
            return JdkVendor.JETBRAINS
        }

        // DCEVM (including Trava OpenJDK)
        if (implementorVersion.contains("DCEVM", ignoreCase = true) ||
            implementorVersion.contains("trava", ignoreCase = true) ||
            implementor.contains("Trava", ignoreCase = true) ||
            File(homePath, "lib/dcevm").isDirectory) {
            return JdkVendor.DCEVM
        }

        // GraalVM — check before Oracle since GraalVM may also contain "Oracle"
        if (implementor.contains("GraalVM", ignoreCase = true) ||
            implementorVersion.contains("GraalVM", ignoreCase = true) ||
            normalizedPath.contains("graalvm")) {
            return JdkVendor.GRAALVM
        }

        // Oracle JDK
        if (implementor.contains("Oracle", ignoreCase = true) ||
            normalizedPath.contains("oracle")) {
            return JdkVendor.ORACLE
        }

        // SapMachine
        if (implementor.contains("SAP", ignoreCase = true) ||
            implementorVersion.contains("SapMachine", ignoreCase = true) ||
            normalizedPath.contains("sapmachine")) {
            return JdkVendor.SAP_MACHINE
        }

        // Amazon Corretto
        if (implementor.contains("Amazon", ignoreCase = true) ||
            implementorVersion.contains("Corretto", ignoreCase = true) ||
            normalizedPath.contains("corretto")) {
            return JdkVendor.CORRETTO
        }

        // Eclipse Temurin (Adoptium)
        if (implementor.contains("Eclipse", ignoreCase = true) ||
            implementor.contains("Adoptium", ignoreCase = true) ||
            implementorVersion.contains("Temurin", ignoreCase = true) ||
            normalizedPath.contains("temurin") || normalizedPath.contains("adoptium")) {
            return JdkVendor.TEMURIN
        }

        // Azul Zulu
        if (implementor.contains("Azul", ignoreCase = true) ||
            implementorVersion.contains("Zulu", ignoreCase = true) ||
            normalizedPath.contains("zulu")) {
            return JdkVendor.AZUL_ZULU
        }

        // BellSoft Liberica
        if (implementor.contains("BellSoft", ignoreCase = true) ||
            implementorVersion.contains("Liberica", ignoreCase = true) ||
            normalizedPath.contains("liberica")) {
            return JdkVendor.LIBERICA
        }

        // Generic OpenJDK — any build with "OpenJDK" in implementor or path
        if (implementor.contains("OpenJDK", ignoreCase = true) ||
            releaseContent.contains("OpenJDK", ignoreCase = true) ||
            normalizedPath.contains("openjdk")) {
            return JdkVendor.OPENJDK
        }

        return JdkVendor.UNKNOWN
    }

    private fun parseReleaseContent(content: String): Properties {
        val props = Properties()
        if (content.isNotEmpty()) {
            try {
                props.load(content.reader())
            } catch (_: Exception) {}
        }
        return props
    }

    private fun buildJvmArgs(hasEnhancedRedefinition: Boolean): List<String> {
        val args = mutableListOf<String>()

        // Module access flags for JDK 17+ — required for instrumentation
        args.add("--add-opens=java.base/java.lang=ALL-UNNAMED")
        args.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED")
        args.add("--add-opens=java.base/java.io=ALL-UNNAMED")
        args.add("--add-opens=java.base/java.lang.invoke=ALL-UNNAMED")
        // Required by MethodForge to create MethodAccessor proxies for forged Method.invoke() support
        args.add("--add-opens=java.base/jdk.internal.reflect=ALL-UNNAMED")

        // -XX:+AllowEnhancedClassRedefinition is JBR/DCEVM-only.
        // On standard JVMs (Oracle, SapMachine, OpenJDK) this flag causes a startup crash.
        if (hasEnhancedRedefinition) {
            args.add("-XX:+AllowEnhancedClassRedefinition")
        }

        return args
    }
}
