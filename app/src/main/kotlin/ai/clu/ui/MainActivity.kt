package ai.clu.ui

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.config.UiPolicy
import ai.clu.core.conversation.Attachment
import ai.clu.core.conversation.Message
import ai.clu.core.conversation.Role
import ai.clu.core.learning.AlternativeOffer
import ai.clu.core.learning.PreferenceQuestion
import ai.clu.core.llm.LlmStatus
import ai.clu.core.memory.MemoryProposal
import ai.clu.core.voice.TurnState
import ai.clu.proactive.ProactiveScheduler
import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/** Chat screen. Clu's single conversation surface (text + voice), with honest status indicators. */
class MainActivity : BaseActivity() {
    private sealed class Item {
        data class Msg(val m: Message) : Item()
        data class Streaming(val text: String) : Item()
        data class Trace(val text: String) : Item()
        data class Question(val q: PreferenceQuestion) : Item()
        data class Alternatives(val messageId: String, val offer: AlternativeOffer, val a: String, val b: String) : Item()
        data class Proposal(val p: MemoryProposal) : Item()
        data class Error(val text: String, val retry: String) : Item()
    }

    private lateinit var list: ListView
    private lateinit var adapter: RowAdapter<Item>
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var micBtn: Button
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var attachRow: LinearLayout
    private val extra = mutableListOf<Item>()
    private var streaming: String? = null
    private var turnJob: Job? = null
    private val pendingAttachments = mutableListOf<Pair<Attachment, String?>>() // attachment + extracted text
    private var redirected = false // startup failed or a crash report is pending: CrashReportActivity shown instead

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!app.isReady || ai.clu.CrashReport.pending(this) != null) {
            redirected = true
            startActivity(Intent(this, ai.clu.CrashReportActivity::class.java)); finish(); return
        }
        buildUi()
        scope.launch { app.conversation.collectLatest { refresh() } }
        scope.launch { core.store.changes.collect { t -> if ((t == "messages" || t == "conversations") && streaming == null) refresh() } }
        scope.launch {
            combine(core.llm.state, app.voice.state, app.sync.state) { a, b, c -> Triple(a, b, c) }.collect { (l, v, s) -> updateStatus(l, v, s) }
        }
        if (core.store.meta("onboarded") == null) onboarding()
        else if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && core.store.meta("asked_notif") == null) {
            core.store.setMeta("asked_notif", "1"); requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        if (redirected) return
        title.text = core.personas.active().name
        app.applyServicePolicy()
        scope.launch {
            delay(800)
            ProactiveScheduler.evaluate(app, appOpenedNow = true)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        // Top bar
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(C.SURFACE) }
        bar.addView(TextView(this).apply { text = "☰"; textSize = 22f; setTextColor(C.TEXT); setPadding(dp(8), 0, dp(12), 0)
            setOnClickListener { startActivity(Intent(this@MainActivity, ConversationsActivity::class.java)) } })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(this).apply { textSize = 19f; setTextColor(C.TEXT); typeface = Typeface.DEFAULT_BOLD; text = core.personas.active().name }
        status = TextView(this).apply { textSize = 12f; setTextColor(C.MUTED) }
        titles.addView(title); titles.addView(status)
        bar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(TextView(this).apply { text = "+"; textSize = 24f; setTextColor(C.TEXT); setPadding(dp(12), 0, dp(12), 0); setOnClickListener { app.openConversation(null) } })
        bar.addView(TextView(this).apply { text = "⋮"; textSize = 24f; setTextColor(C.TEXT); setPadding(dp(12), 0, dp(8), 0); setOnClickListener { showMenu(it) } })
        root.addView(bar)

        adapter = RowAdapter(emptyList()) { item, ctx -> rowFor(item) }
        list = ListView(this).apply { this.adapter = this@MainActivity.adapter; divider = null; transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL; isStackFromBottom = true
            setPadding(dp(8), dp(8), dp(8), dp(8)); clipToPadding = false }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        attachRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), 0, dp(8), 0) }
        root.addView(attachRow)

        val inputBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(10)) }
        inputBar.addView(Button(this).apply { text = "📎"; background = null; setTextColor(C.TEXT); setOnClickListener { pickAttachment() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        input = EditText(this).apply { hint = "Message ${core.settings.string(Settings.CLU_NAME)}"; setTextColor(C.TEXT); setHintTextColor(C.MUTED)
            background = roundBg(C.SURFACE2, 40f); setPadding(dp(14), dp(10), dp(14), dp(10)); maxLines = 5 }
        inputBar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        micBtn = Button(this).apply { text = "🎙"; background = roundBg(C.SURFACE2, 60f); setTextColor(C.TEXT) }
        micBtn.setOnTouchListener { _, e -> onMicTouch(e) }
        inputBar.addView(micBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(6) })
        sendBtn = Button(this).apply { text = "➤"; background = roundBg(C.ACCENT, 60f); setTextColor(C.TEXT); setOnClickListener { onSendOrStop() } }
        inputBar.addView(sendBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(6) })
        root.addView(inputBar)
        setContentView(root)
    }

    private fun updateStatus(l: ai.clu.core.llm.LlmState, v: ai.clu.voice.VoiceUiState, s: ai.clu.sync.SyncUiState) {
        val model = when (l.status) {
            LlmStatus.NO_MODEL -> "no model"; LlmStatus.UNLOADED -> "model idle"; LlmStatus.LOADING -> "loading model…"
            LlmStatus.READY -> "model ready"; LlmStatus.GENERATING -> "thinking…"; LlmStatus.ERROR -> "model error"
        }
        val net = if (app.platform.isOnline()) "online" else "offline"
        val voice = when (v.turn) {
            TurnState.OFF -> ""; TurnState.WAITING_FOR_WAKE -> " · waiting for \"${core.settings.wakeWords().first { it.primary }.phrase}\""
            TurnState.LISTENING -> " · listening" + (if (v.partial.isNotBlank()) ": ${v.partial.takeLast(40)}" else ""); TurnState.PROCESSING -> " · processing"
            TurnState.SPEAKING -> " · speaking"
        }
        val env = if (v.running) " · ${v.environment.name.lowercase()} (${"%.0f".format(v.noiseDb)} dB)" else ""
        status.text = "$model · $net$voice$env" + (if (v.error.isNotBlank()) " · ⚠ ${v.error}" else "")
        status.setTextColor(if (l.status == LlmStatus.ERROR || v.error.isNotBlank()) C.WARN else C.MUTED)
        micBtn.background = roundBg(if (v.turn == TurnState.LISTENING) C.ACCENT else C.SURFACE2, 60f)
        sendBtn.text = if (streaming != null || v.turn == TurnState.SPEAKING) "■" else "➤"
    }

    private fun refresh() {
        val convId = app.currentConversation()
        val msgs = core.conversations.messages(convId).map { Item.Msg(it) as Item }
        adapter.update(msgs + extra + listOfNotNull(streaming?.let { Item.Streaming(it) }))
        title.text = core.personas.active().name
    }

    private fun scrollToEnd() = list.post { list.setSelection(adapter.count - 1) }

    // ---------------------------------------------------------------- sending
    private fun onSendOrStop() {
        if (streaming != null || app.speech.busy) { core.agent.cancel(); app.speech.stop(); return }
        val text = input.text.toString().trim()
        if (text.isEmpty() && pendingAttachments.isEmpty()) return
        input.setText("")
        send(text)
    }

    private fun send(text: String, verified: Boolean = false) {
        app.lastInteractionAt = System.currentTimeMillis()
        val atts = pendingAttachments.toList(); pendingAttachments.clear(); attachRow.removeAllViews()
        val fileText = atts.mapNotNull { (a, t) -> t?.let { "\n\n[Attached file: ${a.name}]\n$it" } }.joinToString("")
        val imageNote = if (atts.any { it.first.kind == "image" }) "\n\n[The owner attached an image. You cannot see images with the current text-only model; say so if relevant.]" else ""
        val fullText = (text.ifEmpty { "(attachment)" }) + fileText + imageNote
        extra.clear()
        streaming = ""
        refresh(); scrollToEnd()
        val actor = app.ownerActor().let { if (verified) it.copy(verifiedAt = System.currentTimeMillis()) else it }
        turnJob = scope.launch {
            val speak = UiPolicy.shouldSpeak(core.settings, viaVoice = false, nowMs = System.currentTimeMillis())
            val cfg = ai.clu.core.voice.VoiceConfig.from(core.settings)
            val chunker = ai.clu.core.voice.SentenceChunker()
            core.agent.respond(TurnRequest(app.currentConversation(), fullText, actor, attachments = atts.map { it.first })).collect { ev ->
                when (ev) {
                    is AgentEvent.Text -> { streaming = (streaming ?: "") + ev.delta; if (speak && cfg.incremental) chunker.push(ev.delta).forEach { app.speech.speak(it, cfg) }; refreshStreaming() }
                    AgentEvent.ResetText -> { streaming = ""; refreshStreaming() }
                    is AgentEvent.Status -> {}
                    is AgentEvent.ToolStarted -> if (UiPolicy.showToolTraces(core.settings)) { extra += Item.Trace("⚙ ${ev.tool} ${ev.args}"); refreshStreaming() }
                    is AgentEvent.ToolFinished -> if (UiPolicy.showToolTraces(core.settings)) {
                        extra += Item.Trace((if (ev.result.success) (if (ev.result.verified) "✓ " else "○ ") else "✗ ") + "${ev.tool}: ${ev.result.summary.take(300)}"); refreshStreaming() }
                    is AgentEvent.NeedsVerification -> verifyOwner("Clu needs to confirm it's you: ${ev.reason}") { ok -> if (ok) send(text, verified = true) }
                    is AgentEvent.Question -> extra += Item.Question(ev.question)
                    is AgentEvent.Alternatives -> extra += Item.Alternatives(ev.messageId, ev.offer, ev.textA, ev.textB)
                    is AgentEvent.ProposalCreated -> extra += Item.Proposal(ev.proposal)
                    is AgentEvent.Control -> if (ev.command == "stop_listening") app.stopListening()
                    is AgentEvent.Error -> extra += Item.Error(ev.text, text)
                    is AgentEvent.Done -> {
                        if (speak) { if (cfg.incremental) chunker.flush().forEach { app.speech.speak(it, cfg) } else app.speech.speak(ev.text, cfg) }
                    }
                    is AgentEvent.UserMessageSaved -> {}
                }
            }
            streaming = null
            refresh(); scrollToEnd()
        }
    }

    private fun refreshStreaming() { refresh(); scrollToEnd() }

    // ---------------------------------------------------------------- voice
    private fun onMicTouch(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_DOWN && e.action != MotionEvent.ACTION_UP) return true
        if (!app.hasMic()) { if (e.action == MotionEvent.ACTION_DOWN) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return true }
        when (UiPolicy.micBehavior(core.settings)) {
            UiPolicy.MicBehavior.HOLD_TO_TALK -> if (e.action == MotionEvent.ACTION_DOWN) app.voice.activate() else app.voice.endUtteranceNow()
            UiPolicy.MicBehavior.TAP_AUTO_STOP, UiPolicy.MicBehavior.CONVERSATION -> if (e.action == MotionEvent.ACTION_UP) {
                val v = app.voice.state.value
                if (v.running && v.turn == TurnState.LISTENING && !app.voice.wakeMode) app.voice.stop() else app.voice.activate()
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) { app.applyServicePolicy(); toast("Microphone enabled. Audio is processed on this device.") }
            else toast("Without microphone permission Clu can't hear you. You can still type.")
        }
    }

    // ---------------------------------------------------------------- rows
    private fun bubble(text: String, mine: Boolean, sub: String? = null, onLong: (() -> Unit)? = null): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = if (mine) Gravity.END else Gravity.START; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val tv = TextView(this).apply {
            this.text = text; textSize = 15.5f; setTextColor(C.TEXT); setLineSpacing(0f, 1.15f)
            background = roundBg(if (mine) C.ACCENT else C.SURFACE, 32f); setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
            setOnLongClickListener { onLong?.invoke() ?: copy(text); true }
        }
        wrap.addView(tv, LinearLayout.LayoutParams(-2, -2))
        sub?.takeIf { it.isNotBlank() }?.let { wrap.addView(TextView(this).apply { this.text = it; textSize = 11f; setTextColor(C.MUTED); setPadding(dp(6), dp(2), dp(6), 0) }) }
        return wrap
    }

    private fun rowFor(item: Item): View = when (item) {
        is Item.Msg -> {
            val m = item.m
            val mine = m.role == Role.USER
            val subParts = mutableListOf<String>()
            if (!mine) {
                UiPolicy.sourceBadge(core.settings, m.meta)?.let { subParts += it }
                if (UiPolicy.showLatency(core.settings)) UiPolicy.latencyLine(m.meta).takeIf { it.isNotBlank() }?.let { subParts += it }
                if (m.meta.interrupted) subParts += "interrupted"
            } else {
                if (m.meta.speaker.isNotBlank()) subParts += "🎤 " + (core.people.get(m.meta.speaker)?.name ?: m.meta.speaker)
                m.meta.attachments.forEach { a -> subParts += "📎 ${a.name}" + if (a.kind == "image") " (not analysed – no vision model)" else "" }
            }
            val shown = if (mine) m.content.substringBefore("\n\n[Attached file:").substringBefore("\n\n[The owner attached an image") else m.content
            val v = bubble(shown, mine, subParts.joinToString(" · "))
            m.meta.attachments.firstOrNull { it.kind == "image" }?.let { a -> v.setOnClickListener { showImage(a.uri) } }
            v
        }
        is Item.Streaming -> bubble(item.text.ifEmpty { "…" }, false, "generating")
        is Item.Trace -> TextView(this).apply { text = item.text; textSize = 12f; setTextColor(C.MUTED); setPadding(dp(12), dp(2), dp(12), dp(2)); typeface = Typeface.MONOSPACE }
        is Item.Error -> LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4))
            p(item.text, C.WARN); smallButton("Retry") { send(item.retry) }
        }
        is Item.Question -> LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            card {
                p("Quick question to tailor my answers", C.MUTED, 12f); p(item.q.text, C.TEXT, 15f)
                item.q.options.forEach { (k, label) -> smallButton(label) { core.prefs.answerQuestion(item.q.id, k); extra.remove(item); refresh(); toast("Noted — I'll apply that from now on.") } }
                smallButton("Not now") { core.prefs.dismissQuestion(item.q.id); extra.remove(item); refresh() }
            }
        }
        is Item.Alternatives -> LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            card {
                p("Which reply style do you prefer?", C.TEXT, 15f)
                val remember = check("Remember this as my preference", true)
                p("A — ${core.prefs.dimensions.first { it.key == item.offer.dimension }.labels[item.offer.optionA]}", C.ACCENT, 13f); p(item.a, C.TEXT)
                smallButton("Choose A") { core.agent.chooseAlternative(item.messageId, item.offer, true, item.a, remember.isChecked); extra.remove(item); refresh() }
                p("B — ${core.prefs.dimensions.first { it.key == item.offer.dimension }.labels[item.offer.optionB]}", C.ACCENT, 13f); p(item.b, C.TEXT)
                smallButton("Choose B") { core.agent.chooseAlternative(item.messageId, item.offer, false, item.b, remember.isChecked); extra.remove(item); refresh() }
            }
        }
        is Item.Proposal -> LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            card {
                p("Clu suggests remembering (needs your approval):", C.MUTED, 12f); p(item.p.text, C.TEXT); p(item.p.rationale, C.MUTED, 12f)
                row {
                    smallButton("Approve") { core.proposals.approve(item.p.id); extra.remove(item); refresh(); toast("Saved to memory.") }
                    smallButton("Edit") { prompt("Edit before saving", item.p.text, multiline = true) { t -> core.proposals.approve(item.p.id, t); extra.remove(item); refresh() } }
                    smallButton("Reject") { core.proposals.reject(item.p.id); extra.remove(item); refresh() }
                }
            }
        }
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Clu", text)); toast("Copied")
    }

    // ---------------------------------------------------------------- attachments
    private fun pickAttachment() = choose("Attach", listOf("Picture", "Text file (.txt, .md, .csv, .json)", "Other file")) { i ->
        when (i) {
            0 -> startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES), REQ_IMAGE)
            1 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*"), REQ_FILE)
            else -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_FILE)
        }
    }

    @Deprecated("framework API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        val name = displayName(uri)
        val dir = File(filesDir, "attachments").apply { mkdirs() }
        val dest = File(dir, System.currentTimeMillis().toString() + "_" + name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        runCatching { contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { o -> i.copyTo(o) } } }.onFailure { toast("Could not read the file."); return }
        val mime = contentResolver.getType(uri) ?: ""
        val att = when {
            requestCode == REQ_IMAGE || mime.startsWith("image/") ->
                Attachment("image", dest.absolutePath, name, mime, analyzed = false, note = "Image understanding needs a vision model (not in this build).") to null
            mime.startsWith("text/") || name.matches(Regex(".*\\.(txt|md|csv|json|log)$", RegexOption.IGNORE_CASE)) ->
                Attachment("file", dest.absolutePath, name, mime, analyzed = true) to dest.readText().take(20_000)
            else -> Attachment("file", dest.absolutePath, name, mime, analyzed = false, note = "Clu can't read this file type.") to null
        }
        pendingAttachments += att
        attachRow.addView(TextView(this).apply { text = "📎 $name" + if (!att.first.analyzed) " (won't be read)" else ""; setTextColor(C.MUTED); setPadding(dp(8), dp(4), dp(8), dp(4)) })
    }

    private fun displayName(uri: Uri): String = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null } ?: "attachment"

    private fun showImage(path: String) {
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return
        AlertDialog.Builder(this).setView(ImageView(this).apply { setImageBitmap(bmp); adjustViewBounds = true }).setPositiveButton("Close", null).show()
    }

    // ---------------------------------------------------------------- menu & onboarding
    private fun showMenu(anchor: View) {
        val entries = mutableListOf(
            "Memory & diary" to MemoryActivity::class.java, "Memory suggestions" to ProposalsActivity::class.java,
            "Characters" to CharactersActivity::class.java, "People & security" to PeopleActivity::class.java,
            "Custom commands" to CommandsActivity::class.java, "AI models" to ModelsActivity::class.java,
            "Voice" to VoiceActivity::class.java, "Phone & tablet" to DevicesActivity::class.java,
            "Learned preferences" to PreferencesActivity::class.java, "Tool history" to ToolHistoryActivity::class.java,
            "Settings" to SettingsActivity::class.java,
        )
        if (UiPolicy.diagnosticsVisible(core.settings)) entries += "Diagnostics" to DiagnosticsActivity::class.java
        val popup = PopupMenu(this, anchor)
        entries.forEachIndexed { i, (t, _) -> popup.menu.add(0, i, i, t) }
        popup.setOnMenuItemClickListener { mi -> startActivity(Intent(this, entries[mi.itemId].second)); true }
        popup.show()
    }

    private fun onboarding() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.p("I'm Clu. I run on this device; nothing you say leaves it unless you allow internet features.", C.TEXT)
        val name = box.field("Your name", "")
        val address = box.field("How should I address you? (optional, e.g. sir)", "")
        box.p("This device is my…", C.TEXT, 13f)
        val role = box.spinner(listOf("Portable device (phone)", "Home / base device (tablet)"), if (resources.configuration.smallestScreenWidthDp >= 600) 1 else 0)
        box.p("Next: load a language model in AI models (import a GGUF file or download one — always with your confirmation).", C.MUTED, 12f)
        AlertDialog.Builder(this).setTitle("Welcome").setView(ScrollView(this).apply { addView(box) }).setCancelable(false)
            .setPositiveButton("Start") { _, _ ->
                core.settings.set(Settings.OWNER_NAME, name.text.toString().trim())
                core.settings.set(Settings.OWNER_ADDRESS, address.text.toString().trim())
                core.settings.set(Settings.DEVICE_ROLE, if (role.selectedItemPosition == 1) "home" else "portable")
                core.people.owner()?.let { core.people.save(it.copy(name = name.text.toString().trim().ifBlank { "Owner" })) }
                core.store.setMeta("onboarded", "1")
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                startActivity(Intent(this, ModelsActivity::class.java))
            }.show()
    }

    companion object { const val REQ_IMAGE = 10; const val REQ_FILE = 11 }
}
