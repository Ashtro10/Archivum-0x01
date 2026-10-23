pluginManagement {
    repositories {
        // Google-hosted mirror of Maven Central first (Central rate-limits shared egress IPs).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        // Google-hosted mirror of Maven Central first (Central rate-limits shared egress IPs).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "clu"
include(":core", ":app")
