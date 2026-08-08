plugins {
    `java-test-fixtures`
}

description = "Public RaidCore API: service interfaces, domain model, value objects, domain events. Must never depend on Paper."

dependencies {
    api(project(":common"))
}
