/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.agent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.jar.JarFile

/** A client keeps its own immutable build; only the Java fallback is shared. */
object McpLauncherStaging {
    data class Staged(val directory: Path, val version: String) {
        val launcher: Path get() = directory.resolve(if (System.getProperty("os.name").startsWith("Windows")) "reclazz-mcp.cmd" else "reclazz-mcp")
    }

    private data class Bundle(val staged: Staged, val jar: ByteArray, val sh: String, val cmd: String)

    /** Computes and validates the proposed location without creating or refreshing anything. */
    fun plan(mcp: Path, agent: Path, version: String, java: Path,
             home: Path = Path.of(System.getProperty("user.home"))): Staged = bundle(mcp, agent, version, java, home).staged

    private fun bundle(mcp: Path, agent: Path, version: String, java: Path, home: Path): Bundle {
        require(version.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid bundled MCP version" }
        for (jar in listOf(mcp, agent)) JarFile(jar.toFile()).use {
            require(it.manifest?.mainAttributes?.getValue("Implementation-Version") == version) {
                "MCP, agent and plugin versions must match before connecting an assistant"
            }
        }
        require(java.isAbsolute && java.toString().none { it == '\n' || it == '\r' }) { "Java fallback must be an absolute executable path" }
        val jarBytes = Files.readAllBytes(mcp)
        val sh = shellLauncher(); val cmd = windowsLauncher()
        val identity = MessageDigest.getInstance("SHA-256")
        for (part in listOf(jarBytes, sh.toByteArray(Charsets.UTF_8), cmd.toByteArray(Charsets.UTF_8))) {
            identity.update((part.size.toString() + ":").toByteArray(Charsets.UTF_8))
            identity.update(part)
        }
        val bundleHash = identity.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val target = home.toAbsolutePath().resolve(".reclazz/mcp/$version-${bundleHash.take(12)}")
        if (System.getProperty("os.name").startsWith("Windows")) {
            requireWindowsSafe(listOf(target.toString(), java.toString()))
        }
        return Bundle(Staged(target, version), jarBytes, sh, cmd)
    }

    /** cmd.exe reparses batch invocations; these paths require manual Java-based registration. */
    fun requireWindowsSafe(values: List<String>) {
        require(values.none { value -> value.any { it in "&%^!\"\r\n" } }) {
            "Windows launcher paths cannot contain &, %, ^, !, quotes or line separators; choose a safe directory"
        }
    }

    fun stage(mcp: Path, agent: Path, version: String, java: Path,
              home: Path = Path.of(System.getProperty("user.home")), expected: Staged? = null): Staged {
        val bundle = bundle(mcp, agent, version, java, home)
        val target = bundle.staged.directory
        require(expected == null || expected == bundle.staged) { "Bundled MCP changed; preview again before publishing" }
        val root = target.parent
        val hash = digest(bundle.jar); val sh = bundle.sh; val cmd = bundle.cmd
        Files.createDirectories(root)
        fun verify() {
            require(Files.isRegularFile(target.resolve("reclazz-mcp.jar")) &&
                digest(Files.readAllBytes(target.resolve("reclazz-mcp.jar"))) == hash &&
                Files.readString(target.resolve("reclazz-mcp")) == sh &&
                Files.readString(target.resolve("reclazz-mcp.cmd")) == cmd) {
                "Staged MCP directory differs from this build; it was not overwritten: $target"
            }
        }
        if (Files.exists(target)) verify() else {
            val tmp = Files.createTempDirectory(root, ".stage-")
            try {
                Files.copy(mcp, tmp.resolve("reclazz-mcp.jar"))
                Files.writeString(tmp.resolve("reclazz-mcp"), sh)
                Files.writeString(tmp.resolve("reclazz-mcp.cmd"), cmd)
                if (Files.getFileStore(tmp).supportsFileAttributeView("posix"))
                    Files.setPosixFilePermissions(tmp.resolve("reclazz-mcp"), PosixFilePermissions.fromString("rwxr-xr-x"))
                try { Files.move(tmp, target, ATOMIC_MOVE) }
                catch (race: java.nio.file.FileSystemException) { if (!Files.isDirectory(target)) throw race }
                verify()
            } finally { tmp.toFile().deleteRecursively() }
        }
        val fallback = Files.createTempFile(root, ".java-", ".tmp")
        try {
            Files.writeString(fallback, java.toString() + "\n")
            Files.move(fallback, root.resolve("java-fallback"), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(fallback) }
        return Staged(target, version)
    }

    @Synchronized
    fun rememberConfig(home: Path, config: Path, copied: Staged? = null) {
        val root=home.toAbsolutePath().resolve(".reclazz/mcp");Files.createDirectories(root)
        val path=config.toAbsolutePath().normalize().toString()
        require(path.none { it=='\r' || it=='\n' }) { "Config path cannot contain line separators" }
        fun encode(text:String)=java.util.Base64.getUrlEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        // A copied command can live outside any known config. Keep its exact version even before paste.
        val reference=if(copied==null)path else "COPY\t${encode(path)}\t${encode(copied.directory.toAbsolutePath().normalize().toString())}"
        java.nio.channels.FileChannel.open(root.resolve("clients.lock"),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val registry=root.resolve("clients")
                val paths=(if(Files.exists(registry))Files.readAllLines(registry) else emptyList()).toMutableSet()
                paths.add(reference)
                val tmp=Files.createTempFile(root,".clients-",".tmp")
                try { Files.writeString(tmp,paths.sorted().joinToString("\n",postfix="\n"));Files.move(tmp,registry,ATOMIC_MOVE,REPLACE_EXISTING) }
                finally { Files.deleteIfExists(tmp) }
            }
        }
    }

    /** Unreadable known configs block cleanup. The running version and copied setups are never candidates. */
    fun cleanupCandidates(home: Path, current: Staged? = null): List<Path> {
        val root=home.toAbsolutePath().resolve(".reclazz/mcp")
        if(!Files.isDirectory(root))return emptyList()
        val registry=root.resolve("clients")
        val references=if(Files.exists(registry))Files.readAllLines(registry).filter { it.isNotBlank() } else emptyList()
        val copied=mutableSetOf<Path>()
        val configs=references.map { line ->
            if(!line.startsWith("COPY\t")) Path.of(line) else {
                val fields=line.split('\t');require(fields.size==3) { "Invalid copied client reference; cleanup stopped" }
                fun decode(value:String)=String(java.util.Base64.getUrlDecoder().decode(value),Charsets.UTF_8)
                copied.add(Path.of(decode(fields[2])))
                Path.of(decode(fields[1]))
            }
        }
        val contents=configs.filter { Files.exists(it) }.map { Files.readString(it) }
        fun version(path:Path)=path.fileName.toString().dropLast(13)
        fun compareVersions(a:String,b:String):Int {
            val left=Regex("[0-9]+|[^0-9]+").findAll(a).map { it.value }.toList()
            val right=Regex("[0-9]+|[^0-9]+").findAll(b).map { it.value }.toList()
            for(i in 0 until minOf(left.size,right.size)) {
                val x=left[i];val y=right[i]
                val order=if(x.all { it.isDigit() } && y.all { it.isDigit() }) java.math.BigInteger(x).compareTo(java.math.BigInteger(y)) else x.compareTo(y)
                if(order!=0)return order
            }
            return left.size.compareTo(right.size)
        }
        val directories=Files.list(root).use { paths -> paths.filter {
            Files.isDirectory(it) && !Files.isSymbolicLink(it) && it.fileName.toString().matches(Regex("[A-Za-z0-9._-]+-[0-9a-f]{12}"))
        }.toList().sortedWith { a,b ->
            val order=compareVersions(version(b),version(a))
            if(order!=0)order else Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a))
        } }
        return directories.drop(3).filter { dir ->
            val path=dir.toString()
            version(dir)!=current?.version && dir !in copied &&
                contents.none { it.contains(path) || it.contains(path.replace("\\","\\\\")) || it.contains(path.replace("\\","/")) }
        }
    }
    fun cleanupConfirmed(home: Path, preview: List<Path>, current: Staged? = null) {
        val now=cleanupCandidates(home,current).toSet()
        check(preview.all { it in now }) { "Staged versions or client references changed; preview cleanup again" }
        for(dir in preview)check(dir.toFile().deleteRecursively()) { "Could not remove staged MCP version: $dir" }
    }

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    internal fun shellLauncher(): String = """
        #!/bin/sh
        set -eu
        fail() { printf '%s\n' "Reclazz MCP: ${'$'}*" >&2; exit 1; }
        compatible() {
          [ -x "${'$'}1" ] || return 1
          output=${'$'}("${'$'}1" -version 2>&1) || return 1
          version=''
          while IFS= read -r line; do
            case "${'$'}line" in
              'openjdk version "'*|'java version "'*)
                version=${'$'}{line#*\"}; version=${'$'}{version%%\"*}; break;;
            esac
          done <<EOF
        ${'$'}output
        EOF
          major=${'$'}{version%%.*}
          case "${'$'}major" in ''|*[!0-9]*) return 1;; esac
          [ "${'$'}major" -ge 17 ]
        }
        here=${'$'}(CDPATH= cd -P "${'$'}{0%/*}" && pwd)
        java=''
        if [ -n "${'$'}{RECLAZZ_JAVA:-}" ]; then
          compatible "${'$'}RECLAZZ_JAVA" || fail 'RECLAZZ_JAVA must name a working Java 17+ executable'
          java=${'$'}RECLAZZ_JAVA
        elif [ -n "${'$'}{JAVA_HOME:-}" ]; then
          java=${'$'}JAVA_HOME/bin/java
          compatible "${'$'}java" || fail 'JAVA_HOME must contain a working Java 17+ executable'
        else
          java=${'$'}(command -v java || true)
          if ! compatible "${'$'}java"; then
            java=''
            [ ! -f "${'$'}here/../java-fallback" ] || IFS= read -r java < "${'$'}here/../java-fallback" || true
            compatible "${'$'}java" || fail 'Java 17+ not found; set RECLAZZ_JAVA or JAVA_HOME'
          fi
        fi
        exec "${'$'}java" -jar "${'$'}here/reclazz-mcp.jar" "${'$'}@"
    """.trimIndent() + "\n"

    internal fun windowsLauncher(): String = """
        @echo off
        setlocal DisableDelayedExpansion
        if defined RECLAZZ_JAVA goto explicit
        if defined JAVA_HOME goto home
        set "JAVA="
        for %%J in (java.exe) do set "JAVA=%%~${'$'}PATH:J"
        call :compatible
        if not errorlevel 1 goto run
        set "JAVA="
        if exist "%~dp0..\java-fallback" call :fallback
        call :compatible
        if not errorlevel 1 goto run
        >&2 echo Reclazz MCP: Java 17+ not found; set RECLAZZ_JAVA or JAVA_HOME
        exit /b 1
        :explicit
        set "JAVA=%RECLAZZ_JAVA%"
        call :compatible
        if not errorlevel 1 goto run
        >&2 echo Reclazz MCP: RECLAZZ_JAVA must name a working Java 17+ executable
        exit /b 1
        :home
        set "JAVA=%JAVA_HOME%\bin\java.exe"
        call :compatible
        if not errorlevel 1 goto run
        >&2 echo Reclazz MCP: JAVA_HOME must contain a working Java 17+ executable
        exit /b 1
        :run
        "%JAVA%" -jar "%~dp0reclazz-mcp.jar" %*
        exit /b %errorlevel%
        :compatible
        if not defined JAVA exit /b 1
        if not exist "%JAVA%" exit /b 1
        rem Capture once using direct redirection. FOR /F command substitution reparses
        rem the quoted executable/redirection and loses stderr on real JBR on Windows.
        set "PROBE=%TEMP%\reclazz-java-%RANDOM%-%RANDOM%"
        mkdir "%PROBE%" >nul 2>&1
        if errorlevel 1 exit /b 1
        call "%JAVA%" -version >"%PROBE%\version.txt" 2>&1
        if errorlevel 1 (
          del /q "%PROBE%\version.txt" >nul 2>&1
          rmdir "%PROBE%" >nul 2>&1
          exit /b 1
        )
        set "VERSION="
        for /f "usebackq tokens=1,2,3" %%A in ("%PROBE%\version.txt") do if "%%B"=="version" if "%%A"=="openjdk" if not defined VERSION set "VERSION=%%~C"
        if not defined VERSION for /f "usebackq tokens=1,2,3" %%A in ("%PROBE%\version.txt") do if "%%B"=="version" if "%%A"=="java" if not defined VERSION set "VERSION=%%~C"
        del /q "%PROBE%\version.txt" >nul 2>&1
        rmdir "%PROBE%" >nul 2>&1
        if not defined VERSION exit /b 1
        set "MAJOR="
        for /f "tokens=1 delims=." %%M in ("%VERSION%") do set "MAJOR=%%M"
        for /f "delims=0123456789" %%X in ("%MAJOR%") do exit /b 1
        if %MAJOR% LSS 17 exit /b 1
        exit /b 0
        :fallback
        rem Read the UTF-8 file using UTF-8, then restore the caller's code page before Java runs.
        set "OLD_CP="
        for /f "tokens=2 delims=:" %%C in ('"%SystemRoot%\System32\chcp.com"') do set "OLD_CP=%%C"
        if not defined OLD_CP exit /b 1
        "%SystemRoot%\System32\chcp.com" 65001 >nul
        if errorlevel 1 exit /b 1
        set /p "JAVA=" < "%~dp0..\java-fallback"
        "%SystemRoot%\System32\chcp.com" %OLD_CP% >nul
        exit /b 0
    """.trimIndent().replace("\n", "\r\n") + "\r\n"
}
