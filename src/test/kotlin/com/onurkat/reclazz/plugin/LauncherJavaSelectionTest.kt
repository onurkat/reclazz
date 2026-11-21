/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.onurkat.reclazz.plugin.agent.McpLauncherStaging
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LauncherJavaSelectionTest {
    @TempDir lateinit var root: Path
    private fun java(name: String,major: Int=21): Path {
        val p=root.resolve(name);Files.createDirectories(p.parent)
        Files.writeString(p,"""
            #!/bin/sh
            if [ "${'$'}{1:-}" = -version ]; then printf 'openjdk version "$major.0.1"\n' >&2; exit 0; fi
            printf '%s\n' "${'$'}@" > "${'$'}CAPTURE"
            exit 3
        """.trimIndent()+"\n");p.toFile().setExecutable(true);return p
    }
    private fun stage(java: Path): McpLauncherStaging.Staged {
        val j=root.resolve("bundle.jar");if(!Files.exists(j))McpLauncherStagingTest.jar(j,"1.0")
        return McpLauncherStaging.stage(j,j,"1.0",java,root)
    }
    private fun run(staged: McpLauncherStaging.Staged,env: Map<String,String> = emptyMap()): Triple<Int,String,String> {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val capture=root.resolve("argv.txt");Files.deleteIfExists(capture)
        val b=ProcessBuilder(staged.directory.resolve("reclazz-mcp").toString(),"--project-dir","/project spaces/& % ! ^ ' quote","last")
        b.environment().remove("JAVA_HOME");b.environment().remove("RECLAZZ_JAVA")
        b.environment()["PATH"]=root.resolve("empty-path").toString();b.environment()["CAPTURE"]=capture.toString();b.environment().putAll(env)
        val p=b.start();assertTrue(p.waitFor(15,java.util.concurrent.TimeUnit.SECONDS));val out=p.inputStream.bufferedReader().readText();val err=p.errorStream.bufferedReader().readText()
        if(p.exitValue()==3) {
            assertEquals("",out);val args=Files.readAllLines(capture)
            assertEquals(listOf("-jar",staged.directory.toRealPath().resolve("reclazz-mcp.jar").toString(),"--project-dir","/project spaces/& % ! ^ ' quote","last"),args)
        }
        return Triple(p.exitValue(),out,err)
    }
    @Test fun explicitJavaWinsAndInvalidExplicitDoesNotFallback() {
        val s=stage(java("fallback"));assertEquals(3,run(s,mapOf("RECLAZZ_JAVA" to java("explicit").toString(),"JAVA_HOME" to "/missing")).first)
        val r=run(s,mapOf("RECLAZZ_JAVA" to "/missing"));assertEquals(1,r.first);assertTrue(r.third.contains("RECLAZZ_JAVA"))
    }
    @Test fun javaHomeWinsAndRejectsOldJava() {
        val s=stage(java("fallback"));java("jdk/bin/java")
        assertEquals(3,run(s,mapOf("JAVA_HOME" to root.resolve("jdk").toString())).first)
        java("old/bin/java",11);assertEquals(1,run(s,mapOf("JAVA_HOME" to root.resolve("old").toString())).first)
    }
    @Test fun pathJavaWinsBeforeFallback() {
        val s=stage(root.resolve("missing-fallback"));val java=java("path/bin/java")
        assertEquals(3,run(s,mapOf("PATH" to java.parent.toString())).first)
    }
    @Test fun sameJarNewIdeRefreshesFallback() {
        val a=java("IDE A/bin/java");val s=stage(a)
        assertEquals(3,run(s).first)
        val b=java("IDE B/bin/java");assertEquals(s,stage(b));Files.delete(a)
        assertEquals(3,run(s).first)
    }
    @Test fun quotedPickedOptionsCannotMakeOldJavaLookSupported() {
        val fake=java("old-with-options",8)
        Files.writeString(fake,Files.readString(fake).replace("then printf", "then printf 'Picked up JAVA_TOOL_OPTIONS: -Dx=\"21\"\\n' >&2; printf"))
        val result=run(stage(fake),mapOf("RECLAZZ_JAVA" to fake.toString()))
        assertEquals(1,result.first);assertTrue(result.third.contains("RECLAZZ_JAVA"))
    }
    @Test fun pickedOptionsBeforeValidVersionDoNotRejectJava() {
        val fake=java("valid-with-options",17)
        Files.writeString(fake,Files.readString(fake).replace("then printf", "then printf 'Picked up _JAVA_OPTIONS: -Dx=\"text\"\\n' >&2; printf"))
        assertEquals(3,run(stage(fake),mapOf("RECLAZZ_JAVA" to fake.toString())).first)
    }
    @Test fun missingFallbackFailsWithoutScanningOrStdout() {
        val s=stage(root.resolve("deleted-ide/bin/java"));val r=run(s)
        assertEquals(1,r.first);assertEquals("",r.second);assertTrue(r.third.contains("Java 17+"))
    }
}
