plugins {
    id("java")
    id("jacoco")
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
    // Byte Buddy is deliberately absent. It was here to emit two probe classes
    // and, in one file, only for the copy of ASM bundled inside it; both are
    // ASM's job and ASM is already here. The agent is loaded into the JVM that
    // runs somebody else's application, so 25 MB of classes to save sixty
    // lines was the wrong trade. It stays as a test dependency because the
    // tests use its agent attach helper, which never ships.
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-commons:9.10.1")
    implementation("org.ow2.asm:asm-tree:9.10.1")
    implementation("org.ow2.asm:asm-util:9.10.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("net.bytebuddy:byte-buddy:1.18.5") {
        exclude(group = "org.slf4j")
    }
    testImplementation("net.bytebuddy:byte-buddy-agent:1.18.5") {
        exclude(group = "org.slf4j")
    }
    // Test only, and deliberately not an implementation dependency: the agent
    // recognises a mapped class by the annotation's descriptor, never by
    // loading it, so that it works against jakarta and javax alike and against
    // an application whose persistence API the agent has never seen.
    testImplementation("jakarta.persistence:jakarta.persistence-api:3.1.0")
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
    // Declared as an input, not merely depended on. A test that reads the built
    // jar and is not told the jar is an input passes on the last run's result:
    // the licence guard did exactly that, staying green against a jar whose
    // NOTICE had been changed to claim a library that is not in it.
    inputs.file(tasks.named<org.gradle.jvm.tasks.Jar>("shadowJar").get().archiveFile)
            .withPropertyName("agentJar")
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

// What the tests actually reach, in this process.
//
// Written down because "I think that is covered" was the only answer available
// before, and for an agent that runs inside somebody else's JVM the untested
// paths are the ones that surface as a stack trace in their log rather than as
// a red line here.
//
// Read it knowing what it cannot see. Fourteen of these tests launch a child
// JVM with the real agent on the command line, because that is the only honest
// way to check what an agent does at startup, under load, or across a clean
// build. None of that work is instrumented, so a class exercised only there
// reads as nought per cent: SynchronizedBodyAdapter and ExcludedClasses both
// do, and both are tested. The number is a floor under this process, not a
// measure of the suite.
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// A floor, not a target. It sits a little under where the suite actually is
// (55% of instructions, 43% of branches when this was written) so ordinary
// refactoring has room, and a change that quietly stops running a few hundred
// instructions does not pass as green. Most of what is uncovered here is
// uncovered on purpose: the Spring and hybris reloaders need a live container
// and are exercised by the integration suite, which does not report into this.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                minimum = "0.52".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.41".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
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
    // shadowJar clears its classifier so the fat jar is the canonical
    // agent-<version>.jar. Without a classifier here both tasks would write
    // that same path and overwrite each other: the plugin would ship whichever
    // ran last, and the thin one is missing the shaded ASM the
    // agent loads at premain. That fails at runtime, not at build time.
    archiveClassifier.set("thin")

    // And deliberately not an agent manifest. It carried one, which made this
    // jar look exactly like the thing to attach while being the one thing that
    // cannot work: the JVM read Premain-Class, ran premain, the agent went
    // looking for ASM, and the process died in native code after printing the
    // Reclazz banner, which reads as Reclazz having killed the JVM.
    //
    //   [Reclazz] Agent loaded via -javaagent (premain)
    //   FATAL ERROR in native method: processing of -javaagent failed
    //
    // Without the attributes the JVM refuses it in one line naming the reason,
    // and the jar that does work is the one the agent manifest is on.
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

// The jar a developer attaches is the point of this module, so the ordinary
// build produces it. It did not: `./gradlew clean build`, which is what the
// README says to run, left only the thin jar in build/libs, and the first
// thing anyone does with a jar called agent-<version> is attach it.
tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}

tasks.shadowJar {
    dependsOn(bootstrapJar)
    archiveClassifier.set("")

    // The licence terms travel with the thing they license, and this jar is a
    // thing on its own: the README tells people to build it and attach it with
    // -javaagent, and plenty of them will never see the plugin zip that carries
    // these files. It redistributes 150 relocated ASM classes, and ASM's BSD
    // licence asks for its copyright notice to come along. It did not.
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(rootProject.file("NOTICE")) { into("META-INF") }
    from(rootProject.file("THIRD-PARTY.md")) { into("META-INF") }
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
