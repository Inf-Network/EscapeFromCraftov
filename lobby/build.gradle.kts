plugins {
    alias(libs.plugins.shadow)
}

description = "Escape from Craftov lobby plugin: PostgreSQL player data and Redis matchmaking."

dependencies {
    compileOnly(libs.paper.api)
    implementation(project(":core"))

    testImplementation(testFixtures(project(":api")))
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveBaseName.set("EscapeFromCraftovLobby")
    archiveClassifier.set("")
    // Lobby never needs the retired auto-map research package from core.
    exclude("com/birditch/escapefromcraftov/automap/**")
    // Publish one plugin jar containing only EscapeFromCraftov's own modules. Third-party
    // libraries are resolved by CraftovLobbyPluginLoader into Paper's libraries cache.
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
    description = "Builds the deployable EscapeFromCraftovLobby plugin jar."
    dependsOn(tasks.shadowJar)
}
