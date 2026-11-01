package ai.clu.desktop.voice

import ai.clu.core.agent.ProperNouns
import ai.clu.core.agent.SaidCorrection
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

/**
 * The M8.5 utterances through the real recogniser (Kroko streaming, fed exactly the way VoiceAgent feeds it), spoken by
 * several synthetic voices, then through the contextual name correction with "Leman Russ" known from the conversation.
 * Records what was heard (raw) and what CLU would understand. `-Dclu.voiceMeasureOut=<file.json>` writes the table.
 */
class VoiceRecognitionTest {
    private val models = SpeechModels(SpeechModels.locate(), File(System.getProperty("java.io.tmpdir"), "clu-voice-user"))
    private val utterances = listOf("Who is Leman Russ?", "Can you search who is Leman Russ?", "I said Leman Russ.", "No, I said talking.", "Answer me.", "Can you hear me?")
    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z ]"), "").replace(Regex("\\s+"), " ").trim()

    @Test fun theUtterancesAreRecognisedAndOnlyTheNameIsCorrected() {
        assumeTrue(NativeVoice.load() && models.kroko != null, "voice build missing")
        val voices = listOf("piper-ryan" to 0, "piper-alan" to 0, "kitten-nano" to 2, "piper-joe" to 0)
        val rows = mutableListOf<JsonObject>()
        StreamingRecognizerModel(models.kroko!!).use { model ->
            for ((pack, speaker) in voices) {
                val dir = models.packDir(pack) ?: continue
                TtsVoice(VoiceCatalog.pack(pack)!!, dir, models.espeak()).use { tts ->
                    for (u in utterances) {
                        val pcm = Pcm.resample(tts.synth(u, speaker, 1.0f), tts.sampleRate, 16_000)
                        val heard = model.session().use { s -> s.begin(FloatArray(16_000)); (FloatArray(4800) + pcm + FloatArray(8000)).toList().chunked(1600).forEach { s.accept(it.toFloatArray()) }; s.finish() }
                        val said = SaidCorrection.parse(heard)
                        val understood = ProperNouns.correct(heard, listOf("Leman Russ"))?.takeIf { it.confident }?.text ?: heard
                        rows += buildJsonObject { put("voice", "$pack/$speaker"); put("said", u); put("heard", heard); put("understood", understood); said?.let { put("correctionOf", it) } }
                        println("[stt] $pack/$speaker  \"$u\" → \"$heard\"" + if (understood != heard) "  ⇒ \"$understood\"" else "")
                    }
                }
            }
        }
        System.getProperty("clu.voiceMeasureOut")?.let { File(it).apply { parentFile?.mkdirs() }.writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows))) }
        assertTrue(rows.isNotEmpty())
        fun row(r: JsonObject, k: String) = r[k]!!.jsonPrimitive.content
        // Plain phrases are heard, and the correction never touches them.
        for (p in listOf("Answer me.", "Can you hear me?")) {
            val rs = rows.filter { row(it, "said") == p }
            assertTrue(rs.count { norm(row(it, "heard")) == norm(p) } >= rs.size - 1, "\"$p\" recognised: ${rs.map { row(it, "heard") }}")
            assertTrue(rs.all { row(it, "heard") == row(it, "understood") }, "never rewritten: $rs")
        }
        // "I said …" is always understood as a correction.
        assertTrue(rows.filter { row(it, "said").contains("I said") }.all { it["correctionOf"] != null }, rows.filter { row(it, "said").contains("I said") }.toString())
        // With the name known, it is understood correctly at least as often as it is heard correctly, and more often overall.
        val name = rows.filter { "Leman Russ" in row(it, "said") }
        val heardRight = name.count { "leman russ" in norm(row(it, "heard")) }; val understoodRight = name.count { "leman russ" in norm(row(it, "understood")) }
        println("[stt] Leman Russ heard right $heardRight/${name.size}, understood right with context $understoodRight/${name.size}")
        assertTrue(understoodRight >= heardRight && understoodRight >= name.size / 2, "context improves the name: heard $heardRight, understood $understoodRight of ${name.size}")
    }
}
