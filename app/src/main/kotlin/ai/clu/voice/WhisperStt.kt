package ai.clu.voice

import ai.clu.core.diag.DiagnosticsLog
import ai.clu.core.stt.WhisperBridge
import ai.clu.core.stt.WhisperEngine
import ai.clu.core.stt.WhisperModel
import ai.clu.core.stt.WhisperModels
import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * whisper.cpp speech recognition for the voice session: official model files (downloaded only on request, SHA1
 * verified, or imported), a lazily loaded engine, and one worker thread so transcription never blocks microphone
 * capture. A newer request or [cancel] makes an in-flight result stale; it is dropped, never delivered.
 */
class WhisperStt(private val context: Context, private val diag: DiagnosticsLog?) {
    val dir = File(context.filesDir, "whisper").apply { mkdirs() }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "clu-whisper").apply { priority = Thread.MAX_PRIORITY - 1 } }
    private val generation = AtomicLong()
    @Volatile private var engine: WhisperEngine? = null
    @Volatile private var engineModel: String? = null

    fun file(m: WhisperModel) = File(dir, m.fileName)
    fun isInstalled(m: WhisperModel) = File(dir, m.fileName + ".verified").exists() && file(m).length() == m.bytes
    fun model(id: String): WhisperModel? = WhisperModels.all.firstOrNull { it.id == id }

    /** Why [m] cannot be installed/used on this device now, or null. */
    fun storageProblem(m: WhisperModel): String? {
        val free = StatFs(dir.path).availableBytes
        if (free < m.bytes + 200L * 1024 * 1024) return "Not enough storage: needs ${m.bytes / 1_048_576} MB + 200 MB free, ${free / 1_048_576} MB available."
        return null
    }

    fun memoryProblem(m: WhisperModel): String? {
        val mi = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return if (mi.availMem < m.ramMb * 1_048_576L * 3 / 2) "Low memory: ${m.title} needs about ${m.ramMb} MB while running, ${mi.availMem / 1_048_576} MB available." else null
    }

    /** Resumable download from the official whisper.cpp model repository, verified against the published SHA1. */
    suspend fun download(m: WhisperModel, progress: (Long, Long) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            storageProblem(m)?.let { error(it) }
            val part = File(dir, m.fileName + ".part")
            var url = URL(m.url)
            var conn: HttpURLConnection
            var redirects = 0
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000; conn.readTimeout = 30_000
                if (part.exists() && part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
                val code = conn.responseCode
                if (code in 300..399 && redirects < 8) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); redirects++; continue }
                if (code !in listOf(200, 206)) error("HTTP $code")
                if (code == 200) part.delete()
                break
            }
            conn.inputStream.use { inp ->
                java.io.FileOutputStream(part, true).use { out ->
                    val buf = ByteArray(256 * 1024); var done = part.length()
                    while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; progress(done, m.bytes) }
                }
            }
            conn.disconnect()
            install(m, part)
        }.onFailure { diag?.e("whisper", "download ${m.id} failed", it) }
    }

    /** Imports a model file picked by the owner (the same official file, e.g. copied from a computer). */
    suspend fun import(uri: Uri): Result<WhisperModel> = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(dir, "import.part")
            context.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it, 256 * 1024) } }
            val m = WhisperModels.all.firstOrNull { it.bytes == tmp.length() }
                ?: run { tmp.delete(); error("Not an official whisper.cpp model (ggml-base.bin or ggml-small.bin): unexpected size ${tmp.length()} bytes.") }
            install(m, tmp)
            m
        }.onFailure { diag?.e("whisper", "import failed", it) }
    }

    private fun install(m: WhisperModel, f: File) {
        val sha = WhisperModels.sha1(f.inputStream())
        if (sha != m.sha1) { f.delete(); error("Checksum mismatch for ${m.fileName} (SHA1 $sha) – corrupted or not the official file.") }
        f.renameTo(file(m)).let { if (!it) error("could not move model into place") }
        File(dir, m.fileName + ".verified").writeText(sha)
        diag?.i("whisper", "installed ${m.fileName} (${m.bytes / 1_048_576} MB, SHA1 verified)")
    }

    fun remove(m: WhisperModel) {
        if (engineModel == file(m).path) release()
        file(m).delete(); File(dir, m.fileName + ".verified").delete()
    }

    /** Loads (or reuses) the engine for [m]. Blocking; call from a worker. Null if not installed or loading failed. */
    @Synchronized fun engine(m: WhisperModel): WhisperEngine? {
        if (!isInstalled(m)) return null
        val path = file(m).path
        engine?.let { if (engineModel == path) return it; it.close() }
        return runCatching {
            WhisperBridge.load()
            val t0 = System.currentTimeMillis()
            WhisperEngine.open(path).also {
                diag?.perf("whisper_load_ms", (System.currentTimeMillis() - t0).toDouble(), m.id)
                diag?.i("whisper", "loaded ${m.fileName}: ${WhisperBridge.nativeSystemInfo().take(160)}")
            }
        }.onFailure { diag?.e("whisper", "load ${m.fileName} failed", it) }.getOrNull().also { engine = it; engineModel = if (it != null) path else null }
    }

    /** Preloads the model in the background so the first utterance does not pay the load time. */
    fun warmUp(m: WhisperModel) { worker.execute { engine(m) } }

    /**
     * Transcribes [pcm] (16 kHz) on the worker; [onResult] runs on the worker with the text (null on failure),
     * unless a newer request or [cancel] superseded this one.
     */
    fun transcribe(m: WhisperModel, pcm: FloatArray, lang: String, threads: Int, onResult: (WhisperEngine.Result?) -> Unit) {
        val gen = generation.incrementAndGet()
        engine?.abort() // an older transcription still running is no longer wanted
        worker.execute {
            if (gen != generation.get()) return@execute
            val e = engine(m)
            val r = e?.let { runCatching { it.transcribe(pcm, lang, threads) }.onFailure { t -> diag?.e("whisper", "transcription failed", t) }.getOrNull() }
            if (gen == generation.get()) onResult(r)
        }
    }

    fun cancel() { generation.incrementAndGet(); engine?.abort() }

    /** Frees the model memory (on the worker, so a running transcription is never freed under it). */
    fun release() { cancel(); worker.execute { synchronized(this) { engine?.close(); engine = null; engineModel = null } } }
}
