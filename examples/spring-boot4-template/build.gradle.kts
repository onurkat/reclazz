import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("com.onurkat.reclazz") version "1.7.2" apply false
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

repositories {
    mavenLocal()
    mavenCentral()
}

// The starter reports attachment; it does not supply a JVM agent.
val reclazzMode = providers.gradleProperty("reclazzMode").orElse("manual").get()
require(reclazzMode in setOf("manual", "plugin", "starter", "absent")) {
    "reclazzMode must be manual, plugin, starter or absent"
}
val manualReclazzAgent by configurations.creating
if (reclazzMode == "plugin") apply(plugin = "com.onurkat.reclazz")

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    if (reclazzMode == "starter" || reclazzMode == "absent") {
        developmentOnly("com.onurkat.reclazz:reclazz-spring-boot-starter:1.7.2")
    }
    if (reclazzMode == "manual" || reclazzMode == "starter") {
        manualReclazzAgent("com.onurkat.reclazz:reclazz-agent:1.7.2")
    }
}

tasks.named<BootRun>("bootRun") {
    if (reclazzMode == "manual" || reclazzMode == "starter") {
        doFirst {
            val classes = layout.buildDirectory.dir("classes/java/main").get().asFile
            val resources = layout.buildDirectory.dir("resources/main").get().asFile
            jvmArgs("-javaagent:${manualReclazzAgent.singleFile}=platform=spring,watchDirs=$classes;$resources")
        }
    }
}
