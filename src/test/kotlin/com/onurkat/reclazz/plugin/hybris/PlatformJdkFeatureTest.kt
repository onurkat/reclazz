/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.hybris

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The version gate behind writing `--sun-misc-unsafe-memory-access=allow`.
 *
 * The flag keeps enum constant appends working on JDK 24 and newer, and a
 * launcher older than JDK 23 refuses to start on it, measured on SapMachine
 * 21: "Unrecognized option: --sun-misc-unsafe-memory-access=allow ... Could
 * not create the Java Virtual Machine". So the failure these tests prevent
 * is a server that no longer boots because the flag was written on a guess.
 * The detection reads the JDK the server actually starts with: the generated
 * wrapper.conf names it in `wrapper.java.command`, and the JDK's own release
 * file names its version.
 */
class PlatformJdkFeatureTest {

    @TempDir
    lateinit var tmp: Path

    private fun platformWith(wrapperJavaCommand: String?, releaseLine: String?): Path {
        val platformHome = Files.createDirectories(tmp.resolve("hybris/bin/platform"))
        if (wrapperJavaCommand != null) {
            val conf = Files.createDirectories(platformHome.resolve("tomcat/conf"))
            Files.writeString(conf.resolve("wrapper.conf"),
                "wrapper.working.dir=..\n" +
                "wrapper.java.command=$wrapperJavaCommand\n" +
                "wrapper.java.command.loglevel=DEBUG\n")
        }
        if (releaseLine != null) {
            val jdkHome = Files.createDirectories(tmp.resolve("jdk"))
            Files.writeString(jdkHome.resolve("release"),
                "IMPLEMENTOR=\"SAP SE\"\n$releaseLine\nOS_NAME=\"Darwin\"\n")
        }
        return platformHome
    }

    @Test
    fun `the platform JDK version is read from wrapper conf and the JDK's release file`() {
        val platformHome = platformWith(
            wrapperJavaCommand = tmp.resolve("jdk/bin/java").toString(),
            releaseLine = "JAVA_VERSION=\"21.0.10.0.1\"")

        assertEquals(21, HybrisAgentInstaller.platformJdkFeature(platformHome),
            "21.0.10.0.1 is feature 21, which must NOT get the flag: that " +
            "launcher refuses to start on it")
    }

    @Test
    fun `a JDK 26 platform is recognised as one that needs the flag`() {
        val platformHome = platformWith(
            wrapperJavaCommand = tmp.resolve("jdk/bin/java").toString(),
            releaseLine = "JAVA_VERSION=\"26\"")

        assertEquals(26, HybrisAgentInstaller.platformJdkFeature(platformHome))
    }

    @Test
    fun `an early access version still yields its feature number`() {
        val platformHome = platformWith(
            wrapperJavaCommand = tmp.resolve("jdk/bin/java").toString(),
            releaseLine = "JAVA_VERSION=\"26-ea\"")

        assertEquals(26, HybrisAgentInstaller.platformJdkFeature(platformHome))
    }

    @Test
    fun `no generated wrapper conf means no answer, which means no flag`() {
        val platformHome = platformWith(wrapperJavaCommand = null, releaseLine = null)

        assertNull(HybrisAgentInstaller.platformJdkFeature(platformHome),
            "a fresh checkout has never run ant; guessing here could stop " +
            "a server from booting")
    }

    @Test
    fun `a java command whose JDK home has no release file means no answer`() {
        val platformHome = platformWith(
            wrapperJavaCommand = tmp.resolve("gone/bin/java").toString(),
            releaseLine = null)

        assertNull(HybrisAgentInstaller.platformJdkFeature(platformHome))
    }
}
