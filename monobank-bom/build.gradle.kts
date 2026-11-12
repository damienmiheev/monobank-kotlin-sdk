plugins {
    `java-platform`
    id("com.vanniktech.maven.publish")
}

dependencies {
    constraints {
        rootProject.subprojects
            .filter { it.name.startsWith("monobank-") && it.name != project.name }
            .forEach { api(project(it.path)) }
    }
}
