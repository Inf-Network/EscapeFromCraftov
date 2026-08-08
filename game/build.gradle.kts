plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

description = "Escape from Craftov game-server plugin: multi-instance raids, per-instance worlds, chat/tab isolation, Redis instance directory."

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
    archiveBaseName.set("RaidCoreGame")
    archiveClassifier.set("")
    relocate("com.zaxxer.hikari", "com.birditch.raidcore.libs.hikari")
    relocate("org.postgresql", "com.birditch.raidcore.libs.postgresql")
    relocate("redis.clients", "com.birditch.raidcore.libs.jedis")
    relocate("org.apache.commons.pool2", "com.birditch.raidcore.libs.commonspool2")
    relocate("com.google.gson", "com.birditch.raidcore.libs.gson")
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
    description = "Builds the deployable RaidCoreGame plugin jar."
    dependsOn(tasks.shadowJar)
}

tasks.runServer {
    minecraftVersion("1.21.11")
}
