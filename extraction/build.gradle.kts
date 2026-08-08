dependencies {
    api(project(":core"))
    testImplementation(testFixtures(project(":api")))
}

description = "Extraction domain implementation: extraction points, timed extraction sessions."
