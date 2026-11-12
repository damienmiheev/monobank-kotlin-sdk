dependencies {
    api(project(":monobank-core"))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    // secp256k1 is no longer shipped by the JDK (removed in Java 16). Used through the
    // lightweight API only; no JCA provider is registered.
    implementation(libs.bouncycastle)
}
