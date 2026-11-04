package ai.clu.desktop.capability

import ai.clu.core.evolution.DevModel
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Measures development models on the kind of work CLU's authoring loop asks of them: write a function, implement
 * behaviour, edit existing code without breaking it, fix a syntax error from the error message, fix a failing test,
 * and build a small DOM interface. Each answer is run in a real browser against tests; the results are kept per
 * model and role (`code`, `edit`, `debug`) and decide which model CLU uses for which role.
 */
class AuthoringBench(private val store: CapabilityStore, private val verifier: ExperienceVerifier, private val scratch: File) {
    data class Task(val id: String, val role: String, val prompt: String, val tests: String)
    data class Result(val task: String, val role: String, val passed: Boolean, val ms: Long, val detail: String)

    val tasks = listOf(
        Task("create-function", "code", "Write a JavaScript function formatSize(bytes) that returns \"512 B\" for 512, \"1.5 KB\" for 1536 and \"2.0 MB\" for 2097152 (one decimal for KB and MB).",
            "t(formatSize(512) === '512 B', '512'); t(formatSize(1536) === '1.5 KB', '1536'); t(formatSize(2097152) === '2.0 MB', '2097152');"),
        Task("implement-behaviour", "code", "Write a JavaScript function sortEntries(entries) that returns a new array of {name, type} objects with folders (type \"dir\") first, then files, each group sorted by name ignoring case.",
            "const r = sortEntries([{name:'b.txt',type:'file'},{name:'Zed',type:'dir'},{name:'a.txt',type:'file'},{name:'alpha',type:'dir'}]).map(e => e.name).join(','); t(r === 'alpha,Zed,a.txt,b.txt', r);"),
        Task("edit-existing", "edit", "Change this code so greet takes an optional second parameter greeting (default \"Hello\") and keeps working for existing callers:\nfunction greet(name) { return \"Hello, \" + name + \"!\"; }",
            "t(greet('Ana') === 'Hello, Ana!', 'old callers'); t(greet('Ana', 'Hi') === 'Hi, Ana!', 'new parameter');"),
        Task("fix-syntax-error", "debug", "This code fails with \"SyntaxError: Unexpected token '}'\". Fix it.\nfunction parent(path) {\n  const parts = path.split('/');\n  parts.pop(;\n  return parts.join('/');\n}",
            "t(parent('a/b/c') === 'a/b', parent('a/b/c')); t(parent('a') === '', 'top');"),
        Task("fix-failing-test", "debug", "The test `countFiles([{type:'file'},{type:'dir'},{type:'file'}]) === 2` fails: it returns 3. Fix the function.\nfunction countFiles(entries) { return entries.filter(e => e.type).length; }",
            "t(countFiles([{type:'file'},{type:'dir'},{type:'file'}]) === 2, 'count');"),
        Task("build-interface", "code", "Write a JavaScript function render(entries) that fills the existing <ul id=\"list\"> with one <li> per entry showing entry.name, with attribute data-entry set to the name, replacing what was there.",
            "document.body.insertAdjacentHTML('beforeend', '<ul id=\"list\"><li>old</li></ul>'); render([{name:'a'},{name:'b'}]); const li = document.querySelectorAll('#list li'); t(li.length === 2 && li[1].dataset.entry === 'b', li.length + ' items');"),
    )

    private val SYSTEM = "You are a careful JavaScript programmer. Answer ONLY with the code, in this format:\n=== FILE: solution.js ===\n(code)\n=== END ==="

    fun solution(answer: String?): String? = answer?.let { a ->
        Regex("===\\s*FILE:\\s*solution\\.js\\s*===\\s*\\n(.*?)(?=\\n===\\s*END|\\z)", RegexOption.DOT_MATCHES_ALL).find(a)?.groupValues?.get(1)
            ?: Regex("```(?:javascript|js)?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).find(a)?.groupValues?.get(1)
    }?.replace(Regex("^```[a-z]*\\n|\\n```\\s*$"), "")

    /** Runs [code] against [tests] in the headless browser; returns failures (empty = all passed). */
    fun runTests(code: String, tests: String): List<String> {
        val f = File(scratch, "bench-${System.nanoTime()}.html")
        f.parentFile.mkdirs()
        val esc = { s: String -> s.replace("</script", "<\\/script") }
        f.writeText("""<!doctype html><html><body><pre id="out">running</pre>
<script>window.__f = []; window.onerror = function (m) { window.__f.push('error: ' + m); };</script>
<script>${esc(code)}</script>
<script>function t(ok, what) { if (!ok) window.__f.push('failed: ' + what); }
try { ${esc(tests)} } catch (e) { window.__f.push('threw: ' + e.message); }
document.getElementById('out').textContent = JSON.stringify(window.__f);</script></body></html>""")
        return try {
            val dom = verifier.dumpDom(f.toURI().toString()) ?: return listOf("the browser did not run the test")
            val out = Regex("<pre id=\"out\">(.*?)</pre>", RegexOption.DOT_MATCHES_ALL).find(dom)?.groupValues?.get(1)?.replace("&quot;", "\"")?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")
                ?: return listOf("no test output")
            if (out == "running") listOf("the code stopped the tests from running") else runCatching { ai.clu.core.util.CluJson.parseToJsonElement(out) }.getOrNull()
                ?.let { (it as kotlinx.serialization.json.JsonArray).map { e -> e.toString().trim('"') } } ?: listOf(out.take(200))
        } finally { f.delete() }
    }

    fun run(modelId: String, model: DevModel): List<Result> = tasks.map { t ->
        val t0 = System.currentTimeMillis()
        val answer = runCatching { runBlocking { model.complete(SYSTEM, t.prompt, 700, 0.2f, 3, null, false) } }.getOrNull()
        val ms = System.currentTimeMillis() - t0
        val code = solution(answer)
        val failures = if (code.isNullOrBlank()) listOf("no code in the answer" + (answer?.let { ": \"${it.trim().take(100)}\"" } ?: "")) else runTests(code, t.tests)
        val r = Result(t.id, t.role, failures.isEmpty(), ms, failures.joinToString("; ").take(300))
        store.recordModel(modelId, t.role, r.passed, ms); store.recordModel(modelId, "bench:${t.id}", r.passed, ms)
        r
    }
}
