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
