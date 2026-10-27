package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.security.Actor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Do the personality settings change what a real model says? Runs the same questions through the full agent with a real
 * instruction-tuned GGUF (greedy decoding, so runs are repeatable) under each setting value and records reply lengths.
 * Opt-in: -Dclu.personaModel=<gguf>. Writes docs/measurements/persona_host.json. A small model follows style
 * instructions only loosely; the numbers are reported as measured, whatever they show.
 */
class PersonaMeasurement {
    private val questions = listOf("Why is the sky blue?", "How do vaccines work?", "Should I learn Kotlin or Python first?",
        "What is inflation?", "How can I sleep better?", "Explain what a database index is.")

    @Test fun measure(): Unit = runBlocking {
        val path = System.getProperty("clu.personaModel")
        assumeTrue(path != null && File(path).exists())
        val core = newCore()
        val s = core.settings
        s.set(Settings.THREADS, 4); s.set(Settings.LOAD_ON_START, false); s.set(Settings.TEMPERATURE, 0.0)
        s.set(Settings.MAX_TOKENS, 200)
        val model = core.models.register(File(path))
        s.set(Settings.MODEL_ACTIVE, model.id)
        core.llm.loadIfNeeded() ?: error(core.llm.state.value.message)
        fun words(t: String) = t.split(Regex("\\s+")).count { it.isNotBlank() }
        suspend fun run(key: String, value: String): Map<String, Any> {
            s.reset(Settings.RESPONSE_LENGTH, verified = true); s.reset(Settings.EXPLANATION, verified = true); s.reset(Settings.PROACTIVITY, verified = true)
            s.set(key, value)
            val replies = questions.map { q ->
                val conv = core.conversations.create().id // fresh conversation: no history carries over between settings
                core.agent.respond(TurnRequest(conv, q, Actor.ownerInApp())).toList().filterIsInstance<AgentEvent.Done>().last()
            }
            val w = replies.map { words(it.text) }
            println("$key=$value words=$w")
            return mapOf("setting" to key, "value" to value, "words" to w, "mean_words" to w.average(),
                "tokens" to replies.map { (it.meta.stats["tokens"] ?: -1.0).toInt() }, "first_reply" to replies.first().text)
        }
        val rows = listOf(
            run(Settings.RESPONSE_LENGTH, "brief"), run(Settings.RESPONSE_LENGTH, "balanced"), run(Settings.RESPONSE_LENGTH, "detailed"),
            run(Settings.EXPLANATION, "minimal"), run(Settings.EXPLANATION, "thorough"),
            run(Settings.PROACTIVITY, "off"), run(Settings.PROACTIVITY, "active"))
        core.shutdown()
        fun j(v: Any): String = when (v) {
            is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
            is Double -> "%.1f".format(v); is List<*> -> v.joinToString(", ", "[", "]") { j(it!!) }; else -> v.toString() }
        val out = File("../docs/measurements/persona_host.json")
        out.parentFile.mkdirs()
        out.writeText("{\n  \"model\": \"${File(path).name}\",\n  \"decoding\": \"greedy (temperature 0), max_tokens 200 scaled by response length\",\n" +
            "  \"questions\": ${j(questions)},\n  \"runs\": [\n" + rows.joinToString(",\n") { r -> "    {" + r.entries.joinToString(", ") { "\"${it.key}\": ${j(it.value)}" } + "}" } +
            "\n  ],\n  \"environment\": \"${System.getProperty("os.arch")} JVM, llama.cpp JNI host build (build container, not the tablet)\"\n}\n")
    }
}
