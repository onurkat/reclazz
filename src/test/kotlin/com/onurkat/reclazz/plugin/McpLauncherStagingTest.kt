/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.onurkat.reclazz.plugin.agent.McpLauncherStaging
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.concurrent.Executors
import kotlin.test.*

class McpLauncherStagingTest {
    @TempDir lateinit var home: Path
    @Test fun immutableAndIdempotent() {
        val jar = jar(home.resolve("bundle.jar"),"1.0")
        val a=McpLauncherStaging.stage(jar,jar,"1.0",javaPath(),home)
        val time=Files.getLastModifiedTime(a.directory.resolve("reclazz-mcp.jar"))
        val b=McpLauncherStaging.stage(jar,jar,"1.0",javaPath(),home)
        assertEquals(a,b);assertEquals(time,Files.getLastModifiedTime(b.directory.resolve("reclazz-mcp.jar")))
        assertContentEquals(Files.readAllBytes(jar),Files.readAllBytes(b.directory.resolve("reclazz-mcp.jar")))
        assertFalse(Files.readString(b.directory.resolve("reclazz-mcp")).contains("/jdk/bin/java"))
        assertFalse(Files.readString(b.directory.resolve("reclazz-mcp.cmd")).contains("/jdk/bin/java"))
    }
    @Test fun digestMismatchRefusesWithoutOverwriting() {
        val jar=jar(home.resolve("bundle.jar"),"1.0")
        val a=McpLauncherStaging.stage(jar,jar,"1.0",javaPath(),home)
        val target=a.directory.resolve("reclazz-mcp.jar");Files.writeString(target,"changed")
        assertFailsWith<IllegalArgumentException> { McpLauncherStaging.stage(jar,jar,"1.0",javaPath(),home) }
        assertEquals("changed",Files.readString(target))
    }
    @Test fun launcherTamperIsRejectedWithoutOverwriting() {
        val jar=jar(home.resolve("bundle.jar"),"1.0")
        val staged=McpLauncherStaging.stage(jar,jar,"1.0",javaPath(),home)
        val launcher=staged.directory.resolve("reclazz-mcp.cmd");Files.writeString(launcher,"changed launcher")
        assertFailsWith<IllegalArgumentException> { McpLauncherStaging.stage(jar,jar,"1.0",javaPath(),home) }
        assertEquals("changed launcher",Files.readString(launcher))
    }
    @Test fun twoVersionsCoexistAndRequireMatchingAgent() {
        val one=jar(home.resolve("one.jar"),"1.0"); val two=jar(home.resolve("two.jar"),"2.0")
        val a=McpLauncherStaging.stage(one,one,"1.0",javaPath(),home)
        val b=McpLauncherStaging.stage(two,two,"2.0",javaPath(),home)
        assertNotEquals(a.directory,b.directory);assertTrue(Files.exists(a.directory));assertTrue(Files.exists(b.directory))
        assertFailsWith<IllegalArgumentException> { McpLauncherStaging.stage(two,one,"2.0",javaPath(),home) }
    }
    @Test fun concurrentStagingPublishesOneWholeDirectory() {
        val jar=jar(home.resolve("bundle.jar"),"1.0");val pool=Executors.newFixedThreadPool(2)
        try {
            val futures=(1..2).map { pool.submit<McpLauncherStaging.Staged> { McpLauncherStaging.stage(jar,jar,"1.0",javaPath(),home) } }
            val stages=futures.map { it.get(15,java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(stages[0],stages[1]);assertEquals(Files.size(jar),Files.size(stages[0].directory.resolve("reclazz-mcp.jar")))
            Files.list(home.resolve(".reclazz/mcp")).use { paths -> assertTrue(paths.noneMatch { it.fileName.toString().startsWith(".stage-") }) }
        } finally { pool.shutdownNow() }
    }
    @Test fun cleanupKeepsKnownReferencesNewestThreeAndRechecks() {
        val root=home.resolve(".reclazz/mcp");Files.createDirectories(root)
        val dirs=(0..5).map { index ->
            val p=root.resolve("1.$index-"+index.toString().repeat(12));Files.createDirectories(p)
            Files.writeString(p.resolve("reclazz-mcp.jar"),"fixture")
            Files.setLastModifiedTime(p,java.nio.file.attribute.FileTime.fromMillis(1000L+index));p
        }
        val config=home.resolve("client.json");Files.writeString(config,dirs[0].toString())
        McpLauncherStaging.rememberConfig(home,config)
        val preview=McpLauncherStaging.cleanupCandidates(home);assertEquals(listOf(dirs[2],dirs[1]),preview)
        Files.writeString(config,dirs[1].toString())
        assertFailsWith<IllegalStateException> { McpLauncherStaging.cleanupConfirmed(home,preview) }
        assertTrue(Files.exists(dirs[1]))
        Files.writeString(config,dirs[0].toString());McpLauncherStaging.cleanupConfirmed(home,preview)
        assertTrue(Files.exists(dirs[0]));assertFalse(Files.exists(dirs[1]));assertFalse(Files.exists(dirs[2]))
        for(p in dirs.drop(3))assertTrue(Files.exists(p))
    }
    @Test fun planningDoesNotCreateCacheAndPublicationChecksThePreview() {
        val jar=jar(home.resolve("bundle.jar"),"1.0")
        val java=Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows"))"java.exe" else "java")
        val planned=McpLauncherStaging.plan(jar,jar,"1.0",java,home)
        assertFalse(Files.exists(home.resolve(".reclazz")))
        assertFailsWith<IllegalArgumentException> {
            McpLauncherStaging.stage(jar,jar,"1.0",java,home,planned.copy(directory=home.resolve("wrong")))
        }
        assertFalse(Files.exists(home.resolve(".reclazz")))
        assertEquals(planned,McpLauncherStaging.stage(jar,jar,"1.0",java,home,planned))
    }
    @Test fun windowsBatchPathsAreRefusedBeforeTheyCanReachCmd() {
        for(char in listOf("&","%","^","!","\"","\n")) {
            assertFailsWith<IllegalArgumentException>(char) {
                McpLauncherStaging.requireWindowsSafe(listOf("C:\\project$char"))
            }
        }
        McpLauncherStaging.requireWindowsSafe(listOf("C:\\project spaces\\app"))
    }
    companion object {
        fun javaPath():Path = Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows"))"java.exe" else "java")
        fun jar(path: Path,version: String): Path {
            val m=Manifest();m.mainAttributes[Attributes.Name.MANIFEST_VERSION]="1.0";m.mainAttributes.putValue("Implementation-Version",version)
            JarOutputStream(Files.newOutputStream(path),m).use { it.putNextEntry(JarEntry("payload"));it.write(version.toByteArray());it.closeEntry() }
            return path
        }
    }
}
