dependencies {
    api(project(":core"))
    testImplementation(testFixtures(project(":api")))
}

description = "Player profiles (persistent data) and sessions (connection state); in-memory and PostgreSQL repositories."
