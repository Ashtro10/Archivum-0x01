package ai.clu.desktop.capability

import ai.clu.core.util.SafeDelete
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** A development instruction after it has been understood: what to do, to which family, in which style. */
data class DevRequest(
    val op: String, val kind: String, val style: String?, val details: String, val text: String, val variant: Boolean,
    val conversationId: String?, val targetCapId: String? = null,
)

/**
 * CLU's development workflow for its own capabilities. Each run, in its own workspace folder:
 *
 * 1. understand the request and list the requirements; 2. look for an existing capability (improve it, add a variant,
 * or say it already exists); 3. consult the development memory of earlier runs (their fixes and limitations);
 * 4. research: which free data sources are reachable now, whether a browser is available to verify with;
 * 5. plan (features, look; the development model proposes a name and, for new styles, a palette, which is validated);
 * 6. list the modules and integration points; 7. write the experience; 8. build-check its files; 9. test it in a real
 * browser against CLU's live services; 10. inspect the screenshot; 11. diagnose failures and apply a fix, then test
 * again (bounded); 12. check its integration (dependencies, served by CLU, listed in the registry); 13. install it as a
 * new verified version (earlier versions kept for rollback); 14. report what was made and how to use it.
 *
 * A version that fails verification is never installed. What cannot be verified here (live data while a provider is
 * unreachable) is recorded as a limitation, never as a pass.
 */
class CapabilityDeveloper(
    private val store: CapabilityStore,
    private val kit: KitServices,
    private val verifier: ExperienceVerifier,
    private val models: DevModels?,
    val root: File,
    /** The base URL of CLU's API on this computer (e.g. http://127.0.0.1:8765). */
    private val baseUrl: () -> String,
    /** A one-time launch code, so the verifying browser gets a session the way CLU's window does. */
    private val launchCode: () -> String,
    private val reply: (conversationId: String, text: String) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = {},
) {
    val installed = File(root, "installed")
    val workspaces = File(root, "workspaces")
    val evidence = File(root, "evidence")
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "clu-capability-dev").apply { isDaemon = true } }
    @Volatile var current: String? = null; private set

    fun start(req: DevRequest): DevRun {
        val now = System.currentTimeMillis()
        val run = DevRun(store.newId(), req.text, req.op, req.kind, req.style ?: "", req.targetCapId, DevRun.RUNNING, emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), null, 0, emptyList(), now, 0, req.conversationId, "Started")
        store.saveRun(run)
        pool.submit { current = run.id; try { execute(run, req) } catch (e: Throwable) { fail(run.id, "internal error: ${e::class.simpleName}: ${e.message}") } finally { current = null } }
        return run
    }

    /** Runs synchronously (tests, CLI). */
    fun develop(req: DevRequest): DevRun { val r = start(req); while (store.run(r.id)?.state == DevRun.RUNNING) Thread.sleep(100); return store.run(r.id)!! }

    // ------------------------------------------------------------------ run state helpers
    private class Notes { val req = mutableListOf<String>(); val research = mutableListOf<String>(); val decisions = mutableListOf<String>(); val files = mutableListOf<String>()
        val models = mutableListOf<String>(); val errors = mutableListOf<String>(); val fixes = mutableListOf<String>(); val limits = mutableListOf<String>() }
    private fun save(id: String, n: Notes, state: String = DevRun.RUNNING, capId: String? = null, version: Int = 0, ver: Verification? = null, message: String = "") {
        val r = store.run(id) ?: return
        store.saveRun(r.copy(state = state, capId = capId ?: r.capId, requirements = n.req.toList(), research = n.research.toList(), decisions = n.decisions.toList(), files = n.files.toList(),
            models = n.models.toList(), errors = n.errors.toList(), fixes = n.fixes.toList(), verification = ver ?: r.verification, version = if (version > 0) version else r.version,
            limitations = n.limits.toList(), finishedAt = if (state == DevRun.RUNNING) 0 else System.currentTimeMillis(), message = message.ifBlank { r.message }))
    }
    private fun fail(id: String, why: String) {
        val r = store.run(id) ?: return
        store.step(id, "report", "failed", why)
        store.saveRun(r.copy(state = DevRun.FAILED, errors = r.errors + why, finishedAt = System.currentTimeMillis(), message = why))
        r.conversationId?.let { reply(it, why) }
    }
    private fun finish(id: String, n: Notes, state: String, message: String, capId: String? = null, version: Int = 0, ver: Verification? = null) {
        store.step(id, "report", if (state == DevRun.INSTALLED) "done" else state, message)
        save(id, n, state, capId, version, ver, message)
        store.run(id)?.conversationId?.let { reply(it, message) }
    }

    private fun execute(run: DevRun, req: DevRequest) {
        val id = run.id; val n = Notes()
        fun step(name: String, status: String, detail: String = "") { store.step(id, name, status, detail); log("capability dev $id: $name $status ${detail.take(160)}") }

        // 1. Understand.
        val family = ExperienceCatalog.family(req.kind)
        if (family == null) {
            step("understand", "declined", "unknown family '${req.kind}'")
            return finish(id, n, DevRun.DECLINED, "I can't build a ${req.kind} yet. Right now I know how to develop weather services, maps and dashboards that combine them, " +
                "in any style you like. I've noted the request.")
        }
        val target = req.targetCapId?.let { store.get(it) }
        val improving = req.op == "IMPROVE" && target != null
        val style = (req.style ?: target?.style ?: when (req.kind) { "dashboard" -> store.defaultFor("map")?.let { store.get(it)?.style } ?: "scifi"; else -> "classic" }).lowercase()
        n.req += "${family.title} capability in the ${Styles.title(style)} style"
        family.base.forEach { n.req += ExperienceCatalog.FEATURE_TEXT[it] ?: it }
        if (req.details.isNotBlank()) n.req += "owner asked: ${req.details}"
        n.req += "works offline as far as possible and says plainly what is unavailable"
        n.req += "reachable from CLU's chat and the Capabilities view; remembers its settings"
        step("understand", "done", n.req.joinToString("; ")); save(id, n)

        // 2. Existing capabilities.
        val same = store.all().filter { it.kind == req.kind }
        val uses = mutableListOf<String>()
        when {
            improving -> n.decisions += "improve ${target!!.name} (${target.id}): a new version; v${target.activeVersion} stays available for rollback"
            req.op == "CREATE" && same.any { it.style == style } && !req.variant -> {
                val existing = same.first { it.style == style }
                step("existing", "done", "already have ${existing.id}")
                return finish(id, n, DevRun.DECLINED, "You already have ${existing.name} (v${existing.activeVersion}). Say \"open ${existing.name}\" to use it, \"improve my ${family.title.lowercase()}\" " +
                    "to update it, or ask for another one in a different style.", capId = existing.id)
            }
            same.isNotEmpty() -> n.decisions += "new ${Styles.title(style)} variant next to ${same.joinToString { it.name }} (they stay unchanged)"
            else -> n.decisions += "first ${family.title.lowercase()} capability"
        }
        if (family.requires.isNotEmpty()) {
            val missing = mutableListOf<String>()
            for (k in family.requires) {
                val dep = store.defaultFor(k)?.let { store.get(it) }?.takeIf { it.enabled } ?: store.all().firstOrNull { it.kind == k && it.enabled }
                if (dep == null) missing += k else uses += dep.id
            }
            if (missing.isNotEmpty()) { step("existing", "declined", "missing ${missing.joinToString()}")
                return finish(id, n, DevRun.DECLINED, "A ${family.title.lowercase()} builds on your ${missing.joinToString(" and ")} capabilit${if (missing.size > 1) "ies" else "y"}, which you don't have yet. " +
                    "Ask me for ${missing.joinToString(" and ") { "a $it" }} first.") }
            n.decisions += "builds on ${uses.joinToString()}"
        }
        step("existing", "done", n.decisions.last()); save(id, n)

        // 3. Development memory.
        val earlier = store.runs(100, req.kind).filter { it.id != id }
        val lessons = earlier.flatMap { r -> r.fixes.map { "fix: $it" } + r.limitations.map { "limitation: $it" } }.distinct().take(12)
        val styleFixes = earlier.filter { it.style == style }.flatMap { it.fixes }.filter { it.contains("contrast") }
        step("memory", "done", if (earlier.isEmpty()) "no earlier ${req.kind} development" else "${earlier.size} earlier run(s); ${lessons.size} lesson(s): ${lessons.joinToString("; ").take(600)}")
        if (earlier.isNotEmpty()) n.research += "development memory: ${earlier.size} earlier ${req.kind} run(s), ${earlier.count { it.state == DevRun.INSTALLED }} installed"
        save(id, n)

        // 4. Research.
        val reach = kit.status(force = true)
        family.services.forEach { n.research += "data source: $it" }
        reach["weather"]?.let { n.research += if (it.available) "Open-Meteo reachable now" else "Open-Meteo NOT reachable now: ${it.reason}" }
        if (req.kind != "weather") reach["tiles"]?.let { n.research += if (it.available) "OpenStreetMap tiles reachable now" else "OpenStreetMap tiles NOT reachable now: ${it.reason}" }
        val browser = verifier.browser()
        n.research += if (browser != null) "verification browser: ${browser.name}" else "no browser that renders headless was found to verify with"
        n.research += "offline data bundled with CLU: ${kit.gazetteer.size} cities (GeoNames), Natural Earth country outlines"
        step("research", "done", n.research.joinToString("; ")); save(id, n)

        // 5. Plan.
        val features = if (improving) {
            val have = store.active(target!!.id)?.blueprint?.features ?: family.base
            val add = ExperienceCatalog.improvements(req.kind, have, req.details + " " + req.text)
            if (add.isEmpty()) { step("plan", "declined", "nothing left to add")
                return finish(id, n, DevRun.DECLINED, "${target.name} already has everything I know how to add (${have.joinToString { ExperienceCatalog.FEATURE_TEXT[it] ?: it }}). Tell me what you'd like it to do differently.", capId = target.id) }
            n.decisions += "add ${add.joinToString { ExperienceCatalog.FEATURE_TEXT[it] ?: it }}"
            have + add
        } else family.base
        val prev = if (improving) store.active(target!!.id)?.blueprint else null
        var design = prev?.design ?: Styles.known[style]
        var name = prev?.name; var tagline = prev?.tagline
        if (prev == null) {
            val chosen = models?.choose("design-json")
            val advice = runBlocking { DesignAdvisor(chosen?.model).advise(req.kind, Styles.title(style), features, wantPalette = design == null, taken = store.all(includeUninstalled = true).map { it.name }) }
            if (chosen != null) {
                n.models += "${chosen.id}: design proposal in ${advice.ms} ms; accepted ${advice.accepted.ifEmpty { listOf("nothing") }.joinToString()}" +
                    (if (advice.rejected.isNotEmpty()) "; rejected ${advice.rejected.joinToString()}" else "")
                store.recordModel(chosen.id, "design-json", advice.accepted.isNotEmpty() && advice.rejected.isEmpty(), advice.ms)
            } else n.models += "no development model available: curated design used"
            name = advice.name ?: "${Styles.title(style)} ${family.title}".let { base -> var n = base; var i = 2; while (store.all(true).any { it.name.equals(n, true) }) n = "$base ${i++}"; n }
            tagline = advice.tagline ?: family.description
            if (design == null) design = advice.design ?: Styles.derived(style).also { n.decisions += "palette derived from the style name '$style'" }
        }
        var d = design!!
        if (styleFixes.isNotEmpty() && Styles.problems(d).isNotEmpty()) { d = Styles.fixContrast(d); n.fixes += "applied the contrast fix remembered from an earlier ${Styles.title(style)} run" }
        Styles.problems(d).takeIf { it.isNotEmpty() }?.let { p -> d = Styles.fixContrast(d); n.fixes += "design contrast: ${p.joinToString()} → colours adjusted" }
        var bp = Blueprint(req.kind, style, name!!, tagline!!, features, d, uses, ExperienceGenerator.labels(req.kind, style))
        n.decisions += "name \"${bp.name}\"; features: ${features.joinToString { ExperienceCatalog.FEATURE_TEXT[it] ?: it }}"
        step("plan", "done", (n.decisions + n.models.map { "model $it" } + n.fixes).joinToString("; ").take(1500)); save(id, n)

        // 6. Modules and integration points.
        val modules = listOf("kit runtime (/kit/clu-kit.js)") + when (req.kind) { "weather" -> listOf("weather client (/kit/clu-weather.js)", "service /v1/kit/weather", "service /v1/kit/places")
            "map" -> listOf("map engine (/kit/clu-map.js)", "offline geography (/kit/world.json, /kit/gazetteer.json)", "service /v1/kit/tiles", "service /v1/kit/places")
            else -> listOf("map engine", "weather client", "capabilities ${uses.joinToString()}") } + listOf("registry /v1/capabilities", "shell navigation and chat")
        step("modules", "done", modules.joinToString("; "))

        // 7–11. Implement, build-check, test, inspect, fix (bounded).
        val capId = target?.id ?: proposeId(req.kind, style)
        val version = if (target != null) store.nextVersion(target.id) else 1
        val ws = File(workspaces, id); val out = File(ws, "out")
        var result: ExperienceVerifier.Result? = null
        var attempt = 0
        while (attempt < 3) {
            attempt++
            if (out.exists()) SafeDelete.tree(out, listOf(workspaces))
            val written = ExperienceGenerator.write(out, capId, version, bp)
            n.files.clear(); n.files += written
            step("implement", "done", "attempt $attempt: wrote ${written.joinToString()} for $capId v$version")
            val build = buildCheck(out)
            if (build.isNotEmpty()) { n.errors += build; step("build", "failed", build.joinToString("; ")); save(id, n); return finish(id, n, DevRun.FAILED, "The ${bp.name} build check failed: ${build.joinToString("; ")}. Nothing was installed.") }
            step("build", "done", "files present, manifest valid, scripts and styles external (CSP-safe)")
            val shot = File(ws, "screenshot.png")
            val r = verifier.verify("${baseUrl()}/x/preview/$id/index.html", launchCode, shot, bp)
            result = r
            step("test", if (r.checks.filter { !it.name.startsWith("screenshot") }.all { it.ok }) "done" else "failed",
                r.checks.filter { !it.name.startsWith("screenshot") }.joinToString("; ") { (if (it.ok) "✓ " else "✗ ") + it.name + (if (it.detail.isNotBlank()) " (${it.detail.take(120)})" else "") })
            step("inspect", if (r.checks.filter { it.name.startsWith("screenshot") }.all { it.ok }) "done" else "failed",
                r.checks.filter { it.name.startsWith("screenshot") }.joinToString("; ") { (if (it.ok) "✓ " else "✗ ") + it.name + " (${it.detail})" })
            if (r.passed) break
            val failed = r.checks.filter { !it.ok }
            n.errors += "attempt $attempt: " + failed.joinToString("; ") { it.name + (if (it.detail.isNotBlank()) ": ${it.detail.take(160)}" else "") }
            val fixed = diagnose(failed, bp)
            if (fixed == null || r.checks.any { it.name == "browser available" && !it.ok }) { step("fix", "failed", "no known fix for: ${failed.joinToString { it.name }}"); break }
            n.fixes += fixed.second; bp = fixed.first
            step("fix", "done", fixed.second); save(id, n)
        }
        val r = result!!
        r.screenshot?.let { s -> evidence.mkdirs(); s.copyTo(File(evidence, "$id.png"), overwrite = true) }
        val verification = Verification(r.passed, r.checks, r.screenshot?.let { "evidence/$id.png" }, r.browser, attempt, r.unverified)
        r.unverified.forEach { n.limits += "not verified here: $it" }
        if (!r.passed) {
            save(id, n, ver = verification)
            val why = r.checks.filter { !it.ok }.joinToString("; ") { it.name + (if (it.detail.isNotBlank()) " (${it.detail.take(100)})" else "") }
            SafeDelete.tree(ws, listOf(workspaces))
            return finish(id, n, DevRun.FAILED, "I built ${bp.name}, but it did not pass verification after $attempt attempt(s): $why. I didn't install it; your other capabilities are unchanged.", ver = verification)
        }

        // 12. Integration.
        val missingDeps = bp.uses.filter { store.get(it)?.enabled != true }
        if (missingDeps.isNotEmpty()) { n.errors += "dependencies disabled: ${missingDeps.joinToString()}"; SafeDelete.tree(ws, listOf(workspaces))
            return finish(id, n, DevRun.FAILED, "${bp.name} depends on ${missingDeps.joinToString()}, which ${if (missingDeps.size > 1) "are" else "is"} not enabled. Nothing was installed.", ver = verification) }

        // 13. Install.
        val dest = File(installed, "$capId/$version")
        if (dest.exists()) SafeDelete.tree(dest, listOf(installed))
        dest.mkdirs()
        out.listFiles()!!.forEach { it.copyTo(File(dest, it.name)) }
        r.screenshot?.copyTo(File(dest, "preview.png"), overwrite = true)
        val files = dest.listFiles()!!.sortedBy { it.name }.map { CapFile(it.name, sha256(it), it.length()) }
        val cap = target ?: store.create(req.kind, style, bp.name, bp.tagline, family.category, bp.uses, interfaces(bp)).also { require(it.id == capId) { "capability id changed during development" } }
        store.addVersion(CapVersion(cap.id, version, dest.relativeTo(root).path, files, bp, verification, true, System.currentTimeMillis(), id, n.decisions.lastOrNull() ?: ""))
        val previous = cap.activeVersion
        store.update(cap.id, name = bp.name, description = bp.tagline, activeVersion = version, enabled = true, limitations = n.limits.toList(), dependencies = bp.uses, interfaces = interfaces(bp),
            recovery = if (previous > 0) "v$previous is kept in ${File(installed, "${cap.id}/$previous").path}; \"roll back my ${cap.name}\" returns to it" else "first version")
        store.event(cap.id, if (improving) "updated" else "installed", "v$version: ${n.decisions.lastOrNull() ?: ""} (run $id)")
        // Served by CLU and listed in the registry: checked after install, from CLU's own API.
        val served = runCatching { java.net.URI("${baseUrl()}/x/${cap.id}/$version/index.html").toURL().openConnection().let { c -> (c as java.net.HttpURLConnection).responseCode } }.getOrDefault(-1)
        step("integrate", if (served == 200) "done" else "failed", "served at /x/${cap.id}/$version/ (HTTP $served); registered as ${cap.id}; ${if (bp.uses.isNotEmpty()) "uses ${bp.uses.joinToString()}" else "no dependencies"}")
        step("install", "done", "${cap.id} v$version installed and active${if (previous > 0) "; v$previous kept for rollback" else ""}")
        SafeDelete.tree(ws, listOf(workspaces))

        // 14. Report.
        val passed = r.checks.count { it.ok }
        val f = if (improving) "Added ${bp.features.filter { it !in (prev?.features ?: emptyList()) }.joinToString { ExperienceCatalog.FEATURE_TEXT[it] ?: it }}." else
            "It has ${bp.features.joinToString { ExperienceCatalog.FEATURE_TEXT[it] ?: it }}."
        val limits = if (n.limits.isEmpty()) "" else " Not verified here: ${r.unverified.joinToString("; ")}; it will work once that source is reachable, and until then it says so instead of guessing."
        val others = store.all().filter { it.kind == req.kind && it.id != cap.id }
        val msg = (if (improving) "I've improved ${bp.name}: v$version is installed (v$previous kept for rollback). " else "I've built and installed ${bp.name} (v$version). ") + f +
            " I checked it in a real browser: $passed checks passed, including a look at the rendered screen.$limits" +
            (if (others.isNotEmpty() && !improving) " Your ${others.joinToString { it.name }} ${if (others.size > 1) "are" else "is"} unchanged." else "") +
            " Open it from Capabilities or say \"open ${bp.name}\"."
        finish(id, n, DevRun.INSTALLED, msg, cap.id, version, verification)
    }

    private fun interfaces(bp: Blueprint) = listOf("experience /x/{id}/{version}/index.html", "chat: open/improve/default", "config /v1/capabilities/{id}/config") +
        (if (bp.kind == "weather") listOf("service /v1/kit/weather") else emptyList()) + (if (bp.kind == "map") listOf("service /v1/kit/tiles") else emptyList())

    fun proposeId(kind: String, style: String): String {
        val base = (kind + "-" + style).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
        var id = base; var i = 2
        while (store.get(id) != null) id = "$base-${i++}"
        return id
    }

    /** Build check: the files a page needs exist, the manifest parses, nothing inline (the CSP would block it). */
    fun buildCheck(dir: File): List<String> = buildList {
        listOf("manifest.json", "index.html", "theme.css", "experience.js").forEach { if (!File(dir, it).isFile) add("$it missing") }
        if (isNotEmpty()) return@buildList
        runCatching { ai.clu.core.util.CluJson.parseToJsonElement(File(dir, "manifest.json").readText()) }.onFailure { add("manifest.json is not valid JSON") }
        val html = File(dir, "index.html").readText()
        Regex("<script(?![^>]*\\bsrc=)[^>]*>").find(html)?.let { add("inline script in index.html") }
        if (Regex("\\sstyle=\"").containsMatchIn(html)) add("inline style attribute in index.html")
        Regex("(?:src|href)=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.forEach { ref ->
            when {
                ref.startsWith("/kit/") -> if (ExperienceFiles::class.java.getResource("/kit/" + ref.removePrefix("/kit/")) == null) add("$ref does not exist in CLU's kit")
                ref.contains("://") || ref.startsWith("//") -> add("$ref points outside CLU")
                else -> if (!File(dir, ref).isFile) add("$ref is missing")
            }
        }
    }

    /** What to change when checks fail; null when CLU knows no fix for it. */
    fun diagnose(failed: List<Verification.Check>, bp: Blueprint): Pair<Blueprint, String>? {
        val names = failed.map { it.name }
        return when {
            names.any { it.contains("look is what is on screen") || it == "theme applied" } && bp.design.texture != "none" ->
                bp.copy(design = bp.design.copy(texture = "none")) to "the theme's texture hid its colours on screen → texture removed"
            names.any { it.contains("text and accents") } -> bp.copy(design = Styles.fixContrast(bp.design.copy(accent = Styles.ensureContrast(bp.design.accent, bp.design.bg, 4.5)))) to
                "text and accents barely visible → contrast raised"
            names.any { it.contains("land and water") || it.contains("land drawn") || it.contains("water drawn") } && Styles.contrast(bp.design.land, bp.design.water) < 1.6 ->
                bp.copy(design = bp.design.copy(land = Styles.ensureContrast(bp.design.land, bp.design.water, 1.8))) to "land and water too similar → land colour adjusted"
            names.any { it.startsWith("map:") || it.startsWith("weather:") } && bp.features.size > (ExperienceCatalog.family(bp.kind)?.base?.size ?: 0) -> {
                val base = ExperienceCatalog.family(bp.kind)!!.base
                val dropped = bp.features.filter { it !in base }
                bp.copy(features = base) to "a new feature broke the experience → rebuilt without ${dropped.joinToString()}"
            }
            else -> null
        }
    }

    companion object {
        fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    }
}
