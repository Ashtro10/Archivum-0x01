package ai.clu.core.db

import ai.clu.core.util.Clock
import ai.clu.core.util.Hlc
import ai.clu.core.util.newId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Shared persistence context: database, clocks and this execution environment's device id.
 * Repositories record every change to synchronised tables in `change_log` via [stamp].
 */
class CluStore(val db: SqlDriver, val clock: Clock) {
    val deviceId: String
    var entityId: String
        private set
    val hlc: Hlc

    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 256)
    /** Emits table names after local or synced modifications. */
    val changes: SharedFlow<String> get() = _changes

    init {
        Schema.migrate(db)
        deviceId = meta("device_id") ?: newId().also { setMeta("device_id", it) }
        entityId = meta("entity_id") ?: newId().also { setMeta("entity_id", it); setMeta("entity_created_at", clock.now().toString()) }
        hlc = Hlc(clock, deviceId)
    }

    fun meta(key: String): String? = db.queryOne("SELECT value FROM kv_meta WHERE key = ?", listOf(key)) { it.string(0) }
    fun setMeta(key: String, value: String) =
        db.execute("INSERT INTO kv_meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", listOf(key, value))

    /** Allocates a new HLC timestamp for a local change and appends it to the change log. */
    fun stamp(table: String, rowId: String): String {
        val h = hlc.now()
        db.execute("INSERT INTO change_log(table_name, row_id, hlc) VALUES(?, ?, ?)", listOf(table, rowId, h))
        return h
    }

    /** Records a change that arrived from a peer (keeps its hlc; still logged so it is forwarded to other peers). */
    fun logRemote(table: String, rowId: String, hlc: String) {
        db.execute("INSERT INTO change_log(table_name, row_id, hlc) VALUES(?, ?, ?)", listOf(table, rowId, hlc))
    }

    fun notifyChanged(table: String) { _changes.tryEmit(table) }

    /**
     * Adopts the home device's entity id during pairing so both execution environments run the same Clu.
     * Any history this device already has is kept and merges into the shared history through normal sync.
     */
    fun adoptEntity(otherEntityId: String) {
        if (otherEntityId == entityId) return
        setMeta("previous_entity_id", entityId)
        setMeta("entity_id", otherEntityId)
        entityId = otherEntityId
    }
}
