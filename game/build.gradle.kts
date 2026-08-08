dependencies {
    api(project(":core"))
    testImplementation(testFixtures(project(":api")))
}

description = "Raid game instances: lifecycle state machine, team assignment, instance manager."
