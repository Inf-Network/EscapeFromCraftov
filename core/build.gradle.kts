description = "EscapeFromCraftov shared implementation: runtime backbone, all domain implementations, Redis infrastructure, and the shared Bukkit glue package. Only com.birditch.escapefromcraftov.bukkit may touch Paper API."

dependencies {
    api(project(":api"))
    implementation(libs.hikaricp)
    implementation(libs.jedis)
    implementation(libs.gson)
    implementation(libs.hibernate.core)
    runtimeOnly(libs.postgresql)

    // Exclusively for the com.birditch.escapefromcraftov.bukkit glue package.
    compileOnly(libs.paper.api)

    testImplementation(testFixtures(project(":api")))
    testImplementation(libs.mockbukkit)
    testImplementation(libs.paper.api)
}
