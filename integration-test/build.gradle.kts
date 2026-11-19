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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // Test runner only; same parsed-JSON version already used by the agent tests.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.23.0")
}

application {
    mainClass.set("com.onurkat.reclazz.inttest.ReclazzTestRunnerKt")
}

// Uses the JDK HTTP server and Kotlin check(), without another test dependency.
val sapProofTest = tasks.register("sapProofTest", JavaExec::class) {
    group = "verification"
    description = "Exercise SAP proof acceptance against real local HTTP responses"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.onurkat.reclazz.inttest.tests.SapProofContractTestKt")
}
tasks.check { dependsOn(sapProofTest) }

// Independent portable runner contract: fake event stream and real loopback HTTP.
val harnessContractTest = tasks.register("harnessContractTest", JavaExec::class) {
    group = "verification"
    description = "Exercise live runner ordering, polling and restoration without SAP"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.onurkat.reclazz.inttest.HarnessContractTestKt")
}
tasks.check { dependsOn(harnessContractTest) }

// Narrow C61 runner contract; broad harness remains an independent audit gate.
val storedScriptContractTest = tasks.register("storedScriptContractTest", JavaExec::class) {
    group = "verification"
    description = "Exercise only stored-script live runner contracts"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.onurkat.reclazz.inttest.tests.StoredScriptHarnessContractTestKt")
}

// Focused C63 runner contract, independently of the full audit harness.
val velocityTemplateContractTest = tasks.register("velocityTemplateContractTest", JavaExec::class) {
    group = "verification"
    description = "Exercise only native Velocity template runner contracts"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.onurkat.reclazz.inttest.tests.VelocityTemplateHarnessContractTestKt")
}

// Narrow C39 proof checks; full harness belongs to audit.
val cmsUidContractTest = tasks.register("cmsUidContractTest", JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.onurkat.reclazz.inttest.tests.CmsUidContractTestKt")
}

// Narrow C40 page/slot proof oracle; full harness remains an audit gate.
val cmsPageSlotContractTest = tasks.register("cmsPageSlotContractTest", JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.onurkat.reclazz.inttest.tests.CmsPageSlotContractTestKt")
}

// Narrow native JSP HTML oracle; broad harness runs at independent audit.
val cmsJspResourceContractTest = tasks.register("cmsJspResourceContractTest", JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.onurkat.reclazz.inttest.tests.CmsJspResourceContractTestKt")
}

// Narrow C17 native PK/context oracle and directly affected default membership.
val searchRestrictionContractTest = tasks.register("searchRestrictionContractTest", JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.onurkat.reclazz.inttest.tests.SearchRestrictionContractTestKt")
}

// Narrow C19 native facade response and identity oracle.
val facadeChainContractTest = tasks.register("facadeChainContractTest", JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.onurkat.reclazz.inttest.tests.FacadeChainContractTestKt")
}

// Narrow C22 new-controller enrollment evidence; broad harness belongs to audit.
val newOccControllerContractTest = tasks.register("newOccControllerContractTest", JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.onurkat.reclazz.inttest.tests.NewOccControllerContractTestKt")
}

// Narrow native OCC error oracle; full harness remains an independent audit gate.
val occErrorResponseContractTest = tasks.register("occErrorResponseContractTest", JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.onurkat.reclazz.inttest.tests.OccErrorResponseContractTestKt")
}

// Narrow C28 watched macro UPDATE/guard recovery contract.
val impexUpdateMacrosContractTest = tasks.register("impexUpdateMacrosContractTest", JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.onurkat.reclazz.inttest.tests.ImpexUpdateMacrosContractTestKt")
}

val newItemTypeContractTest = tasks.register("newItemTypeContractTest", JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.onurkat.reclazz.inttest.tests.NewItemTypeContractTestKt")
}

val newRelationContractTest = tasks.register("newRelationContractTest", JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.onurkat.reclazz.inttest.tests.NewRelationContractTestKt")
}

// Narrow watcher-ownership contracts; the aggregate check already calls these functions.
val impexHarnessContractTest = tasks.register("impexHarnessContractTest", JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.onurkat.reclazz.inttest.tests.ImpexTranslatorHarnessContractTestKt")
}
val removeHarnessContractTest = tasks.register("removeHarnessContractTest", JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.onurkat.reclazz.inttest.tests.RemoveHarnessContractTestKt")
}
