dependencies {
    api(project(":core"))
    testImplementation(testFixtures(project(":api")))
}

description = "Combat domain implementation: player combat state tracking, downed/revive flow."
