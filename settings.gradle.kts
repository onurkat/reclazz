pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.gradleup.shadow") version "8.3.5"
    }
}

rootProject.name = "reclazz"

include(":agent")
include(":integration-test")
