// CLU's home on the PC (M2): the core brain as a background process with a local, token-protected API.
// No new frameworks: the JDK's built-in HTTP server, SQLite through JDBC, and Gradle's standard application plugin.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core"))
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("ai.clu.desktop.HostMainKt")
    applicationName = "clu-host"
    // A small, steady footprint: the model lives in native memory (llama.cpp), not on the Java heap.
    applicationDefaultJvmArgs = listOf("-Xmx384m", "-XX:+UseSerialGC", "-XX:MaxMetaspaceSize=160m", "-Xss1m")
}

// Native libraries next to the jars: lib/native/<os>-x64/. The Windows DLL comes from native/build/cmake-windows
// (cross-built with native/windows-x64.toolchain.cmake); the Linux one from the host build.
distributions {
    main {
        contents {
            from(rootProject.file("native/build/host/libclu_llama.so")) { into("lib/native/linux-x64") }
            from(rootProject.file("native/build/cmake-windows/clu_llama.dll")) { into("lib/native/windows-x64") }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    systemProperty("clu.nativeLib", rootProject.file("native/build/host/libclu_llama.so").absolutePath)
    // Opt-in: a real GGUF for the end-to-end host test (see HostRealModelTest).
    listOf("clu.desktopRealModel").forEach { k -> System.getProperty(k)?.let { systemProperty(k, it) } }
}
