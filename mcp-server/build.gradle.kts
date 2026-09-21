import java.security.MessageDigest

plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.5"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    dependsOn(":agent:shadowJar")
    val agentJar = project(":agent").tasks.named<org.gradle.jvm.tasks.Jar>("shadowJar").flatMap { it.archiveFile }
    inputs.file(agentJar).withPropertyName("agentJar")
    systemProperty("reclazz.agent.jar", agentJar.get().asFile.absolutePath)
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("reclazz-mcp")
    manifest {
        attributes(
            "Main-Class" to "com.onurkat.reclazz.mcp.McpMain",
            "Implementation-Version" to project.version
        )
    }
}

tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}

// Local release assets only; publication belongs to the tag workflow.
val releaseJar = layout.buildDirectory.file("distributions/mcp/reclazz-mcp-${project.version}.jar")
val releaseChecksum = layout.buildDirectory.file("distributions/mcp/reclazz-mcp-${project.version}.jar.sha256")
val mcpRelease by tasks.registering {
    group = "distribution"
    description = "Stage the standalone MCP jar and its portable SHA-256 sidecar (no upload)."
    val fatJar = tasks.named<org.gradle.jvm.tasks.Jar>("shadowJar").flatMap { it.archiveFile }
    inputs.file(fatJar).withPropertyName("fatJar")
    outputs.files(releaseJar, releaseChecksum)
    doLast {
        val jar = releaseJar.get().asFile
        jar.parentFile.mkdirs()
        fatJar.get().asFile.copyTo(jar, overwrite = true)
        val digest = MessageDigest.getInstance("SHA-256").digest(jar.readBytes())
        val hex = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        releaseChecksum.get().asFile.writeText("$hex  ${jar.name}\n", Charsets.UTF_8)
    }
}

tasks.test {
    dependsOn(mcpRelease)
    inputs.files(releaseJar, releaseChecksum).withPropertyName("mcpReleaseAssets")
    systemProperty("reclazz.mcp.releaseJar", releaseJar.get().asFile.absolutePath)
    systemProperty("reclazz.mcp.releaseChecksum", releaseChecksum.get().asFile.absolutePath)
    systemProperty("reclazz.mcp.releaseVersion", project.version.toString())
}
