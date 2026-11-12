pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Downloads the JDK 11 toolchain used by the testJdk11 tasks when it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "monobank-kotlin-sdk"

include(
    ":monobank-core",
    ":monobank-acquiring",
    ":monobank-personal",
    ":monobank-corporate",
    ":monobank-installments",
    ":monobank-testkit",
    ":monobank-bom",
    ":relocation",
)
