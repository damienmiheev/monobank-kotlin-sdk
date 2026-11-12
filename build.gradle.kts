import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.bcv)
}

/** Modules that ship Kotlin code (everything except the BOM and the relocation POM). */
val libraryModules =
    setOf(
        "monobank-core",
        "monobank-acquiring",
        "monobank-personal",
        "monobank-corporate",
        "monobank-installments",
        "monobank-testkit",
    )

apiValidation {
    ignoredProjects += listOf("monobank-bom", "relocation")
    nonPublicMarkers += "io.github.damienmiheev.monobank.core.InternalMonobankApi"
}

allprojects {
    group = property("GROUP") as String
    version = property("VERSION_NAME") as String
}

val catalog = libs
val ktlintVersion: String = libs.versions.ktlint.engine.get()
val jacksonMinVersion: String = libs.versions.jackson.min.get()
val jacksonDatabindMinVersion: String = libs.versions.jackson.databind.min.get()
val kotlinStdlibVersion: String = libs.versions.kotlin.stdlib.get()

configure(subprojects.filter { it.name in libraryModules }) {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java-library")
    apply(plugin = "dev.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "com.vanniktech.maven.publish")

    configure<KotlinJvmProjectExtension> {
        explicitApi()
        // Publish a kotlin-stdlib older Kotlin compilers can read (the compiler itself is newer).
        coreLibrariesVersion = kotlinStdlibVersion
        jvmToolchain(21)
        compilerOptions {
            // Bytecode and JDK API surface of Java 11: Kill Bill 0.24 runs on JDK 11.
            jvmTarget.set(JvmTarget.JVM_11)
            freeCompilerArgs.addAll("-Xjdk-release=11", "-Xjsr305=strict")
            // Kotlin consumers need Kotlin 2.1+ (they read 2.2 metadata); Java consumers are unaffected.
            apiVersion.set(KotlinVersion.KOTLIN_2_2)
            languageVersion.set(KotlinVersion.KOTLIN_2_2)
            optIn.add("io.github.damienmiheev.monobank.core.InternalMonobankApi")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(11)
    }

    dependencies {
        // Aligns all SDK modules to one version, so mixing module versions can't break at runtime.
        "api"(platform(project(":monobank-bom")))
        "testImplementation"(platform(catalog.junit.bom))
        "testImplementation"(catalog.bundles.test)
        "testRuntimeOnly"(catalog.junit.platform.launcher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            // Live tests only run in liveTest (below).
            if (name != "liveTest") excludeTags("live")
        }
        // CI runs the plain test task on the newest JDK as well: -PtestJavaVersion=25
        providers.gradleProperty("testJavaVersion").orNull?.let { version ->
            if (name == "test") {
                javaLauncher.set(
                    project.extensions.getByType<JavaToolchainService>().launcherFor {
                        languageVersion.set(JavaLanguageVersion.of(version))
                    },
                )
            }
        }
        testLogging {
            events("failed")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    val javaToolchains = extensions.getByType<JavaToolchainService>()
    val sourceSets = extensions.getByType<SourceSetContainer>()

    // Runs the unit tests on a real Java 11 runtime (the oldest supported JVM).
    val testJdk11 =
        tasks.register<Test>("testJdk11") {
            description = "Runs the unit tests on Java 11."
            group = "verification"
            testClassesDirs = sourceSets["test"].output.classesDirs
            classpath = sourceSets["test"].runtimeClasspath
            javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(11)) })
        }

    // Runs the unit tests against the oldest supported Jackson (the version Kill Bill pins).
    val jackson213 =
        configurations.create("testRuntimeJackson213") {
            extendsFrom(configurations["testRuntimeClasspath"])
            isCanBeConsumed = false
            resolutionStrategy.eachDependency {
                if (requested.group == "com.fasterxml.jackson.core") {
                    useVersion(
                        if (requested.name == "jackson-databind") jacksonDatabindMinVersion else jacksonMinVersion,
                    )
                }
            }
        }
    val testJackson213 =
        tasks.register<Test>("testJackson213") {
            description = "Runs the unit tests with Jackson 2.13."
            group = "verification"
            testClassesDirs = sourceSets["test"].output.classesDirs
            classpath = sourceSets["test"].output + sourceSets["main"].output + jackson213
            systemProperty("monobank.expectedJacksonDatabind", jacksonDatabindMinVersion)
        }

    // Tagged @Tag("live"): calls the real monobank API. Run by hand only, see CONTRIBUTING.md.
    tasks.register<Test>("liveTest") {
        description = "Runs tests against the real monobank API (needs credentials in env vars)."
        group = "verification"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform { includeTags("live") }
        outputs.upToDateWhen { false }
    }

    tasks.named("check") {
        dependsOn(testJdk11, testJackson213)
    }

    configure<DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(rootProject.files("detekt.yml"))
    }
    // Keep detekt's and ktlint's bundled Kotlin compiler instead of the project's version.
    configurations.matching { it.name.startsWith("detekt") || it.name.startsWith("ktlint") }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion(requested.version!!)
            }
        }
    }
    configure<KtlintExtension> {
        version.set(ktlintVersion)
    }

    configure<MavenPublishBaseExtension> {
        configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"), sourcesJar = SourcesJar.Sources()))
    }
}

configure(subprojects.filter { it.name != "relocation" }) {
    plugins.withId("com.vanniktech.maven.publish") {
        configure<MavenPublishBaseExtension> {
            // Uploads to the Central Portal; the release is confirmed by hand in the portal UI.
            publishToMavenCentral(automaticRelease = false)
            // Required for every non-SNAPSHOT version: a release without a key fails instead of going out unsigned.
            signAllPublications()
        }
    }
}
