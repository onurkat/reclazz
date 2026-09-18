import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

// The Reclazz agent, resolved from Maven Central. It is not a normal dependency
// of the app; it is a -javaagent attached to the dev run and the test JVM.
val reclazzAgent by configurations.creating

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")

    reclazzAgent("com.onurkat.reclazz:reclazz-agent:1.3.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// Build the -javaagent flag once, resolving the agent jar lazily so an unrelated
// task never triggers the download.
fun reclazzFlag(): String {
    val jar = reclazzAgent.singleFile
    val classes = layout.buildDirectory.dir("classes/java/main").get().asFile
    return "-javaagent:$jar=platform=spring,watchDirs=$classes"
}

// `./gradlew bootRun` starts the app with hot-reload active. Edit a class,
// recompile, and the running app picks it up with no restart.
tasks.named<BootRun>("bootRun") {
    doFirst {
        jvmArgs(reclazzFlag())
    }
}

// Tests run against reloaded classes, and the smoke test proves the agent is on.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    doFirst {
        jvmArgs(reclazzFlag())
    }
}
