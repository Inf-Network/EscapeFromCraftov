plugins {
    java
}

group = "net.infnetwork.snowball"
version = "1.0.0"
description = "Craftov - extraction shooter gameplay for Paper 1.21.11"

java {
    toolchain {
        // BetterHud ships Java 25 class files; compile with JDK 25 but target Java 21 bytecode.
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.betterhud) { isTransitive = false }

    testImplementation(libs.paper.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation"))
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveBaseName.set("Craftov")
    archiveClassifier.set("")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Copies the built plugin into the local test server created by server/setup.sh.
tasks.register<Copy>("deployLocal") {
    group = "craftov"
    description = "Builds the plugin and copies it into server/run/plugins."
    dependsOn(tasks.jar)
    from(tasks.jar.flatMap { it.archiveFile })
    into(layout.projectDirectory.dir("server/run/plugins"))
    rename { "Craftov.jar" }
}
