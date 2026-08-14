plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

description = "Escape from Craftov game-server plugin: multi-instance craftovs, per-instance worlds, chat/tab isolation, Redis instance directory."

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.luckperms.api)
    compileOnly(libs.worldedit.bukkit)
    compileOnly(libs.placeholderapi)
    compileOnly(libs.decentholograms) {
        isTransitive = false
    }
    implementation(project(":core"))

    testImplementation(testFixtures(project(":api")))
    testImplementation(libs.luckperms.api)
    testCompileOnly(libs.paper.api)
    testRuntimeOnly(libs.paper.api)
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveBaseName.set("EscapeFromCraftovGame")
    archiveClassifier.set("")
    // Retired auto-map experiments are not part of the production plugin.
    exclude("com/birditch/escapefromcraftov/automap/**")
    // Publish one plugin jar containing only EscapeFromCraftov's own modules. Third-party
    // libraries are resolved by CraftovGamePluginLoader into Paper's libraries cache.
    dependencies {
        include(project(":api"))
        include(project(":core"))
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

tasks.register("buildPlugin") {
    group = "escapefromcraftov"
    description = "Builds the deployable EscapeFromCraftovGame plugin jar."
    dependsOn(tasks.shadowJar)
}

tasks.runServer {
    minecraftVersion("1.21.11")
    // run-paper 3.0.2's downloadPlugins.elements property lacks the annotation
    // Gradle 9.7 validation demands; state tracking is worthless for a dev server anyway.
    doNotTrackState("run-paper 3.0.2 properties are not annotated for Gradle 9.7 validation")
}
