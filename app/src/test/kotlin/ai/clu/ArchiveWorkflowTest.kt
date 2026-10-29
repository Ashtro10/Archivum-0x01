package ai.clu

import ai.clu.core.config.Settings
import ai.clu.core.history.ArchivePerson
import ai.clu.core.history.DateParser
import ai.clu.core.history.Kind
import ai.clu.core.history.Project
import ai.clu.core.memory.Memory
import ai.clu.core.memory.MemoryCategory
import ai.clu.core.memory.MemorySource
import ai.clu.core.sync.PlainKeyVault
import ai.clu.platform.AndroidPlatform
import ai.clu.ui.*
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
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
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

/**
 * The personal archive as a user drives it (1.8.0): every hub button, then each workflow end to end through the real
 * screens, dialogs, file pickers (results delivered as the system picker would) and the chat screen, on the real
 * Android SQLite database and archive services. No mocks of Clu's own code; no language model is loaded, so chat
 * answers here come from the record-based recall path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CluApp::class)
class ArchiveWorkflowTest {
    companion object {
        @JvmStatic @BeforeClass fun hook() { CluApp.platformFactory = { AndroidPlatform(it, PlainKeyVault) } }
    }

    private val app get() = RuntimeEnvironment.getApplication() as CluApp
    private val core get() = app.core
    private fun idle(ms: Long = 50) { repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(ms / 10 + 1) } }
    private fun until(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) { assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline); idle() }
    }
    private fun <T : Activity> launch(cls: Class<T>, intent: Intent? = null): T =
        (if (intent != null) Robolectric.buildActivity(cls, intent) else Robolectric.buildActivity(cls)).setup().get().also { idle() }
    private fun allViews(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } else emptyList()
    private fun texts(a: Activity) = allViews(a.window.decorView).filterIsInstance<TextView>().filter { it !is Button }.map { it.text.toString() }
    private fun buttons(a: Activity) = allViews(a.window.decorView).filterIsInstance<Button>()
    private fun edits(a: Activity) = allViews(a.window.decorView).filterIsInstance<EditText>()
    private fun click(a: Activity, label: String, nth: Int = 0) {
        val b = buttons(a).filter { it.text.toString() == label }
        assertTrue("button \"$label\" on ${a.javaClass.simpleName}; have ${buttons(a).map { it.text }}", b.size > nth)
        b[nth].performClick(); idle()
    }
    private fun clickStarting(a: Activity, prefix: String) {
        val b = buttons(a).firstOrNull { it.text.toString().startsWith(prefix) } ?: error("no button starting \"$prefix\": ${buttons(a).map { it.text }}")
        b.performClick(); idle()
    }
    /** The field whose label (the TextView just before it) is [label]. */
    private fun field(a: Activity, label: String): EditText {
        val all = allViews(a.window.decorView)
        val i = all.indexOfFirst { it is TextView && it !is EditText && it !is Button && it.text.toString() == label }
        assertTrue("field \"$label\"", i >= 0)
        return all.drop(i + 1).filterIsInstance<EditText>().first()
    }
    private fun spinnerAfter(a: Activity, label: String): android.widget.Spinner {
        val all = allViews(a.window.decorView)
        val i = all.indexOfFirst { it is TextView && it !is Button && it.text.toString() == label }
        assertTrue("spinner \"$label\"", i >= 0)
        return all.drop(i + 1).filterIsInstance<android.widget.Spinner>().first()
    }
    private fun dialog(): AlertDialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog? ?: error("no dialog")
    private fun dialogText(): String = shadowOf(dialog()).message?.toString() ?: ""
    private fun answerPrompt(text: String) { val d = dialog(); allViews(d.window!!.decorView).filterIsInstance<EditText>().first().setText(text)
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle() }
    private fun pick(item: String) { val d = dialog(); val items = shadowOf(d).items.map { it.toString() }
        val i = items.indexOfFirst { it.startsWith(item) }; assertTrue("item \"$item\" in $items", i >= 0); shadowOf(d).clickOnItem(i); idle() }
    private fun press(which: Int) { dialog().getButton(which).performClick(); idle() }
    /** Delivers a file to the screen as the system picker would after the button opened it. */
    private fun pickFile(a: Activity, f: File) {
        val req = shadowOf(a).nextStartedActivityForResult ?: error("no picker was opened")
        shadowOf(a).receiveResult(req.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(f))); idle()
    }
    private fun nextStarted(a: Activity): Intent = shadowOf(a).nextStartedActivity ?: error("nothing started")
    private val fixtures: File by lazy { generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "core/src/test/resources/archive") }.first { it.isDirectory } }
    private fun tmp(name: String, bytes: ByteArray) = File(app.cacheDir, name).apply { writeBytes(bytes) }

    private var chat: MainActivity? = null
    /** Types [text] into the real chat screen and returns Clu's reply. */
    private fun say(text: String): String {
        val a = chat ?: run { core.store.setMeta("onboarded", "1"); launch(MainActivity::class.java).also { chat = it } }
        val before = core.conversations.messages(app.currentConversation()).size
        val views = allViews(a.window.decorView)
        views.filterIsInstance<EditText>().first().setText(text)
        views.filterIsInstance<TextView>().first { it.contentDescription == "Send" }.performClick()
        until("a reply to \"$text\"") { core.conversations.messages(app.currentConversation()).size >= before + 2 }
        return core.conversations.messages(app.currentConversation()).last().content
    }
    private fun simulate(date: String = "2026-10-29") { core.settings.set(Settings.SIMULATED_DATE, date); core.settings.set(Settings.SIMULATED_TIME, "fixed") }
    private fun mem(title: String, date: String, content: String, people: List<String> = emptyList(), kind: String = "") =
        core.archive.saveMemory(Memory(category = MemoryCategory.EXPERIENCE, title = title, content = content, source = MemorySource.OWNER_SUPPLIED,
            people = people, eventDate = DateParser.parse(date)!!, threadKind = kind)).memory

    @Test fun everyHubButtonOpensAWorkingScreen() {
        val hub = launch(MemoryActivity::class.java)
        val targets = linkedMapOf("+ Quick add" to MemoryEditActivity::class.java, "Bulk add" to ArchiveImportActivity::class.java, "Timeline" to TimelineActivity::class.java,
            "People (0)" to ArchivePeopleActivity::class.java, "Documents (0)" to DocumentsActivity::class.java, "Projects (0)" to ProjectsActivity::class.java,
            "Import & export" to ArchiveImportActivity::class.java, "Settings" to SettingsCategoryActivity::class.java)
        for ((label, cls) in targets) {
            click(hub, label)
            val i = nextStarted(hub)
            assertEquals(label, cls.name, i.component?.className)
            val screen = launch(cls, i)
            assertFalse("$label opened", screen.isFinishing)
        }
        // The settings button opens the Memory & diary category, which holds the timeline settings.
        click(hub, "Settings")
        val s = launch(SettingsCategoryActivity::class.java, nextStarted(hub))
        assertTrue(texts(s).any { it.startsWith("Clu timeline start") })
        assertTrue(texts(s).any { it.startsWith("Simulated current date") })
        // Memory is in the main chat menu.
        core.store.setMeta("onboarded", "1")
        val main = launch(MainActivity::class.java)
        allViews(main.window.decorView).first { it.contentDescription == "Conversations and settings" }.performClick(); idle()
        pick("Memory")
        assertEquals(MemoryActivity::class.java.name, nextStarted(main).component?.className)
    }

    @Test fun simulatedTimeHistoricalDatesAndRecallThroughChat() {
        simulate()
        // Quick add from the hub with a month-only date and a person.
        val hub = launch(MemoryActivity::class.java)
        assertTrue(texts(hub).any { it.contains("Today in Clu's timeline: 29 October 2026 (simulated)") })
        click(hub, "+ Quick add")
        val ed = launch(MemoryEditActivity::class.java, nextStarted(hub))
        field(ed, "Title").setText("Met Riya at the college fest")
        field(ed, "What happened (your words)").setText("I met Riya at the college fest in Pune. She played guitar in the band.")
        field(ed, "When did it happen?").setText("around March 2018"); idle()
        field(ed, "People (comma separated)").setText("Riya")
        field(ed, "Places (comma separated)").setText("Pune")
        click(ed, "Save")
        val m = core.memories.list().single { it.title == "Met Riya at the college fest" }
        assertEquals("around March 2018", m.historicalDate().label())
        assertEquals(listOf("Pune"), m.places)
        assertNull("no exact day invented", m.eventAt)
        // The list shows the event date, not the day it was typed in.
        val hub2 = launch(MemoryActivity::class.java)
        assertTrue(texts(hub2).any { it.startsWith("around March 2018 · ") })
        assertFalse(texts(hub2).any { it.contains("29 October 2026 ·") })
        // Clu answers from the record, with the elapsed time from the simulated date and no claim of witnessing it.
        val a = say("Do you remember Riya?")
        assertEquals("Here's what you told me about Riya. You first told me about Riya around March 2018 — that's about eight years ago. " +
            "Around March 2018, you met Riya at the college fest in Pune.", a.substringBefore(" She played"))
        val ago = say("How long ago did I first tell you about her?")
        assertTrue(ago, ago.contains("about eight years ago") || ago.contains("about 8 years ago"))
        val time = say("what time is it?")
        assertTrue("the time tool uses Clu's date: $time", time.contains("29 October 2026"))
        // Moving the simulated date moves "ago".
        simulate("2030-04-01")
        val later = say("How long ago did I first tell you about Riya?")
        assertTrue(later, later.contains("twelve years ago") || later.contains("12 years ago"))
        assertEquals("I don't have anything stored about Zorblax. If you tell me about it, I can remember it.", say("Do you remember Zorblax?"))
    }

    @Test fun peopleProfilesLinkingAmbiguityMergeAndConflictResolution() {
        simulate()
        val chatMem = mem("Rahul Verma's offer", "2021", "Rahul Verma offered me a job at his startup.", people = listOf("Rahul Verma"))
        // Add a person through People → + Add person, with an important date via the dialog.
        val people = launch(ArchivePeopleActivity::class.java)
        click(people, "+ Add person")
        val pe = launch(PersonEditActivity::class.java, nextStarted(people))
        field(pe, "Name").setText("Rahul Verma")
        field(pe, "Relationship to you (optional)").setText("colleague")
        field(pe, "First met / first known (optional)").setText("2020"); idle()
        click(pe, "+ Date"); answerPrompt("Birthday | 14 August 1995 | remind")
        assertTrue(texts(pe).any { it.startsWith("Birthday: 14 August 1995") })
        click(pe, "Save")
        val rahul = core.archive.people.list().single { it.name == "Rahul Verma" }
        assertEquals("2020", rahul.firstKnown.label()); assertEquals("colleague", rahul.relation)
        assertEquals(listOf("Birthday"), rahul.importantDates.map { it.label })
        assertTrue("the existing memory is linked to the new profile", chatMem.id in core.archive.links.linked(Kind.PERSON, rahul.id, Kind.MEMORY))
        assertTrue("the profile lists the memory", texts(pe).contains("Rahul Verma's offer"))
        // A second Rahul, then chat asks which one.
        core.archive.people.save(ArchivePerson(name = "Rahul Sharma", relation = "cousin"))
        assertEquals("Do you mean Rahul Sharma (your cousin) or Rahul Verma (your colleague)?", say("What did I tell you about Rahul?"))
        val a = say("the colleague")
        assertTrue(a, a.startsWith("Yes, I remember Rahul Verma.") && a.contains("offered you a job"))
        // Duplicate warning when adding the same person again.
        val pe2 = launch(PersonEditActivity::class.java)
        field(pe2, "Name").setText("Rahul Varma"); click(pe2, "Save")
        assertTrue(dialogText(), dialogText().contains("Already in People: Rahul Verma"))
        press(AlertDialog.BUTTON_POSITIVE)
        val dup = core.archive.people.list().single { it.name == "Rahul Varma" }
        core.archive.people.save(dup.copy(relation = "friend from work"))
        // Merge from the profile: names become aliases, the differing relation becomes a conflict.
        val prof = launch(PersonEditActivity::class.java, Intent().putExtra("id", rahul.id))
        click(prof, "Merge with…"); pick("Rahul Varma"); press(AlertDialog.BUTTON_POSITIVE)
        val merged = core.archive.people.get(rahul.id)!!
        assertTrue(merged.aliases.contains("Rahul Varma")); assertNull(core.archive.people.get(dup.id))
        assertEquals("colleague", merged.relation)
        // The hub shows the conflict; resolving it on the Conflicts screen applies the chosen value.
        val hub = launch(MemoryActivity::class.java)
        assertTrue(texts(hub).any { it.startsWith("1 conflicting record to review") })
        click(hub, "Review")
        val cf = launch(ConflictsActivity::class.java, nextStarted(hub))
        assertTrue(texts(cf).any { it.contains("says colleague") && it.contains("says friend from work") })
        click(cf, "Use friend from work")
        assertEquals("friend from work", core.archive.people.get(rahul.id)!!.relation)
        assertTrue(core.archive.conflicts.list("OPEN").isEmpty())
        assertTrue(texts(cf).contains("No open conflicts."))
    }

    @Test fun aNewProfileKeepsTypedFieldsWhileThePhotoPickerIsOpen() {
        val pe = launch(PersonEditActivity::class.java)
        field(pe, "Name").setText("Ananya Rao")
        field(pe, "Notes").setText("From the robotics club.")
        click(pe, "Photo")
        val png = File(app.cacheDir, "a.png").apply { outputStream().use { android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        pickFile(pe, png)
        assertEquals("Ananya Rao", field(pe, "Name").text.toString())
        assertEquals("From the robotics club.", field(pe, "Notes").text.toString())
        click(pe, "Save")
        val p = core.archive.people.list().single { it.name == "Ananya Rao" }
        assertEquals("From the robotics club.", p.notes)
        assertTrue("a managed copy of the photo", p.photoPath.isNotEmpty() && File(p.photoPath).exists())
    }

    @Test fun editorDuplicatesMergeSameEventConflictAndKeepsHiddenFields() {
        val first = mem("Met Riya at the college fest", "March 2018", "I met Riya at the fest.", people = listOf("Riya"))
        // A second entry with the same title is flagged; "Merge with it" keeps both texts.
        val ed = launch(MemoryEditActivity::class.java)
        field(ed, "Title").setText("Met Riya at the college fest")
        field(ed, "What happened (your words)").setText("We talked about music until late.")
        field(ed, "When did it happen?").setText("March 2018"); idle()
        click(ed, "Save")
        assertTrue(dialogText(), dialogText().contains("Looks like an existing memory: \"Met Riya at the college fest\" (March 2018)."))
        press(AlertDialog.BUTTON_NEUTRAL); press(AlertDialog.BUTTON_POSITIVE)
        val kept = core.memories.list().filter { it.title == "Met Riya at the college fest" }
        assertEquals(1, kept.size)
        assertTrue(kept[0].content.contains("I met Riya at the fest.") && kept[0].content.contains("We talked about music until late."))
        // "Same event as…" with a different date opens a conflict; the Conflicts screen corrects one record.
        val grad1 = mem("Graduation day", "June 2021", "Graduated.")
        val grad2 = mem("Convocation", "July 2021", "Received the degree.")
        val open = launch(MemoryEditActivity::class.java, Intent().putExtra("id", grad2.id))
        click(open, "Same event as…"); answerPrompt("Graduation"); pick("Graduation day")
        val c = core.archive.conflicts.involving(grad2.id).single()
        assertEquals("date", c.field)
        val cf = launch(ConflictsActivity::class.java)
        click(cf, "Use June 2021")
        assertEquals("June 2021", core.memories.get(grad2.id)!!.historicalDate().label())
        assertEquals("June 2021", core.memories.get(grad1.id)!!.historicalDate().label())
        assertEquals("the replaced date stays in the history", "July 2021", core.memories.revisions(grad2.id).first().second.historicalDate().label())
        // Editing a memory keeps fields the editor doesn't show (expiry, sync metadata).
        val expires = System.currentTimeMillis() + 7 * 86_400_000L
        val temp = core.memories.save(Memory(category = MemoryCategory.MANUAL, title = "Parking spot", content = "Level 2, B14", source = MemorySource.OWNER_SUPPLIED, expiresAt = expires))
        val e2 = launch(MemoryEditActivity::class.java, Intent().putExtra("id", temp.id))
        field(e2, "Title").setText("Parking spot at the mall"); click(e2, "Save")
        val after = core.memories.get(temp.id)!!
        assertEquals("Parking spot at the mall", after.title)
        assertEquals("expiry kept", expires, after.expiresAt)
        assertEquals("created time kept", temp.createdAt, after.createdAt)
        assertTrue("the earlier version is in the history", core.memories.revisions(temp.id).any { it.second.title == "Parking spot" })
    }

    @Test fun documentsImportSearchLinkAndRecall() {
        simulate()
        val docs = launch(DocumentsActivity::class.java)
        click(docs, "+ Import files")
        val req = shadowOf(docs).nextStartedActivityForResult
        val files = listOf("primus-notes.pdf", "astra-log.docx", "corrupt.pdf", "unsupported.xlsx").map { File(fixtures, it).copyTo(File(app.cacheDir, it), true) }
        val clip = android.content.ClipData.newRawUri("files", Uri.fromFile(files[0])).apply { files.drop(1).forEach { addItem(android.content.ClipData.Item(Uri.fromFile(it))) } }
        shadowOf(docs).receiveResult(req.intent, Activity.RESULT_OK, Intent().apply { clipData = clip })
        until("the import summary") { runCatching { dialogText().contains("unsupported.xlsx") }.getOrDefault(false) }
        val summary = dialogText()
        assertTrue(summary, summary.contains("primus-notes.pdf: imported."))
        assertTrue(summary, summary.contains("astra-log.docx: imported."))
        assertTrue(summary, Regex("corrupt\\.pdf: .*(damaged|corrupt)", RegexOption.IGNORE_CASE).containsMatchIn(summary))
        assertTrue(summary, Regex("unsupported\\.xlsx: .*(supported|support)", RegexOption.IGNORE_CASE).containsMatchIn(summary))
        assertEquals(setOf("primus-notes.pdf", "astra-log.docx"), core.archive.documents.list().map { it.name }.toSet())
        press(AlertDialog.BUTTON_POSITIVE)
        assertTrue(texts(docs).contains("2 documents"))
        // Search inside documents from the screen.
        field(docs, "Search inside documents").setText("dataset review"); click(docs, "Search")
        assertTrue(texts(docs).any { it.contains("Sarkar agreed to review the dataset") })
        // Open the PDF, link it to a person, and see it on their profile.
        val riya = core.archive.people.save(ArchivePerson(name = "Amit Sarkar", relation = "lab supervisor"))
        val pdf = core.archive.documents.list().single { it.name == "primus-notes.pdf" }
        val d = launch(DocumentActivity::class.java, Intent().putExtra("id", pdf.id))
        assertTrue(texts(d).any { it.contains("Sarkar agreed to review the dataset") })
        click(d, "Person"); pick("Amit Sarkar")
        assertTrue(texts(d).contains("person: Amit Sarkar"))
        val prof = launch(PersonEditActivity::class.java, Intent().putExtra("id", riya.id))
        assertTrue(texts(prof).contains("primus-notes.pdf"))
        // Hub search finds the document passage too; chat quotes it and says it isn't verified.
        val hub = launch(MemoryActivity::class.java)
        field(hub, "Search memories, people, documents, projects").setText("dataset"); click(hub, "Search")
        assertTrue(texts(hub).contains("📄 primus-notes.pdf"))
        val a = say("What does my document say about the dataset?")
        assertTrue(a, a.startsWith("Your document \"primus-notes.pdf\" says:") && a.contains("Sarkar agreed to review the dataset") && a.endsWith("I haven't verified it."))
    }

    @Test fun projectsTimelineAndHistoricalPerspective() {
        simulate()
        val list = launch(ProjectsActivity::class.java)
        click(list, "+ New project")
        val pe = launch(ProjectEditActivity::class.java, nextStarted(list))
        field(pe, "Name").setText("Primus"); field(pe, "Started (optional)").setText("May 2018"); idle()
        click(pe, "Save")
        val primus = core.archive.projects.list().single()
        assertEquals("May 2018", primus.started.label())
        // "+ Add to history" opens the editor already linked to the project.
        click(pe, "+ Add to history")
        val ed = launch(MemoryEditActivity::class.java, nextStarted(pe))
        assertTrue(texts(ed).contains("Projects: Primus"))
        field(ed, "Title").setText("Primus moved to a GPU server")
        field(ed, "What happened (your words)").setText("We moved Primus to a GPU server because the laptop was too slow.")
        field(ed, "When did it happen?").setText("2024"); idle()
        click(ed, "More details")
        spinnerAfter(ed, "Role in a project or story").setSelection(ai.clu.core.memory.ThreadKind.all.indexOfFirst { it.first == "change" } + 1); idle()
        click(ed, "Save")
        mem("Primus started on my laptop", "May 2018", "We started Primus on my laptop.", kind = "plan").also { core.archive.links.link(Kind.MEMORY, it.id, Kind.PROJECT, primus.id, ai.clu.core.history.Relation.ABOUT) }
        val moved = core.memories.list().single { it.title == "Primus moved to a GPU server" }
        assertTrue(primus.id in core.archive.links.linked(Kind.MEMORY, moved.id, Kind.PROJECT))
        val pe2 = launch(ProjectEditActivity::class.java, Intent().putExtra("id", primus.id))
        val hist = texts(pe2)
        assertTrue("history in date order: $hist", hist.indexOf("Primus started on my laptop") in 0 until hist.indexOf("Primus moved to a GPU server"))
        // Timeline groups by year; the filter narrows by period; gaps are listed as gaps in the record.
        val tl = launch(TimelineActivity::class.java)
        assertTrue(texts(tl).containsAll(listOf("2018", "2024", "Gaps in the archive")))
        field(tl, "From (e.g. 2018, March 2018)").setText("2024"); click(tl, "Apply")
        assertTrue(texts(tl).contains("Primus moved to a GPU server")); assertFalse(texts(tl).contains("Primus started on my laptop"))
        click(tl, "Clear")
        // View as of 1 January 2019: later records disappear from the timeline and from Clu's answers.
        click(tl, "View as of…"); answerPrompt("2019-01-01")
        assertEquals("2019-01-01", core.settings.string(Settings.HISTORY_PERSPECTIVE))
        assertTrue(texts(tl).any { it.startsWith("Historical perspective: showing only what Clu knew by 1 January 2019") })
        assertFalse(texts(tl).contains("Primus moved to a GPU server"))
        assertTrue(texts(tl).contains("Primus started on my laptop"))
        assertEquals("That's after 1 January 2019 — in this historical perspective I don't know about it yet.", say("Do you remember what happened in 2024?"))
        click(tl, "Turn off")
        assertEquals("", core.settings.string(Settings.HISTORY_PERSPECTIVE))
        assertTrue(texts(tl).contains("Primus moved to a GPU server"))
        val changed = say("What did we change later about Primus?")
        assertTrue(changed, changed.contains("GPU server"))
    }

    @Test fun bulkImportFromFileAndBackupExportRestore() {
        // Import from a CSV file through the picker, with a preview first.
        val csv = tmp("memories.csv", ("date,title,details,people,project\n" +
            "March 2018,Met Riya,At the fest,Riya,\n" +
            "2019,Started Primus,On my laptop,,Primus\n" +
            "Marhc 2020,Bad row,typo in date,,\n").toByteArray())
        val imp = launch(ArchiveImportActivity::class.java)
        click(imp, "From file (CSV/JSON/TXT)"); pickFile(imp, csv)
        assertTrue(texts(imp).toString(), texts(imp).any { it.startsWith("2 to add") && it.contains("1 problems") })
        assertTrue(texts(imp).toString(), texts(imp).any { it.startsWith("Line 4:") })
        assertTrue("nothing saved before confirming", core.memories.list().none { it.title == "Met Riya" })
        click(imp, "Add 2 memories")
        until("the import") { core.memories.list().count { it.title in setOf("Met Riya", "Started Primus") } == 2 }
        idle()
        // Importing the same file again adds nothing.
        click(imp, "From file (CSV/JSON/TXT)"); pickFile(imp, csv)
        assertTrue(texts(imp).any { it.startsWith("0 to add") })
        // Backup: Export (owner recently verified), then restore after records are lost.
        core.archive.documents.import("note.txt", "Primus first ran on a Raspberry Pi.".toByteArray())
        val riya = core.archive.people.list().single { it.name == "Riya" }
        app.verifiedAt = System.currentTimeMillis()
        click(imp, "Export with files")
        val out = File(app.cacheDir, "clu-archive.json").apply { delete() }
        pickFile(imp, out)
        until("the export file") { out.length() > 0 }
        val json = out.readText()
        assertTrue(json.contains("\"Met Riya\"") && json.contains("Raspberry Pi"))
        core.archive.people.delete(riya.id)
        core.archive.projects.list().forEach { core.archive.projects.delete(it.id) }
        core.archive.documents.list().forEach { core.archive.documents.delete(it.id) }
        click(imp, "Restore a backup"); pickFile(imp, out)
        assertTrue(texts(imp).any { it.contains("1 people, 1 projects, 1 documents") && it.contains("Nothing here is overwritten") })
        click(imp, "Restore")
        until("the restore") { core.archive.people.get(riya.id) != null && core.archive.documents.list().isNotEmpty() }
        assertEquals(listOf("Primus"), core.archive.projects.list().map { it.name })
        assertEquals("Primus first ran on a Raspberry Pi.", core.archive.documents.text(core.archive.documents.list().single().id).trim())
        assertTrue("the restored file is there too", core.archive.documents.fileBytes(core.archive.documents.list().single()) != null)
        assertEquals("March 2018", core.memories.list().single { it.title == "Met Riya" }.historicalDate().label())
        assertTrue(core.archive.memoriesOf(core.archive.people.get(riya.id)!!).any { it.title == "Met Riya" })
        // A damaged file is refused without changing anything.
        val bad = tmp("bad.json", "{\"format\":\"something else\"".toByteArray())
        val n = core.memories.list().size
        click(imp, "Restore a backup"); pickFile(imp, bad)
        assertFalse(texts(imp).any { it.startsWith("Backup from") })
        assertEquals(n, core.memories.list().size)
    }
}
