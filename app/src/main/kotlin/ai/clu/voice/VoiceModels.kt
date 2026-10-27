package ai.clu.voice

import ai.clu.core.diag.DiagnosticsLog
import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Silence appended when finalising a live-recogniser utterance (Kroko needs ~1.5 s to emit the last word). */
const val ASR_TAIL_SEC = 1.5f

/** A downloadable/importable model archive from the sherpa-onnx releases. */
data class VoicePack(
    val id: String,
    val title: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
    /** Archive entries to keep (path suffix -> local name). Empty = single-file download kept as [singleName]. */
    val keep: Map<String, String> = emptyMap(),
    val keepDirPrefix: String? = null,
    val singleName: String? = null,
    val kind: String,
    /** Speaker id to select with this pack (multi-speaker models), or null. */
    val defaultSpeaker: Int? = null,
)

object VoicePacks {
    private const val R = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
    private fun piper(id: String, title: String, model: String, sha: String, bytes: Long, speaker: Int? = null) =
        VoicePack(id, title, "$R/tts-models/vits-piper-$model.tar.bz2", sha, bytes,
            keep = mapOf("$model.onnx" to "model.onnx", "tokens.txt" to "tokens.txt"), keepDirPrefix = "espeak-ng-data/", kind = "tts", defaultSpeaker = speaker)

    // English voices, deepest measured first (see TtsSelection.PIPER_VOICES for the measured pitch).
    val TTS_JOE = piper("tts-joe", "Joe (US male, deep, Piper medium)", "en_US-joe-medium",
        "261b903a1fb2ce621e2b5b14233cd41223c3b9f9590f896c11daee4df22d9bd3", 67_169_394)
    val TTS_ARCTIC = piper("tts-arctic-rms", "Arctic rms (US male, deep, Piper medium, 18-speaker model)", "en_US-arctic-medium",
        "caf83305b2fecc84491e9581f31ef2c81d4e1dacb8b76a4e481ad3fb6d4bf25c", 80_255_511, speaker = 1)
    val TTS_NORTHERN = piper("tts-northern-male", "Northern English male (UK, Piper medium)", "en_GB-northern_english_male-medium",
        "2bb2c1e709f58c11f17c693b3b38f500e110e7f54f2651774ec48b8d41f12c55", 67_210_490)
    val TTS_RYAN = piper("tts-ryan", "Ryan (US male, higher pitch, Piper medium)", "en_US-ryan-medium",
        "c546af78b6395b4e7c4ce1ed899438b64426a362f5d4ec5fecd090ded9ad7505", 67_213_100)
    val TTS_ALAN = piper("tts-alan", "Alan (UK male, Piper medium)", "en_GB-alan-medium",
        "a48d4017da0f77668b27bed63fe6e04dd64c6397e1fadad4f460efb0ef7c9012", 67_220_121)
    // KittenTTS nano v0.8 int8 (Apache-2.0), English only, 8 speakers.
    val TTS_KITTEN = VoicePack("tts-kitten-nano", "KittenTTS nano v0.8 (English only, int8)", "$R/tts-models/kitten-nano-en-v0_8-int8.tar.bz2",
        "6fa5be852612ce761094ba74ee6123b4fc4acfefa79bf64dc63acae4a83af2fd", 31_220_690,
        keep = mapOf("model.int8.onnx" to "model.onnx", "voices.bin" to "voices.bin", "tokens.txt" to "tokens.txt"),
        keepDirPrefix = "espeak-ng-data/", kind = "tts-kitten")
    // Kokoro-82M v0.19 (Apache-2.0), fp32: speaker 10 = bm_lewis, deep British male (~84 Hz median, the most varied
    // intonation measured). int8 was 3x slower than fp32 on the build host (RTF 1.5 vs 0.54), so the fp32 model is used.
    val TTS_KOKORO = VoicePack("tts-kokoro-en", "Lewis (Kokoro-82M, English)", "$R/tts-models/kokoro-en-v0_19.tar.bz2",
        "912804855a04745fa77a30be545b3f9a5d15c4d66db00b88cbcd4921df605ac7", 319_625_534,
        keep = mapOf("model.onnx" to "model.onnx", "voices.bin" to "voices.bin", "tokens.txt" to "tokens.txt"),
        keepDirPrefix = "espeak-ng-data/", kind = "tts-kokoro", defaultSpeaker = 10)
    // Hindi (Devanagari) voice, male, ~103 Hz median pitch. Training data licensed CC BY-NC-SA 4.0 (non-commercial).
    val TTS_HI_PRATHAM = VoicePack("tts-hi-pratham", "Hindi voice – Pratham (male, Piper medium; non-commercial licence)",
        "$R/tts-models/vits-piper-hi_IN-pratham-medium.tar.bz2", "2084d321e1d2752f2b64ed3012ba27751df01a80da46f52920098cdcb7e35648", 67_238_438,
        keep = mapOf("hi_IN-pratham-medium.onnx" to "model.onnx", "tokens.txt" to "tokens.txt"), keepDirPrefix = "espeak-ng-data/", kind = "tts-hi")
    val SPEAKER = VoicePack("speaker-campplus", "Speaker recognition (3D-Speaker CAM++)", "$R/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
        "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b", 29_596_978, singleName = "model.onnx", kind = "speaker")
    val all = listOf(TTS_JOE, TTS_KOKORO, TTS_ARCTIC, TTS_NORTHERN, TTS_ALAN, TTS_RYAN, TTS_HI_PRATHAM, TTS_KITTEN, SPEAKER)
    fun byId(id: String?): VoicePack? = all.firstOrNull { it.id == id }
}

/** Downloads (only on request), imports, verifies and extracts voice packs into app storage. */
class VoicePackManager(private val context: Context, private val diag: DiagnosticsLog?) {
    val root = File(context.filesDir, "voice").apply { mkdirs() }
    fun dir(p: VoicePack) = File(root, p.id)
    fun isInstalled(p: VoicePack) = File(dir(p), ".installed").exists()
    fun installedIds(): Set<String> = VoicePacks.all.filter { isInstalled(it) }.map { it.id }.toSet()
    fun sizeOnDisk(p: VoicePack): Long = dir(p).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Resumable HTTP download with SHA-256 verification. [progress] receives (downloaded, total). */
    suspend fun download(p: VoicePack, progress: (Long, Long) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val part = File(context.cacheDir, "${p.id}.part")
            var url = URL(p.url)
            var conn: HttpURLConnection
            var redirects = 0
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000; conn.readTimeout = 30_000
                if (part.exists() && part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
                val code = conn.responseCode
                if (code in 300..399 && redirects < 5) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); redirects++; continue }
                if (code !in listOf(200, 206)) throw IllegalStateException("HTTP $code")
                if (code == 200) part.delete()
                break
            }
            conn.inputStream.use { inp ->
                java.io.FileOutputStream(part, true).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = part.length()
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n; progress(done, p.bytes)
                    }
                }
            }
            conn.disconnect()
            install(p, part)
            part.delete()
            Unit
        }.onFailure { diag?.e("voicepack", "download ${p.id} failed", it) }
    }

    /** Imports a previously downloaded archive (same file as the download URL) picked by the user. */
    suspend fun import(p: VoicePack, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(context.cacheDir, "${p.id}.import")
            context.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            install(p, tmp)
            tmp.delete()
            Unit
        }.onFailure { diag?.e("voicepack", "import ${p.id} failed", it) }
    }

    private fun install(p: VoicePack, file: File) {
        val sha = MessageDigest.getInstance("SHA-256").let { md -> file.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }; md.digest() }
            .joinToString("") { "%02x".format(it) }
        require(sha == p.sha256) { "Checksum mismatch – the file is corrupted or not the expected archive." }
        val d = dir(p); d.deleteRecursively(); d.mkdirs()
        if (p.singleName != null) { file.copyTo(File(d, p.singleName), overwrite = true) }
        else extract(file.inputStream(), p, d)
        File(d, ".installed").writeText(p.sha256)
        diag?.i("voicepack", "installed ${p.id} (${sizeOnDisk(p) / 1_048_576} MB)")
    }

    private fun extract(input: InputStream, p: VoicePack, d: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(input.buffered())).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                if (e.isDirectory) continue
                val path = e.name.substringAfter('/') // strip top-level folder
                val target = p.keep[path]?.let { File(d, it) }
                    ?: p.keepDirPrefix?.takeIf { path.startsWith(it) }?.let { File(d, path) }
                    ?: continue
                require(target.canonicalPath.startsWith(d.canonicalPath + File.separator)) { "unsafe archive path" }
                target.parentFile?.mkdirs()
                target.outputStream().use { tar.copyTo(it) }
            }
        }
    }

    fun remove(p: VoicePack) { dir(p).deleteRecursively() }
}

/**
 * Lazily created sherpa-onnx engines. Bundled models load from APK assets; voice packs from files.
 * Heavy objects are created off the main thread and released on demand to bound memory.
 */
class SherpaEngines(private val context: Context, private val packs: VoicePackManager) {
    private val assets get() = context.assets
    private var asrInst: OnlineRecognizer? = null
    private var kwsInst: KeywordSpotter? = null
    private var kwsThreshold = -1f
    private var spkInst: SpeakerEmbeddingExtractor? = null

    @Synchronized fun vad(threshold: Float, minSilence: Float): Vad = Vad(assets, VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(model = "models/vad/silero_vad.onnx", threshold = threshold, minSilenceDuration = minSilence,
            minSpeechDuration = 0.2f, windowSize = 512, maxSpeechDuration = 25f), sampleRate = 16000, numThreads = 1))

    /** Human-readable name of the live English recogniser built into the APK. */
    val asrModelName: String get() =
        runCatching { assets.open("models/asr/MODEL.txt").bufferedReader().readText().trim() }.getOrDefault("built-in streaming model")

    /**
     * The live English recogniser (Kroko streaming zipformer, bundled). It looks ahead further than older models, so a
     * finished utterance must be followed by [ASR_TAIL_SEC] of silence before the last word is emitted (measured).
     */
    @Synchronized fun asr(): OnlineRecognizer = asrInst ?: OnlineRecognizer(assets, OnlineRecognizerConfig(
        featConfig = FeatureConfig(16000, 80),
        modelConfig = OnlineModelConfig(transducer = OnlineTransducerModelConfig("models/asr/encoder.onnx", "models/asr/decoder.onnx",
            "models/asr/joiner.onnx"), tokens = "models/asr/tokens.txt", numThreads = 2, modelType = ""),
        enableEndpoint = false, decodingMethod = "greedy_search")).also { asrInst = it }

    @Synchronized fun kws(threshold: Float): KeywordSpotter {
        if (kwsInst != null && kwsThreshold == threshold) return kwsInst!!
        kwsInst?.release()
        return KeywordSpotter(assets, KeywordSpotterConfig(
            featConfig = FeatureConfig(16000, 80),
            modelConfig = OnlineModelConfig(transducer = OnlineTransducerModelConfig("models/kws/encoder.int8.onnx", "models/kws/decoder.int8.onnx",
                "models/kws/joiner.int8.onnx"), tokens = "models/kws/tokens.txt", numThreads = 1, modelType = "zipformer2"),
            keywordsFile = "models/kws/keywords.txt", keywordsThreshold = threshold, keywordsScore = 1.5f)).also { kwsInst = it; kwsThreshold = threshold }
    }

    val bpe: ai.clu.core.voice.SentencePieceBpe by lazy { assets.open("models/kws/bpe.model").use { ai.clu.core.voice.SentencePieceBpe.load(it) } }

    private val sherpaTts = LinkedHashMap<String, OfflineTts>()

    /**
     * The sherpa-onnx synthesiser for an installed TTS pack (Piper VITS or KittenTTS), loaded on first use.
     * At most two stay loaded (English + Hindi voice) to bound memory.
     */
    @Synchronized fun tts(packId: String): OfflineTts {
        sherpaTts[packId]?.let { return it }
        val p = VoicePacks.byId(packId) ?: error("unknown voice pack $packId")
        check(packs.isInstalled(p)) { "${p.title} is not installed" }
        val d = packs.dir(p)
        val model = if (p.kind == "tts-kokoro") OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(model = File(d, "model.onnx").path, voices = File(d, "voices.bin").path,
                tokens = File(d, "tokens.txt").path, dataDir = File(d, "espeak-ng-data").path), numThreads = SYNTH_THREADS)
        else if (p.kind == "tts-kitten") OfflineTtsModelConfig(
            kitten = OfflineTtsKittenModelConfig(model = File(d, "model.onnx").path, voices = File(d, "voices.bin").path,
                tokens = File(d, "tokens.txt").path, dataDir = File(d, "espeak-ng-data").path), numThreads = SYNTH_THREADS)
        else OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(model = File(d, "model.onnx").path, tokens = File(d, "tokens.txt").path, dataDir = File(d, "espeak-ng-data").path),
            numThreads = SYNTH_THREADS)
        while (sherpaTts.size >= 2) sherpaTts.remove(sherpaTts.keys.first())?.release()
        return OfflineTts(null, OfflineTtsConfig(model = model, maxNumSentences = 1)).also { sherpaTts[packId] = it }
    }

    @Synchronized fun speaker(): SpeakerEmbeddingExtractor? {
        if (!packs.isInstalled(VoicePacks.SPEAKER)) return null
        return spkInst ?: SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(model = File(packs.dir(VoicePacks.SPEAKER), "model.onnx").path,
            numThreads = 1)).also { spkInst = it }
    }

    /** Releases recognisers/synthesisers (memory pressure or pipeline rebuild). */
    @Synchronized fun releaseAll() {
        asrInst?.release(); asrInst = null
        kwsInst?.release(); kwsInst = null
        sherpaTts.values.forEach { it.release() }; sherpaTts.clear()
        spkInst?.release(); spkInst = null
    }

    companion object {
        /**
         * Synthesiser threads (Kokoro, Kitten, Piper). Synthesis needs far less than speech takes (best measured on the
         * phone with four: 5.5 ms per character, against ~70 ms per character of spoken audio), so two leave a wide margin while the language model, which generates at the same
         * time, keeps its own cores (see CluAgent's SPEECH_RESERVED_THREADS). Four oversubscribed the performance cores.
         */
        const val SYNTH_THREADS = 2
    }
}
