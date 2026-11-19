package ai.clu.core

import ai.clu.core.llm.CancelSignal
import ai.clu.core.llm.ChatMessage
import ai.clu.core.llm.GenParams
import ai.clu.core.llm.LlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** What the platform can say about itself while the model runs (unknown values are null, never guessed). */
interface DeviceProbe {
    /** This process's resident memory in MB. */
    fun memoryMb(): Double?
    /** The platform's thermal state ("none", "light", "moderate", "severe", "critical", …), or null. */
    fun thermal(): String?
    fun batteryPercent(): Int?
    fun batteryTempC(): Double?
    fun charging(): Boolean?
    /** Model, OS, CPU and RAM of the machine. */
    fun describe(): JsonObject
}

/**
 * Model qualification on the device it runs on: the model the owner has selected, through CLU's own inference service
 * (the same path as conversations), measured, not estimated. It writes nothing to the owner's data (no conversations,
 * memories or reminders): every measurement talks to the model directly.
 *
 * Measured:
 * * model load time and memory before and after;
 * * per round: time to first token, prompt and generation speed, memory, thermal state, battery temperature;
 * * sustained operation: the generation speed of the last third of the rounds against the first third, every thermal
 *   state seen, battery used and whether the device was charging;
 * * context retention: a fact at the start of a prompt filling about [Config.contextFill] of the loaded context is asked
 *   for at the end;
 * * planning: the stepwise understanding call (no execution) for an instruction;
 * * stability: every failure is recorded with its message; a run never stops at the first error.
 */
class DeviceQualification(private val core: CluCore, private val probe: DeviceProbe, private val nanoTime: () -> Long = System::nanoTime) {
    data class Config(val rounds: Int = 12, val promptWords: Int = 350, val genTokens: Int = 128, val pauseMs: Long = 0, val contextFill: Double = 0.7)

    suspend fun run(cfg: Config = Config(), progress: (String) -> Unit = {}): JsonObject {
        val errors = mutableListOf<String>()
        val started = System.currentTimeMillis()
        val battery0 = probe.batteryPercent()
        fun ms(t0: Long) = (nanoTime() - t0) / 1e6
        fun snap() = buildJsonObject { probe.memoryMb()?.let { put("memory_mb", it) }; probe.thermal()?.let { put("thermal", it) }
            probe.batteryTempC()?.let { put("battery_temp_c", it) }; probe.batteryPercent()?.let { put("battery_percent", it) } }
        val before = snap()

        progress("Loading the model")
        runCatching { core.inferenceService.unload() }
        val tLoad = nanoTime()
        val loaded = runCatching { core.llm.loadIfNeeded() }.onFailure { errors += "load: ${it.message}" }.getOrNull()
        val loadMs = ms(tLoad)
        val state = core.llm.state.value
        val afterLoad = snap()
        val engine: LlmEngine? = if (loaded == null) null else runCatching { core.inferenceService.engine() }.getOrNull()
        if (engine == null) return report(cfg, started, before, afterLoad, loadMs, state, emptyList(), null, null, errors + "no model could be loaded", battery0)

        // Rounds: one fixed instruction prefix (as a conversation reuses it) and a different question each round.
        val system = "You are a helpful assistant. " + filler(cfg.promptWords)
        val rounds = mutableListOf<JsonObject>()
        for (i in 1..cfg.rounds) {
            progress("Round $i of ${cfg.rounds}")
            val t0 = nanoTime()
            val r = runCatching { withContext(Dispatchers.IO) {
                engine.generate(listOf(ChatMessage("system", system), ChatMessage("user", "Question $i: explain in a paragraph why ${TOPICS[i % TOPICS.size]}.")),
                    GenParams(maxTokens = cfg.genTokens, temperature = 0.7f), CancelSignal()) {}
            } }.onFailure { errors += "round $i: ${it.message}" }.getOrNull()
            rounds += buildJsonObject {
                put("round", i); put("total_ms", ms(t0))
                r?.stats?.let { s -> put("first_token_ms", s.firstTokenMs); put("prompt_tokens", s.promptTokens); put("reused_tokens", s.reusedTokens)
                    put("prompt_tok_per_s", if (s.promptMs > 0) (s.promptTokens - s.reusedTokens) * 1000.0 / s.promptMs else 0.0)
                    put("generated_tokens", s.generatedTokens); put("gen_tok_per_s", s.tokensPerSecond); put("stop", s.stopReason) }
                if (r == null) put("failed", true)
                snap().forEach { (k, v) -> put(k, v) }
            }
            if (cfg.pauseMs > 0) delay(cfg.pauseMs)
        }

        progress("Context retention")
        val retention = runCatching { retention(engine, cfg.contextFill) }.onFailure { errors += "context: ${it.message}" }.getOrNull()
        progress("Planning")
        val planning = runCatching { planning(engine) }.onFailure { errors += "planning: ${it.message}" }.getOrNull()
        return report(cfg, started, before, afterLoad, loadMs, state, rounds, retention, planning, errors, battery0)
    }

    /** A fact at the start of a long prompt, asked for at its end. */
    private suspend fun retention(engine: LlmEngine, fill: Double): JsonObject {
        val ctx = engine.contextSize().takeIf { it > 0 } ?: 2048
        val target = (ctx * fill).toInt()
        val fact = "Remember this: the locker code is 4917."
        val msgs = mutableListOf(ChatMessage("system", "You are a helpful assistant. Answer briefly."), ChatMessage("user", fact), ChatMessage("assistant", "Noted."))
        var n = 0
        while (engine.countTokens(msgs) < target - 120) {
            n++; msgs += ChatMessage("user", "Note $n: " + filler(60)); msgs += ChatMessage("assistant", "Understood, note $n.")
        }
        msgs += ChatMessage("user", "What is the locker code I gave you at the start? Reply with the number only.")
        val tokens = engine.countTokens(msgs)
        val t0 = nanoTime()
        val r = withContext(Dispatchers.IO) { engine.generate(msgs, GenParams(maxTokens = 16, temperature = 0f, topK = 1), CancelSignal()) {} }
        return buildJsonObject {
            put("context_size", ctx); put("prompt_tokens", tokens); put("fill", tokens.toDouble() / ctx); put("answer", r.text.trim().take(40))
            put("recalled", "4917" in r.text); put("total_ms", (nanoTime() - t0) / 1e6); put("first_token_ms", r.stats.firstTokenMs)
        }
    }

    /** The stepwise understanding call for an instruction (nothing is executed). */
    private suspend fun planning(engine: LlmEngine): JsonObject {
        val text = "remind me to call mom at 7 pm today"
        val t0 = nanoTime()
        val u = core.goalEngine.understandStepwise(engine, text, core.toolRegistry.all(), "today", CancelSignal())
        return buildJsonObject {
            put("total_ms", (nanoTime() - t0) / 1e6); put("supports_grammar", engine.supportsGrammar)
            put("understood", u != null); u?.let { put("kind", it.kind.name); put("tools", JsonArray(it.steps.mapNotNull { s -> s.tool }.map { t -> JsonPrimitive(t) })) }
        }
    }

    private fun report(cfg: Config, started: Long, before: JsonObject, afterLoad: JsonObject, loadMs: Double, state: ai.clu.core.llm.LlmState,
                       rounds: List<JsonObject>, retention: JsonObject?, planning: JsonObject?, errors: List<String>, battery0: Int?): JsonObject {
        fun d(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
        val ok = rounds.filter { it["failed"] == null }
        val gen = ok.mapNotNull { d(it, "gen_tok_per_s") }; val ftt = ok.mapNotNull { d(it, "first_token_ms") }.filter { it >= 0 }
        val third = (ok.size / 3).coerceAtLeast(1)
        val early = ok.take(third).mapNotNull { d(it, "gen_tok_per_s") }; val late = ok.takeLast(third).mapNotNull { d(it, "gen_tok_per_s") }
        val peak = (rounds.mapNotNull { d(it, "memory_mb") } + listOfNotNull(d(afterLoad, "memory_mb"))).maxOrNull()
        return buildJsonObject {
            put("schema", 1); put("started_at", started); put("duration_s", (System.currentTimeMillis() - started) / 1000.0)
            put("device", probe.describe())
            put("model", buildJsonObject { put("name", state.modelName ?: ""); put("id", state.modelId ?: ""); put("context_size", state.contextSize)
                put("backend", state.backend); put("threads", state.threads); put("estimated_mb", state.estimatedBytes / 1e6); put("note", state.note)
                core.llm.loadedSummary()?.forEach { (k, v) -> put("summary_$k", v) } })
            put("config", buildJsonObject { put("rounds", cfg.rounds); put("prompt_words", cfg.promptWords); put("gen_tokens", cfg.genTokens); put("pause_ms", cfg.pauseMs) })
            put("load_ms", loadMs); put("memory_before", before); put("memory_after_load", afterLoad); peak?.let { put("peak_memory_mb", it) }
            put("summary", buildJsonObject {
                put("rounds_ok", "${ok.size}/${rounds.size}")
                med(ftt)?.let { put("first_token_ms_median", it) }; pct(ftt, 0.95)?.let { put("first_token_ms_p95", it) }
                med(gen)?.let { put("gen_tok_per_s_median", it) }
                if (early.isNotEmpty() && late.isNotEmpty()) { val e = med(early)!!; val l = med(late)!!
                    put("sustained_gen_tok_per_s_first_third", e); put("sustained_gen_tok_per_s_last_third", l)
                    if (e > 0) put("sustained_drop_percent", (e - l) * 100.0 / e) }
                put("thermal_states_seen", JsonArray(rounds.mapNotNull { (it["thermal"] as? JsonPrimitive)?.content }.distinct().map { JsonPrimitive(it) }))
                val b1 = probe.batteryPercent(); if (battery0 != null && b1 != null) put("battery_used_percent", battery0 - b1)
                probe.charging()?.let { put("charging", it) }
                retention?.let { put("context_recalled", it["recalled"]!!) }
                put("errors", errors.size)
            })
            put("rounds", JsonArray(rounds)); retention?.let { put("context_retention", it) }; planning?.let { put("planning", it) }
            put("errors", JsonArray(errors.map { JsonPrimitive(it) }))
        }
    }

    companion object {
        private val TOPICS = listOf("the sky looks blue", "bread rises", "leaves change colour in autumn", "ice floats on water", "the Moon has phases",
            "batteries lose charge in the cold", "tides rise and fall", "coffee keeps people awake")
        private val WORDS = "the quick brown fox jumps over the lazy dog while a gentle river runs past old stone bridges and quiet green fields".split(' ')
        fun filler(words: Int) = (0 until words).joinToString(" ") { WORDS[it % WORDS.size] }
        fun med(xs: List<Double>) = pct(xs, 0.5)
        fun pct(xs: List<Double>, p: Double): Double? = xs.sorted().takeIf { it.isNotEmpty() }?.let { it[((it.size - 1) * p).toInt()] }
    }
}

/** Linux /proc readings (desktop Linux and Android alike); everything it cannot read is null. */
open class ProcDeviceProbe : DeviceProbe {
    private fun status(key: String) = runCatching { java.io.File("/proc/self/status").readLines().firstOrNull { it.startsWith("$key:") } }.getOrNull()
        ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toDoubleOrNull()
    override fun memoryMb(): Double? = status("VmRSS")?.let { it / 1024.0 }
    fun peakMemoryMb(): Double? = status("VmHWM")?.let { it / 1024.0 }
    override fun thermal(): String? = null
    override fun batteryPercent(): Int? = null
    override fun batteryTempC(): Double? = null
    override fun charging(): Boolean? = null
    override fun describe(): JsonObject = buildJsonObject {
        put("os", "${System.getProperty("os.name")} ${System.getProperty("os.version")}"); put("arch", System.getProperty("os.arch") ?: "")
        put("cpus", Runtime.getRuntime().availableProcessors())
        runCatching { java.io.File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("model name") || it.startsWith("Hardware") }?.substringAfter(':')?.trim() }
            .getOrNull()?.let { put("cpu", it) }
        runCatching { java.io.File("/proc/meminfo").readLines().first { it.startsWith("MemTotal:") }.substringAfter(':').trim().substringBefore(' ').toDouble() / 1024 }
            .getOrNull()?.let { put("ram_mb", it) }
    }
}
