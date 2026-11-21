/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.onurkat.reclazz.plugin.agent.McpLauncherStaging
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.*
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.*

class WindowsMcpLauncherTest {
    @TempDir lateinit var root: Path
    @Test fun rejectsVersionOutputFromFailingExecutable() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"),"Execution belongs to the Windows gate")
        val fake=root.resolve("fake java.cmd")
        Files.writeString(fake,"""
            @echo off
            if not "%~1"=="-version" goto run
            echo openjdk version "21.0.1"
            exit /b 1
            :run
            echo invoked>"%~dp0was-run"
            exit /b 3
        """.trimIndent().replace("\n","\r\n")+"\r\n")
        val jar=McpLauncherStagingTest.jar(root.resolve("stub.jar"),"1.0")
        val staged=McpLauncherStaging.stage(jar,jar,"1.0",Path.of(System.getProperty("java.home"),"bin/java.exe"),root)
        val p=ProcessBuilder("cmd.exe","/d","/s","/c","\"\"${staged.launcher}\"\"").apply {
            environment()["RECLAZZ_JAVA"]=fake.toString();environment().remove("JAVA_HOME")
        }.start()
        assertTrue(p.waitFor(20,TimeUnit.SECONDS));val err=p.errorStream.bufferedReader().readText()
        assertEquals(1,p.exitValue(),err);assertTrue(err.contains("RECLAZZ_JAVA"),err)
        assertFalse(Files.exists(root.resolve("was-run")),"Must reject before invoking the executable with -jar")
    }
    @Test fun executesCmdWithSafeSpacesAndPreservesExitCode() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"),"Execution belongs to the Windows gate")
        val jar=argvJar()
        val java=Path.of(System.getProperty("java.home"),"bin/java.exe")
        val staged=McpLauncherStaging.stage(jar,jar,"1.0",java,root)
        val args=listOf("--project-dir",root.resolve("project spaces").toString(),"tail")
        val command="\"\"${staged.launcher}\" "+args.joinToString(" ") { "\"$it\"" }+"\""
        val pb=ProcessBuilder("cmd.exe","/d","/s","/c",command)
        pb.environment()["RECLAZZ_JAVA"]=java.toString();pb.environment().remove("JAVA_HOME")
        val process=pb.start();assertTrue(process.waitFor(20,TimeUnit.SECONDS))
        val stderr=process.errorStream.bufferedReader().readText()
        assertEquals(3,process.exitValue(),stderr)
        assertEquals("",process.inputStream.bufferedReader().readText())
        val observed=stderr.lineSequence().filter { it.startsWith("ARG:") }
            .map { String(Base64.getDecoder().decode(it.removePrefix("ARG:")),Charsets.UTF_8) }.toList()
        assertEquals(args,observed)
    }    @Test fun pickedOptionsCannotSpoofOrHideTheRealVersion() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"),"Execution belongs to the Windows gate")
        val jar=McpLauncherStagingTest.jar(root.resolve("stub.jar"),"1.0")
        val staged=McpLauncherStaging.stage(jar,jar,"1.0",Path.of(System.getProperty("java.home"),"bin/java.exe"),root)
        val marker=root.resolve("was-run")
        for(version in listOf("1.8.0","17.0.1")) {
            Files.deleteIfExists(marker)
            val fake=root.resolve("fake java.cmd")
            Files.writeString(fake,"""
                @echo off
                if not "%~1"=="-version" goto run
                echo Picked up JAVA_TOOL_OPTIONS: -Dx="21"
                echo openjdk version "$version"
                exit /b 0
                :run
                echo invoked>"%~dp0was-run"
                exit /b 3
            """.trimIndent().replace("\n","\r\n")+"\r\n")
            val p=ProcessBuilder("cmd.exe","/d","/s","/c","\"\"${staged.launcher}\"\"").apply {
                environment()["RECLAZZ_JAVA"]=fake.toString();environment().remove("JAVA_HOME")
            }.start()
            assertTrue(p.waitFor(20,TimeUnit.SECONDS))
            assertEquals(if(version.startsWith("1."))1 else 3,p.exitValue(),p.errorStream.bufferedReader().readText())
            assertEquals(!version.startsWith("1."),Files.exists(marker))
        }
    }
    @Test fun unicodeFallbackRunsWithoutPathAndRestoresCodePage() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"),"Execution belongs to the Windows gate")
        val cmd=Path.of(System.getenv("SystemRoot"),"System32/cmd.exe").toString()
        val junction=root.resolve("JDK Müller")
        val link=ProcessBuilder(cmd,"/d","/c","mklink","/J",junction.toString(),System.getProperty("java.home")).redirectErrorStream(true).start()
        assertTrue(link.waitFor(20,TimeUnit.SECONDS));assertEquals(0,link.exitValue(),link.inputStream.bufferedReader().readText())
        try {
            val jar=McpLauncherStagingTest.jar(root.resolve("stub.jar"),"1.0")
            val staged=McpLauncherStaging.stage(jar,jar,"1.0",junction.resolve("bin/java.exe"),root)
            val witness=root.resolve("witness.cmd")
            Files.writeString(witness,"""
                @echo off
                "%SystemRoot%\System32\chcp.com" 437 >nul
                call "${staged.launcher}"
                set "RESULT=%errorlevel%"
                "%SystemRoot%\System32\chcp.com"
                exit /b %RESULT%
            """.trimIndent().replace("\n","\r\n")+"\r\n")
            val p=ProcessBuilder(cmd,"/d","/s","/c","\"\"$witness\"\"").apply {
                environment().remove("RECLAZZ_JAVA");environment().remove("JAVA_HOME");environment()["PATH"]=""
            }.start()
            assertTrue(p.waitFor(20,TimeUnit.SECONDS))
            val err=p.errorStream.bufferedReader().readText();val out=p.inputStream.bufferedReader().readText()
            // The fixture jar has no Main-Class: reaching Java's JAR error proves Unicode fallback resolution.
            assertTrue(err.contains("main manifest",ignoreCase=true),err)
            assertFalse(err.contains("Java 17+ not found"),err)
            assertTrue(out.contains("437"),out)
        } finally { Files.deleteIfExists(junction) }
    }
    @Test fun argvFixtureRunsWithoutCompilerApi() {
        val args=listOf("--project-dir",root.resolve("project spaces").toString(),"tail")
        val java=McpLauncherStagingTest.javaPath()
        val p=ProcessBuilder(listOf(java.toString(),"-jar",argvJar().toString())+args).start()
        assertTrue(p.waitFor(20,TimeUnit.SECONDS))
        val err=p.errorStream.bufferedReader().readText();assertEquals(3,p.exitValue(),err)
        val observed=err.lineSequence().filter { it.startsWith("ARG:") }
            .map { String(Base64.getDecoder().decode(it.removePrefix("ARG:")),Charsets.UTF_8) }.toList()
        assertEquals(args,observed);assertEquals("",p.inputStream.bufferedReader().readText())
    }
    @Test fun realJavaVersionWithPickedOptionsStartsTheJar() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"),"Execution belongs to the Windows gate")
        val jar=McpLauncherStagingTest.jar(root.resolve("stub.jar"),"1.0")
        val staged=McpLauncherStaging.stage(jar,jar,"1.0",McpLauncherStagingTest.javaPath(),root)
        val p=ProcessBuilder("cmd.exe","/d","/s","/c","\"\"${staged.launcher}\"\"").apply {
            environment()["RECLAZZ_JAVA"]=McpLauncherStagingTest.javaPath().toString()
            environment()["JAVA_TOOL_OPTIONS"]="-Dreclazz.fixture=\"quoted value\""
            environment().remove("JAVA_HOME")
        }.start()
        assertTrue(p.waitFor(20,TimeUnit.SECONDS));val err=p.errorStream.bufferedReader().readText()
        assertTrue(err.contains("main manifest",ignoreCase=true),err)
        assertFalse(err.contains("must name a working Java"),err)
    }
    /** Straight-line Java 17 bytecode; the IDE test runtime need not contain javac. */
    private fun argvJar():Path {
        val writer=ClassWriter(0)
        writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"Argv",null,"java/lang/Object",null)
        val main=writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,"main","([Ljava/lang/String;)V",null,null)
        main.visitCode()
        repeat(3) { index ->
            main.visitFieldInsn(Opcodes.GETSTATIC,"java/lang/System","err","Ljava/io/PrintStream;")
            main.visitLdcInsn("ARG:")
            main.visitMethodInsn(Opcodes.INVOKESTATIC,"java/util/Base64","getEncoder","()Ljava/util/Base64${'$'}Encoder;",false)
            main.visitVarInsn(Opcodes.ALOAD,0);main.visitInsn(Opcodes.ICONST_0+index);main.visitInsn(Opcodes.AALOAD)
            main.visitFieldInsn(Opcodes.GETSTATIC,"java/nio/charset/StandardCharsets","UTF_8","Ljava/nio/charset/Charset;")
            main.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/lang/String","getBytes","(Ljava/nio/charset/Charset;)[B",false)
            main.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/util/Base64${'$'}Encoder","encodeToString","([B)Ljava/lang/String;",false)
            main.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/lang/String","concat","(Ljava/lang/String;)Ljava/lang/String;",false)
            main.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/io/PrintStream","println","(Ljava/lang/String;)V",false)
        }
        main.visitInsn(Opcodes.ICONST_3)
        main.visitMethodInsn(Opcodes.INVOKESTATIC,"java/lang/System","exit","(I)V",false)
        main.visitInsn(Opcodes.RETURN);main.visitMaxs(6,1);main.visitEnd();writer.visitEnd()
        val jar=root.resolve("argv.jar");val manifest=Manifest()
        manifest.mainAttributes.putValue("Manifest-Version","1.0");manifest.mainAttributes.putValue("Implementation-Version","1.0");manifest.mainAttributes.putValue("Main-Class","Argv")
        JarOutputStream(Files.newOutputStream(jar),manifest).use { out -> out.putNextEntry(JarEntry("Argv.class"));out.write(writer.toByteArray());out.closeEntry() }
        return jar
    }

}
