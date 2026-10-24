package ai.clu.ui

import ai.clu.core.commands.*
import ai.clu.core.config.Settings
import ai.clu.core.persona.*
import ai.clu.core.security.*
import ai.clu.core.util.CluJson
import ai.clu.voice.VoicePacks
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.resume

class ConversationsActivity : BaseActivity() {
    private var query = ""
    private var archived = false
    override fun onResume() { super.onResume(); build() }

    private fun build(): Unit = screen("Conversations") {
        button("+ New conversation", primary = true) { app.openConversation(null); finish() }
        val q = field("Search conversations", query)
        row { smallButton("Search") { query = q.text.toString(); build() }; smallButton(if (archived) "Hide archived" else "Show archived") { archived = !archived; build() } }
        val list = if (query.isNotBlank()) core.conversations.search(query) else core.conversations.list(includeArchived = archived)
        list.forEach { c ->
            card {
                p((if (c.archived) "🗄 " else "") + c.title, C.TEXT, 15f)
                val persona = if (c.personaId == "clu") "" else " · with ${core.characters.get(c.personaId)?.name ?: "character"}"
                p("${fmtTime(c.updatedAt)}$persona", C.MUTED, 12f)
                row {
                    smallButton("Open") { app.openConversation(c.id); finish() }
                    smallButton("Rename") { prompt("Title", c.title) { core.conversations.rename(c.id, it); build() } }
                    smallButton(if (c.archived) "Unarchive" else "Archive") { core.conversations.setArchived(c.id, !c.archived); build() }
                    smallButton("Delete") { confirm("Delete conversation?", "Messages are removed; memories are not affected.") { core.conversations.delete(c.id); build() } }
                }
            }
        }
    }
}

class CharactersActivity : BaseActivity() {
    override fun onResume() { super.onResume(); build() }
    private fun build(): Unit = screen("Characters") {
        val active = core.personas.active()
        p("Characters are separate fictional personas. They never replace Clu or change its memories. Say \"switch to <name>\" or \"back to Clu\".", C.MUTED, 12f)
        card {
            p((if (active.isClu) "● " else "") + core.settings.string(Settings.CLU_NAME) + " (original entity)", C.TEXT, 15f)
            if (!active.isClu) smallButton("Switch to ${core.settings.string(Settings.CLU_NAME)}") { core.personas.switchToClu(); build() }
        }
        core.characters.list().forEach { c ->
            card {
                p((if (active.id == c.id) "● " else "") + c.name + if (c.status == CharacterStatus.ARCHIVED) " (archived)" else "", C.TEXT, 15f)
                if (c.description.isNotBlank()) p(c.description, C.MUTED, 12f)
                p("Memory: ${c.memoryScope.label}${if (c.canUseDeviceTools) " · may use device tools" else ""}${if (c.canAccessPrivateMemory) " · may read Clu's memories" else ""}", C.MUTED, 11f)
                row {
                    if (c.status == CharacterStatus.ACTIVE && active.id != c.id) smallButton("Talk to ${c.name}") { core.personas.switchTo(c.id); app.openConversation(null); finish() }
                    smallButton("Edit") { startActivity(Intent(this@CharactersActivity, CharacterEditActivity::class.java).putExtra("id", c.id)) }
                    smallButton("Duplicate") { core.characters.duplicate(c.id); build() }
                    smallButton(if (c.status == CharacterStatus.ACTIVE) "Archive" else "Restore") {
                        if (c.status == CharacterStatus.ACTIVE) { if (active.id == c.id) core.personas.switchToClu(); core.characters.archive(c.id) } else core.characters.restore(c.id); build()
                    }
                    smallButton("Delete") { confirm("Delete ${c.name}?", "Deletes the character and its private memories. Clu and its memories are unaffected.") {
                        if (active.id == c.id) core.personas.switchToClu(); core.characters.delete(c.id); build() } }
                }
            }
        }
        button("+ Create character", primary = true) { startActivity(Intent(this@CharactersActivity, CharacterEditActivity::class.java)) }
    }
}

class CharacterEditActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = intent.getStringExtra("id")?.let { core.characters.get(it) }
        screen(if (c == null) "New character" else "Edit ${c.name}") {
            val name = field("Name", c?.name ?: "")
            val desc = field("Description", c?.description ?: "")
            val bg = field("Background", c?.background ?: "", multiline = true)
            val pers = field("Personality instructions", c?.personality ?: "", multiline = true)
            val scen = field("Scenario", c?.scenario ?: "", multiline = true)
            val style = field("Conversation style", c?.style ?: "")
            val ex = field("Example dialogue", c?.exampleDialogues ?: "", multiline = true)
            val scopes = CharacterMemoryScope.values().toList()
            p("Memory", C.TEXT, 13f)
            val scope = spinner(scopes.map { it.label }, scopes.indexOf(c?.memoryScope ?: CharacterMemoryScope.OWN))
            val priv = check("May read Clu's private memories", c?.canAccessPrivateMemory ?: false)
            val dev = check("May use device tools (apps, alarms…)", c?.canUseDeviceTools ?: false)
            val tools = field("Other allowed tools (comma separated, e.g. web.search, calc)", c?.allowedTools?.joinToString(", ") ?: "")
            p("Available tools: " + core.toolRegistry.all().joinToString { it.name }, C.MUTED, 11f)
            button("Save", primary = true) {
                val save = {
                    runCatching {
                        core.characters.save((c ?: CharacterProfile(name = name.text.toString())).copy(name = name.text.toString().trim(), description = desc.text.toString(),
                            background = bg.text.toString(), personality = pers.text.toString(), scenario = scen.text.toString(), style = style.text.toString(),
                            exampleDialogues = ex.text.toString(), memoryScope = scopes[scope.selectedItemPosition], canAccessPrivateMemory = priv.isChecked,
                            canUseDeviceTools = dev.isChecked, allowedTools = tools.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                    }.onSuccess { finish() }.onFailure { toast(it.message ?: "Could not save") }
                }
                // Granting a fictional persona access to private memory is a sensitive change.
                if (priv.isChecked && c?.canAccessPrivateMemory != true) verifyOwner("Allow ${name.text} to read private memories") { ok -> if (ok) save() } else save()
            }
        }
    }
}

class PeopleActivity : BaseActivity() {
    override fun onResume() { super.onResume(); build() }

    private fun build(): Unit = screen("People & security") {
        val owner = core.people.ensureOwner(core.settings.string(Settings.OWNER_NAME))
        val spk = app.packs.isInstalled(VoicePacks.SPEAKER)
        p("Voice recognition is probabilistic and is never the only check for sensitive actions. The PIN and security phrase work even if your voice changes.", C.MUTED, 12f)
        if (!spk) p("⚠ Speaker recognition model not installed (Voice screen) – enrolment and recognition are unavailable until then.", C.WARN, 12f)
        h1("Owner")
        card {
            p(owner.name, C.TEXT, 15f)
            p("Voice samples: ${core.people.voiceprintCount(owner.id)}", C.MUTED, 12f)
            row {
                smallButton("Enroll voice") {
                    val doEnroll = { enroll(owner) }
                    // Changing an existing owner voice profile requires verification.
                    if (core.people.voiceprintCount(owner.id) > 0) verifyOwner("Update the owner voice profile") { ok -> if (ok) doEnroll() } else doEnroll()
                }
                smallButton("Test recognition") { testRecognition() }
            }
            row {
                smallButton(if (core.credentials.isSet(CredentialStore.Kind.PIN)) "Change PIN" else "Set PIN") { setSecret(CredentialStore.Kind.PIN) }
                smallButton(if (core.credentials.isSet(CredentialStore.Kind.PHRASE)) "Change security phrase" else "Set security phrase") { setSecret(CredentialStore.Kind.PHRASE) }
            }
        }
        h1("People")
        core.people.list().filter { it.role != PersonRole.OWNER }.forEach { person ->
            card {
                p("${person.name} · ${person.role.label}", C.TEXT, 15f)
                p("Voice samples: ${core.people.voiceprintCount(person.id)} · tools: ${person.allowedTools.joinToString().ifEmpty { "none" }}" +
                    (if (person.canAccessPrivateMemory) " · can access private memory" else "") + (person.voiceThreshold?.let { " · threshold $it" } ?: ""), C.MUTED, 12f)
                if (person.interactionStyle.isNotBlank()) p("Style: ${person.interactionStyle}", C.MUTED, 12f)
                row {
                    smallButton("Record voice") { enroll(person) }
                    smallButton("Edit") { editPerson(person) }
                    smallButton("Delete") { confirm("Delete ${person.name}?", "Their voice samples are removed too.") { core.people.delete(person.id); build() } }
                }
            }
        }
        button("+ Add person") { editPerson(null) }
        button("Security settings") { startActivity(Intent(this@PeopleActivity, SettingsCategoryActivity::class.java).putExtra("cat", "SECURITY")) }
    }

    private fun editPerson(p0: Person?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        val name = box.field("Name", p0?.name ?: "")
        val relation = box.field("Relation (friend, brother…)", p0?.relation ?: "")
        val roles = listOf(PersonRole.TRUSTED, PersonRole.GUEST, PersonRole.BLOCKED)
        val role = box.spinner(roles.map { it.label }, roles.indexOf(p0?.role ?: PersonRole.TRUSTED))
        val tools = box.field("Allowed tools (comma separated)", p0?.allowedTools?.joinToString(", ") ?: "time.now, calc")
        val priv = box.check("Can access private memory", p0?.canAccessPrivateMemory ?: false)
        val thr = box.field("Voice threshold override (blank = default)", p0?.voiceThreshold?.toString() ?: "")
        val style = box.field("How Clu should treat them", p0?.interactionStyle ?: "")
        android.app.AlertDialog.Builder(this).setTitle(if (p0 == null) "Add person" else "Edit ${p0.name}").setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val person = (p0 ?: Person(name = name.text.toString())).copy(name = name.text.toString().trim(), relation = relation.text.toString(),
                    role = roles[role.selectedItemPosition], allowedTools = tools.text.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    canAccessPrivateMemory = priv.isChecked, voiceThreshold = thr.text.toString().toDoubleOrNull()?.coerceIn(0.2, 0.95), interactionStyle = style.text.toString())
                val save = { runCatching { core.people.save(person) }.onFailure { toast(it.message ?: "error") }; build() }
                if (person.canAccessPrivateMemory && p0?.canAccessPrivateMemory != true) verifyOwner("Give ${person.name} access to private memory") { if (it) save() } else save()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun enroll(person: Person) {
        if (!app.hasMic()) return toast("Microphone permission needed (tap the mic in chat).")
        if (!app.packs.isInstalled(VoicePacks.SPEAKER)) return toast("Install the speaker recognition model in Voice first.")
        confirm("Record ${person.name}'s voice", "Ask ${person.name} to speak naturally for 6 seconds after pressing Start. Record 2–3 samples for reliability.", "Start") {
            scope.launch {
                toast("Recording… speak now")
                val emb = app.voice.recordEmbedding(6)
                if (emb == null) toast("Recording failed.") else { core.people.addVoiceprint(person.id, VoicePacks.SPEAKER.id, emb); toast("Sample saved."); build() }
            }
        }
    }

    private fun testRecognition() {
        if (!app.hasMic() || !app.packs.isInstalled(VoicePacks.SPEAKER)) return toast("Needs microphone permission and the speaker model.")
        scope.launch {
            toast("Speak for 4 seconds…")
            val emb = app.voice.recordEmbedding(4) ?: return@launch toast("Recording failed.")
            val m = SpeakerMatcher.identify(emb, core.people.voiceprints(VoicePacks.SPEAKER.id), core.settings.double(Settings.SPEAKER_THRESHOLD))
            val who = m.personId?.let { core.people.get(it)?.name } ?: "unknown"
            android.app.AlertDialog.Builder(this@PeopleActivity).setTitle("Recognised: $who")
                .setMessage("Best similarity ${"%.2f".format(m.score)} (threshold ${core.settings.double(Settings.SPEAKER_THRESHOLD)}), runner-up ${"%.2f".format(m.runnerUp)}.")
                .setPositiveButton("OK", null).show()
        }
    }

    private fun setSecret(kind: CredentialStore.Kind) {
        val change = {
            prompt(if (kind == CredentialStore.Kind.PIN) "New PIN (4–12 digits)" else "New security phrase (6+ characters)", numeric = kind == CredentialStore.Kind.PIN, password = true) { s ->
                runCatching { core.credentials.set(kind, s) }.onSuccess { toast("Saved (stored as a salted hash).") }.onFailure { toast(it.message ?: "Invalid") }
                build()
            }
        }
        if (core.credentials.isSet(kind) || core.credentials.isSet(CredentialStore.Kind.PIN)) verifyOwner("Change security credentials") { if (it) change() } else change()
    }
}

class CommandsActivity : BaseActivity() {
    override fun onResume() { super.onResume(); build() }
    private fun build(): Unit = screen("Custom commands") {
        p("Commands run through the normal permission checks. Session end phrase and its responses are in Wake word settings.", C.MUTED, 12f)
        core.commands.list().forEach { c ->
            card {
                p(c.name + if (!c.enabled) " (disabled)" else "", C.TEXT, 15f)
                p("${c.kind.label} · triggers: ${c.triggers.joinToString(" / ")}", C.MUTED, 12f)
                if (c.steps.isNotEmpty()) p("Steps: " + c.steps.joinToString { "${it.tool} ${it.args}" }, C.MUTED, 11f)
                row {
                    smallButton("Edit") { edit(c) }
                    smallButton(if (c.enabled) "Disable" else "Enable") { core.commands.save(c.copy(enabled = !c.enabled)); build() }
                    smallButton("Delete") { confirm("Delete ${c.name}?", "") { core.commands.delete(c.id); build() } }
                }
            }
        }
        button("+ New command", primary = true) { edit(null) }
    }

    private fun edit(c: CustomCommand?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        val name = box.field("Name", c?.name ?: "")
        val kinds = CommandKind.values().toList()
        val kind = box.spinner(kinds.map { it.label }, kinds.indexOf(c?.kind ?: CommandKind.RESPONSE))
        val triggers = box.field("Trigger phrases / aliases (one per line)", c?.triggers?.joinToString("\n") ?: "", multiline = true)
        val modes = MatchMode.values().toList()
        val mode = box.spinner(modes.map { it.name.lowercase() }, modes.indexOf(c?.match ?: MatchMode.FUZZY))
        val responses = box.field("Responses (one per line; {owner} {greeting} {farewell} {time})", c?.responses?.joinToString("\n") ?: "", multiline = true)
        val steps = box.field("Steps: one per line as  tool.name {json args}", c?.steps?.joinToString("\n") { "${it.tool} ${it.args}" } ?: "", multiline = true)
        box.p("Tools: " + core.toolRegistry.all().joinToString { it.name }, C.MUTED, 11f)
        val owner = box.check("Owner only", c?.ownerOnly ?: true)
        android.app.AlertDialog.Builder(this).setTitle(c?.name ?: "New command").setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                runCatching {
                    val parsed = steps.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
                        val tool = line.substringBefore(' ').trim(); val json = line.substringAfter(' ', "{}").trim().ifEmpty { "{}" }
                        require(core.toolRegistry[tool] != null) { "Unknown tool $tool" }
                        CommandStep(tool, CluJson.parseToJsonElement(json).jsonObject)
                    }
                    core.commands.save((c ?: CustomCommand(name = name.text.toString())).copy(name = name.text.toString().trim(), kind = kinds[kind.selectedItemPosition],
                        triggers = triggers.text.lines().map { it.trim() }.filter { it.isNotEmpty() }, match = modes[mode.selectedItemPosition],
                        responses = responses.text.lines().map { it.trim() }.filter { it.isNotEmpty() }, steps = parsed, ownerOnly = owner.isChecked))
                }.onFailure { toast(it.message ?: "Invalid command") }
                build()
            }.setNegativeButton("Cancel", null).show()
    }
}

class DevicesActivity : BaseActivity() {
    override fun onResume() {
        super.onResume(); build()
        app.sync.pairingPrompt = { name, role, sas ->
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    android.app.AlertDialog.Builder(this@DevicesActivity).setTitle("Pair with $name ($role)?")
                        .setMessage("Check that both devices show this code:\n\n$sas\n\nOnly confirm if the codes match.").setCancelable(false)
                        .setPositiveButton("Codes match") { _, _ -> cont.resume(true) }.setNegativeButton("Cancel") { _, _ -> cont.resume(false) }.show()
                }
            }
        }
        scope.launch { app.sync.state.collect { if (!isFinishing) syncStatus?.text = describe(it) } }
    }

    private var syncStatus: android.widget.TextView? = null
    private fun describe(s: ai.clu.sync.SyncUiState) = "Server: ${if (s.serving) "listening on port ${s.port}" else "off (listens once paired or while pairing is allowed)"}\nThis device: ${s.localAddresses.joinToString().ifEmpty { "no network" }}" +
        "\nDiscovered: ${s.discovered.joinToString().ifEmpty { "none (hotspots often block discovery)" }}\nGateway: ${app.sync.gatewayAddress() ?: "-"}" +
        (s.lastResult?.let { r -> "\nLast: " + if (r.ok) "OK – sent ${r.sent}, received ${r.received}, conflicts ${r.conflicts} (${r.durationMs} ms)" else "failed – ${r.error}" } ?: "") +
        if (s.syncing) "\nSyncing…" else ""

    private fun build(): Unit = screen("Phone & tablet") {
        val role = core.settings.string(Settings.DEVICE_ROLE)
        p("Both devices run the same Clu (entity ${core.store.entityId.take(8)}). This device is the ${if (role == "home") "home/base" else "portable"} device: ${core.settings.string(Settings.DEVICE_NAME)}.", C.TEXT)
        syncStatus = p(describe(app.sync.state.value), C.MUTED, 12f)
        h1("Paired devices")
        val peers = core.syncEngine.peers()
        if (peers.isEmpty()) p("None yet.")
        peers.forEach { pr ->
            card {
                p("${pr.name} · ${pr.role}", C.TEXT, 15f)
                p("Paired ${fmtTime(pr.pairedAt)} · last seen ${pr.lastSeen?.let { fmtTime(it) } ?: "never"} at ${pr.lastAddress ?: "?"} · last sync ${pr.lastSyncAt?.let { fmtTime(it) } ?: "never"}", C.MUTED, 12f)
                smallButton("Unpair") { verifyOwner("Unpair ${pr.name}") { if (it) { core.syncEngine.removePeer(pr.deviceId); build() } } }
            }
        }
        row {
            smallButton("Sync now") { scope.launch { val r = app.sync.syncNow(); toast(if (r.ok) "Synced: ↑${r.sent} ↓${r.received}" else "Sync failed: ${r.error}"); build() } }
            smallButton("Settings") { startActivity(Intent(this@DevicesActivity, SettingsCategoryActivity::class.java).putExtra("cat", "DEVICES")) }
        }
        h1("Pair a new device")
        p("1. On the tablet (home): tap \"Allow pairing\". 2. On the phone: tap \"Pair with…\" and choose the tablet's address (or the hotspot gateway when the tablet hosts nothing). 3. Confirm the 6-digit code matches on both.", C.MUTED, 12f)
        row {
            smallButton("Allow pairing (2 min)") { verifyOwner("Allow a device to pair") { ok -> if (ok) { app.sync.openPairing(); toast("Pairing open for 2 minutes.") } } }
            smallButton("Pair with…") {
                val cands = (app.sync.locator.candidates() + app.sync.state.value.discovered).distinct()
                val opts = cands + "Enter address…"
                choose("Pair with", opts) { i ->
                    val go = { addr: String -> scope.launch { val r = app.sync.pairWith(addr); toast(if (r.ok) "Paired and synced." else "Pairing failed: ${r.error}"); build() } }
                    if (i == cands.size) prompt("Address (host or host:port)") { go(it) } else go(cands[i])
                }
            }
        }
        h1("History")
        core.syncEngine.history(20).forEach { h -> p("${fmtTime(h.startedAt)} · ${h.peer} · ↑${h.sent} ↓${h.received} · conflicts ${h.conflicts}${h.error?.let { " · ⚠ $it" } ?: ""}", C.MUTED, 12f) }
    }
}

class PreferencesActivity : BaseActivity() {
    override fun onResume() { super.onResume(); build() }
    private fun build(): Unit = screen("Learned preferences") {
        p("These change how Clu answers (they are added to its instructions). Adaptation: ${if (core.settings.bool(Settings.LEARNING_ENABLED)) "on" else "off"}.", C.MUTED, 12f)
        val prefs = core.prefs.all()
        if (prefs.isEmpty()) p("Nothing learned yet.")
        prefs.forEach { pr ->
            val dim = core.prefs.dimensions.firstOrNull { it.key == pr.key }
            card {
                p("${dim?.title ?: pr.key}: ${dim?.labels?.get(pr.value) ?: pr.value}", C.TEXT, 15f)
                p("Source: ${pr.source.label} · ${fmtTime(pr.updatedAt)}${if (pr.note.isNotBlank()) " · ${pr.note}" else ""}", C.MUTED, 12f)
                row {
                    if (dim != null) smallButton("Change") { val keys = dim.labels.keys.toList(); choose(dim.title, keys.map { dim.labels.getValue(it) }) { i ->
                        core.prefs.set(dim.key, keys[i], ai.clu.core.learning.PreferenceSource.MANUAL); build() } }
                    smallButton("Remove") { core.prefs.remove(pr.key); build() }
                }
            }
        }
        h1("Set a preference")
        core.prefs.dimensions.filter { d -> prefs.none { it.key == d.key } }.forEach { d ->
            smallButton(d.title) { val keys = d.labels.keys.toList(); choose(d.title, keys.map { d.labels.getValue(it) }) { i -> core.prefs.set(d.key, keys[i], ai.clu.core.learning.PreferenceSource.MANUAL); build() } }
        }
        button("Reset all learned preferences") { confirm("Reset?", "All learned preferences will be removed.") { core.prefs.resetAll(); build() } }
        h1("Feedback history")
        core.prefs.feedbackHistory(30).forEach { (k, r, c) -> p("$k · $r → $c", C.MUTED, 12f) }
        button("Learning settings") { startActivity(Intent(this@PreferencesActivity, SettingsCategoryActivity::class.java).putExtra("cat", "LEARNING")) }
    }
}

class ToolHistoryActivity : BaseActivity() {
    override fun onResume() {
        super.onResume()
        screen("Tool history") {
            h1("Suggestions")
            val sugg = ai.clu.core.diag.ImprovementAdvisor.suggestions(core.tools.reliability(), core.diag.perfSummary())
            if (sugg.isEmpty()) p("No improvement suggestions from the measurements so far.")
            sugg.forEach { sg -> card { p(sg.title, C.TEXT); p(sg.detail, C.MUTED, 12f)
                sg.settingKey?.let { k -> ai.clu.core.config.Settings[k]?.let { d -> smallButton("Open ${d.category.title}") {
                    startActivity(Intent(this@ToolHistoryActivity, SettingsCategoryActivity::class.java).putExtra("cat", d.category.name)) } } } } }
            h1("Reliability")
            core.tools.reliability().forEach { r -> p("${r.tool}: ${r.runs} runs, ${r.failures} failed${r.lastError?.let { " · last error: $it" } ?: ""}", if (r.failures > 0) C.WARN else C.MUTED, 12f) }
            h1("Recent runs")
            core.tools.history(150).forEach { t ->
                p("${fmtTime(t.startedAt)} · ${t.tool} ${if (t.success) (if (t.verified) "✓ verified" else "○ unverified") else "✗ failed"} · ${t.durationMs} ms · by ${t.actor}\n${t.output.take(200)}${t.error?.let { "\n⚠ $it" } ?: ""}",
                    if (t.success) C.MUTED else C.WARN, 12f)
            }
        }
    }
}
