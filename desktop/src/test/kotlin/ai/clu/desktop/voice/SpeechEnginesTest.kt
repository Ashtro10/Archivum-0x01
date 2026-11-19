package ai.clu.desktop.voice

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

/** The speech engines through the real sherpa-onnx libraries and the models installed with CLU: speak, hear it back, find speech, spot wake words. */
class SpeechEnginesTest {
    private val models = SpeechModels(SpeechModels.locate(), File(System.getProperty("java.io.tmpdir"), "clu-voice-user"))
    private fun need() = assumeTrue(NativeVoice.load() && models.bundled != null && models.vad != null, "voice models or native build missing (scripts/prepare_voice_models.sh, scripts/build_voice_native.sh)")
    private fun voice(id: String) = VoiceCatalog.voice(id)!!.let { v -> TtsVoice(VoiceCatalog.pack(v.pack)!!, models.packDir(v.pack)!!, models.espeak()) }
    private fun words(s: String) = s.lowercase().replace(Regex("[^a-z ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }

    @Test fun whatTheVoiceSaysTheRecogniserHears() {
        need()
        voice("joe").use { tts ->
            val t0 = System.nanoTime(); val audio = tts.synth("Hello there. What is the weather like in London today?", 0, 1.0f); val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals(22050, tts.sampleRate); assertTrue(audio.size > tts.sampleRate, "at least a second of speech"); assertTrue(Pcm.rms(audio) > 0.02)
            println("[speech] Joe synthesised ${audio.size * 1000 / tts.sampleRate} ms of audio in $ms ms (RTF %.2f)".format(ms / (audio.size * 1000.0 / tts.sampleRate)))
            val pcm16 = Pcm.resample(audio, 22050, 16000)
            StreamingRecognizer(models.kroko!!).use { stt ->
                stt.begin(FloatArray(0)); var partial = ""
                pcm16.toList().chunked(1600).forEach { c -> stt.accept(c.toFloatArray()); partial = stt.partial() }
                val t1 = System.nanoTime(); val text = stt.finish(); val fin = (System.nanoTime() - t1) / 1_000_000
                println("[speech] partial before the end: \"$partial\"; final: \"$text\" (finalised in $fin ms)")
                val w = words(text); val expected = listOf("hello", "weather", "london", "today")
                assertTrue(expected.count { it in w } >= 3, "recognised \"$text\""); assertTrue(partial.isNotBlank(), "words appeared while the speech was still coming in")
            }
            Vad(models.vad!!).use { vad ->
                val segs = vad.accept(pcm16 + FloatArray(16000)); assertTrue(segs.isNotEmpty(), "speech found"); assertFalse(vad.speaking, "and its end")
                assertEquals(0, Vad(models.vad!!).use { it.accept(FloatArray(48000)).size }, "silence is not speech")
            }
        }
    }

    @Test fun everyBundledVoiceSpeaksAndSoundsDifferent() {
        need()
        val medians = mutableMapOf<String, Double>()
        for (id in listOf("joe", "alan", "ryan", "kitten-0", "kitten-3")) {
            val v = VoiceCatalog.voice(id)!!
            TtsVoice(VoiceCatalog.pack(v.pack)!!, models.packDir(v.pack)!!, models.espeak()).use { tts ->
                val a = tts.synth("I am CLU. How can I help you this evening?", v.speaker, 1.0f)
                assertTrue(a.size > tts.sampleRate / 2 && Pcm.rms(a) > 0.01, "$id produced speech")
                medians[id] = ai.clu.core.voice.PitchAnalysis.stats(a, tts.sampleRate)?.medianHz ?: 0.0
            }
        }
        println("[speech] median pitch (Hz): $medians")
        assertTrue(medians.values.distinct().size >= 4, "the voices really differ: $medians")
    }

    /** ONNX Runtime keeps memory per input shape (measured +0.7 MB per English sentence); a worn voice is replaced between turns. */
    @Test fun aWornVoiceIsReplacedByAFreshOneAndTheOldOneIsSafelyClosed() {
        need()
        val engines = VoiceEngines(models, { VoiceSettings() })
        try {
            val (v, sp) = engines.voice("joe")
            repeat(4) { assertTrue(v.synth("Sentence number $it.", sp, 1f).isNotEmpty()) }
            assertEquals(4, v.uses)
            engines.recycleWorn(limit = 3)
            val (fresh, _) = engines.voice("joe")
            assertNotSame(v, fresh); assertTrue(v.closed); assertEquals(0, fresh.uses)
            assertTrue(v.synth("Too late.", sp, 1f).isEmpty(), "a closed voice returns nothing instead of touching freed memory")
            assertTrue(fresh.synth("Still speaking.", sp, 1f).isNotEmpty())
            engines.recycleWorn(limit = 3); assertSame(fresh, engines.voice("joe").first, "a voice with few uses is kept")
        } finally { engines.unload() }
    }

    @Test fun speedChangesTheSpeakingRate() {
        need()
        voice("joe").use { tts ->
            val normal = tts.synth("This sentence is spoken at different speeds.", 0, 1.0f).size
            val fast = tts.synth("This sentence is spoken at different speeds.", 0, 1.4f).size
            assertTrue(fast < normal * 0.85, "faster is shorter: $fast vs $normal")
        }
    }

    /** Wake words are recognised speech that starts with a wake phrase (the keyword-spotting model was tried and missed isolated words; see docs/VOICE_MODELS.md). */
    @Test fun wakePhrasesAreFoundInRecognisedSpeechWithTheCommandThatFollows() {
        need()
        val words = ai.clu.core.voice.WakeWord.defaults()
        voice("joe").use { tts -> StreamingRecognizer(models.kroko!!).use { stt ->
            // Fed the way VoiceAgent feeds it from a real microphone: primed with 1 s of digital silence, then the pre-roll
            // (room-level noise, never exact zeros), the utterance, and the endpoint's 700 ms of quiet before finish().
            // The earlier version fed exact zeros around the speech and no endpoint silence; that made the recogniser drop
            // the first or last word intermittently (Phase 5: 12 of 45 utterances), which a microphone never produces.
            val rnd = java.util.Random(7)
            fun room(n: Int) = FloatArray(n) { (rnd.nextGaussian() * 0.003).toFloat() }
            fun hear(phrase: String): String { stt.begin(FloatArray(16000)); (room(4800) + Pcm.resample(tts.synth(phrase, 0, 1.0f), 22050, 16000) + room(11200)).toList().chunked(1600).forEach { stt.accept(it.toFloatArray()) }; return stt.finish() }
            val heard = listOf("Clue, what time is it?", "Hey buddy, turn the volume down.", "Friend, are you there?").map { hear(it) }
            val matches = heard.map { ai.clu.core.voice.WakePhraseMatcher.match(it, words, 0.7) }
            println("[speech] heard $heard -> ${matches.map { it?.word?.phrase + " / " + it?.remainder }}")
            assertTrue(matches.count { it != null } >= 2, "wake phrases found: $heard")
            assertTrue(matches.firstOrNull { it?.word?.phrase == "Clue" }?.remainder?.contains("time") == true || matches.count { it != null } == 3, "the command after the wake word is kept")
            assertNull(ai.clu.core.voice.WakePhraseMatcher.match(hear("The table is made of oak and walnut."), words, 0.7), "an ordinary sentence does not wake CLU")
            val farewell = hear("Okay, goodbye for now."); assertTrue(VoiceSettings().isFarewell(farewell), "the farewell phrase is recognised: $farewell")
            assertFalse(VoiceSettings().isFarewell(hear("Say goodbye to the old router before you set up the new one.")), "a sentence that merely contains the word is not a farewell")
        } }
    }

    /**
     * Diagnosis for the intermittent wake-phrase failure: the same pipeline repeated, recording what the voice produced,
     * what the recogniser heard and what the matcher kept. Opt-in: -Dclu.wakeRepeat=<n> [-Dclu.wakeRepeatOut=<json>].
     */
    @Test fun wakePhraseRepeatability() {
        val n = System.getProperty("clu.wakeRepeat")?.toIntOrNull(); assumeTrue(n != null, "opt-in"); need()
        val words = ai.clu.core.voice.WakeWord.defaults()
        val phrases = (System.getProperty("clu.wakePhrases")?.split('|') ?: listOf("Clue, what time is it?", "Hey buddy, turn the volume down.", "Friend, are you there?"))
        // Background noise over the whole signal (-Dclu.wakeNoise=<rms>, e.g. 0.01 quiet room fan, 0.03 busy room, 0.06 loud).
        val noiseRms = System.getProperty("clu.wakeNoise")?.toDoubleOrNull() ?: 0.0
        val rows = mutableListOf<String>()
        System.getProperty("clu.asrTail")?.toDoubleOrNull()?.let { asrTailSeconds = it }
        voice("joe").use { tts -> StreamingRecognizer(models.kroko!!).use { stt ->
            for (i in 1..n!!) for (p in phrases) {
                val audio = tts.synth(p, 0, 1.0f)
                // How the utterance starts (-Dclu.wakeHead): zeros = digital silence (as the test above); noise = room-level noise, as a real microphone gives.
                val hr = java.util.Random(1000L + i)
                fun quiet(n: Int) = if (System.getProperty("clu.wakeHead", "zeros") == "noise") FloatArray(n) { (hr.nextGaussian() * 0.003).toFloat() } else FloatArray(n)
                val nr = java.util.Random(2000L + i)
                val speech = (quiet(4800) + Pcm.resample(audio, 22050, 16000)).let { a -> if (noiseRms > 0) FloatArray(a.size) { k -> a[k] + (nr.nextGaussian() * noiseRms).toFloat() } else a }
                stt.begin(quiet(16000)); speech.toList().chunked(1600).forEach { stt.accept(it.toFloatArray()) }
                // Variants of how the utterance ends (-Dclu.wakeTail): none = as VoiceAgent does today; zeros = 1.5 s more silence; noise = 1.5 s of room-level noise.
                val rnd = java.util.Random(i.toLong())
                when (System.getProperty("clu.wakeTail", "none")) {
                    "zeros" -> stt.accept(FloatArray(24000))
                    "noise" -> stt.accept(FloatArray(24000) { (rnd.nextGaussian() * 0.003).toFloat() })
                    "endpoint" -> stt.accept(FloatArray(11200) { (rnd.nextGaussian() * 0.003).toFloat() }) // the 700 ms of room noise VoiceAgent hears before it ends the utterance
                }
                val tf = System.nanoTime(); val heard = stt.finish(); val finishMs = (System.nanoTime() - tf) / 1_000_000
                val m = ai.clu.core.voice.WakePhraseMatcher.match(heard, words, 0.7)
                // A fingerprint of the synthesised audio shows whether the voice itself varies between runs.
                val fp = "%d/%.5f".format(audio.size, Pcm.rms(audio))
                rows += """{"run":$i,"said":"$p","audio":"$fp","heard":"${heard.replace("\"", "'")}","wake":"${m?.word?.phrase ?: ""}","window":"${m?.heard ?: ""}","remainder":"${m?.remainder ?: ""}","score":${m?.score ?: 0.0},"finish_ms":$finishMs,"tail":"${System.getProperty("clu.wakeTail", "none")}","head":"${System.getProperty("clu.wakeHead", "zeros")}","asr_tail_s":$asrTailSeconds,"noise_rms":$noiseRms,"speech_rms":${"%.4f".format(Pcm.rms(audio))}}"""
                println("[wake-repeat] " + rows.last())
            }
        } }
        System.getProperty("clu.wakeRepeatOut")?.let { File(it).apply { parentFile?.mkdirs() }.writeText("[\n" + rows.joinToString(",\n") + "\n]\n") }
    }

    @Test fun resamplingKeepsTheSoundAndTheLength() {
        val sr = 22050; val tone = FloatArray(sr) { kotlin.math.sin(2 * Math.PI * 440 * it / sr).toFloat() * 0.5f }
        val up = Pcm.resample(tone, sr, 24000); assertTrue(kotlin.math.abs(up.size - 24000) <= 2); assertEquals(Pcm.rms(tone), Pcm.rms(up), 0.03)
        val wav = Pcm.wav(Pcm.toShort(up), 24000); assertEquals("RIFF", String(wav, 0, 4)); assertEquals(44 + 48000, wav.size)
    }
}
