package ai.clu.livekit

import ai.clu.core.*
import ai.clu.core.config.Settings
import ai.clu.core.livekit.*
import ai.clu.core.memory.*
import ai.clu.core.tools.HttpFetcher
import ai.clu.core.tools.HttpResponse
import dev.onvoid.webrtc.media.audio.AudioTrack
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Plain HttpURLConnection fetcher (the app uses its own Android implementation). */
class JvmHttp : HttpFetcher {
    override suspend fun get(url: String, headers: Map<String, String>) = withContext(Dispatchers.IO) { call(url, "GET", null, headers) }
    override suspend fun postJson(url: String, json: String, headers: Map<String, String>) = withContext(Dispatchers.IO) { call(url, "POST", json, headers) }
    private fun call(url: String, method: String, body: String?, headers: Map<String, String>): HttpResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; headers.forEach(c::setRequestProperty)
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
        return HttpResponse(code, text)
    }
}

/**
 * Network mode end to end, with every real component except phone hardware: livekit-server (local, dev keys), the
 * Clu voice server/agent (sherpa-onnx ASR + Piper TTS), this Kotlin LiveKit client on WebRTC (webrtc-java with a
 * headless audio device), and Clu's core (agent, memory, prompt, bridge). The "owner" speaks synthetic speech into
 * the client's microphone track.
 *
 * Runs only when -Dclu.lk.server, -Dclu.lk.python and -Dclu.lk.models are given (see docs/LIVEKIT_INTEGRATION.md).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveKitEndToEndTest {
    private val lkBin = System.getProperty("clu.lk.server")
    private val python = System.getProperty("clu.lk.python")
    private val models = System.getProperty("clu.lk.models")
    private val serverDir = File(System.getProperty("clu.serverDir", "../server"))
    private val work: File = Files.createTempDirectory("clu-lk-e2e").toFile()
    private val procs = mutableListOf<Process>()
    private val lkPort = 27880; private val tokenPort = 28089
    private lateinit var deviceKey: String
    private val metrics = linkedMapOf<String, Any>()
    private val checks = linkedMapOf<String, Boolean>()

    private fun check(name: String, ok: Boolean) { checks[name] = ok; println((if (ok) "PASS " else "FAIL ") + name) }

    @BeforeAll fun startServers() {
        assumeTrue(lkBin != null && python != null && models != null, "LiveKit E2E environment not configured")
        File(work, "livekit.yaml").writeText("port: $lkPort\nbind_addresses: [127.0.0.1]\nrtc:\n  tcp_port: ${lkPort + 1}\n  port_range_start: 52000\n" +
            "  port_range_end: 52100\n  use_external_ip: false\n  node_ip: 127.0.0.1\nkeys:\n  devkey: e2e-secret-e2e-secret-e2e-secret-0001\nlogging:\n  level: warn\n")
        procs += ProcessBuilder(lkBin, "--config", File(work, "livekit.yaml").path).redirectErrorStream(true).redirectOutput(File(work, "livekit.log")).start()
        val env = mapOf("LIVEKIT_API_KEY" to "devkey", "LIVEKIT_API_SECRET" to "e2e-secret-e2e-secret-e2e-secret-0001",
            "CLU_PUBLIC_LIVEKIT_URL" to "ws://127.0.0.1:$lkPort", "CLU_INTERNAL_LIVEKIT_URL" to "ws://127.0.0.1:$lkPort",
            "CLU_DEVICES" to File(work, "devices.json").path, "CLU_MODELS" to models, "CLU_BIND" to "127.0.0.1", "CLU_PORT" to "$tokenPort")
        val add = ProcessBuilder(python, "-I", "clu_voice_server.py", "add-device", "phone").directory(serverDir).apply { environment().putAll(env) }.start()
        deviceKey = add.inputStream.bufferedReader().readLines().last().trim()
        assertEquals(0, add.waitFor())
        procs += ProcessBuilder(python, "-I", "clu_voice_server.py", "serve").directory(serverDir).apply { environment().putAll(env) }
            .redirectErrorStream(true).redirectOutput(File(work, "voice-server.log")).start()
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) {
            if (runCatching { (URL("http://127.0.0.1:$tokenPort/healthz").openConnection() as HttpURLConnection).responseCode == 200 }.getOrDefault(false)) break
            Thread.sleep(200)
        }
        // Synthetic owner speech (a different voice from Clu's) as 48 kHz 16-bit PCM.
        // Phrases the 20M streaming ASR model transcribes reliably from this voice, and that Clu's deterministic command
        // router does not answer by itself (so the reply really comes from the model path).
        val phrases = mapOf("howareyou" to "How are you doing today?", "story" to "Please tell me a long story.", "stop" to "Stop, wait a moment.",
            "tea" to "Do you remember my favourite tea, please?", "hello" to "Hello Clu, how are you?", "end" to "End of the line.")
        val script = """
import sys, json, numpy as np, sherpa_onnx
from pathlib import Path
d = Path(sys.argv[1]) / "vits-piper-en_GB-alan-medium"
tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(vits=sherpa_onnx.OfflineTtsVitsModelConfig(
    model=str(next(d.glob("*.onnx"))), tokens=str(d/"tokens.txt"), data_dir=str(d/"espeak-ng-data")), num_threads=2)))
for name, text in json.loads(sys.argv[3]).items():
    a = tts.generate(text, sid=0, speed=1.0); x = np.asarray(a.samples, dtype=np.float32)
    y = np.interp(np.linspace(0, len(x)-1, int(len(x)*48000/a.sample_rate)), np.arange(len(x)), x)
    (Path(sys.argv[2]) / (name + ".pcm")).write_bytes((np.clip(y, -1, 1) * 32767).astype("<i2").tobytes())
"""
        val json = phrases.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }
        val gen = ProcessBuilder(python, "-I", "-c", script, models, work.path, json).redirectErrorStream(true).start()
        val out = gen.inputStream.bufferedReader().readText()
        assertEquals(0, gen.waitFor(), out)
    }

    @AfterAll fun stopServers() {
        procs.reversed().forEach { p -> p.destroy(); if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly().waitFor() }
        System.getProperty("clu.lk.out")?.let { path ->
            fun q(v: Any) = if (v is Number || v is Boolean) v.toString() else "\"$v\""
            File(path).writeText("{\n  \"checks\": {" + checks.entries.joinToString(",") { "\n    \"${it.key}\": ${it.value}" } + "\n  },\n  \"metrics\": {" +
                metrics.entries.joinToString(",") { "\n    \"${it.key}\": ${q(it.value)}" } + "\n  }\n}\n")
        }
    }

    private fun pcm(name: String): ShortArray {
        val b = File(work, "$name.pcm").readBytes()
        return ShortArray(b.size / 2).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
    }

    /**
     * Continuous microphone like a real one: pushes 10 ms frames in real time - queued speech, otherwise silence.
     */
    private class MicPump(private val mic: JvmMicTrack) : Thread("mic-pump") {
        private val queue = java.util.concurrent.LinkedBlockingQueue<ShortArray>()
        @Volatile var running = true
        @Volatile var lastLoudPushedAt = 0L
        init { isDaemon = true }
        fun enqueue(frames: List<ShortArray>) = queue.addAll(frames)
        val pending get() = queue.size
        override fun run() {
            var t = System.nanoTime()
            val silence = ShortArray(480)
            while (running) {
                val f = queue.poll() ?: silence
                mic.push(f, 48000)
                if (f !== silence && f.any { kotlin.math.abs(it.toInt()) > 600 }) lastLoudPushedAt = System.nanoTime() / 1_000_000
                t += 10_000_000
                val sleep = t - System.nanoTime()
                if (sleep > 0) Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
            }
        }
    }
    private var pump: MicPump? = null

    /** Speaks [samples] through the continuous microphone; returns when played, with the time the speech ended. */
    private fun speak(mic: JvmMicTrack, samples: ShortArray, trailingSilenceMs: Int = 1200): Long {
        val p = pump ?: MicPump(mic).also { pump = it; it.start() }
        p.enqueue(samples.toList().chunked(480).map { c -> ShortArray(480) { k -> if (k < c.size) c[k] else 0 } })
        while (p.pending > 0) Thread.sleep(5)
        val endOfSpeech = p.lastLoudPushedAt
        Thread.sleep(trailingSilenceMs.toLong())
        return endOfSpeech
    }

    /** Waits until the agent has been idle ("listening", nothing playing) for [quietMs]. */
    private fun awaitIdle(events: List<BridgeEvent>, meter: AudioMeter, quietMs: Long = 800, ms: Long = 30_000): Boolean = await(ms) {
        val last = events.lastOrNull { it is BridgeEvent.AgentState } as BridgeEvent.AgentState?
        val now = System.nanoTime() / 1_000_000
        last?.state == "listening" && (meter.loudAt.lastOrNull()?.let { now - it > quietMs } ?: true)
    }

    private fun await(ms: Long = 20_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return false
    }

    @Test fun voiceConversationThroughSelfHostedLiveKit(): Unit = runBlocking {
        val platform = TestPlatform()
        val core = newCore(platform = platform)
        core.settings.set(Settings.VOICE_MODE, "livekit")
        core.settings.set(Settings.LK_TOKEN_URL, "http://127.0.0.1:$tokenPort/token", verified = true)
        val engine = ScriptedEngine()
        core.useEngine(engine)
        core.memories.save(Memory(category = MemoryCategory.MANUAL, title = "Car service", content = "The car service is due in March.", source = MemorySource.OWNER_SUPPLIED))
        core.memories.save(Memory(category = MemoryCategory.MANUAL, title = "Favourite tea", content = "The owner likes masala chai tea with less sugar.", source = MemorySource.OWNER_SUPPLIED))
        core.memories.save(Memory(category = MemoryCategory.MANUAL, title = "Gym", content = "Leg day is on Wednesdays.", source = MemorySource.OWNER_SUPPLIED))

        // 1. Path selection and token provisioning (device key, no LiveKit secret in the client).
        val tokens = TokenProvisioner(core.settings, JvmHttp(), { deviceKey }, System::currentTimeMillis)
        assertEquals(ConversationPath.LIVEKIT, VoiceModePolicy.select(core.settings, online = true, configured = tokens.isConfigured()).path)
        val room = "clu-" + deviceKey.substringBefore('.')
        val t0 = System.currentTimeMillis()
        val token = tokens.token(room, room, "Clu (phone)")
        metrics["token_request_ms"] = System.currentTimeMillis() - t0
        check("token issued by the owner's token server for the device's own room", token.room == room)

        // 2. Join with the Kotlin client, publish the microphone, wire the bridge to Clu's agent.
        val rtc = JvmRtcFactory()
        val meter = AudioMeter()
        val events = CopyOnWriteArrayList<BridgeEvent>()
        lateinit var lk: LiveKitRoom
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val conv = core.conversations.create(personaId = core.personas.active().id).id
        var agentIdentity: String? = null
        val bridge = RemoteVoiceBridge(core.agent, scope, { conv }, { core.auth.actorForSpeaker(null, speakerIdActive = false) },
            send = { t, p -> lk.sendData(t, p.toByteArray(), listOfNotNull(agentIdentity)) }, events = { events += it }, diag = core.diag)
        lk = LiveKitRoom(rtc, { ev ->
            when (ev) {
                is RoomEvent.ParticipantJoined -> if (ev.participant.isAgent) agentIdentity = ev.participant.identity
                is RoomEvent.Data -> if (ev.from != null && ev.from == agentIdentity) bridge.onData(ev.topic ?: "", String(ev.payload))
                is RoomEvent.RemoteAudio -> (ev.handle as? AudioTrack)?.addSink(meter)
                else -> {}
            }
        })
        val tJoin = System.currentTimeMillis()
        val joined = lk.connect(token.serverUrl, token.token)
        metrics["join_ms"] = System.currentTimeMillis() - tJoin
        check("client joined the room", joined.room == room && joined.identity == room)
        lk.publishMicrophone()
        check("voice agent joined and is identified as an agent", await { agentIdentity != null })
        val mic = rtc.mics.single()
        pump = MicPump(mic).also { it.start() }   // the microphone streams continuously from now on, like on a phone
        check("agent receives the device's audio (ready)", await { events.any { it is BridgeEvent.AgentState && it.state == "listening" } })
        delay(500)

        // 3. Turn: speech -> agent ASR -> Clu (local agent) -> streamed reply -> agent TTS -> audio back to the client.
        engine.reply("I am doing well, thank you. All systems are running smoothly.")
        val endSpeech = speak(mic, pcm("howareyou"))
        check("owner's speech transcribed and delivered to Clu", await { events.any { it is BridgeEvent.Heard && "how are you" in it.text } })
        val heard = events.filterIsInstance<BridgeEvent.Heard>().first()
        metrics["turn1_transcript"] = heard.text
        check("Clu's reply streamed to the agent", await { events.any { it is BridgeEvent.Replying } })
        check("reply audio received by the client", await { meter.loudAt.any { it > endSpeech } })
        meter.loudAt.firstOrNull { it > endSpeech }?.let { metrics["end_of_speech_to_first_reply_audio_ms"] = it - endSpeech }
        awaitIdle(events, meter)
        check("turn stored in Clu's conversation history", core.conversations.messages(conv).any { it.content.contains("running smoothly") })

        // 4. Memory: an owner-supplied memory reaches the local model's prompt through the voice path.
        engine.reply("You like masala chai with less sugar.")
        speak(mic, pcm("tea"))
        check("memory retrieved for a LiveKit voice turn", await { engine.prompts.any { p -> p.any { it.role == "system" && "masala chai" in it.content } } })
        awaitIdle(events, meter)

        // 5. Barge-in: owner talks over a long reply -> agent stops speaking and Clu cancels the reply.
        engine.reply("Once upon a time there was a robot named Clu who lived in a tablet. Every day it learned something new about its owner. " +
            "One day it decided to explore the whole house, room by room, very slowly, taking notes about everything it found.")
        speak(mic, pcm("story"))
        val speakingBefore = events.count { it is BridgeEvent.AgentState && it.state == "speaking" }
        check("long reply started speaking", await { events.count { it is BridgeEvent.AgentState && it.state == "speaking" } > speakingBefore - 1 &&
            (events.lastOrNull { it is BridgeEvent.AgentState } as BridgeEvent.AgentState?)?.state == "speaking" })
        delay(1200)
        val stop = pcm("stop")
        val firstSound = stop.indexOfFirst { kotlin.math.abs(it.toInt()) > 600 }
        val tStop = System.nanoTime() / 1_000_000 + firstSound / 48
        val stopJob = launch(Dispatchers.IO) { speak(mic, stop) }
        check("barge-in reached Clu (reply interrupted)", await(10_000) { events.any { it is BridgeEvent.Interrupted } })
        val tInt = System.nanoTime() / 1_000_000
        metrics["barge_in_speech_start_to_clu_interrupt_ms"] = tInt - tStop
        // The interrupted reply must go quiet: first >=300 ms of silence after the interrupt.
        delay(1500)
        var silentFrom: Long? = null; var quietStart: Long? = null
        for ((t, loud) in meter.frames.filter { it.first >= tInt }) {
            if (loud) quietStart = null else { if (quietStart == null) quietStart = t; if (t - quietStart >= 300) { silentFrom = quietStart; break } }
        }
        check("interrupted reply went silent at the client", silentFrom != null && silentFrom - tInt < 800)
        silentFrom?.let { metrics["interrupt_to_silence_at_client_ms"] = it - tInt }
        stopJob.join()

        // 6. Local llama.cpp engine (tiny random-weight model) answers a LiveKit turn: inference stays on the device.
        val model = File(System.getProperty("clu.testModel", "../native/build/test/tiny.gguf"))
        if (model.exists()) {
            core.remoteEngineProvider = null
            core.settings.set(Settings.REMOTE_INFERENCE, "local")
            core.settings.set(Settings.MODEL_ACTIVE, core.models.register(model).id)
            core.settings.set(Settings.CONTEXT_LENGTH, 2048); core.settings.set(Settings.MAX_TOKENS, 24)
            awaitIdle(events, meter, quietMs = 1500)
            val nBefore = events.count { it is BridgeEvent.Replying }
            speak(mic, pcm("hello"))
            check("local llama.cpp model answered the LiveKit turn", await(40_000) { events.count { it is BridgeEvent.Replying } > nBefore })
            val msgs = core.conversations.messages(conv)
            check("reply source is the on-device model", msgs.last().meta.source == "local")
            core.useEngine(engine)
            awaitIdle(events, meter, quietMs = 1500, ms = 60_000)
        }

        // 7. "End of the line": farewell spoken, agent reports completion, session ends.
        val nEndBefore = events.count { it is BridgeEvent.SessionEnded }
        speak(mic, pcm("end"))
        events.filterIsInstance<BridgeEvent.Heard>().forEach { println("heard turn ${it.turn}: ${it.text}") }
        check("end of the line ends the session after the farewell", await(20_000) { events.count { it is BridgeEvent.SessionEnded } > nEndBefore })
        pump?.running = false
        lk.disconnect().join()
        check("client disconnected cleanly", lk.closed)
        rtc.dispose()
        core.shutdown()
        metrics["checks_passed"] = checks.values.count { it }; metrics["checks_total"] = checks.size
        val failed = checks.filterValues { !it }.keys
        assertTrue(failed.isEmpty(), "failed: $failed (logs in ${work.path})")
    }
}
