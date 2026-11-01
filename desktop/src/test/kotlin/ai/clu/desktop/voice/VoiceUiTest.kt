package ai.clu.desktop.voice

import ai.clu.core.config.Settings
import ai.clu.core.llm.*
import ai.clu.desktop.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * The desktop window talking to CLU in a real browser: Chromium (Playwright) runs the shell, its microphone is a
 * synthesised spoken question (Chromium's fake capture device), it joins CLU's own livekit-server with the window's
 * token and the bundled livekit-client, and CLU answers through the real VAD, Kroko recognition, conversation manager
 * (scripted model) and Piper synthesis. Settings → Voice is exercised too: voice list, real preview, download consent,
 * selecting a voice. Opt-in like the other UI test: `-Dclu.shellUi=<node>`.
 */
class VoiceUiTest {
    private class Scripted : LlmEngine {
        val prompts = CopyOnWriteArrayList<List<ChatMessage>>()
        override val id = "scripted"; override val source = "local"
        override fun isReady() = true
        override fun countTokens(messages: List<ChatMessage>) = messages.sumOf { it.content.length / 4 + 4 }
        override fun contextSize() = 8192
        override fun cancel() {}
        override fun generate(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit): GenResult {
            prompts += messages
            val q = messages.last().content.lowercase()
            val text = if ("introduce" in q) "I am CLU, your assistant, running on this computer." else if ("what did i ask" in q) "You asked me to introduce myself." else "Hello."
            text.split(" ").forEach { onText("$it "); Thread.sleep(5) }
            return GenResult(text, GenStats(promptTokens = 50, generatedTokens = 12, stopReason = "eog"), source)
        }
    }

    @Test fun theWindowTalksToCLUThroughItsOwnLiveKitServer() {
        val node = System.getProperty("clu.shellUi"); assumeTrue(!node.isNullOrBlank(), "opt-in: -Dclu.shellUi=<node>")
        val models = SpeechModels.locate(); assumeTrue(models != null && NativeVoice.load(), "voice build missing")
        val script = listOf(File("src/test/ui/voice.ui.js"), File("desktop/src/test/ui/voice.ui.js")).first { it.exists() }
        val shots = File(System.getProperty("clu.shellUiShots") ?: "build/shell-ui").apply { mkdirs() }
        val dir = Files.createTempDirectory("clu-voice-ui").toFile()
        // The "microphone": 3 s of silence, the question in a voice that is not CLU's, then silence (Chromium plays it once).
        val mouth = SpeechModels(models, dir).let { m -> TtsVoice(VoiceCatalog.pack("piper-ryan")!!, m.packDir("piper-ryan")!!, m.espeak()) }
        val q = mouth.use { Pcm.resample(it.synth("Please introduce yourself in one sentence.", 0, 1.0f), it.sampleRate, 48_000) }
        val mic = File(dir, "mic.wav"); mic.writeBytes(Pcm.wav(ShortArray(48_000 * 3) + Pcm.toShort(q) + ShortArray(48_000 * 40), 48_000))
        val host = Host.start(DesktopPaths(File(dir, "home")), HostConfig(maintenanceMinutes = 0, governorIntervalSeconds = 600), port = 0, log = { println("[host] $it") })
        try {
            val model = Scripted(); host.core.remoteEngineProvider = { model }; host.core.settings.set(Settings.REMOTE_INFERENCE, "prefer_home")
            host.core.settings.set(Settings.SEARCH_READ_PAGES, false); host.core.settings.set(Settings.NET_ENABLED, false)
            host.voice!!.store.put(host.voice!!.settings.copy(endSilenceMs = 600))
            val p = ProcessBuilder(node, script.absolutePath, host.url, host.api.issueLaunchCode(), shots.absolutePath, mic.absolutePath).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it); println("[ui] $it") } }.apply { start() }
            val finished = p.waitFor(150, TimeUnit.SECONDS); if (!finished) p.destroyForcibly(); reader.join(2000)
            assertTrue(finished, "UI script timed out:\n$out"); assertEquals(0, p.exitValue(), "UI script failed:\n$out")
            // The voice turn and the typed follow-up are one conversation, and the typed turn saw the spoken one.
            val conv = host.core.conversations.list().first()
            val msgs = host.core.conversations.messages(conv.id).map { it.role.name + ": " + it.content }
            assertTrue(msgs.any { it.startsWith("USER") && "introduce" in it.lowercase() } && msgs.any { it.startsWith("ASSISTANT") && "running on this computer" in it } && msgs.any { "what did i ask" in it.lowercase() }, msgs.toString())
            assertTrue(model.prompts.last().any { "introduce" in it.content.lowercase() }, "the typed turn had the spoken one in its context")
            assertEquals("alan", host.voice!!.settings.voice, "the voice chosen in the window was saved")
            assertTrue(host.voice!!.downloads().isEmpty(), "declining the download downloaded nothing")
        } finally { host.stop(); dir.deleteRecursively() }
    }
}
