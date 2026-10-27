package ai.clu.core.sync

import ai.clu.core.config.SettingsStore
import ai.clu.core.db.CluStore
import ai.clu.core.diary.DiaryRepository
import ai.clu.core.memory.MemoryRepository
import ai.clu.core.util.CluJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phone ↔ tablet continuity without a network between them: an encrypted sync file the owner exports on one device and
 * imports on the other (by USB, a cloud drive, a messenger — any way they like). It carries the same versioned rows as
 * live sync (memories, shared settings, and the diary only if chosen), so importing merges exactly like a sync: newer
 * versions win, older ones and duplicates are ignored, deletions carry over, and importing the same file twice changes
 * nothing.
 *
 * Encryption: AES-256-GCM with a key derived from the owner's passphrase (PBKDF2-HMAC-SHA256, 210 000 iterations,
 * random salt). The header (format, device, contents) is authenticated, so a tampered file is rejected. Without the
 * passphrase the file reveals nothing but its header.
 */
class SyncBundle(private val store: CluStore, private val engine: SyncEngine) {
    data class ExportResult(val bytes: ByteArray, val memories: Int, val settings: Int, val diary: Int)
    data class ImportResult(val applied: Int, val unchanged: Int, val fromDevice: String, val createdAt: Long, val tables: Set<String>)

    class BundleException(message: String) : Exception(message)

    fun export(passphrase: String, includeDiary: Boolean): ExportResult {
        require(passphrase.length >= MIN_PASSPHRASE) { "Use a passphrase of at least $MIN_PASSPHRASE characters." }
        val tables = buildSet { add(MemoryRepository.TABLE); add(SettingsStore.TABLE); if (includeDiary) add(DiaryRepository.TABLE) }
        val rows = engine.allRows(tables)
        val salt = ByteArray(16).also { rng.nextBytes(it) }; val nonce = ByteArray(12).also { rng.nextBytes(it) }
        val header = buildJsonObject {
            put("format", FORMAT); put("v", 1); put("device", store.deviceId); put("entity", store.entityId); put("createdAt", store.clock.now())
            putJsonArray("tables") { tables.sorted().forEach { add(it) } }
            putJsonObject("kdf") { put("alg", "PBKDF2WithHmacSHA256"); put("iter", ITERATIONS); put("salt", SyncEngine.b64(salt)) }
            put("nonce", SyncEngine.b64(nonce))
        }
        val plain = CluJson.encodeToString(ListSerializer(SyncRow.serializer()), rows).toByteArray()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(passphrase, salt, ITERATIONS), GCMParameterSpec(128, nonce)); updateAAD(header.toString().toByteArray())
        }
        val file = buildJsonObject { put("header", header); put("data", SyncEngine.b64(c.doFinal(plain))) }
        return ExportResult(file.toString().toByteArray(), rows.count { it.table == MemoryRepository.TABLE }, rows.count { it.table == SettingsStore.TABLE },
            rows.count { it.table == DiaryRepository.TABLE })
    }

    /** Merges a sync file. [includeDiary] = false skips diary rows even if the file has them. */
    fun import(bytes: ByteArray, passphrase: String, includeDiary: Boolean): ImportResult {
        val file = runCatching { CluJson.parseToJsonElement(String(bytes)).jsonObject }.getOrNull() ?: throw BundleException("This is not a Clu sync file.")
        val header = file["header"] as? JsonObject ?: throw BundleException("This is not a Clu sync file.")
        if (header["format"]?.jsonPrimitive?.contentOrNull != FORMAT) throw BundleException("This is not a Clu sync file.")
        if ((header["v"]?.jsonPrimitive?.intOrNull ?: 0) > 1) throw BundleException("This sync file is from a newer version of Clu; update Clu first.")
        val kdf = header["kdf"]?.jsonObject ?: throw BundleException("The sync file is damaged.")
        val iter = kdf["iter"]?.jsonPrimitive?.intOrNull?.takeIf { it in 10_000..5_000_000 } ?: throw BundleException("The sync file is damaged.")
        val salt = SyncEngine.b64d(kdf["salt"]!!.jsonPrimitive.content); val nonce = SyncEngine.b64d(header["nonce"]!!.jsonPrimitive.content)
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(passphrase, salt, iter), GCMParameterSpec(128, nonce)); updateAAD(header.toString().toByteArray())
            }.doFinal(SyncEngine.b64d(file["data"]!!.jsonPrimitive.content))
        } catch (e: AEADBadTagException) { throw BundleException("Wrong passphrase, or the file was changed after it was exported.") }
        val rows = runCatching { CluJson.decodeFromString(ListSerializer(SyncRow.serializer()), String(plain)) }.getOrElse { throw BundleException("The sync file is damaged.") }
        val from = header["device"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        if (from == store.deviceId) throw BundleException("This sync file was exported on this device.")
        val declared = header["tables"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
        val tables = declared.filter { it in ALLOWED && (includeDiary || it != DiaryRepository.TABLE) }.toSet()
        val st = engine.apply(rows, "file:$from", tables)
        return ImportResult(st.applied, st.skipped, from, header["createdAt"]?.jsonPrimitive?.longOrNull ?: 0, tables)
    }

    companion object {
        const val FORMAT = "clu-sync-file"
        const val ITERATIONS = 210_000
        const val MIN_PASSPHRASE = 8
        private val ALLOWED = setOf(MemoryRepository.TABLE, SettingsStore.TABLE, DiaryRepository.TABLE)
        private val rng = SecureRandom()
        private fun key(pass: String, salt: ByteArray, iter: Int) =
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pass.toCharArray(), salt, iter, 256)).encoded, "AES")
    }
}
