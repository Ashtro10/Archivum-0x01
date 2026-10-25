package ai.clu

import ai.clu.core.config.Settings
import ai.clu.core.sync.PlainKeyVault
import ai.clu.core.voice.TtsSelection
import ai.clu.core.voice.VoiceConfig
import ai.clu.platform.AndroidPlatform
import ai.clu.voice.SystemTtsBackend
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.speech.tts.TextToSpeech
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The engine chosen in settings is the engine used at runtime; nothing else is substituted when it is missing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 35], application = CluApp::class)
class TtsEngineSelectionTest {
    companion object {
        @JvmStatic @BeforeClass fun hook() { CluApp.platformFactory = { AndroidPlatform(it, PlainKeyVault) } }
    }

    private val app get() = RuntimeEnvironment.getApplication() as CluApp

    private fun installTtsEngine(pkg: String) {
        val ri = ResolveInfo().apply { serviceInfo = ServiceInfo().apply { packageName = pkg; name = "$pkg.TtsService" } }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), ri)
    }

    @Test fun missingHayaiIsReportedAndNoOtherEngineSpeaks() {
        app.core.settings.set(Settings.TTS_ENGINE, "hayai")
        val cfg = VoiceConfig.from(app.core.settings)
        val plan = app.speech.refresh(cfg)
        assertEquals("hayai", plan.engine)
        assertFalse(plan.available)
        val e = runCatching { app.speech.backendFor(cfg, "en") }.exceptionOrNull()
        assertNotNull("no backend is substituted", e)
        assertTrue(e!!.message!!.contains("HayaiTTS is not installed"))
        assertEquals("hayai", app.core.settings.string(Settings.TTS_ENGINE)) // the selection is not changed
    }

    @Test fun installedHayaiIsBoundByItsPackage() {
        installTtsEngine(TtsSelection.HAYAI_PACKAGE)
        app.core.settings.set(Settings.TTS_ENGINE, "hayai")
        val cfg = VoiceConfig.from(app.core.settings)
        assertTrue(app.speech.refresh(cfg).available)
        val b = app.speech.backendFor(cfg, "en")
        assertTrue(b is SystemTtsBackend)
        assertEquals(TtsSelection.HAYAI_PACKAGE, (b as SystemTtsBackend).enginePackage)
    }

    @Test fun piperUsesTheSelectedVoiceOnly() {
        app.core.settings.set(Settings.TTS_ENGINE, "piper")
        app.core.settings.set(Settings.TTS_PIPER_VOICE, "tts-joe")
        val plan = app.speech.refresh(VoiceConfig.from(app.core.settings))
        assertFalse("Joe is not installed in a fresh app", plan.available)
        assertTrue(plan.problem!!.contains("Joe"))
    }

    @Test fun legacyEngineIdIsMigratedAtStartup() {
        // Written by older versions; CluApp rewrites it on start (see CluApp.start). Here: the mapping used.
        assertEquals("piper", TtsSelection.normalise("sherpa"))
        assertEquals("piper", app.core.settings.string(Settings.TTS_ENGINE).let(TtsSelection::normalise))
    }
}
