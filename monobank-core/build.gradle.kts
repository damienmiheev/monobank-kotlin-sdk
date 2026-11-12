dependencies {
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

tasks.withType<Test>().configureEach {
    // LogSafetyTest reads the SDK's debug output to prove no secrets are logged.
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug")
}
