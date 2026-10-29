package ai.clu.ui

import ai.clu.core.history.*
import ai.clu.core.memory.Memory
import ai.clu.core.memory.MemoryCategory
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private fun BaseActivity.fileName(uri: Uri): String = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

private fun BaseActivity.readBytes(uri: Uri, max: Long = TextExtractors.MAX_BYTES + 1): ByteArray? = runCatching {
    contentResolver.openInputStream(uri)!!.use { input -> val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(65536); var total = 0L
        while (true) { val n = input.read(buf); if (n < 0) break; total += n; if (total > max) break; out.write(buf, 0, n) }; out.toByteArray() }
}.getOrNull()

// ------------------------------------------------------------------------------------------------ timeline

/** The timeline ("time machine"): memories in event order, filtered by period, person, project, category or keyword; gaps; perspective. */
class TimelineActivity : BaseActivity() {
    private var filter = ArchiveService.TimelineFilter()
    private var fromText = ""; private var toText = ""

    override fun onResume() { super.onResume(); build() }

    private fun build(): Unit = screen("Timeline") {
        val a = core.archive
        val persp = core.time.perspective()
        if (persp != null) card { p("Historical perspective: showing only what Clu knew by ${HistoricalDate.day(persp).label()}.", C.TEXT, 14f)
            smallButton("Turn off") { core.settings.set(ai.clu.core.config.Settings.HISTORY_PERSPECTIVE, ""); build() } }
        val from = field("From (e.g. 2018, March 2018)", fromText); val to = field("To (optional)", toText)
        val people = listOf<ArchivePerson?>(null) + a.people.list()
        spinner(people.map { it?.name ?: "Anyone" }, people.indexOfFirst { it?.id == filter.personId }.coerceAtLeast(0)) { filter = filter.copy(personId = people[it]?.id); build() }
        val projects = listOf<Project?>(null) + a.projects.list()
        spinner(projects.map { it?.name ?: "Any project" }, projects.indexOfFirst { it?.id == filter.projectId }.coerceAtLeast(0)) { filter = filter.copy(projectId = projects[it]?.id); build() }
        val cats = listOf<MemoryCategory?>(null) + MemoryCategory.selectable
        spinner(cats.map { it?.label ?: "All categories" }, cats.indexOf(filter.category)) { filter = filter.copy(category = cats[it]); build() }
        val kw = field("Keyword", filter.keyword)
        row {
            smallButton("Apply") {
                fromText = from.text.toString(); toText = to.text.toString()
                val f = DateParser.parse(fromText)?.takeIf { it.known }; val t = DateParser.parse(toText)?.takeIf { it.known }
                if (fromText.isNotBlank() && f == null || toText.isNotBlank() && t == null) toast("A date isn't understood.")
                filter = filter.copy(from = f?.startDate(), to = (t ?: f)?.endDate(), keyword = kw.text.toString().trim()); build()
            }
            smallButton("Clear") { filter = ArchiveService.TimelineFilter(); fromText = ""; toText = ""; build() }
            smallButton("View as of…") { prompt("Historical perspective", hint = "yyyy-mm-dd (empty = off)") { v ->
                val ok = v.isBlank() || CluTime.parseDay(v) != null
                if (!ok) toast("Use yyyy-mm-dd.") else { core.settings.set(ai.clu.core.config.Settings.HISTORY_PERSPECTIVE, v.trim()); build() } } }
        }
        val list = a.timeline(filter)
        p("${list.size} dated memories, oldest first" + if (filter.from != null) " (${HistoricalDate.range(filter.from!!, filter.to ?: filter.from!!).label()})" else "")
        var lastYear = ""
        list.forEach { m ->
            val y = m.historicalDate().startDate()?.year?.toString() ?: "?"
            if (y != lastYear) { h1(y); lastYear = y }
            memoryCard(this@TimelineActivity, m) { build() }
        }
        val undated = core.memories.list(scope = null, limit = 2000).filter { !it.historicalDate().known && it.category !in setOf(MemoryCategory.INSTRUCTION, MemoryCategory.DIARY) }
        if (filter == ArchiveService.TimelineFilter() && undated.isNotEmpty()) { h1("Undated (${undated.size})"); undated.take(30).forEach { memoryCard(this@TimelineActivity, it) { build() } } }
        h1("Gaps in the archive")
        val gaps = a.gaps()
        if (gaps.isEmpty()) p("No gaps of three months or more since the timeline start.")
        else { p("Periods with no records — gaps in what you've recorded, not proof that nothing happened."); gaps.take(12).forEach { g ->
            row { p(g.label() + (if (g.yearLevelRecords > 0) " (some records give only the year)" else ""), C.TEXT, 13f)
                smallButton("Add") { startActivity(Intent(this@TimelineActivity, MemoryEditActivity::class.java)) } } } }
    }
}

// ------------------------------------------------------------------------------------------------ people

class ArchivePeopleActivity : BaseActivity() {
    private var query = ""
    override fun onResume() { super.onResume(); build() }
    private fun build(): Unit = screen("People") {
        p("Everyone you know, with what you've told Clu about them. Only what you enter is stored; this list gives nobody access to Clu.")
        button("+ Add person", primary = true) { startActivity(Intent(this@ArchivePeopleActivity, PersonEditActivity::class.java)) }
        core.archive.dueReminders().forEach { (p, d) -> card { p("Today: ${p.name} — ${d.label}", C.TEXT, 14f) } }
        val s = field("Search names, nicknames", query)
        smallButton("Search") { query = s.text.toString(); build() }
        val list = if (query.isBlank()) core.archive.people.list() else core.archive.people.find(query).map { it.first }
        p("${list.size} people")
        list.forEach { person -> card {
            p(person.name + (person.aliases.takeIf { it.isNotEmpty() }?.let { " (${it.joinToString()})" } ?: ""), C.TEXT, 15f)
            p(listOfNotNull(person.relation.takeIf { it.isNotBlank() }, person.firstKnown.takeIf { it.known }?.let { "known since ${it.label()}" },
                "${core.archive.memoriesOf(person).size} memories").joinToString(" · "), C.MUTED, 12f)
            smallButton("Open") { startActivity(Intent(this@ArchivePeopleActivity, PersonEditActivity::class.java).putExtra("id", person.id)) }
        } }
    }
}

class PersonEditActivity : BaseActivity() {
    private var person: ArchivePerson? = null
    private val dates = mutableListOf<ImportantDate>()
    private var photo = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        person = intent.getStringExtra("id")?.let { core.archive.people.get(it) }
        person?.let { dates += it.importantDates; photo = it.photoPath }
    }
    override fun onResume() { super.onResume(); person = person?.id?.let { core.archive.people.get(it) } ?: person; build() }

    private fun build() {
        val pr = person
        screen(pr?.name ?: "Add person") {
            if (photo.isNotEmpty()) runCatching { android.graphics.BitmapFactory.decodeFile(photo) }.getOrNull()?.let { bmp ->
                addView(ImageView(context).apply { setImageBitmap(bmp); adjustViewBounds = true; maxHeight = dp(180) }, LinearLayout.LayoutParams(-2, dp(180))) }
            val name = field("Name", pr?.name ?: "")
            val aliases = field("Other names, nicknames, spellings (comma separated)", pr?.aliases?.joinToString(", ") ?: "")
            val relation = field("Relationship to you (optional)", pr?.relation ?: "", hint = "e.g. friend from college")
            val first = HistoricalDateField(this@PersonEditActivity, this, "First met / first known (optional)", pr?.firstKnown)
            val howMet = field("How you know them", pr?.howWeMet ?: "", multiline = true)
            val background = field("Background (optional)", pr?.background ?: "", multiline = true)
            val notes = field("Notes", pr?.notes ?: "", multiline = true)
            val leftOff = field("Where you last left off", pr?.lastLeftOff ?: "", multiline = true)
            val contact = field("Contact (optional — only if you want it stored)", pr?.contact ?: "")
            h1("Important dates")
            val datesBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }; addView(datesBox)
            fun showDates() { datesBox.removeAllViews(); dates.forEachIndexed { i, d -> datesBox.row { datesBox.context.let { }
                p("${d.label}: ${d.date.label()}${if (d.remind) " 🔔" else ""}", C.TEXT, 13f); smallButton("Remove") { dates.removeAt(i); showDates() } } } }
            showDates()
            smallButton("+ Date") { prompt("Important date", hint = "Label | date | remind (e.g. Birthday | 14 August 2000 | remind)") { v ->
                val parts = v.split('|').map { it.trim() }
                val d = parts.getOrNull(1)?.let { DateParser.parse(it) }?.takeIf { it.known }
                if (parts.isEmpty() || parts[0].isBlank() || d == null) toast("Use: label | date | remind") else { dates += ImportantDate(parts[0], d, parts.getOrNull(2)?.startsWith("remind", true) == true); showDates() } } }
            row {
                smallButton("Photo") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), 1) }
                if (photo.isNotEmpty()) smallButton("Remove photo") { photo = ""; build() }
            }
            button("Save", primary = true) {
                val fk = first.value() ?: return@button toast("The date isn't understood.")
                val p = (pr ?: ArchivePerson(name = name.text.toString())).copy(name = name.text.toString().trim(), aliases = aliases.text.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    relation = relation.text.toString().trim(), firstKnown = fk, howWeMet = howMet.text.toString().trim(), background = background.text.toString().trim(),
                    notes = notes.text.toString().trim(), lastLeftOff = leftOff.text.toString().trim(), contact = contact.text.toString().trim(), importantDates = dates.toList(), photoPath = photo)
                if (p.name.isBlank()) return@button toast("A name is needed.")
                val dups = if (pr == null) core.archive.people.duplatesSafe(p) else emptyList()
                val save = { person = core.archive.people.save(p); core.memories.list(scope = null, limit = 5000).filter { m -> m.people.any { Names.score(it, person!!.allNames()) >= 2 } }
                    .forEach { core.archive.saveMemory(it, check = false) }; toast("Saved."); build() }
                if (dups.isNotEmpty()) confirm("Possible duplicate", "Already in People: ${dups.joinToString { it.name }}. Save as a separate person anyway?") { save() } else save()
            }
            if (pr != null) {
                h1("Memories")
                val ms = core.archive.memoriesOf(pr)
                if (ms.isEmpty()) p("None yet.")
                ms.forEach { memoryCard(this@PersonEditActivity, it) { build() } }
                smallButton("+ Memory with ${pr.name}") { startActivity(Intent(this@PersonEditActivity, MemoryEditActivity::class.java).putExtra("person", pr.id)) }
                val docs = core.archive.documentsOf(Kind.PERSON, pr.id)
                if (docs.isNotEmpty()) { h1("Documents"); docs.forEach { d -> card { p(d.name, C.TEXT, 14f); smallButton("Open") { startActivity(Intent(this@PersonEditActivity, DocumentActivity::class.java).putExtra("id", d.id)) } } } }
                h1("Other")
                row {
                    smallButton("Merge with…") { val others = core.archive.people.list().filter { it.id != pr.id }
                        choose("Merge into ${pr.name}", others.map { it.name }) { i -> confirm("Merge ${others[i].name} into ${pr.name}?", "Names become aliases, memories and documents move over; differing details are flagged for you to resolve.") {
                            person = core.archive.mergePeople(pr.id, others[i].id); toast("Merged."); build() } } }
                    smallButton("Delete") { confirm("Delete ${pr.name}?", "Their profile is removed. Memories that mention them stay.") { core.archive.people.delete(pr.id); finish() } }
                }
            }
        }
    }

    private fun ArchivePeopleRepository.duplatesSafe(p: ArchivePerson) = duplicatesOf(p)

    @Deprecated("framework API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK || requestCode != 1) return
        val bytes = readBytes(uri, 15L * 1024 * 1024) ?: return toast("Could not read the photo.")
        if (bytes.size > 15 * 1024 * 1024) return toast("The photo is larger than 15 MB.")
        val dir = java.io.File(filesDir, "archive/photos").apply { mkdirs() }
        val f = java.io.File(dir, "${person?.id ?: ai.clu.core.util.newId()}-${System.currentTimeMillis()}.img").apply { writeBytes(bytes) }
        if (android.graphics.BitmapFactory.decodeFile(f.path) == null) { f.delete(); return toast("That isn't a picture Clu can show.") }
        photo = f.absolutePath; build()
    }
}

// ------------------------------------------------------------------------------------------------ documents

class DocumentsActivity : BaseActivity() {
    private var query = ""
    override fun onResume() { super.onResume(); build() }
    private fun build(): Unit = screen("Documents") {
        p("Files in your archive (stored privately on this device). Supported: TXT, Markdown, CSV, HTML, JSON, PDF and Word (.docx); photos as attachments. " +
            "There's no OCR, so scanned pages have no searchable text.")
        button("+ Import files", primary = true) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 1) }
        val s = field("Search inside documents", query)
        smallButton("Search") { query = s.text.toString(); build() }
        if (query.isNotBlank()) core.archive.documents.search(query, limit = 10).forEach { ps -> card { p(ps.document.name, C.TEXT, 14f); p(ps.text.take(300), C.TEXT, 13f)
            smallButton("Open") { startActivity(Intent(this@DocumentsActivity, DocumentActivity::class.java).putExtra("id", ps.document.id)) } } }
        val list = core.archive.documents.list()
        h1("${list.size} documents")
        list.forEach { d -> card {
            p(d.name, C.TEXT, 15f)
            p(listOfNotNull(d.type.uppercase(), fmtBytes(d.sizeBytes), d.date.takeIf { it.known }?.label(), if (d.textStatus == "indexed") "${d.chars} characters indexed" else "no searchable text").joinToString(" · "), C.MUTED, 12f)
            smallButton("Open") { startActivity(Intent(this@DocumentsActivity, DocumentActivity::class.java).putExtra("id", d.id)) }
        } }
    }

    @Deprecated("framework API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || requestCode != 1 || data == null) return
        val uris = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
        importAll(uris)
    }

    private fun importAll(uris: List<Uri>, allowNoText: Set<Uri> = emptySet()): kotlinx.coroutines.Job = scope.launch {
        val results = mutableListOf<String>(); val noText = mutableListOf<Uri>()
        for ((i, u) in uris.withIndex()) {
            val name = fileName(u)
            toast("Importing ${i + 1} of ${uris.size}: $name")
            val msg = withContext(Dispatchers.IO) {
                val bytes = readBytes(u) ?: return@withContext "$name: couldn't be read."
                try { core.archive.documents.import(name, bytes, originalRef = u.toString(), allowNoText = u in allowNoText).let { "$name: imported${if (it.textStatus == "no_text") " (no searchable text)" else ""}." } }
                catch (e: DocumentImportException) { if (e.reason == DocumentImportException.Reason.NO_TEXT) noText += u; "$name: ${e.message}" }
            }
            results += msg
        }
        build()
        android.app.AlertDialog.Builder(this@DocumentsActivity).setTitle("Import").setMessage(results.joinToString("\n\n")).setPositiveButton("OK", null).apply {
            if (noText.isNotEmpty()) setNeutralButton("Keep ${noText.size} as attachment${if (noText.size > 1) "s" else ""}") { _, _ -> importAll(noText, noText.toSet()) }
        }.show()
    }
}

class DocumentActivity : BaseActivity() {
    private lateinit var doc: ArchiveDocument
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        doc = intent.getStringExtra("id")?.let { core.archive.documents.get(it) } ?: return finish()
        build()
    }
    private fun build(): Unit = screen(doc.name) {
        p("${doc.type.uppercase()} · ${fmtBytes(doc.sizeBytes)} · ${if (doc.textStatus == "indexed") "${doc.chars} characters of searchable text" else "no searchable text (attachment only)"}", C.MUTED, 12f)
        val desc = field("Description", doc.description, multiline = true)
        val date = HistoricalDateField(this@DocumentActivity, this, "Date of the document (optional)", doc.date)
        val tags = field("Tags (comma separated)", doc.tags.joinToString(", "))
        button("Save", primary = true) {
            val hd = date.value() ?: return@button toast("The date isn't understood.")
            doc = core.archive.documents.update(doc.copy(description = desc.text.toString().trim(), date = hd, tags = tags.text.split(",").map { it.trim() }.filter { it.isNotEmpty() })); toast("Saved.")
        }
        h1("Linked to")
        val links = core.archive.links.of(Kind.DOCUMENT, doc.id)
        links.forEach { l -> val (k, id) = if (l.aKind == Kind.DOCUMENT && l.aId == doc.id) l.bKind to l.bId else l.aKind to l.aId
            val label = when (k) { Kind.PERSON -> core.archive.people.get(id)?.name; Kind.PROJECT -> core.archive.projects.get(id)?.name; Kind.MEMORY -> core.memories.get(id)?.title; else -> null } ?: return@forEach
            row { p("$k: $label", C.TEXT, 13f); smallButton("Unlink") { core.archive.links.unlink(l.id); build() } } }
        row {
            smallButton("Person") { val ps = core.archive.people.list(); choose("Link to person", ps.map { it.name }) { i -> core.archive.links.link(Kind.DOCUMENT, doc.id, Kind.PERSON, ps[i].id, Relation.ABOUT); build() } }
            smallButton("Project") { val ps = core.archive.projects.list(); choose("Link to project", ps.map { it.name }) { i -> core.archive.links.link(Kind.DOCUMENT, doc.id, Kind.PROJECT, ps[i].id, Relation.ABOUT); build() } }
            smallButton("Memory") { prompt("Find memory", hint = "words from its title") { q -> val f = core.memories.search(q, null).take(15)
                choose("Link to memory", f.map { it.title }) { i -> core.archive.links.link(Kind.DOCUMENT, doc.id, Kind.MEMORY, f[i].id, Relation.SUPPORTS); build() } } }
        }
        if (doc.textStatus == "indexed") { h1("Text"); p(core.archive.documents.text(doc.id).take(4000), C.TEXT, 13f) }
        row {
            smallButton("Save a copy") { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_TITLE, doc.name), 1) }
            smallButton("Delete") { confirm("Delete ${doc.name}?", "The file and its text are removed from the archive.") { core.archive.documents.delete(doc.id); finish() } }
        }
    }
    @Deprecated("framework API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        val bytes = core.archive.documents.fileBytes(doc) ?: return toast("The stored copy is missing.")
        runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(bytes) } }.onSuccess { toast("Saved.") }.onFailure { toast("Couldn't save: ${it.message}") }
    }
}

// ------------------------------------------------------------------------------------------------ projects

class ProjectsActivity : BaseActivity() {
    override fun onResume() { super.onResume(); build() }
    private fun build(): Unit = screen("Projects & events") {
        p("Projects and long-running stories (Astra, Primus, Clu…): plans, decisions, changes, abandoned approaches and lessons, in order.")
        button("+ New project", primary = true) { startActivity(Intent(this@ProjectsActivity, ProjectEditActivity::class.java)) }
        core.archive.projects.list().forEach { pj -> card {
            p(pj.name, C.TEXT, 15f)
            p(listOfNotNull(pj.status, pj.started.takeIf { it.known }?.let { "since ${it.label()}" }, "${core.archive.memoriesOf(pj).size} memories").joinToString(" · "), C.MUTED, 12f)
            smallButton("Open") { startActivity(Intent(this@ProjectsActivity, ProjectEditActivity::class.java).putExtra("id", pj.id)) }
        } }
    }
}

class ProjectEditActivity : BaseActivity() {
    private var project: Project? = null
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); project = intent.getStringExtra("id")?.let { core.archive.projects.get(it) } }
    override fun onResume() { super.onResume(); build() }
    private fun build() {
        val pj = project
        screen(pj?.name ?: "New project") {
            val name = field("Name", pj?.name ?: "")
            val aliases = field("Other names (comma separated)", pj?.aliases?.joinToString(", ") ?: "")
            val desc = field("Description", pj?.description ?: "", multiline = true)
            val statuses = listOf("active", "paused", "done", "abandoned")
            p("Status", C.TEXT, 13f)
            val status = spinner(statuses, statuses.indexOf(pj?.status ?: "active").coerceAtLeast(0))
            val started = HistoricalDateField(this@ProjectEditActivity, this, "Started (optional)", pj?.started)
            val ended = HistoricalDateField(this@ProjectEditActivity, this, "Ended (optional)", pj?.ended)
            button("Save", primary = true) {
                val s = started.value() ?: return@button toast("The start date isn't understood."); val e = ended.value() ?: return@button toast("The end date isn't understood.")
                if (name.text.isBlank()) return@button toast("A name is needed.")
                project = core.archive.projects.save((pj ?: Project(name = name.text.toString())).copy(name = name.text.toString().trim(), aliases = aliases.text.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    description = desc.text.toString().trim(), status = statuses[status.selectedItemPosition], started = s, ended = e)); toast("Saved."); build()
            }
            if (pj != null) {
                h1("History")
                val ms = core.archive.memoriesOf(pj)
                if (ms.isEmpty()) p("No memories yet.")
                ms.forEach { m -> memoryCard(this@ProjectEditActivity, m) { build() } }
                smallButton("+ Add to history") { startActivity(Intent(this@ProjectEditActivity, MemoryEditActivity::class.java).putExtra("project", pj.id)) }
                smallButton("Delete project") { confirm("Delete ${pj.name}?", "The project record is removed; its memories stay.") { core.archive.projects.delete(pj.id); finish() } }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ import / export / conflicts

/** Bulk entry, structured import with preview, and archive backup/restore. */
class ArchiveImportActivity : BaseActivity() {
    private var preview: ArchiveService.ImportPreview? = null
    private var bundle: ArchiveService.BundlePreview? = null
    private var legacy: List<Memory>? = null
    private var running = false
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); build() }

    private fun build(): Unit = screen("Import & export") {
        h1("Add many memories")
        p("One per line: date | title | details | people: … | places: … | project: … | tags: … | kind: decision | told: … | certainty: uncertain\n" +
            "Dates can be \"March 2018\", \"around 2019\", \"between 2018 and 2019\", \"12 March 2018\" or empty. CSV (with a header row) and JSON work too.", C.MUTED, 12f)
        input = field("Memories", if (::input.isInitialized) input.text.toString() else "", multiline = true,
            hint = "March 2018 | Met Riya at the college fest | She was in the band | people: Riya, Karan | places: Pune")
        row {
            smallButton("Preview") { preview = core.archive.previewImport(input.text.toString(), "bulk entry"); build() }
            smallButton("From file (CSV/JSON/TXT)") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 1) }
        }
        preview?.let { pv -> card {
            p("${pv.importable.size} to add · ${pv.rows.count { it.duplicateOf != null }} likely duplicates (skipped) · ${pv.rows.count { it.alreadyImported }} already imported · ${pv.errors.size} problems", C.TEXT, 14f)
            pv.errors.take(20).forEach { p("Line ${it.line}: ${it.message}", C.TEXT, 12f) }
            pv.rows.filter { it.duplicateOf != null }.take(10).forEach { p("Line ${it.line}: \"${it.memory.title}\" looks like \"${it.duplicateOf!!.title}\"", C.MUTED, 12f) }
            if (pv.newPeople.isNotEmpty()) p("New people profiles: ${pv.newPeople.joinToString()}", C.MUTED, 12f)
            if (pv.newProjects.isNotEmpty()) p("New projects: ${pv.newProjects.joinToString()}", C.MUTED, 12f)
            pv.rows.take(8).forEach { p("• ${it.memory.historicalDate().label()} — ${it.memory.title}" + (if (it.hints.isNotEmpty()) " (${it.hints.first()})" else ""), C.TEXT, 12f) }
            val progress = p("", C.MUTED, 12f)
            if (pv.importable.isNotEmpty() && !running) smallButton("Add ${pv.importable.size} memories") {
                running = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { core.archive.applyImport(pv, progress = { d, t -> runOnUiThread { progress.text = "$d of $t…" } }) }
                    running = false; preview = null
                    toast(when (r.status) { "done" -> "Added ${r.imported} memories." ; "interrupted" -> "Stopped after ${r.imported}. Import again to continue — nothing is added twice."
                        else -> "Stopped: ${r.error}. ${r.imported} were added; import again to continue." }); build()
                }
            }
        } }
        core.archive.unfinishedImports().take(3).forEach { (_, src, st) -> p("An import of \"$src\" was $st. Import the same text or file again to finish it; rows already added are skipped.", C.MUTED, 12f) }

        h1("Backup")
        p("The whole archive in one file: memories with their dates, people, projects, documents (text, and optionally the files), links and conflicts. Keep it somewhere safe; it isn't encrypted.", C.MUTED, 12f)
        row {
            smallButton("Export") { verifyOwner("Export your archive") { ok -> if (ok) startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "clu-archive.json"), 2) } }
            smallButton("Export with files") { verifyOwner("Export your archive") { ok -> if (ok) startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "clu-archive-with-files.json"), 3) } }
        }
        smallButton("Restore a backup") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 4) }
        bundle?.let { b -> card {
            p("Backup from ${fmtTime(b.bundle.exportedAt)}: ${b.newMemories} new memories (${b.existingMemories} already here), ${b.newPeople} people, ${b.newProjects} projects, ${b.newDocuments} documents. Nothing here is overwritten.", C.TEXT, 13f)
            smallButton("Restore") { val n = core.archive.restoreBundle(b); bundle = null; toast("Restored $n records."); build() }
        } }
        legacy?.let { l -> card {
            val fresh = l.filter { !it.deleted && core.memories.get(it.id) == null }
            p("An older memory export: ${fresh.size} memories not yet here.", C.TEXT, 13f)
            smallButton("Import them") { fresh.forEach { core.memories.save(it) }; legacy = null; toast("Imported ${fresh.size} memories."); build() }
        } }
    }

    @Deprecated("framework API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            1 -> { val text = readBytes(uri, 20L * 1024 * 1024)?.let { TextExtractors.decodeText(it) } ?: return toast("Couldn't read the file.")
                preview = core.archive.previewImport(text, fileName(uri)); build() }
            2, 3 -> scope.launch {
                val json = withContext(Dispatchers.IO) { core.archive.exportBundle(includeFiles = requestCode == 3) }
                runCatching { withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) } } }
                    .onSuccess { toast("Exported.") }.onFailure { toast("Export failed: ${it.message}") }
            }
            4 -> { val text = readBytes(uri, 512L * 1024 * 1024)?.toString(Charsets.UTF_8) ?: return toast("Couldn't read the file.")
                runCatching { bundle = core.archive.previewBundle(text) }.onFailure {
                    // An export from before 1.8.0 (a plain list of memories).
                    legacy = runCatching { ai.clu.core.util.CluJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Memory.serializer()), text) }.getOrNull()
                    if (legacy == null) toast(it.message ?: "This isn't a Clu archive backup.") }
                build() }
        }
    }
}

class ConflictsActivity : BaseActivity() {
    override fun onResume() { super.onResume(); build() }
    private fun build(): Unit = screen("Conflicts") {
        p("Records that disagree. Nothing was overwritten; choose which is right, or keep both accounts.")
        val list = core.archive.conflicts.list("OPEN")
        if (list.isEmpty()) p("No open conflicts.", C.TEXT)
        list.forEach { c -> card {
            val (a, b) = if (c.kind == Kind.MEMORY) (core.memories.get(c.aId)?.title ?: "?") to (core.memories.get(c.bId)?.title ?: "?")
                else (core.archive.people.get(c.aId)?.name ?: "?") to "merged profile"
            p("${c.field}: \"$a\" says ${c.aValue}; \"$b\" says ${c.bValue}", C.TEXT, 14f)
            row {
                smallButton("Use ${c.aValue.take(18)}") { core.archive.resolveConflict(c.id, "a"); build() }
                smallButton("Use ${c.bValue.take(18)}") { core.archive.resolveConflict(c.id, "b"); build() }
            }
            row { smallButton("Keep both") { core.archive.resolveConflict(c.id, "both"); build() }; smallButton("Not a conflict") { core.archive.resolveConflict(c.id, "dismiss"); build() } }
        } }
    }
}
