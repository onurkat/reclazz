/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.onurkat.reclazz.plugin.hybris.writeAtomically
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The files this plugin writes belong to somebody else.
 *
 * `95-local.properties` and the generated wrapper configs are part of a SAP
 * Commerce installation, and `Files.writeString` truncates the target before it
 * writes into it. Between those two things the file is empty, and if the IDE is
 * killed or the disk is full it stays that way. A truncated `wrapper.conf` is a
 * server that will not start.
 *
 * There was a backup, and it is not the answer. It is taken once, the first
 * time Reclazz writes to a file it did not create, which protects the original
 * and nothing after it: every later write, and there is one each time a setting
 * changes, went straight at the live file with weeks of the user's own edits in
 * it.
 */
class ConfigWritesAreAtomicTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `the content lands`() {
        val target = tmp.resolve("95-local.properties")

        writeAtomically(target, "tomcat.javaoptions=-javaagent:reclazz\n")

        assertEquals("tomcat.javaoptions=-javaagent:reclazz\n", target.readText())
    }

    @Test
    fun `an existing config is replaced whole`() {
        val target = tmp.resolve("wrapper.conf")
        target.writeText("wrapper.java.additional.26=old\n")

        writeAtomically(target, "wrapper.java.additional.26=new\n")

        assertEquals("wrapper.java.additional.26=new\n", target.readText())
    }

    @Test
    fun `no temporary file is left in the user's config directory`() {
        writeAtomically(tmp.resolve("local.properties"), "a=1\n")
        writeAtomically(tmp.resolve("local.properties"), "a=2\n")

        assertEquals(listOf("local.properties"), tmp.listDirectoryEntries().map { it.fileName.toString() })
    }

    /**
     * The point of it. When the write cannot be completed, the file that was
     * there is the file that is still there: not an empty one, not half of one.
     */
    @Test
    fun `a failed write leaves the original untouched`() {
        val target = tmp.resolve("wrapper.conf")
        target.createDirectory()
        target.resolve("inside").writeText("still here\n")

        assertFailsWith<IOException> {
            writeAtomically(target, "wrapper.java.additional.26=new\n")
        }

        assertTrue(target.isDirectory(), "the target was not replaced by half a write")
        assertEquals("still here\n", target.resolve("inside").readText())
        assertEquals(listOf("wrapper.conf"), tmp.listDirectoryEntries().map { it.fileName.toString() },
            "and the temporary file was cleaned up on the way out")
    }

    /**
     * The property the whole thing exists for, watched rather than argued
     * about. A reader with no lock between it and the writer must see either
     * the old file or the new one, and never the space between them. Measured
     * against what this replaced, same shape, same machine:
     * `Files.writeString` was caught mid-flight by 7 and then 9 of 22 reads at
     * exactly this size and count. Here it has to be none of them.
     */
    @Test
    fun `a reader never sees half of it`() {
        val before = "a".repeat(64_000)
        val after = "b".repeat(64_000)
        val target = tmp.resolve("wrapper.conf")
        writeAtomically(target, before)

        val torn = java.util.concurrent.atomic.AtomicInteger()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val stop = java.util.concurrent.atomic.AtomicBoolean()
        val reader = Thread {
            while (!stop.get()) {
                try {
                    val seen = target.readText()
                    reads.incrementAndGet()
                    if (seen != before && seen != after) torn.incrementAndGet()
                } catch (_: IOException) {
                    // A read landing exactly on the rename may fail; what it
                    // must never do is succeed with half a file.
                }
                Thread.sleep(0, 200_000)
            }
        }
        reader.start()
        try {
            repeat(60) { writeAtomically(target, if (it % 2 == 0) after else before) }
        } finally {
            stop.set(true)
            reader.join(5000)
        }

        assertTrue(reads.get() > 0, "the reader never got to look, so this proved nothing")
        assertEquals(0, torn.get(),
            "${torn.get()} of ${reads.get()} reads saw a file that was neither the old one nor the new one")
    }

    /**
     * The permissions belong to the file, not to how it was replaced.
     * `createTempFile` makes an owner-only file, and on the SAP Commerce
     * installation this was measured against, `wrapper.conf` is 777: handing it
     * back as 600 would leave a server another account starts unable to read
     * its own configuration.
     */
    @Test
    fun `the file's own permissions survive the replacement`() {
        val target = tmp.resolve("wrapper.conf")
        target.writeText("old\n")
        val wide = java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx")
        try {
            Files.setPosixFilePermissions(target, wide)
        } catch (_: UnsupportedOperationException) {
            return
        }

        writeAtomically(target, "new\n")

        assertEquals(wide, Files.getPosixFilePermissions(target),
            "the replacement handed the user's config back locked to its owner")
    }

    /** A parent that has to be created is created, as the old write did. */
    @Test
    fun `a missing directory is made rather than refused`() {
        val target = tmp.resolve("config").resolve("dev").resolve("props").resolve("95-local.properties")

        writeAtomically(target, "a=1\n")

        assertTrue(Files.isRegularFile(target))
        assertEquals("a=1\n", target.readText())
    }
}
