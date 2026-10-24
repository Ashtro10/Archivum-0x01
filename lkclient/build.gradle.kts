import clu.build.ProtoLiteTask

// Minimal LiveKit client (signalling + WebRTC + data packets) for Clu's optional self-hosted voice path.
// Platform-independent: WebRTC is behind the RtcFactory interface (Android: org.webrtc, tests: webrtc-java).
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

evaluationDependsOn(":core")
val toolchain = rootProject.layout.projectDirectory.dir("toolchain")
val protoLite = configurations.create("protoLite")

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("com.google.protobuf:protobuf-javalite:4.36.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    protoLite("com.google.protobuf:protobuf-javalite:4.36.2")

    testImplementation(project(":core"))
    // Core's test helpers (TestPlatform, ScriptedEngine, JDBC SQLite driver) for the end-to-end test.
    testImplementation(rootProject.project(":core").extensions.getByType<SourceSetContainer>()["test"].output)
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.xerial:sqlite-jdbc:3.47.1.0")
    testImplementation("dev.onvoid.webrtc:webrtc-java:0.20.0")
    testRuntimeOnly("dev.onvoid.webrtc:webrtc-java:0.20.0:linux-x86_64")
}

val generateLiveKitProto by tasks.registering(ProtoLiteTask::class) {
    description = "Generates protobuf-javalite sources for the LiveKit signalling protocol"
    protoc.set(toolchain.file("protoc"))
    protos.from(listOf("livekit_rtc", "livekit_models", "livekit_metrics").map { toolchain.file("livekit-protocol/protobufs/$it.proto") })
    includeJars.from(protoLite)
    outputDir.set(layout.buildDirectory.dir("generated/proto"))
}
sourceSets.main { java.srcDir(generateLiveKitProto) }

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    // End-to-end tests run only when a livekit-server binary and the voice server environment are provided.
    listOf("clu.lk.server", "clu.lk.python", "clu.lk.models", "clu.lk.out", "clu.offline").forEach { k -> System.getProperty(k)?.let { systemProperty(k, it) } }
    systemProperty("clu.serverDir", rootProject.file("server").absolutePath)
    systemProperty("clu.nativeLib", rootProject.file("native/build/host/libclu_llama.so").absolutePath)
    systemProperty("clu.testModel", rootProject.file("native/build/test/tiny.gguf").absolutePath)
    maxHeapSize = "2g"
}
