package ai.clu.desktop.capability

import ai.clu.core.evolution.DevModel
import ai.clu.core.util.CluJson
import ai.clu.core.util.SafeDelete
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * What an application must do, as checkable requirements. Each requirement type comes with the checks CLU runs in the
 * browser and the page contract the code must follow so the checks can find things (`data-entry`, `data-action`,
 * `data-role`). The types are generic building blocks shared by any application (files, search, 3D scene, timer…);
 * the application itself is written by the development model.
 */
object Requirements {
    data class Req(val id: String, val text: String, val contract: String, val checks: List<JsonObject>, val permission: String? = null)

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private fun check(name: String, vararg steps: JsonObject) = buildJsonObject { put("name", name); put("steps", JsonArray(steps.toList())) }
    private fun s(vararg kv: Pair<String, Any>) = buildJsonObject { kv.forEach { (k, v) -> when (v) { is String -> put(k, v); is Int -> put(k, v); is List<*> -> put(k, JsonArray(v.map { JsonPrimitive(it.toString()) })); else -> put(k, v.toString()) } } }

    /** The fixture CLU creates for verification when an application reads files (never the owner's files). */
    val FIXTURE = mapOf("notes.txt" to "Remember the milk.", "readme.txt" to "Fixture for CLU's checks.", "alpha/beta.md" to "# Beta", "alpha/gamma/deep.txt" to "deep file")

    fun derive(text: String, previous: List<String> = emptyList()): List<Req> {
        val t = text.lowercase()
        val ids = previous.toMutableSet()
        if (r("\\b(?:files?|folders?|director(?:y|ies)|explorer|file\\s+browser|documents?\\s+browser|drive)\\b").containsMatchIn(t)) ids += "fs"
        if (r("\\b(?:search|find|filter|look\\s+up|lookup)\\b").containsMatchIn(t)) ids += "search"
        if (r("\\b(?:3d|3-d|three[\\s-]dimensional|galaxy|galactic|space|solar|planets?|orbit\\w*|globe|spatial|starfield|stars?|universe|cosmic|nebula)\\b").containsMatchIn(t)) ids += "scene3d"
        if (r("\\b(?:timer|pomodoro|countdown|stopwatch)\\b").containsMatchIn(t)) ids += "timer"
        ids += "renders"
        return ORDER.filter { it in ids }.map { build(it, ids) }
    }

    // Checks that only look run before checks that change what is shown (a search leaves one result on screen).
    private val ORDER = listOf("renders", "scene3d", "fs", "search", "timer")

    fun build(id: String, all: Set<String>): Req = when (id) {
        "renders" -> Req(id, "shows a working interface with no script errors", "Put the main content inside the page; show short status text in an element with data-role=\"status\".",
            listOf(check("renders: the interface shows content", s("expectText" to listOf("body", "[A-Za-z]{3,}")))))
        "fs" -> Req(id, "browses real files and folders inside the folder the owner permitted: open folders, go back, say when a folder is empty, show errors",
            "Show every entry of the current folder as a clickable element with data-entry=\"<entry name>\" (folders open when clicked). " +
                "A back control with data-action=\"back\" goes to the parent folder. Show the current folder path in an element with data-role=\"path\". " +
                "Show messages (\"This folder is empty\", errors such as permission denied) in an element with data-role=\"message\". " +
                "List with kit.fs.list(path) (path \"\" is the permitted folder); it throws an Error with a message when a folder cannot be opened.",
            listOf(
                check("files: the permitted folder's entries are listed", s("expect" to "[data-entry=\"alpha\"]"), s("expect" to "[data-entry=\"notes.txt\"]"), s("expect" to "[data-entry=\"empty\"]")),
                check("files: a folder opens", s("click" to "[data-entry=\"alpha\"]"), s("expect" to "[data-entry=\"beta.md\"]"), s("expectText" to listOf("[data-role=\"path\"]", "alpha"))),
                check("files: back returns to the parent", s("click" to "[data-action=\"back\"]"), s("expect" to "[data-entry=\"notes.txt\"]")),
                check("files: an empty folder is said to be empty", s("click" to "[data-entry=\"empty\"]"), s("expectText" to listOf("[data-role=\"message\"]", "empty|no files|nothing")), s("click" to "[data-action=\"back\"]"), s("expect" to "[data-entry=\"notes.txt\"]")),
                check("files: a folder that cannot be opened shows an error and the app keeps working", s("click" to "[data-entry=\"locked\"]"),
                    s("expectText" to listOf("[data-role=\"message\"]", "denied|error|cannot|can't|could not|unable|outside|not allowed|failed")), s("click" to "[data-action=\"back\"]"), s("expect" to "[data-entry=\"notes.txt\"]")),
            ), permission = "fs.read")
        "search" -> if ("fs" in all) Req(id, "searches file and folder names below the permitted folder",
            "A text input with data-action=\"search\"; pressing Enter searches (kit.fs.search(query) returns {entries:[...]} with paths) and shows each match as an element with data-entry=\"<name>\".",
            listOf(check("search: finds a file deep in the folder tree", s("type" to listOf("[data-action=\"search\"]", "deep")), s("key" to listOf("[data-action=\"search\"]", "Enter")), s("expect" to "[data-entry=\"deep.txt\"]"))))
            else Req(id, "lets the owner search what it shows", "A text input with data-action=\"search\"; Enter applies it.", listOf(check("search: a search box is present", s("expect" to "[data-action=\"search\"]"))))
        "scene3d" -> Req(id, "draws an interactive 3D scene that turns when dragged",
            "Draw the 3D scene on a <canvas data-role=\"scene\"> (use CluScene3D.create(canvas, {onPick}) and scene.setNodes([...]) — positions x,y,z, size, color, label); dragging turns it." +
                (if ("fs" in all) " Each entry of the current folder is also a node in the scene; picking a folder node opens it." else ""),
            listOf(check("3D: the scene is drawn", s("canvasNotBlank" to "canvas[data-role=\"scene\"]")), check("3D: dragging turns the scene", s("canvasDrag" to "canvas[data-role=\"scene\"]"))))
        else -> Req(id, "runs a timer that counts while running", "A start control with data-action=\"start\" and the remaining time in an element with data-role=\"time\".",
            listOf(check("timer: starts and counts", s("click" to "[data-action=\"start\"]"), s("changes" to listOf("[data-role=\"time\"]", "1500")))))
    }

    fun checksJson(reqs: List<Req>) = JsonArray(reqs.flatMap { it.checks })
}

/** The generic shell every developed application gets (identical for every application; its code is in app.*). */
object AppScaffold {
    val OWN_FILES = listOf("app.html", "app.css", "app.js")

    fun write(dir: File, appId: String, capId: String, version: Int, name: String, request: String, reqs: List<Requirements.Req>, files: Map<String, String>) {
        dir.mkdirs()
        files.forEach { (n, t) -> File(dir, n).writeText(t) }
        val scripts = listOf("/kit/clu-kit.js") + (if (reqs.any { it.id == "scene3d" }) listOf("/kit/clu-scene3d.js") else emptyList()) + listOf("/kit/clu-probe.js", "app.js", "boot.js")
        File(dir, "index.html").writeText("""<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>${ExperienceGenerator.esc(name)}</title>
<link rel="stylesheet" href="app.css"></head>
<body class="clu-app">
<div id="expStatus" data-role="status" class="clu-status" role="status"></div>
${files["app.html"] ?: ""}
<pre id="clu-selfcheck" hidden></pre>
${scripts.joinToString("\n") { "<script src=\"$it\"></script>" }}
</body>
</html>
""")
        File(dir, "boot.js").writeText("""/* Generated by CLU: starts the application on the kit runtime. */
(function () { "use strict";
  CluKit.start(${buildJsonObject { put("capId", capId); put("appId", appId); put("version", version); put("kind", "app"); put("name", name) }}, async function (kit) {
    if (!window.CluApp || typeof window.CluApp.mount !== "function") throw new Error("app.js did not define window.CluApp.mount");
    return window.CluApp.mount(kit);
  });
})();
""")
        File(dir, "checks.json").writeText(Requirements.checksJson(reqs).toString())
        File(dir, "manifest.json").writeText(buildJsonObject {
            put("id", capId); put("version", version); put("kind", "app"); put("name", name); put("request", request); put("entry", "index.html"); put("kit", ExperienceGenerator.KIT_VERSION)
            putJsonArray("requirements") { reqs.forEach { add(it.id) } }
            putJsonArray("permissions") { reqs.mapNotNull { it.permission }.distinct().forEach { add(it) } }
            putJsonArray("authoredFiles") { OWN_FILES.forEach { add(it) } }
        }.toString())
    }

    /** Problems a browser would hit (CSP: no inline code) or that break CLU's rules; empty when the files can be run. */
    fun check(files: Map<String, String>): List<String> = buildList {
        val js = files["app.js"]; val html = files["app.html"] ?: ""
        if (js.isNullOrBlank()) add("app.js is missing or empty")
        else {
            if (!js.contains("CluApp")) add("app.js must define window.CluApp = { mount: async function (kit) { … } }")
            if (Regex("\\beval\\s*\\(|new\\s+Function\\s*\\(").containsMatchIn(js)) add("app.js must not use eval or new Function")
            if (Regex("https?://", RegexOption.IGNORE_CASE).containsMatchIn(js.replace(Regex("//[^\\n]*"), ""))) add("app.js must not contact other sites (use kit APIs only)")
            if (js.length > 80_000) add("app.js is over 80 KB")
        }
        if (Regex("<script", RegexOption.IGNORE_CASE).containsMatchIn(html)) add("app.html must not contain <script> (scripts go in app.js)")
        if (Regex("\\son[a-z]+\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(html)) add("app.html must not use inline event handlers (onclick=…); add listeners in app.js")
        if (Regex("\\sstyle\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(html)) add("app.html must not use style attributes; put styles in app.css")
        if (Regex("(?:src|href)\\s*=\\s*\"(?:https?:)?//", RegexOption.IGNORE_CASE).containsMatchIn(html)) add("app.html must not load anything from other sites")
    }

    /**
     * Mechanical fixes CLU applies itself, like a linter's autofix, so a model that repeats a formatting mistake still
     * gets its logic tested: inline `<script>` blocks move from app.html into app.js, and inline event-handler
     * attributes are removed (they could never run under the CSP). Returns the fixed files and what was changed.
     */
    fun autofix(files: Map<String, String>): Pair<Map<String, String>, List<String>> {
        var html = files["app.html"] ?: return files to emptyList()
        val notes = mutableListOf<String>()
        val scripts = Regex("<script(?![^>]*\\bsrc=)[^>]*>(.*?)</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        if (Regex("<script", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            html = html.replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "").replace(Regex("<script[^>]*/?>", RegexOption.IGNORE_CASE), "")
            notes += "moved ${scripts.size} inline <script> block(s) from app.html into app.js"
        }
        val handlers = Regex("\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*')", RegexOption.IGNORE_CASE)
        if (handlers.containsMatchIn(html)) { notes += "removed ${handlers.findAll(html).count()} inline event handler attribute(s) (blocked by the CSP)"; html = html.replace(handlers, "") }
        if (notes.isEmpty()) return files to emptyList()
        val js = listOfNotNull(files["app.js"]?.takeIf { it.isNotBlank() }) + scripts
        return (files + mapOf("app.html" to html, "app.js" to js.joinToString("\n\n") + "\n")) to notes
    }

    /** Files in the model's answer: `=== FILE: name ===` blocks (code fences tolerated). */
    fun parse(answer: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val re = Regex("===\\s*FILE:\\s*(app\\.(?:html|css|js))\\s*===\\s*\\n(.*?)(?=\\n===\\s*(?:FILE:|END)|\\z)", RegexOption.DOT_MATCHES_ALL)
        re.findAll(answer).forEach { m -> var body = m.groupValues[2].trim('\n')
            body = body.replace(Regex("^```[a-zA-Z]*\\n"), "").replace(Regex("\\n```\\s*$"), "")
            out[m.groupValues[1]] = body.trimEnd() + "\n" }
        return out
    }
}

/**
 * CLU's general software authoring loop for applications that no kit family covers. The development model writes the
 * application (app.html, app.css, app.js); CLU checks the files, runs the application in a real browser against its
 * requirement checks, gives the model the actual failures (failed checks, script errors, build problems) and lets it
 * revise its own code, within an attempt and time budget. Nothing is installed unless every check passes.
 */
class SoftwareAuthor(
    private val store: CapabilityStore,
    private val verifier: ExperienceVerifier,
    private val apps: AppServices,
    private val models: DevModels?,
    private val workspaces: File,
    private val baseUrl: () -> String,
    private val launchCode: () -> String,
    private val contextTokens: () -> Int,
) {
    data class Budget(val attempts: Int, val minutes: Int)
    fun budget() = Budget(store.pref("dev.budget.attempts")?.toIntOrNull()?.coerceIn(1, 20) ?: 5, store.pref("dev.budget.minutes")?.toIntOrNull()?.coerceIn(1, 240) ?: 45)

    data class Outcome(val passed: Boolean, val files: Map<String, String>, val result: ExperienceVerifier.Result?, val attempts: Int, val reason: String, val paused: Boolean = false)

    fun system(name: String) = """You are $name, a personal AI, writing a web application that runs inside your own window.
Answer ONLY with complete files in this exact format and nothing else:
=== FILE: app.html ===
(the HTML that goes inside <body>; no <script>, no inline event handlers, no style attributes)
=== FILE: app.css ===
(all styles)
=== FILE: app.js ===
(all code; it must define window.CluApp = { mount: async function (kit) { ... } })
=== END ==="""

    fun kitDocs(reqs: List<Requirements.Req>) = buildString {
        appendLine("The kit object passed to mount(kit):")
        appendLine("- kit.\$(id) returns document.getElementById(id); kit.el(tag, className, text) creates an element with text.")
        appendLine("- kit.status(text) shows a short status line.")
        if (reqs.any { it.id == "fs" }) {
            appendLine("- await kit.fs.list(path) -> {path: \"alpha\", entries: [{name: \"beta.md\", path: \"alpha/beta.md\", type: \"dir\"|\"file\"|\"link\", size: 12}]}. path \"\" is the permitted folder. Throws Error(message) if the folder cannot be opened (e.g. permission denied).")
            if (reqs.any { it.id == "search" }) appendLine("- await kit.fs.search(query) -> {entries: [same shape as list]} for names containing query anywhere below the permitted folder.")
            appendLine("- await kit.fs.read(path) -> {text, size, binary} for a file.")
        }
        if (reqs.any { it.id == "scene3d" }) {
            appendLine("- CluScene3D.create(canvas, {background: \"#02040a\", onPick: function (node) {}}) returns scene; scene.setNodes([{id, label, x, y, z, size, color}]) draws them in 3D (coordinates about -200..200); dragging the canvas turns the scene.")
        }
        appendLine("Only these APIs may be used; there is no network access and no other library.")
    }

    fun firstPrompt(request: String, reqs: List<Requirements.Req>, memory: List<String>) = buildString {
        appendLine("The owner asked: \"$request\"")
        appendLine("Requirements (each is checked automatically in a browser):")
        reqs.forEachIndexed { i, r -> appendLine("${i + 1}. ${r.text}. ${r.contract}") }
        appendLine(); append(kitDocs(reqs))
        if (memory.isNotEmpty()) { appendLine(); appendLine("Lessons from earlier development:"); memory.forEach { appendLine("- $it") } }
        appendLine(); appendLine("Write the complete application now: a striking look that fits the request, and every requirement working.")
    }

    fun revisePrompt(request: String, reqs: List<Requirements.Req>, files: Map<String, String>, failures: List<String>, extra: String = "") = buildString {
        appendLine("The owner asked: \"$request\"")
        appendLine("Requirements:"); reqs.forEachIndexed { i, r -> appendLine("${i + 1}. ${r.text}. ${r.contract}") }
        appendLine(); append(kitDocs(reqs))
        if (extra.isNotBlank()) { appendLine(); appendLine(extra) }
        appendLine(); appendLine("Your current files:")
        AppScaffold.OWN_FILES.forEach { n -> files[n]?.let { appendLine("=== FILE: $n ==="); appendLine(it.trimEnd()) } }
        appendLine("=== END ===")
        appendLine(); appendLine("What happened when CLU ran them in a browser:")
        failures.take(14).forEach { appendLine("- $it") }
        appendLine(); appendLine("Fix every problem. Answer with the complete new content of every file you change, in the same format.")
    }

    /** Fixture folder for verification: files, nested folders, an empty folder, and a link leading outside (must be refused). */
    fun fixture(ws: File): File {
        val root = File(ws, "fixture/files"); root.mkdirs()
        Requirements.FIXTURE.forEach { (p, t) -> File(root, p).apply { parentFile.mkdirs(); writeText(t) } }
        File(root, "empty").mkdirs()
        val outside = File(ws, "fixture/outside").apply { mkdirs(); File(this, "secret.txt").writeText("must not be readable") }
        runCatching { Files.createSymbolicLink(File(root, "locked").toPath(), outside.toPath()) }.onFailure { File(root, "locked").mkdirs() }
        return root
    }

    fun syntaxErrors(ws: File, js: String): String? {
        val node = listOfNotNull(System.getProperty("clu.node"), "/opt/node22/bin/node", "/usr/bin/node", "/usr/local/bin/node").map(::File).firstOrNull { it.canExecute() }
            ?: runCatching { ProcessBuilder(if (System.getProperty("os.name").lowercase().contains("win")) listOf("where", "node") else listOf("which", "node")).start().inputStream.bufferedReader().readLine()?.let(::File) }.getOrNull()?.takeIf { it.canExecute() }
            ?: return null
        val f = File(ws, "syntax-check.js"); f.writeText(js)
        return try {
            val p = ProcessBuilder(node.path, "--check", f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); null } else if (p.exitValue() == 0) null else out.lines().filter { it.isNotBlank() && !it.startsWith("    at ") && !it.startsWith("Node.js") }.take(6).joinToString(" | ").replace(f.path, "app.js")
        } finally { f.delete() }
    }

    /**
     * Writes, runs and revises the application until every check passes or the budget is spent. [start] are files to
     * begin from (an installed version being improved, or a paused run). [step] records progress; [pause] is polled.
     */
    fun author(runId: String, capId: String, version: Int, name: String, request: String, reqs: List<Requirements.Req>, start: Map<String, String>?, startAttempt: Int,
               memory: List<String>, step: (String, String, String) -> Unit, note: (String, String) -> Unit, pause: () -> Boolean): Outcome {
        val ws = File(workspaces, runId).apply { mkdirs() }
        val out = File(ws, "out")
        val appId = "preview-$runId"
        apps.grantPreview(appId, fixture(ws))
        val b = budget(); val deadline = System.currentTimeMillis() + b.minutes * 60_000L
        val coder = models?.choose("code"); val debugger = models?.choose("debug") ?: coder
        if (coder == null) { apps.revokePreview(appId); return Outcome(false, start ?: emptyMap(), null, startAttempt, "no development model is available (select a local model in Models, or configure a permitted free remote model)") }
        note("models", "coding: ${coder.id}; debugging: ${debugger!!.id}")
        val ctx = contextTokens()
        var files = start ?: emptyMap()
        var failures = emptyList<String>()
        var last: ExperienceVerifier.Result? = null
        var attempt = startAttempt
        val improving = start != null && startAttempt == 0
        try {
            while (attempt < b.attempts) {
                if (pause()) return Outcome(false, files, last, attempt, "paused", paused = true)
                if (System.currentTimeMillis() > deadline) return Outcome(false, files, last, attempt, "the ${b.minutes}-minute budget ran out")
                attempt++
                val first = files.isEmpty()
                val model = if (first || failures.isEmpty()) coder else debugger
                val prompt = when {
                    first -> firstPrompt(request, reqs, memory)
                    failures.isEmpty() && improving -> revisePrompt(request, reqs, files, listOf("Not run yet: this is an improvement of an installed version. Change it so the new requirements work and the existing ones keep working."))
                    else -> revisePrompt(request, reqs, files, failures)
                }
                val promptTokens = (system(name).length + prompt.length) / 3
                val room = ctx - promptTokens - 64
                if (room < 1200) { note("errors", "attempt $attempt: the prompt needs ~$promptTokens tokens but the model's context is $ctx; at least ${promptTokens + 1300} are needed (Models → Context length)")
                    return Outcome(false, files, last, attempt, "the model's context window ($ctx tokens) is too small for this application (needs about ${promptTokens + 1300})") }
                val t0 = System.currentTimeMillis()
                val answer = runBlocking { model.model.complete(system(name), prompt, minOf(room, 7000), if (attempt == 1) 0.3f else 0.5f, 17 + attempt, null, false) }
                val ms = System.currentTimeMillis() - t0
                val changed = answer?.let { AppScaffold.parse(it) } ?: emptyMap()
                step("implement", if (changed.isEmpty()) "failed" else "done", "attempt $attempt (${model.id}, ${ms / 1000}s): " +
                    (if (changed.isEmpty()) "the answer contained no files${answer?.let { " — it began: \"${it.trim().take(160).replace('\n', ' ')}\"" } ?: " (${model.model.lastError ?: "no answer"})"}"
                    else "wrote ${changed.entries.joinToString { "${it.key} (${it.value.lines().size} lines)" }}"))
                val role = if (model === debugger && !first) "debug" else "code"
                if (changed.isEmpty()) { store.recordModel(model.id, role, false, ms); failures = listOf("Your answer contained no files. Answer only with the === FILE: … === blocks."); continue }
                files = files + changed
                AppScaffold.autofix(files).let { (fixed, notes) -> if (notes.isNotEmpty()) { files = fixed; step("fix", "done", "CLU's mechanical fix: ${notes.joinToString("; ")}"); note("fixes", notes.joinToString("; ")) } }
                val problems = AppScaffold.check(files) + listOfNotNull(files["app.js"]?.let { syntaxErrors(ws, it) }?.let { "JavaScript syntax error: $it" })
                if (problems.isNotEmpty()) { store.recordModel(model.id, role, false, ms); step("build", "failed", problems.joinToString("; ")); failures = problems.map { "build: $it" }; note("errors", "attempt $attempt build: ${problems.joinToString("; ")}"); continue }
                if (out.exists()) SafeDelete.tree(out, listOf(workspaces))
                AppScaffold.write(out, appId, capId, version, name, request, reqs, files)
                step("build", "done", "files checked (no inline code, no other sites${if (files["app.js"] != null) ", JavaScript syntax valid" else ""})")
                val shot = File(ws, "screenshot.png")
                val r = verifier.verify("${baseUrl()}/x/preview/$runId/index.html", launchCode, shot,
                    Blueprint("app", "custom", name, "", reqs.map { it.id }, Styles.derived("app")))
                last = r
                val bad = r.checks.filter { !it.ok }
                step("test", if (bad.none { !it.name.startsWith("screenshot") }) "done" else "failed",
                    r.checks.filter { !it.name.startsWith("screenshot") }.joinToString("; ") { (if (it.ok) "✓ " else "✗ ") + it.name + (if (it.detail.isNotBlank()) " (${it.detail.take(140)})" else "") })
                step("inspect", if (bad.none { it.name.startsWith("screenshot") }) "done" else "failed", r.checks.filter { it.name.startsWith("screenshot") }.joinToString("; ") { (if (it.ok) "✓ " else "✗ ") + it.name + " (${it.detail})" })
                store.recordModel(model.id, role, r.passed, ms)
                if (r.passed) return Outcome(true, files, r, attempt, "")
                if (r.checks.any { it.name == "browser available" && !it.ok }) return Outcome(false, files, r, attempt, "no browser to verify with")
                failures = bad.map { it.name + (if (it.detail.isNotBlank()) ": ${it.detail.take(300)}" else "") }
                note("errors", "attempt $attempt: " + failures.joinToString("; ").take(1500))
                if (attempt < b.attempts) step("fix", "done", "attempt $attempt failed ${bad.size} check(s); the failures go back to the model for attempt ${attempt + 1}")
            }
            return Outcome(false, files, last, attempt, "the ${b.attempts}-attempt budget was spent; last failures: ${failures.joinToString("; ").take(600)}")
        } finally { apps.revokePreview(appId) }
    }
}
