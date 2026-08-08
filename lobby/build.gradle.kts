plugins {
    alias(libs.plugins.shadow)
}

description = "Escape from Craftov lobby plugin: Redis matchmaking queue, player settings, cosmetics."

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
    archiveBaseName.set("RaidCoreLobby")
    archiveClassifier.set("")
    relocate("redis.clients", "com.birditch.raidcore.libs.jedis")
    relocate("org.apache.commons.pool2", "com.birditch.raidcore.libs.commonspool2")
    relocate("com.google.gson", "com.birditch.raidcore.libs.gson")
    mergeServiceFiles()
    dependencies {
        // Lobby never touches SQL; Paper ships slf4j.
        exclude(dependency("org.slf4j:.*:.*"))
        exclude(dependency("com.zaxxer:HikariCP:.*"))
        exclude(dependency("org.postgresql:postgresql:.*"))
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

tasks.register("buildPlugin") {
    group = "raidcore"
    description = "Builds the deployable RaidCoreLobby plugin jar."
    dependsOn(tasks.shadowJar)
}
