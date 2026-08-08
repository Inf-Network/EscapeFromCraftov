dependencies {
    api(project(":core"))
    testImplementation(testFixtures(project(":api")))
}

description = "Item domain implementation: registry, item service, built-in demo items."
