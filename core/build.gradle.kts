description = "Core runtime: service registry, module lifecycle, event bus, configuration model, database infrastructure."

dependencies {
    api(project(":api"))
    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql)
}
