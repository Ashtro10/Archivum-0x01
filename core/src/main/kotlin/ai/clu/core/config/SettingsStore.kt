package ai.clu.core.config

import ai.clu.core.db.CluStore
import ai.clu.core.db.str
import ai.clu.core.util.CluJson
import ai.clu.core.util.newId
import ai.clu.core.voice.WakeWord
import kotlinx.coroutines.flow.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

sealed class SettingResult {
    object Ok : SettingResult()
    data class Invalid(val reason: String) : SettingResult()
    object RequiresVerification : SettingResult()
    object UnknownSetting : SettingResult()
}

data class ConfigSnapshot(val id: String, val createdAt: Long, val label: String, val knownGood: Boolean)

/**
 * Single source of truth for configuration values. Values are validated against [Settings], persisted in SQLite,
 * exposed as a [StateFlow] so services react to changes, and SHARED values are recorded for synchronisation.
 */
class SettingsStore(private val store: CluStore) {
    private val db = store.db
    private val state = MutableStateFlow(load())
    val values: StateFlow<Map<String, JsonElement>> get() = state

    private fun load(): Map<String, JsonElement> =
        db.query("SELECT key, value FROM settings") { it.str(0) to CluJson.parseToJsonElement(it.str(1)) }.toMap()

    fun raw(key: String): JsonElement = state.value[key] ?: Settings[key]?.default
        ?: throw IllegalArgumentException("Unknown setting $key")

    fun isDefault(key: String) = key !in state.value

    fun bool(key: String): Boolean = raw(key).jsonPrimitive.boolean
    fun int(key: String): Int = raw(key).jsonPrimitive.int
    fun double(key: String): Double = raw(key).jsonPrimitive.double
    fun string(key: String): String = raw(key).jsonPrimitive.content
    fun list(key: String): List<String> = raw(key).jsonArray.map { it.jsonPrimitive.content }
    fun wakeWords(): List<WakeWord> = CluJson.decodeFromJsonElement(ListSerializer(WakeWord.serializer()), raw(Settings.WAKE_WORDS))

    fun observe(key: String): Flow<JsonElement> = state.map { it[key] ?: raw(key) }.distinctUntilChanged()
    /** Emits whenever any of the given keys changes (used by services that rebuild on configuration change). */
    fun observeAny(keys: Collection<String>): Flow<Map<String, JsonElement>> =
        state.map { m -> keys.associateWith { m[it] ?: raw(it) } }.distinctUntilChanged()

    fun validate(key: String, value: JsonElement): SettingResult {
        val def = Settings[key] ?: return SettingResult.UnknownSetting
        return def.type.validate(value)?.let { SettingResult.Invalid(it) } ?: SettingResult.Ok
    }

    /**
     * Sets a value. Sensitive settings require [verified] = true, which callers obtain from AuthorizationPolicy after a
     * PIN/device-credential check.
     */
    fun set(key: String, value: JsonElement, verified: Boolean = false): SettingResult {
        val def = Settings[key] ?: return SettingResult.UnknownSetting
        def.type.validate(value)?.let { return SettingResult.Invalid(it) }
        if (def.sensitive && !verified) return SettingResult.RequiresVerification
        if (state.value[key] == value) return SettingResult.Ok
        db.transaction {
            val hlc = if (def.scope == SettingScope.SHARED) store.stamp(TABLE, key) else store.hlc.now()
            db.execute(
                "INSERT INTO settings(key, value, scope, updated_at, hlc, origin) VALUES(?,?,?,?,?,?) " +
                    "ON CONFLICT(key) DO UPDATE SET value=excluded.value, scope=excluded.scope, updated_at=excluded.updated_at, hlc=excluded.hlc, origin=excluded.origin",
                listOf(key, value.toString(), def.scope.name, store.clock.now(), hlc, store.deviceId)
            )
        }
        state.value = state.value + (key to value)
        store.notifyChanged(TABLE)
        return SettingResult.Ok
    }

    fun set(key: String, value: Boolean, verified: Boolean = false) = set(key, jsonOf(value), verified)
    fun set(key: String, value: Int, verified: Boolean = false) = set(key, jsonOf(value), verified)
    fun set(key: String, value: Double, verified: Boolean = false) = set(key, jsonOf(value), verified)
    fun set(key: String, value: String, verified: Boolean = false) = set(key, jsonOf(value), verified)
    fun set(key: String, value: List<String>, verified: Boolean = false) = set(key, jsonOf(value), verified)
    fun setWakeWords(words: List<WakeWord>) =
        set(Settings.WAKE_WORDS, CluJson.encodeToJsonElement(ListSerializer(WakeWord.serializer()), words))

    fun reset(key: String, verified: Boolean = false): SettingResult {
        val def = Settings[key] ?: return SettingResult.UnknownSetting
        return set(key, def.default, verified)
    }

    // ----------------------------------------------------------------- sync support

    data class Row(val key: String, val value: String, val hlc: String, val origin: String)

    fun rowFor(key: String): Row? = db.query("SELECT key, value, hlc, origin FROM settings WHERE key = ? AND scope = 'SHARED'", listOf(key)) {
        Row(it.str(0), it.str(1), it.str(2), it.str(3))
    }.firstOrNull()

    /** Applies a synchronised value if it is newer than the local one. Returns true if applied. */
    fun applyRemote(row: Row): Boolean {
        val def = Settings[row.key]
        if (def != null && def.scope != SettingScope.SHARED) return false
        val value = runCatching { CluJson.parseToJsonElement(row.value) }.getOrNull() ?: return false
        if (def != null && def.type.validate(value) != null) return false
        val local = db.query("SELECT hlc FROM settings WHERE key = ?", listOf(row.key)) { it.str(0) }.firstOrNull()
        if (local != null && local >= row.hlc) return false
        store.hlc.observe(row.hlc)
        db.execute(
            "INSERT INTO settings(key, value, scope, updated_at, hlc, origin) VALUES(?,?,'SHARED',?,?,?) " +
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at, hlc=excluded.hlc, origin=excluded.origin",
            listOf(row.key, row.value, store.clock.now(), row.hlc, row.origin)
        )
        store.logRemote(TABLE, row.key, row.hlc)
        state.value = state.value + (row.key to value)
        store.notifyChanged(TABLE)
        return true
    }

    // ----------------------------------------------------------------- versioned configurations

    fun exportJson(includeDevice: Boolean = true): String {
        val obj = buildJsonObject {
            for ((k, v) in state.value) {
                val def = Settings[k]
                if (!includeDevice && def?.scope == SettingScope.DEVICE) continue
                put(k, v)
            }
        }
        return obj.toString()
    }

    /** Imports values; invalid or unknown entries are skipped and reported. Sensitive values need [verified]. */
    fun importJson(json: String, verified: Boolean = false): List<String> {
        val problems = mutableListOf<String>()
        val obj = runCatching { CluJson.parseToJsonElement(json).jsonObject }.getOrElse { return listOf("not a JSON object") }
        for ((k, v) in obj) {
            when (val r = set(k, v, verified)) {
                SettingResult.Ok -> {}
                is SettingResult.Invalid -> problems += "$k: ${r.reason}"
                SettingResult.RequiresVerification -> problems += "$k: requires verification"
                SettingResult.UnknownSetting -> problems += "$k: unknown setting"
            }
        }
        // Keys absent from the snapshot return to their defaults so a restore is exact.
        for (k in state.value.keys - obj.keys) {
            val def = Settings[k] ?: continue
            if (def.sensitive && !verified) continue
            set(k, def.default, verified)
        }
        return problems
    }

    fun snapshot(label: String, knownGood: Boolean = false): ConfigSnapshot {
        val s = ConfigSnapshot(newId(), store.clock.now(), label, knownGood)
        db.execute("INSERT INTO settings_snapshots(id, created_at, label, json, known_good) VALUES(?,?,?,?,?)",
            listOf(s.id, s.createdAt, label, exportJson(), if (knownGood) 1 else 0))
        db.execute("DELETE FROM settings_snapshots WHERE known_good = 0 AND id NOT IN (SELECT id FROM settings_snapshots ORDER BY created_at DESC LIMIT 30)")
        return s
    }

    fun snapshots(): List<ConfigSnapshot> = db.query("SELECT id, created_at, label, known_good FROM settings_snapshots ORDER BY created_at DESC") {
        ConfigSnapshot(it.str(0), it.long(1) ?: 0, it.str(2), (it.long(3) ?: 0) != 0L)
    }

    fun markKnownGood(id: String) {
        db.transaction {
            db.execute("UPDATE settings_snapshots SET known_good = 0")
            db.execute("UPDATE settings_snapshots SET known_good = 1 WHERE id = ?", listOf(id))
        }
    }

    fun restore(id: String, verified: Boolean = false): List<String> {
        val json = db.query("SELECT json FROM settings_snapshots WHERE id = ?", listOf(id)) { it.str(0) }.firstOrNull()
            ?: return listOf("snapshot not found")
        snapshot("Before restore", knownGood = false)
        return importJson(json, verified)
    }

    fun restoreKnownGood(verified: Boolean = false): List<String> {
        val id = db.query("SELECT id FROM settings_snapshots WHERE known_good = 1 ORDER BY created_at DESC LIMIT 1") { it.str(0) }.firstOrNull()
            ?: return listOf("no known-good configuration saved")
        return restore(id, verified)
    }

    companion object {
        const val TABLE = "settings"
    }
}
