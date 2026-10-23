package ai.clu.core.persona

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.db.CluStore
import ai.clu.core.db.str
import ai.clu.core.memory.CLU_SCOPE
import ai.clu.core.memory.MemoryRepository
import ai.clu.core.util.CluJson
import ai.clu.core.util.newId
import ai.clu.core.voice.WakePhraseMatcher
import kotlinx.serialization.Serializable

enum class CharacterMemoryScope(val label: String) {
    NONE("No memory (each conversation starts fresh)"),
    OWN("Own private memory only"),
    OWN_AND_CLU("Own memory + read Clu's memories (requires private-memory permission)"),
}

enum class CharacterStatus { ACTIVE, ARCHIVED }

/**
 * A fictional companion created by the owner. Characters are separate personas: they never replace or modify
 * Clu's identity, and they only see Clu's private memories if explicitly permitted.
 */
@Serializable
data class CharacterProfile(
    val id: String = newId(),
    val name: String,
    val description: String = "",
    val background: String = "",
    val personality: String = "",
    val scenario: String = "",
    val style: String = "",
    val exampleDialogues: String = "",
    /** Tool names this character may use. Empty = conversation only. */
    val allowedTools: List<String> = emptyList(),
    val memoryScope: CharacterMemoryScope = CharacterMemoryScope.OWN,
    val canAccessPrivateMemory: Boolean = false,
    val canUseDeviceTools: Boolean = false,
    val status: CharacterStatus = CharacterStatus.ACTIVE,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

class CharacterRepository(private val store: CluStore, private val memories: MemoryRepository) {
    private val db = store.db

    fun list(includeArchived: Boolean = true): List<CharacterProfile> =
        db.query("SELECT json FROM characters WHERE deleted = 0 ${if (includeArchived) "" else "AND status = 'ACTIVE'"} ORDER BY created_at", emptyList()) {
            CluJson.decodeFromString(CharacterProfile.serializer(), it.str(0))
        }

    fun get(id: String): CharacterProfile? =
        db.query("SELECT json FROM characters WHERE id = ? AND deleted = 0", listOf(id)) { CluJson.decodeFromString(CharacterProfile.serializer(), it.str(0)) }.firstOrNull()

    fun save(c: CharacterProfile): CharacterProfile {
        require(c.name.isNotBlank()) { "character needs a name" }
        require(c.id != CLU_SCOPE) { "reserved id" }
        require(!c.name.equals("clu", ignoreCase = true)) { "\"Clu\" is reserved for Clu itself" }
        val now = store.clock.now()
        val existing = get(c.id)
        val row = c.copy(createdAt = existing?.createdAt ?: now, updatedAt = now)
        db.transaction {
            val hlc = store.stamp(TABLE, row.id)
            write(row, deleted = false, hlc = hlc, origin = store.deviceId)
        }
        store.notifyChanged(TABLE)
        return row
    }

    fun archive(id: String) = get(id)?.let { save(it.copy(status = CharacterStatus.ARCHIVED)) }
    fun restore(id: String) = get(id)?.let { save(it.copy(status = CharacterStatus.ACTIVE)) }
    fun duplicate(id: String): CharacterProfile? = get(id)?.let { save(it.copy(id = newId(), name = it.name + " (copy)", createdAt = 0)) }

    /** Deletes the character and only its own private memories. Clu's identity and memories are untouched. */
    fun delete(id: String) {
        val c = get(id) ?: return
        memories.list(scope = c.id).forEach { memories.delete(it.id) }
        db.transaction {
            val hlc = store.stamp(TABLE, id)
            write(c, deleted = true, hlc = hlc, origin = store.deviceId)
        }
        store.notifyChanged(TABLE)
    }

    private fun write(c: CharacterProfile, deleted: Boolean, hlc: String, origin: String) = db.execute(
        """INSERT INTO characters(id, json, status, created_at, updated_at, deleted, hlc, origin) VALUES(?,?,?,?,?,?,?,?)
           ON CONFLICT(id) DO UPDATE SET json=excluded.json, status=excluded.status, updated_at=excluded.updated_at,
           deleted=excluded.deleted, hlc=excluded.hlc, origin=excluded.origin""",
        listOf(c.id, CluJson.encodeToString(CharacterProfile.serializer(), c), c.status.name, c.createdAt, c.updatedAt, deleted, hlc, origin)
    )

    data class Row(val id: String, val json: String, val deleted: Boolean, val hlc: String, val origin: String)
    fun row(id: String): Row? = db.query("SELECT id, json, deleted, hlc, origin FROM characters WHERE id = ?", listOf(id)) {
        Row(it.str(0), it.str(1), (it.long(2) ?: 0) != 0L, it.str(3), it.str(4))
    }.firstOrNull()

    fun applyRemote(r: Row): Boolean {
        val local = row(r.id)
        if (local != null && local.hlc >= r.hlc) return false
        val c = runCatching { CluJson.decodeFromString(CharacterProfile.serializer(), r.json) }.getOrNull() ?: return false
        store.hlc.observe(r.hlc)
        db.transaction { write(c, r.deleted, r.hlc, r.origin); store.logRemote(TABLE, r.id, r.hlc) }
        store.notifyChanged(TABLE)
        return true
    }

    companion object { const val TABLE = "characters" }
}

/** Describes the persona currently speaking: Clu itself, or a character. */
data class ActivePersona(
    val id: String,
    val name: String,
    val isClu: Boolean,
    val character: CharacterProfile?,
    /** Memory scopes this persona may read. */
    val readableScopes: List<String>,
    /** Scope new memories are written to (null = this persona keeps no memory). */
    val writeScope: String?,
    val canUseDeviceTools: Boolean,
    val allowedTools: List<String>?, // null = all tools permitted by the general policy
)

class PersonaManager(private val settings: SettingsStore, private val characters: CharacterRepository) {
    fun active(): ActivePersona {
        val id = settings.string(Settings.ACTIVE_PERSONA)
        val c = if (id == CLU_SCOPE) null else characters.get(id)?.takeIf { it.status == CharacterStatus.ACTIVE }
        if (c == null) return ActivePersona(CLU_SCOPE, settings.string(Settings.CLU_NAME), true, null, listOf(CLU_SCOPE), CLU_SCOPE, true, null)
        val readable = when (c.memoryScope) {
            CharacterMemoryScope.NONE -> emptyList()
            CharacterMemoryScope.OWN -> listOf(c.id)
            CharacterMemoryScope.OWN_AND_CLU -> if (c.canAccessPrivateMemory) listOf(c.id, CLU_SCOPE) else listOf(c.id)
        }
        return ActivePersona(c.id, c.name, false, c, readable, if (c.memoryScope == CharacterMemoryScope.NONE) null else c.id,
            c.canUseDeviceTools, c.allowedTools)
    }

    fun switchToClu() { settings.set(Settings.ACTIVE_PERSONA, CLU_SCOPE) }

    fun switchTo(id: String): Boolean {
        if (id == CLU_SCOPE) { switchToClu(); return true }
        val c = characters.get(id) ?: return false
        if (c.status != CharacterStatus.ACTIVE) return false
        settings.set(Settings.ACTIVE_PERSONA, c.id)
        return true
    }

    /** Resolves a spoken name ("switch to Rick") to Clu or an active character by fuzzy match. */
    fun resolveByName(spoken: String): String? {
        val n = WakePhraseMatcher.normalize(spoken)
        val cluName = WakePhraseMatcher.normalize(settings.string(Settings.CLU_NAME))
        if (WakePhraseMatcher.similarity(n, cluName) >= 0.7 || n in setOf("clu", "clue", "yourself", "normal", "you")) return CLU_SCOPE
        val scored = characters.list(includeArchived = false).map { c ->
            val cn = WakePhraseMatcher.normalize(c.name)
            val first = cn.split(' ').first()
            c.id to maxOf(WakePhraseMatcher.similarity(n, cn), WakePhraseMatcher.similarity(n, first), if (cn.contains(n) && n.length >= 3) 0.85 else 0.0)
        }.filter { it.second >= 0.7 }
        return scored.maxByOrNull { it.second }?.first
    }
}
