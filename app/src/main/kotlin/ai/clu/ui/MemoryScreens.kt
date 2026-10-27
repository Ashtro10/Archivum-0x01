package ai.clu.ui

import ai.clu.core.memory.*
import ai.clu.core.util.CluJson
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.*
import kotlinx.serialization.builtins.ListSerializer
import java.util.Calendar

/** Memory browser: search, filter by category/scope, insert, export/import. Separate from conversation history. */
class MemoryActivity : BaseActivity() {
    private var category: MemoryCategory? = null
    private var query = ""
    private var memScope = CLU_SCOPE

    override fun onResume() { super.onResume(); build() }

    private fun build() {
        screen("Memory") {
            p("Long-term memory is separate from conversation history. ${if (!core.settings.bool(ai.clu.core.config.Settings.MEMORY_ENABLED)) "⚠ Persistent memory is OFF – nothing new is saved." else ""}")
            row {
                smallButton("+ Insert memory") { startActivity(Intent(this@MemoryActivity, MemoryEditActivity::class.java).putExtra("scope", memScope)) }
                smallButton("Diary (${core.diary.count()})") { startActivity(Intent(this@MemoryActivity, DiaryActivity::class.java)) }
                smallButton("Suggestions (${core.proposals.pending().size})") { startActivity(Intent(this@MemoryActivity, ProposalsActivity::class.java)) }
            }
            val scopes = listOf(CLU_SCOPE to "Clu") + core.characters.list().map { it.id to "${it.name} (character)" }
            spinner(scopes.map { it.second }, scopes.indexOfFirst { it.first == memScope }) { memScope = scopes[it].first; build() }
            val cats = listOf<MemoryCategory?>(null) + MemoryCategory.selectable
            spinner(cats.map { it?.label ?: "All categories" }, cats.indexOf(category)) { category = cats[it]; build() }
            val search = field("Search", query)
            smallButton("Search") { query = search.text.toString(); build() }
            val items = if (query.isNotBlank()) core.memories.search(query, memScope).filter { category == null || it.category == category }
                else core.memories.list(memScope, category)
            p("${items.size} memories")
            items.forEach { m ->
                card {
                    p((if (m.pinned) "📌 " else "") + m.title, C.TEXT, 15f)
                    p("${m.category.label} · ${m.source.label}" + (m.eventAt?.let { " · " + fmtTime(it) } ?: "") +
                        (if (m.people.isNotEmpty()) " · ${m.people.joinToString()}" else "") + (m.expiresAt?.let { " · expires ${fmtTime(it)}" } ?: ""), C.MUTED, 12f)
                    p(m.bodyForPrompt().take(240), C.TEXT, 13f)
                    row {
                        smallButton("Open") { startActivity(Intent(this@MemoryActivity, MemoryEditActivity::class.java).putExtra("id", m.id)) }
                        smallButton(if (m.pinned) "Unpin" else "Pin") { core.memories.setPinned(m.id, !m.pinned); build() }
                        smallButton("Delete") { confirm("Delete memory?", m.title) { core.memories.delete(m.id); build() } }
                    }
                }
            }
            h1("Backup")
            row {
                smallButton("Export (JSON)") { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, "clu-memories.json"), 1) }
                smallButton("Import (JSON)") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 2) }
            }
        }
    }

    @Deprecated("framework API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        val ser = ListSerializer(Memory.serializer())
        when (requestCode) {
            1 -> verifyOwner("Export private memories") { ok ->
                if (ok) contentResolver.openOutputStream(uri)!!.use { it.write(CluJson.encodeToString(ser, core.memories.list(null, limit = 100_000)).toByteArray()) }.also { toast("Exported.") }
            }
            2 -> runCatching {
                val list = CluJson.decodeFromString(ser, contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) })
                var n = 0
                list.filter { !it.deleted }.forEach { m -> if (core.memories.get(m.id) == null) { core.memories.save(m); n++ } }
                toast("Imported $n memories (existing ones kept)."); build()
            }.onFailure { toast("Import failed: ${it.message}") }
        }
    }
}

/** Manual memory inserter / editor. Keeps the original text separate from any summary. */
class MemoryEditActivity : BaseActivity() {
    private var existing: Memory? = null
    private var eventAt: Long? = null
    private var hasTime = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        existing = intent.getStringExtra("id")?.let { core.memories.get(it) }
        eventAt = existing?.eventAt; hasTime = existing?.eventHasTime ?: false
        build()
    }

    private lateinit var textField: EditText

    private fun build() {
        val m = existing
        screen(if (m == null) "Insert memory" else "Edit memory") {
            m?.let { p("Source: ${it.source.label} · created ${fmtTime(it.createdAt)} · updated ${fmtTime(it.updatedAt)}", C.MUTED, 12f) }
            val title = field("Title", m?.title ?: "")
            val cats = (MemoryCategory.selectable + listOfNotNull(m?.category)).distinct()
            p("Category", C.TEXT, 13f)
            val cat = spinner(cats.map { it.label }, cats.indexOf(m?.category ?: MemoryCategory.MANUAL))
            val dateLabel = p(eventAt?.let { "Event date: " + fmtTime(it) + if (!hasTime) " (date only)" else "" } ?: "Event date: not set", C.TEXT, 13f)
            row {
                smallButton("Set date") {
                    val c = Calendar.getInstance().apply { eventAt?.let { timeInMillis = it } }
                    DatePickerDialog(this@MemoryEditActivity, { _, y, mo, d -> c.set(y, mo, d, 12, 0, 0); eventAt = c.timeInMillis; hasTime = false
                        dateLabel.text = "Event date: " + fmtTime(eventAt!!) + " (date only)" }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                }
                smallButton("Set time") {
                    val c = Calendar.getInstance().apply { eventAt?.let { timeInMillis = it } }
                    TimePickerDialog(this@MemoryEditActivity, { _, h, mi -> c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, mi); eventAt = c.timeInMillis; hasTime = true
                        dateLabel.text = "Event date: " + fmtTime(eventAt!!) }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
                }
                smallButton("Clear") { eventAt = null; dateLabel.text = "Event date: not set" }
            }
            val people = field("People (comma separated)", m?.people?.joinToString(", ") ?: "")
            val tags = field("Tags (comma separated)", m?.tags?.joinToString(", ") ?: "")
            textField = field("Original text (paste an old conversation, describe the event…)", m?.content ?: "", multiline = true)
            smallButton("Import text file") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*"), 1) }
            val summary = field("Summary (optional, your words)", m?.summary ?: "", multiline = true)
            val modes = listOf(RetainMode.FULL to "Keep full original text", RetainMode.BOTH to "Keep text and summary", RetainMode.SUMMARY to "Keep summary only (discard original)")
            p("Retention", C.TEXT, 13f)
            val mode = spinner(modes.map { it.second }, modes.indexOfFirst { it.first == (m?.retainMode ?: RetainMode.FULL) })
            val pinned = check("Pinned (always considered first)", m?.pinned ?: false)
            p("Clu treats inserted memories as told by you, not as events it witnessed.", C.MUTED, 12f)
            button("Save", primary = true) {
                val retain = modes[mode.selectedItemPosition].first
                if (retain == RetainMode.SUMMARY && m?.retainMode != RetainMode.SUMMARY) {
                    confirm("Discard original text?", "Only the summary will be kept.") { save(title, cat, people, tags, summary, retain, pinned, cats) }
                } else save(title, cat, people, tags, summary, retain, pinned, cats)
            }
            if (m != null) button("Delete") { confirm("Delete this memory?", m.title) { core.memories.delete(m.id); finish() } }
        }
    }

    private fun save(title: EditText, cat: Spinner, people: EditText, tags: EditText, summary: EditText, retain: RetainMode, pinned: Switch, cats: List<MemoryCategory>) {
        val base = existing
        val mem = Memory(id = base?.id ?: ai.clu.core.util.newId(), category = cats[cat.selectedItemPosition], title = title.text.toString().trim(),
            content = textField.text.toString(), summary = summary.text.toString().trim().ifEmpty { null }, retainMode = retain, eventAt = eventAt, eventHasTime = hasTime,
            source = base?.source ?: MemorySource.OWNER_SUPPLIED, people = people.text.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            tags = tags.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }, pinned = pinned.isChecked,
            scope = base?.scope ?: intent.getStringExtra("scope") ?: CLU_SCOPE, createdAt = base?.createdAt ?: 0)
        runCatching { core.memories.save(mem) }.onSuccess { toast("Saved."); finish() }.onFailure { toast(it.message ?: "Could not save") }
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
