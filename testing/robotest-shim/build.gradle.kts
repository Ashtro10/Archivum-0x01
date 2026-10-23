// androidx.test "monitor" + espresso idling-resource classes compiled from the Apache-2.0 sources in
// android/android-test (fetched by scripts/setup_toolchain.sh). Robolectric needs them at test runtime and they are
// otherwise only published on Google Maven. Test-only; never packaged into the APK.
plugins {
    kotlin("jvm")
}
val src = rootProject.layout.projectDirectory.dir("toolchain/androidx-test")
sourceSets {
    main {
        java.srcDirs(src.dir("runner/monitor/java"), src.dir("espresso/idling_resource/java"), "stubs")
        kotlin.srcDirs(src.dir("runner/monitor/java"))
    }
}
// Instrumentation-runner pieces built on hidden framework APIs are not used by Robolectric.
val excluded = listOf("**/androidx/test/runner/MonitoringInstrumentation.java", "**/androidx/test/internal/runner/hidden/**",
    "**/androidx/test/runner/MonitoringInstrumentation*.java", "**/androidx/test/internal/runner/InstrumentationConnection.java",
    "**/androidx/test/internal/runner/intercepting/DefaultInterceptingActivityFactory.java")
tasks.withType<JavaCompile>().configureEach { excluded.forEach { exclude(it) } }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { excluded.forEach { exclude(it) } }
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    compileOnly(files(rootProject.file("toolchain/android.jar")))
    compileOnly("com.google.errorprone:error_prone_annotations:2.28.0")
}
tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xlint:none"); options.isWarnings = false }
