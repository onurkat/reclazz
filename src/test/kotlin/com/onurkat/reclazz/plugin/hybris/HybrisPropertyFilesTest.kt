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
import kotlin.test.assertTrue

/**
 * Picking the wrong properties layer is silent: the value lands in a
 * file, the server ignores it, and nothing explains why. These tests pin
 * the layer order Hybris actually uses.
 */
class HybrisPropertyFilesTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var configDir: Path
    private lateinit var platformHome: Path

    private fun setUpLayout(optionalDir: String? = "\${HYBRIS_CONFIG_DIR}/dev/props") {
        configDir = Files.createDirectories(tmp.resolve("hybris/config"))
        platformHome = Files.createDirectories(tmp.resolve("hybris/bin/platform"))
        Files.writeString(platformHome.resolve("project.properties"),
            "tomcat.javaoptions=\ntomcat.debugjavaoptions=-Xdebug -platform-default\n")
        val local = StringBuilder()
        if (optionalDir != null) local.append("hybris.optional.config.dir=$optionalDir\n")
        Files.writeString(configDir.resolve("local.properties"), local.toString())
    }

    private fun optionalDir(): Path =
        Files.createDirectories(configDir.resolve("dev/props"))

    @Test
    fun `optional config dir is resolved through the HYBRIS_CONFIG_DIR placeholder`() {
        setUpLayout()
        val dir = optionalDir()

        assertEquals(dir, HybrisPropertyFiles.optionalConfigDir(configDir))
    }

    @Test
    fun `target is our own file inside the optional dir when there is one`() {
        setUpLayout()
        optionalDir()

        val target = HybrisPropertyFiles.targetFile(configDir)

        assertEquals(HybrisPropertyFiles.RECLAZZ_PROPERTIES, target.fileName.toString())
        assertEquals(configDir.resolve("dev/props"), target.parent)
    }

    @Test
    fun `target falls back to local properties without an optional dir`() {
        setUpLayout(optionalDir = null)

        val target = HybrisPropertyFiles.targetFile(configDir)

        assertEquals("local.properties", target.fileName.toString())
    }

    @Test
    fun `optional dir wins over local properties`() {
        setUpLayout()
        val dir = optionalDir()
        Files.writeString(configDir.resolve("local.properties"),
            "hybris.optional.config.dir=\${HYBRIS_CONFIG_DIR}/dev/props\n" +
            "tomcat.debugjavaoptions=-from-local\n")
        Files.writeString(dir.resolve("10-local.properties"), "tomcat.debugjavaoptions=-from-optional\n")

        val value = HybrisPropertyFiles
            .effectiveValue(platformHome, configDir, "tomcat.debugjavaoptions")

        assertEquals("-from-optional", value)
    }

    @Test
    fun `later files in the optional dir win`() {
        setUpLayout()
        val dir = optionalDir()
        Files.writeString(dir.resolve("10-local.properties"), "tomcat.debugjavaoptions=-first\n")
        Files.writeString(dir.resolve("20-local.properties"), "tomcat.debugjavaoptions=-second\n")

        val value = HybrisPropertyFiles
            .effectiveValue(platformHome, configDir, "tomcat.debugjavaoptions")

        assertEquals("-second", value)
    }

    @Test
    fun `our own file is excluded so a reinstall does not append to itself`() {
        setUpLayout()
        val dir = optionalDir()
        Files.writeString(dir.resolve("10-local.properties"), "tomcat.debugjavaoptions=-user-value\n")
        // A previous install already wrote its own file, which sorts last.
        Files.writeString(dir.resolve(HybrisPropertyFiles.RECLAZZ_PROPERTIES),
            "tomcat.debugjavaoptions=-user-value -javaagent:/old/agent.jar\n")

        val value = HybrisPropertyFiles
            .effectiveValue(platformHome, configDir, "tomcat.debugjavaoptions")

        assertEquals("-user-value", value)
        assertTrue(!value.contains("javaagent"), "reinstall must not stack agent arguments")
    }

    @Test
    fun `platform default is used when nothing overrides it`() {
        setUpLayout()
        optionalDir()

        val value = HybrisPropertyFiles
            .effectiveValue(platformHome, configDir, "tomcat.debugjavaoptions")

        assertEquals("-Xdebug -platform-default", value)
    }

    @Test
    fun `commented definitions are ignored`() {
        setUpLayout()
        val dir = optionalDir()
        Files.writeString(dir.resolve("10-local.properties"),
            "#tomcat.debugjavaoptions=-commented-out\n")

        val value = HybrisPropertyFiles
            .effectiveValue(platformHome, configDir, "tomcat.debugjavaoptions")

        assertEquals("-Xdebug -platform-default", value)
    }

    @Test
    fun `files not matching the naming convention are ignored, as Hybris ignores them`() {
        // Verified against a real installation: a file in the optional
        // directory is only read when it is named <digits>-local.properties.
        // Reading a descriptively-named file here would make Reclazz believe
        // a value is in effect that the server never sees.
        setUpLayout()
        val dir = optionalDir()
        Files.writeString(dir.resolve("10-local.properties"), "tomcat.debugjavaoptions=-real\n")
        Files.writeString(dir.resolve("99-reclazz.properties"), "tomcat.debugjavaoptions=-ignored\n")
        Files.writeString(dir.resolve("90-reclazz-local.properties"), "tomcat.debugjavaoptions=-also-ignored\n")

        val value = HybrisPropertyFiles
            .effectiveValue(platformHome, configDir, "tomcat.debugjavaoptions")

        assertEquals("-real", value)
    }

    @Test
    fun `the file we write into is one Hybris will actually read`() {
        setUpLayout()
        optionalDir()

        val target = HybrisPropertyFiles.targetFile(configDir)

        assertTrue(
            Regex("""\d+-local\.properties""").matches(target.fileName.toString()),
            "target ${target.fileName} would be ignored by the platform"
        )
    }

    @Test
    fun `agent paths containing spaces are quoted`() {
        // Not an edge case: on macOS the plugin lives under
        // "~/Library/Application Support/JetBrains/...". These properties are
        // split on whitespace into wrapper.java.additional lines, so an
        // unquoted path with a space is dropped entirely and the server
        // starts silently without the agent.
        val quoted = HybrisAgentInstaller.agentArgument(
            "/Users/me/Library/Application Support/JetBrains/plugins/reclazz/agent.jar=hybrisHome=/srv")

        assertEquals(
            "-javaagent:\"/Users/me/Library/Application Support/JetBrains/plugins/reclazz/agent.jar=hybrisHome=/srv\"",
            quoted
        )
    }

    @Test
    fun `agent paths without spaces are left unquoted`() {
        val plain = HybrisAgentInstaller.agentArgument("/opt/reclazz/agent.jar=hybrisHome=/srv")

        assertEquals("-javaagent:/opt/reclazz/agent.jar=hybrisHome=/srv", plain)
    }

    @Test
    fun `missing property reads as null`() {
        setUpLayout()

        assertNull(HybrisPropertyFiles.readProperty(
            configDir.resolve("local.properties"), "tomcat.javaoptions"))
    }
}
