dependencies {
    api(project(":core"))
    testImplementation(testFixtures(project(":api")))
}

description = "Loot domain implementation: loot table registry, weighted rolling, debug tables."
