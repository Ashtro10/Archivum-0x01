package ai.clu.desktop.voice

import ai.clu.core.config.Settings
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
 * Voice with a REAL local model, measured: the production path (CLU's livekit-server, LiveKit FFI, Silero VAD, Kroko,
 * CluAgent with llama.cpp, Piper) answering spoken questions from a stand-in client, plus the process's memory and CPU at
 * each stage. Opt-in: `-Dclu.voiceRealModel=<file.gguf>`; `-Dclu.voiceMeasureOut=<file.json>` writes the numbers.
 */
class VoiceMeasureTest {
    private fun rssMb() = SystemMemoryProbe.processBytes() / 1_048_576
    private fun cpuMs() = ProcessHandle.current().info().totalCpuDuration().map { it.toMillis() }.orElse(0L)
    private fun cpuPercentOver(ms: Long, during: () -> Unit = { Thread.sleep(ms) }): Double {
        val c0 = cpuMs(); val t0 = System.nanoTime(); during(); val wall = (System.nanoTime() - t0) / 1e6
        return 100.0 * (cpuMs() - c0) / wall
    }

    @Test fun spokenQuestionsAnsweredByARealModelWithTimingsAndResources() = runBlocking {
        val model = System.getProperty("clu.voiceRealModel"); assumeTrue(model != null && File(model).isFile, "opt-in: -Dclu.voiceRealModel=<gguf>")
        val models = SpeechModels.locate(); assumeTrue(models != null && NativeVoice.load(), "voice build missing")
        val dir = Files.createTempDirectory("clu-voice-measure").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = Host.start(DesktopPaths(File(dir, "home")), HostConfig(maintenanceMinutes = 0, governorIntervalSeconds = 600), port = 0, log = { println("[host] $it") })
        val out = buildJsonObject {
        try {
            val entry = host.core.models.register(File(model!!))
            host.core.settings.set(Settings.MODEL_ACTIVE, entry.id); host.core.settings.set(Settings.CONTEXT_LENGTH, 2048)
            host.core.settings.set(Settings.MAX_TOKENS, 60); host.core.settings.set(Settings.SEARCH_AUTO, "off"); host.core.settings.set(Settings.NET_ENABLED, false)
            val v = host.voice!!; v.store.put(v.settings.copy(endSilenceMs = 700))
            put("model", entry.name); put("cores", Runtime.getRuntime().availableProcessors())
            System.gc(); Thread.sleep(500)
            put("hostIdleRssMb", rssMb()); put("hostIdleCpuPercent", cpuPercentOver(10_000))
            // Model loaded by a typed warm-up turn (the same conversation manager voice uses).
            val conv = host.core.conversations.create("Voice measurement").id
            host.core.agent.respond(ai.clu.core.agent.TurnRequest(conv, "Hello.", ai.clu.core.security.Actor.ownerInApp())).collect {}
            System.gc(); Thread.sleep(300); put("withModelRssMb", rssMb())

            val s = v.startDesktop(conv)
            val heard = CopyOnWriteArrayList<Pair<Long, ShortArray>>(); val texts = CopyOnWriteArrayList<LkEvent.Text>()
            lateinit var room: LkRoom
            room = LkRoom.connect(s["url"]!!.jsonPrimitive.content, s["token"]!!.jsonPrimitive.content, scope) { e -> when (e) {
                is LkEvent.AudioTrack -> room.listen(e.participant, e.trackHandle) { heard += System.currentTimeMillis() to it }
                is LkEvent.Text -> texts += e
                else -> {} } }.first
            room.ready(); room.publishVoice(24_000)
            val sess = v.session(s["room"]!!.jsonPrimitive.content)!!
            withTimeout(20_000) { while (!(sess.agent.listener != null && sess.agent.started)) delay(50) }
            System.gc(); Thread.sleep(300)
            put("withVoiceEnginesRssMb", rssMb()); put("livekitServerRssMb", v.serverMemory() / 1_048_576)
            // Listening to silence (what an open microphone costs while nobody talks).
            put("listeningIdleCpuPercent", cpuPercentOver(10_000) { runBlocking { room.speak(ShortArray(24_000 * 10)) } })

            val mouth = TtsVoice(VoiceCatalog.pack("piper-ryan")!!, v.models.packDir("piper-ryan")!!, v.models.espeak())
            val questions = listOf("What is the capital of France?", "Give me one tip for staying focused.", "Please introduce yourself in one sentence.")
            val turnList = mutableListOf<JsonObject>()
            run {
                for (q in questions) {
                    val before = sess.agent.timings.size; val finalsBefore = texts.count { it.attributes["lk.transcription_final"] == "true" && it.attributes["lk.segment_id"]?.startsWith("r") == true }
                    val pcm = Pcm.toShort(Pcm.resample(mouth.synth(q, 0, 1.0f), mouth.sampleRate, 24_000))
                    val c0 = cpuMs(); val t0 = System.nanoTime()
                    room.speak(pcm); val endAt = System.currentTimeMillis()
                    val silence = launch { room.speak(ShortArray(24_000 * 30)) { sess.agent.timings.size > before } }
                    withTimeout(90_000) { while (sess.agent.timings.size <= before) delay(50) }
                    silence.cancelAndJoin()
                    val cpu = 100.0 * (cpuMs() - c0) / ((System.nanoTime() - t0) / 1e6)
                    val t = sess.agent.timings.last()
                    val heardText = texts.filter { it.attributes["lk.segment_id"]?.startsWith("u") == true && it.attributes["lk.transcription_final"] == "true" }.lastOrNull()?.text.orEmpty()
                    val reply = texts.filter { it.attributes["lk.transcription_final"] == "true" && it.attributes["lk.segment_id"]?.startsWith("r") == true }.drop(finalsBefore).lastOrNull()?.text.orEmpty()
                    val firstLoud = heard.firstOrNull { it.first >= endAt && it.second.map { x -> abs(x.toInt()) }.average() > 400 }?.first
                    turnList += buildJsonObject {
                        put("asked", q); put("heard", heardText); put("reply", reply.take(200))
                        put("speechEndToFirstAudioMs", t.speechEndToFirstAudioMs); put("sttFinalizeMs", t.sttFinalizeMs); put("firstTokenMs", t.firstTokenMs); put("ttsFirstMs", t.ttsFirstMs)
                        put("clientHeardAfterSpeechMs", firstLoud?.minus(endAt) ?: -1); put("turnCpuPercent", cpu)
                    }
                    println("[measure] \"$q\" → heard \"$heardText\" → \"${reply.take(80)}\" | $t")
                    delay(1500)
                }
            }
            put("turns", JsonArray(turnList))
            System.gc(); Thread.sleep(300); put("afterTurnsRssMb", rssMb())
            mouth.close(); room.disconnect(); v.stop(s["room"]!!.jsonPrimitive.content)
            v.engines.unload(); System.gc(); Thread.sleep(500); put("afterVoiceUnloadRssMb", rssMb())
        } finally { scope.cancel(); host.stop(); dir.deleteRecursively() }
        }
        println("[measure] $out")
        System.getProperty("clu.voiceMeasureOut")?.let { File(it).apply { parentFile?.mkdirs() }.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), out)) }
        val turns = out["turns"]!!.jsonArray.map { it.jsonObject }
        assertTrue(turns.all { it["reply"]!!.jsonPrimitive.content.isNotBlank() }, "every spoken question got a spoken reply: $turns")
        assertTrue(turns.count { t -> t["heard"]!!.jsonPrimitive.content.lowercase().let { "france" in it || "focus" in it || "introduce" in it } } == 3, "each question was recognised: $turns")
    }
}
