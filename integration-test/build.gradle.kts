plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

application {
    mainClass.set("com.onurkat.reclazz.inttest.ReclazzTestRunnerKt")
}

// Uses the JDK HTTP server and Kotlin check(), without another test dependency.
val sapProofTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Exercise SAP proof acceptance against real local HTTP responses"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.onurkat.reclazz.inttest.tests.SapProofContractTestKt")
}
tasks.check { dependsOn(sapProofTest) }

// Independent portable runner contract: fake event stream and real loopback HTTP.
val harnessContractTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Exercise live runner ordering, polling and restoration without SAP"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.onurkat.reclazz.inttest.HarnessContractTestKt")
}
tasks.check { dependsOn(harnessContractTest) }
