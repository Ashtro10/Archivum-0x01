package ai.clu.core.evolution

import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.util.CluJson
import ai.clu.core.util.newId
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** One test case from a JUnit XML report (what Gradle writes to `build/test-results`). */
@Serializable
data class TestCaseResult(val suite: String, val name: String, val status: String, val message: String = "", val trace: String = "", val timeS: Double = 0.0) {
    val id get() = "$suite#$name"
    val failed get() = status == FAILED || status == ERROR
    companion object { const val PASSED = "passed"; const val FAILED = "failed"; const val ERROR = "error"; const val SKIPPED = "skipped" }
}

object JUnitXml {
    /** Parses one report file. Malformed input yields no results rather than an exception (the build output still shows what happened). */
    fun parse(xml: String): List<TestCaseResult> = runCatching {
        val f = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); isExpandEntityReferences = false }
        val doc = f.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
        val cases = doc.getElementsByTagName("testcase")
        (0 until cases.length).map { i ->
            val e = cases.item(i) as org.w3c.dom.Element
            fun child(tag: String) = e.getElementsByTagName(tag).let { if (it.length > 0) it.item(0) as org.w3c.dom.Element else null }
            val failure = child("failure"); val error = child("error"); val skipped = child("skipped")
            val bad = failure ?: error
            TestCaseResult(e.getAttribute("classname"), e.getAttribute("name"),
                when { failure != null -> TestCaseResult.FAILED; error != null -> TestCaseResult.ERROR; skipped != null -> TestCaseResult.SKIPPED; else -> TestCaseResult.PASSED },
                bad?.getAttribute("message").orEmpty().take(2000), bad?.textContent.orEmpty().take(12_000), e.getAttribute("time").toDoubleOrNull() ?: 0.0)
        }
    }.getOrDefault(emptyList())
}

data class StackFrame(val cls: String, val method: String, val file: String, val line: Int) {
    /** The outer class (`a.b.C$inner` → `a.b.C`). */
    val outer get() = cls.substringBefore('$')
}

object StackTraces {
    /** `at pkg.Class.method(File.kt:12)`, also with the JDK's class-loader/module prefix (`app//`, `java.base/`, `java.base@21/`). */
    private val FRAME = Regex("""^\s*at\s+(?:[\w.@-]*/)*([\w.$]+)\.([\w$<>-]+)\(([^:()]+)(?::(\d+))?\)""", RegexOption.MULTILINE)
    fun frames(trace: String): List<StackFrame> = FRAME.findAll(trace).map { StackFrame(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4].toIntOrNull() ?: 0) }.toList()
    /** JVM, Kotlin, JUnit, Gradle and coroutine machinery: never the cause of a CLU failure. */
    fun isFramework(f: StackFrame) = listOf("java.", "jdk.", "sun.", "kotlin.", "kotlinx.", "org.junit.", "org.gradle.", "worker.org.gradle.", "org.opentest4j.", "com.sun.").any { f.cls.startsWith(it) }
    fun exceptionLine(trace: String) = trace.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty().take(500)
}

// --------------------------------------------------------------------------------------------- incidents
data class Incident(val id: String, val signature: String, val kind: String, val title: String, val detail: String, val status: String,
                    val firstSeen: Long, val lastSeen: Long, val count: Int, val commit: String)

class Incidents(private val store: EvolutionStore) {
    private val db = store.db

    /** Opens an incident or, if the same problem (same signature) was seen before, counts the recurrence and reopens it. */
    fun report(kind: String, signature: String, title: String, detail: String, commit: String): Incident {
        val now = store.clock.now()
        val existing = bySignature(signature)
        if (existing != null) {
            val reopened = existing.status == "resolved" || existing.status == "rejected"
            db.execute("UPDATE incidents SET last_seen = ?, count = count + 1, detail = ?, commit_seen = ?, status = CASE WHEN status IN ('resolved','rejected') THEN 'open' ELSE status END WHERE id = ?",
                listOf(now, detail.take(20_000), commit, existing.id))
            store.history(if (reopened) "incident-recurred" else "incident-seen-again", existing.id, "${if (reopened) "Recurred" else "Seen again"} (${existing.count + 1}×): $title")
            return bySignature(signature)!!
        }
        val i = Incident(newId(), signature, kind, title.take(300), detail.take(20_000), "open", now, now, 1, commit)
        db.execute("INSERT INTO incidents(id, signature, kind, title, detail, status, first_seen, last_seen, count, commit_seen) VALUES(?,?,?,?,?,?,?,?,?,?)",
            listOf(i.id, i.signature, i.kind, i.title, i.detail, i.status, now, now, 1, commit))
        store.history("incident-detected", i.id, "Detected ($kind): ${i.title}", detail.take(4000))
        return i
    }

    fun setStatus(id: String, status: String) = db.execute("UPDATE incidents SET status = ? WHERE id = ?", listOf(status, id))
    fun get(id: String): Incident? = db.query("$SELECT WHERE id = ? OR id LIKE ?", listOf(id, "$id%"), ::read).firstOrNull()
    fun bySignature(sig: String): Incident? = db.query("$SELECT WHERE signature = ?", listOf(sig), ::read).firstOrNull()
    fun list(status: String? = null): List<Incident> = db.query("$SELECT ${if (status != null) "WHERE status = ?" else ""} ORDER BY last_seen DESC LIMIT 200", listOfNotNull(status), ::read)

    companion object {
        private const val SELECT = "SELECT id, signature, kind, title, detail, status, first_seen, last_seen, count, commit_seen FROM incidents"
        private fun read(r: ai.clu.core.db.SqlRow) = Incident(r.str(0), r.str(1), r.str(2), r.str(3), r.str(4), r.str(5), r.lng(6), r.lng(7), r.lng(8).toInt(), r.str(9))
        fun signature(vararg parts: String): String = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

        /** A test failure's signature: the test and where it failed, not the (possibly variable) message. */
        fun testSignature(t: TestCaseResult): String {
            val first = StackTraces.frames(t.trace).firstOrNull { !StackTraces.isFramework(it) }
            return signature("test", t.id, first?.let { "${it.cls}:${it.line}" } ?: StackTraces.exceptionLine(t.trace).substringBefore(':'))
        }

        /** Repeated runtime errors in the diagnostics log (same tag and same message once numbers are removed). */
        fun repeatedErrors(events: List<ai.clu.core.diag.DiagEvent>, minCount: Int = 3): List<Triple<String, String, Int>> =
            events.filter { it.level == ai.clu.core.diag.Level.ERROR }
                .groupBy { it.tag to it.message.replace(Regex("\\d+"), "#").replace(Regex("[0-9a-f]{8}-[0-9a-f-]{27}"), "<id>").take(200) }
                .filter { it.value.size >= minCount }.map { (k, v) -> Triple(k.first, v.last().message, v.size) }
    }
}

// --------------------------------------------------------------------------------------------- investigation
@Serializable
data class Hypothesis(val id: String, val claim: String, val status: String, val origin: String, val evidenceFor: List<String>, val evidenceAgainst: List<String>,
                      val prediction: String, val files: List<String> = emptyList()) {
    companion object {
        /** Status values. Only an experiment moves a hypothesis to [CONFIRMED] or [REFUTED]. */
        const val PROPOSED = "proposed"; const val SUPPORTED = "supported by evidence"; const val CONFIRMED = "confirmed by experiment"; const val REFUTED = "refuted by experiment"
    }
}

@Serializable
data class Experiment(val name: String, val purpose: String, val command: String, val expected: String, val observed: String, val verdict: String, val at: Long)

@Serializable
data class Investigation(
    val id: String, val incidentId: String, val state: String,
    val failingTests: List<String>, val exception: String, val assertion: String,
    /** Code under suspicion, best first, with why. */
    val suspects: List<Suspect>,
    val testExcerpt: String, val hypotheses: List<Hypothesis>, val experiments: List<Experiment>,
    val reproduction: String, val conclusion: String, val priorAttempts: List<String> = emptyList(),
) {
    val confirmedCause get() = hypotheses.firstOrNull { it.status == Hypothesis.CONFIRMED }
}

@Serializable
data class Suspect(val file: String, val symbol: String, val line: Int, val reason: String, val score: Double)

/**
 * Investigates a failing test from evidence: the report, the stack trace, the test's source at the failing line, the
 * index (what that line calls and where it is implemented) and recent history (what changed, earlier attempts).
 *
 * It forms competing hypotheses and states what would discriminate them. Nothing is "confirmed" here: confirmation
 * comes only from experiments recorded later (a reproduction run, a repair that makes the failure pass while
 * nothing else changes), see [recordExperiment].
 */
class Investigator(private val store: EvolutionStore, private val indexer: SourceIndexer) {
    private val db = store.db

    fun investigate(incident: Incident, failures: List<TestCaseResult>, source: (String) -> String?, recentlyChanged: (String) -> List<String>,
                    reproduceCommand: String, lastChangedAt: (String) -> Long = { 0L }): Investigation {
        val primary = failures.first()
        val frames = StackTraces.frames(primary.trace).filterNot(StackTraces::isFramework)
        val suspects = linkedMapOf<String, Suspect>()
        fun add(s: Suspect) { val k = s.file + "#" + s.symbol; val old = suspects[k]; if (old == null || old.score < s.score) suspects[k] = s }

        // 1) Production frames on the stack: the failure happened inside them.
        var testFile: String? = null; var testLine = 0
        frames.forEachIndexed { depth, f ->
            val file = fileForClass(f.outer, f.file) ?: return@forEachIndexed
            val kind = indexer.file(file)?.kind
            if (kind == "test") { if (testFile == null) { testFile = file; testLine = f.line } }
            else add(Suspect(file, f.method, f.line, "on the failure's stack trace (${f.cls.substringAfterLast('.')}.${f.method}, depth $depth)", 5.0 - depth * 0.5))
        }
        if (testFile == null) testFile = fileForClass(primary.suite, primary.suite.substringAfterLast('.') + ".kt")

        // 2) What the failing test line calls: identifiers on that line resolved through the index.
        val testSrc = testFile?.let(source).orEmpty()
        val excerpt = if (testSrc.isNotEmpty() && testLine > 0) excerpt(testSrc, testLine, 6) else testSrc.lines().take(0).joinToString("\n")
        val failingLine = testSrc.lines().getOrNull(testLine - 1).orEmpty()
        val imports = testFile?.let { indexer.imports(it) }.orEmpty()
        Regex("\\b([A-Z]\\w+)\\.(\\w+)\\s*\\(|\\b([A-Z]\\w+)\\s*\\(|\\.(\\w+)\\s*\\(").findAll(failingLine).forEach { m ->
            val owner = m.groupValues[1].ifEmpty { m.groupValues[3] }; val member = m.groupValues[2].ifEmpty { m.groupValues[4] }
            if (owner.isNotEmpty() && owner !in ASSERT_NAMES) indexer.symbols(owner, 10).filter { it.name == owner && indexer.file(it.file)?.kind == "main" }.forEach { h ->
                val memberHit = if (member.isNotEmpty()) indexer.symbolsIn(h.file).firstOrNull { it.name == member } else null
                val imported = imports.any { it.endsWith(".$owner") || it.endsWith(".*") }
                add(Suspect(h.file, memberHit?.name ?: h.name, memberHit?.line ?: h.line,
                    "called on the failing test line (${testFile?.substringAfterLast('/')}:$testLine)${if (imported) ", imported by the test" else ""}", if (memberHit != null) 4.5 else 3.5))
            }
            if (owner.isEmpty() && member.isNotEmpty() && member !in ASSERT_NAMES) indexer.symbols(member, 10).filter { it.name == member && it.kind == "fun" && indexer.file(it.file)?.kind == "main" }.take(3).forEach { h ->
                add(Suspect(h.file, h.name, h.line, "function called on the failing test line", 2.5))
            }
        }
        val ranked = suspects.values.sortedByDescending { it.score }.take(6)

        // 3) Hypotheses, each with what would tell it apart.
        val assertion = parseAssertion(primary.message)
        val hyps = mutableListOf<Hypothesis>()
        val top = ranked.firstOrNull()
        if (top != null) {
            val changed = recentlyChanged(top.file)
            hyps += Hypothesis("H1", "A defect in ${top.symbol} (${top.file}:${top.line}) produces the wrong result the failing assertion reports.",
                Hypothesis.SUPPORTED, "deterministic", listOfNotNull(top.reason, assertion.takeIf { it.isNotEmpty() }?.let { "assertion: $it" },
                    changed.takeIf { it.isNotEmpty() }?.let { "changed recently: ${it.joinToString()}" }), emptyList(),
                "Changing only ${top.file.substringAfterLast('/')} makes ${primary.name} pass without touching the test, and no other test starts failing.", listOf(top.file))
        }
        testFile?.let { tf ->
            // Which changed last, the test or the code it points at? A test edited after the code is evidence for a wrong expectation; code edited after the test is evidence against.
            val testAt = lastChangedAt(tf); val codeAt = top?.let { lastChangedAt(it.file) } ?: 0L
            val testNewer = testAt > 0 && testAt > codeAt
            val forH2 = if (testNewer) listOf("the test changed after the suspected code (${recentlyChanged(tf).firstOrNull() ?: "recent commit"})") else emptyList()
            val againstH2 = if (!testNewer && top != null && codeAt > 0) listOf("the suspected code changed after the test (${recentlyChanged(top.file).firstOrNull() ?: "recent commit"})") else emptyList()
            hyps += Hypothesis("H2", "The test's expectation is wrong (the test, not the code, changed).", if (testNewer) Hypothesis.SUPPORTED else Hypothesis.PROPOSED,
                "deterministic", forH2, againstH2, "The production code is unchanged since the test last passed; the test's expected value differs from documented behaviour.", listOf(tf))
        }
        hyps += Hypothesis("H3", "The failure is environmental or flaky (timing, memory, ordering), not a code defect.", Hypothesis.PROPOSED, "deterministic",
            emptyList(), emptyList(), "Re-running the same test on the same commit passes.")
        for ((n, other) in ranked.drop(1).take(2).withIndex()) hyps += Hypothesis("H${4 + n}", "The defect is in ${other.symbol} (${other.file}:${other.line}) instead.",
            Hypothesis.PROPOSED, "deterministic", listOf(other.reason), emptyList(), "A change confined to ${other.file.substringAfterLast('/')} fixes the failure.", listOf(other.file))

        val inv = Investigation(newId(), incident.id, "evidence-collected", failures.map { it.id }, StackTraces.exceptionLine(primary.trace), assertion, ranked,
            excerpt, hyps, emptyList(), reproduceCommand,
            "Not yet confirmed: ${hyps.count { it.status == Hypothesis.SUPPORTED }} hypothesis(es) supported by evidence; experiments decide.",
            priorAttempts = priorAttempts(incident.id))
        save(inv)
        store.history("investigation", inv.id, "Investigated ${primary.name}: top suspect ${top?.let { "${it.symbol} in ${it.file}" } ?: "none found"}",
            CluJson.encodeToString(Investigation.serializer(), inv).take(20_000))
        return inv
    }

    /** Adds a hypothesis the model proposed. It is recorded as unverified model output and never starts as supported. */
    fun addModelHypothesis(inv: Investigation, claim: String): Investigation {
        if (claim.isBlank()) return inv
        val h = Hypothesis("M${inv.hypotheses.count { it.origin == "model" } + 1}", claim.trim().take(800), Hypothesis.PROPOSED, "model", emptyList(), emptyList(),
            "Unverified: proposed by the language model.")
        return inv.copy(hypotheses = inv.hypotheses + h).also(::save)
    }

    /** Records an experiment and applies its verdict to the hypotheses it discriminates. */
    fun recordExperiment(inv: Investigation, e: Experiment, confirms: List<String> = emptyList(), refutes: List<String> = emptyList(), state: String? = null, conclusion: String? = null): Investigation {
        val hyps = inv.hypotheses.map { h -> when (h.id) {
            in confirms -> h.copy(status = Hypothesis.CONFIRMED, evidenceFor = h.evidenceFor + "experiment '${e.name}': ${e.observed}")
            in refutes -> h.copy(status = Hypothesis.REFUTED, evidenceAgainst = h.evidenceAgainst + "experiment '${e.name}': ${e.observed}")
            else -> h } }
        val next = inv.copy(hypotheses = hyps, experiments = inv.experiments + e, state = state ?: inv.state, conclusion = conclusion ?: inv.conclusion)
        save(next)
        store.history("experiment", inv.id, "${e.name}: ${e.verdict}", "${e.purpose}\ncommand: ${e.command}\nexpected: ${e.expected}\nobserved: ${e.observed}")
        return next
    }

    fun get(id: String): Investigation? = db.query("SELECT json FROM investigations WHERE id = ? OR id LIKE ?", listOf(id, "$id%")) { it.str(0) }.firstOrNull()
        ?.let { CluJson.decodeFromString(Investigation.serializer(), it) }
    fun latestFor(incidentId: String): Investigation? = db.query("SELECT json FROM investigations WHERE incident_id = ? ORDER BY updated_at DESC LIMIT 1", listOf(incidentId)) { it.str(0) }
        .firstOrNull()?.let { CluJson.decodeFromString(Investigation.serializer(), it) }

    private fun save(inv: Investigation) {
        val now = store.clock.now(); val json = CluJson.encodeToString(Investigation.serializer(), inv)
        db.execute("INSERT INTO investigations(id, incident_id, state, json, created_at, updated_at) VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET state = excluded.state, json = excluded.json, updated_at = excluded.updated_at",
            listOf(inv.id, inv.incidentId, inv.state, json, now, now))
    }

    /** Earlier repair attempts for the same incident that did not succeed, so they are not repeated. */
    private fun priorAttempts(incidentId: String): List<String> =
        db.query("SELECT id, state, source, patch_hash, json FROM repairs WHERE incident_id = ? AND state IN ('failed-verification','rejected','patch-failed') ORDER BY created_at", listOf(incidentId)) {
            val why = runCatching { CluJson.parseToJsonElement(it.str(4)).let { e -> (e as kotlinx.serialization.json.JsonObject)["outcome"]?.toString() } }.getOrNull().orEmpty()
            "${it.str(0).take(8)} (${it.str(2)}, ${it.str(1)}, patch ${it.str(3).take(10)}) $why".take(400) }

    private fun fileForClass(fqClass: String, fileName: String): String? {
        val simple = fqClass.substringAfterLast('.'); val pkg = fqClass.substringBeforeLast('.', "")
        // A declaration with that fully qualified name, else a file of that name in that package (Kotlin file facades: FooKt).
        indexer.symbols(simple, 20).firstOrNull { it.fqname == fqClass }?.let { return it.file }
        return indexer.files().firstOrNull { it.pkg == pkg && it.path.endsWith("/$fileName") }?.path
            ?: indexer.files().firstOrNull { it.pkg == pkg && it.path.substringAfterLast('/').substringBefore('.') == simple.removeSuffix("Kt") }?.path
    }

    companion object {
        private val ASSERT_NAMES = setOf("assertEquals", "assertTrue", "assertFalse", "assertNull", "assertNotNull", "assertContains", "assertFails", "assertIs", "fail", "require", "check",
            "listOf", "mapOf", "setOf", "println", "Regex", "String", "Test")
        fun excerpt(src: String, line: Int, radius: Int): String = src.lines().let { l ->
            (maxOf(1, line - radius)..minOf(l.size, line + radius)).joinToString("\n") { n -> "${if (n == line) ">" else " "}${n.toString().padStart(4)}  ${l[n - 1]}" } }
        /** "expected: <a> but was: <b>" → a readable comparison. */
        fun parseAssertion(msg: String): String {
            val m = Regex("(?s)(.*?)expected:\\s*<(.*)>\\s*but was:\\s*<(.*)>").find(msg) ?: return msg.take(600)
            return "${m.groupValues[1].trim().trimEnd('.', ':').let { if (it.isEmpty()) "" else "$it — " }}expected <${m.groupValues[2].take(300)}> but was <${m.groupValues[3].take(300)}>"
        }
    }
}
