import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

dependencies {
    api(project(":monobank-core"))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    // secp256k1 is no longer shipped by the JDK (removed in Java 16). Used through the
    // lightweight API only; no JCA provider is registered.
    implementation(libs.bouncycastle)
}

configure<KotlinJvmProjectExtension> {
    compilerOptions {
        // The whole module is experimental; its own code and tests use it freely.
        optIn.add("io.github.damienmiheev.monobank.core.ExperimentalMonobankApi")
    }
}
