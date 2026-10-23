package ai.clu

import ai.clu.core.config.RegisterGenerator
import ai.clu.core.config.Settings
import ai.clu.core.sync.PlainKeyVault
import ai.clu.platform.AndroidPlatform
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Writes docs/SETTINGS_REGISTER.md with all settings, including per-tool settings registered by the Android app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = CluApp::class)
class RegisterTest {
    companion object { @JvmStatic @BeforeClass fun hook() { CluApp.platformFactory = { AndroidPlatform(it, PlainKeyVault) } } }

    @Test fun generateSettingsRegister() {
        RuntimeEnvironment.getApplication() as CluApp // registers Android tools and their settings
        val out = System.getProperty("clu.registerOut") ?: return
        val dirs = (System.getProperty("clu.testResultDirs") ?: "").split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)
        RegisterGenerator.generate(File(out), dirs, System.getProperty("clu.buildInfo") ?: "")
        assertTrue(Settings.all.any { it.key == "tool.app.open.enabled" })
        assertTrue(File(out).readText().contains("`identity.cluName`"))
    }
}
