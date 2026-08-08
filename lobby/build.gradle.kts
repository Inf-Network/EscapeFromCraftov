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
    relocate("org.json", "com.birditch.raidcore.libs.json")
    mergeServiceFiles()
    // The lobby never touches SQL — drop core's database/profile classes entirely
    // so the jar carries no code referencing the excluded HikariCP/PostgreSQL.
    exclude("com/birditch/raidcore/core/database/**")
    exclude("com/birditch/raidcore/profile/**")
    dependencies {
        // Paper ships slf4j; compile-only annotation libs are dead weight at runtime.
        exclude(dependency("org.slf4j:.*:.*"))
        exclude(dependency("com.zaxxer:HikariCP:.*"))
        exclude(dependency("org.postgresql:postgresql:.*"))
        exclude(dependency("org.checkerframework:checker-qual:.*"))
        exclude(dependency("com.google.errorprone:error_prone_annotations:.*"))
        exclude(dependency("org.jspecify:jspecify:.*"))
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
