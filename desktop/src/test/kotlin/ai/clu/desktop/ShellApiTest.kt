package ai.clu.desktop

import ai.clu.core.memory.ProposalKind
import ai.clu.core.util.CluJson
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.*

/** The endpoints the desktop shell (M3) uses: launch codes, static assets, archive, suggestions, settings and host config. */
class ShellApiTest {
    private val dir = Files.createTempDirectory("clu-shell").toFile().also { d -> Runtime.getRuntime().addShutdownHook(Thread { d.deleteRecursively() }) }
    private val paths = DesktopPaths(File(dir, "home"))
    private val host = Host.start(paths, HostConfig(), port = 0, log = {})
    private val http = HttpClient.newHttpClient()

    @AfterEach fun stop() = host.stop()

    private fun req(path: String, method: String = "GET", body: String? = null, token: String? = host.token, headers: Map<String, String> = emptyMap(),
                    bytes: ByteArray? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create(host.url + path))
        token?.let { b.header("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> b.header(k, v) }
        val pub = when { bytes != null -> HttpRequest.BodyPublishers.ofByteArray(bytes); body != null -> HttpRequest.BodyPublishers.ofString(body); else -> HttpRequest.BodyPublishers.noBody() }
        return http.send(b.method(method, pub).build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun obj(r: HttpResponse<String>) = CluJson.parseToJsonElement(r.body()).jsonObject
    private fun arr(r: HttpResponse<String>) = CluJson.parseToJsonElement(r.body()).jsonArray
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun launchCodeIsSingleUseAndExpires() {
        val code = host.api.issueLaunchCode()
        val first = req("/v1/session", "POST", """{"code":"$code"}""", token = null)
        assertEquals(200, first.statusCode()); assertEquals(host.token, obj(first).s("token"))
        assertEquals(401, req("/v1/session", "POST", """{"code":"$code"}""", token = null).statusCode(), "a code works once")
        assertEquals(401, req("/v1/session", "POST", """{"code":"guessed-code"}""", token = null).statusCode())
        assertEquals(401, req("/v1/session", "POST", "not json", token = null).statusCode())
        val stale = host.api.issueLaunchCode(ttlMs = -1)
        assertEquals(401, req("/v1/session", "POST", """{"code":"$stale"}""", token = null).statusCode(), "an expired code is refused")
    }

    @Test fun shellAssetsAreServedWithAStrictPolicyAndNoSecrets() {
        val page = req("/", token = null)
        assertEquals(200, page.statusCode())
        assertTrue(page.headers().firstValue("Content-Type").get().startsWith("text/html"))
        val csp = page.headers().firstValue("Content-Security-Policy").orElse("")
        assertTrue("script-src 'self'" in csp && "frame-ancestors 'none'" in csp, csp)
        assertFalse(host.token in page.body(), "the page never carries the token")
        for (asset in listOf("shell.js", "shell.css", "face.js")) assertEquals(200, req("/shell/$asset", token = null).statusCode(), asset)
        assertEquals(404, req("/shell/missing.js", token = null).statusCode())
        assertEquals(404, req("/shell/..%2F..%2Fhost.json", token = null).statusCode(), "no way out of the shell folder")
        assertEquals(404, req("/shell/%2e%2e/token", token = null).statusCode())
        assertFalse(host.token in req("/shell/shell.js", token = null).body())
    }

    @Test fun memoriesKeepTheOwnersDateAndCanBeEditedAndDeleted() {
        val created = req("/v1/memories", "POST", """{"title":"Met Riya at the college fest","content":"We talked about robots.","when":"around March 2018","people":"Riya","places":"Pune"}""")
        assertEquals(201, created.statusCode(), created.body())
        val m = obj(created)["memory"]!!.jsonObject
        assertTrue("2018" in m.s("when") && "March" in m.s("when"), m.toString())
        assertEquals("2018-03-01", m.s("sortDate"))
        assertEquals(listOf("Riya"), m["people"]!!.jsonArray.map { it.jsonPrimitive.content })
        val id = m.s("id")

        assertEquals(400, req("/v1/memories", "POST", """{"title":"x","when":"the blue Tuesday of nothing"}""").statusCode(), "an unreadable date is refused, never guessed")
        val unknown = obj(req("/v1/memories", "POST", """{"title":"Old photo","when":""}"""))["memory"]!!.jsonObject
        assertEquals("", unknown.s("when"), "no date stays unknown; it is never today")

        val found = arr(req("/v1/memories?q=" + "college fest".replace(" ", "+")))
        assertTrue(found.any { it.jsonObject.s("id") == id })

        val edited = req("/v1/memories/$id", "PUT", """{"when":"12 March 2018"}""")
        assertEquals(200, edited.statusCode(), edited.body())
        assertEquals("Met Riya at the college fest", obj(edited)["memory"]!!.jsonObject.s("title"), "fields not sent are kept")
        assertEquals("2018-03-12", obj(edited)["memory"]!!.jsonObject.s("sortDate"))

        req("/v1/memories", "POST", """{"title":"First job","when":"2016"}""")
        val timeline = arr(req("/v1/timeline")).map { it.jsonObject.s("title") }
        assertEquals(listOf("First job", "Met Riya at the college fest"), timeline, "dated only, oldest first")

        assertEquals(200, req("/v1/memories/$id", "DELETE").statusCode())
        assertEquals(404, req("/v1/memories/$id", "DELETE").statusCode())
        assertTrue(arr(req("/v1/memories")).none { it.jsonObject.s("id") == id })
    }

    @Test fun dateReadingForTheEditor() {
        val r = obj(req("/v1/dates/parse?q=" + java.net.URLEncoder.encode("between 2018 and 2019", "UTF-8")))
        assertEquals(true, r["understood"]!!.jsonPrimitive.boolean); assertTrue("2018" in r.s("label") && "2019" in r.s("label"), r.toString())
        assertEquals(false, obj(req("/v1/dates/parse?q=gibberish+words"))["understood"]!!.jsonPrimitive.boolean)
        val empty = obj(req("/v1/dates/parse?q="))
        assertEquals(true, empty["understood"]!!.jsonPrimitive.boolean); assertEquals("", empty.s("label"))
    }

    @Test fun peopleReportPossibleDuplicatesWithoutMerging() {
        assertEquals(201, req("/v1/people", "POST", """{"name":"Riya Sharma","relation":"friend","aliases":"Riya"}""").statusCode())
        val second = obj(req("/v1/people", "POST", """{"name":"Riya Sharma"}"""))
        assertTrue(second["possibleDuplicates"]!!.jsonArray.isNotEmpty())
        assertEquals(2, arr(req("/v1/people")).size, "kept separate: merging is the owner's decision")
        assertEquals(400, req("/v1/people", "POST", """{"name":"  "}""").statusCode())
    }

    @Test fun documentsUploadSearchAndRefusals() {
        val text = "Minutes of the robotics club. We chose the name Hexapod for the walking robot.".toByteArray()
        val up = req("/v1/documents", "POST", bytes = text, headers = mapOf("X-File-Name" to "club%20notes.txt"))
        assertEquals(201, up.statusCode(), up.body()); assertEquals("indexed", obj(up).s("textStatus"))
        assertEquals(409, req("/v1/documents", "POST", bytes = text, headers = mapOf("X-File-Name" to "copy.txt")).statusCode(), "the same file twice is refused")
        assertEquals(422, req("/v1/documents", "POST", bytes = byteArrayOf(0x4d, 0x5a, 0, 1), headers = mapOf("X-File-Name" to "setup.exe")).statusCode())
        assertEquals(400, req("/v1/documents", "POST", bytes = text).statusCode(), "needs a file name")
        assertEquals("club notes.txt", arr(req("/v1/documents")).single().jsonObject.s("name"))
        val hits = arr(req("/v1/documents/search?q=Hexapod"))
        assertTrue(hits.single().jsonObject.s("text").contains("Hexapod"))
    }

    @Test fun suggestionsAreSavedOnlyWhenApproved() {
        val keep = host.core.proposals.propose(ProposalKind.OBSERVATION, "Prefers tea in the evening.", "said so twice")!!
        val drop = host.core.proposals.propose(ProposalKind.OBSERVATION, "Dislikes Mondays.", "a guess")!!
        assertEquals(2, obj(req("/v1/status"))["pendingSuggestions"]!!.jsonPrimitive.int)
        assertEquals(2, arr(req("/v1/suggestions")).size)
        val before = host.core.memories.list(null, null).size

        assertEquals(200, req("/v1/suggestions/${drop.id}/reject", "POST", "{}").statusCode())
        assertEquals(before, host.core.memories.list(null, null).size, "rejecting saves nothing")
        val ok = req("/v1/suggestions/${keep.id}/approve", "POST", """{"text":"Prefers green tea in the evening."}""")
        assertEquals(200, ok.statusCode(), ok.body())
        assertTrue(host.core.memories.list(null, null).any { it.content.contains("green tea") }, "approved with the owner's edit")
        assertEquals(0, arr(req("/v1/suggestions")).size)
        assertEquals(409, req("/v1/suggestions/${keep.id}/approve", "POST", "{}").statusCode(), "cannot approve twice")
    }

    @Test fun settingsAreListedAndChangedThroughTheCoreRules() {
        val s = obj(req("/v1/settings"))
        val all = s["settings"]!!.jsonArray.map { it.jsonObject }
        assertTrue(s["categories"]!!.jsonArray.isNotEmpty())
        val unload = all.single { it.s("key") == "model.autoUnloadMin" }
        assertEquals("int", unload["type"]!!.jsonObject.s("kind"))

        val ok = req("/v1/settings", "PUT", """{"key":"model.autoUnloadMin","value":5}""")
        assertEquals(200, ok.statusCode(), ok.body()); assertEquals(5, obj(ok)["value"]!!.jsonPrimitive.int)
        assertEquals(400, req("/v1/settings", "PUT", """{"key":"model.autoUnloadMin","value":100000}""").statusCode(), "out of range")
        assertEquals(404, req("/v1/settings", "PUT", """{"key":"no.such","value":1}""").statusCode())
        val sensitive = all.first { it["sensitive"]!!.jsonPrimitive.boolean }
        assertEquals(403, req("/v1/settings", "PUT", """{"key":"${sensitive.s("key")}","value":"http://example.com"}""").statusCode(), "sensitive settings need verification")
    }

    @Test fun hostConfigChangesApplyToTheGovernorAndPersist() {
        val fresh = obj(req("/v1/host-config"))
        assertEquals(16.0, fresh["memoryBudgetGB"]!!.jsonPrimitive.double, "defaults are included, so the form isn't empty on a new install")
        assertEquals(2.0, fresh["reserveGB"]!!.jsonPrimitive.double)
        assertEquals(400, req("/v1/host-config", "PUT", """{"noSuchField":1}""").statusCode(), "unknown fields are refused")
        val r = req("/v1/host-config", "PUT", """{"memoryBudgetGB":8,"reserveGB":3}""")
        assertEquals(200, r.statusCode(), r.body())
        assertEquals(8 * 1024L, obj(r)["governor"]!!.jsonObject["budgetMB"]!!.jsonPrimitive.long)
        assertEquals(8 * 1024L, obj(req("/v1/status"))["governor"]!!.jsonObject["budgetMB"]!!.jsonPrimitive.long, "the running governor uses it")
        assertEquals(8.0, HostConfig.load(paths.config).memoryBudgetGB, "saved for the next start")
        assertEquals(400, req("/v1/host-config", "PUT", """{"memoryBudgetGB":-1}""").statusCode())
        assertEquals(8.0, obj(req("/v1/host-config"))["memoryBudgetGB"]!!.jsonPrimitive.double, "a refused change leaves the config as it was")
    }

    @Test fun shellOpenNeedsTheTokenAndALauncher() {
        assertEquals(401, req("/v1/shell/open", "POST", "{}", token = null).statusCode())
        var opened = 0
        host.api.openShell = { opened++ }
        assertEquals(200, req("/v1/shell/open", "POST", "{}").statusCode()); assertEquals(1, opened)
        host.api.openShell = null
        assertEquals(409, req("/v1/shell/open", "POST", "{}").statusCode())
    }

    /** The UI contract: the shell is a client of the API and nothing else (no other origin, no local database). */
    @Test fun theShellOnlyTalksToTheApi() {
        val js = listOf("shell.js", "face.js").associateWith { javaClass.getResource("/shell/$it")!!.readText() }
        val all = js.values.joinToString("\n") + javaClass.getResource("/shell/index.html")!!.readText()
        for (banned in listOf("indexedDB", "localStorage", "openDatabase", "WebSocket", "XMLHttpRequest", "eval(", "new Function", "innerHTML", "outerHTML", "insertAdjacentHTML", "document.write"))
            assertFalse(banned in all, "the shell must not use $banned")
        assertFalse(Regex("(src|href)=\"(https?:)?//").containsMatchIn(all), "no assets from other origins")
        assertFalse(Regex("\\bon[a-z]+=\"").containsMatchIn(all), "no inline handlers (the CSP forbids them anyway)")
        val shell = js.getValue("shell.js")
        val fetches = Regex("fetch\\(\\s*([^,)]+)").findAll(shell).map { it.groupValues[1].trim() }.toList()
        assertTrue(fetches.isNotEmpty())
        fetches.forEach { assertTrue(it == "path" || it.matches(Regex("[\"`]/v1/.*")), "fetch target must be an API path on this origin: $it") }
        val apiCalls = Regex("api\\(\\s*([\"`][^\"`]*)").findAll(shell).map { it.groupValues[1].drop(1) }.toList()
        assertTrue(apiCalls.size > 20 && apiCalls.all { it.startsWith("/v1/") }, apiCalls.filterNot { it.startsWith("/v1/") }.toString())
        assertFalse(Regex("sessionStorage\\.setItem\\(\"(?!clu-token\")").containsMatchIn(shell), "only the session token is kept, and only for this window")
    }
}
