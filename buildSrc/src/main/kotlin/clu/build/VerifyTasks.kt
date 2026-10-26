package clu.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.objectweb.asm.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * Static "NewApi"-style check: every reference from app/core bytecode to a platform class, method or field must exist
 * in the minSdk android.jar (API 33). Catches calls that compile against API 35 but would crash on Android 13.
 */
abstract class ApiCheckTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val minSdkJar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val compileSdkJar: RegularFileProperty
    @get:InputFiles @get:Classpath abstract val classes: ConfigurableFileCollection
    @get:OutputFile abstract val report: RegularFileProperty

    private class Info(val superName: String?, val interfaces: List<String>, val methods: Set<String>, val fields: Set<String>)

    private fun index(jar: File): Map<String, Info> {
        val out = HashMap<String, Info>()
        ZipFile(jar).use { z ->
            for (e in z.entries()) {
                if (!e.name.endsWith(".class")) continue
                val r = ClassReader(z.getInputStream(e).readBytes())
                val methods = HashSet<String>(); val fields = HashSet<String>()
                r.accept(object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(a: Int, n: String, d: String, s: String?, ex: Array<out String>?): MethodVisitor? { methods += n + d; return null }
                    override fun visitField(a: Int, n: String, d: String, s: String?, v: Any?): FieldVisitor? { fields += n; return null }
                }, ClassReader.SKIP_CODE)
                out[r.className] = Info(r.superName, r.interfaces.toList(), methods, fields)
            }
        }
        return out
    }

    @TaskAction
    fun check() {
        val min = index(minSdkJar.get().asFile)
        val compile = index(compileSdkJar.get().asFile)
        val problems = sortedSetOf<String>()
        fun isPlatform(owner: String) = compile.containsKey(owner)
        fun hasMethod(owner: String, nd: String, seen: MutableSet<String> = HashSet()): Boolean {
            if (!seen.add(owner)) return false
            val i = min[owner] ?: return false
            if (nd in i.methods) return true
            return (listOfNotNull(i.superName) + i.interfaces).any { hasMethod(it, nd, seen) }
        }
        fun hasField(owner: String, n: String, seen: MutableSet<String> = HashSet()): Boolean {
            if (!seen.add(owner)) return false
            val i = min[owner] ?: return false
            if (n in i.fields) return true
            return (listOfNotNull(i.superName) + i.interfaces).any { hasField(it, n, seen) }
        }
        fun typeRef(t: String, where: String) {
            val name = if (t.startsWith("[")) Type.getType(t).elementType.let { if (it.sort == Type.OBJECT) it.internalName else return } else t
            if (isPlatform(name) && !min.containsKey(name)) problems += "class $name (used in $where) is not available on API 33"
        }
        val files = classes.files.flatMap { f -> if (f.isDirectory) f.walkTopDown().filter { it.name.endsWith(".class") }.toList() else emptyList() }
        for (f in files) {
            val r = ClassReader(f.readBytes())
            val cls = r.className
            if (cls.startsWith("com/k2fsa/")) continue // vendored sherpa-onnx API, version-matched to its JNI library
            r.superName?.let { typeRef(it, cls) }
            r.interfaces.forEach { typeRef(it, cls) }
            r.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(a: Int, mName: String, d: String, s: String?, ex: Array<out String>?): MethodVisitor {
                    val where = "$cls.$mName"
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(op: Int, owner: String, name: String, desc: String, itf: Boolean) {
                            if (owner.startsWith("[") || !isPlatform(owner)) return
                            if (!min.containsKey(owner)) { problems += "class $owner (used in $where) is not available on API 33"; return }
                            if (!hasMethod(owner, name + desc) && !(owner == "java/lang/invoke/MethodHandle" || owner == "java/lang/invoke/VarHandle"))
                                problems += "$owner.$name$desc (used in $where) is not available on API 33"
                        }
                        override fun visitFieldInsn(op: Int, owner: String, name: String, desc: String) {
                            if (!isPlatform(owner)) return
                            if (!min.containsKey(owner)) { problems += "class $owner (used in $where) is not available on API 33"; return }
                            if (!hasField(owner, name)) problems += "$owner.$name (used in $where) is not available on API 33"
                        }
                        override fun visitTypeInsn(op: Int, type: String) = typeRef(type, where)
                        override fun visitLdcInsn(v: Any?) { if (v is Type && v.sort == Type.OBJECT) typeRef(v.internalName, where) }
                    }
                }
            }, 0)
        }
        report.get().asFile.writeText(if (problems.isEmpty()) "OK: ${files.size} classes checked against API 33, no unavailable references.\n" else problems.joinToString("\n"))
        if (problems.isNotEmpty()) throw GradleException("API level check failed (${problems.size}):\n" + problems.take(60).joinToString("\n"))
        logger.lifecycle("API check: ${files.size} classes, all platform references exist on API 33")
    }
}

/**
 * Verifies the packaged APK: manifest components exist as classes, JNI externals have matching native symbols,
 * and asset paths referenced by the voice code are packaged.
 */
abstract class VerifyApkTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val apk: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val aapt2: RegularFileProperty
    @get:InputFiles @get:Classpath abstract val classes: ConfigurableFileCollection
    @get:OutputFile abstract val report: RegularFileProperty

    private fun run(vararg cmd: String): String {
        val out = ByteArrayOutputStream()
        exec.exec { commandLine(*cmd); standardOutput = out }
        return out.toString()
    }

    @TaskAction
    fun verify() {
        val problems = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val apkFile = apk.get().asFile
        // 1. Manifest components.
        val tree = run(aapt2.get().asFile.absolutePath, "dump", "xmltree", "--file", "AndroidManifest.xml", apkFile.absolutePath)
        val comps = Regex("""E: (activity|service|receiver|provider|application)[^\n]*\n(?:[^\n]*\n)*?\s+A: http://schemas.android.com/apk/res/android:name\(0x01010003\)="([^"]+)"""").findAll(tree)
            .map { it.groupValues[2] }.toList()
        val classFiles = classes.files.flatMap { f -> if (f.isDirectory) f.walkTopDown().filter { it.name.endsWith(".class") }.map { it.relativeTo(f).path.removeSuffix(".class").replace('/', '.') }.toList() else emptyList() }.toSet()
        for (c in comps) {
            val fq = if (c.startsWith(".")) "ai.clu$c" else c
            if (fq !in classFiles) problems += "manifest component $fq has no class"
        }
        notes += "manifest components: ${comps.size}"
        // Activities started in code must be declared.
        val referenced = classFiles.filter { it.startsWith("ai.clu.ui.") && it.endsWith("Activity") && !it.contains('$') && it != "ai.clu.ui.BaseActivity" }
        for (a in referenced) if (comps.none { (if (it.startsWith(".")) "ai.clu$it" else it) == a }) problems += "activity $a is not declared in the manifest"
        // 2. JNI symbols.
        val tmp = File(temporaryDir, "lib").apply { deleteRecursively(); mkdirs() }
        ZipFile(apkFile).use { z -> z.entries().toList().filter { it.name.startsWith("lib/arm64-v8a/") }.forEach { e -> File(tmp, e.name.substringAfterLast('/')).writeBytes(z.getInputStream(e).readBytes()) } }
        val symbols = tmp.listFiles()!!.associate { it.name to run("readelf", "--dyn-syms", "-W", it.absolutePath) }
        val externals = mutableListOf<Pair<String, String>>() // jni symbol, library
        // Class bytes from directories and jars (the LiveKit SDK, its WebRTC build and JNA ship as jars).
        val classBytes: List<ByteArray> = classes.files.flatMap { d ->
            when {
                d.isDirectory -> d.walkTopDown().filter { it.name.endsWith(".class") }.map { it.readBytes() }.toList()
                d.isFile && d.name.endsWith(".jar") -> ZipFile(d).use { z -> z.entries().toList().filter { it.name.endsWith(".class") }.map { e -> z.getInputStream(e).readBytes() } }
                else -> emptyList()
            }
        }
        for (bytes in classBytes) {
            val r = ClassReader(bytes)
            val lib = when {
                r.className.startsWith("com/k2fsa/") -> "libsherpa-onnx-jni.so"
                r.className == "ai/clu/core/llm/LlamaBridge" -> "libclu_llama.so"
                r.className == "ai/clu/core/stt/WhisperBridge" -> "libclu_whisper.so"
                // LibaomAv1Encoder: AV1 video encoder not compiled into this WebRTC build; Clu publishes no video, so its two
                // natives are unreachable (checked 2026-10-26 for the prefixed WebRTC of livekit-android 2.29.2).
                r.className == "livekit/org/webrtc/LibaomAv1Encoder" -> continue
                r.className.startsWith("livekit/org/webrtc/") || r.className.startsWith("livekit/org/jni_zero/") -> "liblkjingle_peerconnection_so.so"
                r.className.startsWith("com/sun/jna/") -> "libjnidispatch.so"
                else -> continue
            }
            r.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(a: Int, n: String, d: String, s: String?, ex: Array<out String>?): MethodVisitor? {
                    if (a and Opcodes.ACC_NATIVE != 0) {
                        fun mangle(x: String) = x.replace("_", "_1").replace("/", "_").replace("$", "_00024")
                        externals += ("Java_" + mangle(r.className) + "_" + mangle(n)) to lib
                    }
                    return null
                }
            }, ClassReader.SKIP_CODE)
        }
        for ((sym, lib) in externals) {
            val table = symbols[lib] ?: run { problems += "native library $lib missing from APK"; "" }
            if (!table.contains(" $sym") && !table.contains(" ${sym}__")) problems += "JNI symbol $sym not found in $lib"
        }
        notes += "JNI externals checked: ${externals.size}"
        // 3. Bundled assets referenced by the voice code.
        val names = ZipFile(apkFile).use { z -> z.entries().toList().map { it.name }.toSet() }
        listOf("vad/silero_vad.onnx", "asr/encoder.onnx", "asr/decoder.onnx", "asr/joiner.onnx", "asr/tokens.txt", "asr/MODEL.txt",
            "kws/encoder.int8.onnx", "kws/decoder.int8.onnx", "kws/joiner.int8.onnx", "kws/tokens.txt", "kws/bpe.model", "kws/keywords.txt")
            .forEach { if ("assets/models/$it" !in names) problems += "asset models/$it missing" }
        report.get().asFile.writeText((if (problems.isEmpty()) "OK\n" else "PROBLEMS\n" + problems.joinToString("\n") + "\n") + notes.joinToString("\n") + "\n")
        if (problems.isNotEmpty()) throw GradleException("APK verification failed:\n" + problems.joinToString("\n"))
        logger.lifecycle("APK verification OK (${notes.joinToString()})")
    }
}
