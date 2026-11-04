package ai.clu.desktop

import ai.clu.core.evolution.DevModel
import ai.clu.core.tools.HttpFetcher
import ai.clu.core.tools.HttpResponse
import ai.clu.desktop.capability.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** The decisions inside capability development, each on its own: design validation, fixes, build checks, model consent, data honesty. */
class CapabilityUnitTest {
    private class Scripted(val answer: String?) : DevModel {
        override val description = "scripted"; override val lastError: String? = null
        override suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Float, seed: Int, grammar: String?, reasoning: Boolean) = answer
    }
    private fun advise(answer: String?, palette: Boolean = false, taken: List<String> = emptyList()) =
        runBlocking { DesignAdvisor(Scripted(answer)).advise("map", "Ocean", listOf("world"), palette, taken) }

    @Test fun modelDesignProposalsAreValidatedNotTrusted() {
        advise("""{"name": "Tidewatch Atlas", "tagline": "Charts of the world's seas and shores.", "bg": "#04223a", "panel": "#0a3150", "ink": "#e6f6ff", "accent": "#36d1c4", "font": "serif"}""", palette = true).let {
            assertEquals("Tidewatch Atlas", it.name); assertNotNull(it.design); assertEquals("#04223a", it.design!!.bg); assertTrue(it.rejected.isEmpty(), it.rejected.toString()) }
        // What Qwen3-0.6B actually answered in the acceptance run: "CLU" for both maps.
        advise("""{"name": "CLU", "tagline": "A map.", "bg": "#000000", "panel": "#111111", "ink": "#ffffff", "accent": "#00ff00", "font": "mono"}""").let {
            assertNull(it.name); assertTrue(it.rejected.any { r -> r.contains("too generic") }, it.rejected.toString()) }
        advise("""{"name": "CLU Map", "tagline": "Maps for CLU users.", "bg": "#000000", "panel": "#111111", "ink": "#ffffff", "accent": "#00ff00", "font": "mono"}""").let { assertNull(it.name) }
        advise("""{"name": "Tidewatch Atlas", "tagline": "Charts of the seas.", "bg": "#000000", "panel": "#111111", "ink": "#ffffff", "accent": "#00ff00", "font": "mono"}""", taken = listOf("tidewatch atlas")).let {
            assertNull(it.name); assertTrue(it.rejected.any { r -> r.contains("already used") }) }
        advise("""{"name": "Sea Charts", "tagline": "<script>alert(1)</script>", "bg": "blue", "panel": "#111111", "ink": "#ffffff", "accent": "#00ff00", "font": "mono"}""", palette = true).let {
            assertEquals("Sea Charts", it.name); assertNull(it.tagline); assertNull(it.design); assertTrue(it.rejected.any { r -> r.startsWith("palette") }) }
        advise("I think a nice map would be good").let { assertNull(it.name); assertEquals(listOf("the answer was not JSON"), it.rejected) }
        advise(null).let { assertNull(it.name); assertTrue(it.rejected.single().startsWith("the model gave no answer")) }
        runBlocking { DesignAdvisor(null).advise("map", "Ocean", listOf("world"), true) }.let { assertEquals(listOf("no development model available"), it.rejected) }
    }

    @Test fun designsWithUnreadableTextAreFixed() {
        val bad = Styles.known.getValue("classic").copy(ink = "#e0e0e0", accent = "#f0f0f0")
        assertTrue(Styles.problems(bad).isNotEmpty())
        val fixed = Styles.fixContrast(bad)
        assertTrue(Styles.problems(fixed).isEmpty(), Styles.problems(fixed).toString())
        Styles.known.forEach { (k, d) -> assertTrue(Styles.problems(d).isEmpty(), "$k: ${Styles.problems(d)}") }
        listOf("ocean", "steampunk", "sunny-beach", "x").forEach { s -> assertTrue(Styles.problems(Styles.derived(s)).isEmpty(), "$s: ${Styles.problems(Styles.derived(s))}") }
        assertEquals(Styles.derived("ocean"), Styles.derived("ocean"), "the same words give the same look")
    }

    @Test fun generatedSourcePassesTheBuildCheckAndBrokenSourceDoesNot() {
        val dir = Files.createTempDirectory("gen").toFile()
        val store = CapabilityStore(JdbcSqlDriver(File(dir, "c.db").absolutePath))
        val host = Host.start(DesktopPaths(File(dir, "h")), HostConfig(), port = 0, log = {})
        try { generated(dir, CapabilityDeveloper(store, KitServices(host.core, NoHttp, File(dir, "tiles")), ExperienceVerifier(scratch = dir), null, File(dir, "caps"), { "" }, { "" })) }
        finally { host.stop() }
    }

    private fun generated(dir: File, dev: CapabilityDeveloper) {
        for (kind in listOf("weather", "map", "dashboard")) for (style in Styles.known.keys) {
            val out = File(dir, "$kind-$style")
            val bp = Blueprint(kind, style, "Test", "Tagline", ExperienceCatalog.family(kind)!!.base + ExperienceCatalog.family(kind)!!.improvements.map { it.first }, Styles.known.getValue(style), labels = ExperienceGenerator.labels(kind, style))
            ExperienceGenerator.write(out, "$kind-$style", 1, bp)
            assertEquals(emptyList(), dev.buildCheck(out), "$kind/$style")
        }
        val out = File(dir, "map-classic")
        File(out, "index.html").appendText("<script>alert(1)</script><img src=\"https://evil.example/x.png\">")
        val problems = dev.buildCheck(out)
        assertTrue(problems.any { it.contains("inline script") } && problems.any { it.contains("outside CLU") }, problems.toString())
        File(out, "theme.css").delete(); assertTrue(dev.buildCheck(out).contains("theme.css missing"))
        // Diagnosis: a broken new feature is removed, an invisible texture is dropped, unknown failures are not "fixed".
        val bp = Blueprint("map", "fantasy", "T", "T", ExperienceCatalog.family("map")!!.base + "measure", Styles.known.getValue("fantasy"))
        assertEquals("none", dev.diagnose(listOf(Verification.Check("screenshot: the Fantasy look is what is on screen", false)), bp)!!.first.design.texture)
        assertFalse("measure" in dev.diagnose(listOf(Verification.Check("map: measuring", false)), bp)!!.first.features)
        assertNull(dev.diagnose(listOf(Verification.Check("CLU session", false)), bp.copy(features = ExperienceCatalog.family("map")!!.base)))
    }

    private object NoHttp : HttpFetcher {
        override suspend fun get(url: String, headers: Map<String, String>) = throw java.io.IOException("offline")
        override suspend fun postJson(url: String, json: String, headers: Map<String, String>) = throw java.io.IOException("offline")
    }

    @Test fun remoteModelsNeedThreeExplicitConsentsAndNothingIsSentBefore() {
        val dir = Files.createTempDirectory("dm").toFile()
        val host = Host.start(DesktopPaths(File(dir, "h")), HostConfig(), port = 0, log = {})
        try {
            val store = CapabilityStore(JdbcSqlDriver(File(dir, "c.db").absolutePath))
            var calls = 0
            val http = object : HttpFetcher {
                override suspend fun get(url: String, headers: Map<String, String>): HttpResponse { calls++; return HttpResponse(200, "{}") }
                override suspend fun postJson(url: String, json: String, headers: Map<String, String>): HttpResponse { calls++
                    return HttpResponse(200, """{"choices":[{"message":{"content":"{\"name\":\"Tidewatch\"}"}}]}""") }
            }
            val models = DevModels(host.core, store, http, null) { "key" }
            assertNull(models.choose("design-json")); assertEquals("remote models are off (the default)", models.remoteAllowed())
            store.setPref("dev.remote.enabled", "true"); store.setPref("dev.remote.url", "https://ollama.com/v1"); store.setPref("dev.remote.model", "gpt-oss:20b")
            assertNull(models.choose("design-json"), "enabled alone is not enough"); assertTrue(models.remoteAllowed()!!.contains("free"))
            store.setPref("dev.remote.free", "true")
            assertNull(models.choose("design-json")); assertTrue(models.remoteAllowed()!!.contains("development context"))
            assertEquals(0, calls, "nothing was sent while any consent was missing")
            store.setPref("dev.remote.share", "true")
            val c = models.choose("design-json")!!; assertTrue(c.remote)
            assertEquals("""{"name":"Tidewatch"}""", runBlocking { c.model.complete("s", "u", 10, 0f, 1, null, false) }); assertEquals(1, calls)
            // Measured results decide, not size: a local model that keeps failing loses to one that passes.
            val both = DevModels(host.core, store, http, null) { "key" }.apply { localOverride = Scripted("x") }
            repeat(3) { store.recordModel(both.candidates().first { !it.remote }.id, "design-json", false, 100) }
            store.recordModel(both.candidates().first { it.remote }.id, "design-json", true, 900)
            assertTrue(both.choose("design-json")!!.remote)
        } finally { host.stop() }
    }

    @Test fun weatherIsNeverInventedAndStaleDataIsLabelled() {
        val dir = Files.createTempDirectory("wx").toFile()
        val host = Host.start(DesktopPaths(File(dir, "h")), HostConfig(), port = 0, log = {})
        try {
            var up = true; var t = 1_000_000L
            val http = object : HttpFetcher {
                override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = if (up) HttpResponse(200, OpenMeteoStandIn.forecast()) else throw java.io.IOException("Connection refused")
                override suspend fun postJson(url: String, json: String, headers: Map<String, String>) = throw UnsupportedOperationException()
            }
            val kit = KitServices(host.core, http, File(dir, "tiles")) { t }
            val first = kit.weather(51.5, -0.12, "London")
            assertTrue(first["ok"]!!.jsonPrimitive.boolean); assertEquals(13.4, first["current"]!!.jsonObject["temperature"]!!.jsonPrimitive.double); assertEquals(5, first["daily"]!!.jsonArray.size)
            up = false; t += 11 * 60_000
            val stale = kit.weather(51.5, -0.12, "London")
            assertTrue(stale["stale"]!!.jsonPrimitive.boolean); assertTrue(stale["reason"]!!.jsonPrimitive.content.contains("could not be reached")); assertEquals(1_000_000L, stale["fetchedAt"]!!.jsonPrimitive.long)
            val none = kit.weather(-33.9, 151.2, "Sydney")
            assertFalse(none["ok"]!!.jsonPrimitive.boolean); assertNull(none["current"], "no figures without data")
            // Offline gazetteer.
            assertEquals("GB", kit.places("London").first().country); assertEquals("FR", kit.places("paris").first().country)
            assertTrue(kit.places("São Paulo").isNotEmpty() && kit.places("Sao Paulo").isNotEmpty(), "accents optional")
            assertEquals("CA", kit.places("London, CA").first().country); assertEquals("Tokyo", kit.near(35.68, 139.69)!!.name)
            assertTrue(kit.places("x").isEmpty())
        } finally { host.stop() }
    }

    @Test fun mechanicalMistakesAreFixedSoTheModelsLogicGetsTested() {
        val (fixed, notes) = AppScaffold.autofix(mapOf("app.html" to "<div id=\"x\" onclick=\"go()\">hi</div>\n<script>\nfunction go() {}\n</script>", "app.js" to "window.CluApp = { mount: async function (kit) {} };", "app.css" to ""))
        assertEquals(2, notes.size, notes.toString())
        assertFalse(fixed["app.html"]!!.contains("<script") || fixed["app.html"]!!.contains("onclick"))
        assertTrue(fixed["app.js"]!!.contains("function go()") && fixed["app.js"]!!.contains("CluApp"))
        assertTrue(AppScaffold.check(fixed).isEmpty(), AppScaffold.check(fixed).toString())
        assertEquals(emptyList(), AppScaffold.autofix(mapOf("app.html" to "<p>ok</p>", "app.js" to "x")).second)
    }

    @Test fun theRegistryKeepsVersionsHistoryAndPreferences() {
        val dir = Files.createTempDirectory("reg").toFile()
        val db = File(dir, "c.db").absolutePath
        val s = CapabilityStore(JdbcSqlDriver(db))
        val a = s.create("map", "tactical", "Tactical Map", "d", "navigation", emptyList(), listOf("x"))
        val b = s.create("map", "tactical", "Tactical Map 2", "d", "navigation", emptyList(), emptyList())
        assertNotEquals(a.id, b.id, "a second tactical map gets its own id")
        val bp = Blueprint("map", "tactical", "Tactical Map", "t", listOf("world"), Styles.known.getValue("tactical"))
        s.addVersion(CapVersion(a.id, 1, "installed/${a.id}/1", listOf(CapFile("index.html", "00", 1)), bp, Verification(true, listOf(Verification.Check("x", true))), true, 1, "r1", ""))
        s.addVersion(CapVersion(a.id, 2, "installed/${a.id}/2", emptyList(), bp.copy(features = listOf("world", "measure")), null, false, 2, "r2", ""))
        s.update(a.id, activeVersion = 2); s.setPref("default.map", a.id); s.event(a.id, "default", "set")
        val again = CapabilityStore(JdbcSqlDriver(db))
        assertEquals(2, again.get(a.id)!!.activeVersion); assertEquals(a.id, again.defaultFor("map")); assertEquals(3, again.nextVersion(a.id))
        assertEquals(listOf(true, false), again.versions(a.id).map { it.verified }); assertEquals(listOf("measure"), again.active(a.id)!!.blueprint.features - "world")
        assertEquals(listOf("created", "default"), again.events(a.id).map { it.kind })
    }
}
