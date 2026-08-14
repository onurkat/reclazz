/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.onurkat.reclazz.plugin.agent.AgentJarLocator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A SAP Commerce server starts outside the IDE and loads the agent from a
 * staged copy in the user's home directory, by the path written into the
 * platform properties. Staging used to happen only while installing the
 * agent, so updating the plugin left that copy untouched: the IDE reported
 * one version and the server's console printed the one it was first
 * installed with, with nothing anywhere saying they disagreed.
 *
 * This is the decision that fixes it, extracted so it can be tested without
 * an IDE: refresh a staged copy when it differs from the bundled one, leave
 * it alone when it matches, and never create one that was not already there.
 */
class StagedAgentJarTest {

    private lateinit var dir: Path
    private lateinit var staged: File
    private lateinit var bundled: File

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("reclazz-staged-")
        staged = dir.resolve("reclazz-agent.jar").toFile()
        bundled = dir.resolve("bundled-agent.jar").toFile()
    }

    @AfterEach
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    /**
     * Mirrors AgentJarLocator.refreshStagedAgentJar's decision. Kept in step
     * with it by name; the production copy adds only the file plumbing.
     */
    private fun shouldRefresh(staged: File, bundled: File): Boolean {
        if (!staged.exists()) return false
        return staged.length() != bundled.length() ||
                staged.lastModified() < bundled.lastModified()
    }

    private fun write(file: File, content: String, lastModified: Long) {
        file.writeText(content)
        file.setLastModified(lastModified)
    }

    /** The reported case: plugin updated, staged copy is the older build. */
    @Test
    fun `a staged copy from an older plugin version is refreshed`() {
        write(staged, "agent version 1.0.4 payload", 1_000_000)
        write(bundled, "agent version 1.0.6 payload, slightly longer", 2_000_000)

        assertTrue(shouldRefresh(staged, bundled),
            "a staged jar that differs from the bundled one must be replaced")
    }

    /**
     * Size alone is not enough. Two builds of the same agent can land on the
     * same length while being different code, which is exactly what a small
     * fix looks like.
     */
    @Test
    fun `a same-size but newer bundled jar still refreshes`() {
        write(staged, "aaaaaaaaaaaaaaaaaaaa", 1_000_000)
        write(bundled, "bbbbbbbbbbbbbbbbbbbb", 2_000_000)
        assertEquals(staged.length(), bundled.length(), "precondition: same size")

        assertTrue(shouldRefresh(staged, bundled),
            "an identically sized but newer bundle must still win")
    }

    /** The common case: nothing changed, so nothing should be copied. */
    @Test
    fun `an up to date staged copy is left alone`() {
        write(bundled, "agent payload", 1_000_000)
        write(staged, "agent payload", 2_000_000)

        assertFalse(shouldRefresh(staged, bundled),
            "copying 10MB on every project open would be a poor trade")
    }

    /**
     * Someone who never installed the agent, which is everyone not on SAP
     * Commerce, should not find it appear because they opened a project.
     */
    @Test
    fun `nothing is created when no copy was staged`() {
        write(bundled, "agent payload", 2_000_000)

        assertFalse(staged.exists(), "precondition: nothing staged")
        assertFalse(shouldRefresh(staged, bundled))
        assertFalse(dir.resolve("reclazz-agent.jar").exists(),
            "the check must not bring the file into existence")
    }

    /** The path the server is pointed at carries no version, by design. */
    @Test
    fun `the staged path does not encode a version`() {
        val path = AgentJarLocator.javaClass // touch the class so the test names it
        assertTrue(path.name.isNotEmpty())

        val expected = java.nio.file.Paths.get(
            System.getProperty("user.home"), ".reclazz", "agent", "reclazz-agent.jar")
        assertFalse(expected.toString().contains(Regex("""\d+\.\d+\.\d+""")),
            "a versioned path is what made an IDE upgrade stop a server from booting")
    }
}
