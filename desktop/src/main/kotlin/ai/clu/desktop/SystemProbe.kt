package ai.clu.desktop

import ai.clu.core.llm.ModelRegistry
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What this PC can do, for the first-run wizard and the Models view. Everything here is read from the machine: the
 * GPU names come from Windows itself, the engine builds from the files installed next to CLU, and whether the GPU is
 * actually *used* is only known after a model has been loaded (the runtime state says so; this class never claims it).
 */
class SystemProbe(private val paths: DesktopPaths, private val platform: DesktopLlmPlatform, private val governor: ResourceGovernor,
                  private val run: (List<String>) -> String? = ::runCommand) {
    data class Check(val id: String, val status: String, val title: String, val detail: String)

    fun gpuNames(): List<String> {
        val out = when {
            IS_WINDOWS -> run(listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", "(Get-CimInstance Win32_VideoController).Name"))
                ?: run(listOf("wmic", "path", "win32_VideoController", "get", "name"))?.lineSequence()?.drop(1)?.joinToString("\n")
            else -> run(listOf("sh", "-c", "lspci 2>/dev/null | grep -i -E 'vga|3d|display' | sed 's/^[^:]*: //'"))
        }
        return out?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }

    /** True if the CPU has the instructions the Windows engine build needs (AVX2). The launcher reads this from Windows and passes CLU_AVX2. */
    fun avx2(): Boolean? = System.getenv("CLU_AVX2")?.let { it == "1" } ?: if (!IS_WINDOWS) runCatching { File("/proc/cpuinfo").readText().contains(" avx2") }.getOrNull() else null

    fun snapshot(): JsonObject {
        val snap = governor.snapshot()
        val totalMB = snap.getValue("systemTotalMB"); val freeDiskMB = paths.root.let { it.mkdirs(); it }.usableSpace / (1024 * 1024)
        val gpus = gpuNames()
        val cpuLib = platform.resolveNativeLibrary(); val vk = platform.vulkanLibrary()
        val avx2 = avx2()
        val osName = System.getProperty("os.name") + " " + System.getProperty("os.version")
        val checks = buildList {
            add(Check("os", if (IS_WINDOWS && System.getProperty("os.arch").let { it == "amd64" || it == "x86_64" }) "ok" else if (IS_WINDOWS) "fail" else "warn", "Operating system",
                if (IS_WINDOWS) "$osName, ${System.getProperty("os.arch")}" else "$osName: CLU's installer targets 64-bit Windows 10/11; this works for development only"))
            add(Check("ram", if (totalMB >= 12 * 1024) "ok" else if (totalMB >= 6 * 1024) "warn" else "fail", "Memory",
                "%.1f GB installed. ".format(totalMB / 1024.0) + if (totalMB >= 12 * 1024) "Plenty for a 3-billion-parameter model." else if (totalMB >= 6 * 1024) "Enough for a small model; larger ones will be slow or refused." else "Too little for a useful model."))
            add(Check("disk", if (freeDiskMB >= 20 * 1024) "ok" else if (freeDiskMB >= 5 * 1024) "warn" else "fail", "Storage", "%.0f GB free where CLU keeps its data. A model needs 1–3 GB.".format(freeDiskMB / 1024.0)))
            add(Check("engine", if (cpuLib.isFile) "ok" else "fail", "Inference engine", if (cpuLib.isFile) "CPU engine present (${cpuLib.length() / 1024 / 1024} MB)" else "The engine file ${cpuLib.name} is missing: reinstall CLU."))
            add(Check("avx2", when (avx2) { true -> "ok"; false -> "fail"; null -> "warn" }, "Processor", when (avx2) { true -> "Supports AVX2 (needed by the engine)."; false -> "This processor lacks AVX2; the engine cannot run here."; null -> "Could not check for AVX2." }))
            add(Check("gpu", if (gpus.isEmpty()) "warn" else "ok", "Graphics", if (gpus.isEmpty()) "No graphics adapter reported. CLU will use the CPU." else gpus.joinToString(", ") +
                (if (vk != null) ". An optional GPU (Vulkan) engine is installed: you can try it after setup. It is only used if you switch it on and CLU confirms it started." else ". CLU uses the CPU; GPU acceleration is optional and off.")))
        }
        return buildJsonObject {
            put("os", osName); put("arch", System.getProperty("os.arch")); put("cpu", ModelBenchmark.cpuName()); put("logicalCores", Runtime.getRuntime().availableProcessors())
            put("totalMemoryMB", totalMB); put("freeDiskMB", freeDiskMB); put("dataFolder", paths.root.absolutePath)
            putJsonArray("gpus") { gpus.forEach { add(it) } }
            put("cpuEngine", cpuLib.isFile); put("vulkanEngineInstalled", vk != null)
            put("loadedEngineBuild", platform.loadedBuild.ifEmpty { "not loaded yet" })
            put("memoryBudgetMB", snap.getValue("budgetMB")); put("availableForModelMB", snap.getValue("availableForModelMB"))
            putJsonArray("checks") { checks.forEach { c -> addJsonObject { put("id", c.id); put("status", c.status); put("title", c.title); put("detail", c.detail) } } }
            put("compatible", checks.none { it.status == "fail" })
        }
    }
}

/** Runs a command and returns its output, or null if it fails or takes longer than 8 seconds. */
fun runCommand(cmd: List<String>): String? = runCatching {
    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    if (!p.waitFor(8, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
    if (p.exitValue() != 0) null else out
}.getOrNull()

/** Models CLU can suggest (never downloaded without the owner's say-so), with the facts needed to decide: source, size, licence, what it needs. */
data class SuggestedModel(
    val id: String, val name: String, val parameters: String, val quantization: String, val url: String, val sourcePage: String,
    val approxBytes: Long, val license: String, val licenseUrl: String, val notes: String, val layers: Int, val embedding: Int, val headCount: Int, val kvHeads: Int,
) {
    val fileName: String get() = url.substringAfterLast('/').substringBefore('?')
    /** Estimated RAM at [ctx] tokens, from the model's published dimensions (weights + f16 KV cache + working buffers). */
    fun estimateBytes(ctx: Int): Long = approxBytes + 2L * layers * ctx * (embedding.toLong() * kvHeads / headCount) * 2 + ModelRegistry.COMPUTE_OVERHEAD
}

object ModelCatalog {
    val models = listOf(
        SuggestedModel("qwen2.5-3b-instruct-q4_k_m", "Qwen2.5 3B Instruct", "3B", "Q4_K_M", "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF", 2_100_000_000L, "Qwen Research License (not Apache-2.0: read it before relying on it)", "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct/blob/main/LICENSE",
            "Recommended default: good instruction following for its size, fast on a modern PC.", 36, 2048, 16, 2),
        SuggestedModel("llama-3.2-3b-instruct-q4_k_m", "Llama 3.2 3B Instruct", "3B", "Q4_K_M", "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF", 2_020_000_000L, "Llama 3.2 Community License", "https://www.llama.com/llama3_2/license/",
            "Alternative 3B model with the Llama 3 chat format.", 28, 3072, 24, 8),
        SuggestedModel("qwen2.5-1.5b-instruct-q4_k_m", "Qwen2.5 1.5B Instruct", "1.5B", "Q4_K_M", "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF", 1_120_000_000L, "Apache-2.0", "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct/blob/main/LICENSE",
            "Smaller and faster; noticeably weaker reasoning. A good choice for a low-memory PC.", 28, 1536, 12, 2),
    )

    fun get(id: String) = models.firstOrNull { it.id == id }

    /** How well a model suits this PC, from what CLU may use right now. */
    fun suitability(m: SuggestedModel, ctx: Int, availableForModelBytes: Long, totalMemoryBytes: Long): Pair<String, String> {
        val need = m.estimateBytes(ctx)
        return when {
            need > availableForModelBytes -> "too-large" to "Needs about ${need / 1_048_576} MB but CLU may use ${availableForModelBytes / 1_048_576} MB right now."
            need > totalMemoryBytes / 2 -> "tight" to "Fits, but would use over half of this PC's memory."
            else -> "suitable" to "Needs about ${need / 1_048_576} MB; CLU may use ${availableForModelBytes / 1_048_576} MB right now."
        }
    }
}
