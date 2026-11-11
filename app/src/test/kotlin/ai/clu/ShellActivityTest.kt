package ai.clu

import ai.clu.core.sync.PlainKeyVault
import ai.clu.platform.AndroidPlatform
import ai.clu.ui.ShellActivity
import ai.clu.ui.ShellServer
import android.content.Intent
import android.os.Looper
import android.webkit.WebView
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URI

/**
 * CLU's window on Android: the launcher opens the PC interface (the packaged desktop shell) in a WebView served from
 * 127.0.0.1 with a one-time launch code; the native bridge is present; the page reaches this device's own API when no
 * PC is paired. (Rendering itself needs a real WebView: see the device checklist.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CluApp::class)
class ShellActivityTest {
    companion object {
        @JvmStatic @BeforeClass fun hook() { CluApp.platformFactory = { AndroidPlatform(it, PlainKeyVault) } }
    }
    private val app get() = RuntimeEnvironment.getApplication() as CluApp
    private fun idle() { repeat(10) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) } }

    private fun get(url: String, method: String = "GET", body: String? = null, token: String? = null): Pair<Int, String> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.requestMethod = method; token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        return code to ((if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: "")
    }

    @Test fun theLauncherOpensThePCInterfaceServedFromThisDevice() {
        val launcher = app.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName), 0)
        assertEquals(listOf(ShellActivity::class.java.name), launcher.map { it.activityInfo.name })

        val a = Robolectric.buildActivity(ShellActivity::class.java).setup().get(); idle()
        val web = generateSequence(listOf(a.window.decorView)) { level -> level.flatMap { v -> (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).map(g::getChildAt) } ?: emptyList() }.ifEmpty { null } }
            .flatten().filterIsInstance<WebView>().single()
        val url = shadowOf(web).lastLoadedUrl
        assertTrue(url, Regex("^http://127\\.0\\.0\\.1:\\d+/#code=[A-Za-z0-9_-]{20,}$").matches(url))
        assertTrue(web.settings.userAgentString.contains("CLU-Android/"))
        assertTrue(web.settings.javaScriptEnabled)
        assertFalse("no file access", web.settings.allowFileAccess)
        assertNotNull(shadowOf(web).getJavascriptInterface("CluNative"))

        val origin = url.substringBefore("/#")
        val (code, html) = get("$origin/")
        assertEquals(200, code)
        assertTrue("the packaged shell, with the Android adapter and the Primus core", "platform.js" in html && "primus.js" in html && "primusCore" in html)
        assertTrue(get("$origin/shell/platform.js").second.contains("CLU-Android"))
        assertTrue("the core draws at 1.25x on Android", get("$origin/shell/primus/app.js").second.contains("1.25"))
        assertTrue("the browser voice client is not shipped (native voice): a placeholder", get("$origin/shell/livekit-client.js").second.contains("native voice session"))

        val launchCode = url.substringAfter("#code=")
        val (s, tokenJson) = get("$origin/v1/session", "POST", """{"code":"$launchCode"}""")
        assertEquals(200, s)
        val token = Json.parseToJsonElement(tokenJson).jsonObject["token"]!!.jsonPrimitive.content
        val status = Json.parseToJsonElement(get("$origin/v1/status", token = token).second).jsonObject
        assertEquals("device", status["link"]!!.jsonPrimitive.content) // no PC paired in a fresh install
        assertEquals(401, get("$origin/v1/status").first)
        // Capability detection with the app's real tools: device commands run here, PC-only views are named.
        val platform = Json.parseToJsonElement(get("$origin/v1/shell/platform", token = token).second).jsonObject
        assertEquals("android", platform["platform"]!!.jsonPrimitive.content)
        val tools = platform["deviceTools"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(tools.toString(), tools.containsAll(listOf("alarm.set", "timer.set", "device.volume", "app.open")))
        assertTrue(platform["pcOnly"]!!.jsonArray.map { it.jsonPrimitive.content }.containsAll(listOf("agents", "models")))
        // Standalone: memory and the timeline answer on the device.
        assertEquals(201, get("$origin/v1/memories", "POST", """{"title":"Pad 6 test","content":"standalone"}""", token).first)
        assertTrue(get("$origin/v1/memories?q=Pad", token = token).second.contains("Pad 6 test"))

        val voice = Json.parseToJsonElement(ShellServer.voiceState(app)).jsonObject
        assertFalse(voice["micOn"]!!.jsonPrimitive.boolean)
        assertNotNull(voice["phase"]); assertNotNull(voice["link"])
    }

    @Test fun pagesOtherThanCLUsOwnOpenOutsideTheApp() {
        val a = Robolectric.buildActivity(ShellActivity::class.java).setup().get(); idle()
        val web = (a.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as android.view.ViewGroup).getChildAt(0) as WebView
        val client = shadowOf(web).webViewClient
        val origin = shadowOf(web).lastLoadedUrl.substringBefore("/#")
        fun req(u: String) = object : android.webkit.WebResourceRequest {
            override fun getUrl() = android.net.Uri.parse(u); override fun isForMainFrame() = true; override fun isRedirect() = false
            override fun hasGesture() = true; override fun getMethod() = "GET"; override fun getRequestHeaders() = emptyMap<String, String>()
        }
        assertFalse(client.shouldOverrideUrlLoading(web, req("$origin/shell/index.html")))
        assertTrue(client.shouldOverrideUrlLoading(web, req("https://example.com/")))
        assertEquals(Intent.ACTION_VIEW, shadowOf(a).nextStartedActivity?.action)
    }
}
