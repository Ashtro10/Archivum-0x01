package ai.clu.desktop.capability

import ai.clu.core.capability.CapabilityCommand
import ai.clu.core.capability.CapabilityOp
import ai.clu.core.security.Sensitivity
import ai.clu.core.tools.Tool
import ai.clu.core.tools.ToolContext
import ai.clu.core.tools.ToolResult
import ai.clu.core.util.SafeDelete
import ai.clu.desktop.Call
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything CLU does with its capabilities after they exist, and the entry point for new ones: list, open (asking
 * which one when several fit and no default is set), default, enable/disable, rollback, uninstall (with the owner's
 * confirmation; files move to a recoverable trash folder), inspect, search, duplicate, and develop/improve through
 * [CapabilityDeveloper]. Installed, verified, enabled and active are separate facts:
 *
 * * installed: the active version's files are on disk and match the hashes recorded at install;
 * * verified: that version passed verification in a browser;
 * * enabled: the owner has not turned it off;
 * * active: it is open in CLU's window right now.
 */
class CapabilityManager(val store: CapabilityStore, val developer: CapabilityDeveloper) {
    private val open = ConcurrentHashMap<String, Long>()
    val root: File get() = developer.root

    data class Status(val installed: Boolean, val verified: Boolean, val enabled: Boolean, val active: Boolean, val isDefault: Boolean, val problem: String?)

    fun status(c: Capability): Status {
        val v = store.active(c.id)
        val integrity = v?.let { ver -> ver.files.firstOrNull { f -> val file = File(File(root, ver.dir), f.path); !file.isFile || CapabilityDeveloper.sha256(file) != f.sha256 }?.let { "${it.path} is missing or changed" } }
            ?: if (v == null) "no installed version" else null
        return Status(!c.uninstalled && integrity == null, v?.verified == true, c.enabled && !c.uninstalled, (open[c.id] ?: 0) > System.currentTimeMillis() - 90_000,
            store.defaultFor(c.kind) == c.id, integrity)
    }

    fun url(c: Capability) = "/x/${c.id}/${c.activeVersion}/index.html"
    fun usable(c: Capability) = status(c).let { it.installed && it.verified && it.enabled }

    fun json(c: Capability, detail: Boolean = false): JsonObject {
        val s = status(c); val v = store.active(c.id)
        return buildJsonObject {
            put("id", c.id); put("kind", c.kind); put("style", c.style); put("styleName", Styles.title(c.style)); put("name", c.name); put("description", c.description); put("category", c.category)
            put("version", c.activeVersion); put("installed", s.installed); put("verified", s.verified); put("enabled", s.enabled); put("active", s.active); put("default", s.isDefault)
            s.problem?.let { put("problem", it) }
            put("url", url(c)); put("preview", if (v?.files?.any { it.path == "preview.png" } == true) "/x/${c.id}/${c.activeVersion}/preview.png" else "")
            put("createdAt", c.createdAt); put("updatedAt", c.updatedAt)
            putJsonArray("features") { v?.blueprint?.features?.forEach { add(it) } }
            putJsonArray("limitations") { c.limitations.forEach { add(it) } }
            putJsonArray("dependencies") { c.dependencies.forEach { add(it) } }
            if (detail) {
                putJsonArray("interfaces") { c.interfaces.forEach { add(it) } }
                put("recovery", c.recovery); put("config", c.config)
                putJsonArray("versions") { store.versions(c.id).forEach { ver -> addJsonObject {
                    put("version", ver.version); put("verified", ver.verified); put("installedAt", ver.installedAt); put("run", ver.runId); put("notes", ver.notes)
                    putJsonArray("features") { ver.blueprint.features.forEach { add(it) } }
                    ver.verification?.let { vf -> put("checksPassed", vf.checks.count { it.ok }); put("checks", vf.checks.size); putJsonArray("unverified") { vf.unverified.forEach { add(it) } } } } } }
                putJsonArray("history") { store.events(c.id).forEach { e -> addJsonObject { put("at", e.at); put("kind", e.kind); put("detail", e.detail) } } }
            }
        }
    }

    /** Capabilities matching what the owner said: family, style word, or words of the name. */
    fun resolve(kind: String?, style: String?, target: String?, includeDisabled: Boolean = false): List<Capability> {
        val all = store.all().filter { includeDisabled || it.enabled }.filter { kind == null || it.kind == kind }
        if (style != null) all.filter { it.style == style }.takeIf { it.isNotEmpty() }?.let { return it }
        val words = target?.lowercase()?.split(Regex("[^a-z0-9]+"))?.filter { it.length > 2 && it !in STOP }.orEmpty()
        if (words.isNotEmpty()) all.filter { c -> words.any { w -> c.name.lowercase().contains(w) || c.style.contains(w) } }.takeIf { it.isNotEmpty() && it.size < all.size }?.let { return it }
        return if (style != null) emptyList() else all
    }

    // ------------------------------------------------------------------ the instruction from chat (or the shell)
    data class Answer(val ok: Boolean, val text: String, val action: JsonObject? = null)

    fun command(op: CapabilityOp, kind: String?, style: String?, target: String?, details: String, variant: Boolean, text: String, conversationId: String?, confirmed: Boolean = false): Answer {
        val fam = ExperienceCatalog.family(kind)
        val noun = fam?.title?.lowercase() ?: kind ?: "capabilit"
        fun list(items: List<Capability>) = JsonArray(items.map { json(it) })
        return when (op) {
            CapabilityOp.CREATE -> {
                val run = developer.start(DevRequest("CREATE", kind ?: "unknown", style, details, text, variant, conversationId))
                Answer(true, "On it: I'm developing ${if (style != null) "a ${Styles.title(style)} " else "a "}${fam?.title?.lowercase() ?: kind} for myself. I'll build it in a separate workspace, test it in a browser, and install it only if it works. Progress shows here.",
                    buildJsonObject { put("action", "develop"); put("run", run.id) })
            }
            CapabilityOp.IMPROVE -> {
                val c = resolve(kind, style, target)
                when {
                    c.isEmpty() -> Answer(false, "You don't have a $noun capability to improve yet. Ask me to create one.")
                    c.size > 1 && store.defaultFor(kind ?: "") == null -> choose(c, "Which one should I improve?", "Improve the %s")
                    else -> { val t = c.firstOrNull { it.id == store.defaultFor(it.kind) } ?: c.first()
                        val run = developer.start(DevRequest("IMPROVE", t.kind, t.style, details, text, false, conversationId, t.id))
                        Answer(true, "I'll improve ${t.name} (currently v${t.activeVersion}). The new version is built and tested separately; v${t.activeVersion} stays installed until the new one passes.",
                            buildJsonObject { put("action", "develop"); put("run", run.id) }) }
                }
            }
            CapabilityOp.LIST -> {
                val items = store.all().filter { kind == null || it.kind == kind }
                if (items.isEmpty()) Answer(true, if (kind == null) "I haven't built any capabilities for myself yet. Try \"give yourself a weather service\"." else "I don't have any ${noun}s yet. Ask me to build one.")
                else Answer(true, "Here ${if (items.size == 1) "is" else "are"} your ${if (kind == null) "capabilities" else "${noun}s"}: " + items.joinToString("; ") { c -> val s = status(c)
                    "${c.name} (v${c.activeVersion}${if (s.isDefault) ", default" else ""}${if (!s.enabled) ", disabled" else ""})" } + ".",
                    buildJsonObject { put("action", "list"); put("items", list(items)) })
            }
            CapabilityOp.OPEN -> {
                val c = resolve(kind, style, target).filter { usable(it) }
                val def = kind?.let { store.defaultFor(it) }?.let { store.get(it) }?.takeIf { usable(it) && style == null && (target == null || !hasSpecificWords(target)) }
                when {
                    def != null -> openAnswer(def, if (c.size > 1) " (your default; you also have ${c.filter { it.id != def.id }.joinToString { it.name }})" else "")
                    c.isEmpty() -> Answer(false, if (style != null) "I don't have a ${Styles.title(style)} $noun. Ask me to build one." else "You don't have a $noun capability yet. Ask me to build one.")
                    c.size == 1 -> openAnswer(c.first(), "")
                    else -> choose(c, "You have ${c.size} ${noun}s. Which one should I open?", "Open the %s")
                }
            }
            CapabilityOp.SET_DEFAULT -> {
                val c = resolve(kind, style, target)
                when {
                    c.isEmpty() -> Answer(false, "I couldn't find that $noun.")
                    c.size > 1 -> choose(c, "Which one should be your default $noun?", "Make the %s my default")
                    else -> { val t = c.first(); setDefault(t); Answer(true, "Done: ${t.name} is now your default $noun. Your other ${noun}s are unchanged; \"open my $noun\" now opens ${t.name}.",
                        buildJsonObject { put("action", "updated"); put("capability", json(t)) }) }
                }
            }
            CapabilityOp.ENABLE, CapabilityOp.DISABLE -> {
                val c = resolve(kind, style, target, includeDisabled = true)
                if (c.size != 1) return if (c.isEmpty()) Answer(false, "I couldn't find that $noun.") else choose(c, "Which one?", (if (op == CapabilityOp.ENABLE) "Enable" else "Disable") + " the %s")
                val t = c.first(); setEnabled(t, op == CapabilityOp.ENABLE)
                Answer(true, "${t.name} is now ${if (op == CapabilityOp.ENABLE) "enabled" else "disabled (kept installed; say \"enable ${t.name}\" to bring it back)"}.", buildJsonObject { put("action", "updated"); put("capability", json(store.get(t.id)!!)) })
            }
            CapabilityOp.ROLLBACK -> {
                val c = resolve(kind, style, target, includeDisabled = true)
                if (c.size != 1) return if (c.isEmpty()) Answer(false, "I couldn't find that $noun.") else choose(c, "Which one should I roll back?", "Roll back the %s")
                rollback(c.first()).let { Answer(it.first, it.second, buildJsonObject { put("action", "updated"); put("capability", json(store.get(c.first().id)!!)) }) }
            }
            CapabilityOp.UNINSTALL -> {
                val c = resolve(kind, style, target, includeDisabled = true)
                if (c.size != 1) return if (c.isEmpty()) Answer(false, "I couldn't find that $noun.") else choose(c, "Which one should I uninstall?", "Uninstall the %s")
                val t = c.first()
                if (!confirmed) Answer(true, "Uninstalling ${t.name} removes it from your capabilities (its files move to a recovery folder, nothing else changes). Confirm?",
                    buildJsonObject { put("action", "confirm"); put("capability", json(t)); put("op", "uninstall") })
                else uninstall(t).let { Answer(it.first, it.second) }
            }
            CapabilityOp.INSPECT -> {
                val c = resolve(kind, style, target, includeDisabled = true)
                if (c.size != 1) return if (c.isEmpty()) Answer(false, "I couldn't find that $noun.") else choose(c, "Which one?", "Tell me about the %s")
                val t = c.first(); val s = status(t); val vs = store.versions(t.id)
                Answer(true, "${t.name}: ${t.description} Version ${t.activeVersion} of ${vs.size}; ${listOf("installed" to s.installed, "verified" to s.verified, "enabled" to s.enabled, "default" to s.isDefault, "open now" to s.active)
                    .joinToString { (k, v) -> if (v) k else "not $k" }}. ${if (t.limitations.isNotEmpty()) "Limitations: ${t.limitations.joinToString("; ")}." else ""}",
                    buildJsonObject { put("action", "inspect"); put("capability", json(t, detail = true)) })
            }
        }
    }

    private fun hasSpecificWords(target: String) = target.lowercase().split(Regex("[^a-z0-9]+")).any { it.length > 2 && it !in STOP && it !in FAMILY_WORDS }

    private fun openAnswer(c: Capability, extra: String) = Answer(true, "Opening ${c.name}$extra.", buildJsonObject { put("action", "open"); put("capability", json(c)) })

    private fun choose(options: List<Capability>, question: String, template: String) = Answer(true, question + " " + options.joinToString(" or ") { it.name } + "?",
        buildJsonObject { put("action", "choose"); put("question", question)
            putJsonArray("options") { options.forEach { c -> add(JsonObject(json(c) + mapOf("say" to JsonPrimitive(template.format(c.name))))) } } })

    // ------------------------------------------------------------------ operations (also used by the API)
    fun setDefault(c: Capability) { store.setPref("default.${c.kind}", c.id); store.event(c.id, "default", "set as the default ${c.kind}") }
    fun setEnabled(c: Capability, on: Boolean) {
        store.update(c.id, enabled = on); store.event(c.id, if (on) "enabled" else "disabled", "")
        if (!on && store.defaultFor(c.kind) == c.id) store.setPref("default.${c.kind}", null)
    }
    fun rollback(c: Capability): Pair<Boolean, String> {
        val prev = store.versions(c.id).filter { it.version < c.activeVersion && it.verified && File(File(root, it.dir), "index.html").isFile }.maxByOrNull { it.version }
            ?: return false to "${c.name} has no earlier verified version to return to."
        store.update(c.id, activeVersion = prev.version, recovery = "v${c.activeVersion} is kept; it can be re-activated from the Capabilities view")
        store.event(c.id, "rolled back", "v${c.activeVersion} → v${prev.version}")
        return true to "${c.name} is back on v${prev.version}. v${c.activeVersion} is kept and can be switched back to."
    }
    fun activate(c: Capability, version: Int): Pair<Boolean, String> {
        val v = store.version(c.id, version)?.takeIf { it.verified } ?: return false to "v$version of ${c.name} is not a verified version."
        store.update(c.id, activeVersion = v.version); store.event(c.id, "switched", "v${c.activeVersion} → v${v.version}")
        return true to "${c.name} now runs v${v.version}."
    }
    fun uninstall(c: Capability): Pair<Boolean, String> {
        val trash = File(root, "trash/${c.id}-${System.currentTimeMillis()}")
        val src = File(developer.installed, c.id)
        if (src.exists()) { trash.parentFile.mkdirs(); if (!src.renameTo(trash)) return false to "I couldn't move ${c.name}'s files; nothing was changed." }
        store.update(c.id, enabled = false, uninstalled = true, recovery = "files kept in ${trash.path}")
        if (store.defaultFor(c.kind) == c.id) store.setPref("default.${c.kind}", null)
        store.event(c.id, "uninstalled", "files moved to ${trash.path}")
        return true to "${c.name} is uninstalled. Its files are kept in a recovery folder (${trash.name}); your other capabilities are unchanged."
    }
    /** A copy of the active version as a new capability (to be improved separately). */
    fun duplicate(c: Capability): Capability {
        val v = store.active(c.id) ?: error("no active version")
        val id = developer.proposeId(c.kind, c.style)
        val dest = File(developer.installed, "$id/1"); dest.mkdirs()
        val src = File(root, v.dir)
        src.listFiles()!!.forEach { f -> val text = if (f.extension in setOf("html", "js", "json")) f.readText().replace("\"${c.id}\"", "\"$id\"").replace("${c.id} · v${v.version}", "$id · v1").replace("\"version\":${v.version}", "\"version\":1") else null
            if (text != null) File(dest, f.name).writeText(text) else f.copyTo(File(dest, f.name)) }
        val copy = store.create(c.kind, c.style, c.name + " (copy)", c.description, c.category, c.dependencies, c.interfaces)
        require(copy.id == id)
        val files = dest.listFiles()!!.sortedBy { it.name }.map { CapFile(it.name, CapabilityDeveloper.sha256(it), it.length()) }
        store.addVersion(v.copy(capId = id, version = 1, dir = dest.relativeTo(root).path, files = files, blueprint = v.blueprint.copy(name = c.name + " (copy)"), notes = "duplicated from ${c.id} v${v.version}"))
        store.update(id, activeVersion = 1, limitations = c.limitations)
        store.event(id, "duplicated", "from ${c.id} v${v.version}")
        return store.get(id)!!
    }

    fun opened(id: String) { open[id] = System.currentTimeMillis() }
    fun closed(id: String) { open.remove(id) }

    fun search(q: String): List<Capability> { val w = q.lowercase().trim(); if (w.isEmpty()) return store.all()
        return store.all().filter { c -> listOf(c.name, c.description, c.kind, c.style, Styles.title(c.style)).any { it.lowercase().contains(w) } } }

    fun runJson(r: DevRun, steps: Boolean = true) = buildJsonObject {
        put("id", r.id); put("request", r.request); put("op", r.op); put("kind", r.kind); put("style", r.style); put("capability", r.capId); put("state", r.state)
        put("version", r.version); put("message", r.message); put("startedAt", r.startedAt); put("finishedAt", r.finishedAt)
        listOf("requirements" to r.requirements, "research" to r.research, "decisions" to r.decisions, "files" to r.files, "models" to r.models, "errors" to r.errors, "fixes" to r.fixes, "limitations" to r.limitations)
            .forEach { (k, v) -> putJsonArray(k) { v.forEach { add(it) } } }
        r.verification?.let { v -> putJsonObject("verification") { put("passed", v.passed); put("browser", v.browser); put("attempts", v.attempts); v.screenshot?.let { put("screenshot", it) }
            putJsonArray("checks") { v.checks.forEach { c -> addJsonObject { put("name", c.name); put("ok", c.ok); put("detail", c.detail) } } }
            putJsonArray("unverified") { v.unverified.forEach { add(it) } } } }
        r.capId?.let { id -> store.get(id)?.takeIf { r.state == DevRun.INSTALLED || r.state == DevRun.DECLINED }?.let { put("result", json(it)) } }
        if (steps) putJsonArray("steps") { store.steps(r.id).forEach { s -> addJsonObject { put("at", s.at); put("step", s.step); put("status", s.status); put("detail", s.detail) } } }
    }

    companion object {
        private val STOP = setOf("the", "my", "your", "one", "map", "maps", "weather", "service", "dashboard", "interface", "experience", "viewer", "app", "hud", "default", "and", "for", "with", "too")
        private val FAMILY_WORDS = setOf("map", "maps", "weather", "dashboard", "forecast", "service", "app", "viewer", "interface", "experience", "hud", "ui")
    }
}

/** The chat side: CLU's agent hands development and management instructions to this tool. */
class CapabilityTool(private val manager: CapabilityManager) : Tool {
    override val name = CapabilityCommand.TOOL
    override val description = "Develop, install and manage CLU's own capabilities (weather services, maps, dashboards) from the owner's instructions."
    override val argsExample = """{"text":"Give yourself a sci-fi weather service","op":"CREATE","kind":"weather","style":"scifi"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val op = args["op"]?.jsonPrimitive?.contentOrNull?.let { runCatching { CapabilityOp.valueOf(it) }.getOrNull() } ?: return ToolResult.fail("No capability operation given.")
        val a = manager.command(op, args["kind"]?.jsonPrimitive?.contentOrNull, args["style"]?.jsonPrimitive?.contentOrNull, args["target"]?.jsonPrimitive?.contentOrNull,
            args["details"]?.jsonPrimitive?.contentOrNull ?: "", args["variant"]?.jsonPrimitive?.booleanOrNull ?: false, args["text"]?.jsonPrimitive?.contentOrNull ?: "",
            ctx.conversationId)
        return ToolResult(a.ok, a.ok, a.text, data = a.action, spoken = a.text, error = if (a.ok) null else "capability")
    }
}

/**
 * The capability API (owner token, like every /v1 route):
 *
 * | | |
 * |---|---|
 * | GET /v1/capabilities | all capabilities with installed/verified/enabled/active/default; `?q=` searches; `?kind=` filters |
 * | GET /v1/capabilities/{id} | details: versions, verification, history, limitations, recovery |
 * | POST /v1/capabilities/command | `{text, conversationId?, confirm?}`: the same instruction handling as chat |
 * | POST /v1/capabilities/develop | `{text}` or `{op, kind, style, details}`: start a development run |
 * | GET /v1/capabilities/runs, /runs/{id} | development memory: runs with requirements, research, decisions, steps, verification |
 * | POST /v1/capabilities/{id}/default, /enable, /disable, /rollback, /duplicate | operations |
 * | POST /v1/capabilities/{id}/activate | `{version}` switch to another verified version |
 * | POST /v1/capabilities/{id}/uninstall | `{confirm: true}` required; files move to a recovery folder |
 * | GET/PUT /v1/capabilities/{id}/config | the experience's own settings (merged on PUT, ≤ 32 KiB) |
 * | POST /v1/capabilities/{id}/opened, /closed | the shell reports what is open (the "active" state) |
 * | GET/POST /v1/capabilities/models | development models: availability, measured scores, remote-model consent |
 */
class CapabilityRoutes(private val m: CapabilityManager, private val models: DevModels?) {
    fun handle(c: Call): Boolean {
        val p = c.path
        if (!p.startsWith("/v1/capabilities")) return false
        val one = Regex("^/v1/capabilities/([a-z0-9-]{1,60})(?:/([a-z]+))?$").find(p)
        val run = Regex("^/v1/capabilities/runs/([a-z0-9]{8,32})$").find(p)
        when {
            p == "/v1/capabilities" && c.method == "GET" -> c.ok(JsonArray((c.query("q")?.let { m.search(it) } ?: m.store.all()).filter { k -> c.query("kind")?.let { it == k.kind } ?: true }.map { m.json(it) }))
            p == "/v1/capabilities/command" && c.method == "POST" -> {
                val text = c.str("text")?.trim().orEmpty()
                val cmd = ai.clu.core.capability.CapabilityCommand.parse(text) ?: return true.also { c.fail(422, "that isn't an instruction about CLU's capabilities") }
                val a = m.command(cmd.op, cmd.kind, cmd.style, cmd.target, cmd.details, cmd.variant, text, c.str("conversationId"), confirmed = c.body["confirm"]?.jsonPrimitive?.booleanOrNull == true)
                c.ok(buildJsonObject { put("ok", a.ok); put("text", a.text); a.action?.let { put("action", it) } })
            }
            p == "/v1/capabilities/develop" && c.method == "POST" -> {
                val text = c.str("text").orEmpty()
                val cmd = ai.clu.core.capability.CapabilityCommand.parse(text)
                val kind = c.str("kind") ?: cmd?.kind ?: return true.also { c.fail(400, "say what to build (text or kind)") }
                val r = m.developer.start(DevRequest(c.str("op") ?: cmd?.op?.name ?: "CREATE", kind, c.str("style") ?: cmd?.style, c.str("details") ?: cmd?.details ?: "", text.ifBlank { "develop $kind" },
                    c.body["variant"]?.jsonPrimitive?.booleanOrNull ?: cmd?.variant ?: false, c.str("conversationId"), c.str("capability")))
                c.send(202, buildJsonObject { put("run", r.id) })
            }
            p == "/v1/capabilities/runs" && c.method == "GET" -> c.ok(JsonArray(m.store.runs(100, c.query("kind")).map { m.runJson(it, steps = false) }))
            run != null && c.method == "GET" -> m.store.run(run.groupValues[1])?.let { c.ok(m.runJson(it)) } ?: c.fail(404, "no such run")
            p == "/v1/capabilities/models" && c.method == "GET" -> c.ok(buildJsonObject {
                putJsonArray("models") { models?.availability("design-json")?.forEach { a -> addJsonObject { put("id", a.id); put("available", a.available); put("reason", a.reason); put("remote", a.remote)
                    a.score?.let { s -> put("attempts", s.attempts); put("successes", s.successes); put("avgMs", s.avgMs) } } } }
                putJsonObject("remote") { listOf("enabled", "free", "share", "url", "model").forEach { k -> put(k, m.store.pref("dev.remote.$k") ?: "") } }
                put("chosen", models?.choose("design-json")?.id) })
            p == "/v1/capabilities/models" && c.method == "POST" -> {
                listOf("enabled", "free", "share").forEach { k -> c.body[k]?.jsonPrimitive?.booleanOrNull?.let { m.store.setPref("dev.remote.$k", it.toString()) } }
                c.str("url")?.let { u -> if (u.isNotBlank() && !Regex("^https://\\S+$").matches(u)) return true.also { c.fail(400, "the endpoint must be an https:// URL") }; m.store.setPref("dev.remote.url", u.ifBlank { null }) }
                c.str("model")?.let { m.store.setPref("dev.remote.model", it.take(100).ifBlank { null }) }
                c.ok(buildJsonObject { put("remoteAllowed", models?.remoteAllowed() == null); put("reason", models?.remoteAllowed() ?: "") })
            }
            one != null -> {
                val cap = m.store.get(one.groupValues[1]) ?: return true.also { c.fail(404, "no such capability") }
                when (one.groupValues[2] to c.method) {
                    "" to "GET" -> c.ok(m.json(cap, detail = true))
                    "config" to "GET" -> c.ok(cap.config)
                    "config" to "PUT" -> {
                        val merged = JsonObject(cap.config + c.body)
                        if (merged.toString().length > 32_768) return true.also { c.fail(413, "configuration over 32 KiB") }
                        m.store.update(cap.id, config = merged); c.ok(merged)
                    }
                    "default" to "POST" -> { m.setDefault(cap); c.ok(m.json(m.store.get(cap.id)!!)) }
                    "enable" to "POST" -> { m.setEnabled(cap, true); c.ok(m.json(m.store.get(cap.id)!!)) }
                    "disable" to "POST" -> { m.setEnabled(cap, false); c.ok(m.json(m.store.get(cap.id)!!)) }
                    "rollback" to "POST" -> m.rollback(cap).let { (ok, msg) -> if (ok) c.ok(buildJsonObject { put("message", msg); put("capability", m.json(m.store.get(cap.id)!!)) }) else c.fail(409, msg) }
                    "activate" to "POST" -> m.activate(cap, c.body["version"]?.jsonPrimitive?.intOrNull ?: -1).let { (ok, msg) -> if (ok) c.ok(buildJsonObject { put("message", msg) }) else c.fail(409, msg) }
                    "duplicate" to "POST" -> c.send(201, m.json(m.duplicate(cap)))
                    "uninstall" to "POST" -> if (c.body["confirm"]?.jsonPrimitive?.booleanOrNull != true) c.fail(428, "uninstalling needs {\"confirm\": true}")
                        else m.uninstall(cap).let { (ok, msg) -> if (ok) c.ok(buildJsonObject { put("message", msg) }) else c.fail(409, msg) }
                    "opened" to "POST" -> { m.opened(cap.id); c.ok() }
                    "closed" to "POST" -> { m.closed(cap.id); c.ok() }
                    else -> c.fail(404, "no such endpoint")
                }
            }
            else -> c.fail(404, "no such endpoint")
        }
        return true
    }
}

/** Creates the capability system in the data folder (its own database next to CLU's). */
class CapabilitySetup(val manager: CapabilityManager, val routes: CapabilityRoutes, val kit: KitServices, val files: ExperienceFiles, val tool: CapabilityTool, private val db: ai.clu.core.db.SqlDriver) {
    fun close() = runCatching { db.close() }

    companion object {
        fun open(core: ai.clu.core.CluCore, dataRoot: File, baseUrl: () -> String, launchCode: () -> String, local: ai.clu.core.evolution.DevModel?, log: (String) -> Unit): CapabilitySetup {
            val root = File(dataRoot, "capabilities").apply { mkdirs() }
            File(root, "workspaces").apply { mkdirs() }.listFiles()?.forEach { runCatching { SafeDelete.tree(it, listOf(File(root, "workspaces"))) } } // leftovers of an interrupted run
            val db = ai.clu.desktop.JdbcSqlDriver(File(root, "capabilities.db").absolutePath)
            val store = CapabilityStore(db)
            store.runs(50).filter { it.state == DevRun.RUNNING }.forEach { store.saveRun(it.copy(state = DevRun.FAILED, message = "Interrupted when CLU stopped; nothing was installed.", finishedAt = System.currentTimeMillis())) }
            val kit = KitServices(core, core.platform.http, File(root, "tile-cache"))
            val models = DevModels(core, store, core.platform.http, local) { core.platform.secret("dev_remote_key") }
            val dev = CapabilityDeveloper(store, kit, ExperienceVerifier(scratch = File(root, "workspaces")), models, root, baseUrl, launchCode,
                reply = { conv, text -> runCatching { if (core.conversations.get(conv)?.deleted == false) core.conversations.addMessage(conv, ai.clu.core.conversation.Role.ASSISTANT, text) } }, log = log)
            val manager = CapabilityManager(store, dev)
            return CapabilitySetup(manager, CapabilityRoutes(manager, models), kit, ExperienceFiles({ dev.installed }, { dev.workspaces }), CapabilityTool(manager), db)
        }
    }
}
