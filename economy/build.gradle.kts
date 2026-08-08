dependencies {
    api(project(":core"))
    testImplementation(testFixtures(project(":api")))
}

description = "Economy domain implementation: balances and transactions on top of player profiles."
