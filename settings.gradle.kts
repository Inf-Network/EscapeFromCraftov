plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
        maven("https://api.modrinth.com/maven") {
            name = "modrinth"
            content { includeGroup("maven.modrinth") }
        }
    }
}

rootProject.name = "Craftov"
