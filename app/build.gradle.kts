import clu.build.*

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

val toolchain = rootProject.layout.projectDirectory.dir("toolchain")
val androidJarFile = toolchain.file("android.jar")
val minSdkVersion = 31
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.apache.commons:commons-compress:1.27.1")
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
