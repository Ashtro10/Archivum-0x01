package ai.clu

import ai.clu.core.sync.PlainKeyVault
import ai.clu.platform.AndroidPlatform
import ai.clu.ui.MainActivity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A failure during startup must show the crash report screen instead of closing the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 35], application = CluApp::class)
class CrashReportTest {
    companion object {
        @JvmStatic @BeforeClass fun failingPlatform() { CluApp.platformFactory = { throw IllegalStateException("simulated keystore failure") } }
        @JvmStatic @AfterClass fun restore() { CluApp.platformFactory = { AndroidPlatform(it, PlainKeyVault) } }
    }

    private fun texts(v: View): List<String> =
        (if (v is TextView) listOf(v.text.toString()) else emptyList()) + if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else emptyList()

    @Test fun startupFailureShowsReportInsteadOfCrashing() {
        val app = RuntimeEnvironment.getApplication() as CluApp
        assertFalse(app.isReady)
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue("main screen closes itself", main.isFinishing)
        val next = shadowOf(main).nextStartedActivity
        assertEquals(CrashReportActivity::class.java.name, next.component!!.className)

        val report = Robolectric.buildActivity(CrashReportActivity::class.java, next).setup().get()
        val shown = texts(report.window.decorView).joinToString("\n")
        assertTrue(shown.contains("Clu could not start"))
        assertTrue("stack trace is shown", shown.contains("simulated keystore failure"))
        assertTrue("device is identified", shown.contains("Android"))
        assertNotNull(CrashReport.pending(app))
    }
}
