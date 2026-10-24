import clu.build.*

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

val toolchain = rootProject.layout.projectDirectory.dir("toolchain")
val androidJarFile = toolchain.file("android.jar")
val minSdkVersion = 33
val targetSdkVersion = 35
val cluVersionCode = (findProperty("clu.versionCode") as String).toInt()
val cluVersionName = findProperty("clu.versionName") as String

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Compile against android.jar's java.* instead of the host JDK so that only
        // APIs present on Android can be referenced.
        freeCompilerArgs.addAll("-no-jdk", "-Xno-call-assertions", "-Xno-param-assertions", "-Xno-receiver-assertions")
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(files(androidJarFile))
    implementation(project(":core"))
    // Optional self-hosted LiveKit voice path: protocol client + WebRTC Android SDK classes (native lib in toolchain/jniLibs).
    implementation(project(":lkclient"))
    implementation(files(toolchain.file("webrtc/webrtc-sdk.jar")))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Robolectric UI/runtime smoke tests on real Android framework code (JVM). androidx.test classes come from the
    // :testing:robotest-shim module because Google Maven is not reachable from the build environment.
    testCompileOnly(files(androidJarFile))
    testRuntimeOnly(files(androidJarFile)) // like AGP's mockable android.jar; Robolectric loads real framework classes in its sandbox
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1") {
        exclude(group = "androidx.test")
        exclude(group = "androidx.test.espresso")
    }
    testImplementation(project(":testing:robotest-shim"))
}

val apkDir = layout.buildDirectory.dir("apk")

val linkResources by tasks.registering(Aapt2LinkTask::class) {
    aapt2.set(toolchain.file("aapt2"))
    this.androidJar.set(androidJarFile)
    manifest.set(layout.projectDirectory.file("src/main/AndroidManifest.xml"))
    resDir.set(layout.projectDirectory.dir("src/main/res"))
    versionCode.set(cluVersionCode)
    versionName.set(cluVersionName)
    minSdk.set(minSdkVersion)
    targetSdk.set(targetSdkVersion)
    outputApk.set(apkDir.map { it.file("resources.ap_") })
}

val debugKeystore by tasks.registering(DebugKeystoreTask::class) {
    keystore.set(toolchain.file("keystores/debug.keystore"))
}

fun registerVariant(name: String, release: Boolean) {
    val cap = name.replaceFirstChar { it.uppercase() }
    val dex = tasks.register<DexTask>("dex$cap") {
        r8Jar.set(toolchain.file("r8lib.jar"))
        this.androidJar.set(androidJarFile)
        programInputs.from(sourceSets.main.get().output.classesDirs)
        programInputs.from(configurations.runtimeClasspath)
        minSdk.set(minSdkVersion)
        this.release.set(release)
        outputDir.set(apkDir.map { it.dir("dex-$name") })
    }
    val pack = tasks.register<PackageApkTask>("package$cap") {
        resourceApk.set(linkResources.flatMap { it.outputApk })
        dexDir.set(dex.flatMap { it.outputDir })
        jniDirs.from(toolchain.dir("jniLibs"))
        jniDirs.from(rootProject.layout.projectDirectory.dir("native/build/jniLibs"))
        assetDirs.from(layout.projectDirectory.dir("src/main/assets"))
        assetDirs.from(toolchain.dir("assets"))
        javaResJars.from(configurations.runtimeClasspath)
        outputApk.set(apkDir.map { it.file("clu-$name-unsigned.apk") })
    }
    tasks.register<SignApkTask>("assemble$cap") {
        group = "build"
        description = "Builds and signs the $name APK"
        unsignedApk.set(pack.flatMap { it.outputApk })
        minSdk.set(minSdkVersion)
        if (release) {
            val ks = System.getenv("CLU_KEYSTORE") ?: (findProperty("clu.keystore") as String?)
            if (ks != null) keystore.set(file(ks))
            storePassword.set(providers.environmentVariable("CLU_KEYSTORE_PASSWORD").orElse(""))
            keyAlias.set(providers.environmentVariable("CLU_KEY_ALIAS").orElse("clu"))
            keyPassword.set(providers.environmentVariable("CLU_KEY_PASSWORD").orElse(providers.environmentVariable("CLU_KEYSTORE_PASSWORD").orElse("")))
        } else {
            keystore.set(debugKeystore.flatMap { it.keystore })
            storePassword.set("android")
            keyAlias.set("androiddebugkey")
            keyPassword.set("android")
        }
        signedApk.set(apkDir.map { it.file("clu-$name.apk") })
    }
}
registerVariant("debug", release = false)
registerVariant("release", release = true)

val apiCheck by tasks.registering(ApiCheckTask::class) {
    group = "verification"
    description = "Verifies all platform API references exist at minSdk (API 33)"
    minSdkJar.set(toolchain.file("android-33.jar"))
    compileSdkJar.set(androidJarFile)
    classes.from(sourceSets.main.get().output.classesDirs)
    classes.from(project(":core").sourceSets.main.get().output.classesDirs)
    report.set(layout.buildDirectory.file("reports/api-check.txt"))
    classes.from(project(":lkclient").sourceSets.main.get().output.classesDirs)
}

// Android compiles regexes with ICU, which rejects patterns desktop Java accepts (e.g. an unescaped '}').
val icuRegexCheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "Compiles every regex literal in the sources with ICU (the engine Android uses)"
    commandLine("python3", "-I", rootProject.file("scripts/check_icu_regex.py").absolutePath)
    inputs.files(fileTree("src/main/kotlin"), project(":core").fileTree("src/main/kotlin"))
    outputs.upToDateWhen { false }
}

val verifyApk by tasks.registering(VerifyApkTask::class) {
    dependsOn(icuRegexCheck)
    group = "verification"
    description = "Checks manifest components, JNI symbols and bundled assets of the APK (-Pclu.verify=release for the release APK)"
    val variant = (findProperty("clu.verify") as String?)?.replaceFirstChar { it.uppercase() } ?: "Debug"
    apk.set(tasks.named<SignApkTask>("assemble$variant").flatMap { it.signedApk })
    aapt2.set(toolchain.file("aapt2"))
    classes.from(sourceSets.main.get().output.classesDirs)
    classes.from(project(":core").sourceSets.main.get().output.classesDirs)
    classes.from(project(":lkclient").sourceSets.main.get().output.classesDirs)
    classes.from(toolchain.file("webrtc/webrtc-sdk.jar"))
    report.set(layout.buildDirectory.file("reports/verify-apk.txt"))
}
tasks.named("check") { dependsOn(apiCheck, verifyApk) }

// Robolectric reads the resource APK and manifest from this file (the same keys AGP generates).
val robolectricConfig by tasks.registering {
    val out = layout.buildDirectory.dir("generated/robolectric")
    inputs.files(linkResources)
    outputs.dir(out)
    doLast {
        val f = out.get().file("com/android/tools/test_config.properties").asFile
        f.parentFile.mkdirs()
        f.writeText(
            "android_merged_manifest=" + layout.projectDirectory.file("src/main/AndroidManifest.xml").asFile.absolutePath + "\n" +
            "android_resource_apk=" + linkResources.get().outputApk.get().asFile.absolutePath + "\n" +
            "android_custom_package=ai.clu\n"
        )
    }
}
sourceSets.test { resources.srcDir(robolectricConfig) }
tasks.test {
    useJUnit()
    systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
    systemProperty("robolectric.dependency.repo.id", "central-mirror")
    systemProperty("robolectric.logging.enabled", "true")
    // Settings Implementation Register: generated after the core tests (whose results it reports).
    dependsOn(":core:test")
    systemProperty("clu.registerOut", rootProject.file("docs/SETTINGS_REGISTER.md").absolutePath)
    systemProperty("clu.testResultDirs", listOf(project(":core").layout.buildDirectory.dir("test-results/test").get().asFile,
        layout.buildDirectory.dir("test-results/test").get().asFile).joinToString(File.pathSeparator))
    systemProperty("clu.buildInfo", "Version " + cluVersionName + ".")
    maxHeapSize = "3g"
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
