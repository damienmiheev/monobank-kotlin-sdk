dependencies {
    api(project(":monobank-core"))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    testImplementation(project(":monobank-acquiring"))
}
