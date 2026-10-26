package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Offline mode with no network at all (run inside a network namespace: scripts/test_offline.sh). A new voice
 * conversation runs through the same engines and models the APK bundles - sherpa-onnx streaming ASR (20M zipformer)
 * and Piper TTS (sherpa-onnx 1.13.8, here via its Python binding) and Clu's core with the real llama.cpp engine,
 * with the on-device engine selected ("This device"; there is no automatic fallback from the Clu server engine).
 *
 * Not covered here (needs the phone): Android microphone/speaker, the sherpa-onnx Android JNI build, the arm64 llama
 * build and its speed.
 */
class OfflineConversationTest {
    private val python = System.getProperty("clu.lk.python")
    private val models = System.getProperty("clu.lk.models")

    @Test fun newConversationCompletesWithoutNetwork(): Unit = runBlocking {
        assumeTrue(System.getProperty("clu.offline") == "true" && python != null && models != null, "offline environment not configured")
        // 0. Prove there is no network: no route to the internet, no DNS.
        val reach = runCatching { Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 3000) } }
        assertTrue(reach.isFailure, "this test must run without network access")
        assertTrue(runCatching { java.net.InetAddress.getByName("huggingface.co") }.isFailure, "DNS must be unavailable")

        val work = Files.createTempDirectory("clu-offline").toFile()
        // 1. Owner speech -> streaming ASR (bundled model) -> transcript.
        val helper = """
import sys, numpy as np, sherpa_onnx, time
from pathlib import Path
M = Path(sys.argv[1]); mode = sys.argv[2]
def tts(voice):
    d = M / voice
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(vits=sherpa_onnx.OfflineTtsVitsModelConfig(
        model=str(next(d.glob("*.onnx"))), tokens=str(d/"tokens.txt"), data_dir=str(d/"espeak-ng-data")), num_threads=2)))
if mode == "asr":
    a = tts("vits-piper-en_GB-alan-medium").generate(sys.argv[3], sid=0, speed=1.0); x = np.asarray(a.samples, dtype=np.float32)
    y = np.interp(np.linspace(0, len(x)-1, int(len(x)*16000/a.sample_rate)), np.arange(len(x)), x).astype(np.float32)
    d = M / "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
    rec = sherpa_onnx.OnlineRecognizer.from_transducer(tokens=str(d/"tokens.txt"), encoder=str(next(d.glob("encoder*.int8.onnx"))),
        decoder=str(next(d.glob("decoder*.int8.onnx"))), joiner=str(next(d.glob("joiner*.int8.onnx"))), num_threads=2, sample_rate=16000, feature_dim=80)
    s = rec.create_stream(); s.accept_waveform(16000, np.zeros(16000, dtype=np.float32))
    t0 = time.time()
    for i in range(0, len(y), 320):
        s.accept_waveform(16000, y[i:i+320])
        while rec.is_ready(s): rec.decode_stream(s)
    s.accept_waveform(16000, np.zeros(8000, dtype=np.float32)); s.input_finished()
    while rec.is_ready(s): rec.decode_stream(s)
    print(rec.get_result(s).strip().lower())
else:
    a = tts("vits-piper-en_US-ryan-medium").generate(sys.argv[3], sid=0, speed=1.0)
    x = np.asarray(a.samples, dtype=np.float32)
    print(len(x), a.sample_rate, float(np.abs(x).max()) if len(x) else 0.0)
"""
        fun py(vararg args: String): String {
            val p = ProcessBuilder(listOf(python, "-I", "-c", helper, models) + args).redirectErrorStream(false).start()
            val out = p.inputStream.bufferedReader().readText().trim(); val err = p.errorStream.bufferedReader().readText()
            assertEquals(0, p.waitFor(), err.takeLast(800)); return out
        }
        val transcript = py("asr", "How are you doing today?")
        assertTrue("how are you" in transcript, "offline ASR transcript: '$transcript'")

        // 2. The on-device engine is selected (the default) and the platform reports no network.
        val platform = TestPlatform().apply { online = false }
        val core = newCore(platform = platform)
        assertEquals("offline", core.settings.string(Settings.VOICE_MODE))

        // 3. New conversation with the real llama.cpp engine (tiny random-weight model: plumbing, not answer quality).
        val model = File(System.getProperty("clu.testModel", "../native/build/test/tiny.gguf"))
        assertTrue(model.exists(), "test model missing: $model")
        core.settings.set(Settings.MODEL_ACTIVE, core.models.register(model).id)
        core.settings.set(Settings.CONTEXT_LENGTH, 2048); core.settings.set(Settings.MAX_TOKENS, 24)
        val conv = core.conversations.create(personaId = core.personas.active().id)
        val actor = core.auth.actorForSpeaker(null, speakerIdActive = false)!!
        val t0 = System.currentTimeMillis()
        val events = core.agent.respond(TurnRequest(conv.id, transcript, actor, viaVoice = true)).toList()
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        val llmMs = System.currentTimeMillis() - t0
        assertEquals("local", done.meta.source, "answered by the on-device model")
        assertTrue(done.text.isNotBlank())
        assertEquals(2, core.conversations.messages(conv.id).size, "user turn and reply stored")
        assertTrue(platform.fakeHttp.requests.isEmpty(), "no HTTP request was attempted: ${platform.fakeHttp.requests}")

        // 4. Reply -> Piper TTS (Clu voice).
        val (samples, rate, peak) = py("tts", "I am doing well, thank you.").split(" ")
        assertTrue(samples.toInt() > rate.toInt() / 2 && peak.toDouble() > 0.05, "speech synthesised offline")
        println("offline conversation OK: transcript='$transcript', model reply ${done.text.length} chars in $llmMs ms (tiny model, x86 host), " +
            "TTS ${"%.2f".format(samples.toDouble() / rate.toDouble())} s audio")
        File(work, "result.txt").writeText("ok")
        core.shutdown()
    }
}
