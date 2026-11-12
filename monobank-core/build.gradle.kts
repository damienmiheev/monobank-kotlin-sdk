dependencies {
    api(platform(libs.jackson.bom))
    api(libs.jackson.annotations)
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
}

tasks.processResources {
    val sdkVersion = project.version.toString()
    inputs.property("version", sdkVersion)
    filesMatching("**/sdk.properties") {
        expand("version" to sdkVersion)
    }
}
