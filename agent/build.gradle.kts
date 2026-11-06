import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.io.ByteArrayOutputStream
import org.gradle.kotlin.dsl.support.serviceOf

/**
 * The local tag the Rabbit harness runs, offline. It points at the image for
 * the daemon's own platform, not at the pinned multi-platform index.
 *
 * Under Docker's containerd image store a reference to an index intermittently
 * stops resolving even though the content is there: `docker image ls` lists the
 * tag and `docker image inspect` on it answers "No such image". Tagging the
 * index, which this did, gave a tag with exactly that flake, and the whole
 * messaging suite failed on it (measured twice in one evening). A
 * platform-specific inspect of the same index resolved every time, so the tag
 * is moved onto that child image.
 */
fun ensurePinnedRabbitImage(exec: org.gradle.process.ExecOperations, logger: org.gradle.api.logging.Logger, task: String) {
    val digest = "rabbitmq@sha256:3486d98205df3d6395ed70e7924baa13b561cbac54116c0ddae5b0b7382bbabd"
    val localTag = "reclazz-rabbit-pinned:4.3.5-alpine"
    fun docker(vararg args: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val code = exec.exec {
            commandLine(listOf("docker") + args)
            isIgnoreExitValue = true
            standardOutput = out
            errorOutput = ByteArrayOutputStream()
        }.exitValue
        return code to out.toString().trim()
    }
    val platform = docker("version", "--format", "{{.Server.Os}}/{{.Server.Arch}}").second
    // Found by listing, then inspected by ID. Under the containerd image store,
    // inspect by name answered "No such image" for a tag that list and run found.
    fun child(ref: String): String? {
        val id = docker("image", "ls", "--no-trunc", "--format", "{{.ID}}", ref).second
            .lineSequence().firstOrNull { it.startsWith("sha256:") } ?: return null
        return docker("image", "inspect", "--platform", platform, "--format", "{{.Id}}", id)
            .takeIf { it.first == 0 && it.second.startsWith("sha256:") }?.second ?: id
    }
    var image = child(localTag)
    if (image == null) {
        logger.lifecycle("$task: local image $localTag missing; pulling $digest for $platform")
        exec.exec { commandLine("docker", "pull", "--platform", platform, digest) }
        exec.exec { commandLine("docker", "tag", digest, localTag) }
        image = child(localTag) ?: throw GradleException("$task: $digest has no $platform image after the pull")
    }
    // Which reference a daemon can tag differs by image store: on the classic
    // store of the CI runners the listed ID answered "No such image" while the
    // pinned digest tagged fine, so the digest is the fallback.
    if (docker("tag", image, localTag).first != 0 && docker("tag", digest, localTag).first != 0) {
        throw GradleException("$task: could not tag $localTag from $image or $digest")
    }
}

plugins {
    id("java")
    id("jacoco")
    id("com.gradleup.shadow") version "8.3.5"
    `maven-publish`
    signing
}

group = "com.onurkat.reclazz"
version = rootProject.version

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

// Sources are UTF-8 (the status banner uses box-drawing characters). Pin the
// compiler encoding so a Windows runner, which defaults to windows-1252, does not
// fail with "unmappable character".
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// The javadoc jar exists to satisfy the Central Portal, not to be exhaustive,
// so missing tags on internal classes must not fail the release build. Javadoc
// reads the sources with its own encoding, so it needs UTF-8 too or a Windows
// runner fails on the same box-drawing characters that compileJava now handles.
tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("Xdoclint:none", "-quiet")
    }
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
    testImplementation("net.bytebuddy:byte-buddy:1.18.14") {
        exclude(group = "org.slf4j")
    }
    testImplementation("net.bytebuddy:byte-buddy-agent:1.18.14") {
        exclude(group = "org.slf4j")
    }
    // Test only, and deliberately not an implementation dependency: the agent
    // recognises a mapped class by the annotation's descriptor, never by
    // loading it, so that it works against jakarta and javax alike and against
    // an application whose persistence API the agent has never seen.
    testImplementation("jakarta.persistence:jakarta.persistence-api:3.2.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Spring for XML reloader tests only, production code stays
    // reflection-only and has no Spring compile dependency.
    testImplementation("org.springframework:spring-beans:5.3.39")
    testImplementation("org.springframework:spring-context:5.3.39")
    // Real JSR-250 lifecycle regressions only; never packaged with the agent.
    testImplementation("javax.annotation:javax.annotation-api:1.3.2")
    // Real local Kafka broker regressions only; never packaged with the agent.
    testImplementation("org.springframework.kafka:spring-kafka:2.9.13")
    testImplementation("org.springframework.kafka:spring-kafka-test:2.9.13")
    testImplementation("org.springframework:spring-messaging:5.3.39")
    // Real Rabbit listener regressions only; production remains reflection-only.
    testImplementation("org.springframework.amqp:spring-rabbit:4.1.2")
    // Real commit/rollback regressions against a local in-memory database only.
    testImplementation("org.springframework:spring-jdbc:5.3.39")
    testImplementation("com.h2database:h2:2.5.250")
    // Real @Aspect proxy regressions only; no weaving and no production dependency.
    testImplementation("org.aspectj:aspectjweaver:1.9.25.1")
    // Native method authorization regressions only; never packaged in the agent.
    testImplementation("org.springframework.security:spring-security-config:5.7.11")
    // Real servlet security policy observers only; never packaged with the agent.
    testImplementation("org.springframework.security:spring-security-web:5.7.11")
    // Spring MVC, its test support and the servlet API, for driving a real
    // RequestMappingHandlerMapping under load while a controller is re-scanned.
    testImplementation("org.springframework:spring-webmvc:5.3.39")
    testImplementation("org.springframework:spring-test:5.3.39")
    testImplementation("javax.servlet:javax.servlet-api:4.0.1")
    // Real local servlet async lifecycle tests; never shipped in the agent.
    testImplementation("org.apache.tomcat.embed:tomcat-embed-core:9.0.121") {
        // javax.annotation-api above already supplies these API classes.
        exclude(group = "org.apache.tomcat", module = "tomcat-annotations-api")
    }
    // The Bean Validation API under its pre-Jakarta name, for the test that
    // a Boot 2 application's validator is found by it.
    testImplementation("javax.validation:validation-api:2.0.1.Final")
    testImplementation("org.springframework.boot:spring-boot:2.7.18")
    // Native Boot condition regressions only; never packaged with the agent.
    testImplementation("org.springframework.boot:spring-boot-autoconfigure:2.7.18")
    // Boot 2.7 YAML regression fixtures only; not shipped in the agent.
    testImplementation("org.yaml:snakeyaml:1.30")
    // Real DTO serialization regressions; never included in the agent jar.
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    testImplementation("org.hibernate.validator:hibernate-validator:6.2.5.Final")
    testImplementation("org.glassfish:jakarta.el:3.0.4")
    // Native cached-template evidence only; never packaged with the agent.
    testImplementation("org.freemarker:freemarker:2.3.34")
    testImplementation("org.thymeleaf:thymeleaf:3.1.3.RELEASE")
}

// Core compilation never sees premium output; the optional module sees core.
val premium = sourceSets.create("premium") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[premium.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[premium.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
sourceSets.test {
    compileClasspath += premium.output
    runtimeClasspath += premium.output
}

// The broker's Jackson/SLF4J versions belong to JMS tests only. Extending the
// dependency declarations resolves a separate graph; it does not change test.
val jmsTests = sourceSets.create("jmsTest") {
    compileClasspath += premium.output + sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += premium.output + sourceSets.main.get().output + sourceSets.test.get().output
}
configurations[jmsTests.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[jmsTests.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
    add(jmsTests.implementationConfigurationName, "org.springframework:spring-jms:5.3.39")
    add(jmsTests.implementationConfigurationName, "org.apache.activemq:activemq-broker:5.19.11")
}
val jmsTest by tasks.registering(Test::class) {
    description = "All JMS regressions with isolated real-broker dependencies"
}
val jmsUnitTest by tasks.registering(Test::class) {
    description = "JMS regressions without starting an agent JVM"
    exclude("com/onurkat/reclazz/e2e/**")
}
val jmsE2eTest by tasks.registering(Test::class) {
    description = "JMS regressions against a real agent JVM"
    include("com/onurkat/reclazz/e2e/**")
}
for (suite in listOf(jmsTest, jmsUnitTest, jmsE2eTest)) suite.configure {
    group = "verification"
    useJUnitPlatform()
    // --rerun applies only to the named task, not dependencies. Each outer
    // audit/inner-loop gate must also execute its isolated JMS counterpart.
    outputs.upToDateWhen { false }
    testClassesDirs = jmsTests.output.classesDirs
    classpath = jmsTests.runtimeClasspath
    dependsOn(tasks.named("shadowJar"))
    systemProperty("reclazz.agent.jar",
        tasks.named<org.gradle.jvm.tasks.Jar>("shadowJar").get().archiveFile.get().asFile.absolutePath)
    inputs.file(tasks.named<org.gradle.jvm.tasks.Jar>("shadowJar").get().archiveFile).withPropertyName("agentJar")
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
    testLogging { events("passed", "failed", "skipped") }
}

val rabbitTests = sourceSets.create("rabbitTest") {
    compileClasspath += premium.output + sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += premium.output + sourceSets.main.get().output + sourceSets.test.get().output
}
configurations[rabbitTests.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[rabbitTests.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
val rabbitTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Real RabbitMQ broker tests (requires Docker; explicit Windows CI opt-out only)"
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
    useJUnitPlatform()
    testClassesDirs = rabbitTests.output.classesDirs
    classpath = rabbitTests.runtimeClasspath
    dependsOn(tasks.named("shadowJar"))
    systemProperty("reclazz.agent.jar",
        tasks.named<org.gradle.jvm.tasks.Jar>("shadowJar").get().archiveFile.get().asFile.absolutePath)
    inputs.file(tasks.named<org.gradle.jvm.tasks.Jar>("shadowJar").get().archiveFile).withPropertyName("agentJar")
    outputs.upToDateWhen { false }
    onlyIf {
        val skip = providers.gradleProperty("reclazz.skipRabbitBroker").orNull == "true"
        if (skip) logger.lifecycle("RabbitMQ broker tests explicitly NOT RUN (-Preclazz.skipRabbitBroker=true)")
        !skip
    }
    testLogging { events("passed", "failed", "skipped") }
    doFirst {
        ensurePinnedRabbitImage(project.serviceOf<org.gradle.process.ExecOperations>(), logger, "rabbitTest")
    }
}

// Hibernate ORM is not part of SAP Commerce; the L2 invalidator is optional
// support and gets its only executable proof here, on an isolated graph so
// the ordinary test classpath keeps its versions. Owner-approved, test-only.
val hibernateTests = sourceSets.create("hibernateTest") {
    compileClasspath += premium.output + sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += premium.output + sourceSets.main.get().output + sourceSets.test.get().output
}
configurations[hibernateTests.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[hibernateTests.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
    add(hibernateTests.implementationConfigurationName, "org.hibernate.orm:hibernate-core:6.5.3.Final")
    add(hibernateTests.implementationConfigurationName, "org.hibernate.orm:hibernate-jcache:6.5.3.Final")
    add(hibernateTests.implementationConfigurationName, "com.github.ben-manes.caffeine:jcache:3.3.0")
}
val hibernateTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Real Hibernate ORM L2 cache regressions on H2 with isolated dependencies"
    useJUnitPlatform()
    testClassesDirs = hibernateTests.output.classesDirs
    classpath = hibernateTests.runtimeClasspath
    outputs.upToDateWhen { false }
    testLogging { events("passed", "failed", "skipped") }
}

// Spring 6 moved the lifecycle annotations to the jakarta namespace and wires
// CommonAnnotationBeanPostProcessor against them. That path cannot be proven on
// the Spring 5 test classpath, so it gets its own isolated graph. Spring 6
// needs JDK 17+, which this build already targets. Test-only; never packaged.
val springSixTests = sourceSets.create("springSixTest") {
    compileClasspath += premium.output + sourceSets.main.get().output + sourceSets.test.get().output + sourceSets.main.get().compileClasspath
    // Direct registrar tests need the agent's existing ASM runtime as well as
    // its classes. This adds no Spring 5 test dependencies to the isolated graph.
    runtimeClasspath += premium.output + sourceSets.main.get().runtimeClasspath + sourceSets.test.get().output
}
dependencies {
    add(springSixTests.implementationConfigurationName, "org.junit.jupiter:junit-jupiter:5.10.2")
    add(springSixTests.implementationConfigurationName, "org.springframework:spring-context:6.1.14")
    // Real Jakarta MVC async lifecycle and local HTTP tests; never packaged.
    add(springSixTests.implementationConfigurationName, "org.springframework:spring-webmvc:6.1.14")
    add(springSixTests.implementationConfigurationName, "org.springframework:spring-test:6.1.14")
    add(springSixTests.implementationConfigurationName, "org.apache.tomcat.embed:tomcat-embed-core:10.1.60") {
        // jakarta.annotation-api below already supplies these classes.
        exclude(group = "org.apache.tomcat", module = "tomcat-annotations-api")
    }
    // Real Jakarta constraint reload acceptance; test-only, never shipped.
    add(springSixTests.implementationConfigurationName, "com.fasterxml.jackson.core:jackson-databind:2.22.3")
    add(springSixTests.implementationConfigurationName, "org.hibernate.validator:hibernate-validator:8.0.2.Final")
    add(springSixTests.implementationConfigurationName, "org.glassfish.expressly:expressly:5.0.0")
    // Native modern method authorization; isolated from Spring 5 and never packaged.
    add(springSixTests.implementationConfigurationName, "org.springframework.security:spring-security-config:6.3.4")
    add(springSixTests.implementationConfigurationName, "org.springframework.security:spring-security-web:6.3.4")
    // Owner-approved real JPA/schema validation tests; isolated and never packaged.
    add(springSixTests.implementationConfigurationName, "org.springframework:spring-orm:6.1.14")
    add(springSixTests.implementationConfigurationName, "org.hibernate.orm:hibernate-core:6.5.3.Final")
    add(springSixTests.implementationConfigurationName, "com.h2database:h2:2.5.250")
    // Native repository refresh regressions only; never shipped with the agent.
    add(springSixTests.implementationConfigurationName, "org.springframework.data:spring-data-jpa:3.3.4")
    add(springSixTests.implementationConfigurationName, "net.bytebuddy:byte-buddy-agent:1.18.14")
    add(springSixTests.implementationConfigurationName, "jakarta.annotation:jakarta.annotation-api:2.1.1")
    add(springSixTests.runtimeOnlyConfigurationName, "org.junit.platform:junit-platform-launcher")
}
val springSixTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Real Spring 6 / Jakarta lifecycle regressions on an isolated dependency graph"
    useJUnitPlatform()
    testClassesDirs = springSixTests.output.classesDirs
    classpath = springSixTests.runtimeClasspath
    outputs.upToDateWhen { false }
    dependsOn(tasks.named("shadowJar"))
    systemProperty("reclazz.agent.jar",
        tasks.named<org.gradle.jvm.tasks.Jar>("shadowJar").get().archiveFile.get().asFile.absolutePath)
    inputs.file(tasks.named<org.gradle.jvm.tasks.Jar>("shadowJar").get().archiveFile).withPropertyName("agentJar")
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
    testLogging { events("passed", "failed", "skipped") }
}

// Native Spring 6 messaging regressions on an independent, test-only graph.
val modernMessagingTests = sourceSets.create("modernMessagingTest") {
    compileClasspath += premium.output + sourceSets.main.get().output + sourceSets.test.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += premium.output + sourceSets.main.get().runtimeClasspath + sourceSets.test.get().output
}
dependencies {
    add(modernMessagingTests.implementationConfigurationName, "org.junit.jupiter:junit-jupiter:5.10.2")
    // Existing repository test-only attacher for native Rabbit worker tracking.
    add(modernMessagingTests.implementationConfigurationName, "net.bytebuddy:byte-buddy-agent:1.18.14")
    add(modernMessagingTests.implementationConfigurationName, "org.springframework:spring-context:6.1.14")
    add(modernMessagingTests.implementationConfigurationName, "org.springframework:spring-messaging:6.1.14")
    add(modernMessagingTests.implementationConfigurationName, "org.springframework:spring-tx:6.1.14")
    add(modernMessagingTests.implementationConfigurationName, "org.springframework:spring-jms:6.1.14")
    add(modernMessagingTests.implementationConfigurationName, "org.springframework.kafka:spring-kafka:3.2.4")
    add(modernMessagingTests.implementationConfigurationName, "org.springframework.kafka:spring-kafka-test:3.2.4")
    add(modernMessagingTests.implementationConfigurationName, "org.springframework.amqp:spring-rabbit:4.1.2")
    add(modernMessagingTests.implementationConfigurationName, "org.apache.activemq:activemq-broker:6.1.3")
    add(modernMessagingTests.implementationConfigurationName, "jakarta.annotation:jakarta.annotation-api:2.1.1")
    add(modernMessagingTests.runtimeOnlyConfigurationName, "org.junit.platform:junit-platform-launcher")
}
configurations[modernMessagingTests.runtimeClasspathConfigurationName].resolutionStrategy.eachDependency {
    if (requested.group == "org.springframework") useVersion("6.1.14")
}
val modernMessagingTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Native modern Kafka/Jakarta JMS/Rabbit reload and refusal regressions"
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
    useJUnitPlatform()
    testClassesDirs = modernMessagingTests.output.classesDirs
    classpath = modernMessagingTests.runtimeClasspath
    dependsOn(tasks.named("shadowJar"))
    systemProperty("reclazz.agent.jar", tasks.named<org.gradle.jvm.tasks.Jar>("shadowJar").get().archiveFile.get().asFile.absolutePath)
    outputs.upToDateWhen { false }
    inputs.file(tasks.named<org.gradle.jvm.tasks.Jar>("shadowJar").get().archiveFile).withPropertyName("agentJar")
    val skipRabbit = providers.gradleProperty("reclazz.skipRabbitBroker").orNull == "true"
    if (skipRabbit) {
        exclude("com/onurkat/reclazz/e2e/ModernRabbitListenerReloadTest*")
        exclude("com/onurkat/reclazz/e2e/ModernRabbitConsumerCascadeReloadTest*")
        exclude("com/onurkat/reclazz/spring/ModernRabbitDependencyRefreshTest*")
    }
    testLogging { events("passed", "failed", "skipped") }
    doFirst {
        if (skipRabbit) {
            logger.lifecycle("Modern RabbitMQ tests explicitly NOT RUN (-Preclazz.skipRabbitBroker=true)")
        } else {
        ensurePinnedRabbitImage(project.serviceOf<org.gradle.process.ExecOperations>(), logger, "modernMessagingTest")
        }
    }
}

tasks.test {
    dependsOn(modernMessagingTest)
    dependsOn(springSixTest)
    dependsOn(hibernateTest)
    dependsOn(rabbitTest)
    dependsOn(jmsTest)
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
    // Throughput measurement, opt-in: -Preclazz.bench.jar=<jar> -Preclazz.bench.out=<file>
    for (key in listOf("reclazz.bench.jar", "reclazz.bench.corpus", "reclazz.bench.out",
                       "reclazz.bench.trace", "reclazz.bench.beans", "reclazz.bench.saved",
                       "reclazz.bench.files")) {
        (project.findProperty(key) as String?)?.let { systemProperty(key, it) }
    }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

// The suite is two minutes, and 104 of those seconds are the 24 end-to-end
// tests, each of which starts a JVM. The other 850 tests take under twenty.
// `unitTest` is the inner loop; `test` still runs everything and is the gate.
val unitTest by tasks.registering(Test::class) {
    // Evidence checks inspect isolated compiled test methods without loading Spring 6.
    dependsOn(tasks.named("springSixTestClasses"))
    // P4 provenance checks inspect broker test bytecode without starting a broker.
    dependsOn(tasks.named("jmsTestClasses"))
    dependsOn(tasks.named("rabbitTestClasses"))
    dependsOn(tasks.named("modernMessagingTestClasses"))
    dependsOn(jmsUnitTest)
    dependsOn(hibernateTest)
    group = "verification"
    description = "Every test except the end-to-end ones that start a JVM (about 20s)"
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
    systemProperty("reclazz.agent.jar",
            tasks.named<org.gradle.jvm.tasks.Jar>("shadowJar").get().archiveFile.get().asFile.absolutePath)
    exclude("com/onurkat/reclazz/e2e/**")
}

val e2eTest by tasks.registering(Test::class) {
    dependsOn(rabbitTest)
    dependsOn(jmsE2eTest)
    group = "verification"
    description = "Only the end-to-end tests, against the built agent jar (about 100s)"
    useJUnitPlatform()
    dependsOn(tasks.named("shadowJar"))
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("reclazz.agent.jar",
            tasks.named<org.gradle.jvm.tasks.Jar>("shadowJar").get().archiveFile.get().asFile.absolutePath)
    include("com/onurkat/reclazz/e2e/**")
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
    classDirectories.setFrom(sourceSets.main.get().output.classesDirs, premium.output.classesDirs)
    sourceDirectories.setFrom(sourceSets.main.get().allSource.srcDirs, premium.allSource.srcDirs)
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
    classDirectories.setFrom(sourceSets.main.get().output.classesDirs, premium.output.classesDirs)
    sourceDirectories.setFrom(sourceSets.main.get().allSource.srcDirs, premium.allSource.srcDirs)
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
    from(premium.output)
    archiveClassifier.set("")
}

// Local verification artifact only; publication and every consumer keep shadowJar.
val coreShadowJar by tasks.registering(ShadowJar::class) {
    archiveClassifier.set("core")
    // Match the manifest inherited by the canonical Shadow task from the Java jar.
    manifest { attributes("Implementation-Version" to project.version) }
    from(sourceSets.main.get().output)
    configurations = listOf(project.configurations.runtimeClasspath.get())
    // Shadow's canonical Java task already drops the dependency module descriptor.
    exclude("module-info.class")
}

tasks.withType<ShadowJar>().configureEach {
    dependsOn(bootstrapJar)

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
    // Exclude bootstrap classes from shadow JAR, they are loaded on the bootstrap
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

tasks.named<Jar>("sourcesJar") { from(premium.allSource) }
tasks.javadoc {
    source(premium.allJava)
    classpath += premium.output + premium.compileClasspath
}

for (name in listOf("test", "unitTest", "e2eTest")) tasks.named<Test>(name) {
    dependsOn(coreShadowJar, tasks.named("shadowJar"))
    systemProperty("reclazz.core.agent.jar", coreShadowJar.get().archiveFile.get().asFile.absolutePath)
    inputs.file(coreShadowJar.flatMap { it.archiveFile }).withPropertyName("coreAgentJar")
}

// Maven Central publication of the standalone agent, so the Gradle plugin and
// any build can resolve com.onurkat.reclazz:reclazz-agent by version. The
// published main artifact is the shadow (fat) jar with the agent manifest, the
// same jar people attach with -javaagent; sources and javadoc jars accompany
// it because the Central Portal requires them.
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "reclazz-agent"
            artifact(tasks.named("shadowJar"))
            artifact(tasks.named("sourcesJar"))
            artifact(tasks.named("javadocJar"))
            pom {
                name.set("Reclazz agent")
                description.set(
                    "Hot-reload agent for Spring Boot and SAP Commerce (Hybris): " +
                        "redefines classes in place on a stock JDK 17+ with no restart."
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
}

// Signing is required only when a key is configured, so publishToMavenLocal and
// the build work without one; the release machine that has signing.keyId signs.
signing {
    isRequired = providers.gradleProperty("signing.keyId").isPresent
    sign(publishing.publications["maven"])
}

// A local Maven layout the Central Portal accepts as an upload bundle. Publish
// the signed publication here, then zip it and upload at central.sonatype.com,
// or POST it to the Publisher API. This keeps the exact artifact set under our
// control (fat jar + sources + javadoc + pom + signatures + checksums) without
// depending on a portal-upload plugin's moving DSL.
publishing {
    repositories {
        maven {
            name = "centralBundle"
            url = layout.buildDirectory.dir("central-bundle").get().asFile.toURI()
        }
    }
}

val centralBundle by tasks.registering(Zip::class) {
    group = "publishing"
    description = "Zips the signed Maven bundle for a Central Portal upload"
    dependsOn("publishMavenPublicationToCentralBundleRepository")
    from(layout.buildDirectory.dir("central-bundle"))
    archiveFileName.set("reclazz-agent-${project.version}-central-bundle.zip")
    destinationDirectory.set(layout.buildDirectory)
}
