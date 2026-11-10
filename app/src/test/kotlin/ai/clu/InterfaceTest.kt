package ai.clu

import ai.clu.core.config.Settings
import ai.clu.core.conversation.Role
import ai.clu.core.sync.PlainKeyVault
import ai.clu.platform.AndroidPlatform
import ai.clu.ui.*
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The redesigned interface (1.12): sound and motion settings change behaviour, the synthesised sounds are short, quiet
 * and click-free, Markdown renders, CLU's entity draws every state without text, images attach to messages.
 * Robolectric (Android framework code on the JVM), not a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = CluApp::class, qualifiers = "w420dp-h933dp-xxhdpi")
class InterfaceTest {
    companion object { @JvmStatic @BeforeClass fun hook() { CluApp.platformFactory = { AndroidPlatform(it, PlainKeyVault) } } }

    private val app get() = RuntimeEnvironment.getApplication() as CluApp
    private fun idle(ms: Long = 50) { repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms / 10 + 1)); Thread.sleep(ms / 10 + 1) } }
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    @Test fun soundsSetting() {
        app.core.settings.set(Settings.VOICE_MODE, "offline")
        app.core.settings.set(Settings.UI_SOUNDS, true)
        Sfx.played.clear()
        assertTrue("on: a click sound plays", Sfx.play(SfxSynth.Sound.CLICK))
        assertEquals(SfxSynth.Sound.CLICK, Sfx.played.last())
        // A real button press plays it too (the builders all go through the same path).
        val s = Robolectric.buildActivity(SettingsActivity::class.java).setup().get(); idle()
        Sfx.played.clear(); Thread.sleep(60)
        all(s.window.decorView).first { it.contentDescription == "Back" }.performClick()
        assertTrue("the back control sounded", Sfx.played.contains(SfxSynth.Sound.CLICK))
        app.core.settings.set(Settings.UI_SOUNDS, false)
        Sfx.played.clear(); Thread.sleep(60)
        assertFalse("off: silent", Sfx.play(SfxSynth.Sound.NOTIFY)); assertTrue(Sfx.played.isEmpty())
        // Never while CLU listens or speaks: the microphone must not hear them and they must not talk over CLU.
        app.core.settings.set(Settings.UI_SOUNDS, true)
        val q = Sfx.quiet
        try { Sfx.quiet = { true }; assertFalse("silent while listening/speaking", Sfx.play(SfxSynth.Sound.ACTIVATE)) } finally { Sfx.quiet = q }
        app.voice.stop(); assertTrue("and back once the microphone is closed", Sfx.play(SfxSynth.Sound.ACTIVATE))
    }

    @Test fun motionSetting() {
        app.core.store.setMeta("onboarded", "1")
        // Full motion: the start-up sequence runs once per launch, over controls that work from the first frame.
        app.core.settings.set(Settings.UI_MOTION, "full"); MainActivity.booted = false
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        assertTrue("start-up sequence shown", all(a.window.decorView).any { it is BootOverlay && it.isShown })
        assertFalse("it never takes touches", all(a.window.decorView).first { it is BootOverlay }.isClickable)
        idle(2500)
        assertFalse("and is gone after it ends", all(a.window.decorView).any { it is BootOverlay && it.isShown })
        val b = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        assertFalse("not again on returning to the chat", all(b.window.decorView).any { it is BootOverlay })
        // Reduced: no sequence, nothing waits for an animation.
        app.core.settings.set(Settings.UI_MOTION, "reduced"); MainActivity.booted = false
        val c = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        assertTrue(Motion.reduced)
        assertFalse("reduced: no start-up sequence", all(c.window.decorView).any { it is BootOverlay })
        val send = all(c.window.decorView).first { it.contentDescription == "Speak" }
        assertEquals("reduced: controls are fully visible at once", 1f, (send.parent as View).alpha, 0.001f)
        app.core.settings.set(Settings.UI_MOTION, "full")
    }

    @Test fun synthesisedSoundsAreShortQuietAndClickFree() {
        for (s in SfxSynth.Sound.values()) {
            val pcm = SfxSynth.pcm(s)
            val sec = pcm.size / SfxSynth.RATE.toDouble()
            assertTrue("$s length $sec s", sec in 0.01..1.0)
            val peak = pcm.maxOf { abs(it.toInt()) } / 32767.0
            assertTrue("$s peak $peak (quiet, never clipping)", peak in 0.01..0.35)
            assertTrue("$s starts at silence", abs(pcm.first().toInt()) < 400)
            assertEquals("$s ends at silence", 0, pcm.last().toInt())
            val wav = SfxSynth.wav(pcm)
            assertEquals("RIFF", String(wav, 0, 4)); assertEquals("WAVE", String(wav, 8, 4)); assertEquals(44 + pcm.size * 2, wav.size)
        }
    }

    @Test fun markdownRendersRepliesAndToleratesStreaming() {
        val b = Markdown.parse("# Plan\nFirst **bold** and *it* and `x()`.\n\n- one\n- two\n1. step\n> quote\n---\n```kotlin\nval a = 1\n```\nafter")
        assertEquals(Markdown.Block.Heading(1, "Plan"), b[0])
        assertTrue(b[1] is Markdown.Block.Para)
        assertEquals(listOf("one", "two"), b.filterIsInstance<Markdown.Block.Item>().filter { it.marker == "•" }.map { it.text })
        assertEquals("1.", b.filterIsInstance<Markdown.Block.Item>().last().marker)
        assertTrue(b.any { it is Markdown.Block.Quote } && b.any { it == Markdown.Block.Rule })
        assertEquals(Markdown.Block.Code("kotlin", "val a = 1", closed = true), b.first { it is Markdown.Block.Code })
        assertEquals(Markdown.Block.Para("after"), b.last())
        val inl = Markdown.inline("First **bold** and *it* and `x()` see [docs](https://example.org) 2*3*4")
        assertTrue(Markdown.Inline.Bold("bold") in inl && Markdown.Inline.Italic("it") in inl && Markdown.Inline.Code("x()") in inl)
        assertTrue(Markdown.Inline.Link("docs", "https://example.org") in inl)
        assertTrue("arithmetic is not italic", inl.last() is Markdown.Inline.Text && (inl.last() as Markdown.Inline.Text).s.endsWith("2*3*4"))
        // Streaming: an unclosed fence is code to the end; unclosed emphasis stays literal; javascript: links never link.
        assertEquals(Markdown.Block.Code("py", "print(1)", closed = false), Markdown.parse("Here:\n```py\nprint(1)").last())
        assertEquals(listOf(Markdown.Inline.Text("**half")), Markdown.inline("**half"))
        assertTrue(Markdown.inline("[x](javascript:alert(1))").none { it is Markdown.Inline.Link })
        // Rendered: a code block becomes its own panel with a copy control.
        val v = app.markdownView("Text\n```\ncode\n```")
        assertTrue(all(v).any { it.contentDescription == "Copy code" })
    }

    @Test fun entityDrawsEveryStateWithoutTextAndStaysCheap() {
        val v = Robolectric.buildActivity(VoiceChatActivity::class.java).setup().get(); idle()
        val e = all(v.window.decorView).filterIsInstance<EntityView>().single()
        assertFalse(e.isClickable || e.isFocusable)
        val bmp = Bitmap.createBitmap(1080, 1400, Bitmap.Config.ARGB_8888)
        val probe = EntityView(v); probe.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY))
        probe.layout(0, 0, 1080, 1400)
        val canvas = android.graphics.Canvas(bmp)
        val cost = mutableMapOf<EntityView.Mode, Double>()
        for (m in EntityView.Mode.values()) {
            probe.mode = m; probe.level = 0.6f
            repeat(5) { probe.draw(canvas) }                      // warm up and let the state blend in
            val t0 = System.nanoTime(); repeat(20) { probe.draw(canvas) }
            cost[m] = (System.nanoTime() - t0) / 20 / 1e6
        }
        println("EntityView frame cost on the build host (software canvas, 1080x1400): " + cost.entries.joinToString { "${it.key}=%.2f ms".format(it.value) })
        assertTrue("frames counted", probe.frames >= 125)
        // A loose bound (software rendering on a shared build machine): the particle pass must stay well within a frame.
        assertTrue("cost per frame: $cost", cost.values.all { it < 40.0 })
        // Something is actually drawn: the eyes' area is lit, the corners are not.
        probe.mode = EntityView.Mode.IDLE; probe.draw(canvas)
        val centre = bmp.getPixel(540 + 120, 700 - 10); val corner = bmp.getPixel(5, 5)
        assertTrue("eyes lit: ${Integer.toHexString(centre)}", android.graphics.Color.blue(centre) > 60)
        assertEquals(0, android.graphics.Color.alpha(corner))
    }

    @Test fun imagesAttachToMessagesAndClusAnswerIsHonest() {
        app.core.settings.set(Settings.VOICE_MODE, "offline")
        val f = File(app.cacheDir, "photo.png")
        f.outputStream().use { Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.CYAN) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val a = ChatImages.importImage(app, android.net.Uri.fromFile(f))
        assertEquals("image", a.kind)
        val stored = File(a.uri).inputStream().use { android.graphics.BitmapFactory.decodeStream(it) }
        assertTrue("scaled to at most 1600 px: ${stored.width}x${stored.height}", maxOf(stored.width, stored.height) <= 1600)
        assertTrue("kept in CLU's private storage", a.uri.startsWith(app.filesDir.absolutePath))
        assertNotNull(ChatImages.thumb(a.uri, 120))
        app.openConversation(null)
        assertTrue(app.engine.send("What's in this photo?", listOf(a)))
        val conv = app.currentConversation()
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) { idle(); if (app.core.conversations.messages(conv).any { it.role == Role.ASSISTANT }) break }
        val msgs = app.core.conversations.messages(conv)
        assertEquals("the image is kept with the message", a.uri, msgs.first { it.role == Role.USER }.meta.attachments.single().uri)
        val reply = msgs.first { it.role == Role.ASSISTANT }.content
        assertTrue("CLU never pretends to see it: $reply", reply.contains("can't see images", ignoreCase = true) || reply.contains("can't", ignoreCase = true))
        // The chat shows the image in the message.
        app.core.store.setMeta("onboarded", "1"); app.core.settings.set(Settings.UI_MOTION, "reduced")
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        assertTrue(all(m.window.decorView).any { it.contentDescription == "Image: photo.png" })
        app.core.settings.set(Settings.UI_MOTION, "full")
    }

    /**
     * 1.12.0 crashed on a vivo I2304 (Android 16) at the end of the start-up sequence: "DecorView contains null child
     * at index 2 when traversal in dispatchGetDisplayList, the view may have been removed". The overlay finished from
     * inside its own onDraw and was removed from the window during the draw pass. Here a parent fails exactly as
     * Android does if a child is removed while it draws; the sequence must still end and be removed afterwards.
     */
    @Test fun theStartUpSequenceIsNeverRemovedDuringADrawPass() {
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get(); idle()
        var drawing = false
        val guard = object : android.widget.FrameLayout(a) {
            override fun dispatchDraw(canvas: android.graphics.Canvas) { drawing = true; try { super.dispatchDraw(canvas) } finally { drawing = false } }
            override fun onViewRemoved(child: View?) { check(!drawing) { "child removed during a draw pass" }; super.onViewRemoved(child) }
        }
        var done = 0
        lateinit var overlay: BootOverlay
        overlay = BootOverlay(a, listOf("core ... online")) { done++; guard.removeView(overlay) }
        guard.addView(overlay, android.view.ViewGroup.LayoutParams(-1, -1))
        a.findViewById<android.view.ViewGroup>(android.R.id.content).addView(guard, android.view.ViewGroup.LayoutParams(-1, -1))
        guard.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY))
        guard.layout(0, 0, 1080, 2000)
        Thread.sleep(1700)                                                  // past the sequence's end (it keeps real time)
        guard.draw(android.graphics.Canvas(Bitmap.createBitmap(1080, 2000, Bitmap.Config.ARGB_8888)))   // finishes inside onDraw
        assertTrue("the sequence ended", overlay.isFinished)
        assertEquals("ended once, not yet removed during the draw", 0, done)
        idle()
        assertEquals("then removed, after the draw pass", 1, done)
        assertNull(overlay.parent)
    }
}
