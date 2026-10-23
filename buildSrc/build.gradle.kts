plugins {
    `kotlin-dsl`
}
repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
    gradlePluginPortal()
}
dependencies {
    // apksig (bundled in uber-apk-signer) for APK v2/v3 signing. Fetched by scripts/setup_toolchain.sh.
    implementation(files("../toolchain/apksig.jar"))
}
