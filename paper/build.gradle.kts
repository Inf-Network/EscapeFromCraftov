plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

description = "Paper plugin entry point: bootstrap, listeners, Brigadier commands, scheduler adapter."

dependencies {
    compileOnly(libs.paper.api)
    implementation(project(":core"))
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveBaseName.set("RaidCore")
    archiveClassifier.set("")
    relocate("com.zaxxer.hikari", "com.birditch.raidcore.libs.hikari")
    relocate("org.postgresql", "com.birditch.raidcore.libs.postgresql")
    mergeServiceFiles()
    dependencies {
        // Paper ships slf4j; never bundle our own copy.
        exclude(dependency("org.slf4j:.*:.*"))
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

tasks.register("buildPlugin") {
    group = "raidcore"
    description = "Builds the deployable RaidCore plugin jar."
    dependsOn(tasks.shadowJar)
}

tasks.runServer {
    minecraftVersion("1.21.11")
}
