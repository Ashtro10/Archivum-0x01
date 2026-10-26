plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.xerial:sqlite-jdbc:3.47.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.test {
    systemProperty("clu.nativeLib", rootProject.file("native/build/host/libclu_llama.so").absolutePath)
    systemProperty("clu.testModel", rootProject.file("native/build/test/tiny.gguf").absolutePath)
    maxHeapSize = "2g"
    listOf("clu.bench", "clu.llmBenchModel", "clu.llmBenchThreads", "clu.llmBenchOut", "clu.llmBenchWarmUp").forEach { k -> System.getProperty(k)?.let { systemProperty(k, it) } }
    // whisper.cpp host measurements (opt-in: need model files; see docs/VOICE_STREAMING.md)
    systemProperty("clu.whisperLib", rootProject.file("native/build/host/libclu_whisper.so").absolutePath)
    listOf("clu.whisperModels", "clu.sttFixtures", "clu.offline", "clu.lk.python", "clu.lk.models").forEach { k -> System.getProperty(k)?.let { systemProperty(k, it) } }
    systemProperty("clu.whisperOut", rootProject.file("docs/measurements/whisper_cpp_host.json").absolutePath)
    systemProperty("clu.benchOut", rootProject.file("docs/measurements/core_host.json").absolutePath)
}

val prepareNativeTest by tasks.registering(Exec::class) {
    description = "Builds the host llama.cpp JNI library and tiny test models"
    commandLine(rootProject.file("scripts/prepare_native_test.sh").absolutePath)
    outputs.files(rootProject.file("native/build/host/libclu_llama.so"), rootProject.file("native/build/test/tiny.gguf"), rootProject.file("native/build/test/tiny2.gguf"))
}
tasks.test { dependsOn(prepareNativeTest) }
