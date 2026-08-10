/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.hybris

import com.intellij.openapi.project.Project
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

object HybrisProjectDetector {

    private data class CachedValue<T>(val value: T, val nanoTimestamp: Long)

    private const val CACHE_TTL_NS = 60_000_000_000L // 1 minute in nanos

    private val cache = ConcurrentHashMap<String, CachedValue<Boolean>>()
    private val hybrisHomeCache = ConcurrentHashMap<String, CachedValue<Path?>>()

    fun isHybrisProject(project: Project): Boolean {
        val basePath = project.basePath ?: return false
        val cached = cache[basePath]
        if (cached != null && System.nanoTime() - cached.nanoTimestamp < CACHE_TTL_NS) {
            return cached.value
        }
        val result = detectHybris(basePath)
        cache[basePath] = CachedValue(result, System.nanoTime())
        return result
    }

    fun findHybrisHome(project: Project): Path? {
        val basePath = project.basePath ?: return null
        val cached = hybrisHomeCache[basePath]
        if (cached != null && System.nanoTime() - cached.nanoTimestamp < CACHE_TTL_NS) {
            return cached.value
        }
        val result = resolveHybrisHome(basePath)
        hybrisHomeCache[basePath] = CachedValue(result, System.nanoTime())
        return result
    }

    private fun detectHybris(basePath: String): Boolean {
        val base = Paths.get(basePath)

        // Direct layouts (basePath IS the hybris home or its parent)
        val markers = listOf(
            base.resolve("config/localextensions.xml"),
            base.resolve("hybris/config/localextensions.xml"),
            base.resolve("bin/platform/build.xml"),
            // SAP Commerce Cloud project layout — hybris nested at core-customize/hybris
            base.resolve("core-customize/hybris/config/localextensions.xml"),
            base.resolve("core-customize/hybris/bin/platform/build.xml")
        )

        return markers.any { it.toFile().exists() }
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
            if (candidate.resolve("bin").resolve("platform").toFile().exists()) {
                return candidate
            }
        }

        // Then walk upward in case basePath is nested inside a hybris install.
        var candidate: Path? = base
        var i = 0
        while (candidate != null && i < 5) {
            val platform = candidate.resolve("bin").resolve("platform")
            if (platform.toFile().exists()) {
                return candidate
            }
            val hybrisSub = candidate.resolve("hybris")
            if (hybrisSub.resolve("bin").resolve("platform").toFile().exists()) {
                return hybrisSub
            }
            candidate = candidate.parent
            i++
        }
        return null
    }
}
