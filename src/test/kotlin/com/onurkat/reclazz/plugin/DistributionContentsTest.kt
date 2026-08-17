/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertTrue

/**
 * The licences in the agent jar are not decoration: Apache 2.0 asks for the
 * licence and the NOTICE to travel with the distribution, and ASM's BSD asks
 * for its copyright notice. Keeping them in the repository satisfies nobody,
 * because the person who needs them is reviewing a downloaded zip on behalf of
 * an employer and has no reason to go looking for a git remote.
 *
 * This ran green for months while the zip carried neither file.
 */
class DistributionContentsTest {

    @Test
    fun `the distribution carries the licence terms of what is inside it`() {
        val zip = newestDistribution() ?: return  // nothing built in this run

        ZipFile(zip).use { archive ->
            val names = archive.entries().toList().map { it.name.substringAfterLast('/') }

            for (required in listOf("LICENSE", "NOTICE", "THIRD-PARTY.md")) {
                assertTrue(
                    names.contains(required),
                    "$required is missing from ${zip.name}; the terms have to ship with the code they cover",
                )
            }
        }
    }

    @Test
    fun `the agent ships nothing third-party outside its relocated packages`() {
        val zip = newestDistribution() ?: return

        ZipFile(zip).use { archive ->
            val agent = archive.entries().toList().firstOrNull { it.name.endsWith("reclazz-agent.jar") }
                ?: error("the agent jar is missing from the distribution")

            val temp = File.createTempFile("agent", ".jar").apply { deleteOnExit() }
            archive.getInputStream(agent).use { input -> temp.outputStream().use { input.copyTo(it) } }

            val strays = ZipFile(temp).use { inner ->
                inner.entries().toList()
                    .map { it.name }
                    .filter { it.endsWith(".class") }
                    .filterNot { it.startsWith("com/onurkat/") || it.startsWith("META-INF/") }
            }

            assertTrue(
                strays.isEmpty(),
                "third-party classes outside our packages can collide with the host application: $strays",
            )
        }
    }

    private fun newestDistribution(): File? =
        File("build/distributions").listFiles { f: File -> f.name.endsWith(".zip") && !f.name.contains("signed") }
            ?.maxByOrNull { it.lastModified() }
}
