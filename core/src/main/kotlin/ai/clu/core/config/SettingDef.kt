package ai.clu.core.config

import kotlinx.serialization.json.*

enum class SettingScope {
    /** Part of Clu's identity/preferences; synchronised between paired devices. */
    SHARED,
    /** Specific to this execution environment (hardware, local paths, device role). */
    DEVICE,
}

enum class ApplyMode(val label: String) {
    IMMEDIATE("Applies immediately"),
    NEXT_TURN("Applies from the next response"),
    MODEL_RELOAD("Reloads the model"),
    VOICE_RESTART("Restarts the listening session"),
}

sealed class SettingType {
    abstract fun validate(v: JsonElement): String?

    object Bool : SettingType() {
        override fun validate(v: JsonElement) =
            if (v is JsonPrimitive && !v.isString && v.booleanOrNull != null) null else "expected true/false"
    }

    data class IntRange(val min: Int, val max: Int) : SettingType() {
        override fun validate(v: JsonElement): String? {
            val i = (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return "expected an integer"
            return if (i in min..max) null else "must be between $min and $max"
        }
    }

    data class FloatRange(val min: Double, val max: Double) : SettingType() {
        override fun validate(v: JsonElement): String? {
            val d = (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return "expected a number"
            return if (d.isFinite() && d in min..max) null else "must be between $min and $max"
        }
    }

    data class Choice(val options: List<Pair<String, String>>) : SettingType() {
        val values get() = options.map { it.first }
        override fun validate(v: JsonElement): String? {
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return "expected one of $values"
            return if (s in values) null else "must be one of $values"
        }
    }

    data class Text(val maxLength: Int = 200, val allowEmpty: Boolean = true, val pattern: Regex? = null, val multiline: Boolean = false) : SettingType() {
        override fun validate(v: JsonElement): String? {
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return "expected text"
            if (!allowEmpty && s.isBlank()) return "must not be empty"
            if (s.length > maxLength) return "must be at most $maxLength characters"
            if (pattern != null && s.isNotEmpty() && !pattern.matches(s)) return "invalid format"
            return null
        }
    }

    /** "HH:MM" 24h. */
    object TimeOfDay : SettingType() {
        private val re = Regex("([01]\\d|2[0-3]):[0-5]\\d")
        override fun validate(v: JsonElement): String? {
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return "expected HH:MM"
            return if (re.matches(s)) null else "expected HH:MM (24h)"
        }
    }

    data class TextList(val maxItems: Int = 30, val maxLength: Int = 120, val minItems: Int = 0) : SettingType() {
        override fun validate(v: JsonElement): String? {
            val arr = v as? JsonArray ?: return "expected a list"
            if (arr.size > maxItems) return "at most $maxItems entries"
            if (arr.size < minItems) return "at least $minItems entries"
            for (e in arr) {
                val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return "entries must be text"
                if (s.isBlank()) return "entries must not be blank"
                if (s.length > maxLength) return "entries must be at most $maxLength characters"
            }
            return null
        }
    }

    /** Arbitrary structured JSON validated by a custom function (e.g. wake word list). */
    data class Structured(val validator: (JsonElement) -> String?) : SettingType() {
        override fun validate(v: JsonElement) = validator(v)
    }
}

/**
 * Declaration of one setting. Everything a user can configure is declared here, which lets the settings UI,
 * persistence, validation, synchronisation and the Settings Implementation Register share one source of truth.
 */
data class SettingDef(
    val key: String,
    val title: String,
    val category: SettingCategory,
    val type: SettingType,
    val default: JsonElement,
    val description: String,
    /** Subsystem that consumes the value. */
    val component: String,
    /** What observably changes when the value changes. */
    val effect: String,
    val scope: SettingScope = SettingScope.SHARED,
    val apply: ApplyMode = ApplyMode.IMMEDIATE,
    /** Changing requires owner verification (PIN / device credential). */
    val sensitive: Boolean = false,
    /** Test that verifies the behaviour (Class#method), used by the Settings Implementation Register. */
    val verifiedBy: String,
    /** Limitations to disclose in the UI and register. */
    val limitations: String = "",
    /** Whether the observable effect can only be confirmed on a physical device. */
    val deviceOnlyEffect: Boolean = false,
    /** Advanced settings are hidden unless developer diagnostics are enabled. */
    val advanced: Boolean = false,
) {
    init {
        require(type.validate(default) == null) { "Default for $key is invalid: ${type.validate(default)}" }
    }
}

enum class SettingCategory(val title: String) {
    GENERAL("General & personality"),
    VOICE("Voice"),
    WAKE("Wake words & activation"),
    MODEL("AI model"),
    MEMORY("Memory & diary"),
    SECURITY("Owner, people & security"),
    TOOLS("Device control & tools"),
    NETWORK("Internet & external services"),
    DEVICES("Phone & tablet"),
    PROACTIVE("Proactive behaviour"),
    LEARNING("Learning & adaptation"),
    DEVELOPER("Developer & diagnostics"),
}

fun jsonOf(v: Boolean) = JsonPrimitive(v)
fun jsonOf(v: Int) = JsonPrimitive(v)
fun jsonOf(v: Double) = JsonPrimitive(v)
fun jsonOf(v: String) = JsonPrimitive(v)
fun jsonOf(v: List<String>) = JsonArray(v.map { JsonPrimitive(it) })
