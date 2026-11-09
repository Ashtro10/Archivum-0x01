package ai.clu.core.settings

import ai.clu.core.config.Settings
import ai.clu.core.config.UiPolicy
import ai.clu.core.newCore
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Interface sounds and motion (the Android app's Sfx and Motion follow these decisions; InterfaceTest checks the app side). */
class InterfaceSettingsTest {
    private val core = newCore()

    @Test fun sounds() {
        val s = core.settings
        assertTrue(s.bool(Settings.UI_SOUNDS), "on by default")
        assertTrue(UiPolicy.interfaceSound(s, listening = false, speaking = false))
        assertFalse(UiPolicy.interfaceSound(s, listening = true, speaking = false), "never while the microphone listens")
        assertFalse(UiPolicy.interfaceSound(s, listening = false, speaking = true), "never over CLU's voice")
        s.set(Settings.UI_SOUNDS, false)
        assertFalse(UiPolicy.interfaceSound(s, listening = false, speaking = false), "off means silent")
    }

    @Test fun motion() {
        val s = core.settings
        assertEquals("full", s.string(Settings.UI_MOTION))
        assertFalse(UiPolicy.reducedMotion(s))
        s.set(Settings.UI_MOTION, "reduced")
        assertTrue(UiPolicy.reducedMotion(s))
        assertTrue(s.set(Settings.UI_MOTION, "wild") is ai.clu.core.config.SettingResult.Invalid, "only the two choices are accepted")
        assertEquals("reduced", s.string(Settings.UI_MOTION))
    }
}
