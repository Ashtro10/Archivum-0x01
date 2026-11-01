package ai.clu.desktop.voice

import ai.clu.core.db.SqlDriver
import ai.clu.core.db.str
import ai.clu.core.voice.WakePhraseMatcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How CLU listens: always (continuous), only while a key is held (push-to-talk), only after a wake word, or only when the microphone is switched on (manual). */
enum class ListenMode { CONTINUOUS, PUSH_TO_TALK, WAKE_WORD, MANUAL }

/**
 * CLU's voice settings, kept on the PC (the one place speech is produced) and used by every device: the desktop window and
 * the phone hear the same voice because the PC speaks for both. Every field here changes what the voice pipeline does.
 */
@Serializable
data class VoiceSettings(
    val voice: String = VoiceCatalog.DEFAULT_VOICE,
    /** 0.7–1.4 (1 = the voice's natural rate). */
    val speed: Double = 1.0,
    /** 0.2–1.5 output gain. */
    val volume: Double = 1.0,
    /** Speech recognition: "en" (Kroko streaming English) or a Whisper language code / "auto" (needs the optional Whisper model). */
    val language: String = "en",
    val mode: ListenMode = ListenMode.CONTINUOUS,
    /** Silence (ms) that ends what the owner is saying. */
    val endSilenceMs: Int = 700,
    /** Speech needed while CLU talks before it stops to listen (barge-in), ms. Higher = fewer false interruptions. */
    val bargeInMs: Int = 400,
    val wakeWords: List<String> = listOf("Clue", "Buddy", "Friend"),
    /** 0.55 (eager) – 0.9 (strict) similarity a heard phrase needs to count as a wake word. */
    val wakeThreshold: Double = 0.72,
    val farewells: List<String> = listOf("goodbye", "goodbye for now", "bye", "bye clue", "that's all", "that will be all", "see you later", "good night", "stop listening"),
    /** "say X as Y": spelling hints applied before speaking. */
    val pronunciations: Map<String, String> = mapOf("CLU" to "Clue"),
    /** Names and words the recogniser should get right (people, places, fiction): misheard versions are corrected or asked about. */
    val vocabulary: List<String> = emptyList(),
    /** Phones on this network may connect (LiveKit and the phone token endpoint also listen on this PC's private address). */
    val phoneAccess: Boolean = false,
) {
    fun validated(): VoiceSettings = copy(
        voice = voice.takeIf { VoiceCatalog.voice(it) != null } ?: VoiceCatalog.DEFAULT_VOICE,
        speed = speed.coerceIn(0.7, 1.4), volume = volume.coerceIn(0.2, 1.5),
        language = language.lowercase().takeIf { it.matches(Regex("[a-z]{2,3}|auto")) } ?: "en",
        endSilenceMs = endSilenceMs.coerceIn(300, 2500), bargeInMs = bargeInMs.coerceIn(120, 1200),
        wakeWords = wakeWords.map { it.trim() }.filter { it.isNotEmpty() && it.length <= 30 }.distinct().take(5).ifEmpty { listOf("Clue") },
        wakeThreshold = wakeThreshold.coerceIn(0.55, 0.9),
        farewells = farewells.map { it.trim() }.filter { it.isNotEmpty() && it.length <= 40 }.distinct().take(15),
        vocabulary = vocabulary.map { it.trim().take(60) }.filter { it.length >= 3 }.distinct().take(200),
        pronunciations = pronunciations.filterKeys { it.isNotBlank() && it.length <= 40 }.mapValues { it.value.take(60) }.entries.take(50).associate { it.key to it.value },
    )

    /** The farewell check: the utterance is a farewell phrase, starts or ends with one, and is short. */
    fun isFarewell(text: String): Boolean {
        val t = WakePhraseMatcher.normalize(text); if (t.isEmpty() || t.split(' ').size > 7) return false
        return farewells.any { p -> val n = WakePhraseMatcher.normalize(p); n.isNotEmpty() && (WakePhraseMatcher.similarity(t, n) >= 0.8 || t.startsWith("$n ") || t.endsWith(" $n") || t == n) }
    }

    /** Applies the pronunciation hints (whole words, case-insensitive). */
    fun pronounce(text: String): String = pronunciations.entries.fold(text) { acc, (k, v) -> acc.replace(Regex("(?i)\\b${Regex.escape(k)}\\b"), v) }
}

class VoiceSettingsStore(private val db: SqlDriver) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile private var cached: VoiceSettings? = null
    fun get(): VoiceSettings = cached ?: (db.query("SELECT value FROM kv_meta WHERE key = 'voice.settings'") { it.str(0) }.firstOrNull()
        ?.let { runCatching { json.decodeFromString(VoiceSettings.serializer(), it) }.getOrNull() } ?: VoiceSettings()).validated().also { cached = it }
    @Synchronized fun put(s: VoiceSettings): VoiceSettings {
        val v = s.validated()
        db.execute("INSERT INTO kv_meta(key, value) VALUES('voice.settings', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", listOf(json.encodeToString(VoiceSettings.serializer(), v)))
        cached = v; return v
    }
}
