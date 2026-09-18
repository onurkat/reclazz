plugins {
    id("java-library")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

base {
    archivesName.set("reclazz-spring-boot-starter")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

val bootVersion = "3.3.5"

dependencies {
    // The starter carries the auto-configuration; the app brings Spring Boot.
    api("org.springframework.boot:spring-boot-autoconfigure:$bootVersion")

    // slf4j is present in every Spring Boot app at runtime; compile against it
    // without forcing a version on the consumer.
    compileOnly("org.slf4j:slf4j-api:2.0.16")

    // The actuator endpoint is optional: compile against it, but do not force it
    // onto an app that does not use the actuator.
    compileOnly("org.springframework.boot:spring-boot-actuator:$bootVersion")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:$bootVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test:$bootVersion")
    testImplementation("org.springframework.boot:spring-boot-actuator:$bootVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
