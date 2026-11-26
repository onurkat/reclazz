repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
        content { includeGroup("com.intellij.remoterobot") }
    }
}

val robotClient by configurations.creating
dependencies { robotClient("com.intellij.remoterobot:remote-robot:0.11.23") }

val stageClient by tasks.registering(Sync::class) {
    from(robotClient)
    into(layout.buildDirectory.dir("robot-libs"))
}

tasks.register<Exec>("uiTest") {
    group = "verification"
    description = "Run the real Reclazz IDE workflow in an isolated Linux virtual display."
    dependsOn(stageClient, ":fixture:stageRuntime")
    val plugin = providers.gradleProperty("pluginZip").map { file(it) }.orElse(provider {
        val props = java.util.Properties()
        file("../gradle.properties").inputStream().use { props.load(it) }
        file("../build/distributions/reclazz-" + props.getProperty("pluginVersion") + ".zip")
    })
    commandLine("python3", "run.py", plugin.get().absolutePath, providers.gradleProperty("uiScenario").getOrElse("matrix"))
}
