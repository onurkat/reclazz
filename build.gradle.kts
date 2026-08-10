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
                "passphrase as RECLAZZ_SIGNING_PASSWORD."
            )
        }
    }
}

tasks.matching { it.name == "signPlugin" || it.name == "publishPlugin" }.configureEach {
    dependsOn(requireSigningMaterial)
}

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
        ides {
            // Pinned rather than recommended(): the resolver in this Gradle
            // plugin version reaches for builds that are not downloadable.
            // These two bracket the declared compatibility range.
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2024.1")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
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
