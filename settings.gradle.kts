pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.gradleup.shadow") version "9.6.1"
    }
}

rootProject.name = "reclazz"

include(":agent")
include(":integration-test")
