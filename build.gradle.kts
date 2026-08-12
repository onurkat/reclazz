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

tasks.named("check") { dependsOn(checkInternalApiUsage) }
tasks.matching { it.name == "buildPlugin" }.configureEach { dependsOn(checkInternalApiUsage) }

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = "241"
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
        // "Compatible" when that call is present, on every IDE listed below.
        // Whatever the Marketplace uses to flag it, this verifier with these
        // IDEs does not reproduce it. Re-adding INTERNAL_API_USAGES here was
        // checked by reintroducing the call: the build stayed green. The
        // denylist in checkInternalApiUsage is what actually catches it.
        //
        // DEPRECATED_API_USAGES is deliberately absent: FileSaverDescriptor's
        // deprecated constructor is called on purpose because the replacement
        // does not exist in 2024.1, our declared sinceBuild.
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
