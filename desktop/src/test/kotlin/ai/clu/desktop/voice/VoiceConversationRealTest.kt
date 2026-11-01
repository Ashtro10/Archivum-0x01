package ai.clu.desktop.voice

import ai.clu.core.config.Settings
import ai.clu.core.tools.HttpFetcher
import ai.clu.core.tools.HttpResponse
import ai.clu.desktop.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.test.*

/**
 * The acceptance conversation of M8.5, spoken into CLU's real voice path with a REAL local model:
 * a second synthetic voice says each line into a LiveKit microphone track; CLU hears it (Silero VAD, Kroko), answers
 * through CluAgent and the selected model, and speaks (Piper). Every turn's full trace, what was heard, what was
 * answered, reasoning-marker leakage (text stream, transcripts, stored history) and the barge-in stop are recorded.
 * Web search is answered by a fake Wikipedia (no internet), so research routing is exercised without the network.
 *
 * Opt-in: `-Dclu.voiceRealModel=<gguf>`; `-Dclu.voiceMeasureOut=<file.json>` writes the measurements.
 */
class VoiceConversationRealTest {
    private class FakeWiki : HttpFetcher {
        val requests = CopyOnWriteArrayList<String>()
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            requests += url
            if ("list=search" in url && ("Leman" in url || "Russ" in url)) return HttpResponse(200, """{"query":{"searchinfo":{"totalhits":1},"search":[{"title":"Leman Russ",""" +
                """"snippet":"Leman Russ is the Primarch of the Space Wolves, the VI Legion of the Space Marines in Warhammer 40,000. He is known as the Wolf King of Fenris.","timestamp":"2025-11-02T10:00:00Z"}]}}""")
            if ("list=search" in url) return HttpResponse(200, """{"query":{"searchinfo":{"totalhits":0},"search":[]}}""")
            return HttpResponse(404, "")
        }
        override suspend fun postJson(url: String, json: String, headers: Map<String, String>) = HttpResponse(404, "")
    }

    private val leak = Regex("(?i)</?\\s*(think|thinking|analysis|reasoning)\\s*>|\\[/?Context]|\\[Tool result|\"tool\"\\s*:|<\\|im_(start|end)\\|>")

    @Test fun theAcceptanceConversationThroughTheRealVoicePath() = runBlocking {
        val model = System.getProperty("clu.voiceRealModel"); assumeTrue(model != null && File(model).isFile, "opt-in: -Dclu.voiceRealModel=<gguf>")
        val models = SpeechModels.locate(); assumeTrue(models != null && NativeVoice.load(), "voice build missing")
        val dir = Files.createTempDirectory("clu-voice-real").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val web = FakeWiki()
        val host = Host.start(DesktopPaths(File(dir, "home")), HostConfig(maintenanceMinutes = 0, governorIntervalSeconds = 600), port = 0, http = web, log = { println("[host] $it") })
        val turns = mutableListOf<JsonObject>()
        var out: JsonObject? = null
        try {
            val entry = host.core.models.register(File(model!!))
            host.core.settings.set(Settings.MODEL_ACTIVE, entry.id)
            host.core.settings.set(Settings.SEARCH_READ_PAGES, false)
            val v = host.voice!!
            val conv = host.core.conversations.create("Acceptance").id
            val s = v.startDesktop(conv)
            val heardAudio = CopyOnWriteArrayList<Pair<Long, ShortArray>>(); val texts = CopyOnWriteArrayList<LkEvent.Text>()
            lateinit var room: LkRoom
            room = LkRoom.connect(s["url"]!!.jsonPrimitive.content, s["token"]!!.jsonPrimitive.content, scope) { e -> when (e) {
                is LkEvent.AudioTrack -> room.listen(e.participant, e.trackHandle) { heardAudio += System.currentTimeMillis() to it }
                is LkEvent.Text -> texts += e
                else -> {} } }.first
            room.ready(); room.publishVoice(24_000)
            val sess = v.session(s["room"]!!.jsonPrimitive.content)!!
            withTimeout(30_000) { while (!(sess.agent.listener != null && sess.agent.started)) delay(50) }
            val mouth = TtsVoice(VoiceCatalog.pack("piper-ryan")!!, v.models.packDir("piper-ryan")!!, v.models.espeak())
            fun loudAt(t: Long) = heardAudio.filter { it.first >= t }.firstOrNull { it.second.map { x -> abs(x.toInt()) }.average() > 300 }?.first
            suspend fun say(line: String) { room.speak(Pcm.toShort(Pcm.resample(mouth.synth(line, 0, 1.0f), mouth.sampleRate, 24_000))) }

            val script = listOf("Hello, Clue.", "Can you hear me?", "Who is Leman Russ?", "I said Leman Russ.", "Can you search for him?")
            for (line in script) {
                val before = sess.agent.timings.size
                val t0 = System.currentTimeMillis()
                say(line); val saidAt = System.currentTimeMillis()
                val silence = launch { room.speak(ShortArray(24_000 * 120)) { sess.agent.timings.size > before } }
                val done = withTimeoutOrNull(120_000) { while (sess.agent.timings.size <= before) delay(50); true } == true
                silence.cancelAndJoin()
                val t = sess.agent.timings.lastOrNull()?.takeIf { done }
                val replyFinal = texts.filter { it.attributes["lk.transcription_final"] == "true" && it.attributes["lk.segment_id"]?.startsWith("r") == true }.lastOrNull()?.text.orEmpty()
                val streamed = texts.filter { it.attributes["lk.transcription_final"] == "false" && it.attributes["lk.segment_id"]?.startsWith("r") == true && it.timestamp(t0) }.joinToString(" ") { it.text }
                turns += buildJsonObject {
                    put("said", line); put("completed", done); put("heard", t?.heard ?: ""); put("reply", t?.reply ?: replyFinal); put("outcome", t?.outcome ?: "TIMEOUT 120 s")
                    put("speechEndToFirstAudioMs", t?.speechEndToFirstAudioMs ?: -1); put("firstTokenMs", t?.firstTokenMs ?: -1)
                    put("clientHeardAfterSayingMs", loudAt(saidAt)?.minus(saidAt) ?: -1)
                    put("leakInSpokenStream", leak.containsMatchIn(streamed)); put("leakInReply", leak.containsMatchIn(t?.reply ?: replyFinal))
                    putJsonArray("stages") { t?.stages?.forEach { st -> add("${st.name} +${st.ms}${if (st.detail.isNotEmpty()) " (${st.detail})" else ""}") } }
                }
                println("[real] \"$line\" → heard \"${t?.heard}\" → \"${(t?.reply ?: "").take(160)}\" [${t?.outcome}] end→audio ${t?.speechEndToFirstAudioMs} ms")
                delay(1200)
            }
            // "Stop talking." while CLU speaks a long answer: audio must stop and the generation must be cancelled.
            val before = sess.agent.timings.size
            say("Tell me everything you know about the Space Wolves.")
            val silenceJob = launch { room.speak(ShortArray(24_000 * 60)) { sess.agent.phase == AgentPhase.SPEAKING } }
            val speaking = withTimeoutOrNull(90_000) { while (sess.agent.phase != AgentPhase.SPEAKING) delay(20); true } == true
            silenceJob.cancelAndJoin()
            var stopMs = -1L; var stopped = false
            if (speaking) {
                delay(1500)
                val sayAt = System.currentTimeMillis()
                val stopJob = launch { say("Stop talking.") }
                stopped = withTimeoutOrNull(10_000) { while (sess.agent.phase == AgentPhase.SPEAKING || sess.agent.phase == AgentPhase.THINKING) delay(10); true } == true
                stopMs = System.currentTimeMillis() - sayAt
                stopJob.join(); room.speak(ShortArray(24_000 * 4))
            }
            val afterStop = sess.agent.timings.drop(before)
            turns += buildJsonObject { put("said", "Stop talking. (while CLU spoke)"); put("clluWasSpeaking", speaking); put("stoppedWithinMs", stopMs); put("stopped", stopped)
                put("turnsAfter", JsonArray(afterStop.map { JsonPrimitive("${it.heard} → ${it.outcome}: ${it.reply.take(80)}") })) }
            println("[real] stop talking: speaking=$speaking stopped=$stopped within $stopMs ms; later turns: ${afterStop.map { it.heard + " → " + it.outcome }}")

            val history = host.core.conversations.messages(conv).map { it.role.name + ": " + it.content }
            out = buildJsonObject {
                put("model", entry.name); put("modelFile", File(model).name); put("cores", Runtime.getRuntime().availableProcessors())
                put("contextLength", host.core.settings.int(Settings.CONTEXT_LENGTH)); put("maxTokens", host.core.settings.int(Settings.MAX_TOKENS))
                put("turns", JsonArray(turns)); put("leakInHistory", history.any { leak.containsMatchIn(it) }); put("history", JsonArray(history.map { JsonPrimitive(it.take(300)) }))
                put("webRequests", web.requests.size)
            }
            mouth.close(); room.disconnect()
        } finally {
            out?.let { o -> println("[real] " + o); System.getProperty("clu.voiceMeasureOut")?.let { File(it).apply { parentFile?.mkdirs() }.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), o)) } }
            scope.cancel(); host.stop(); dir.deleteRecursively()
        }
        val o = out!!
        val ts = o["turns"]!!.jsonArray.map { it.jsonObject }
        assertFalse(o["leakInHistory"]!!.jsonPrimitive.boolean, "no reasoning or internal markers in the stored conversation")
        ts.filter { it["completed"] != null }.forEach { t ->
            assertTrue(t["completed"]!!.jsonPrimitive.boolean, "every line got a reply: $t")
            assertFalse(t["leakInSpokenStream"]!!.jsonPrimitive.boolean || t["leakInReply"]!!.jsonPrimitive.boolean, "no reasoning or internal markers shown or spoken: $t")
            assertTrue(t["reply"]!!.jsonPrimitive.content.isNotBlank(), "a reply with content: $t")
        }
        val stop = ts.last()
        assertTrue(stop["stopped"]!!.jsonPrimitive.boolean, "\"Stop talking\" stopped CLU: $stop")
    }

    private fun LkEvent.Text.timestamp(after: Long) = true
}
