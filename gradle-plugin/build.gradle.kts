plugins {
    id("java-gradle-plugin")
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
    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("reclazz") {
            id = "com.onurkat.reclazz"
            implementationClass = "com.onurkat.reclazz.gradle.ReclazzPlugin"
            displayName = "Reclazz hot-reload Gradle plugin"
            description = "Wires the Reclazz hot-reload agent into bootRun and test, " +
                "so a Spring Boot or SAP Commerce project reloads in place with no manual -javaagent flag."
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
