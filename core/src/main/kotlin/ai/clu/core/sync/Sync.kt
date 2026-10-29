package ai.clu.core.sync

import ai.clu.core.commands.CommandRepository
import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.conversation.Conversation
import ai.clu.core.conversation.ConversationRepository
import ai.clu.core.conversation.Message
import ai.clu.core.conversation.MessageMeta
import ai.clu.core.conversation.Role
import ai.clu.core.db.CluStore
import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.diag.DiagnosticsLog
import ai.clu.core.diary.DiaryEntry
import ai.clu.core.diary.DiaryRepository
import ai.clu.core.diary.DiarySource
import ai.clu.core.learning.PreferenceEngine
import ai.clu.core.memory.Memory
import ai.clu.core.memory.MemoryRepository
import ai.clu.core.persona.CharacterRepository
import ai.clu.core.security.PeopleRepository
import ai.clu.core.util.CluJson
import ai.clu.core.util.newId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.Base64

@Serializable
data class SyncRow(val table: String, val id: String, val hlc: String, val origin: String, val data: JsonObject)

data class PeerInfo(val deviceId: String, val name: String, val role: String, val publicKey: ByteArray, val pairedAt: Long,
                    val lastSeen: Long?, val lastAddress: String?, val lastSyncAt: Long?, val ackedSeq: Long, val lastSyncHlc: String)

data class SyncHistoryEntry(val id: String, val peer: String, val startedAt: Long, val finishedAt: Long?, val sent: Int, val received: Int,
                            val conflicts: Int, val error: String?)

/**
 * Collects outbound changes from the change log and applies inbound rows with the configured conflict policy.
 * Transport-independent (see SyncNode for the network session).
 */
class SyncEngine(
    private val store: CluStore,
    private val settings: SettingsStore,
    private val conversations: ConversationRepository,
    private val memories: MemoryRepository,
    private val characters: CharacterRepository,
    private val people: PeopleRepository,
    private val prefs: PreferenceEngine,
    private val commands: CommandRepository,
    private val diag: DiagnosticsLog?,
    private val diary: DiaryRepository? = null,
) {
    private val db = store.db

    // ------------------------------------------------------------------ identity keys
    fun identityKey(vault: KeyVault): java.security.KeyPair {
        val priv = store.meta("sync_priv"); val pub = store.meta("sync_pub")
        if (priv != null && pub != null) {
            return java.security.KeyPair(Crypto.publicFrom(b64d(pub)), Crypto.privateFrom(vault.unprotect(b64d(priv))))
        }
        val kp = Crypto.newKeyPair()
        store.setMeta("sync_pub", b64(kp.public.encoded))
        store.setMeta("sync_priv", b64(vault.protect(kp.private.encoded)))
        return kp
    }

    // ------------------------------------------------------------------ peers
    fun peers(): List<PeerInfo> = db.query("SELECT device_id, name, role, public_key, paired_at, last_seen, last_address, last_sync_at, acked_seq, received_hlc FROM sync_peers") {
        PeerInfo(it.str(0), it.str(1), it.str(2), it.blob(3)!!, it.lng(4), it.long(5), it.string(6), it.long(7), it.lng(8), it.str(9))
    }

    fun peer(id: String) = peers().firstOrNull { it.deviceId == id }

    fun addPeer(deviceId: String, name: String, role: String, publicKey: ByteArray) {
        db.execute("""INSERT INTO sync_peers(device_id, name, role, public_key, paired_at) VALUES(?,?,?,?,?)
            ON CONFLICT(device_id) DO UPDATE SET name=excluded.name, role=excluded.role, public_key=excluded.public_key, paired_at=excluded.paired_at,
            acked_seq=0, received_hlc=''""", listOf(deviceId, name, role, publicKey, store.clock.now()))
        store.notifyChanged("sync_peers")
    }

    fun removePeer(deviceId: String) { db.execute("DELETE FROM sync_peers WHERE device_id = ?", listOf(deviceId)); store.notifyChanged("sync_peers") }

    fun markSeen(deviceId: String, address: String?, name: String? = null, role: String? = null) {
        db.execute("UPDATE sync_peers SET last_seen = ?, last_address = COALESCE(?, last_address), name = COALESCE(?, name), role = COALESCE(?, role) WHERE device_id = ?",
            listOf(store.clock.now(), address, name, role, deviceId))
    }

    fun setAcked(deviceId: String, seq: Long) = db.execute("UPDATE sync_peers SET acked_seq = MAX(acked_seq, ?) WHERE device_id = ?", listOf(seq, deviceId))

    fun completeSync(deviceId: String) = db.execute("UPDATE sync_peers SET last_sync_at = ?, received_hlc = ? WHERE device_id = ?",
        listOf(store.clock.now(), store.hlc.now(), deviceId)).also { store.notifyChanged("sync_peers") }

    fun maxSeq(): Long = db.queryOneLong("SELECT COALESCE(MAX(seq), 0) FROM change_log")

    // ------------------------------------------------------------------ outbound
    private fun tableEnabled(table: String) = when (table) {
        MemoryRepository.TABLE -> settings.bool(Settings.SYNC_MEMORIES)
        ConversationRepository.CONV, ConversationRepository.MSG -> settings.bool(Settings.SYNC_CONVERSATIONS)
        SettingsStore.TABLE -> settings.bool(Settings.SYNC_SETTINGS)
        CharacterRepository.TABLE -> settings.bool(Settings.SYNC_CHARACTERS)
        PreferenceEngine.TABLE -> settings.bool(Settings.SYNC_PREFERENCES)
        CommandRepository.TABLE -> settings.bool(Settings.SYNC_COMMANDS)
        PeopleRepository.TABLE -> settings.bool(Settings.SYNC_PEOPLE)
        DiaryRepository.TABLE -> settings.bool(Settings.SYNC_DIARY) && diary != null
        else -> false
    }

    /**
     * Queues every row of [table] again (with its current version), e.g. after its sync scope was switched on, so rows
     * the peer skipped while it was off are sent. Receivers ignore versions they already have.
     */
    fun requeue(table: String) {
        val idCol = when (table) { SettingsStore.TABLE, PreferenceEngine.TABLE -> "key"; else -> "id" }
        val where = if (table == SettingsStore.TABLE) " WHERE scope = 'SHARED'" else ""
        if (table !in SCOPED) return
        val ids = db.query("SELECT $idCol, hlc FROM $table$where") { it.str(0) to it.str(1) }
        db.transaction { ids.forEach { (id, h) -> store.logRemote(table, id, h) } }
    }

    /**
     * A scope that was off during a sync skipped its changes (the log moved past them). When it is on again, its rows
     * are queued once more so nothing written in the meantime is lost; receivers ignore versions they already have.
     */
    private fun requeueReenabledScopes() {
        for (t in SCOPED) {
            val key = "sync_scope_skipped:$t"
            if (!tableEnabled(t)) { if (store.meta(key) != "1") store.setMeta(key, "1") }
            else if (store.meta(key) == "1") { requeue(t); store.setMeta(key, "0") }
        }
    }

    /** Every current row of [tables] (including deletions), for a sync file. */
    fun allRows(tables: Set<String>): List<SyncRow> {
        val ids = buildList {
            if (MemoryRepository.TABLE in tables) addAll(db.query("SELECT id FROM memories") { MemoryRepository.TABLE to it.str(0) })
            if (SettingsStore.TABLE in tables) addAll(db.query("SELECT key FROM settings WHERE scope = 'SHARED'") { SettingsStore.TABLE to it.str(0) })
            if (DiaryRepository.TABLE in tables) addAll(db.query("SELECT id FROM diary_entries") { DiaryRepository.TABLE to it.str(0) })
        }
        return ids.mapNotNull { (t, id) -> rowFor(t, id) }
    }

    /** Changes after [sinceSeq], de-duplicated by row (latest state), oldest first, at most [limit] rows. Returns rows + last seq covered. */
    fun changesSince(sinceSeq: Long, limit: Int = 200): Pair<List<SyncRow>, Long> {
        // Diary sync switched on for the first time: queue every entry once (entries from before 1.5.0 were never sent).
        val diaryOn = settings.bool(Settings.SYNC_DIARY) && diary != null
        if (diaryOn && store.meta("diary_sync_queued") != "1") { requeue(DiaryRepository.TABLE); store.setMeta("diary_sync_queued", "1") }
        requeueReenabledScopes()
        val entries = db.query("SELECT seq, table_name, row_id FROM change_log WHERE seq > ? ORDER BY seq LIMIT ?", listOf(sinceSeq, limit * 4)) {
            Triple(it.lng(0), it.str(1), it.str(2))
        }
        if (entries.isEmpty()) return emptyList<SyncRow>() to sinceSeq
        val seen = LinkedHashSet<Pair<String, String>>()
        var lastSeq = sinceSeq
        for ((seq, t, id) in entries) { if (seen.size >= limit && (t to id) !in seen) break; seen += t to id; lastSeq = seq }
        // Conversations before their messages so foreign references resolve on the receiver.
        val ordered = seen.sortedBy { if (it.first == ConversationRepository.CONV) 0 else 1 }
        val rows = ordered.filter { tableEnabled(it.first) }.mapNotNull { (t, id) -> rowFor(t, id) }
        return rows to lastSeq
    }

    private fun rowFor(table: String, id: String): SyncRow? = when (table) {
        MemoryRepository.TABLE -> memories.get(id)?.let { SyncRow(table, id, it.hlc, it.origin, CluJson.encodeToJsonElement(Memory.serializer(), it).jsonObject) }
        ConversationRepository.CONV -> conversations.get(id)?.let { c -> SyncRow(table, id, c.hlc, c.origin, buildJsonObject {
            put("title", c.title); put("personaId", c.personaId); put("archived", c.archived); put("createdAt", c.createdAt); put("updatedAt", c.updatedAt); put("deleted", c.deleted) }) }
        ConversationRepository.MSG -> conversations.message(id)?.let { m -> SyncRow(table, id, m.hlc, m.origin, buildJsonObject {
            put("conversationId", m.conversationId); put("role", m.role.name); put("content", m.content); put("createdAt", m.createdAt)
            put("meta", CluJson.encodeToJsonElement(MessageMeta.serializer(), m.meta)); put("deleted", m.deleted) }) }
        SettingsStore.TABLE -> settings.rowFor(id)?.let { SyncRow(table, id, it.hlc, it.origin, buildJsonObject { put("value", it.value) }) }
        CharacterRepository.TABLE -> characters.row(id)?.let { SyncRow(table, id, it.hlc, it.origin, buildJsonObject { put("json", it.json); put("deleted", it.deleted) }) }
        PeopleRepository.TABLE -> people.row(id)?.let { SyncRow(table, id, it.hlc, it.origin, buildJsonObject { put("json", it.json); put("deleted", it.deleted) }) }
        PreferenceEngine.TABLE -> prefs.row(id)?.let { SyncRow(table, id, it.hlc, it.origin, buildJsonObject {
            put("value", it.value); put("source", it.source); put("note", it.note); put("deleted", it.deleted) }) }
        CommandRepository.TABLE -> commands.row(id)?.let { SyncRow(table, id, it.hlc, it.origin, buildJsonObject { put("json", it.json); put("deleted", it.deleted) }) }
        DiaryRepository.TABLE -> diary?.row(id)?.let { e -> SyncRow(table, id, e.hlc, e.origin.ifEmpty { store.deviceId }, buildJsonObject {
            put("date", e.date); put("title", e.title); put("body", e.body); put("source", e.source.id); e.conversationId?.let { put("conversationId", it) }
            e.memoryId?.let { put("memoryId", it) }; put("createdAt", e.createdAt); put("updatedAt", e.updatedAt); put("deleted", e.deleted) }) }
        else -> null
    }

    // ------------------------------------------------------------------ inbound
    data class ApplyStats(var applied: Int = 0, var skipped: Int = 0, var conflicts: Int = 0)

    /** Applies rows from [peerId]. Idempotent: duplicates and older versions are ignored. */
    fun apply(rows: List<SyncRow>, peerId: String, tables: Set<String>? = null): ApplyStats = db.transaction { applyBatch(rows, peerId, tables) }

    /**
     * One transaction per received batch: atomic (a dropped connection never leaves half a batch) and fast.
     * [tables], when given (sync files), replaces the per-scope settings: only those tables are applied.
     */
    private fun applyBatch(rows: List<SyncRow>, peerId: String, tables: Set<String>?): ApplyStats {
        val st = ApplyStats()
        val peer = peer(peerId)
        val policy = settings.string(Settings.CONFLICT_POLICY)
        val iAmHome = settings.string(Settings.DEVICE_ROLE) == "home"
        val peerIsHome = peer?.role == "home"
        for (r in rows) {
            if (if (tables != null) r.table !in tables else !tableEnabled(r.table)) { st.skipped++; continue }
            val local = rowFor(r.table, r.id)
            // Concurrent edit = both sides changed the row since the last successful sync with this peer.
            val concurrent = local != null && local.hlc != r.hlc && local.origin == store.deviceId &&
                peer != null && local.hlc > peer.lastSyncHlc && r.origin != store.deviceId
            if (concurrent) {
                st.conflicts++
                when {
                    policy == "home" && iAmHome -> { st.skipped++; continue }
                    policy == "home" && peerIsHome -> { if (applyRow(r, force = true)) st.applied++ else st.skipped++; continue }
                    policy == "keep_both" && r.table == DiaryRepository.TABLE && diary != null -> {
                        val incoming = diaryFrom(r)
                        val mine = diary?.row(r.id)
                        if (mine != null && !incoming.deleted && !mine.deleted && (mine.body != incoming.body || mine.title != incoming.title)) {
                            runCatching { diary?.writeConflictCopy(incoming) }
                            st.applied++; continue
                        }
                    }
                    policy == "keep_both" && r.table == MemoryRepository.TABLE -> {
                        val incoming = CluJson.decodeFromJsonElement(Memory.serializer(), r.data)
                        val mine = memories.get(r.id)
                        if (mine != null && !incoming.deleted && (mine.content != incoming.content || mine.title != incoming.title)) {
                            runCatching { memories.writeConflictCopy(incoming.copy(id = r.id)) }
                            st.applied++; continue
                        }
                    }
                }
            }
            if (applyRow(r, force = false)) st.applied++ else st.skipped++
        }
        diag?.i("sync", "applied ${st.applied}, skipped ${st.skipped}, conflicts ${st.conflicts} from $peerId")
        return st
    }

    /**
     * A memory edited on a device still running a version before 1.8.0 arrives without the personal-archive fields
     * (1.8.0 always sends them). Keep this device's values for those fields instead of resetting them to defaults;
     * if the older device changed the event time, that change wins.
     */
    private fun fromOlderVersion(incoming: Memory, d: JsonObject): Memory {
        if ("eventDate" in d) return incoming
        val local = memories.get(incoming.id) ?: return incoming
        val date = if (incoming.eventAt != local.eventAt && incoming.eventAt != null) ai.clu.core.history.HistoricalDate.UNKNOWN else local.eventDate
        return incoming.copy(eventDate = date, told = local.told, places = local.places, certainty = local.certainty, importance = local.importance,
            significance = local.significance, threadKind = local.threadKind, links = local.links, mergedInto = local.mergedInto)
    }

    private fun applyRow(r: SyncRow, force: Boolean): Boolean {
        // "force" (home-wins) adopts the peer version even if our hlc is newer, by re-stamping it above ours.
        val hlc = if (force) { store.hlc.observe(r.hlc); val local = rowFor(r.table, r.id)?.hlc ?: ""; if (local > r.hlc) store.hlc.now() else r.hlc } else r.hlc
        val d = r.data
        fun s(k: String) = d[k]?.jsonPrimitive?.contentOrNull ?: ""
        fun b(k: String) = d[k]?.jsonPrimitive?.booleanOrNull ?: false
        fun l(k: String) = d[k]?.jsonPrimitive?.longOrNull ?: 0L
        return runCatching {
            when (r.table) {
                MemoryRepository.TABLE -> memories.applyRemote(fromOlderVersion(CluJson.decodeFromJsonElement(Memory.serializer(), d), d).copy(hlc = hlc, origin = r.origin))
                ConversationRepository.CONV -> conversations.applyRemoteConversation(Conversation(r.id, s("title"), s("personaId").ifEmpty { "clu" }, b("archived"),
                    l("createdAt"), l("updatedAt"), b("deleted"), hlc, r.origin))
                ConversationRepository.MSG -> conversations.applyRemoteMessage(Message(r.id, s("conversationId"), Role.valueOf(s("role")), s("content"), l("createdAt"),
                    d["meta"]?.let { CluJson.decodeFromJsonElement(MessageMeta.serializer(), it) } ?: MessageMeta(), b("deleted"), hlc, r.origin))
                SettingsStore.TABLE -> settings.applyRemote(SettingsStore.Row(r.id, s("value"), hlc, r.origin))
                CharacterRepository.TABLE -> characters.applyRemote(CharacterRepository.Row(r.id, s("json"), b("deleted"), hlc, r.origin))
                PeopleRepository.TABLE -> people.applyRemote(PeopleRepository.Row(r.id, s("json"), b("deleted"), hlc, r.origin))
                PreferenceEngine.TABLE -> prefs.applyRemote(PreferenceEngine.Row(r.id, s("value"), s("source"), s("note"), b("deleted"), hlc, r.origin))
                CommandRepository.TABLE -> commands.applyRemote(CommandRepository.Row(r.id, s("json"), b("deleted"), hlc, r.origin))
                DiaryRepository.TABLE -> diary?.applyRemote(diaryFrom(r).copy(hlc = hlc)) ?: false
                else -> false
            }
        }.getOrElse { diag?.w("sync", "row ${r.table}/${r.id} rejected: ${it.message}"); false }
    }

    private fun diaryFrom(r: SyncRow): DiaryEntry {
        val d = r.data
        fun s(k: String) = d[k]?.jsonPrimitive?.contentOrNull
        return DiaryEntry(r.id, s("date") ?: "", s("title") ?: "", s("body") ?: "", DiarySource.of(s("source") ?: "manual"), s("conversationId"), s("memoryId"),
            d["createdAt"]?.jsonPrimitive?.longOrNull ?: 0, d["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0, d["deleted"]?.jsonPrimitive?.booleanOrNull ?: false,
            r.hlc, r.origin)
    }

    // ------------------------------------------------------------------ history
    fun startHistory(peer: String): String = newId().also {
        db.execute("INSERT INTO sync_history(id, peer, started_at) VALUES(?,?,?)", listOf(it, peer, store.clock.now()))
    }

    fun finishHistory(id: String, sent: Int, received: Int, conflicts: Int, error: String?) {
        db.execute("UPDATE sync_history SET finished_at = ?, sent = ?, received = ?, conflicts = ?, error = ? WHERE id = ?",
            listOf(store.clock.now(), sent, received, conflicts, error, id))
        db.execute("DELETE FROM sync_history WHERE id NOT IN (SELECT id FROM sync_history ORDER BY started_at DESC LIMIT 200)")
        store.notifyChanged("sync_history")
    }

    fun history(limit: Int = 50): List<SyncHistoryEntry> = db.query(
        "SELECT id, peer, started_at, finished_at, sent, received, conflicts, error FROM sync_history ORDER BY started_at DESC LIMIT $limit") {
        SyncHistoryEntry(it.str(0), it.str(1), it.lng(2), it.long(3), it.lng(4).toInt(), it.lng(5).toInt(), it.lng(6).toInt(), it.string(7))
    }

    companion object {
        private val SCOPED = listOf(MemoryRepository.TABLE, ConversationRepository.CONV, ConversationRepository.MSG, SettingsStore.TABLE,
            CharacterRepository.TABLE, PreferenceEngine.TABLE, CommandRepository.TABLE, PeopleRepository.TABLE, DiaryRepository.TABLE)
        fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
        fun b64d(s: String): ByteArray = Base64.getDecoder().decode(s)
    }
}

private fun ai.clu.core.db.SqlDriver.queryOneLong(sql: String): Long = query(sql) { it.long(0) ?: 0L }.firstOrNull() ?: 0L
