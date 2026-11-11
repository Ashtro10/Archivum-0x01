package ai.clu.ui

import ai.clu.CluApp
import ai.clu.core.api.LoopbackServer
import ai.clu.core.api.ShellHost
import ai.clu.core.config.Settings
import ai.clu.core.live.Link
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * CLU's window on a tablet or phone: the PC window's own interface (the desktop shell, packaged in the APK under
 * assets/shell) in a WebView, with the Primus core, particles and animations exactly as on Windows. Its API calls go to
 * [ShellServer] on 127.0.0.1, which sends them to the paired PC (real conversations, memory, models and settings) or
 * answers them on this device when there is no PC. Voice uses the app's native session (one LiveKit connection, Android
 * audio routing and echo cancellation), exposed to the page as `CluNative`. The native screens stay as "Classic view".
 */
class ShellActivity : BaseActivity() {
    override val ownEntrance = true
    private var web: WebView? = null
    private var pendingVoice = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!app.isReady || ai.clu.CrashReport.pending(this) != null) {
            startActivity(Intent(this, ai.clu.CrashReportActivity::class.java)); finish(); return
        }
        // Tablets with 120/144 Hz panels: the interface is drawn at 60 Hz (half the GPU and battery cost, same look).
        window.attributes = window.attributes.apply { preferredRefreshRate = 60f }
        val (host, port) = try { ShellServer.ensure(app) } catch (e: Exception) { return classic(fallback = true) }
        val w = try { WebView(this) } catch (e: Exception) { return classic(fallback = true) } // no system WebView installed
        web = w
        w.setBackgroundColor(C.BG)
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false // interface sounds
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false); setGeolocationEnabled(false)
            userAgentString = "$userAgentString CLU-Android/${version()}"
        }
        val origin = "http://127.0.0.1:$port"
        w.webViewClient = object : WebViewClient() {
            // Only CLU's own pages load here; links in answers open in the browser.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.toString().startsWith("$origin/")) return false
                if (url.scheme == "http" || url.scheme == "https") runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }
        }
        w.addJavascriptInterface(Bridge(), "CluNative")
        val root = FrameLayout(this).apply { setBackgroundColor(C.BG); addView(w, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(root)
        applySystemInsets(root)
        w.loadUrl("$origin/#code=${host.newLaunchCode()}")
    }

    private fun version() = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    private fun classic(fallback: Boolean = false) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_FROM_SHELL, true))
        if (fallback) finish()
    }

    override fun onResume() { super.onResume(); web?.onResume(); app.engine.screenVisible("shell", true) }
    override fun onPause() { web?.onPause(); app.engine.screenVisible("shell", false); super.onPause() }
    override fun onDestroy() {
        web?.let { it.removeJavascriptInterface("CluNative"); it.destroy() }; web = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web?.canGoBack() == true) web?.goBack() else moveTaskToBack(true) }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && pendingVoice) { pendingVoice = false; if (app.hasMic()) app.engine.startVoice() }
    }

    /** What the page may ask of the app (called on a WebView thread; everything here is thread-safe or posted). */
    inner class Bridge {
        @JavascriptInterface fun voiceStart(): String {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                pendingVoice = true
                runOnUiThread { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC) }
                return """{"ok":true,"permission":"asked"}"""
            }
            runOnUiThread { app.engine.startVoice() }
            return """{"ok":true}"""
        }
        @JavascriptInterface fun voiceEnd() { runOnUiThread { app.engine.stopVoice() } }
        @JavascriptInterface fun setMic(on: Boolean) { runOnUiThread { app.engine.setMic(on) } }
        @JavascriptInterface fun interrupt() { runOnUiThread { app.engine.stop() } }
        @JavascriptInterface fun voiceState(): String = ShellServer.voiceState(app)
        @JavascriptInterface fun classic() { runOnUiThread { this@ShellActivity.classic() } }
    }

    companion object {
        const val REQ_MIC = 41
        const val EXTRA_FROM_SHELL = "from_shell"
    }
}

/** The loopback server and shell host, one per process (they outlive activity re-creation, e.g. rotation). */
object ShellServer {
    @Volatile private var running: Pair<ShellHost, Int>? = null
    private var server: LoopbackServer? = null

    @Synchronized fun ensure(app: CluApp): Pair<ShellHost, Int> {
        running?.let { return it }
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
        val host = ShellHost(app.core, version,
            asset = { name -> runCatching { app.assets.open("shell/$name").use { it.readBytes() } }.getOrNull()
                ?: ShellServer::class.java.getResourceAsStream("/shell/$name")?.use { it.readBytes() } }, // JVM tests (no APK assets)
            pc = { remote(app) })
        val s = LoopbackServer(handler = host::handle)
        val port = s.start()
        server = s
        return (host to port).also { running = it }
    }

    /** The paired PC (when the owner chose "your PC answers" and a device key is stored), else null. */
    fun remote(app: CluApp): ShellHost.Remote? {
        if (!app.liveKit.enabled()) return null
        val url = app.core.settings.string(Settings.LK_TOKEN_URL).trim().ifEmpty { return null }
        val key = app.liveKit.deviceKey()?.ifBlank { null } ?: return null
        return ShellHost.Remote(url.removeSuffix("/").removeSuffix("/token"), key)
    }

    fun voiceState(app: CluApp): String {
        val u = app.engine.ui.value
        return buildJsonObject {
            put("server", u.server); put("phase", u.phase.name.lowercase().let { if (it == "absent" || it == "initializing") "idle" else it })
            put("link", when (u.link) { Link.CONNECTED -> "connected"; Link.RECONNECTING -> "reconnecting"; Link.CONNECTING -> "connecting"; else -> u.link.name.lowercase() })
            put("micOn", u.micOn); put("userLive", u.userLive); put("agentLive", u.agentLive); put("busy", u.busy)
            u.error?.let { put("error", it) }; put("level", u.agentLevel.toDouble())
        }.toString()
    }

    @Synchronized fun stop() { server?.stop(); server = null; running = null }
}
