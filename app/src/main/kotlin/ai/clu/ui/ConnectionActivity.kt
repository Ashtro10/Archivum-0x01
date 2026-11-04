package ai.clu.ui

import ai.clu.core.config.SettingResult
import ai.clu.core.config.Settings
import ai.clu.core.live.AgentPhase
import ai.clu.core.live.Link
import ai.clu.core.live.LiveDiagnostics
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Settings → Connection: which engine answers (Clu's server or this device) and the server details. The device key
 * is stored encrypted (Android Keystore); the LiveKit API secret never reaches the phone.
 */
class ConnectionActivity : BaseActivity() {
    private var watch: Job? = null
    private var diagWatch: Job? = null

    override fun onResume() { super.onResume(); build() }

    private fun build(): Unit = screen("Connection") {
        val server = app.liveKit.enabled(); val mode = core.settings.string(Settings.VOICE_MODE)
        h1("Who answers")
        engineCard(this, "Automatic (recommended)", "CLU on your PC once this phone is paired with it; until then, this device. " +
            (if (mode == "auto") "Now: ${if (server) "CLU on my PC" else "this device (not paired yet)"}." else ""), mode == "auto") { set("auto") }
        engineCard(this, "Always CLU on my PC", "Best understanding of natural speech, fast streaming replies and a natural voice. " +
            "Your PC does the listening, thinking and speaking with the same voice and memory, and the phone does not load its own model; " +
            "needs the PC on the same Wi-Fi or hotspot (no internet needed).", mode == "livekit") { set("livekit") }
        engineCard(this, "Always this device", "Works without any network, using the language model and voice installed on this phone. Slower and less accurate.", mode == "offline") { set("offline") }

        h1("CLU on my PC")
        val url = core.settings.string(Settings.LK_TOKEN_URL)
        p("Token endpoint: ${url.ifBlank { "not set" }}", C.TEXT, 14f)
        p("Device key: ${if (app.liveKit.deviceKey() == null) "not set" else "set (stored encrypted)"}", C.TEXT, 14f)
        row {
            smallButton("Token endpoint…") {
                prompt("Token endpoint", url, hint = "shown on the PC, e.g. http://192.168.1.20:47822/token") { v ->
                    when (val r = core.settings.set(Settings.LK_TOKEN_URL, v.trim())) {
                        SettingResult.Ok -> build()
                        is SettingResult.Invalid -> toast("Not saved: ${r.reason}")
                        SettingResult.RequiresVerification -> verifyOwner("Change the token endpoint") { ok -> if (ok) { core.settings.set(Settings.LK_TOKEN_URL, v.trim(), verified = true); build() } }
                        else -> {}
                    }
                }
            }
            smallButton(if (app.liveKit.deviceKey() == null) "Device key…" else "Replace key…") {
                verifyOwner("Change the device key") { ok ->
                    if (ok) prompt("Device key", hint = "shown once on the PC when you pair this phone", password = true) { key ->
                        if (key.trim().matches(Regex("[a-z0-9][a-z0-9-]{0,30}\\.[A-Za-z0-9_-]{16,}"))) { app.liveKit.setDeviceKey(key); build() }
                        else toast("That does not look like a device key (id.secret).")
                    }
                }
            }
        }
        val status = p("", C.MUTED, 13f)
        button("Test connection", primary = true) {
            if (!server) set("livekit")
            app.liveKit.open(mic = false, spokenReplies = false)
        }
        watch?.cancel()
        watch = scope.launch {
            app.liveKit.state.collect { s ->
                status.text = buildString {
                    append(when (s.link) {
                        Link.CONNECTED -> if (s.agent == AgentPhase.ABSENT) "Connected – waiting for Clu's agent to join" else "Connected – Clu's agent is ${s.agent.name.lowercase()}"
                        Link.CONNECTING -> "Connecting…"; Link.RECONNECTING -> "Reconnecting…"; Link.NOT_CONFIGURED -> "Not set up"
                        Link.FAILED -> "Failed"; Link.DISCONNECTED -> "Not connected"
                    })
                    s.error?.let { append("\n").append(it) }
                    s.metrics["speech_end_to_first_audio_ms"]?.let { append("\nLast reply started %.0f ms after you stopped speaking".format(it)) }
                }
                status.setTextColor(if (s.link == Link.CONNECTED && s.agent != AgentPhase.ABSENT) C.ACCENT else if (s.error != null) C.WARN else C.MUTED)
            }
        }
        p("On the PC: CLU → Settings → Voice → turn on phone access, then Pair a phone. Enter the endpoint and key it shows here. " +
            "The phone and the PC must be on the same Wi-Fi (or the phone's hotspot). Audio and text go only to your PC; nothing goes to the internet.", C.MUTED, 12f)

        h1("Diagnostics")
        p("For finding problems without a computer. Contains no conversation text, keys or tokens.", C.MUTED, 12f)
        val diag = p("", C.TEXT, 12f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        row {
            smallButton("Copy") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Clu diagnostics", diagnosticsReport()))
                toast("Diagnostics copied")
            }
            smallButton("Share…") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, diagnosticsReport()), "Share diagnostics"))
            }
        }
        diagWatch?.cancel()
        diagWatch = scope.launch {
            app.liveKit.state.collect { st ->
                val ev = app.liveKit.live.events().takeLast(25)
                diag.text = buildString {
                    append("engine: ").append(if (app.liveKit.enabled()) "CLU on my PC" else "this device").append('\n')
                    append("connection: ").append(st.link.name.lowercase()).append(" · agent: ").append(st.agent.name.lowercase()).append('\n')
                    if (st.providers.isNotEmpty()) st.providers.forEach { (k, v) -> append(k).append(": ").append(v).append('\n') }
                    else append("providers: not reported yet\n")
                    if (st.appMetrics.isNotEmpty()) append("phone (ms): ").append(st.appMetrics.entries.joinToString(" ") { "${it.key}=${it.value}" }).append('\n')
                    if (st.metrics.isNotEmpty()) append("agent (ms): ").append(st.metrics.entries.joinToString(" ") { "${it.key}=${it.value.toLong()}" }).append('\n')
                    ev.forEach { append(LiveDiagnostics.time(it.atMs)).append(' ').append(it.kind).append(' ').append(LiveDiagnostics.redact(it.detail)).append('\n') }
                }.trimEnd()
            }
        }
    }

    private fun diagnosticsReport(): String {
        val endpoint = core.settings.string(Settings.LK_TOKEN_URL).let { u -> runCatching { java.net.URI(u).let { "${it.scheme}://${it.host}${if (it.port > 0) ":" + it.port else ""}" } }.getOrDefault("?") }
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).let { "${it.versionName} (${it.longVersionCode})" } }.getOrDefault("?")
        return LiveDiagnostics.report(app.liveKit.state.value, app.liveKit.live.events(), linkedMapOf(
            "app" to version, "device" to "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "engine" to if (app.liveKit.enabled()) "CLU on my PC (LiveKit)" else "this device (offline)",
            "token endpoint" to endpoint.ifBlank { "not set" },
            "device key" to if (app.liveKit.deviceKey() == null) "not set" else "set",
            "agent name" to core.settings.string(Settings.LK_AGENT_NAME)))
    }

    private fun set(mode: String) {
        core.settings.set(Settings.VOICE_MODE, mode)
        // The PC answers: the phone's own engine stops and its model is unloaded (it loads again if this device is chosen).
        if (app.liveKit.enabled()) { app.voice.stop(); app.speech.stop(); scope.launch { core.llm.unload() } } else app.liveKit.close()
        build()
    }

    private fun engineCard(parent: LinearLayout, title: String, desc: String, selected: Boolean, onPick: () -> Unit) = parent.card {
        p((if (selected) "◉  " else "○  ") + title, if (selected) C.ACCENT else C.TEXT, 16f)
        p(desc, C.MUTED, 12f)
        tappable(onPick)
    }
}
