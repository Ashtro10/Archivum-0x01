package ai.clu.desktop.voice

import ai.clu.core.config.Settings
import ai.clu.core.llm.*
import ai.clu.desktop.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.test.*

/**
 * Real-time voice end to end on real components: CLU's own livekit-server, LiveKit's Rust SDK for both participants,
 * Silero VAD, Kroko streaming recognition, the conversation manager (CluAgent, with a scripted model so replies are
 * predictable) and Piper synthesis. A stand-in client (what the desktop window or phone does) speaks synthesised questions
 * into a real microphone track and listens to CLU's track.
 */
class VoiceConversationTest {
    private val dir = Files.createTempDirectory("clu-voice-e2e").toFile()
    private var host: RunningHost? = null; private var voice: VoiceService? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @AfterEach fun stop() { scope.cancel(); voice?.shutdown(); host?.stop(); dir.deleteRecursively() }

    /** Answers predictably; [slow] streams a long answer word by word (for barge-in). */
    private class Scripted : LlmEngine {
        val prompts = CopyOnWriteArrayList<List<ChatMessage>>(); @Volatile var cancelled = 0
        override val id = "scripted"; override val source = "local"
        override fun isReady() = true
        override fun countTokens(messages: List<ChatMessage>) = messages.sumOf { it.content.length / 4 + 4 }
        override fun contextSize() = 8192
        @Volatile private var stop = false
        override fun cancel() { stop = true; cancelled++ }
        override fun generate(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit): GenResult {
            prompts += messages; stop = false
            val q = messages.last().content.lowercase()
            val text = when {
                "story" in q -> (1..60).joinToString(" ") { "This is sentence number $it of a very long story." }
                "introduce" in q -> "I am CLU, your assistant, running on this computer."
                "colo" in q -> "Your favourite colour is green."
                else -> "Hello. I am listening."
            }
            for (w in text.split(" ")) { if (stop) break; onText("$w "); Thread.sleep(if ("story" in q) 40 else 5) }
            return GenResult(text, GenStats(promptTokens = 50, generatedTokens = text.length / 4, stopReason = if (stop) "cancelled" else "eog"), source)
        }
    }

    private class Client(val room: LkRoom) {
        val heard = CopyOnWriteArrayList<Pair<Long, ShortArray>>(); val texts = CopyOnWriteArrayList<LkEvent.Text>(); val data = CopyOnWriteArrayList<String>()
        fun energySince(t: Long) = heard.filter { it.first >= t }.flatMap { it.second.asIterable() }.let { l -> if (l.isEmpty()) 0.0 else l.map { abs(it.toInt()) }.average() }
        fun firstLoudAfter(t: Long, threshold: Double = 400.0) = heard.firstOrNull { it.first >= t && it.second.map { s -> abs(s.toInt()) }.average() > threshold }?.first
    }

    private suspend fun setup(): Triple<VoiceService, Client, String> {
        val models = SpeechModels.locate(); assumeTrue(models != null && NativeVoice.load(), "voice build missing")
        val h = Host.start(DesktopPaths(File(dir, "home")), HostConfig(maintenanceMinutes = 0, governorIntervalSeconds = 600), port = 0, log = {}).also { host = it }
        val model = Scripted(); h.core.remoteEngineProvider = { model }; h.core.settings.set(Settings.REMOTE_INFERENCE, "prefer_home")
        h.core.settings.set(Settings.SEARCH_READ_PAGES, false); h.core.settings.set(Settings.NET_ENABLED, false)
        val v = h.voice!!.also { voice = it } // the host's own voice service (what the window and the phone use)
        v.store.put(v.settings.copy(endSilenceMs = 600))
        val conv = h.core.conversations.create("Voice test").id
        val s = v.startDesktop(conv)
        lateinit var c: Client
        val lk = LkRoom.connect(s["url"]!!.toString().trim('"'), s["token"]!!.toString().trim('"'), scope) { e ->
            when (e) {
                is LkEvent.AudioTrack -> c.room.listen(e.participant, e.trackHandle) { c.heard += System.currentTimeMillis() to it }
                is LkEvent.Text -> c.texts += e
                is LkEvent.Data -> c.data += String(e.bytes)
                else -> {}
            }
        }.first
        c = Client(lk); lk.ready(); lk.publishVoice(24_000)
        withTimeout(20_000) { while (v.session(s["room"]!!.toString().trim('"'))?.agent?.let { it.listener != null && it.started && it.phase != AgentPhase.STARTING } != true) delay(50) }
        return Triple(v, c, conv)
    }

    private val mouth by lazy { SpeechModels(SpeechModels.locate(), dir).let { m -> TtsVoice(VoiceCatalog.pack("piper-ryan")!!, m.packDir("piper-ryan")!!, m.espeak()) } }
    /** Says [text] into the client's microphone (a different voice from CLU's), then [silenceMs] of silence. Returns when the speech ended. */
    private suspend fun say(c: Client, text: String, silenceMs: Int = 1200): Long {
        val pcm = Pcm.toShort(Pcm.resample(mouth.synth(text, 0, 1.0f), mouth.sampleRate, 24_000))
        c.room.speak(pcm); val end = System.currentTimeMillis()
        c.room.speak(ShortArray(24 * silenceMs)); return end
    }
    private fun finals(c: Client, from: String? = null) = c.texts.filter { it.topic == "lk.transcription" && it.attributes["lk.transcription_final"] == "true" && (from == null || it.participant == from) }.map { it.text }

    @Test fun aSpokenQuestionIsTranscribedAnsweredThroughTheConversationAndSpokenBack() = runBlocking {
        val (v, c, conv) = setup()
        val askedAt = say(c, "Please introduce yourself in one sentence.")
        if (withTimeoutOrNull(25_000) { while (finals(c, "clu").none { "assistant" in it.lowercase() }) delay(50); true } == null) { println("[voice] texts=${c.texts} data=${c.data} heard=${c.heard.size}"); fail("no reply transcript") }
        val userText = finals(c).firstOrNull { "introduce" in it.lowercase() }
        assertNotNull(userText, "the client got its own words back as a final transcript: ${finals(c)}")
        println("[voice] texts=${c.texts.map { it.attributes["lk.segment_id"] + ":" + it.attributes["lk.transcription_final"] + ":" + it.text }}")
        assertTrue(c.texts.any { it.attributes["lk.transcription_final"] == "false" && it.attributes["lk.segment_id"]?.startsWith("u") == true }, "partial transcripts of the owner's words arrived while speaking")
        val firstAudio = c.firstLoudAfter(askedAt); assertNotNull(firstAudio, "CLU's reply was heard")
        withTimeout(15_000) { while (host!!.core.conversations.messages(conv).size < 2) delay(50) }
        val msgs = host!!.core.conversations.messages(conv)
        assertTrue(msgs.any { it.role.name == "USER" && "introduce" in it.content.lowercase() } && msgs.any { it.role.name == "ASSISTANT" && "assistant" in it.content.lowercase() }, "both sides are in the shared conversation: ${msgs.map { it.content }}")
        // What CLU said is really speech that says the reply: recognise CLU's own audio.
        delay(3000)
        val clu16 = Pcm.resample(Pcm.toFloat(c.heard.filter { it.first >= askedAt }.flatMap { it.second.asIterable() }.toShortArray()), 16_000, 16_000)
        val back = StreamingRecognizer(v.models.kroko!!).use { r -> r.begin(FloatArray(0)); clu16.toList().chunked(1600).forEach { r.accept(it.toFloatArray()) }; r.finish() }
        println("[voice] CLU's audio recognised as: \"$back\"; speech end → first audio heard by the client: ${firstAudio!! - askedAt} ms")
        assertTrue(listOf("clu", "clue", "assistant", "computer").count { it in back.lowercase() } >= 2, "CLU's audio says the reply: $back")
        val t = v.session(v.status()["sessions"].toString().substringAfter("\"room\":\"").substringBefore('"'))?.agent?.timings?.lastOrNull()
        assertNotNull(t); assertTrue(t.speechEndToFirstAudioMs in 1..8000, "measured on the PC: $t")
    }

    @Test fun talkingOverCLUStopsItAtOnceAndTheNewQuestionIsAnswered() = runBlocking {
        val (v, c, conv) = setup()
        say(c, "Please tell me a long story.", silenceMs = 900)
        withTimeout(25_000) { while (c.firstLoudAfter(System.currentTimeMillis() - 300) == null) delay(50) } // CLU started speaking
        delay(1500)
        val bargeAt = System.currentTimeMillis()
        val sayJob = launch { say(c, "Stop. Please introduce yourself.") }
        withTimeout(10_000) { while (c.data.none { "interrupted" in it }) delay(20) }
        val stoppedAfter = System.currentTimeMillis() - bargeAt
        sayJob.join()
        withTimeout(25_000) { while (finals(c, "clu").none { "assistant" in it.lowercase() }) delay(50) }
        println("[voice] barge-in recognised ${stoppedAfter} ms after the owner started talking")
        assertTrue(stoppedAfter < 3000, "interrupted promptly: $stoppedAfter ms")
        // After the interruption, the long story's audio did not continue: the story's last sentences were never spoken.
        assertTrue(finals(c, "clu").none { "number 60" in it }, "the rest of the story was dropped")
        val msgs = host!!.core.conversations.messages(conv).map { it.content.lowercase() }
        assertTrue(msgs.any { "story" in it } && msgs.any { "introduce" in it }, "context continues across the interruption: $msgs")
    }

    @Test fun inWakeWordModeCLUIgnoresOrdinaryTalkAndAnswersWhenCalled() = runBlocking {
        val (v, c, conv) = setup()
        v.store.put(v.settings.copy(mode = ListenMode.WAKE_WORD))
        say(c, "I should introduce my cousin to the neighbours soon.", silenceMs = 1500)
        delay(2500)
        assertTrue(finals(c, "clu").isEmpty() && host!!.core.conversations.messages(conv).isEmpty(), "nothing was sent to the model without the wake word")
        say(c, "Clue, please introduce yourself.")
        if (withTimeoutOrNull(25_000) { while (finals(c, "clu").none { "assistant" in it.lowercase() }) delay(50); true } == null) { println("[voice] wake texts=${c.texts.map { it.text }}"); fail("no answer after the wake word") }
        say(c, "Okay, goodbye for now.", silenceMs = 1500)
        delay(3000)
        val n = host!!.core.conversations.messages(conv).size
        say(c, "Please introduce yourself in one sentence.", silenceMs = 1500); delay(3000)
        assertEquals(n, host!!.core.conversations.messages(conv).size, "after the farewell CLU sleeps again until it is called")
    }

    @Test fun typedAndSpokenTurnsShareOneConversation() = runBlocking {
        val (v, c, conv) = setup()
        host!!.core.agent.respond(ai.clu.core.agent.TurnRequest(conv, "Remember my favourite colour is green.", ai.clu.core.security.Actor.ownerInApp())).collect {}
        say(c, "What is my favourite colour? Do you remember?")
        withTimeout(25_000) { while (finals(c, "clu").none { "green" in it.lowercase() }) delay(50) }
        val msgs = host!!.core.conversations.messages(conv)
        assertTrue(msgs.size >= 4, "typed and spoken turns are in the same history: ${msgs.map { it.role.name + ":" + it.content.take(30) }}")
    }
}
