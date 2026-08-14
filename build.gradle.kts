import org.gradle.kotlin.dsl.support.serviceOf

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
        bundledPlugin("com.intellij.java")

        // Real Application and Project fixtures. The bytecode call-graph test
        // answers "can startup reach a dialog"; this answers "does startup
        // actually work", which needs the plugin loaded into a live IDE.
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    // Plugin-side unit tests. The pieces worth testing here are the ones
    // that reason about a SAP Commerce installation's layout on disk and
    // need no IDE fixture to exercise.
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // StartupMustNotShowDialogTest reads our own compiled classes to walk the
    // call graph out of the startup activity. Same version the agent uses.
    testImplementation("org.ow2.asm:asm:9.8")
    testImplementation("org.ow2.asm:asm-tree:9.8")

    // BasePlatformTestCase descends from junit.framework.TestCase, so the
    // IDE fixtures need JUnit 3/4 on the classpath and the vintage engine to
    // run under the JUnit Platform the rest of the suite uses.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}

// Signing material, resolved once so both the intellijPlatform block and
// the guard task below agree on what "configured" means.
val signingChainFile = file("certificate/chain.crt")
val signingKeyFile = file("certificate/private.pem")

/**
 * Without a certificate the platform's signPlugin task is skipped by an
 * onlyIf spec and the build still reports success, so `signPlugin
 * publishPlugin` can upload an UNSIGNED plugin while looking like it
 * worked. Fail loudly instead: signing is only ever requested on purpose.
 */
val requireSigningMaterial by tasks.registering {
    doFirst {
        val missing = buildList {
            if (!signingChainFile.exists()) add("certificate/chain.crt")
            if (!signingKeyFile.exists()) add("certificate/private.pem")
            if (System.getenv("RECLAZZ_SIGNING_PASSWORD").isNullOrBlank()) {
                add("RECLAZZ_SIGNING_PASSWORD (environment variable)")
            }
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Cannot sign the plugin, missing: ${missing.joinToString(", ")}.\n" +
                "Generate the certificate once with " +
                "./scripts/generate-signing-certificate.sh, then export the " +
                "passphrase as RECLAZZ_SIGNING_PASSWORD.\n" +
                "If you did export it: a running Gradle daemon keeps the " +
                "environment it started with and will not see it. Re-run " +
                "with --no-daemon."
            )
        }
    }
}

tasks.matching { it.name == "signPlugin" || it.name == "publishPlugin" }.configureEach {
    dependsOn(requireSigningMaterial)
}

// The platform plugin ships verifyPluginSignature without a dependency on the
// task that produces what it verifies, so `signPlugin verifyPluginSignature`
// fails validation, and running it alone would check whatever signed zip
// happens to be lying around from an earlier build.
tasks.matching { it.name == "verifyPluginSignature" }.configureEach {
    dependsOn("signPlugin")
}

/**
 * Marketplace review rejects plugins that call IntelliJ internal API. The
 * Plugin Verifier is supposed to be the local gate for that, but it does not
 * flag every case the reviewers do: 1.0.0 was rejected for two calls to
 * PluginManagerCore.getPlugin that verifyPlugin reported as Compatible against
 * 2024.1, 2025.1 and 2025.2 alike.
 *
 * So this is a plain denylist over the sources. It is not a substitute for the
 * verifier and it only knows what we have been taught so far, but it turns a
 * rejection that costs a review round into a build failure. Add an entry every
 * time review finds one.
 */
val checkInternalApiUsage by tasks.registering {
    val sources = fileTree("src/main") { include("**/*.kt", "**/*.java") }
    inputs.files(sources)

    // Symbol to the public replacement to use instead.
    val denied = mapOf(
        "PluginManagerCore" to "PluginManager.getInstance().findEnabledPlugin(id)",
    )

    doLast {
        val hits = mutableListOf<String>()
        sources.forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                // Skip comments so the explanation of why we avoid a symbol
                // does not itself trip the check.
                val code = line.substringBefore("//").trim()
                denied.forEach { (symbol, replacement) ->
                    if (code.contains(symbol)) {
                        hits += "${file.relativeTo(projectDir)}:${i + 1}  $symbol  ->  use $replacement"
                    }
                }
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException(
                "Internal IntelliJ API referenced; the Marketplace rejects this:\n" +
                        hits.joinToString("\n") { "  $it" }
            )
        }
    }
}

/**
 * buildSearchableOptions starts a full IDE to index this plugin's settings so
 * they can be found from the Settings search box. It asks for a 2GB heap by
 * default, which is the platform's number rather than anything this task
 * needs: it indexes one configurable.
 *
 * On a machine already running something large, a SAP Commerce server say,
 * that reservation is what fails, and it fails as a bare "finished with
 * non-zero exit value 3" with no hint that memory was the problem. It cost
 * two release attempts before the cause was found.
 *
 * Capped rather than disabled: the index really does contain our settings
 * page, so turning the task off would quietly make those settings
 * unsearchable.
 */
tasks.matching { it.name == "buildSearchableOptions" }.configureEach {
    if (this !is JavaExec) return@configureEach
    val task = this

    maxHeapSize = "768m"

    // The crash happens before the IDE has done anything, so a second attempt
    // costs twenty seconds and almost always works. Swallow the exit code here
    // and decide below on the evidence that matters: whether the index exists.
    isIgnoreExitValue = true

    doLast {
        val out = task.outputs.files.singleFile

        fun produced() = out.walkTopDown().any { it.isFile }

        if (produced()) return@doLast

        val exec = project.serviceOf<org.gradle.process.ExecOperations>()
        var lastExit = -1
        for (attempt in 2..4) {
            logger.lifecycle("buildSearchableOptions produced nothing; attempt $attempt of 4")
            val result = exec.javaexec {
                classpath = task.classpath
                mainClass.set(task.mainClass)
                // The real arguments live in the providers, not in jvmArgs.
                jvmArgs = task.jvmArgumentProviders.flatMap { it.asArguments() }
                args = task.args ?: emptyList()
                task.args?.let { args = it }
                maxHeapSize = "768m"
                isIgnoreExitValue = true
            }
            lastExit = result.exitValue
            if (produced()) {
                logger.lifecycle("buildSearchableOptions succeeded on attempt $attempt")
                return@doLast
            }
        }

        throw GradleException(
            "buildSearchableOptions produced no index after 4 attempts (last exit $lastExit). " +
            "This normally means the sandbox IDE failed to start; see " +
            "build/idea-sandbox/*/log/idea.log. docs/publishing.md has the details."
        )
    }
}

/**
 * A release carries its notes in two places that have to agree: CHANGELOG.md
 * for people reading the repository, and the change-notes block in plugin.xml
 * for the "What's new" panel the IDE shows when it offers an update. Nothing
 * connected them, so they drifted: 1.0.9 went to the Marketplace with 1.0.8 at
 * the top of its notes, and everyone who updated saw nothing about what they
 * were getting.
 *
 * Generating one from the other was the alternative. It was not taken because
 * the two are deliberately different, the changelog explains and the notes
 * summarise, and a generator would have to flatten that. Checking they both
 * mention the version being built keeps them independent and still catches the
 * omission before it can ship.
 */
val checkReleaseNotes by tasks.registering {
    group = "verification"
    description = "Fails when the version being built has no entry in the changelog or in plugin.xml change-notes"

    val version = providers.gradleProperty("pluginVersion")
    val pluginXml = layout.projectDirectory.file("src/main/resources/META-INF/plugin.xml")
    val changelog = layout.projectDirectory.file("CHANGELOG.md")
    inputs.property("version", version)
    inputs.files(pluginXml, changelog)
    outputs.upToDateWhen { false }

    doLast {
        val v = version.get()
        val missing = mutableListOf<String>()

        val notes = pluginXml.asFile.readText()
        val block = Regex("""<change-notes>.*?</change-notes>""", RegexOption.DOT_MATCHES_ALL)
            .find(notes)?.value
            ?: throw GradleException("plugin.xml has no <change-notes> block at all")
        if (!block.contains("<h3>$v</h3>")) {
            missing += "plugin.xml <change-notes> has no <h3>$v</h3> section. " +
                    "This is the text the IDE shows when it offers the update."
        }

        if (!changelog.asFile.readText().contains("## [$v]")) {
            missing += "CHANGELOG.md has no '## [$v]' section."
        }

        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release notes for $v are incomplete:\n  - " + missing.joinToString("\n  - ") +
                "\n\nWrite them before publishing; a release nobody can read the notes for " +
                "is how 1.0.9 shipped."
            )
        }
    }
}

tasks.named("check") { dependsOn(checkInternalApiUsage) }
tasks.named("check") { dependsOn(checkReleaseNotes) }
tasks.matching { it.name == "buildPlugin" }.configureEach { dependsOn(checkInternalApiUsage) }
tasks.matching { it.name == "publishPlugin" || it.name == "signPlugin" }
    .configureEach { dependsOn(checkReleaseNotes) }

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = "233"
            untilBuild = "261.*"
        }
    }

    // Marketplace runs the Plugin Verifier on every submission. Running it
    // locally first turns "rejected after upload" into a build failure.
    // The range mirrors the sinceBuild/untilBuild declared above.
    pluginVerification {
        // The default failure level is COMPATIBILITY_PROBLEMS + INVALID_PLUGIN,
        // which is narrower than what the Marketplace rejects for, so the
        // levels the reviewers act on are listed explicitly.
        //
        // Be aware of what this does NOT buy. 1.0.0 was rejected for two calls
        // to PluginManagerCore.getPlugin, and this task still reports
        // "Compatible" when that call is present, on 2024.1, 2025.1 and
        // 2025.2 alike. Whatever the Marketplace uses to flag it, this
        // verifier does not reproduce it. Re-adding INTERNAL_API_USAGES was
        // checked by reintroducing the call: the build stayed green. The
        // denylist in checkInternalApiUsage is what actually catches it.
        //
        // DEPRECATED_API_USAGES is deliberately absent: FileSaverDescriptor's
        // deprecated constructor is called on purpose because the replacement
        // does not exist at 2023.3, our declared sinceBuild, and the
        // constructor is not even deprecated there or at 2024.1.
        failureLevel = listOf(
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
        )

        ides {
            // Pinned rather than recommended(): the resolver in this Gradle
            // plugin version reaches for builds that are not downloadable.
            // 2025.3 is not resolvable here either, by version or by build
            // number, so 2025.2 is the newest this toolchain can verify while
            // untilBuild claims 261.
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2023.3")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2024.1")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.2")
        }
    }

    // Signing material lives outside the repository (see .gitignore) and
    // only the maintainer has it. Wiring it unconditionally made every
    // clone carry a build that references files it cannot have; contributors
    // hit it as a confusing failure rather than "you don't need this".
    // Generate yours with scripts/generate-signing-certificate.sh.
    if (signingChainFile.exists() && signingKeyFile.exists()) {
        signing {
            certificateChainFile.set(signingChainFile)
            privateKeyFile.set(signingKeyFile)
            password.set(providers.environmentVariable("RECLAZZ_SIGNING_PASSWORD"))
        }
    }

    publishing {
        token.set(providers.environmentVariable("RECLAZZ_PUBLISH_TOKEN"))
    }
}

// Bundle the agent shadow JAR as a separate file in the plugin distribution
evaluationDependsOn(":agent")

val copyAgentJar by tasks.registering(Copy::class) {
    val agentShadowJar = project(":agent").tasks.named("shadowJar")
    dependsOn(agentShadowJar)
    from(agentShadowJar)
    rename { "reclazz-agent.jar" }
    into(layout.buildDirectory.dir("idea-sandbox/plugins/reclazz/agent"))
}

tasks.named("prepareSandbox") {
    dependsOn(copyAgentJar)
}

tasks.named("buildPlugin") {
    dependsOn(copyAgentJar)
}

// Also include the agent JAR in the final distribution ZIP
tasks.named<Zip>("buildPlugin") {
    val agentShadowJar = project(":agent").tasks.named("shadowJar")
    dependsOn(agentShadowJar)
    from(agentShadowJar) {
        rename { "reclazz-agent.jar" }
        into("agent")
    }
    // Remove duplicates from nested paths
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
