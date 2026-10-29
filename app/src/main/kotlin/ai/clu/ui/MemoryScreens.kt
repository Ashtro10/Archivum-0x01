package ai.clu.ui

import ai.clu.core.memory.*
import ai.clu.core.history.DateParser
import ai.clu.core.history.HistoricalDate
import ai.clu.core.util.CluJson
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.*
import kotlinx.serialization.builtins.ListSerializer
import java.util.Calendar

/**
 * Memory: the personal archive's home. Quick add, the main areas (timeline, people, documents, projects), search,
 * import/export and settings, then the memories themselves. Long-term memory is separate from conversation history.
 */
class MemoryActivity : BaseActivity() {
    private var category: MemoryCategory? = null
    private var query = ""
    private var memScope = CLU_SCOPE

    override fun onResume() { super.onResume(); build() }

    private fun build() {
        screen("Memory") {
            val t = core.time
            p("Clu's timeline starts ${HistoricalDate.day(t.timelineStart()).label()}. Today in Clu's timeline: ${HistoricalDate.day(t.today()).label()}" +
                (when { t.perspective() != null -> " (historical perspective — only what Clu knew by then)"; t.simulated() -> " (simulated)"; else -> "" }) + ".", C.MUTED, 13f)
            if (!core.settings.bool(ai.clu.core.config.Settings.MEMORY_ENABLED)) p("⚠ Persistent memory is OFF – nothing new is saved.", C.TEXT)
            row {
                smallButton("+ Quick add") { startActivity(Intent(this@MemoryActivity, MemoryEditActivity::class.java).putExtra("scope", memScope)) }
                smallButton("Bulk add") { startActivity(Intent(this@MemoryActivity, ArchiveImportActivity::class.java)) }
            }
            row {
                smallButton("Timeline") { startActivity(Intent(this@MemoryActivity, TimelineActivity::class.java)) }
                smallButton("People (${core.archive.people.list().size})") { startActivity(Intent(this@MemoryActivity, ArchivePeopleActivity::class.java)) }
            }
            row {
                smallButton("Documents (${core.archive.documents.list().size})") { startActivity(Intent(this@MemoryActivity, DocumentsActivity::class.java)) }
                smallButton("Projects (${core.archive.projects.list().size})") { startActivity(Intent(this@MemoryActivity, ProjectsActivity::class.java)) }
            }
            row {
                smallButton("Import & export") { startActivity(Intent(this@MemoryActivity, ArchiveImportActivity::class.java).putExtra("backup", true)) }
                smallButton("Settings") { startActivity(Intent(this@MemoryActivity, SettingsCategoryActivity::class.java).putExtra("cat", ai.clu.core.config.SettingCategory.MEMORY.name)) }
            }
            row {
                smallButton("Diary (${core.diary.count()})") { startActivity(Intent(this@MemoryActivity, DiaryActivity::class.java)) }
                smallButton("Suggestions (${core.proposals.pending().size})") { startActivity(Intent(this@MemoryActivity, ProposalsActivity::class.java)) }
            }
            val open = core.archive.conflicts.list("OPEN")
            if (open.isNotEmpty()) card { p("${open.size} conflicting record${if (open.size > 1) "s" else ""} to review (nothing was overwritten).", C.TEXT, 14f)
                smallButton("Review") { startActivity(Intent(this@MemoryActivity, ConflictsActivity::class.java)) } }
            core.archive.dueReminders().forEach { (person, d) -> card { p("Today: ${person.name} — ${d.label}", C.TEXT, 14f) } }
            h1("Memories")
            val scopes = listOf(CLU_SCOPE to "Clu") + core.characters.list().map { it.id to "${it.name} (character)" }
            spinner(scopes.map { it.second }, scopes.indexOfFirst { it.first == memScope }) { memScope = scopes[it].first; build() }
            val cats = listOf<MemoryCategory?>(null) + MemoryCategory.selectable
            spinner(cats.map { it?.label ?: "All categories" }, cats.indexOf(category)) { category = cats[it]; build() }
            val search = field("Search memories, people, documents, projects", query)
            smallButton("Search") { query = search.text.toString(); build() }
            if (query.isNotBlank()) {
                val people = core.archive.people.find(query).map { it.first }.take(5)
                val projects = core.archive.projects.list().filter { p -> p.allNames().any { it.contains(query, true) } }.take(5)
                val passages = core.archive.documents.search(query, limit = 3)
                people.forEach { pr -> card { p("👤 ${pr.name}" + (pr.relation.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), C.TEXT, 15f)
                    smallButton("Open") { startActivity(Intent(this@MemoryActivity, PersonEditActivity::class.java).putExtra("id", pr.id)) } } }
                projects.forEach { pj -> card { p("📁 ${pj.name}", C.TEXT, 15f)
                    smallButton("Open") { startActivity(Intent(this@MemoryActivity, ProjectEditActivity::class.java).putExtra("id", pj.id)) } } }
                passages.forEach { ps -> card { p("📄 ${ps.document.name}", C.TEXT, 15f); p(ps.text.take(240), C.TEXT, 13f)
                    smallButton("Open") { startActivity(Intent(this@MemoryActivity, DocumentActivity::class.java).putExtra("id", ps.document.id)) } } }
            }
            val items = if (query.isNotBlank()) core.memories.search(query, memScope).filter { category == null || it.category == category }
                else core.memories.list(memScope, category)
            p("${items.size} memories")
            items.forEach { m -> memoryCard(this@MemoryActivity, m) { build() } }
        }
    }
}

/** One memory in a list: title, when it happened (never when it was typed in), category and people. */
fun LinearLayout.memoryCard(a: BaseActivity, m: Memory, onChange: () -> Unit) = card {
    p((if (m.pinned) "📌 " else "") + (if (m.importance > 0) "★".repeat(m.importance) + " " else "") + m.title, C.TEXT, 15f)
    p(listOfNotNull(m.historicalDate().label(), m.category.label, m.certainty.takeIf { it != Certainty.RECOLLECTION }?.label,
        m.people.takeIf { it.isNotEmpty() }?.joinToString(), m.places.takeIf { it.isNotEmpty() }?.joinToString(),
        m.expiresAt?.let { "expires ${fmtTime(it)}" }).joinToString(" · "), C.MUTED, 12f)
    p(m.bodyForPrompt().take(240), C.TEXT, 13f)
    row {
        smallButton("Open") { a.startActivity(Intent(a, MemoryEditActivity::class.java).putExtra("id", m.id)) }
        smallButton(if (m.pinned) "Unpin" else "Pin") { a.core.memories.setPinned(m.id, !m.pinned); onChange() }
        smallButton("Delete") { a.confirm("Delete memory?", m.title + "\n\nIt is removed with its earlier versions.") { a.core.memories.delete(m.id); onChange() } }
    }
}

/**
 * A "when did it happen?" field: free text ("March 2018", "around 2019", "between 2018 and 2019", "12 March 2018",
 * "unknown") with a live reading of what Clu understood, plus an exact-day picker. Never fills in today's date.
 */
class HistoricalDateField(private val a: BaseActivity, parent: LinearLayout, label: String, initial: HistoricalDate?, hint: String = "e.g. March 2018, around 2019, 12 March 2018, unknown") {
    val edit: EditText = parent.field(label, initial?.takeIf { it.known }?.label() ?: "", hint = hint)
    private val reading: TextView = parent.p("", C.MUTED, 12f)
    init {
        update()
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = update()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        parent.row {
            smallButton("Pick exact day") {
                val c = Calendar.getInstance(); value()?.startDate()?.let { c.set(it.year, it.monthValue - 1, it.dayOfMonth) }
                DatePickerDialog(a, { _, y, mo, d -> edit.setText(HistoricalDate.day(java.time.LocalDate.of(y, mo + 1, d)).label()) },
                    c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
            }
            smallButton("Unknown") { edit.setText("") }
        }
    }
    /** The parsed date, UNKNOWN when empty, or null when the text can't be read. */
    fun value(): HistoricalDate? = edit.text.toString().trim().let { if (it.isEmpty()) HistoricalDate.UNKNOWN else DateParser.parse(it) }
    private fun update() {
        val v = value()
        reading.text = when { v == null -> "⚠ Not understood — try \"March 2018\", \"around 2019\" or \"between 2018 and 2019\"."; !v.known -> "Date unknown (that's fine)."; else -> "Understood as: ${v.label()}" }
    }
}

/** The memory editor: what happened, when (as precisely as known), who/where/which project, how sure, how important. */
class MemoryEditActivity : BaseActivity() {
    private var existing: Memory? = null
    private lateinit var textField: EditText
    private val projectIds = mutableListOf<String>()
    private val documentIds = mutableListOf<String>()
    private val relatedIds = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        existing = intent.getStringExtra("id")?.let { core.memories.get(it) }
        existing?.let { m ->
            projectIds += core.archive.links.linked(ai.clu.core.history.Kind.MEMORY, m.id, ai.clu.core.history.Kind.PROJECT)
            documentIds += core.archive.links.linked(ai.clu.core.history.Kind.MEMORY, m.id, ai.clu.core.history.Kind.DOCUMENT)
            relatedIds += core.archive.links.linked(ai.clu.core.history.Kind.MEMORY, m.id, ai.clu.core.history.Kind.MEMORY)
        }
        intent.getStringExtra("project")?.let { if (it !in projectIds) projectIds += it }
        intent.getStringExtra("person")?.let { pid -> core.archive.people.get(pid)?.let { intent.putExtra("personName", it.name) } }
        build()
    }

    private fun build() {
        val m = existing
        screen(if (m == null) "Add memory" else "Edit memory") {
            m?.let { p("Source: ${it.source.label}", C.MUTED, 12f) }
            val title = field("Title", m?.title ?: "", hint = "e.g. Met Riya at the college fest")
            textField = field("What happened (your words)", m?.content ?: "", multiline = true)
            val date = HistoricalDateField(this@MemoryEditActivity, this, "When did it happen?", m?.historicalDate())
            val people = field("People (comma separated)", m?.people?.joinToString(", ") ?: intent.getStringExtra("personName") ?: "")
            val places = field("Places (comma separated)", m?.places?.joinToString(", ") ?: "")
            val tags = field("Tags (comma separated)", m?.tags?.joinToString(", ") ?: "")
            val projLabel = p("", C.TEXT, 13f)
            fun showProjects() { projLabel.text = "Projects: " + projectIds.mapNotNull { core.archive.projects.get(it)?.name }.ifEmpty { listOf("none") }.joinToString() }
            showProjects()
            row {
                smallButton("Add project") {
                    val all = core.archive.projects.list()
                    choose("Project", all.map { it.name } + "+ New project…") { i ->
                        if (i < all.size) { if (all[i].id !in projectIds) projectIds += all[i].id; showProjects() }
                        else prompt("New project", hint = "Name") { n -> if (n.isNotBlank()) { projectIds += core.archive.projects.save(ai.clu.core.history.Project(name = n)).id; showProjects() } }
                    }
                }
                smallButton("Clear") { projectIds.clear(); showProjects() }
            }
            val cats = (MemoryCategory.selectable + listOfNotNull(m?.category)).distinct()
            p("Category", C.TEXT, 13f)
            val cat = spinner(cats.map { it.label }, cats.indexOf(m?.category ?: MemoryCategory.EXPERIENCE))
            p("How sure is this?", C.TEXT, 13f)
            val certainty = spinner(Certainty.values().map { it.label }, (m?.certainty ?: Certainty.RECOLLECTION).ordinal)
            p("Importance", C.TEXT, 13f)
            val importance = spinner(listOf("Normal", "Important", "Very important"), m?.importance ?: 0)
            // Advanced details, shown on request (progressive disclosure).
            val adv = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = if (m != null && (m.told != null || m.threadKind.isNotEmpty() || m.links.isNotEmpty() || m.significance.isNotBlank())) android.view.View.VISIBLE else android.view.View.GONE }
            smallButton("More details") { adv.visibility = if (adv.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE }
            addView(adv)
            val toldDefault = if (core.settings.string(ai.clu.core.config.Settings.TOLD_DEFAULT) == "event") "when it happened" else "unknown"
            val told = HistoricalDateField(this@MemoryEditActivity, adv, "When you told Clu (in Clu's timeline)", m?.told, hint = "empty = $toldDefault")
            val significance = adv.field("Personal significance (optional)", m?.significance ?: "")
            val kinds = listOf("" to "—") + ThreadKind.all
            adv.p("Role in a project or story", C.TEXT, 13f)
            val kind = adv.spinner(kinds.map { it.second }, kinds.indexOfFirst { it.first == (m?.threadKind ?: "") }.coerceAtLeast(0))
            val links = adv.field("Links (one per line)", m?.links?.joinToString("\n") ?: "", multiline = true)
            val summary = adv.field("Summary (optional, your words)", m?.summary ?: "", multiline = true)
            val modes = listOf(RetainMode.FULL to "Keep full original text", RetainMode.BOTH to "Keep text and summary", RetainMode.SUMMARY to "Keep summary only (discard original)")
            adv.p("Retention", C.TEXT, 13f)
            val mode = adv.spinner(modes.map { it.second }, modes.indexOfFirst { it.first == (m?.retainMode ?: RetainMode.FULL) })
            val docLabel = adv.p("", C.TEXT, 13f)
            fun showDocs() { docLabel.text = "Attached documents: " + documentIds.mapNotNull { core.archive.documents.get(it)?.name }.ifEmpty { listOf("none") }.joinToString() }
            showDocs()
            adv.row { smallButton("Attach document") { val all = core.archive.documents.list(); if (all.isEmpty()) toast("Import documents in Memory → Documents first.")
                else choose("Document", all.map { it.name }) { i -> if (all[i].id !in documentIds) documentIds += all[i].id; showDocs() } }
                smallButton("Import text file") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*"), 1) } }
            val relLabel = adv.p("", C.TEXT, 13f)
            fun showRel() { relLabel.text = "Related memories: " + relatedIds.mapNotNull { core.memories.get(it)?.title }.ifEmpty { listOf("none") }.joinToString() }
            showRel()
            adv.smallButton("Link a related memory") { prompt("Find memory", hint = "words from its title") { q -> val found = core.memories.search(q, null).filter { it.id != m?.id }.take(15)
                if (found.isEmpty()) toast("Nothing found.") else choose("Related memory", found.map { "${it.title} (${it.historicalDate().label()})" }) { i -> relatedIds += found[i].id; showRel() } } }
            val pinned = check("Pinned (always considered first)", m?.pinned ?: false)
            p("Clu treats what you enter as your account, not as something it witnessed. The date you enter it is never used as the date it happened.", C.MUTED, 12f)
            button("Save", primary = true) {
                val hd = date.value() ?: return@button toast("The date isn't understood — fix it or leave it empty.")
                val toldDate = told.value() ?: return@button toast("The 'told' date isn't understood.")
                val retain = modes[mode.selectedItemPosition].first
                val base = existing
                val mem = Memory(id = base?.id ?: ai.clu.core.util.newId(), category = cats[cat.selectedItemPosition], title = title.text.toString().trim(),
                    content = textField.text.toString(), summary = summary.text.toString().trim().ifEmpty { null }, retainMode = retain, eventDate = hd,
                    told = toldDate.takeIf { it.known }, source = base?.source ?: MemorySource.OWNER_SUPPLIED, people = split(people), places = split(places), tags = split(tags),
                    pinned = pinned.isChecked, scope = base?.scope ?: intent.getStringExtra("scope") ?: CLU_SCOPE, createdAt = base?.createdAt ?: 0,
                    certainty = Certainty.values()[certainty.selectedItemPosition], importance = importance.selectedItemPosition, significance = significance.text.toString().trim(),
                    threadKind = kinds[kind.selectedItemPosition].first, links = links.text.lines().map { it.trim() }.filter { it.isNotEmpty() })
                val go = { save(mem) }
                if (retain == RetainMode.SUMMARY && base?.retainMode != RetainMode.SUMMARY) confirm("Discard original text?", "Only the summary will be kept.") { go() } else go()
            }
            if (m != null) {
                row {
                    smallButton("Same event as…") { prompt("Find the other record", hint = "words from its title") { q -> val found = core.memories.search(q, null).filter { it.id != m.id }.take(15)
                        if (found.isEmpty()) toast("Nothing found.") else choose("Same event", found.map { "${it.title} (${it.historicalDate().label()})" }) { i ->
                            val c = core.archive.linkSameEvent(m.id, found[i].id)
                            toast(if (c != null) "Linked. Their dates disagree — flagged for review (nothing changed)." else "Linked as the same event.") } } }
                    smallButton("History (${core.memories.revisions(m.id).size})") {
                        val revs = core.memories.revisions(m.id)
                        if (revs.isEmpty()) toast("No earlier versions.")
                        else choose("Earlier versions", revs.map { (at, r) -> "${fmtTime(at)} — ${r.title}: ${r.historicalDate().label()} · ${r.content.take(60)}" }) { i ->
                            confirm("Restore this version?", "The current version is kept in the history.") { core.memories.revisionReason = "restored an earlier version"
                                core.memories.save(revs[i].second.copy(id = m.id, deleted = false)); finish() } }
                    }
                }
                button("Delete") { confirm("Delete this memory?", m.title + "\n\nIt is removed with its earlier versions.") { core.memories.delete(m.id); finish() } }
            }
        }
    }

    private fun split(e: EditText) = e.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun save(mem: Memory) {
        val r = runCatching { core.archive.saveMemory(mem, projectIds = projectIds, relatedIds = relatedIds, documentIds = documentIds) }
            .getOrElse { return toast(it.message ?: "Could not save") }
        existing = r.memory
        val notes = buildList {
            r.duplicates.forEach { add("Looks like an existing memory: \"${it.title}\" (${it.historicalDate().label()}).") }
            r.conflicts.forEach { add("Another record gives a different date (${it.bValue}); both are kept — review it under Memory → conflicts.") }
            r.ambiguousPeople.forEach { (n, ps) -> add("\"$n\" could be ${ps.joinToString(" or ") { it.name }} — not linked; edit the name to be specific.") }
            addAll(r.hints)
        }
        if (notes.isEmpty()) { toast("Saved."); finish(); return }
        val b = android.app.AlertDialog.Builder(this).setTitle("Saved").setMessage(notes.joinToString("\n\n")).setPositiveButton("OK") { _, _ -> finish() }
        r.duplicates.firstOrNull()?.let { d -> b.setNeutralButton("Merge with it") { _, _ ->
            confirm("Merge?", "\"${r.memory.title}\" will be merged into \"${d.title}\". Both texts are kept; differing dates are flagged.") {
                core.archive.mergeMemories(d.id, r.memory.id); toast("Merged."); finish() } } }
        val unknown = r.memory.people.filter { core.archive.people.resolve(it).isEmpty() }
        if (unknown.isNotEmpty()) b.setNegativeButton("Add ${unknown.first()} to People") { _, _ ->
            core.archive.people.save(ai.clu.core.history.ArchivePerson(name = unknown.first())); core.archive.saveMemory(r.memory, check = false); toast("Added to People."); finish() }
        b.show()
    }

    @Deprecated("framework API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val text = runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() ?: return toast("Could not read file")
        textField.setText(text.take(200_000))
    }
}

class ProposalsActivity : BaseActivity() {
    override fun onResume() { super.onResume(); build() }
    private fun build(): Unit = screen("Memory suggestions") {
        p("Clu never saves an inferred memory without your approval. Rejected suggestions are erased.")
        val list = core.proposals.pending()
        if (list.isEmpty()) p("No pending suggestions.", C.TEXT)
        list.forEach { pr ->
            card {
                p("${pr.kind.label} · ${fmtTime(pr.createdAt)}", C.MUTED, 12f); p(pr.text, C.TEXT); p("Why: ${pr.rationale}", C.MUTED, 12f)
                row {
                    smallButton("Approve") { core.proposals.approve(pr.id); build() }
                    smallButton("Edit & approve") { prompt("Edit", pr.text, multiline = true) { core.proposals.approve(pr.id, it); build() } }
                    smallButton("Reject") { core.proposals.reject(pr.id); build() }
                }
            }
        }
    }
}
