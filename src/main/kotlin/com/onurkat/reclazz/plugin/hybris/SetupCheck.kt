/* Copyright 2026 Onur Kat. SPDX-License-Identifier: Apache-2.0 */
package com.onurkat.reclazz.plugin.hybris

import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import javax.xml.parsers.DocumentBuilderFactory

/** Reads installed launch evidence. Checking setup never installs, repairs or creates a directory. */
internal object SetupCheck {
    enum class Level { OK, WARN, FAIL, SKIP }
    data class Finding(val id: String, val level: Level, val message: String, val fix: String = "")
    data class Input(val home: Path, val pluginVersion: String, val watchExtensions: Set<String> = emptySet(), val autoCompile: Boolean = false)
    data class Server(val version: String, val summary: String)
    private val keys = listOf("tomcat.javaoptions", "tomcat.debugjavaoptions")
    private val reclazzJar = Regex("""reclazz-agent(?:-[0-9][^/\\]*)?\.jar""")
    private val wrappers = listOf("wrapper.conf", "wrapper-debug.conf")
    private fun tokens(value: String) = HybrisAgentInstaller.jvmOptions(value)
    private fun isReclazz(token: String): Boolean {
        if(!token.startsWith("-javaagent:"))return false
        val jar=token.substringAfter(':').substringBefore('=').replace('\\','/')
        return reclazzJar.matches(jar.substringAfterLast('/')) || jar.endsWith("/.reclazz/agent.jar")
    }
    private fun debugFlags(tokens: List<String>) = tokens.filter { it == "-Xdebug" || it.startsWith("-Xrunjdwp:") || it.startsWith("-agentlib:jdwp") }.toSet()
    private fun read(path: Path): String? = try { if (Files.isRegularFile(path)) Files.readString(path) else null } catch (_: IOException) { null }
    private fun path(value: String): Path? = try { Path.of(value).toAbsolutePath().normalize() } catch (_: java.nio.file.InvalidPathException) { null }
    private fun expand(value:String,home:Path) = value
        .replace("\${HYBRIS_BIN_DIR}",home.resolve("bin").toString())
        .replace("\${HYBRIS_CONFIG_DIR}",home.resolve("config").toString())
        .replace("\${platformhome}",home.resolve("bin/platform").toString())
    private fun agentPath(token:String,home:Path):Path? {
        val raw=expand(token.substringAfter(':').substringBefore('='),home)
        if(raw.contains("\${"))return null
        return try { val parsed=Path.of(raw);(if(parsed.isAbsolute)parsed else home.resolve("bin/platform").resolve(parsed)).normalize() }
        catch(_:java.nio.file.InvalidPathException){null}
    }
    private fun agentIdentity(token: String,home:Path): String {
        val value=expand(token.substringAfter(':'),home);val actual=agentPath(token,home)
        val resolved=try { actual?.toRealPath() } catch (_: IOException) { actual }
        val args=if ('=' in value) value.substringAfter('=').split(',').associate { it.substringBefore('=') to it.substringAfter('=', "") }.toSortedMap().toString() else ""
        return "$resolved=$args"
    }
    private fun relevant(value: List<String>,home:Path) = value.filter(::isReclazz).map { agentIdentity(it,home) }.sorted() +
        value.filter { it == HybrisAgentInstaller.UNSAFE_ACCESS_FLAG }.sorted() + debugFlags(value).sorted()
    private fun wrapperOptions(file: Path): List<String>? {
        val text=read(file) ?: return null
        val numbered=text.lineSequence().map(String::trim).filter { it.startsWith("wrapper.java.additional.") && '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
            .filterKeys { it.substringAfterLast('.').toIntOrNull()!=null }.toSortedMap(compareBy { it.substringAfterLast('.').toInt() })
        return numbered.values.flatMap(::tokens)
    }
    private fun target(config: Path) = HybrisPropertyFiles.optionalConfigDir(config, createMissing=false)?.resolve(HybrisPropertyFiles.RECLAZZ_PROPERTIES) ?: config.resolve("local.properties")
    private fun managedValues(file: Path): Map<String,String> {
        var managed=false;val values=linkedMapOf<String,String>()
        read(file)?.lineSequence()?.forEach { line ->
            when {
                line.startsWith("# >>> reclazz") -> managed=true
                line.startsWith("# <<< reclazz") -> managed=false
                managed && keys.any { line.startsWith("$it=") } -> values[line.substringBefore('=')]=line.substringAfter('=')
            }
        }
        return values
    }
    fun check(input: Input, server: () -> Server? = { null }, checkCancelled: () -> Unit = {}): List<Finding> {
        val platform=input.home.resolve("bin/platform");val config=input.home.resolve("config")
        val values=keys.associateWith { HybrisPropertyFiles.effectiveValue(platform,config,it,excludeManagedBlock=false) }
        val general=HybrisPropertyFiles.effectiveValue(platform,config,"tomcat.generaloptions",excludeManagedBlock=false)
        val ltw=HybrisPropertyFiles.effectiveValue(platform,config,"ltw.configuration",excludeManagedBlock=false)
        val options=values.mapValues { tokens("$general ${it.value} $ltw") };val agents=options.mapValues { it.value.filter(::isReclazz) }
        val unresolvedAgentPath=agents.values.flatten().any { agentPath(it,input.home)==null }
        val rows=mutableListOf<Finding>()
        fun add(check: () -> Finding) { checkCancelled();rows.add(check()) }
        add {
            val wrong=agents.filterValues { it.size!=1 }.keys
            if(wrong.isNotEmpty()) Finding("agent-line",Level.FAIL,"Exactly one Reclazz Java agent is required in ${wrong.joinToString()}.","Use Install Java Agent / Reinstall in Settings, then run ant server and restart the server.")
            else {
                val managed=managedValues(target(config))
                val manual=keys.any { key -> agents.getValue(key).single() !in tokens(managed[key].orEmpty()) }
                Finding("agent-line",if(manual) Level.WARN else Level.OK,if(manual) "Reclazz is installed manually outside its managed block." else "Both launch modes contain one managed Reclazz Java agent.",if(manual) "Keep this manual configuration, or remove it before using Install Java Agent." else "")
            }
        }
        add {
            val missing=mutableListOf<String>();val stale=mutableListOf<String>()
            for((index,file) in wrappers.withIndex()) {
                val installed=wrapperOptions(platform.resolve("tomcat/conf/$file"))
                if(installed==null) missing.add(file) else if(relevant(installed,input.home)!=relevant(options.getValue(keys[index]),input.home)) stale.add(file)
            }
            when {
                unresolvedAgentPath -> Finding("ant-server",Level.WARN,"Cannot verify a configured Java agent path or placeholder.","Use a known SAP Commerce path placeholder or a path relative to bin/platform.")
                stale.isNotEmpty() -> Finding("ant-server",Level.FAIL,"Launch arguments differ in ${stale.joinToString()}.","Run ant server, then restart the server.")
                missing.isNotEmpty() -> Finding("ant-server",Level.SKIP,"Not built yet: ${missing.joinToString()} is missing.","Run ant server to generate the launch configuration.")
                else -> Finding("ant-server",Level.OK,"Both generated launch modes match the effective Java agent, Unsafe and debug options.")
            }
        }
        add {
            val key=keys[1];val file=target(config)
            val recorded=read(file)?.lineSequence()?.firstOrNull { it.startsWith("# reclazz-base:$key=") }?.substringAfter("# reclazz-base:$key=")
            val current=HybrisPropertyFiles.effectiveValue(platform,config,key,excludeManagedBlock=true)
            val expected=debugFlags(tokens(current))+debugFlags(tokens(recorded.orEmpty()))+debugFlags(tokens(general))
            val missing=expected-debugFlags(options.getValue(key))
            if(missing.isNotEmpty()) Finding("debug-flags",Level.FAIL,"Configured debug flags were lost or overridden.","Restore the original debug flags, Reinstall, then run ant server.")
            else Finding("debug-flags",Level.OK,"Original debug flags are retained.")
        }
        add {
            val jars=agents.values.flatten().map { agentPath(it,input.home) }.distinct()
            if(jars.isEmpty()) Finding("agent-jar",Level.SKIP,"No Reclazz jar is configured.","Install Java Agent in Settings.")
            else {
                val unresolved=jars.any { it==null }
                val missing=jars.filterNotNull().any { !Files.isRegularFile(it) }
                var unreadable=false
                val versions=if(missing || unresolved) emptyList() else jars.map { file ->
                    try { JarFile(file!!.toFile()).use { it.manifest?.mainAttributes?.getValue("Implementation-Version") } }
                    catch (_: IOException) { unreadable=true;null }
                }
                when {
                    missing -> Finding("agent-jar",Level.FAIL,"A configured Reclazz jar is missing.","Reinstall Java Agent before starting the server.")
                    unresolved -> Finding("agent-jar",Level.WARN,"Cannot verify a configured Java agent path or placeholder.","Use a known SAP Commerce path placeholder or a path relative to bin/platform.")
                    unreadable -> Finding("agent-jar",Level.FAIL,"A configured Java agent jar cannot be read as a JAR.","Reinstall Java Agent before starting the server.")
                    versions.any { it!=input.pluginVersion } -> Finding("agent-jar",Level.WARN,"Java agent jar version ${versions.joinToString { it ?: "unknown" }}; plugin ${input.pluginVersion}.","Reinstall Java Agent and restart the server to use the bundled version.")
                    else -> Finding("agent-jar",Level.OK,"Installed Java agent jar matches plugin ${input.pluginVersion}.")
                }
            }
        }
        add {
            val command=HybrisPropertyFiles.readProperty(platform.resolve("tomcat/conf/wrapper.conf"),"wrapper.java.command")?.trim()?.trim('"','\'')
            val home=command?.let(::path)?.parent?.parent
            val feature=home?.let(HybrisAgentInstaller::jdkFeatureOf)
            when {
                home==null || feature==null -> Finding("jdk",Level.SKIP,"Server JDK could not be determined from wrapper.java.command and its release file.","Check the generated wrapper's Java home.")
                (input.autoCompile || agents.values.flatten().any { it.substringAfter('=', "").split(',').contains("autoCompile=true") }) && !Files.isRegularFile(home.resolve("bin/javac")) && !Files.isRegularFile(home.resolve("bin/javac.exe")) -> Finding("jdk",Level.FAIL,"Server Java home has no javac; autoCompile needs a JDK.","Configure a full JDK and rebuild the server launch configuration.")
                feature>=24 && options.values.any { HybrisAgentInstaller.UNSAFE_ACCESS_FLAG !in it } -> Finding("jdk",Level.FAIL,"JDK $feature needs the Unsafe access flag in both launch modes.","Reinstall Java Agent, run ant server and restart the server.")
                else -> Finding("jdk",Level.OK,"Server JDK $feature satisfies the configured requirements.")
            }
        }
        add {
            val other=options.values.flatten().distinct().filter { (it.startsWith("-javaagent:") || it.startsWith("-agentpath:")) && !isReclazz(it) }
            val reload=other.any { token -> val name=token.substringAfter(':').substringBefore('=').substringAfterLast('/').substringAfterLast('\\').lowercase();name.contains("jrebel") || name.contains("hotswap-agent") || name.contains("hotswapagent") }
            when {
                reload -> Finding("other-agents",Level.FAIL,"Another reload Java agent is configured (JRebel/HotswapAgent).","Remove the other reload Java agent before starting with Reclazz.")
                other.isNotEmpty() -> Finding("other-agents",Level.WARN,"Other Java or native agents are configured.","Check their compatibility with Reclazz.")
                else -> Finding("other-agents",Level.OK,"No other Java or native agents are configured.")
            }
        }
        add {
            val requested=input.watchExtensions.filter { it.isNotBlank() && it!="*" }.toSet()
            val known=extensionNames(input.home,requested,checkCancelled)
            val unknown=requested-known
            if(unknown.isNotEmpty()) Finding("extensions",Level.WARN,"Unknown watched extensions: ${unknown.sorted().joinToString()}.","Correct watchExtensions or include these extensions in localextensions.xml.")
            else Finding("extensions",Level.OK,"Watched extension names are present in the configured/discovered extensions.")
        }
        add {
            try {
                val observed=server();checkCancelled()
                when {
                    observed==null -> Finding("server",Level.SKIP,"Server not running or no Java agent endpoint discovered.","Start the server with Reclazz, then check again.")
                    observed.version!=input.pluginVersion -> Finding("server",Level.WARN,"Running Java agent ${observed.version.ifBlank { "unknown" }}; plugin ${input.pluginVersion}. ${observed.summary}","Restart with the matching installed Java agent jar.")
                    else -> Finding("server",Level.OK,"Java agent ${observed.version}: ${observed.summary}")
                }
            } catch (_: IOException) { Finding("server",Level.WARN,"Could not read the running Java agent's DOCTOR response.","Check the connection and try again.") }
        }
        return rows
    }
    private fun xmlNames(file: Path, home: Path? = null): Set<String> {
        if(!Files.isRegularFile(file))return emptySet()
        return try {
            val factory=DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false)
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false)
            factory.isXIncludeAware=false;factory.isExpandEntityReferences=false
            val nodes=factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("extension")
            (0 until nodes.length).mapNotNull { index ->
                val attributes=nodes.item(index).attributes
                attributes?.getNamedItem("name")?.nodeValue?.takeIf(String::isNotBlank) ?: home?.let { root ->
                    val raw=attributes?.getNamedItem("dir")?.nodeValue ?: return@let null
                    val resolved=raw.replace("\${HYBRIS_BIN_DIR}",root.resolve("bin").toString()).replace("\${HYBRIS_CONFIG_DIR}",root.resolve("config").toString())
                    path(resolved)?.takeIf(Files::isDirectory)?.fileName?.toString()
                }
            }.toSet()
        } catch (_: Exception) { emptySet() }
    }
    private fun extensionNames(home: Path, requested: Set<String>, cancelled: () -> Unit): Set<String> {
        val names=xmlNames(home.resolve("config/localextensions.xml"),home).toMutableSet();names.add("platform")
        if(names.containsAll(requested))return names
        // Metadata is at depth four in grouped/deprecated module layouts. Keep one
        // extra level, while bounding misspelled-name scans away from deep build/data trees.
        for(dir in listOf("ext","modules","custom")) {
            val root=home.resolve("bin/$dir");if(!Files.isDirectory(root))continue
            try {
                Files.walkFileTree(root,setOf(FileVisitOption.FOLLOW_LINKS),5,object:SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir:Path,attrs:BasicFileAttributes):FileVisitResult {
                        cancelled();val metadata=dir.resolve("extensioninfo.xml")
                        if(!Files.isRegularFile(metadata))return FileVisitResult.CONTINUE
                        names.addAll(xmlNames(metadata))
                        // Once an extension is identified, its sources, classes, web assets
                        // and libraries cannot contain another extension's metadata.
                        return if(names.containsAll(requested))FileVisitResult.TERMINATE else FileVisitResult.SKIP_SUBTREE
                    }
                    override fun visitFile(file:Path,attrs:BasicFileAttributes):FileVisitResult {
                        cancelled()
                        return FileVisitResult.CONTINUE
                    }
                })
            } catch (_: IOException) { /* Missing/unreadable discovery cannot prove an unknown name. */ }
              catch (_: UncheckedIOException) { /* A symlink loop yields a partial index, as in the agent. */ }
            if(names.containsAll(requested))break
        }
        return names
    }
}
