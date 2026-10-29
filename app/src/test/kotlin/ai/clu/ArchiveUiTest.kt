package ai.clu

import ai.clu.core.history.ArchivePerson
import ai.clu.core.history.DateParser
import ai.clu.core.history.DatePrecision
import ai.clu.core.memory.Memory
import ai.clu.core.memory.MemoryCategory
import ai.clu.core.memory.MemorySource
import ai.clu.core.sync.PlainKeyVault
import ai.clu.platform.AndroidPlatform
import ai.clu.ui.*
import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Personal archive screens (1.8.0) on Android framework code: the editor's historical date, People, timeline, bulk add. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CluApp::class)
class ArchiveUiTest {
    companion object {
        @JvmStatic @BeforeClass fun hook() { CluApp.platformFactory = { AndroidPlatform(it, PlainKeyVault) } }
    }

    private val app get() = RuntimeEnvironment.getApplication() as CluApp
    private fun idle(ms: Long = 50) { repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(ms / 10 + 1) } }
    private fun <T : Activity> launch(cls: Class<T>, intent: Intent? = null): T =
        (if (intent != null) Robolectric.buildActivity(cls, intent) else Robolectric.buildActivity(cls)).setup().get().also { idle() }
    private fun allViews(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } else emptyList()
    private fun texts(a: Activity) = allViews(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun edits(a: Activity) = allViews(a.window.decorView).filterIsInstance<EditText>()
    private fun click(a: Activity, label: String) { allViews(a.window.decorView).filterIsInstance<Button>().first { it.text.toString() == label }.performClick(); idle() }

    @Test fun theEditorKeepsTheHistoricalDateNotTodaysDate() {
        val a = launch(MemoryEditActivity::class.java)
        val e = edits(a)
        e[0].setText("Met Riya at the college fest")
        e[1].setText("I met Riya at the college fest in Pune.")
        e[2].setText("around March 2018"); idle()
        assertTrue(texts(a).contains("Understood as: around March 2018"))
        e[3].setText("Riya")
        click(a, "Save")
        val m = app.core.memories.list().first { it.title == "Met Riya at the college fest" }
        assertEquals("around March 2018", m.historicalDate().label())
        assertEquals(DatePrecision.MONTH, m.historicalDate().precision)
        assertNull("no exact day invented", m.eventAt)
        assertEquals(listOf("Riya"), m.people)
    }

    @Test fun anUnreadableDateIsNotSaved() {
        val a = launch(MemoryEditActivity::class.java)
        val e = edits(a)
        e[0].setText("Typo"); e[2].setText("Marhc 2018"); idle()
        assertTrue(texts(a).any { it.startsWith("⚠ Not understood") })
        click(a, "Save")
        assertTrue(app.core.memories.list().none { it.title == "Typo" })
    }

    @Test fun hubTimelineAndPeopleShowTheArchive() {
        val core = app.core
        core.archive.people.save(ArchivePerson(name = "Riya", relation = "friend"))
        core.archive.saveMemory(Memory(category = MemoryCategory.EXPERIENCE, title = "Fest with Riya", content = "Fest.", source = MemorySource.OWNER_SUPPLIED,
            people = listOf("Riya"), eventDate = DateParser.parse("March 2018")!!))
        val hub = launch(MemoryActivity::class.java)
        val t = texts(hub)
        assertTrue(t.any { it.startsWith("Clu's timeline starts 1 January 2018") })
        assertTrue(t.contains("Timeline") && t.contains("People (1)") && t.contains("+ Quick add") && t.contains("Import & export"))
        assertTrue(t.any { it.startsWith("March 2018 · Relationship / experience · Riya") })
        val tl = launch(TimelineActivity::class.java)
        assertTrue(texts(tl).contains("2018")); assertTrue(texts(tl).contains("Fest with Riya"))
        val people = launch(ArchivePeopleActivity::class.java)
        assertTrue(texts(people).any { it.startsWith("Riya") }); assertTrue(texts(people).any { it.contains("1 memories") })
    }

    @Test fun bulkAddPreviewsBeforeSaving() {
        val a = launch(ArchiveImportActivity::class.java)
        edits(a)[0].setText("2019 | Started Primus | project: Primus\nMarhc 2019 | Bad date")
        click(a, "Preview")
        assertTrue(texts(a).any { it.startsWith("1 to add") && it.contains("1 problems") })
        assertTrue(app.core.memories.list().none { it.title == "Started Primus" })
        click(a, "Add 1 memories")
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && app.core.memories.list().none { it.title == "Started Primus" }) idle()
        assertEquals("2019", app.core.memories.list().first { it.title == "Started Primus" }.historicalDate().label())
        assertEquals(listOf("Primus"), app.core.archive.projects.list().map { it.name })
    }
}
