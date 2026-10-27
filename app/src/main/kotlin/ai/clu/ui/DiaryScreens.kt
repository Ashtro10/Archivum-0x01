package ai.clu.ui

import ai.clu.core.diary.DiaryEntry
import ai.clu.core.diary.DiarySource
import ai.clu.core.memory.MemoryCategory
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Diary: browse by month, search, open. Entries stay on this device and are not memories unless you keep one as a memory. */
class DiaryActivity : BaseActivity() {
    private var month: String? = null
    private var query = ""

    override fun onResume() { super.onResume(); build() }

    private fun build() {
        screen("Diary") {
            p("Your diary stays on this device. Clu doesn't read it in conversation unless you ask; \"Keep as memory\" turns an entry into a memory.")
            row {
                smallButton("+ New entry") { startActivity(Intent(this@DiaryActivity, DiaryEditActivity::class.java)) }
                smallButton("Memory") { startActivity(Intent(this@DiaryActivity, MemoryActivity::class.java)) }
            }
            val months = core.diary.months()
            val opts = listOf<String?>(null) + months.map { it.first }
            spinner(listOf("All dates (${core.diary.count()})") + months.map { "${monthLabel(it.first)} (${it.second})" }, opts.indexOf(month).coerceAtLeast(0)) {
                month = opts[it]; build()
            }
            val search = field("Search the diary", query)
            row {
                smallButton("Search") { query = search.text.toString().trim(); build() }
                if (query.isNotEmpty()) smallButton("Clear") { query = ""; build() }
            }
            val items = if (query.isNotEmpty()) core.diary.search(query).filter { month == null || it.date.startsWith(month!!) } else core.diary.list(month = month)
            p(if (query.isNotEmpty()) "${items.size} entries match \"$query\"" else "${items.size} entries")
            var day: String? = null
            items.forEach { e ->
                if (e.date != day) { day = e.date; h1(dayLabel(e.date)) }
                card {
                    p(e.title, C.TEXT, 15f)
                    p(e.source.label + (if (e.memoryId != null) " · kept as memory" else "") + " · edited ${fmtTime(e.updatedAt)}", C.MUTED, 12f)
                    p(e.body.take(300), C.TEXT, 13f)
                    row {
                        smallButton("Open") { startActivity(Intent(this@DiaryActivity, DiaryEditActivity::class.java).putExtra("id", e.id)) }
                        if (e.memoryId == null) smallButton("Keep as memory") { keep(e) }
                        smallButton("Delete") { confirm("Delete diary entry?", e.title) { core.diary.delete(e.id); build() } }
                    }
                }
            }
        }
    }

    private fun keep(e: DiaryEntry) = keepAsMemory(e) { build() }

    companion object {
        fun monthLabel(m: String) = runCatching { SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(SimpleDateFormat("yyyy-MM", Locale.US).parse(m)!!) }.getOrDefault(m)
        fun dayLabel(d: String) = runCatching { SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d)!!) }.getOrDefault(d)
    }
}

/**
 * Ask which kind of memory a diary entry becomes, show the proposed memory text for editing, and save only when the
 * owner confirms (the entry itself stays in the diary).
 */
fun BaseActivity.keepAsMemory(e: DiaryEntry, done: () -> Unit) {
    val cats = MemoryCategory.selectable
    choose("Keep as memory — what kind?", cats.map { it.label }) { i ->
        prompt("Save this memory? (${cats[i].label})", core.diary.proposedMemory(e.id), multiline = true) { text ->
            runCatching { core.diary.promote(e.id, cats[i], text) }.onSuccess { toast("Saved to memory as ${cats[i].label.lowercase()}"); done() }
                .onFailure { toast(it.message ?: "Couldn't save") }
        }
    }
}

/** Write or edit one diary entry. */
class DiaryEditActivity : BaseActivity() {
    private var existing: DiaryEntry? = null
    private var date = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        existing = intent.getStringExtra("id")?.let { core.diary.get(it) }
        date = existing?.date ?: core.diary.today()
        build()
    }

    private fun build() {
        val e = existing
        screen(if (e == null) "New diary entry" else "Diary entry") {
            e?.let { p("${it.source.label} · created ${fmtTime(it.createdAt)} · edited ${fmtTime(it.updatedAt)}" +
                if (it.memoryId != null) " · kept as memory" else "", C.MUTED, 12f) }
            val dateLabel = p("Date: " + DiaryActivity.dayLabel(date), C.TEXT, 14f)
            smallButton("Change date") {
                val c = Calendar.getInstance().apply { runCatching { time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!! } }
                DatePickerDialog(this@DiaryEditActivity, { _, y, mo, d -> date = "%04d-%02d-%02d".format(y, mo + 1, d); dateLabel.text = "Date: " + DiaryActivity.dayLabel(date) },
                    c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
            }
            val title = field("Title (optional)", e?.title ?: "")
            val body = field("What happened?", e?.body ?: "", multiline = true)
            button("Save", primary = true) { save(title, body) }
            if (e != null) {
                if (e.memoryId == null) button("Keep as memory") { keepAsMemory(e) { existing = core.diary.get(e.id); build() } }
                button("Delete") { confirm("Delete diary entry?", e.title) { core.diary.delete(e.id); finish() } }
            }
        }
    }

    private fun save(title: EditText, body: EditText) {
        val text = body.text.toString().trim()
        val t = title.text.toString().trim()
        val base = existing
        runCatching {
            if (base == null) core.diary.add(text, t.ifEmpty { null }, DiarySource.MANUAL, date = date)
            else core.diary.save(base.copy(date = date, title = t.ifEmpty { ai.clu.core.diary.DiaryRepository.titleFor(text) }, body = text))
        }.onSuccess { toast("Saved."); finish() }.onFailure { toast(it.message ?: "Could not save") }
    }
}
