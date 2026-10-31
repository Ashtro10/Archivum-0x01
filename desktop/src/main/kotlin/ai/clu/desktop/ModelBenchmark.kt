package ai.clu.desktop

import ai.clu.core.CluCore
import ai.clu.core.config.Settings
import ai.clu.core.llm.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A reproducible benchmark of one registered model through the real inference service (the same path chat uses):
 * load time, first-token latency, prompt-processing and generation speed (median of several runs, each with a
 * different prompt prefix so the KV cache can't flatter the numbers), cancel latency, idle and active CPU, and the
 * process's memory before loading, while loaded, at its peak and after unloading.
 *
 * It measures only what this process can see. GPU utilisation is **not** measured (there is no portable counter):
 * the report says which backend the engine actually used (from its own log) and leaves GPU load to Task Manager.
 * Every result records the model, quantisation, context, threads, backend and the machine it ran on, so a number is
 * never separated from the conditions that produced it; results measured on Linux say so and must not be read as
 * Windows figures.
 */
object ModelBenchmark {
    const val ENGINE = "llama.cpp b11844 (commit 06230cb), CLU JNI bridge; Windows build: CPU backend (AVX2/FMA/F16C), no GPU backend"

    data class Options(val tokens: Int = 64, val repeats: Int = 3, val promptTokens: Int = 256, val cpuSampleSeconds: Int = 3)

    class Cancelled : Exception("benchmark cancelled")

    private val sentences = listOf(
        "The robot walked across the workshop floor while the engineers watched the display.",
        "Each leg of the machine lifts, swings forward and lowers in a repeating pattern.",
        "A battery pack sits low in the chassis to keep the centre of gravity steady.",
        "Small differences in servo timing add up over a long walk and need correcting.",
        "The notes from the meeting list three open questions and two decisions already made.",
    )

    private fun prompt(run: Int, approxTokens: Int): List<ChatMessage> {
        val sb = StringBuilder("Run $run. Read the following notes and summarise them in one sentence.\n")
        var i = 0
        while (sb.length < approxTokens * 4) sb.append(sentences[i++ % sentences.size]).append(' ')
        return listOf(ChatMessage("system", "You are a concise assistant."), ChatMessage("user", sb.toString().trim()))
    }

    private val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
    private fun cpuNanos() = os?.processCpuTime ?: -1L
    private fun cores() = Runtime.getRuntime().availableProcessors()

    fun cpuName(): String = runCatching {
        if (IS_WINDOWS) System.getenv("PROCESSOR_IDENTIFIER").orEmpty()
        else File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("model name") }?.substringAfter(':')?.trim().orEmpty()
    }.getOrDefault("").ifBlank { System.getProperty("os.arch") }

    private fun median(l: List<Double>) = l.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
    private fun r1(d: Double) = Math.round(d * 10) / 10.0

    /** Runs the benchmark. [onStep] reports progress; [cancelled] is polled between steps. The model is left unloaded. */
    fun run(core: CluCore, model: ModelEntry, opts: Options = Options(), onStep: (String) -> Unit = {}, cancelled: () -> Boolean = { false }): JsonObject {
        fun check() { if (cancelled()) throw Cancelled() }
        val svc = core.inferenceService
        val startedAt = System.currentTimeMillis()
        core.settings.set(Settings.MODEL_ACTIVE, model.id)
        onStep("unloading any loaded model"); runBlocking { svc.unload() }
        System.gc(); Thread.sleep(300)
        val rssBefore = SystemMemoryProbe.processBytes()
        check(); onStep("loading ${model.name}")
        val t0 = System.nanoTime()
        val st = runBlocking { svc.load() }
        val loadWallMs = (System.nanoTime() - t0) / 1_000_000
        if (st.status != LlmStatus.READY) return result(core, model, opts, startedAt, error = st.message.ifBlank { "the model did not load" }, state = st)
        val rssLoaded = SystemMemoryProbe.processBytes()
        val peak = AtomicLong(rssLoaded); val sampling = AtomicBoolean(true)
        val sampler = Thread { while (sampling.get()) { peak.accumulateAndGet(SystemMemoryProbe.processBytes()) { a, b -> maxOf(a, b) }; Thread.sleep(100) } }.apply { isDaemon = true; start() }
        try {
            // After a load CLU warms the prompt cache with one short generation of its own. That is real work, but it is not
            // idle time, so wait until the model has been quiet for a moment before measuring what idle costs.
            onStep("waiting for CLU's own warm-up to finish"); awaitQuiet(svc)
            check(); onStep("measuring idle CPU for ${opts.cpuSampleSeconds}s")
            val c0 = cpuNanos(); val w0 = System.nanoTime(); Thread.sleep(opts.cpuSampleSeconds * 1000L)
            val idleCpu = cpuPercent(cpuNanos() - c0, System.nanoTime() - w0)

            val runs = mutableListOf<JsonObject>(); val firstTok = mutableListOf<Double>(); val prefill = mutableListOf<Double>(); val gen = mutableListOf<Double>(); val activeCpu = mutableListOf<Double>()
            repeat(opts.repeats) { i ->
                check(); onStep("generation ${i + 1} of ${opts.repeats}")
                val c1 = cpuNanos(); val w1 = System.nanoTime()
                val r = runBlocking { svc.complete(InferenceRequest(prompt(i + 1, opts.promptTokens), GenParams(maxTokens = opts.tokens, temperature = 0f, topK = 1, seed = 1), owner = "benchmark", fitContext = false)) }
                val cpu = cpuPercent(cpuNanos() - c1, System.nanoTime() - w1)
                val s = r.stats; val fresh = (s.promptTokens - s.reusedTokens).coerceAtLeast(0)
                val pf = if (s.promptMs > 0) fresh * 1000.0 / s.promptMs else 0.0
                firstTok += s.firstTokenMs; prefill += pf; gen += s.tokensPerSecond; activeCpu += cpu
                runs += buildJsonObject {
                    put("promptTokens", s.promptTokens); put("reusedTokens", s.reusedTokens); put("generatedTokens", s.generatedTokens); put("stopReason", s.stopReason)
                    put("firstTokenMs", r1(s.firstTokenMs)); put("promptMs", r1(s.promptMs)); put("generateMs", r1(s.generateMs))
                    put("prefillTokensPerSecond", r1(pf)); put("generationTokensPerSecond", r1(s.tokensPerSecond)); put("cpuPercentOfMachine", r1(cpu))
                }
            }
            check(); onStep("measuring how fast a cancel takes effect")
            val cancelMs = cancelLatency(svc, opts)
            sampling.set(false); sampler.join(500)
            onStep("unloading")
            val tu = System.nanoTime(); runBlocking { svc.unload() }; val unloadMs = (System.nanoTime() - tu) / 1_000_000
            System.gc(); Thread.sleep(500)
            val rssAfter = SystemMemoryProbe.processBytes()
            fun mb(b: Long) = b / (1024 * 1024)
            return result(core, model, opts, startedAt, state = st) { o ->
                o.put("loadMsWall", loadWallMs); o.put("loadMsEngine", st.loadMs); o.put("unloadMs", unloadMs)
                o.put("firstTokenMsMedian", r1(median(firstTok))); o.put("prefillTokensPerSecondMedian", r1(median(prefill))); o.put("generationTokensPerSecondMedian", r1(median(gen)))
                o.put("cancelLatencyMs", cancelMs)
                o.putJsonObject("cpu") { put("idlePercentOfMachine", r1(idleCpu)); put("activePercentOfMachineMedian", r1(median(activeCpu))); put("logicalCores", cores()); put("note", "percent of all logical cores, from this process's CPU time") }
                o.putJsonObject("memoryMB") { put("processBeforeLoad", mb(rssBefore)); put("processLoaded", mb(rssLoaded)); put("processPeak", mb(peak.get())); put("processAfterUnload", mb(rssAfter))
                    put("modelEstimate", mb(st.estimatedBytes)); put("note", "this process only (resident set on Linux, private bytes on Windows); the window's browser process is not included") }
                o.putJsonArray("runs") { runs.forEach { add(it) } }
            }
        } finally { sampling.set(false); runCatching { runBlocking { svc.unload() } } }
    }

    private fun awaitQuiet(svc: InferenceService) {
        Thread.sleep(1500)
        val deadline = System.currentTimeMillis() + 60_000; var quietSince = 0L
        while (System.currentTimeMillis() < deadline) {
            val busy = svc.snapshot().let { it.running != null || it.queued.isNotEmpty() }
            if (busy) quietSince = 0L else { if (quietSince == 0L) quietSince = System.currentTimeMillis(); if (System.currentTimeMillis() - quietSince >= 800) return }
            Thread.sleep(100)
        }
    }

    private fun cpuPercent(cpuNs: Long, wallNs: Long): Double = if (cpuNs < 0 || wallNs <= 0) 0.0 else cpuNs.toDouble() / (wallNs.toDouble() * cores()) * 100.0

    private fun cancelLatency(svc: InferenceService, opts: Options): Long {
        val id = "bench-cancel"; var cancelAt = 0L; var endAt = 0L
        runBlocking {
            svc.generate(InferenceRequest(prompt(99, 64), GenParams(maxTokens = 2000, temperature = 1f), id = id, owner = "benchmark", fitContext = false)).collect { ev ->
                if (ev is InferenceEvent.Token && cancelAt == 0L) { cancelAt = System.nanoTime(); svc.cancel(id) }
                if (ev is InferenceEvent.Completed || ev is InferenceEvent.Failed) endAt = System.nanoTime()
            }
        }
        return if (cancelAt > 0 && endAt >= cancelAt) (endAt - cancelAt) / 1_000_000 else -1
    }

    private fun result(core: CluCore, model: ModelEntry, opts: Options, startedAt: Long, error: String? = null, state: LlmState, extra: (JsonObjectBuilder) -> Unit = {}): JsonObject = buildJsonObject {
        put("schema", 1); put("startedAt", Instant.ofEpochMilli(startedAt).toString())
        error?.let { put("error", it) }
        putJsonObject("model") {
            put("name", model.name); put("file", File(model.path).name); put("sizeBytes", model.sizeBytes); put("quantization", model.info.quantization); put("architecture", model.info.architecture)
            put("parametersHint", model.info.parameterCountHint); put("trainContext", model.info.contextLength)
        }
        putJsonObject("configuration") {
            put("contextTokens", state.contextSize); put("threads", state.threads); put("batch", core.settings.int(Settings.BATCH)); put("mmap", core.settings.bool(Settings.USE_MMAP))
            put("accelerationRequested", core.settings.string(Settings.ACCELERATION)); put("gpuLayersRequested", core.settings.int(Settings.GPU_LAYERS))
            put("backend", state.backend.ifEmpty { "not loaded" }); put("gpuLayersOffloaded", state.gpuLayers); put("note", state.note)
            put("promptTokensTarget", opts.promptTokens); put("generatedTokensPerRun", opts.tokens); put("runs", opts.repeats)
        }
        putJsonObject("engine") { put("version", ENGINE); put("gpuUtilisation", "not measured (use Task Manager > Performance > GPU while it runs)") }
        putJsonObject("machine") {
            put("os", System.getProperty("os.name") + " " + System.getProperty("os.version")); put("arch", System.getProperty("os.arch")); put("cpu", cpuName())
            put("logicalCores", cores()); put("totalMemoryMB", SystemMemoryProbe.systemTotalBytes() / (1024 * 1024)); put("java", System.getProperty("java.version"))
            put("measuredOn", if (IS_WINDOWS) "windows" else if (System.getProperty("os.name").lowercase().startsWith("mac")) "macos" else "linux")
        }
        extra(this)
    }

    /** One-paragraph summary for the console. */
    fun summary(r: JsonObject): String {
        val err = r["error"]?.jsonPrimitive?.contentOrNull
        val m = r["model"]!!.jsonObject; val c = r["configuration"]!!.jsonObject; val mach = r["machine"]!!.jsonObject
        val head = "${m["name"]!!.jsonPrimitive.content} (${m["quantization"]!!.jsonPrimitive.content}) | ctx ${c["contextTokens"]!!.jsonPrimitive.content} | ${c["threads"]!!.jsonPrimitive.content} threads | backend ${c["backend"]!!.jsonPrimitive.content} | measured on ${mach["measuredOn"]!!.jsonPrimitive.content}"
        if (err != null) return "$head\n  FAILED: $err"
        fun d(k: String) = r[k]!!.jsonPrimitive.double
        return "$head\n  load ${r["loadMsWall"]!!.jsonPrimitive.long} ms | first token ${d("firstTokenMsMedian")} ms | prompt ${d("prefillTokensPerSecondMedian")} tok/s | generation ${d("generationTokensPerSecondMedian")} tok/s | cancel ${r["cancelLatencyMs"]!!.jsonPrimitive.long} ms\n" +
            "  CPU idle ${r["cpu"]!!.jsonObject["idlePercentOfMachine"]!!.jsonPrimitive.content}% / active ${r["cpu"]!!.jsonObject["activePercentOfMachineMedian"]!!.jsonPrimitive.content}% | RAM before ${r["memoryMB"]!!.jsonObject["processBeforeLoad"]!!.jsonPrimitive.content} MB, loaded ${r["memoryMB"]!!.jsonObject["processLoaded"]!!.jsonPrimitive.content} MB, peak ${r["memoryMB"]!!.jsonObject["processPeak"]!!.jsonPrimitive.content} MB, after unload ${r["memoryMB"]!!.jsonObject["processAfterUnload"]!!.jsonPrimitive.content} MB"
    }

    /** Saves a result under <data>/benchmarks/ and returns the file. */
    fun save(paths: DesktopPaths, r: JsonObject): File {
        val dir = File(paths.root, "benchmarks").apply { mkdirs() }
        val name = (r["model"]!!.jsonObject["name"]!!.jsonPrimitive.content).replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40)
        val f = File(dir, "${r["startedAt"]!!.jsonPrimitive.content.replace(":", "")}-$name.json")
        f.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), r) + "\n")
        return f
    }

    fun list(paths: DesktopPaths): List<File> = File(paths.root, "benchmarks").listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.name }.orEmpty()
}
