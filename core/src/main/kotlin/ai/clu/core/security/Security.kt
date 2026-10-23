package ai.clu.core.security

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.db.queryOne
import ai.clu.core.db.CluStore
import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.util.CluJson
import ai.clu.core.util.newId
import ai.clu.core.util.randomBytes
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.min
import kotlin.math.sqrt

enum class PersonRole(val label: String) {
    OWNER("Owner"),
    TRUSTED("Trusted (routine actions + allowed tools)"),
    GUEST("Guest (conversation only)"),
    BLOCKED("Blocked"),
}

@Serializable
data class Person(
    val id: String = newId(),
    val name: String,
    val role: PersonRole = PersonRole.GUEST,
    val relation: String = "",
    /** Tools this person may trigger (in addition to conversation). */
    val allowedTools: List<String> = emptyList(),
    val canAccessPrivateMemory: Boolean = false,
    /** Optional per-person voice threshold override. */
    val voiceThreshold: Double? = null,
    val notes: String = "",
    /** How Clu should treat/address this person. */
    val interactionStyle: String = "",
)

class PeopleRepository(private val store: CluStore) {
    private val db = store.db

    fun list(): List<Person> = db.query("SELECT json FROM people WHERE deleted = 0 ORDER BY created_at") {
        CluJson.decodeFromString(Person.serializer(), it.str(0))
    }

    fun get(id: String): Person? = db.query("SELECT json FROM people WHERE id = ? AND deleted = 0", listOf(id)) {
        CluJson.decodeFromString(Person.serializer(), it.str(0))
    }.firstOrNull()

    fun owner(): Person? = list().firstOrNull { it.role == PersonRole.OWNER }

    fun ensureOwner(name: String): Person = owner() ?: save(Person(name = name.ifBlank { "Owner" }, role = PersonRole.OWNER, canAccessPrivateMemory = true))

    /** Saves a person. Changing who the owner is, or editing the owner record, is a sensitive action (verified by caller). */
    fun save(p: Person): Person {
        require(p.name.isNotBlank()) { "person needs a name" }
        if (p.role == PersonRole.OWNER) require(list().none { it.role == PersonRole.OWNER && it.id != p.id }) { "there can only be one owner" }
        val now = store.clock.now()
        db.transaction {
            val hlc = store.stamp(TABLE, p.id)
            db.execute("""INSERT INTO people(id, json, created_at, updated_at, deleted, hlc, origin) VALUES(?,?,?,?,0,?,?)
                ON CONFLICT(id) DO UPDATE SET json=excluded.json, updated_at=excluded.updated_at, deleted=0, hlc=excluded.hlc, origin=excluded.origin""",
                listOf(p.id, CluJson.encodeToString(Person.serializer(), p), now, now, hlc, store.deviceId))
        }
        store.notifyChanged(TABLE)
        return p
    }

    fun delete(id: String) {
        val p = get(id) ?: return
        require(p.role != PersonRole.OWNER) { "the owner cannot be deleted" }
        db.transaction {
            val hlc = store.stamp(TABLE, id)
            db.execute("UPDATE people SET deleted = 1, updated_at = ?, hlc = ?, origin = ? WHERE id = ?", listOf(store.clock.now(), hlc, store.deviceId, id))
            db.execute("DELETE FROM voiceprints WHERE person_id = ?", listOf(id))
        }
        store.notifyChanged(TABLE)
    }

    // ----------------------------------------------------- voiceprints (device-local; embeddings are model-specific)
    fun addVoiceprint(personId: String, model: String, embedding: FloatArray) {
        val bb = ByteBuffer.allocate(embedding.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        embedding.forEach { bb.putFloat(it) }
        db.execute("INSERT INTO voiceprints(id, person_id, model, embedding, created_at) VALUES(?,?,?,?,?)",
            listOf(newId(), personId, model, bb.array(), store.clock.now()))
        store.notifyChanged("voiceprints")
    }

    fun clearVoiceprints(personId: String) = db.execute("DELETE FROM voiceprints WHERE person_id = ?", listOf(personId))

    fun voiceprints(model: String): Map<String, List<FloatArray>> =
        db.query("SELECT person_id, embedding FROM voiceprints WHERE model = ?", listOf(model)) { r ->
            val b = ByteBuffer.wrap(r.blob(1)!!).order(ByteOrder.LITTLE_ENDIAN)
            r.str(0) to FloatArray(b.remaining() / 4) { b.float }
        }.groupBy({ it.first }, { it.second })

    fun voiceprintCount(personId: String): Long = db.queryOne("SELECT COUNT(*) FROM voiceprints WHERE person_id = ?", listOf(personId)) { it.lng(0) } ?: 0

    data class Row(val id: String, val json: String, val deleted: Boolean, val hlc: String, val origin: String)
    fun row(id: String): Row? = db.query("SELECT id, json, deleted, hlc, origin FROM people WHERE id = ?", listOf(id)) {
        Row(it.str(0), it.str(1), (it.long(2) ?: 0) != 0L, it.str(3), it.str(4))
    }.firstOrNull()

    fun applyRemote(r: Row): Boolean {
        val local = row(r.id)
        if (local != null && local.hlc >= r.hlc) return false
        val p = runCatching { CluJson.decodeFromString(Person.serializer(), r.json) }.getOrNull() ?: return false
        // Never let a peer create a second owner record.
        if (p.role == PersonRole.OWNER && owner()?.let { it.id != p.id } == true) return false
        store.hlc.observe(r.hlc)
        db.transaction {
            db.execute("""INSERT INTO people(id, json, created_at, updated_at, deleted, hlc, origin) VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET json=excluded.json, updated_at=excluded.updated_at, deleted=excluded.deleted, hlc=excluded.hlc, origin=excluded.origin""",
                listOf(r.id, r.json, store.clock.now(), store.clock.now(), r.deleted, r.hlc, r.origin))
            store.logRemote(TABLE, r.id, r.hlc)
        }
        store.notifyChanged(TABLE)
        return true
    }

    companion object { const val TABLE = "people" }
}

data class SpeakerMatch(val personId: String?, val score: Double, val runnerUp: Double)

/** Cosine-similarity speaker identification over enrolled embeddings (embeddings come from the on-device model). */
object SpeakerMatcher {
    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
    }

    fun identify(embedding: FloatArray, enrolled: Map<String, List<FloatArray>>, threshold: Double, overrides: Map<String, Double> = emptyMap()): SpeakerMatch {
        val scores = enrolled.mapValues { (_, list) -> list.maxOf { cosine(embedding, it) } }.entries.sortedByDescending { it.value }
        if (scores.isEmpty()) return SpeakerMatch(null, 0.0, 0.0)
        val best = scores[0]
        val runner = scores.getOrNull(1)?.value ?: 0.0
        val t = overrides[best.key] ?: threshold
        return if (best.value >= t) SpeakerMatch(best.key, best.value, runner) else SpeakerMatch(null, best.value, runner)
    }
}

/**
 * PIN and security phrase, stored as salted PBKDF2-HMAC-SHA256 hashes (never plaintext). Repeated failures cause an
 * increasing delay (30 s doubling up to 15 min) but never a permanent lockout.
 */
class CredentialStore(private val store: CluStore, private val iterations: Int = 60_000) {
    private val db = store.db

    enum class Kind { PIN, PHRASE }
    sealed class VerifyResult {
        object Ok : VerifyResult()
        object Wrong : VerifyResult()
        object NotSet : VerifyResult()
        data class LockedOut(val retryInMs: Long) : VerifyResult()
    }

    fun isSet(kind: Kind) = (db.queryOne("SELECT COUNT(*) FROM credentials WHERE kind = ?", listOf(kind.name)) { it.lng(0) } ?: 0) > 0

    fun set(kind: Kind, secret: String) {
        val normalized = normalize(kind, secret)
        if (kind == Kind.PIN) require(Regex("\\d{4,12}").matches(normalized)) { "PIN must be 4-12 digits" }
        else require(normalized.length >= 6) { "security phrase must be at least 6 characters" }
        val salt = randomBytes(16)
        db.execute("INSERT INTO credentials(kind, hash, salt, iterations, updated_at) VALUES(?,?,?,?,?) " +
            "ON CONFLICT(kind) DO UPDATE SET hash=excluded.hash, salt=excluded.salt, iterations=excluded.iterations, updated_at=excluded.updated_at",
            listOf(kind.name, hash(normalized, salt, iterations), salt, iterations, store.clock.now()))
    }

    fun verify(kind: Kind, secret: String): VerifyResult {
        val row = db.queryOne("SELECT hash, salt, iterations FROM credentials WHERE kind = ?", listOf(kind.name)) {
            Triple(it.blob(0)!!, it.blob(1)!!, (it.long(2) ?: 0).toInt())
        } ?: return VerifyResult.NotSet
        val now = store.clock.now()
        val lockedUntil = state("locked_until")
        if (now < lockedUntil) return VerifyResult.LockedOut(lockedUntil - now)
        val ok = MessageDigest.isEqual(row.first, hash(normalize(kind, secret), row.second, row.third))
        if (ok) { setState("failures", 0); setState("locked_until", 0); return VerifyResult.Ok }
        val failures = state("failures") + 1
        setState("failures", failures)
        if (failures >= 5) {
            val delay = min(30_000L shl (failures - 5).toInt().coerceAtMost(5), 15 * 60_000L)
            setState("locked_until", now + delay)
        }
        return VerifyResult.Wrong
    }

    private fun state(k: String) = db.queryOne("SELECT value FROM auth_state WHERE key = ?", listOf(k)) { it.lng(0) } ?: 0L
    private fun setState(k: String, v: Long) = db.execute("INSERT INTO auth_state(key, value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", listOf(k, v))

    private fun normalize(kind: Kind, s: String) = if (kind == Kind.PIN) s.trim() else s.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun hash(secret: String, salt: ByteArray, iter: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iter, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}

enum class Sensitivity { ROUTINE, PERSONAL, CONFIG, SENSITIVE }

/** Who is issuing a request. */
data class Actor(val personId: String?, val role: PersonRole?, val viaVoice: Boolean, val verifiedAt: Long = 0) {
    companion object {
        fun ownerInApp(verifiedAt: Long = 0) = Actor(null, PersonRole.OWNER, viaVoice = false, verifiedAt = verifiedAt)
    }
}

sealed class AuthDecision {
    object Allow : AuthDecision()
    data class NeedsVerification(val reason: String) : AuthDecision()
    data class Deny(val reason: String) : AuthDecision()
}

/**
 * Central permission decisions. Voice recognition only identifies the likely speaker; SENSITIVE actions always need
 * a fresh PIN/device-credential verification when "Verify for sensitive actions" is on.
 */
class AuthorizationPolicy(private val settings: SettingsStore, private val people: PeopleRepository, private val now: () -> Long) {
    /** Resolves a voice-identified speaker (or null = unrecognised) into an actor according to settings. */
    fun actorForSpeaker(personId: String?, speakerIdActive: Boolean): Actor? {
        if (!speakerIdActive) return Actor(people.owner()?.id, PersonRole.OWNER, viaVoice = true)
        if (personId != null) {
            val p = people.get(personId) ?: return null
            return Actor(p.id, p.role, viaVoice = true)
        }
        return when (settings.string(Settings.UNKNOWN_POLICY)) {
            "owner" -> Actor(people.owner()?.id, PersonRole.OWNER, viaVoice = true)
            "ignore" -> null
            else -> Actor(null, PersonRole.GUEST, viaVoice = true)
        }
    }

    fun decide(actor: Actor, sensitivity: Sensitivity, toolName: String? = null): AuthDecision {
        val role = actor.role ?: return AuthDecision.Deny("unknown speaker")
        val verifiedRecently = actor.verifiedAt > 0 && now() - actor.verifiedAt <= settings.int(Settings.AUTH_WINDOW) * 1000L
        return when (role) {
            PersonRole.BLOCKED -> AuthDecision.Deny("blocked")
            PersonRole.OWNER -> when {
                sensitivity != Sensitivity.SENSITIVE -> AuthDecision.Allow
                !settings.bool(Settings.PIN_FOR_SENSITIVE) || verifiedRecently -> AuthDecision.Allow
                else -> AuthDecision.NeedsVerification("sensitive action")
            }
            PersonRole.TRUSTED -> {
                val person = actor.personId?.let { people.get(it) }
                when (sensitivity) {
                    Sensitivity.ROUTINE -> if (toolName == null || person?.allowedTools?.contains(toolName) == true) AuthDecision.Allow
                        else AuthDecision.Deny("tool not permitted for ${person?.name ?: "this person"}")
                    Sensitivity.PERSONAL -> if (person?.canAccessPrivateMemory == true) AuthDecision.Allow else AuthDecision.Deny("private")
                    else -> AuthDecision.NeedsVerification("owner verification required")
                }
            }
            PersonRole.GUEST -> when {
                sensitivity == Sensitivity.ROUTINE && toolName == null -> AuthDecision.Allow
                sensitivity == Sensitivity.ROUTINE && toolName in GUEST_TOOLS -> AuthDecision.Allow
                sensitivity == Sensitivity.SENSITIVE || sensitivity == Sensitivity.CONFIG -> AuthDecision.NeedsVerification("owner verification required")
                else -> AuthDecision.Deny("not permitted for guests")
            }
        }
    }

    fun refusalLine(seed: Int = (now() / 1000).toInt()): String {
        val lines = settings.list(Settings.REFUSAL_LINES)
        val sarcasm = settings.string(Settings.SARCASM)
        val pool = when (sarcasm) {
            "off" -> listOf("I'm sorry, only my owner can ask for that.")
            else -> lines
        }
        return pool[Math.floorMod(seed, pool.size)]
    }

    companion object {
        val GUEST_TOOLS = setOf("time.now", "calc")
    }
}
