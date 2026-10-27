package ai.clu.ui

import ai.clu.core.config.*
import ai.clu.core.CluCore
import ai.clu.voice.AndroidAsr
import ai.clu.voice.VoicePacks
import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import kotlinx.serialization.json.*

class SettingsActivity : BaseActivity() {
    override fun onResume() {
        super.onResume()
        if (intent.getBooleanExtra("advanced", false)) advanced() else simple()
    }

    /** The normal Settings screen: a few task-oriented sections; engine internals live under "All settings". */
    private fun simple() = screen("Settings") {
        fun section(title: String, desc: String, open: () -> Unit) = card {
            p(title, C.TEXT, 16f); p(desc, C.MUTED, 12f)
            tappable(open)
        }
        section("Connection", "Who answers: Clu's server (LiveKit agent) or this device, and the server details.") { startActivity(Intent(this@SettingsActivity, ConnectionActivity::class.java)) }
        section(SimpleSettings.CONVERSATION.title, SimpleSettings.CONVERSATION.description) {
            startActivity(Intent(this@SettingsActivity, SettingsCategoryActivity::class.java).putExtra("section", SimpleSettings.CONVERSATION.id))
        }
        for (sec in listOf(SimpleSettings.MEMORY, SimpleSettings.TOOLS)) section(sec.title, sec.description) {
            startActivity(Intent(this@SettingsActivity, SettingsCategoryActivity::class.java).putExtra("section", sec.id))
        }
        section("Phone & tablet", "Pair this device with your other one, sync memories and settings, or move them with an encrypted sync file.") {
            startActivity(Intent(this@SettingsActivity, DevicesActivity::class.java))
        }
        if (core.settings.string(Settings.VOICE_MODE) != "livekit") {
            section("On-device voice", "Voice and language for the on-device engine.") { startActivity(Intent(this@SettingsActivity, VoiceActivity::class.java)) }
            section("On-device model", "The language model that runs on this phone, and optional downloads.") { startActivity(Intent(this@SettingsActivity, ModelsActivity::class.java)) }
        }
        section("Diagnostics", "Request timings, speech recognition and performance — for fixing problems.") { startActivity(Intent(this@SettingsActivity, DiagnosticsActivity::class.java)) }
        button("All settings (advanced)") { startActivity(Intent(this@SettingsActivity, SettingsActivity::class.java).putExtra("advanced", true)) }
    }

    private fun advanced() {
        screen("All settings") {
            p("Every setting below changes real behaviour. Each is listed with its effect and verified by an automated test (see docs/SETTINGS_REGISTER.md).")
            SettingCategory.values().forEach { cat ->
                val n = UiPolicy.visibleSettings(core.settings, cat).size
                if (n > 0) button("${cat.title}  ($n)") { startActivity(Intent(this@SettingsActivity, SettingsCategoryActivity::class.java).putExtra("cat", cat.name)) }
            }
            h1("Shortcuts")
            button("Wake words") { startActivity(Intent(this@SettingsActivity, WakeWordsActivity::class.java)) }
            button("Restore known-good configuration") {
                verifyOwner("Restore configuration") { ok -> if (ok) { val p = core.settings.restoreKnownGood(verified = true); toast(if (p.isEmpty()) "Restored." else p.joinToString("\n")) } }
            }
            button("Save current configuration as known-good") { core.settings.snapshot("Saved by owner", knownGood = true); toast("Saved.") }
        }
    }
}

class SettingsCategoryActivity : BaseActivity() {
    private var title = ""
    private var defs: () -> List<SettingDef> = { emptyList() }
    /** Simple sections show the setting and its current effect only; the full register metadata is for "All settings". */
    private var simple = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sec = SimpleSettings.byId(intent.getStringExtra("section"))
        if (sec != null) { title = sec.title; simple = true; defs = { sec.keys.mapNotNull { Settings[it] } } }
        else { val cat = SettingCategory.valueOf(intent.getStringExtra("cat")!!); title = cat.title; defs = { UiPolicy.visibleSettings(core.settings, cat) } }
    }

    override fun onResume() { super.onResume(); build() }

    private fun build() {
        screen(title) {
            for (d in defs()) card { control(d) }
            if (intent.getStringExtra("section") == SimpleSettings.TOOLS.id) card { searchKey() }
            button("Reset this section to defaults") {
                verifyOwner("Reset $title") { ok -> if (ok) { defs().forEach { core.settings.reset(it.key, verified = true) }; build() } }
            }
        }
    }

    /** The Brave Search key is a secret: kept in the encrypted secret store on this device, never in settings, exports or logs. */
    private fun LinearLayout.searchKey() {
        val has = !app.platform.secrets.get(CluCore.BRAVE_SEARCH_KEY).isNullOrBlank()
        p("Brave Search API key", C.TEXT, 15f)
        p((if (has) "A key is saved on this device." else "No key saved.") + " Needed only for the Brave provider; get one at brave.com/search/api. " +
            "Only your search words are sent to Brave — never memories or diary entries.", C.MUTED, 12f)
        row {
            smallButton(if (has) "Replace key" else "Enter key") {
                verifyOwner("Change the search API key") { ok -> if (ok) prompt("Brave Search API key", password = true) { k ->
                    app.platform.secrets.put(CluCore.BRAVE_SEARCH_KEY, k.trim().ifEmpty { null }); toast(if (k.isBlank()) "Key removed." else "Key saved."); build() } }
            }
            if (has) smallButton("Remove key") { verifyOwner("Remove the search API key") { ok -> if (ok) { app.platform.secrets.put(CluCore.BRAVE_SEARCH_KEY, null); build() } } }
        }
    }

    /** Honest availability notes for options that depend on this device or on optional downloads. */
    private fun capabilityNote(d: SettingDef): String? = when (d.key) {
        Settings.ACCELERATION -> "GPU backend not included in this build – CPU only."
        Settings.TTS_ENGINE, Settings.TTS_PIPER_VOICE, Settings.TTS_HINDI_VOICE ->
            app.speech.refresh(ai.clu.core.voice.VoiceConfig.from(core.settings)).let { pl -> pl.problem ?: if (d.key == Settings.TTS_HINDI_VOICE) pl.hindiNote else null }
        Settings.ASR_ENGINE -> if (!AndroidAsr(this).onDeviceAvailable()) "No Android on-device recognizer found on this device." else null
        Settings.SPEAKER_VERIFY -> if (!app.packs.isInstalled(VoicePacks.SPEAKER)) "Speaker model not installed (Voice screen) – voices can't be told apart yet." else null
        Settings.BACKGROUND_LISTENING -> if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) "Microphone permission not granted." else null
        else -> null
    }

    private fun apply(d: SettingDef, v: JsonElement) {
        when (val r = core.settings.set(d.key, v)) {
            SettingResult.Ok -> toast("${d.title}: ${d.apply.label}")
            is SettingResult.Invalid -> { toast("Not saved: ${r.reason}"); build() }
            SettingResult.RequiresVerification -> verifyOwner("Change \"${d.title}\"") { ok ->
                if (ok) { core.settings.set(d.key, v, verified = true); toast("${d.title}: ${d.apply.label}") }
                build()
            }
            SettingResult.UnknownSetting -> toast("Unknown setting")
        }
    }

    private fun LinearLayout.control(d: SettingDef) {
        p(d.title + if (d.sensitive) "  🔒" else "", C.TEXT, 15f)
        if (d.description.isNotBlank()) p(d.description, C.MUTED, 12f)
        val note = capabilityNote(d)
        val raw = core.settings.raw(d.key)
        when (val t = d.type) {
            SettingType.Bool -> check(if (raw.jsonPrimitive.boolean) "On" else "Off", raw.jsonPrimitive.boolean) { apply(d, JsonPrimitive(it)) }
            is SettingType.IntRange -> slider(t.min.toDouble(), t.max.toDouble(), raw.jsonPrimitive.double, integer = true) { apply(d, JsonPrimitive(it.toInt())) }
            is SettingType.FloatRange -> slider(t.min, t.max, raw.jsonPrimitive.double, integer = false) { apply(d, JsonPrimitive(it)) }
            is SettingType.Choice -> {
                val values = t.options
                spinner(values.map { (k, label) -> if (d.key == Settings.ACCELERATION && k == "gpu") "$label (unavailable)" else label },
                    values.indexOfFirst { it.first == raw.jsonPrimitive.content }) { i ->
                    val k = values[i].first
                    if (d.key == Settings.ACCELERATION && k == "gpu") { toast("GPU acceleration isn't available in this build."); build(); return@spinner }
                    apply(d, JsonPrimitive(k))
                }
            }
            is SettingType.Text -> {
                val e = field("", raw.jsonPrimitive.content, multiline = t.multiline)
                smallButton("Save") { apply(d, JsonPrimitive(e.text.toString())) }
            }
            SettingType.TimeOfDay -> smallButton(raw.jsonPrimitive.content) {
                val (h, m) = raw.jsonPrimitive.content.split(":").map { it.toInt() }
                TimePickerDialog(this@SettingsCategoryActivity, { _, hh, mm -> apply(d, JsonPrimitive("%02d:%02d".format(hh, mm))) }, h, m, true).show()
            }
            is SettingType.TextList -> {
                val items = raw.jsonArray.map { it.jsonPrimitive.content }
                p(if (items.isEmpty()) "(none)" else items.joinToString("\n") { "• $it" }, C.TEXT, 13f)
                smallButton("Edit (one per line)") {
                    prompt(d.title, items.joinToString("\n"), multiline = true) { txt ->
                        apply(d, JsonArray(txt.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { JsonPrimitive(it) }))
                    }
                }
            }
            is SettingType.Structured -> smallButton("Edit wake words") { startActivity(Intent(this@SettingsCategoryActivity, WakeWordsActivity::class.java)) }
        }
        if (simple) { note?.let { p("⚠ $it", C.WARN, 11f) }; if (!core.settings.isDefault(d.key)) smallButton("Reset to default") { apply(d, d.default) }; return }
        val meta = buildList {
            add("Effect: ${d.effect}"); add(d.apply.label)
            if (d.limitations.isNotBlank()) add("Limitation: ${d.limitations}")
            if (d.deviceOnlyEffect) add("Audible/visible effect depends on this device")
            note?.let { add("⚠ $it") }
        }
        p(meta.joinToString(" · "), if (note != null) C.WARN else C.MUTED, 11f)
        if (!core.settings.isDefault(d.key)) smallButton("Reset to default") { apply(d, d.default) }
    }

    private fun LinearLayout.slider(min: Double, max: Double, value: Double, integer: Boolean, onSet: (Double) -> Unit) {
        val steps = if (integer) (max - min).toInt().coerceAtMost(1000) else 100
        val label = TextView(context).apply { setTextColor(C.TEXT) }
        fun valueAt(p: Int) = if (integer) (min + p * (max - min) / steps).let { Math.round(it).toDouble() } else min + p * (max - min) / steps
        fun show(v: Double) { label.text = if (integer) v.toLong().toString() else "%.2f".format(v) }
        val bar = SeekBar(context).apply {
            this.max = steps; progress = ((value - min) / (max - min) * steps).toInt(); show(value)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { show(valueAt(p)) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { onSet(valueAt(s.progress)) }
            })
        }
        row {
            addView(bar, LinearLayout.LayoutParams(0, -2, 1f)); addView(label)
            smallButton("Set") { prompt("Exact value ($min – $max)", label.text.toString(), numeric = true) { it.toDoubleOrNull()?.let(onSet) ?: toast("Not a number") } }
        }
    }
}
