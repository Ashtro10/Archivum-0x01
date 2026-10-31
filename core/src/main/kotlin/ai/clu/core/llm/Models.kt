package ai.clu.core.llm

import ai.clu.core.db.CluStore
import ai.clu.core.db.SqlRow
import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.util.CluJson
import ai.clu.core.util.newId
import kotlinx.serialization.Serializable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Serializable
data class GgufInfo(
    val version: Int = 0,
    val architecture: String = "",
    val name: String = "",
    val fileType: Int = -1,
    val contextLength: Long = 0,
    val blockCount: Long = 0,
    val embeddingLength: Long = 0,
    /** Attention heads and key/value heads (grouped-query attention): decide how big the KV cache is. 0 = not in the file. */
    val headCount: Long = 0,
    val headCountKv: Long = 0,
    val tensorCount: Long = 0,
    val hasChatTemplate: Boolean = false,
    val parameterCountHint: Long = 0,
    /** Smallest file size that holds the start of every tensor (header + data offsets); 0 if unknown. */
    val minimumBytes: Long = 0,
) {
    val quantization: String get() = FILE_TYPES[fileType] ?: if (fileType >= 0) "type $fileType" else "unknown"

    companion object {
        val FILE_TYPES = mapOf(0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 7 to "Q8_0", 8 to "Q5_0", 9 to "Q5_1", 10 to "Q2_K",
            11 to "Q3_K_S", 12 to "Q3_K_M", 13 to "Q3_K_L", 14 to "Q4_K_S", 15 to "Q4_K_M", 16 to "Q5_K_S", 17 to "Q5_K_M", 18 to "Q6_K",
            19 to "IQ2_XXS", 20 to "IQ2_XS", 21 to "Q2_K_S", 22 to "IQ3_XS", 23 to "IQ3_XXS", 24 to "IQ1_S", 25 to "IQ4_NL", 26 to "IQ3_S",
            27 to "IQ3_M", 28 to "IQ2_S", 29 to "IQ2_M", 30 to "IQ4_XS", 31 to "IQ1_M", 32 to "BF16", 36 to "TQ1_0", 37 to "TQ2_0")
    }
}

class GgufException(msg: String) : Exception(msg)

/** Reads GGUF metadata without loading tensors. */
object GgufReader {
    fun read(file: File): GgufInfo {
        if (!file.exists()) throw GgufException("file not found")
        RandomAccessFile(file, "r").use { raf ->
            val r = Reader(raf)
            val magic = r.bytes(4)
            if (String(magic, Charsets.US_ASCII) != "GGUF") throw GgufException("not a GGUF file (bad magic)")
            val version = r.u32().toInt()
            if (version !in 2..3) throw GgufException("unsupported GGUF version $version")
            val tensorCount = r.u64()
            val kvCount = r.u64()
            if (kvCount > 100_000 || tensorCount > 1_000_000) throw GgufException("corrupt header")
            var info = GgufInfo(version = version, tensorCount = tensorCount)
            var arch = ""
            val wanted = HashMap<String, Any?>()
            repeat(kvCount.toInt()) {
                val key = r.string()
                val type = r.u32().toInt()
                val keep = key.startsWith("general.") || key.endsWith(".context_length") || key.endsWith(".block_count") ||
                    key.endsWith(".embedding_length") || key.endsWith(".attention.head_count") || key.endsWith(".attention.head_count_kv") || key == "tokenizer.chat_template"
                val v = r.value(type, keep)
                if (keep) wanted[key] = v
                if (key == "general.architecture") arch = v as String
            }
            fun num(k: String) = (wanted[k] as? Number)?.toLong() ?: 0L
            // Tensor table: every tensor's data must start inside the file, or the file is incomplete (interrupted
            // download or copy). llama.cpp would also refuse it, but with a less clear message.
            var maxOffset = 0L
            repeat(tensorCount.toInt()) {
                r.skipString()
                val dims = r.u32().toInt(); if (dims !in 0..8) throw GgufException("corrupt tensor table")
                repeat(dims) { r.u64() }
                r.u32()
                val off = r.u64(); if (off < 0) throw GgufException("corrupt tensor table")
                maxOffset = maxOf(maxOffset, off)
            }
            val align = (wanted["general.alignment"] as? Number)?.toLong()?.takeIf { it > 0 } ?: 32L
            val dataStart = (raf.filePointer + align - 1) / align * align
            val minimum = dataStart + maxOffset + 1
            if (tensorCount > 0 && minimum > file.length())
                throw GgufException("file is incomplete: ${file.length()} bytes, needs at least $minimum (interrupted download or copy?)")
            info = info.copy(minimumBytes = if (tensorCount > 0) minimum else 0,
                architecture = arch,
                name = wanted["general.name"] as? String ?: "",
                fileType = (wanted["general.file_type"] as? Number)?.toInt() ?: -1,
                contextLength = num("$arch.context_length"),
                blockCount = num("$arch.block_count"),
                embeddingLength = num("$arch.embedding_length"),
                headCount = num("$arch.attention.head_count"),
                headCountKv = num("$arch.attention.head_count_kv"),
                hasChatTemplate = wanted.containsKey("tokenizer.chat_template"),
                parameterCountHint = num("general.parameter_count"),
            )
            return info
        }
    }

    private class Reader(val raf: RandomAccessFile) {
        private val b8 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        fun bytes(n: Int): ByteArray { val a = ByteArray(n); raf.readFully(a); return a }
        // Calls go through java.nio.Buffer: the JDK 9+ covariant ByteBuffer overloads don't exist on Android 13.
        private fun le(n: Int): ByteBuffer { (b8 as java.nio.Buffer).clear(); raf.readFully(b8.array(), 0, n); (b8 as java.nio.Buffer).limit(n); return b8 }
        fun u32(): Long = le(4).int.toLong() and 0xffffffffL
        fun u64(): Long = le(8).long
        fun string(): String {
            val n = u64(); if (n < 0 || n > 64 * 1024 * 1024) throw GgufException("corrupt string length")
            return String(bytes(n.toInt()), Charsets.UTF_8)
        }
        fun skipString() { val n = u64(); if (n < 0 || n > 64 * 1024 * 1024) throw GgufException("corrupt string length"); raf.seek(raf.filePointer + n) }

        fun value(type: Int, keep: Boolean): Any? = when (type) {
            0 -> le(1).get().toInt() and 0xff
            1 -> le(1).get().toInt()
            2 -> le(2).short.toInt() and 0xffff
            3 -> le(2).short.toInt()
            4 -> u32()
            5 -> le(4).int
            6 -> le(4).float
            7 -> le(1).get().toInt() != 0
            8 -> if (keep) string() else { skipString(); null }
            9 -> {
                val et = u32().toInt(); val n = u64()
                if (n < 0 || n > 50_000_000) throw GgufException("corrupt array")
                val size = elemSize(et)
                if (size > 0) raf.seek(raf.filePointer + n * size)
                else repeat(n.toInt()) { value(et, false) }
                null
            }
            10 -> u64()
            11 -> le(8).long
            12 -> le(8).double
            else -> throw GgufException("unknown metadata type $type")
        }

        private fun elemSize(t: Int) = when (t) { 0, 1, 7 -> 1; 2, 3 -> 2; 4, 5, 6 -> 4; 10, 11, 12 -> 8; else -> -1 }
    }
}

enum class ModelStatus(val label: String) {
    UNVERIFIED("Header valid – not yet loaded"),
    VERIFIED("Loaded and generated successfully"),
    INCOMPATIBLE("Incompatible or corrupted"),
    MISSING("File missing"),
}

data class ModelEntry(
    val id: String, val name: String, val path: String, val format: String, val sizeBytes: Long, val addedAt: Long,
    val status: ModelStatus, val lastError: String?, val info: GgufInfo,
)

/** Suggested models the owner may download (sizes are approximate; nothing downloads without confirmation). */
data class CatalogModel(val name: String, val url: String, val approxBytes: Long, val notes: String)

/** Registry of local model files (device-specific: model files are not synchronised). */
class ModelRegistry(private val store: CluStore) {
    private val db = store.db

    fun list(): List<ModelEntry> = db.query("SELECT id, name, path, format, size_bytes, added_at, status, last_error, info FROM models WHERE kind = 'llm' ORDER BY added_at", emptyList(), ::read)
        .map { e -> if (!File(e.path).exists() && e.status != ModelStatus.MISSING) e.copy(status = ModelStatus.MISSING) else e }

    fun get(id: String): ModelEntry? = list().firstOrNull { it.id == id }

    /** Registers a GGUF file already on disk (e.g. copied into app storage by the importer). */
    fun register(file: File, displayName: String? = null): ModelEntry {
        val info = try { GgufReader.read(file) } catch (e: GgufException) { throw LlmException("Not a compatible model: ${e.message}") }
        val existing = list().firstOrNull { it.path == file.absolutePath }
        val e = ModelEntry(existing?.id ?: newId(), displayName ?: info.name.ifBlank { file.nameWithoutExtension }, file.absolutePath, "GGUF v${info.version}",
            file.length(), store.clock.now(), ModelStatus.UNVERIFIED, null, info)
        db.execute("""INSERT INTO models(id, name, path, format, size_bytes, added_at, status, last_error, info, kind) VALUES(?,?,?,?,?,?,?,?,?,'llm')
            ON CONFLICT(id) DO UPDATE SET name=excluded.name, size_bytes=excluded.size_bytes, status=excluded.status, info=excluded.info, last_error=NULL""",
            listOf(e.id, e.name, e.path, e.format, e.sizeBytes, e.addedAt, e.status.name, null, CluJson.encodeToString(GgufInfo.serializer(), info)))
        store.notifyChanged(TABLE)
        return e
    }

    fun setStatus(id: String, status: ModelStatus, error: String? = null) {
        db.execute("UPDATE models SET status = ?, last_error = ? WHERE id = ?", listOf(status.name, error, id))
        store.notifyChanged(TABLE)
    }

    fun rename(id: String, name: String) { db.execute("UPDATE models SET name = ? WHERE id = ?", listOf(name, id)); store.notifyChanged(TABLE) }

    /** Removes the registry entry; deletes the file only if it lives inside [ownedDir] (files the app copied). */
    fun remove(id: String, ownedDir: File?) {
        val e = get(id) ?: return
        if (ownedDir != null && File(e.path).canonicalPath.startsWith(ownedDir.canonicalPath)) File(e.path).delete()
        db.execute("DELETE FROM models WHERE id = ?", listOf(id))
        store.notifyChanged(TABLE)
    }

    private fun read(r: SqlRow) = ModelEntry(r.str(0), r.str(1), r.str(2), r.str(3), r.lng(4), r.lng(5), ModelStatus.valueOf(r.str(6)), r.string(7),
        runCatching { CluJson.decodeFromString(GgufInfo.serializer(), r.str(8)) }.getOrDefault(GgufInfo()))

    companion object {
        const val TABLE = "models"

        /**
         * RAM need: weights (memory-mapped, but resident while generating) + KV cache + compute buffers.
         * The KV cache is f16 and sized from the model's own head counts when the file has them (grouped-query attention
         * makes it several times smaller than full attention); otherwise a typical 1/4 ratio is assumed.
         */
        fun estimateRamBytes(e: ModelEntry, nCtx: Int): Long = e.sizeBytes + kvCacheBytes(e.info, nCtx) + COMPUTE_OVERHEAD

        fun kvCacheBytes(info: GgufInfo, nCtx: Int): Long {
            val layers = info.blockCount.takeIf { it > 0 } ?: 32
            val embd = info.embeddingLength.takeIf { it > 0 } ?: 3072
            val kvDim = if (info.headCount > 0 && info.headCountKv > 0) embd * info.headCountKv / info.headCount else embd / 4
            return 2L * layers * nCtx * kvDim * 2
        }

        const val COMPUTE_OVERHEAD = 300L * 1024 * 1024

        /**
         * The largest context (multiple of 256, at least [MIN_CONTEXT]) whose estimated need fits in [budgetBytes], never
         * above [wanted] or the model's own training context. Null when even the smallest context does not fit.
         */
        fun fitContext(e: ModelEntry, wanted: Int, budgetBytes: Long): Int? {
            val cap = if (e.info.contextLength > 0) minOf(wanted.toLong(), e.info.contextLength).toInt() else wanted
            var ctx = cap
            while (ctx >= MIN_CONTEXT) { if (estimateRamBytes(e, ctx) <= budgetBytes) return ctx; ctx = (ctx - 256).coerceAtMost(ctx * 3 / 4) / 256 * 256 }
            return null
        }
        const val MIN_CONTEXT = 1024

        val catalog = listOf(
            CatalogModel("Qwen2.5 3B Instruct (Q4_K_M) – recommended default",
                "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf", 2_100_000_000L,
                "~3B parameters, ChatML template. Strong instruction following for its size; the default suggestion for the PC and the phone."),
            CatalogModel("Llama 3.2 3B Instruct (Q4_K_M)",
                "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf", 2_020_000_000L,
                "~3B parameters, Llama 3 chat template."),
            CatalogModel("Qwen2.5 1.5B Instruct (Q4_K_M) – fast fallback",
                "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf", 1_120_000_000L,
                "Lower latency and RAM; noticeably weaker reasoning."),
        )
    }
}
