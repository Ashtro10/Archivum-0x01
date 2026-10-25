package ai.clu

import ai.clu.core.sync.PlainKeyVault
import ai.clu.platform.AndroidPlatform
import ai.clu.ui.MainActivity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Insets
import android.view.View
import android.view.WindowInsets
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the chat screen at the iQOO Neo 9 Pro's resolution (1260x2800 px, xxhdpi) with Robolectric native graphics,
 * after dispatching Android 15 edge-to-edge window insets (status bar/cutout 40 dp, gesture bar 24 dp, keyboard 300 dp).
 * Translucent overlays show where the system bars and keyboard are. Simulation, not a device screenshot.
 * Output: app/build/screenshots/<name>.png (opt-in: -Dclu.screenshots=<label>).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = CluApp::class, qualifiers = "w420dp-h933dp-xxhdpi")
class UiScreenshotTest {
    companion object {
        @JvmStatic @BeforeClass fun hook() { CluApp.platformFactory = { AndroidPlatform(it, PlainKeyVault) } }
    }

    private val label = System.getProperty("clu.screenshots")
    private val w = 1260; private val h = 2800; private val d = 3

    private fun shot(name: String, ime: Boolean, prepare: (MainActivity) -> Unit = {}) {
        if (label == null) return
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        prepare(a)
        render(a, name, ime)
    }

    private fun render(a: android.app.Activity, name: String, ime: Boolean) {
        val decor = a.window.decorView
        val insets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 40 * d, 0, 0))
            .setInsets(WindowInsets.Type.displayCutout(), Insets.of(0, 40 * d, 0, 0))
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 24 * d))
            .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, if (ime) 300 * d else 0))
            .setVisible(WindowInsets.Type.ime(), ime)
            .build()
        decor.dispatchApplyWindowInsets(insets)
        decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, w, h)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        decor.draw(c)
        val p = Paint().apply { color = Color.argb(150, 255, 0, 200) }
        c.drawRect(0f, 0f, w.toFloat(), 40f * d, p)                      // status bar / cutout
        c.drawRect(0f, h - 24f * d, w.toFloat(), h.toFloat(), p)          // gesture navigation bar
        if (ime) c.drawRect(0f, h - 300f * d, w.toFloat(), h.toFloat(), Paint().apply { color = Color.argb(200, 90, 90, 110) }) // keyboard
        val t = Paint().apply { color = Color.WHITE; textSize = 34f; isAntiAlias = true }
        c.drawText("simulated: status bar", 20f, 34f * d, t); c.drawText("simulated: gesture bar", 20f, h - 6f * d, t)
        if (ime) c.drawText("simulated: keyboard (300 dp)", 20f, h - 280f * d, t)
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "$label-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun chatScreen() = shot("chat", ime = false)
    @Test fun voiceSettingsScreen() { if (label != null) render(Robolectric.buildActivity(ai.clu.ui.VoiceActivity::class.java).setup().get(), "voice-settings", false) }
    @Test fun chatScreenWithKeyboard() = shot("chat-keyboard", ime = true)
    @Test fun voiceMode() = shot("voice", ime = false) { a ->
        // Voice-mode overlay as shown while listening (state set directly; no microphone in the JVM).
        val f = MainActivity::class.java.getDeclaredField("voiceOverlay").apply { isAccessible = true }
        (f.get(a) as View).visibility = View.VISIBLE
        fun tv(n: String) = MainActivity::class.java.getDeclaredField(n).apply { isAccessible = true }.get(a) as android.widget.TextView
        tv("voiceLabel").text = "Clu is listening…"; tv("voiceTranscript").text = "set an alarm for seven tomorrow"; tv("voiceTimer").text = "00:07"
        (MainActivity::class.java.getDeclaredField("voiceOrb").apply { isAccessible = true }.get(a) as ai.clu.ui.OrbView).apply { mode = ai.clu.ui.OrbView.Mode.LISTENING; level = 0.6f }
    }
}
