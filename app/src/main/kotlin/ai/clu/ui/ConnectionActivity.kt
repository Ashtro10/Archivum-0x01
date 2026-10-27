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
        val server = core.settings.string(Settings.VOICE_MODE) == "livekit"
        h1("Who answers")
        engineCard(this, "Clu server (recommended)", "Best understanding of natural speech, fast streaming replies and a natural voice. " +
            "Needs internet (or your home network) and the Clu server: a LiveKit server with the Clu agent (docs/LIVEKIT_AGENT.md).", server) { set(true) }
        engineCard(this, "This device", "Works without any network, using the language model and voice installed on this phone. Slower and less accurate.", !server) { set(false) }

        h1("Clu server")
        val url = core.settings.string(Settings.LK_TOKEN_URL)
        p("Token endpoint: ${url.ifBlank { "not set" }}", C.TEXT, 14f)
        p("Device key: ${if (app.liveKit.deviceKey() == null) "not set" else "set (stored encrypted)"}", C.TEXT, 14f)
        row {
            smallButton("Token endpoint…") {
                prompt("Token endpoint", url, hint = "e.g. http://192.168.1.20:8089/token") { v ->
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
                    if (ok) prompt("Device key", hint = "from: python clu_voice_server.py add-device phone", password = true) { key ->
                        if (key.trim().matches(Regex("[a-z0-9][a-z0-9-]{0,30}\\.[A-Za-z0-9_-]{16,}"))) { app.liveKit.setDeviceKey(key); build() }
                        else toast("That does not look like a device key (id.secret).")
                    }
                }
            }
        }
        val status = p("", C.MUTED, 13f)
        button("Test connection", primary = true) {
            if (!server) set(true)
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
        p("Run the server on your computer (or use LiveKit Cloud): python clu_voice_server.py serve. The phone needs to reach the token endpoint " +
            "and the LiveKit server URL it returns. Audio and text go only to that server.", C.MUTED, 12f)

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
                    append("engine: ").append(if (core.settings.string(Settings.VOICE_MODE) == "livekit") "Clu server" else "this device").append('\n')
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
            "engine" to if (core.settings.string(Settings.VOICE_MODE) == "livekit") "Clu server (LiveKit)" else "this device (offline)",
            "token endpoint" to endpoint.ifBlank { "not set" },
            "device key" to if (app.liveKit.deviceKey() == null) "not set" else "set",
            "agent name" to core.settings.string(Settings.LK_AGENT_NAME)))
    }

    private fun set(server: Boolean) {
        core.settings.set(Settings.VOICE_MODE, if (server) "livekit" else "offline")
        if (server) { app.voice.stop(); app.speech.stop() } else app.liveKit.close()
        build()
    }

    private fun engineCard(parent: LinearLayout, title: String, desc: String, selected: Boolean, onPick: () -> Unit) = parent.card {
        p((if (selected) "◉  " else "○  ") + title, if (selected) C.ACCENT else C.TEXT, 16f)
        p(desc, C.MUTED, 12f)
        setOnClickListener { onPick() }
    }
}
