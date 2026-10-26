package clu.build

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Compiles res/ and links the binary manifest + resources.arsc with aapt2.
 * Output is a resource-only APK that [PackageApkTask] extends with dex, native libs and assets.
 */
abstract class Aapt2LinkTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val aapt2: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val androidJar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val manifest: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val resDir: DirectoryProperty
    @get:Input abstract val versionCode: Property<Int>
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val minSdk: Property<Int>
    @get:Input abstract val targetSdk: Property<Int>
    @get:OutputFile abstract val outputApk: RegularFileProperty

    @TaskAction
    fun link() {
        val work = File(temporaryDir, "flat").apply { deleteRecursively(); mkdirs() }
        val aapt = aapt2.get().asFile.absolutePath
        exec.exec { commandLine(aapt, "compile", "--dir", resDir.get().asFile.absolutePath, "-o", work.absolutePath) }
        val flats = work.listFiles { f -> f.name.endsWith(".flat") }!!.sorted().map { it.absolutePath }
        exec.exec {
            commandLine(
                listOf(
                    aapt, "link",
                    "-I", androidJar.get().asFile.absolutePath,
                    "--manifest", manifest.get().asFile.absolutePath,
                    "--min-sdk-version", minSdk.get().toString(),
                    "--target-sdk-version", targetSdk.get().toString(),
                    "--version-code", versionCode.get().toString(),
                    "--version-name", versionName.get(),
                    "--auto-add-overlay",
                    "-o", outputApk.get().asFile.absolutePath,
                ) + flats
            )
        }
    }
}

/** Converts JVM bytecode (app classes + runtime jars) into dex with D8. */
abstract class DexTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val r8Jar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val androidJar: RegularFileProperty
    @get:InputFiles @get:Classpath abstract val programInputs: ConfigurableFileCollection
    @get:Input abstract val minSdk: Property<Int>
    @get:Input abstract val release: Property<Boolean>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun dex() {
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val inputs = programInputs.files.filter { it.exists() }.flatMap { f ->
            if (f.isDirectory) f.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.toList() else listOf(f)
        }.map { it.absolutePath }
        val argFile = File(temporaryDir, "d8-inputs.txt")
        argFile.writeText(inputs.joinToString("\n"))
        exec.javaexec {
            classpath(r8Jar.get().asFile)
            mainClass.set("com.android.tools.r8.D8")
            maxHeapSize = "2g"
            args(
                listOf(
                    if (release.get()) "--release" else "--debug",
                    "--min-api", minSdk.get().toString(),
                    "--lib", androidJar.get().asFile.absolutePath,
                    "--output", out.absolutePath,
                    "@" + argFile.absolutePath,
                )
            )
        }
    }
}

/**
 * Builds the final unsigned APK: resource APK + classes*.dex + lib/<abi>/ *.so + assets/.
 * STORED entries are 4-byte aligned (zipalign semantics; resources.arsc must be stored+aligned for targetSdk 30+).
 */
abstract class PackageApkTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val resourceApk: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val dexDir: DirectoryProperty
    /** Directories laid out as <abi>/lib*.so */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val jniDirs: ConfigurableFileCollection
    /** Native library file names to leave out (superseded libraries still present in a toolchain directory). */
    @get:Input @get:Optional abstract val excludeLibs: org.gradle.api.provider.SetProperty<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val assetDirs: ConfigurableFileCollection
    /** Java resources (e.g. kotlin builtins metadata, META-INF services) copied from runtime jars. */
    @get:InputFiles @get:Classpath abstract val javaResJars: ConfigurableFileCollection
    @get:OutputFile abstract val outputApk: RegularFileProperty

    @TaskAction
    fun pack() {
        val out = outputApk.get().asFile
        out.parentFile.mkdirs()
        val seen = HashSet<String>()
        AlignedZipWriter(out.outputStream().buffered()).use { zip ->
            ZipFile(resourceApk.get().asFile).use { res ->
                for (e in res.entries()) {
                    val bytes = res.getInputStream(e).readBytes()
                    val store = e.name == "resources.arsc" || e.method == ZipEntry.STORED || isIncompressible(e.name)
                    zip.add(e.name, bytes, store); seen += e.name
                }
            }
            dexDir.get().asFile.listFiles { f -> f.name.endsWith(".dex") }!!.sortedBy { it.name }.forEach {
                zip.add(it.name, it.readBytes(), store = false); seen += it.name
            }
            for (dir in jniDirs.files.filter { it.isDirectory }) {
                dir.listFiles { f -> f.isDirectory }!!.sortedBy { it.name }.forEach { abi ->
                    abi.listFiles { f -> f.name.endsWith(".so") && f.name !in excludeLibs.getOrElse(emptySet()) }!!.sortedBy { it.name }.forEach { so ->
                        val name = "lib/${abi.name}/${so.name}"
                        if (seen.add(name)) zip.add(name, so.readBytes(), store = false)
                    }
                }
            }
            for (dir in assetDirs.files.filter { it.isDirectory }) {
                dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                    val name = "assets/" + f.relativeTo(dir).invariantSeparatorsPath
                    if (seen.add(name)) zip.add(name, f.readBytes(), store = isIncompressible(name))
                }
            }
            for (jar in javaResJars.files.filter { it.isFile && it.name.endsWith(".jar") }) {
                ZipFile(jar).use { z ->
                    for (e in z.entries()) {
                        val n = e.name
                        if (e.isDirectory || n.endsWith(".class") || n.startsWith("META-INF/") && !n.startsWith("META-INF/services/") &&
                            !n.endsWith(".kotlin_module") || n == "module-info.class" || n.startsWith("META-INF/versions/")) continue
                        if (seen.add(n)) zip.add(n, z.getInputStream(e).readBytes(), store = false)
                    }
                }
            }
        }
    }

    private fun isIncompressible(name: String) =
        name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".ogg") || name.endsWith(".mp3") || name.endsWith(".bz2") || name.endsWith(".zip")
}

/** Minimal zip writer that pads STORED entries so their data starts on a 4-byte boundary. */
class AlignedZipWriter(private val raw: OutputStream) : AutoCloseable {
    private val counter = CountingStream(raw)
    private val zos = ZipOutputStream(counter)

    fun add(name: String, data: ByteArray, store: Boolean) {
        val e = ZipEntry(name)
        e.time = 315532800000L // 1980-01-01, deterministic
        if (store) {
            e.method = ZipEntry.STORED
            e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
            e.crc = CRC32().apply { update(data) }.value
            // Local header = 30 bytes + name + extra. Flush to get an accurate offset first.
            zos.flush()
            val headerStart = counter.count
            val nameLen = name.toByteArray(Charsets.UTF_8).size
            val dataStart = headerStart + 30 + nameLen
            val pad = ((4 - (dataStart % 4)) % 4).toInt()
            if (pad > 0) e.extra = ByteArray(pad)
        } else {
            e.method = ZipEntry.DEFLATED
        }
        zos.putNextEntry(e)
        zos.write(data)
        zos.closeEntry()
    }

    override fun close() = zos.close()

    private class CountingStream(val out: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }
}

/** Signs with APK Signature Scheme v1+v2+v3 using apksig, then verifies the result. */
abstract class SignApkTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val unsignedApk: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val keystore: RegularFileProperty
    @get:Input abstract val storePassword: Property<String>
    @get:Input abstract val keyAlias: Property<String>
    @get:Input abstract val keyPassword: Property<String>
    @get:Input abstract val minSdk: Property<Int>
    @get:OutputFile abstract val signedApk: RegularFileProperty

    @TaskAction
    fun sign() {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        FileInputStream(keystore.get().asFile).use { ks.load(it, storePassword.get().toCharArray()) }
        val key = ks.getKey(keyAlias.get(), keyPassword.get().toCharArray()) as PrivateKey
        val certs = ks.getCertificateChain(keyAlias.get()).map { it as X509Certificate }
        val cfg = ApkSigner.SignerConfig.Builder("CLU", key, certs).build()
        val out = signedApk.get().asFile
        ApkSigner.Builder(listOf(cfg))
            .setInputApk(unsignedApk.get().asFile)
            .setOutputApk(out)
            .setMinSdkVersion(minSdk.get())
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
        val result = ApkVerifier.Builder(out).build().verify()
        if (!result.isVerified) {
            throw GradleException("APK verification failed: " + result.errors.joinToString())
        }
        logger.lifecycle("Signed APK (v1=${result.isVerifiedUsingV1Scheme} v2=${result.isVerifiedUsingV2Scheme} v3=${result.isVerifiedUsingV3Scheme}): $out")
    }
}

/** Creates a local debug keystore (never committed) if missing. */
abstract class DebugKeystoreTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:OutputFile abstract val keystore: RegularFileProperty

    @TaskAction
    fun create() {
        val f = keystore.get().asFile
        if (f.exists()) return
        f.parentFile.mkdirs()
        exec.exec {
            commandLine(
                "keytool", "-genkeypair", "-v", "-keystore", f.absolutePath, "-storepass", "android", "-keypass", "android",
                "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
                "-dname", "CN=Clu Debug,O=Clu,C=US", "-storetype", "PKCS12"
            )
        }
    }
}
