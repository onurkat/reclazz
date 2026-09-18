plugins {
    id("java-library")
    `maven-publish`
    signing
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
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
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

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "reclazz-spring-boot-starter"
            from(components["java"])
            pom {
                name.set("Reclazz Spring Boot starter")
                description.set(
                    "Reports at startup whether the Reclazz hot-reload agent is attached, " +
                        "and exposes a reclazz actuator endpoint when the actuator is present."
                )
                url.set("https://reclazz.com")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("onurkat")
                        name.set("Onur Kat")
                        url.set("https://www.onurkat.com")
                    }
                }
                scm {
                    url.set("https://github.com/onurkat/reclazz")
                    connection.set("scm:git:https://github.com/onurkat/reclazz.git")
                    developerConnection.set("scm:git:ssh://git@github.com/onurkat/reclazz.git")
                }
            }
        }
    }
    // A local Maven layout the Central Portal accepts as an upload bundle, the
    // same flow the agent uses: publish the signed publication here, zip it, and
    // upload at central.sonatype.com.
    repositories {
        maven {
            name = "centralBundle"
            url = layout.buildDirectory.dir("central-bundle").get().asFile.toURI()
        }
    }
}

// Signing is required only when a key is configured, so the build works without
// one; the release machine that has signing.keyId signs.
signing {
    isRequired = providers.gradleProperty("signing.keyId").isPresent
    sign(publishing.publications["maven"])
}

val centralBundle by tasks.registering(Zip::class) {
    group = "publishing"
    description = "Zips the signed Maven bundle for a Central Portal upload"
    dependsOn("publishMavenPublicationToCentralBundleRepository")
    from(layout.buildDirectory.dir("central-bundle"))
    archiveFileName.set("reclazz-spring-boot-starter-${project.version}-central-bundle.zip")
    destinationDirectory.set(layout.buildDirectory)
}
