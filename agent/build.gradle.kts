plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.onurkat.reclazz"
version = rootProject.version

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.bytebuddy:byte-buddy:1.18.5") {
        exclude(group = "org.slf4j")
    }
    implementation("net.bytebuddy:byte-buddy-agent:1.18.5") {
        exclude(group = "org.slf4j")
    }
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-commons:9.8")
    implementation("org.ow2.asm:asm-tree:9.8")
    implementation("org.ow2.asm:asm-util:9.8")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Spring for XML reloader tests only — production code stays
    // reflection-only and has no Spring compile dependency.
    testImplementation("org.springframework:spring-beans:5.3.39")
    testImplementation("org.springframework:spring-context:5.3.39")
}

tasks.test {
    useJUnitPlatform()
    // The generic-path smoke test launches a child JVM with -javaagent, so
    // it needs the shaded agent jar. Built here rather than assumed so a
    // plain `:agent:test` still exercises it (the test skips if absent).
    dependsOn(tasks.named("shadowJar"))
    systemProperty("reclazz.agent.jar",
            tasks.named<org.gradle.jvm.tasks.Jar>("shadowJar").get().archiveFile.get().asFile.absolutePath)
    // Allow tests to self-attach the JVM via ByteBuddyAgent.install() so the
    // hot-swap reload path (Instrumentation.redefineClasses) can be exercised
    // end-to-end inside the test process.
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

// Build a standalone bootstrap JAR from compiled bootstrap classes only (no shading, pure JDK)
val bootstrapJar by tasks.registering(Jar::class) {
    dependsOn(tasks.compileJava)
    from(tasks.compileJava.get().destinationDirectory) {
        include("com/onurkat/reclazz/bootstrap/**")
    }
    archiveFileName.set("reclazz-bootstrap.jar")
    destinationDirectory.set(layout.buildDirectory.dir("bootstrap"))
}

// Copy bootstrap JAR into resources so it gets picked up by both jar and shadowJar
// Renamed to .bin to avoid ShadowJar's default JAR file exclusion
val copyBootstrapJar by tasks.registering(Copy::class) {
    dependsOn(bootstrapJar)
    from(layout.buildDirectory.file("bootstrap/reclazz-bootstrap.jar"))
    into(layout.buildDirectory.dir("resources/main/META-INF"))
    rename("reclazz-bootstrap.jar", "reclazz-bootstrap.bin")
}

tasks.processResources {
    dependsOn(copyBootstrapJar)
}

tasks.classes {
    dependsOn(copyBootstrapJar)
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "com.onurkat.reclazz.agent.ReclazzAgent",
            "Agent-Class" to "com.onurkat.reclazz.agent.ReclazzAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "Can-Set-Native-Method-Prefix" to "true",
            "Implementation-Version" to project.version
        )
    }
}

tasks.shadowJar {
    dependsOn(bootstrapJar)
    archiveClassifier.set("")
    relocate("net.bytebuddy", "com.onurkat.reclazz.shaded.bytebuddy")
    relocate("org.objectweb.asm", "com.onurkat.reclazz.shaded.asm")
    exclude("org/slf4j/**")
    exclude("META-INF/services/org.slf4j.*")
    // Exclude bootstrap classes from shadow JAR — they are loaded on the bootstrap
    // classloader via appendToBootstrapClassLoaderSearch. If they existed in both
    // the shadow JAR (agent CL) and bootstrap JAR (bootstrap CL), the JVM would
    // see two different Class objects, making DispatchTable retargeting a no-op.
    exclude("com/onurkat/reclazz/bootstrap/**")
    mergeServiceFiles()

    manifest {
        attributes(
            "Premain-Class" to "com.onurkat.reclazz.agent.ReclazzAgent",
            "Agent-Class" to "com.onurkat.reclazz.agent.ReclazzAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "Can-Set-Native-Method-Prefix" to "true",
            "Implementation-Version" to project.version
        )
    }
}
