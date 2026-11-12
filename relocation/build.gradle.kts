import com.vanniktech.maven.publish.MavenPublishBaseExtension

// Final release of the old coordinates app.spilki:monobank-acquiring-sdk. It contains only a
// POM that relocates users to io.github.damienmiheev:monobank-acquiring. Published by hand
// (see RELEASING.md), never together with the SDK.
plugins {
    `maven-publish`
    id("com.vanniktech.maven.publish.base")
}

val newVersion = rootProject.property("VERSION_NAME") as String

group = "app.spilki"
version = "0.0.4"

publishing {
    publications {
        create<MavenPublication>("relocation") {
            groupId = "app.spilki"
            artifactId = "monobank-acquiring-sdk"
            version = "0.0.4"
            pom {
                packaging = "pom"
                name.set("monobank-acquiring-sdk (relocated)")
                description.set("Relocated to io.github.damienmiheev:monobank-acquiring.")
                url.set(property("POM_URL") as String)
                licenses {
                    license {
                        name.set(property("POM_LICENSE_NAME") as String)
                        url.set(property("POM_LICENSE_URL") as String)
                    }
                }
                developers {
                    developer {
                        id.set(property("POM_DEVELOPER_ID") as String)
                        name.set(property("POM_DEVELOPER_NAME") as String)
                    }
                }
                scm {
                    url.set(property("POM_SCM_URL") as String)
                    connection.set(property("POM_SCM_CONNECTION") as String)
                }
                distributionManagement {
                    relocation {
                        groupId.set("io.github.damienmiheev")
                        artifactId.set("monobank-acquiring")
                        version.set(newVersion)
                        message.set(
                            "Moved to io.github.damienmiheev:monobank-acquiring. Packages were renamed from " +
                                "app.spilki.monobank.acquiring to io.github.damienmiheev.monobank.acquiring; see " +
                                "https://github.com/damienmiheev/monobank-kotlin-sdk#migrating-from-appspilki",
                        )
                    }
                }
            }
        }
    }
}

configure<MavenPublishBaseExtension> {
    publishToMavenCentral(automaticRelease = false)
    signAllPublications()
}
