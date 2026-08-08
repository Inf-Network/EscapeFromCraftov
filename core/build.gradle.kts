description = "RaidCore runtime: service registry, module lifecycle, event bus, configuration, database infrastructure, and all domain implementations."

dependencies {
    api(project(":api"))
    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql)

    testImplementation(testFixtures(project(":api")))
}
