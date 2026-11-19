/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.hybris

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

object HybrisProjectDetector {

    private data class CachedValue<T>(val value: T, val nanoTimestamp: Long)

    private const val CACHE_TTL_NS = 60_000_000_000L // 1 minute in nanos

    private val hybrisHomeCache = ConcurrentHashMap<String, CachedValue<Path?>>()

    fun isHybrisProject(project: Project): Boolean = findHybrisHome(project) != null

    fun findHybrisHome(project: Project): Path? {
        val basePath = project.basePath ?: return null
        val cached = hybrisHomeCache[basePath]
        if (cached != null && System.nanoTime() - cached.nanoTimestamp < CACHE_TTL_NS) {
            return cached.value
        }
        val result = resolveHybrisHome(basePath) ?: runReadAction {
            ModuleManager.getInstance(project).modules.asSequence()
                .flatMap { ModuleRootManager.getInstance(it).contentRoots.asSequence() }
                .mapNotNull { resolveHybrisHome(it.path) }
                .firstOrNull()
        }
        hybrisHomeCache[basePath] = CachedValue(result, System.nanoTime())
        return result
    }

    private fun resolveHybrisHome(basePath: String): Path? {
        val base = Paths.get(basePath)

        // Try common downward layouts first (SAP Commerce Cloud projects
        // nest hybris under core-customize/, on-prem layouts put it at
        // basePath/hybris or directly at basePath).
        val downwardCandidates = listOf(
            base,
            base.resolve("hybris"),
            base.resolve("core-customize").resolve("hybris"),
            base.resolve("core-customize"),
            base.resolve("commerce-suite"),
            base.resolve("ccv2").resolve("hybris")
        )
        for (candidate in downwardCandidates) {
            if (Files.isRegularFile(candidate.resolve("bin/platform/build.xml"))) {
                return candidate
            }
        }

        // Then walk upward in case basePath is nested inside a hybris install.
        var candidate: Path? = base
        var i = 0
        while (candidate != null && i < 5) {
            if (Files.isRegularFile(candidate.resolve("bin/platform/build.xml"))) {
                return candidate
            }
            val hybrisSub = candidate.resolve("hybris")
            if (Files.isRegularFile(hybrisSub.resolve("bin/platform/build.xml"))) {
                return hybrisSub
            }
            candidate = candidate.parent
            i++
        }
        return null
    }
}
