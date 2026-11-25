plugins { java }

repositories { mavenCentral() }
dependencies { implementation("org.springframework.boot:spring-boot-starter-web:3.3.5") }

tasks.register<Sync>("stageRuntime") {
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("ui-libs"))
}
