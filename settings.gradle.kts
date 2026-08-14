pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
        maven("https://maven.enginehub.org/repo/") {
            name = "enginehub"
        }
        maven("https://repo.extendedclip.com/content/repositories/placeholderapi/") {
            name = "placeholderapi"
        }
        maven("https://jitpack.io") {
            name = "jitpack"
        }
    }
}

rootProject.name = "EscapeFromCraftov"

include(
    "api",
    "core",
    "game",
    "lobby",
)
