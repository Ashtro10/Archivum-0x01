package ai.clu.core.commands

import ai.clu.core.db.CluStore
import ai.clu.core.db.str
import ai.clu.core.util.CluJson
import ai.clu.core.util.newId
import ai.clu.core.voice.WakePhraseMatcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

enum class CommandKind(val label: String) {
    RESPONSE("Reply with a custom response"),
    ACTION("Run tool steps"),
    STARTUP("Runs when a voice session starts"),
    SHUTDOWN("Runs when a voice session ends"),
}

enum class MatchMode { EXACT, STARTS_WITH, CONTAINS, FUZZY }

@Serializable
data class CommandStep(val tool: String, val args: JsonObject = JsonObject(emptyMap()))

/**
 * Owner-defined command. Triggers are phrases (the first is the name, the rest aliases). Responses are templates
 * ({owner}, {greeting}, {farewell}, {time}); steps run in order through the normal permission checks.
 */
@Serializable
data class CustomCommand(
    val id: String = newId(),
    val name: String,
    val kind: CommandKind = CommandKind.RESPONSE,
    val triggers: List<String> = emptyList(),
    val match: MatchMode = MatchMode.FUZZY,
    val responses: List<String> = emptyList(),
    val steps: List<CommandStep> = emptyList(),
    val enabled: Boolean = true,
    val ownerOnly: Boolean = true,
)

class CommandRepository(private val store: CluStore) {
    private val db = store.db

    fun list(): List<CustomCommand> = db.query("SELECT json FROM custom_commands WHERE deleted = 0 ORDER BY created_at") {
        CluJson.decodeFromString(CustomCommand.serializer(), it.str(0))
    }

    fun get(id: String) = list().firstOrNull { it.id == id }

    fun save(c: CustomCommand): CustomCommand {
        require(c.name.isNotBlank()) { "command needs a name" }
        require(c.kind == CommandKind.STARTUP || c.kind == CommandKind.SHUTDOWN || c.triggers.any { it.isNotBlank() }) { "command needs at least one trigger phrase" }
        require(c.responses.isNotEmpty() || c.steps.isNotEmpty()) { "command needs a response or a step" }
        val now = store.clock.now()
        db.transaction {
            val hlc = store.stamp(TABLE, c.id)
            db.execute("""INSERT INTO custom_commands(id, json, created_at, updated_at, deleted, hlc, origin) VALUES(?,?,?,?,0,?,?)
                ON CONFLICT(id) DO UPDATE SET json=excluded.json, updated_at=excluded.updated_at, deleted=0, hlc=excluded.hlc, origin=excluded.origin""",
                listOf(c.id, CluJson.encodeToString(CustomCommand.serializer(), c), now, now, hlc, store.deviceId))
        }
        store.notifyChanged(TABLE)
        return c
    }

    fun delete(id: String) {
        db.transaction {
            val hlc = store.stamp(TABLE, id)
            db.execute("UPDATE custom_commands SET deleted = 1, updated_at = ?, hlc = ?, origin = ? WHERE id = ?", listOf(store.clock.now(), hlc, store.deviceId, id))
        }
        store.notifyChanged(TABLE)
    }

    /** Finds the enabled command whose trigger matches [utterance] best. */
    fun match(utterance: String): CustomCommand? {
        val u = WakePhraseMatcher.normalize(utterance)
        if (u.isEmpty()) return null
        var best: Pair<CustomCommand, Double>? = null
        for (c in list().filter { it.enabled && (it.kind == CommandKind.RESPONSE || it.kind == CommandKind.ACTION) }) {
            for (t in c.triggers.map(WakePhraseMatcher::normalize).filter { it.isNotEmpty() }) {
                val score = when (c.match) {
                    MatchMode.EXACT -> if (u == t) 1.0 else 0.0
                    MatchMode.STARTS_WITH -> if (u.startsWith(t)) 1.0 else 0.0
                    MatchMode.CONTAINS -> if (Regex("\\b${Regex.escape(t)}\\b").containsMatchIn(u)) 1.0 else 0.0
                    MatchMode.FUZZY -> WakePhraseMatcher.similarity(u, t).takeIf { it >= 0.82 } ?: 0.0
                }
                if (score > 0 && (best == null || score > best.second)) best = c to score
            }
        }
        return best?.first
    }

    fun byKind(kind: CommandKind) = list().filter { it.enabled && it.kind == kind }

    fun seedDefaults() {
        if (store.meta("commands_seeded") != null) return
        save(CustomCommand(name = "Status report", kind = CommandKind.ACTION, triggers = listOf("status report", "system status"),
            steps = listOf(CommandStep("device.status")), ownerOnly = false))
        save(CustomCommand(name = "Good night", kind = CommandKind.RESPONSE, triggers = listOf("good night clu", "good night"),
            responses = listOf("Good night, {owner}. I'll keep watch.", "Rest well, {owner}.")))
        store.setMeta("commands_seeded", "1")
    }

    data class Row(val id: String, val json: String, val deleted: Boolean, val hlc: String, val origin: String)
    fun row(id: String): Row? = db.query("SELECT id, json, deleted, hlc, origin FROM custom_commands WHERE id = ?", listOf(id)) {
        Row(it.str(0), it.str(1), (it.long(2) ?: 0) != 0L, it.str(3), it.str(4))
    }.firstOrNull()

    fun applyRemote(r: Row): Boolean {
        val local = row(r.id)
        if (local != null && local.hlc >= r.hlc) return false
        if (runCatching { CluJson.decodeFromString(CustomCommand.serializer(), r.json) }.isFailure) return false
        store.hlc.observe(r.hlc)
        db.transaction {
            db.execute("""INSERT INTO custom_commands(id, json, created_at, updated_at, deleted, hlc, origin) VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET json=excluded.json, updated_at=excluded.updated_at, deleted=excluded.deleted, hlc=excluded.hlc, origin=excluded.origin""",
                listOf(r.id, r.json, store.clock.now(), store.clock.now(), r.deleted, r.hlc, r.origin))
            store.logRemote(TABLE, r.id, r.hlc)
        }
        store.notifyChanged(TABLE)
        return true
    }

    companion object { const val TABLE = "custom_commands" }
}
