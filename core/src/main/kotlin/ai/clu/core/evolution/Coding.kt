package ai.clu.core.evolution

import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.util.CluJson
import ai.clu.core.util.newId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// =============================================================================================== structured patches
/**
 * One change to one file, by line range in the version the model was shown. `replace` swaps lines [start]..[end]
 * (inclusive) for [code], `insert` puts [code] after line [start] ([end] ignored), `delete` removes the range.
 */
@Serializable
data class LineEdit(val file: String, val op: String = "replace", val start: Int, val end: Int = start, val code: String = "")

@Serializable
data class StructuredPatch(val cause: String = "", val edits: List<LineEdit>)

/**
 * The patch format of CLU's development mode. The model answers with JSON constrained by [GRAMMAR] (llama.cpp GBNF), so
 * it cannot produce prose, Markdown or a half-formed block. Every edit names a file and a line range inside a window it
 * was shown, and [validate] refuses anything else before a byte is written.
 */
object StructuredPatches {
    /** JSON: {"cause": "...", "edits": [{"file": "...", "op": "replace|insert|delete", "start": N, "end": N, "code": "..."}]} (1–4 edits). */
    val GRAMMAR = """
root   ::= "{" ws "\"cause\"" ws ":" ws string ws "," ws "\"edits\"" ws ":" ws "[" ws edit (ws "," ws edit)? (ws "," ws edit)? (ws "," ws edit)? ws "]" ws "}"
edit   ::= "{" ws "\"file\"" ws ":" ws string ws "," ws "\"op\"" ws ":" ws op ws "," ws "\"start\"" ws ":" ws int ws "," ws "\"end\"" ws ":" ws int ws "," ws "\"code\"" ws ":" ws string ws "}"
op     ::= "\"replace\"" | "\"insert\"" | "\"delete\""
int    ::= [1-9] [0-9]{0,5}
string ::= "\"" ( [^"\\\x00-\x1f] | "\\" ( ["\\/bfnrt] | "u" [0-9a-fA-F]{4} ) ){0,2400} "\""
ws     ::= [ \t\n]{0,4}
""".trimIndent()

    /** Stage 1 of the line strategy: which line is wrong. */
    val LOCATE_GRAMMAR = """
root   ::= "{" ws "\"file\"" ws ":" ws string ws "," ws "\"line\"" ws ":" ws int ws "," ws "\"why\"" ws ":" ws string ws "}"
int    ::= [1-9] [0-9]{0,5}
string ::= "\"" ( [^"\\\x00-\x1f] | "\\" ( ["\\/bfnrt] | "u" [0-9a-fA-F]{4} ) ){0,600} "\""
ws     ::= [ \t\n]{0,4}
""".trimIndent()

    /** A new member as raw text: 1–12 lines of code starting with a declaration keyword. */
    val MEMBER_GRAMMAR = """
root ::= [ \t]{0,8} ("fun " | "val " | "private fun " | "internal fun ") [^\n]+ rest
rest ::= ("\n" [^\n]*)*
""".trimIndent()

    /** Stage 2 as raw text: exactly one line of code, no JSON (so quotes, backslashes and regexes need no escaping). */
    val RAW_LINE_GRAMMAR = """root ::= [ \t]{0,40} [^ \t\n] [^\n]{0,400}"""

    /**
     * The whole-patch format without JSON: blocks of
     * `FILE: path` / `LINES: a-b` / `| code` lines / `END`. Code lines carry a "| " prefix that is stripped, so code is
     * written exactly as it is, with no escaping, and the block boundaries stay unambiguous.
     */
    val BLOCK_GRAMMAR = """
root  ::= block block? block?
block ::= "FILE: " path "\nLINES: " int "-" int "\n" line line? line? line? line? line? line? line? line? line? line? line? "END\n"
line  ::= "| " [^\n]{0,300} "\n"
path  ::= [a-zA-Z0-9_./-]{1,200}
int   ::= [1-9] [0-9]{0,5}
""".trimIndent()

    private val BLOCK = Regex("""FILE:\s*(\S+)\s*\nLINES:\s*(\d+)\s*-\s*(\d+)\s*\n((?:\|[^\n]*\n)*)END""")
    /** Parses the block format; null if there is no complete block. */
    fun parseBlocks(text: String): StructuredPatch? {
        val edits = BLOCK.findAll(text.replace("\r\n", "\n")).map { m ->
            val code = m.groupValues[4].lines().filter { it.startsWith("|") }.joinToString("\n") { it.removePrefix("|").removePrefix(" ") }
            LineEdit(m.groupValues[1].removePrefix("./"), if (code.isEmpty()) "delete" else "replace", m.groupValues[2].toInt(), m.groupValues[3].toInt(), code) }.toList()
        return if (edits.isEmpty()) null else StructuredPatch("", edits)
    }

    /** A locate grammar that only admits the given (file, line) candidates. */
    fun locateGrammar(candidates: List<Pair<String, Int>>): String {
        val byFile = candidates.groupBy({ it.first }, { it.second })
        val alts = byFile.entries.joinToString(" | ") { (f, ls) -> "\"\\\"${f.replace("\"", "")}\\\"\" ws \",\" ws \"\\\"line\\\"\" ws \":\" ws (" + ls.distinct().joinToString(" | ") { "\"$it\"" } + ")" }
        return LOCATE_GRAMMAR.lines().first().replace("string ws \",\" ws \"\\\"line\\\"\" ws \":\" ws int", "target").let { root ->
            root + "\ntarget ::= " + alts + "\n" + LOCATE_GRAMMAR.lines().drop(1).joinToString("\n") }
    }

    /** Code lines worth changing in a window: not blank, not only brackets, not comments, package or import lines. */
    fun candidateLines(w: CodeWindow, src: String): List<Int> {
        val lines = src.split('\n')
        return (w.from..w.to).filter { n -> val t = lines.getOrElse(n - 1) { "" }.trim()
            t.isNotEmpty() && !Regex("^[})\\]]+[;,]?$").matches(t) && !Regex("^(//|/\\*|\\*|@|package |import )").containsMatchIn(t) &&
                !Regex("^(?:(?:private|internal|public|protected|data|sealed|abstract|open|enum|inline|value|override|suspend)\\s+)*(class|object|interface)\\b").containsMatchIn(t) &&
                !(Regex("^(?:(?:private|internal|public|protected|override|suspend|inline)\\s+)*fun\\b").containsMatchIn(t) && t.endsWith("{")) }
    }

    /** Stage 2 of the line strategy: the corrected line. */
    val REWRITE_GRAMMAR = """
root   ::= "{" ws "\"code\"" ws ":" ws string ws "}"
string ::= "\"" ( [^"\\\x00-\x1f] | "\\" ( ["\\/bfnrt] | "u" [0-9a-fA-F]{4} ) ){0,600} "\""
ws     ::= [ \t\n]{0,4}
""".trimIndent()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parses a model answer (the JSON object, possibly with text around it). Null if there is no valid patch object. */
    fun parse(text: String): StructuredPatch? {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.decodeFromString(StructuredPatch.serializer(), text.substring(start, end + 1)) }.getOrNull()
            ?.takeIf { it.edits.isNotEmpty() }
    }

    /** A window of a file the model may change: lines [from]..[to] (1-based, inclusive). */
    data class Window(val file: String, val from: Int, val to: Int)

    sealed class Checked {
        data class Ok(val files: Map<String, String>) : Checked()
        data class Rejected(val reason: String) : Checked()
    }

    /**
     * Validates a patch against the current sources and the windows the model was shown, then applies it (in memory).
     * Rejected: unknown files, edits outside every shown window, overlapping edits, edits that change nothing,
     * test files when [allowTests] is false, and new files unless [newFiles] allows the path.
     */
    fun check(patch: StructuredPatch, sources: (String) -> String?, windows: List<Window>, allowTests: Boolean, newFiles: Set<String> = emptySet()): Checked {
        val byFile = patch.edits.groupBy { it.file.trim().removePrefix("./") }
        val out = linkedMapOf<String, String>()
        for ((file, edits) in byFile) {
            if (file.isBlank() || file.contains("..") || file.startsWith("/")) return Checked.Rejected("invalid file path \"$file\"")
            if (!allowTests && "/src/test/" in file) return Checked.Rejected("edits a test file ($file); a repair must change the code, not the test")
            val original = sources(file)
            if (original == null) {
                if (file !in newFiles) return Checked.Rejected("$file does not exist")
                val creates = edits.singleOrNull()?.takeIf { it.op == "insert" && it.start <= 1 }
                    ?: return Checked.Rejected("a new file must be created with one insert at line 1 ($file)")
                out[file] = creates.code.trimEnd('\n') + "\n"; continue
            }
            val lines = original.split('\n').toMutableList()
            val trailingNewline = original.endsWith("\n"); if (trailingNewline) lines.removeAt(lines.size - 1)
            val fileWindows = windows.filter { it.file == file }
            if (fileWindows.isEmpty()) return Checked.Rejected("$file was not among the code shown for this task")
            val sorted = edits.map { tighten(normalize(it), lines, fileWindows) }.sortedBy { it.start }
            for ((i, e) in sorted.withIndex()) {
                if (e.op !in setOf("replace", "insert", "delete")) return Checked.Rejected("unknown operation \"${e.op}\"")
                if (e.start < 1 || e.end < e.start || e.end > lines.size + (if (e.op == "insert") 1 else 0)) return Checked.Rejected("lines ${e.start}-${e.end} are outside $file (${lines.size} lines)")
                val inWindow = fileWindows.any { w -> e.start >= w.from && (if (e.op == "insert") e.start <= w.to else e.end <= w.to) }
                if (!inWindow) return Checked.Rejected("lines ${e.start}-${e.end} of $file are outside the code that was shown (${fileWindows.joinToString { "${it.from}-${it.to}" }})")
                if (i > 0 && e.start <= sorted[i - 1].end && !(e.op == "insert" && sorted[i - 1].op == "insert" && e.start != sorted[i - 1].start)) return Checked.Rejected("edits to $file overlap")
                if (e.op == "replace" && lines.subList(e.start - 1, e.end).joinToString("\n") == e.code.trimEnd('\n')) return Checked.Rejected("an edit to $file lines ${e.start}-${e.end} changes nothing")
                if (e.op != "delete" && e.code.isBlank()) return Checked.Rejected("an edit to $file has no code; use delete to remove lines")
            }
            // Apply from the bottom up so earlier line numbers stay valid.
            for (e in sorted.reversed()) {
                val code = e.code.trimEnd('\n').split('\n')
                when (e.op) {
                    "replace" -> { repeat(e.end - e.start + 1) { lines.removeAt(e.start - 1) }; lines.addAll(e.start - 1, reindent(code, lines.getOrNull(e.start - 2), original.split('\n')[e.start - 1])) }
                    "insert" -> lines.addAll(e.start, reindent(code, lines.getOrNull(e.start - 1), lines.getOrNull(e.start - 1) ?: ""))
                    "delete" -> repeat(e.end - e.start + 1) { lines.removeAt(e.start - 1) }
                }
            }
            out[file] = lines.joinToString("\n") + if (trailingNewline) "\n" else ""
        }
        return Checked.Ok(out)
    }

    /**
     * Small models name the right line but often a range one or two lines too wide. A replacement shorter than its range
     * keeps the range's trailing blank lines instead of deleting them, and a range that runs past the file or the shown
     * window is cut to the replacement's own length. Nothing else is guessed: other mismatches are rejected or fail the build.
     */
    private fun tighten(e: LineEdit, lines: List<String>, windows: List<Window>): LineEdit {
        if (e.op != "replace") return e
        val n = e.code.trimEnd('\n').split('\n').size
        var end = e.end
        val windowEnd = windows.filter { e.start >= it.from && e.start <= it.to }.maxOfOrNull { it.to } ?: lines.size
        if (end > minOf(lines.size, windowEnd) && e.start + n - 1 <= minOf(lines.size, windowEnd)) end = e.start + n - 1
        val closers = Regex("^\\s*[})\\]]+[;,]?\\s*$")
        val replacementCloses = e.code.lines().count { closers.matches(it) }
        var keptClosers = 0
        while (end - e.start + 1 > n && end >= 1 && end <= lines.size &&
            (lines[end - 1].isBlank() || (closers.matches(lines[end - 1]) && keptClosers++ >= 0 && replacementCloses == 0))) end--
        return if (end >= e.start) e.copy(end = end) else e
    }

    private val NUMBERED = Regex("(?m)^\\s*>{0,2}\\s*\\d{1,5}\\|\\s?")
    /** Lowercased op, a sane end, Windows line ends removed, and line-number prefixes the model copied from the prompt ("  42| ") stripped. */
    private fun normalize(e: LineEdit) = e.copy(op = e.op.lowercase().trim(), end = if (e.op == "insert") e.start else maxOf(e.end, e.start),
        code = e.code.replace("\r\n", "\n").let { c -> if (c.lines().all { it.isBlank() || NUMBERED.containsMatchIn(it) }) NUMBERED.replace(c, "") else c })

    /** Small models often drop indentation: if the replacement starts unindented while the original line was indented, indent it the same way. */
    private fun reindent(code: List<String>, @Suppress("UNUSED_PARAMETER") above: String?, originalFirst: String): List<String> {
        val want = originalFirst.takeWhile { it == ' ' || it == '\t' }
        val have = code.firstOrNull { it.isNotBlank() }?.takeWhile { it == ' ' || it == '\t' } ?: return code
        if (want.isEmpty() || have.isNotEmpty()) return code
        return code.map { if (it.isBlank()) it else want + it }
    }

    /** A stable fingerprint of what a patch changes (whitespace-insensitive), to recognise "the same patch again". */
    fun fingerprint(files: Map<String, String>): String = files.entries.sortedBy { it.key }.joinToString("\u0000") { (k, v) -> k + "\u0001" + v.replace(Regex("\\s+"), " ").trim() }
        .let { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
}

// =============================================================================================== code context
/** What the model sees of one file: a numbered window around the code of interest, and what that code is. */
@Serializable
data class CodeWindow(val file: String, val from: Int, val to: Int, val purpose: String, val numbered: String)

object CodeContext {
    /**
     * The enclosing declaration (function, or class when it is small) around [line], by brace matching on the code with
     * strings and comments blanked, widened by [pad] lines, capped at [maxLines]. Lines are numbered so a patch can
     * name exact ranges.
     */
    fun window(file: String, src: String, line: Int, purpose: String, pad: Int = 2, maxLines: Int = 60): CodeWindow {
        val lines = src.split('\n').let { if (src.endsWith("\n")) it.dropLast(1) else it }
        val clean = KotlinScanner.blank(src).split('\n')
        val target = line.coerceIn(1, maxOf(1, lines.size))
        // Start: the nearest declaration at or above the line.
        var start = target
        // The enclosing declaration: a fun/class/object/interface, or a member-level (not local) val/var.
        val decl = Regex("^\\s*(?:[\\w@]+\\s+)*(fun|class|object|interface)\\b")
        val member = Regex("^ {0,4}(?:[\\w@]+\\s+)*(val|var)\\b")
        while (start > 1 && !decl.containsMatchIn(clean[start - 1]) && !member.containsMatchIn(clean[start - 1])) start--
        // End: where the braces opened from the declaration close again (or the expression body's line).
        var depth = 0; var end = start; var opened = false
        for (i in start - 1 until lines.size) {
            for (ch in clean[i]) { if (ch == '{') { depth++; opened = true } else if (ch == '}') depth-- }
            end = i + 1
            if (opened && depth <= 0) break
            if (!opened && i + 1 >= target && !clean[i].trimEnd().endsWith("=") && !clean[i].trimEnd().endsWith(",") && !clean.getOrElse(i + 1) { "" }.trimStart().startsWith(".")) break
        }
        var from = maxOf(1, start - pad); var to = minOf(lines.size, maxOf(end, target) + pad)
        if (to - from + 1 > maxLines) { from = maxOf(1, target - maxLines / 2); to = minOf(lines.size, from + maxLines - 1) }
        return CodeWindow(file, from, to, purpose, number(lines, from, to) + definitions(lines, clean, from, to))
    }

    /**
     * Declarations outside the window that the window's code uses (constants and properties of the same file, e.g.
     * `const val MAX_STEPS = 5`): a developer looks them up before changing the code, so the model gets them too, read-only.
     */
    fun definitions(lines: List<String>, clean: List<String>, from: Int, to: Int, max: Int = 6): String {
        val used = (from..to).flatMap { n -> Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b").findAll(clean.getOrElse(n - 1) { "" }).map { it.value } }.toSet()
        val decl = Regex("^\\s*(?:(?:private|internal|public|protected|const|override|lateinit)\\s+)*va[lr]\\s+([A-Za-z_][A-Za-z0-9_]*)")
        val found = lines.indices.filter { i -> i + 1 !in from..to }.mapNotNull { i -> decl.find(clean[i])?.groupValues?.get(1)?.takeIf { it in used }?.let { i + 1 } }
            .filter { n -> lines[n - 1].length < 200 }.take(max)
        if (found.isEmpty()) return ""
        return "\n  (defined elsewhere in this file, read-only:)\n" + found.joinToString("\n") { n -> "${n.toString().padStart(4)}| ${lines[n - 1]}" }
    }

    /** A whole small file (or its first [maxLines]) as a window, for adding code to it. */
    fun whole(file: String, src: String, purpose: String, maxLines: Int = 120): CodeWindow {
        val lines = src.split('\n').let { if (src.endsWith("\n")) it.dropLast(1) else it }
        val to = minOf(lines.size, maxLines)
        return CodeWindow(file, 1, maxOf(1, to), purpose, number(lines, 1, to))
    }

    fun number(lines: List<String>, from: Int, to: Int) = (from..to).joinToString("\n") { n -> "${n.toString().padStart(4)}| ${lines.getOrElse(n - 1) { "" }}" }
}

// =============================================================================================== failure analysis
/** What kind of failure an attempt ended in: CLU reacts differently to each. */
enum class FailureKind(val label: String) {
    MALFORMED("the model's answer was not a valid patch"),
    REJECTED("the patch broke a rule (outside the shown code, a test file, overlapping or empty edits)"),
    REPEATED("the patch is the same as an earlier failed one"),
    SYNTAX("the code does not parse (syntax error)"),
    COMPILE("the code does not compile (type or reference error)"),
    TEST_FAILED("it compiles but the target test still fails"),
    REGRESSION("it fixes the target but breaks a test that passed before"),
    REVIEW_BLOCKED("the diff review refused it (test weakening, trust-critical file, too large)"),
    WRONG_DIAGNOSIS("every attempt on the suspected code failed: the suspected code may not be the cause"),
    MODEL_INCAPABLE("the model could not produce a working change within the budget"),
    NONE("verified"),
}

@Serializable
data class CompilerError(val file: String, val line: Int, val message: String)

object Failures {
    private val KOTLINC = Regex("""^e:\s+(?:file://)?(\S+?\.kts?):(\d+):(?:\d+)?\s*(.+)$""", RegexOption.MULTILINE)
    private val SYNTAX_WORDS = listOf("Expecting", "Unexpected tokens", "Unclosed comment", "Unresolved reference: \u0000", "Syntax error", "Missing '}", "expecting")

    /** Kotlin compiler errors from build output, with paths made relative to [root]. */
    fun compilerErrors(output: String, root: String): List<CompilerError> = KOTLINC.findAll(output).map { m ->
        val abs = m.groupValues[1]; val rel = abs.substringAfter(root.trimEnd('/') + "/", abs)
        CompilerError(rel, m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3].trim().take(300)) }.distinct().take(12).toList()

    fun isSyntax(errors: List<CompilerError>) = errors.isNotEmpty() && errors.all { e -> SYNTAX_WORDS.any { e.message.startsWith(it) || e.message.contains("Expecting") } }

    /** Feedback for the next attempt, short enough for a small model's context. */
    fun feedback(kind: FailureKind, errors: List<CompilerError>, after: TestRun?, target: List<String>): String = buildString {
        append("Your previous patch failed: ${kind.label}.")
        when (kind) {
            FailureKind.SYNTAX, FailureKind.COMPILE -> errors.take(5).forEach { append("\n- ${it.file}:${it.line}: ${it.message}") }
            FailureKind.TEST_FAILED -> after?.results?.filter { it.id in target && it.failed }?.take(2)?.forEach { append("\n- ${it.name}: ${Investigator.parseAssertion(it.message).take(300)}") }
            FailureKind.REGRESSION -> after?.failed?.filter { it.id !in target }?.take(3)?.forEach { append("\n- now failing: ${it.id}: ${Investigator.parseAssertion(it.message).take(200)}") }
            else -> {}
        }
    }
}

// =============================================================================================== the loop
/** One try within a coding task, as recorded. */
@Serializable
data class CodingAttempt(
    val n: Int, val strategy: String, val temperature: Float, val prompt: String, val raw: String, val patch: StructuredPatch?, val diff: String,
    val failure: String, val detail: String, val compilerErrors: List<CompilerError> = emptyList(), val passed: Int = 0, val failed: Int = 0,
    val generationMs: Long = 0, val buildMs: Long = 0, val fingerprint: String = "",
)

/** CLU's own model in development mode. [grammar] constrains the output; [reasoning] lets a reasoning model think first. */
interface DevModel {
    val description: String
    val lastError: String?
    suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Float, seed: Int, grammar: String?, reasoning: Boolean): String?
}

/** What the loop needs from the isolated workspace (implemented by the desktop host over a Git worktree). */
interface DevWorkspace {
    val root: String
    fun read(path: String): String?
    /** Writes the files; returns false if a path is outside the workspace. */
    fun write(files: Map<String, String>): Boolean
    /** Puts the workspace back to the base commit (between attempts). */
    fun reset()
    /** Builds and runs the task's tests. */
    fun test(label: String, phase: String): TestRun
    fun diff(): String
    fun buildOutput(): String
}

/** A bounded development task: what to achieve, what code may change, and how success is decided. */
data class CodingTask(
    val goal: String,
    /** The failing test(s) that must pass afterwards (repairs), or the tests that must pass (features). */
    val targetTests: List<String>,
    val windows: List<CodeWindow>,
    val evidence: String,
    val allowTests: Boolean = false,
    val newFiles: Set<String> = emptySet(),
    /** A development task (adding code) rather than a repair. */
    val feature: Boolean = false,
)

data class CodingBudget(val maxAttempts: Int = 8, val maxMinutes: Int = 20, val maxTokens: Int = 700,
                        /** Temperatures per attempt: deterministic first, then more varied (so retries are not the same answer). */
                        val temperatures: List<Float> = listOf(0.0f, 0.4f, 0.7f, 0.9f, 1.0f, 1.0f),
                        val reasoningFrom: Int = Int.MAX_VALUE,
                        /** Alternate the locate-then-rewrite strategy with whole patches (helps small models). */
                        val lineStrategy: Boolean = true)

data class CodingResult(val verified: Boolean, val attempts: List<CodingAttempt>, val finalFailure: FailureKind, val files: Map<String, String>, val after: TestRun?, val review: List<ReviewFinding>)

/**
 * The iterative development loop: generate a structured patch, validate it against the shown code, apply it in the
 * workspace, build and test, classify the failure, feed it back, and try again with a different approach, until the
 * change verifies, the budget runs out or a safety rule stops it. An identical patch is never submitted twice.
 */
class CodingLoop(private val model: DevModel, private val name: String = "CLU", private val clock: () -> Long = System::currentTimeMillis,
                 private val onStep: (String) -> Unit = {}) {

    fun systemPrompt(allowTests: Boolean) = """You are $name, working in developer mode on your own Kotlin source code.
You are given a task, evidence, and windows of source code with line numbers ("  42| code").
Answer only with one or more blocks in exactly this format (each code line starts with "| ", written exactly as Kotlin, no escaping):
FILE: <path as shown>
LINES: <first line>-<last line>
| <new code for those lines>
END
The new lines replace lines first..last. Rules: change as few lines as possible; only lines inside the windows shown; keep the existing indentation; ${if (allowTests) "you may add or change tests when the task asks for it" else "never change tests"}; do not repeat a patch that already failed."""

    fun userPrompt(task: CodingTask, history: List<CodingAttempt>): String = buildString {
        appendLine("Task: ${task.goal}")
        if (task.evidence.isNotBlank()) { appendLine(); appendLine(task.evidence.trim()) }
        for (w in task.windows) { appendLine(); appendLine("File ${w.file} (${w.purpose}), lines ${w.from}-${w.to}:"); appendLine(w.numbered) }
        val failed = history.filter { it.failure != FailureKind.NONE.name }
        if (failed.isNotEmpty()) {
            appendLine(); appendLine("Earlier attempts that did not work:")
            for (a in failed.takeLast(3)) {
                a.patch?.edits?.forEach { e -> appendLine("- ${e.op} ${e.file} ${e.start}-${e.end} with: ${e.code.lines().joinToString(" ⏎ ").take(200)}") }
                appendLine("  result: ${a.detail.lines().first().take(200)}")
                a.detail.lines().drop(1).take(4).forEach { appendLine("  $it") }
            }
            appendLine("Write a DIFFERENT patch.")
        }
    }

    /** Feature tasks: the model writes one complete new member, inserted before the closing brace of the class or object shown. */
    private suspend fun memberAttempt(task: CodingTask, temp: Float, n: Int, ws: DevWorkspace): String? {
        val w = task.windows.first()
        val src = ws.read(w.file) ?: return null
        val lines = src.split('\n')
        val close = (w.to downTo w.from).firstOrNull { lines.getOrElse(it - 1) { "" }.trim() == "}" } ?: return null
        val sys = "You are $name, adding code to your own Kotlin source. Answer with the complete new Kotlin function only, exactly as it should appear in the file (one or more lines), nothing else."
        val user = buildString {
            appendLine("Task: ${task.goal}"); appendLine(task.evidence.trim()); appendLine()
            appendLine("File ${w.file}:"); appendLine(w.numbered)
            appendLine(); append("Write ONLY the new function to add inside this object (it will be inserted before line $close). Do not repeat existing functions.")
        }
        val raw = model.complete(sys, user, 300, temp, 4000 + n, StructuredPatches.MEMBER_GRAMMAR, false) ?: return null
        val code = raw.lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n").takeIf { it.isNotBlank() } ?: return raw
        val indent = lines.getOrElse(close - 2) { "" }.takeWhile { it == ' ' }.ifEmpty { "    " }
        val body = code.trimEnd().lines().joinToString("\n") { if (it.isBlank() || it.startsWith(indent)) it else indent + it.trimStart() }
        return Json.encodeToString(StructuredPatch.serializer(), StructuredPatch("add a member", listOf(LineEdit(w.file, "insert", close - 1, close - 1, body))))
    }

    /**
     * The line strategy for small models: first "which line is wrong?" (a line number constrained by grammar, never one
     * already rewritten without success), then "rewrite exactly that line". The answer is turned into an ordinary
     * structured patch (one replace edit) and goes through the same validation, build and tests.
     */
    private suspend fun lineAttempt(task: CodingTask, history: List<CodingAttempt>, temp: Float, n: Int, ws: DevWorkspace): String? {
        val tried = history.flatMap { a -> a.patch?.edits.orEmpty().filter { it.start == it.end }.map { it.file to it.start } }.toSet()
        val triedText = if (tried.isEmpty()) "" else "\nThese lines were already changed without success, choose another one unless you are sure: " + tried.joinToString { "${it.first.substringAfterLast('/')}:${it.second}" }
        val locateSys = "You are $name, finding the single wrong line in your own Kotlin code. Answer with JSON only: {\"file\": \"<path as shown>\", \"line\": <number>, \"why\": \"<what is wrong in that line>\"}."
        val locateUser = userPrompt(task, emptyList()) + "\nWhich ONE line in the code shown is wrong? Think about what the test expects and what each line does. Never a test line, and not a function's declaration line unless its signature itself is wrong.$triedText"
        fun parseLoc(t: String) = runCatching { Json.parseToJsonElement(t.substring(t.indexOf('{'), t.lastIndexOf('}') + 1)).jsonObject }.getOrNull()
        // The grammar only admits real candidate lines (code inside the windows) that were not already tried.
        val all = task.windows.flatMap { w -> ws.read(w.file)?.let { src -> StructuredPatches.candidateLines(w, src).map { w.file to it } }.orEmpty() }
        val candidates = (all - tried).ifEmpty { all }
        val grammar = if (candidates.isEmpty()) StructuredPatches.LOCATE_GRAMMAR else StructuredPatches.locateGrammar(candidates)
        val loc = model.complete(locateSys, locateUser, 160, temp, 2000 + n, grammar, false) ?: return null
        val obj = parseLoc(loc) ?: return loc
        val line = obj["line"]?.jsonPrimitive?.intOrNull ?: return loc
        val why = obj["why"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val w = task.windows.firstOrNull { it.file == obj["file"]?.jsonPrimitive?.contentOrNull?.trim() && line in it.from..it.to } ?: task.windows.firstOrNull { line in it.from..it.to } ?: task.windows.first()
        val src = ws.read(w.file) ?: return loc
        val original = src.split('\n').getOrNull(line - 1) ?: return loc
        val rewriteSys = "You are $name, correcting one line of your own Kotlin code. Answer with the corrected line of Kotlin code only, exactly as it should appear in the file. Keep everything else on the line the same."
        val rewriteUser = buildString {
            appendLine("Task: ${task.goal}"); appendLine(task.evidence.trim()); appendLine()
            appendLine("File ${w.file}:"); appendLine(w.numbered.lines().joinToString("\n") { if (it.trimStart().startsWith("$line|")) ">>$it" else "  $it" })
            appendLine(); appendLine("The wrong line is $line: ${original.trim()}"); if (why.isNotBlank()) appendLine("Why: $why")
            append("Write the corrected line $line. It must be different from the current one.")
        }
        fun parseCode(t: String): String? = t.lines().firstOrNull { it.isNotBlank() }?.trimEnd()?.removeSurrounding("`")
        var rw = model.complete(rewriteSys, rewriteUser, 200, temp, 3000 + n, StructuredPatches.RAW_LINE_GRAMMAR, false) ?: return null
        var code = parseCode(rw) ?: return rw
        if (code.trim() == original.trim()) { // the same line back: one more try, warmer, saying so
            rw = model.complete(rewriteSys, rewriteUser + "\nYour answer \"${code.trim()}\" is identical to the current line; that cannot fix the failure. Change it.", 200, minOf(1.0f, temp + 0.5f), 3500 + n, StructuredPatches.RAW_LINE_GRAMMAR, false) ?: return null
            code = parseCode(rw) ?: return rw
        }
        // One line in, one line out: the first non-blank line of the answer, without copied line-number prefixes.
        val one = code.lines().map { it.replace(Regex("^\\s*>{0,2}\\s*\\d{1,5}\\|\\s?"), "") }.firstOrNull { it.isNotBlank() } ?: code
        return Json.encodeToString(StructuredPatch.serializer(), StructuredPatch(why.ifBlank { "line $line is wrong" }, listOf(LineEdit(w.file, "replace", line, line, one))))
    }

    suspend fun run(task: CodingTask, ws: DevWorkspace, before: TestRun, budget: CodingBudget, priorFingerprints: Set<String> = emptySet(),
                    review: (String) -> List<ReviewFinding>,
                    /** Decides success for this kind of task (repair, build, feature); default: the failing targets must pass with no regressions. */
                    judge: (TestRun, List<ReviewFinding>) -> Verdict = { after, f -> Verification.compare(task.targetTests, before, after, f) },
                    /** Called before each attempt; suspends while CLU is serving the owner (development yields). */
                    yieldToOwner: suspend () -> Unit = {}): CodingResult {
        val attempts = mutableListOf<CodingAttempt>()
        val seen = priorFingerprints.toMutableSet()
        val deadline = clock() + budget.maxMinutes * 60_000L
        val windows = task.windows.map { StructuredPatches.Window(it.file, it.from, it.to) }
        var lastAfter: TestRun? = null; var lastReview: List<ReviewFinding> = emptyList()
        var compileFailures = 0; var testFailures = 0
        for (n in 1..budget.maxAttempts) {
            yieldToOwner()
            if (clock() > deadline) break
            ws.reset() // every attempt starts from the base version the model is shown
            val reasoning = n >= budget.reasoningFrom
            // Alternate: the line strategy (locate one line, then rewrite it) and the whole-patch strategy (multi-line, multi-file).
            val lineStrategy = !reasoning && budget.lineStrategy && n % 2 == 1
            val temp = budget.temperatures.getOrElse(if (budget.lineStrategy) (n - 1) / 2 else n - 1) { 0.9f }
            val strategy = when { reasoning -> "structured+reasoning"; lineStrategy && task.feature -> "add-member"; lineStrategy -> "locate-then-rewrite"; else -> "structured" }
            onStep("attempt $n: asking the model for a patch ($strategy, temperature $temp)")
            val user = userPrompt(task, attempts)
            val t0 = clock()
            val raw = if (lineStrategy && task.feature) memberAttempt(task, temp, n, ws) else if (lineStrategy) lineAttempt(task, attempts, temp, n, ws) else
                model.complete(systemPrompt(task.allowTests), user, budget.maxTokens + if (reasoning) 1200 else 0, temp, 1000 + n, if (reasoning) null else StructuredPatches.BLOCK_GRAMMAR, reasoning)
            val genMs = clock() - t0
            fun record(kind: FailureKind, detail: String, patch: StructuredPatch? = null, diff: String = "", errors: List<CompilerError> = emptyList(), after: TestRun? = null, buildMs: Long = 0, fp: String = "") =
                CodingAttempt(n, strategy, temp, user, (raw ?: "").take(6000), patch, diff, kind.name, detail, errors, after?.passed ?: 0, after?.failed?.size ?: 0, genMs, buildMs, fp).also { attempts += it }
            if (raw == null) {
                if (model.lastError != null) { record(FailureKind.MODEL_INCAPABLE, "the model could not be used: ${model.lastError}"); break }
                record(FailureKind.MALFORMED, "no usable answer for this strategy"); continue
            }
            val patch = StructuredPatches.parseBlocks(raw) ?: StructuredPatches.parse(raw)
            if (patch == null) { record(FailureKind.MALFORMED, "the answer was not a patch (neither FILE/LINES blocks nor a JSON patch)"); continue }
            ws.reset() // validate against the base version the model was shown, never against an earlier attempt's changes
            val checked = StructuredPatches.check(patch, ws::read, windows, task.allowTests, task.newFiles)
            if (checked is StructuredPatches.Checked.Rejected) { record(FailureKind.REJECTED, "rejected: ${checked.reason}", patch); continue }
            val files = (checked as StructuredPatches.Checked.Ok).files
            val fp = StructuredPatches.fingerprint(files)
            if (!seen.add(fp)) { record(FailureKind.REPEATED, "the same change was already tried and failed", patch, fp = fp); continue }
            if (!ws.write(files)) { record(FailureKind.REJECTED, "a file is outside the workspace", patch); continue }
            val diff = ws.diff()
            val findings = review(diff)
            if (findings.any { it.severity == ReviewFinding.BLOCKING }) {
                lastReview = findings; record(FailureKind.REVIEW_BLOCKED, "review: " + findings.filter { it.severity == ReviewFinding.BLOCKING }.joinToString("; ") { "${it.file} ${it.message}".trim() }, patch, diff, fp = fp); continue }
            onStep("attempt $n: building and testing the change")
            val b0 = clock()
            val after = ws.test("attempt $n", "attempt-$n")
            val buildMs = clock() - b0
            lastAfter = after; lastReview = findings
            if (!after.compiled) {
                val errors = Failures.compilerErrors(ws.buildOutput(), ws.root)
                val kind = if (Failures.isSyntax(errors)) FailureKind.SYNTAX else FailureKind.COMPILE
                compileFailures++
                record(kind, Failures.feedback(kind, errors, after, task.targetTests), patch, diff, errors, after, buildMs, fp); continue
            }
            val v = judge(after, findings)
            when (v.outcome) {
                Verdict.VERIFIED -> { record(FailureKind.NONE, v.reasons.joinToString("; "), patch, diff, after = after, buildMs = buildMs, fp = fp)
                    return CodingResult(true, attempts, FailureKind.NONE, files, after, findings) }
                Verdict.REGRESSION -> { testFailures++; record(FailureKind.REGRESSION, Failures.feedback(FailureKind.REGRESSION, emptyList(), after, task.targetTests), patch, diff, after = after, buildMs = buildMs, fp = fp) }
                else -> { testFailures++; record(FailureKind.TEST_FAILED, Failures.feedback(FailureKind.TEST_FAILED, emptyList(), after, task.targetTests) + " (${v.outcome})", patch, diff, after = after, buildMs = buildMs, fp = fp) }
            }
        }
        ws.reset()
        val final = when {
            attempts.isEmpty() -> FailureKind.MODEL_INCAPABLE
            attempts.last().failure == FailureKind.MODEL_INCAPABLE.name -> FailureKind.MODEL_INCAPABLE
            testFailures >= 3 && compileFailures == 0 -> FailureKind.WRONG_DIAGNOSIS
            else -> FailureKind.MODEL_INCAPABLE
        }
        return CodingResult(false, attempts, final, emptyMap(), lastAfter, lastReview)
    }
}

// =============================================================================================== persistent coding experience
class CodingExperience(private val store: EvolutionStore) {
    private val db = store.db
    init { Schema2.ensure(store) }

    fun record(taskId: String, kind: String, goal: String, model: String, a: CodingAttempt) {
        db.execute("""INSERT INTO coding_attempts(id, task_id, kind, goal, model, n, strategy, temperature, failure, detail, patch, diff, fingerprint, passed, failed,
            generation_ms, build_ms, prompt, raw, at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            listOf(newId(), taskId, kind, goal.take(2000), model, a.n, a.strategy, a.temperature.toDouble(), a.failure, a.detail.take(4000),
                a.patch?.let { CluJson.encodeToString(StructuredPatch.serializer(), it) }.orEmpty(), a.diff.take(20_000), a.fingerprint, a.passed, a.failed,
                a.generationMs, a.buildMs, a.prompt.take(12_000), a.raw.take(6000), store.clock.now()))
    }

    /** Fingerprints of changes already tried for this goal that failed: never submitted again. */
    fun failedFingerprints(goal: String): Set<String> =
        db.query("SELECT fingerprint FROM coding_attempts WHERE goal = ? AND failure != 'NONE' AND fingerprint != ''", listOf(goal.take(2000))) { it.str(0) }.toSet()

    /** Trusted examples: only verified changes (never unverified model output). */
    fun verifiedExamples(limit: Int = 3): List<Pair<String, String>> =
        db.query("SELECT goal, diff FROM coding_attempts WHERE failure = 'NONE' ORDER BY at DESC LIMIT $limit") { it.str(0) to it.str(1) }

    data class Stats(val tasks: Int, val attempts: Int, val verified: Int, val byFailure: Map<String, Int>)
    fun stats(): Stats {
        val by = db.query("SELECT failure, COUNT(*) FROM coding_attempts GROUP BY failure") { it.str(0) to it.lng(1).toInt() }.toMap()
        return Stats(db.query("SELECT COUNT(DISTINCT task_id) FROM coding_attempts") { it.lng(0).toInt() }.first(), by.values.sum(), by["NONE"] ?: 0, by)
    }

    fun attempts(taskId: String): List<Map<String, String>> = db.query(
        "SELECT n, strategy, temperature, failure, detail, diff, generation_ms, build_ms, raw FROM coding_attempts WHERE task_id = ? ORDER BY n", listOf(taskId)) {
        mapOf("n" to it.str(0), "strategy" to it.str(1), "temperature" to it.str(2), "failure" to it.str(3), "detail" to it.str(4), "diff" to it.str(5),
            "generationMs" to it.str(6), "buildMs" to it.str(7), "raw" to it.str(8)) }
}

/** Second migration of evolution.db: coding attempts. Kept separate so existing databases upgrade in place. */
object Schema2 {
    val STEP = listOf(
        """CREATE TABLE coding_attempts (id TEXT PRIMARY KEY, task_id TEXT NOT NULL, kind TEXT NOT NULL, goal TEXT NOT NULL, model TEXT NOT NULL, n INTEGER NOT NULL,
            strategy TEXT NOT NULL, temperature REAL NOT NULL, failure TEXT NOT NULL, detail TEXT NOT NULL, patch TEXT NOT NULL, diff TEXT NOT NULL, fingerprint TEXT NOT NULL,
            passed INTEGER NOT NULL, failed INTEGER NOT NULL, generation_ms INTEGER NOT NULL, build_ms INTEGER NOT NULL, prompt TEXT NOT NULL, raw TEXT NOT NULL, at INTEGER NOT NULL)""",
        """CREATE INDEX idx_coding_task ON coding_attempts(task_id, n)""",
        """CREATE INDEX idx_coding_goal ON coding_attempts(goal)""",
    )
    fun ensure(store: EvolutionStore) {} // applied by EvolutionStore.MIGRATIONS
}
