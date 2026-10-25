package ai.clu.core

import ai.clu.core.diag.DiagnosticsLog
import ai.clu.core.stt.WhisperBridge
import ai.clu.core.stt.WhisperEngine
import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * whisper.cpp through Clu's JNI bridge on the host CPU: accuracy (CER) and latency for English, Hindi and mixed clips.
 * Opt-in: -Dclu.whisperModels=<dir with ggml-base*.bin / ggml-small*.bin> -Dclu.sttFixtures=<scripts/make_stt_fixtures.py out>.
 * Host x86 numbers, not phone numbers.
 */
class WhisperIntegrationTest {
    private val lib = File(System.getProperty("clu.whisperLib", "../native/build/host/libclu_whisper.so"))
    private val models = System.getProperty("clu.whisperModels")?.let(::File)
    private val fixtures = System.getProperty("clu.sttFixtures")?.let(::File)

    private fun ready() {
        assumeTrue(lib.isFile && models?.isDirectory == true && fixtures?.isDirectory == true, "whisper models/fixtures not provided")
        WhisperBridge.load(lib.absolutePath)
    }

    private fun wav(f: File): FloatArray {
        val b = f.readBytes(); val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4); val len = bb.getInt(pos + 4)
            if (id == "data") return FloatArray(len / 2) { bb.getShort(pos + 8 + it * 2) / 32768f }
            pos += 8 + len
        }
        error("no data chunk in $f")
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() || it in 'ऀ'..'ॿ' }
        .replace(Regex("\\s+"), " ").trim()

    /** Character error rate (Levenshtein on normalised text). */
    private fun cer(ref: String, hyp: String): Double {
        val a = norm(ref); val b = norm(hyp)
        if (a.isEmpty()) return if (b.isEmpty()) 0.0 else 1.0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length].toDouble() / a.length
    }

    private fun p(v: List<Double>, q: Int) = DiagnosticsLog.percentile(v.sorted(), q)

    @Test fun accuracyAndLatencyEnglishHindiMixed() {
        ready()
        val clips = Json.parseToJsonElement(File(fixtures, "manifest.json").readText()).jsonArray.map { it.jsonObject }
        val results = buildJsonArray {
            for (mf in models!!.listFiles()!!.filter { it.name.endsWith(".bin") }.sortedBy { it.length() }) {
                val tLoad = System.nanoTime()
                WhisperEngine.open(mf.path).use { eng ->
                    val loadMs = (System.nanoTime() - tLoad) / 1e6
                    for (c in clips) {
                        val pcm = wav(File(fixtures, c["file"]!!.jsonPrimitive.content))
                        val clipLang = c["lang"]!!.jsonPrimitive.content
                        val langs = if (clipLang == "mixed") listOf("hi", "auto") else listOf(clipLang)
                        for (lang in langs) for ((fast, fallback) in listOf(true to false, true to true, false to false)) {
                            eng.transcribe(pcm, lang, threads = 4, fastShortAudio = fast, fallback = fallback) // warm-up
                            val runs = (0 until 3).map { eng.transcribe(pcm, lang, threads = 4, fastShortAudio = fast, fallback = fallback)!! }
                            val ref = c["ref"]!!.jsonPrimitive.content
                            val r = runs.last()
                            val e = cer(ref, r.text)
                            println("${mf.name} ${c["name"]!!.jsonPrimitive.content} lang=$lang fast=$fast fb=$fallback cer=${"%.2f".format(e)} " +
                                "p50=${p(runs.map { it.totalMs }, 50)}ms enc=${r.encodeMs} '${r.text}'")
                            add(buildJsonObject {
                                put("model", mf.name); put("load_ms", loadMs); put("clip", c["name"]!!); put("kind", c["kind"]!!)
                                put("audio_s", c["seconds"]!!); put("lang", lang); put("fast_audio_ctx", fast); put("temperature_fallback", fallback)
                                put("total_ms_p50", p(runs.map { it.totalMs }, 50)); put("total_ms_max", runs.maxOf { it.totalMs })
                                put("encode_ms", r.encodeMs); put("cer", e); put("text", r.text); put("ref", ref)
                            })
                        }
                    }
                }
            }
        }
        System.getProperty("clu.whisperOut")?.let { File(it).writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), results)) }
        // Sanity: English real speech must be recognised well by every model.
        val en = results.map { it.jsonObject }.filter { it["clip"]!!.jsonPrimitive.content == "en_real_short" && !it["fast_audio_ctx"]!!.jsonPrimitive.boolean }
        assertTrue(en.all { it["cer"]!!.jsonPrimitive.double < 0.1 }, "$en")
    }

    @Test fun abortStopsATranscriptionQuickly() {
        ready()
        val mf = models!!.listFiles()!!.filter { it.name.endsWith(".bin") }.maxBy { it.length() }
        val pcm = wav(File(fixtures, "en_real_long.wav"))
        WhisperEngine.open(mf.path).use { eng ->
            // Abort during decoding (after the encoder, which whisper.cpp cannot interrupt): stops at the next token.
            val enc = eng.transcribe(pcm, "en", threads = 4)!!.encodeMs
            var result: WhisperEngine.Result? = WhisperEngine.Result("", "", 0.0, 0.0)
            val t = Thread { result = eng.transcribe(pcm, "en", threads = 4) }
            t.start(); Thread.sleep(enc.toLong() + 150)
            val t0 = System.nanoTime(); eng.abort(); t.join(10_000)
            val ms = (System.nanoTime() - t0) / 1e6
            println("abort -> returned in ${"%.0f".format(ms)} ms during decode (${mf.name}, encoder ${enc} ms)")
            assertFalse(t.isAlive); assertNull(result, "aborted transcription returns null")
            assertTrue(ms < 300, "abort took $ms ms")
            assertNotNull(eng.transcribe(pcm.copyOf(32_000), "en"), "engine usable after abort")
        }
    }
}
