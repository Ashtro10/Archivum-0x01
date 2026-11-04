package ai.clu.desktop

import ai.clu.core.evolution.DevModel
import ai.clu.core.util.CluJson
import ai.clu.desktop.capability.ExperienceVerifier
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/**
 * The general authoring engine's mechanics with a scripted model (test equipment in src/test/resources/authoring):
 * the model's first answer is broken, CLU runs it in a real browser, sends the actual failures back, the revision
 * passes and is installed with a scoped file permission; a later instruction modifies the same application. What the
 * real local models do with the same task is measured separately (AuthoringAcceptanceTest).
 */
class SoftwareAuthorTest {
    private val dir = Files.createTempDirectory("clu-author").toFile().also { d -> Runtime.getRuntime().addShutdownHook(Thread { d.deleteRecursively() }) }
    private val paths = DesktopPaths(File(dir, "home"))
    private var host: RunningHost? = null
    private val http = HttpClient.newHttpClient()
    @AfterEach fun stop() { host?.stop() }

    private fun res(n: String) = File(listOf("src/test/resources/authoring", "desktop/src/test/resources/authoring").first { File(it).isDirectory }, n).readText()
    private fun app(search: Boolean, broken: Boolean): String {
        var html = res("explorer.html").replace("SEARCHBOX", if (search) "<input type=\"search\" data-action=\"search\" class=\"gx-search\" placeholder=\"Search\">" else "")
        if (broken) html = html.replace(" data-action=\"back\"", "")
        val js = res("explorer.js").replace("BACKHOOK", if (!search) "" else """const sb = document.querySelector("[data-action=search]");
  sb.addEventListener("keydown", async (e) => { if (e.key !== "Enter") return; try { const r = await kit.fs.search(sb.value); msg.textContent = r.entries.length ? r.entries.length + " found" : "Nothing found."; show(r.entries); } catch (x) { msg.textContent = x.message; } });""")
            .let { if (broken) it.replace("document.querySelector(\"[data-action=back]\").addEventListener", "(document.querySelector(\"[data-action=back]\") || document.body).addEventListener") else it }
        return "=== FILE: app.html ===\n$html\n=== FILE: app.css ===\n${res("explorer.css")}\n=== FILE: app.js ===\n$js\n=== END ==="
    }

    /** Answers like a model would: a flawed first draft, then a fix that uses the reported failures; search when it is required. */
    private inner class Scripted(val delayMs: Long = 0) : DevModel {
        val prompts = CopyOnWriteArrayList<String>()
        override val description = "scripted-test-model"; override val lastError: String? = null
        override suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Float, seed: Int, grammar: String?, reasoning: Boolean): String {
            prompts += user; Thread.sleep(delayMs)
            val search = user.contains("searches file and folder names")
            return when {
                !user.contains("Your current files") -> app(search, broken = true)
                user.contains("back returns to the parent") -> app(search, broken = false)
                else -> app(search, broken = false)
            }
        }
    }

    private fun req(path: String, method: String = "GET", body: String? = null): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create(host!!.url + path)).header("Authorization", "Bearer ${host!!.token}")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    private fun obj(path: String, method: String = "GET", body: String? = null) = CluJson.parseToJsonElement(req(path, method, body).body()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun say(text: String, wait: Boolean = true): JsonObject {
        val r = obj("/v1/capabilities/command", "POST", buildJsonObject { put("text", text) }.toString())
        val run = r["action"]?.jsonObject?.takeIf { it.s("action") == "develop" }?.s("run") ?: return r
        if (!wait) return buildJsonObject { put("run", run) }
        return waitRun(run)
    }
    private fun waitRun(run: String): JsonObject { val until = System.currentTimeMillis() + 300_000
        while (System.currentTimeMillis() < until) { val st = obj("/v1/capabilities/runs/$run"); if (st.s("state") != "running") return st; Thread.sleep(300) }
        fail("run $run did not finish") }

    private fun start(model: Scripted): File {
        host = Host.start(paths, HostConfig(), port = 0, log = {})
        host!!.api.capabilities!!.models.localOverride = model
        val owner = File(dir, "owner-files").apply { mkdirs(); File(this, "projects/clu").mkdirs(); File(this, "projects/clu/plan.md").writeText("plan"); File(this, "photos").mkdirs(); File(this, "todo.txt").writeText("x") }
        File(dir, "private").apply { mkdirs(); File(this, "secret.txt").writeText("no") }
        runCatching { Files.createSymbolicLink(File(owner, "escape").toPath(), File(dir, "private").toPath()) }
        assertEquals(200, req("/v1/capabilities/permissions", "PUT", buildJsonObject { putJsonArray("roots") { add(owner.path) } }.toString()).statusCode())
        return owner
    }

    @Test fun anApplicationIsWrittenRevisedFromRealFailuresInstalledWithScopedAccessAndModified() {
        assumeTrue(ExperienceVerifier.find() != null, "no browser")
        val model = Scripted(); val owner = start(model)
        val run = say("Give yourself a futuristic file explorer with a three-dimensional galaxy interface")
        assertEquals("installed", run.s("state"), run.toString().take(4000))
        val id = run.s("capability")
        assertEquals(2, model.prompts.size, "a broken first draft, then one revision: " + run["errors"].toString())
        assertTrue(model.prompts[1].contains("files: back returns to the parent") && model.prompts[1].contains("=== FILE: app.js ==="), "the model got the real failure and its own code back")
        assertTrue(run["errors"]!!.jsonArray.any { it.jsonPrimitive.content.contains("back") }, "the failed attempt is in the development memory")
        val checks = run["verification"]!!.jsonObject["checks"]!!.jsonArray.map { it.jsonObject.s("name") }
        listOf("files: a folder opens", "files: an empty folder is said to be empty", "files: a folder that cannot be opened shows an error and the app keeps working", "3D: dragging turns the scene")
            .forEach { assertTrue(it in checks, "$it in $checks") }
        // Installed, served, permitted to read exactly the owner's permitted folder — and nothing outside it.
        assertEquals(200, req("/x/$id/1/index.html").statusCode())
        assertEquals(owner.canonicalPath, obj("/v1/capabilities/permissions")["grants"]!!.jsonObject[id]!!.jsonPrimitive.content)
        val top = obj("/v1/apps/$id/fs/list?path=")["entries"]!!.jsonArray.map { it.jsonObject.s("name") }
        assertTrue(top.containsAll(listOf("projects", "photos", "todo.txt")), top.toString())
        assertEquals(403, req("/v1/apps/$id/fs/list?path=..").statusCode())
        assertEquals(403, req("/v1/apps/$id/fs/list?path=%2Fetc").statusCode())
        if (File(owner, "escape").exists()) assertEquals(403, req("/v1/apps/$id/fs/list?path=escape").statusCode(), "a link out of the folder is refused")
        assertEquals(403, req("/v1/apps/weather-scifi/fs/list?path=").statusCode(), "an application without the permission gets nothing")

        // "Add a search function to your galaxy file explorer": the same application, modified.
        val before = model.prompts.size
        val v2 = say("Add a search function to your futuristic file explorer")
        assertEquals("installed", v2.s("state"), v2.toString().take(4000))
        assertEquals(id, v2.s("capability")); assertEquals(2, v2["version"]!!.jsonPrimitive.int)
        assertTrue(model.prompts[before].contains("searches file and folder names") && model.prompts[before].contains("CluApp"), "the model was given its installed code to modify")
        assertTrue(v2["verification"]!!.jsonObject["checks"]!!.jsonArray.any { it.jsonObject.s("name") == "search: finds a file deep in the folder tree" && it.jsonObject["ok"]!!.jsonPrimitive.boolean })
        assertEquals(1, CluJson.parseToJsonElement(req("/v1/capabilities?kind=app").body()).jsonArray.size, "no duplicate explorer")
        assertEquals("list", say("Show me all the applications you have developed")["action"]!!.jsonObject.s("action"))
        assertEquals(id, say("Open the futuristic file explorer")["action"]!!.jsonObject["capability"]!!.jsonObject.s("id"))

        // Restart: still installed and usable.
        host!!.stop(); host = Host.start(paths, HostConfig(), port = 0, log = {})
        val after = CluJson.parseToJsonElement(req("/v1/capabilities").body()).jsonArray.map { it.jsonObject }.single { it.s("id") == id }
        assertTrue(after["installed"]!!.jsonPrimitive.boolean && after["verified"]!!.jsonPrimitive.boolean); assertEquals(2, after["version"]!!.jsonPrimitive.int)
        assertEquals(200, req("/x/$id/2/index.html").statusCode()); assertEquals(200, req("/v1/apps/$id/fs/search?q=plan").statusCode())
    }

    @Test fun aRunCanBePausedAndResumed() {
        assumeTrue(ExperienceVerifier.find() != null, "no browser")
        val model = Scripted(delayMs = 1500); start(model)
        val run = say("Give yourself a futuristic file explorer with a three-dimensional galaxy interface", wait = false).s("run")
        while (model.prompts.isEmpty()) Thread.sleep(100)
        assertEquals(200, req("/v1/capabilities/runs/$run/pause", "POST", "{}").statusCode())
        val paused = waitRun(run)
        assertEquals("paused", paused.s("state"), paused.toString().take(2000))
        assertEquals(200, req("/v1/capabilities/runs/$run/resume", "POST", "{}").statusCode())
        val done = waitRun(run)
        assertEquals("installed", done.s("state"), done.toString().take(3000))
        assertTrue(model.prompts.last().contains("Your current files"), "resuming continued from the paused workspace")
    }

    @Test fun withoutAnyModelCluSaysSoInsteadOfPretending() {
        host = Host.start(paths, HostConfig(), port = 0, log = {})
        val r = say("Build yourself a pomodoro timer with a holographic interface")
        assertEquals("failed", r.s("state")); assertTrue(r.s("message").contains("model"), r.s("message"))
        assertEquals(0, CluJson.parseToJsonElement(req("/v1/capabilities").body()).jsonArray.size)
    }
}
