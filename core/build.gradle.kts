description = "RaidCore shared implementation: runtime backbone, all domain implementations, Redis infrastructure, and the shared Bukkit glue package. Only com.birditch.raidcore.bukkit may touch Paper API."

dependencies {
    api(project(":api"))
    implementation(libs.hikaricp)
    implementation(libs.jedis)
    implementation(libs.gson)
    implementation(libs.hibernate.core)
    runtimeOnly(libs.postgresql)

    // Exclusively for the com.birditch.raidcore.bukkit glue package.
    compileOnly(libs.paper.api)

    testImplementation(testFixtures(project(":api")))
}
