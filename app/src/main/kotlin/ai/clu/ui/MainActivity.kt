package ai.clu.ui

import ai.clu.core.config.Settings
import ai.clu.core.conversation.Message
import ai.clu.core.conversation.Role
import ai.clu.core.live.AgentPhase
import ai.clu.core.live.Link
import ai.clu.voice.EngineUi
import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Text chat: conversation history, the reply streaming in, and a bottom composer with send, stop and microphone.
 * Replies come from the same engine as voice chat (Clu's server or this device); the ⬡ button opens the voice HUD.
 * Status shown here is real: the dot is the actual connection, "Thinking…" appears only while Clu is thinking.
 */
class MainActivity : BaseActivity() {
    private sealed class Item {
        data class Msg(val m: Message) : Item()
        data class Live(val text: String, val thinking: Boolean) : Item()
        data class Problem(val text: String, val retry: String?) : Item()
        data class Suggestion(val p: ai.clu.core.memory.MemoryProposal) : Item()
    }

    private lateinit var list: ListView
    private lateinit var adapter: Adapter
    private lateinit var input: EditText
    private lateinit var actionBtn: TextView
    private lateinit var speakerBtn: TextView
    private lateinit var connDot: View
    private lateinit var connText: TextView
    private lateinit var emptyState: View
    private lateinit var greeting: TextView
    private var msgItems: List<Item> = emptyList()
    private var last = EngineUi()
    private var lastTyped: String? = null
    private var redirected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!app.isReady || ai.clu.CrashReport.pending(this) != null) {
            redirected = true
            startActivity(Intent(this, ai.clu.CrashReportActivity::class.java)); finish(); return
        }
        setContentView(build())
        scope.launch { app.conversation.collectLatest { refresh() } }
        scope.launch { core.store.changes.collect { t -> if (t == "messages" || t == "conversations" || t == "memory_proposals") refresh() } }
        scope.launch { app.engine.ui.collect { render(it) } }
        scope.launch { core.chat.state.collect { st -> if (!app.engine.server && (st.status != null || st.events.lastOrNull() is ai.clu.core.agent.AgentEvent.Error)) showItems() } }
        if (core.store.meta("onboarded") == null) onboarding()
    }

    override fun onStart() { super.onStart(); if (!redirected) { app.engine.screenVisible("chat", true); app.engine.startChat() } }

    private var restartedAt = 0L
    /** Return from the background → first frame drawn (Diagnostics → Performance: resume_to_frame_ms). */
    override fun onRestart() {
        super.onRestart(); restartedAt = System.nanoTime()
        window.decorView.post { window.decorView.postOnAnimation { if (restartedAt > 0) { core.diag.perf("resume_to_frame_ms", (System.nanoTime() - restartedAt) / 1e6); restartedAt = 0 } } }
    }

    override fun onStop() { if (!redirected) app.engine.screenVisible("chat", false); super.onStop() }

    override fun onResume() {
        super.onResume()
        if (redirected) return
        greeting.text = core.settings.string(Settings.OWNER_NAME).takeIf { it.isNotBlank() }?.let { "Hello, $it" } ?: "Hello"
        refresh()
    }

    // ------------------------------------------------------------------------------------------------ layout
    private fun build(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = backdrop() }
        // Top: menu, Clu pill with the real connection state, voice HUD.
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(6)) }
        top.addView(iconButton("☰", "Conversations and settings") { menu() }, LinearLayout.LayoutParams(dp(46), dp(46)))
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; background = glassBg(); setPadding(dp(14), dp(8), dp(14), dp(8))
            connDot = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.MUTED) } }
            addView(connDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
            addView(TextView(context).apply { text = "Clu"; textSize = 15f; setTextColor(C.TEXT); typeface = Typeface.DEFAULT_BOLD })
            connText = TextView(context).apply { textSize = 12f; setTextColor(C.MUTED); setPadding(dp(8), 0, 0, 0) }
            addView(connText)
            setOnClickListener { startActivity(Intent(this@MainActivity, ConnectionActivity::class.java)) }
            contentDescription = "Connection status"
        }
        top.addView(FrameLayout(this).apply { addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(iconButton("⬡", "Voice chat") { startActivity(Intent(this, VoiceChatActivity::class.java)) }.apply { setTextColor(C.ACCENT); textSize = 24f },
            LinearLayout.LayoutParams(dp(46), dp(46)))
        root.addView(top)

        // Conversation (or the empty state).
        val stage = FrameLayout(this)
        adapter = Adapter()
        list = ListView(this).apply {
            divider = null; dividerHeight = 0; transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL; isStackFromBottom = true
            setPadding(dp(12), dp(8), dp(12), dp(8)); clipToPadding = false; selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        list.adapter = adapter
        stage.addView(list, FrameLayout.LayoutParams(-1, -1))
        emptyState = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            val hud = HexHudView(context)
            addView(hud, LinearLayout.LayoutParams(dp(150), dp(140)))
            greeting = TextView(context).apply { textSize = 15f; setTextColor(C.MUTED); gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(4)) }
            addView(greeting)
            addView(TextView(context).apply { text = "How can I help?"; textSize = 26f; setTextColor(C.TEXT); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
        }
        stage.addView(emptyState, FrameLayout.LayoutParams(-1, -1))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        // Composer: speaker toggle, message field, mic / send / stop.
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(12)) }
        val field = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = glassBg(dp(26).toFloat()); setPadding(dp(6), 0, dp(14), 0)
            speakerBtn = TextView(context).apply { text = "🔊"; textSize = 16f; gravity = Gravity.CENTER; contentDescription = "Speak replies aloud"
                setOnClickListener { app.engine.setSpokenReplies(!last.spokenReplies) } }
            addView(speakerBtn, LinearLayout.LayoutParams(dp(40), dp(48)))
            input = EditText(context).apply {
                hint = "Message Clu"; setHintTextColor(C.MUTED); setTextColor(C.TEXT); textSize = 16f; background = null; maxLines = 5
                imeOptions = EditorInfo.IME_ACTION_SEND; setRawInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
                setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { updateAction() }
                })
            }
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        }
        bar.addView(field, LinearLayout.LayoutParams(0, dp(52), 1f))
        actionBtn = TextView(this).apply { gravity = Gravity.CENTER; textSize = 20f; setTextColor(Color.WHITE); setOnClickListener { onAction() } }
        bar.addView(actionBtn, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(10) })
        root.addView(bar)
        applySystemInsets(root)
        updateAction()
        return root
    }

    private fun backdrop() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.BLACK, Color.parseColor("#05080C"), Color.parseColor("#0A1420")))

    private fun iconButton(icon: String, label: String, onClick: () -> Unit) = TextView(this).apply {
        text = icon; textSize = 20f; gravity = Gravity.CENTER; setTextColor(C.TEXT); background = glassBg(); contentDescription = label
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------------------------------------------ state
    private fun render(s: EngineUi) {
        last = s
        val (dot, label) = when {
            !s.server -> C.MUTED to "on-device"
            s.link == Link.CONNECTED && s.phase == AgentPhase.ABSENT -> C.WARN to "waiting for agent"
            s.link == Link.CONNECTED -> C.ACCENT to ""
            s.link == Link.CONNECTING || s.link == Link.RECONNECTING -> C.WARN to "connecting…"
            s.link == Link.NOT_CONFIGURED -> C.ERROR to "not set up"
            else -> C.ERROR to "offline"
        }
        (connDot.background as GradientDrawable).setColor(dot); connText.text = label; connText.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
        speakerBtn.alpha = if (s.spokenReplies) 1f else 0.4f
        input.hint = if (s.micOn) (s.userLive.ifBlank { "Listening…" }) else "Message Clu"
        updateAction()
        showItems()
    }

    private fun updateAction() {
        val typing = input.text.isNotBlank()
        val (icon, desc) = when {
            last.busy -> "■" to "Stop"
            typing -> "↑" to "Send"
            last.micOn -> "🎙" to "Stop listening"
            else -> "🎤" to "Speak"
        }
        actionBtn.text = icon; actionBtn.contentDescription = desc
        actionBtn.background = if (last.busy || typing || last.micOn) accentBg() else glassBg()
    }

    private fun onAction() {
        when {
            last.busy -> app.engine.stop()
            input.text.isNotBlank() -> send()
            else -> toggleMic()
        }
    }

    private fun toggleMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this).setTitle("Speak to Clu")
                .setMessage("Clu needs the microphone to hear you. Audio goes only to your Clu server (or stays on this device when it answers offline).")
                .setPositiveButton("Allow microphone") { _, _ -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
                .setNegativeButton("Not now", null).show()
            return
        }
        // Voice in, answer in the chat (spoken too only if the speaker is on).
        app.engine.setMic(!last.micOn)
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || last.busy) return
        lastTyped = text
        if (app.engine.send(text)) input.setText("")
        scrollToEnd()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && app.hasMic()) app.engine.setMic(true)
    }

    // ------------------------------------------------------------------------------------------------ list
    private fun refresh() {
        val conv = app.currentConversation()
        msgItems = core.conversations.messages(conv).filter { it.role == Role.USER || it.role == Role.ASSISTANT }.map { Item.Msg(it) } +
            // Memory suggestions Clu made in this conversation: saved only if the owner approves here or in Memory.
            core.proposals.pending().filter { it.conversationId == conv && it.kind != ai.clu.core.memory.ProposalKind.DIARY }.map { Item.Suggestion(it) }
        showItems()
    }

    private fun showItems() {
        val extra = mutableListOf<Item>()
        val status = if (!last.server) core.chat.state.value.status else null
        when {
            last.agentLive.isNotBlank() -> extra += Item.Live(last.agentLive, false)
            status != null && last.thinking -> extra += Item.Live(status, true)
            last.thinking -> extra += Item.Live("", true)
        }
        val chatError = if (!last.server) (core.chat.state.value.events.lastOrNull { it is ai.clu.core.agent.AgentEvent.Error } as? ai.clu.core.agent.AgentEvent.Error)?.text else null
        (last.error ?: chatError)?.let { extra += Item.Problem(it, lastTyped) }
        val wasAtEnd = list.lastVisiblePosition >= adapter.count - 2
        adapter.items = msgItems + extra
        adapter.notifyDataSetChanged()
        emptyState.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
        if (wasAtEnd) scrollToEnd()
    }

    private fun scrollToEnd() = list.post { if (adapter.count > 0) list.setSelection(adapter.count - 1) }

    private inner class Adapter : BaseAdapter() {
        var items: List<Item> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = when (val it = items[position]) {
            is Item.Msg -> if (it.m.role == Role.USER) userRow(it.m) else cluRow(it.m.content, badge(it.m), muted = false, msg = it.m)
            is Item.Live -> cluRow(if (it.thinking) it.text.ifBlank { "Thinking…" } else it.text, null, muted = it.thinking)
            is Item.Problem -> problemRow(it.text, it.retry)
            is Item.Suggestion -> suggestionRow(it.p)
        }
    }

    private fun userRow(m: Message): View = LinearLayout(this).apply {
        gravity = Gravity.END; setPadding(dp(48), dp(6), 0, dp(6))
        addView(TextView(context).apply {
            this.text = m.content; textSize = 16f; setTextColor(C.TEXT); setLineSpacing(0f, 1.15f)
            background = roundBg(C.SURFACE2, dp(20).toFloat(), C.BORDER); setPadding(dp(16), dp(11), dp(16), dp(11))
            setOnLongClickListener { messageMenu(m); true }
        })
    }

    /** Under a reply: where it came from. A searched answer says so (with its sources below); others show their engine. */
    private fun badge(m: Message): String? =
        listOfNotNull(ai.clu.core.config.UiPolicy.sourceBadge(core.settings, m.meta), "interrupted".takeIf { m.meta.interrupted }).joinToString(" · ").ifEmpty { null }

    private fun cluRow(text: String, sub: String?, muted: Boolean = false, msg: Message? = null): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), dp(28), dp(8))
        addView(TextView(context).apply { this.text = "⬡"; textSize = 18f; setTextColor(C.ACCENT); gravity = Gravity.CENTER_HORIZONTAL },
            LinearLayout.LayoutParams(dp(30), -2))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                this.text = text; textSize = 16f; setTextColor(if (muted) C.MUTED else C.TEXT); setLineSpacing(0f, 1.2f)
                setOnLongClickListener { if (msg != null) messageMenu(msg) else copy(text); true }
            })
            sub?.let { addView(TextView(context).apply { this.text = it; textSize = 11f; setTextColor(C.MUTED); setPadding(0, dp(4), 0, 0) }) }
            // Answer first: the sources are one collapsed line ("Sources (3)" / "Source: GDACS …"); tapping it lists them
            // exactly as the provider returned them, and tapping one opens it in the browser.
            val sources = msg?.meta?.sources.orEmpty()
            ai.clu.core.config.UiPolicy.sourcesSummary(sources)?.let { summary ->
                val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE
                    sources.forEachIndexed { i, src -> addView(sourceRow(i + 1, src)) } }
                addView(TextView(context).apply {
                    this.text = "$summary ▸"; textSize = 12f; setTextColor(C.MUTED); setPadding(0, dp(6), 0, dp(6)); minimumHeight = dp(32)
                    contentDescription = "$summary. Double-tap to show."
                    setOnClickListener {
                        val open = list.visibility != View.VISIBLE
                        list.visibility = if (open) View.VISIBLE else View.GONE
                        this.text = "$summary ${if (open) "▾" else "▸"}"
                        contentDescription = "$summary. Double-tap to ${if (open) "hide" else "show"}."
                    }
                })
                addView(list)
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun sourceRow(n: Int, src: ai.clu.core.conversation.SourceRef): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(8), dp(8)); minimumHeight = dp(48)
        background = roundBg(C.SURFACE, dp(12).toFloat(), C.BORDER)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        addView(TextView(context).apply { this.text = src.title; textSize = 14f; setTextColor(C.ACCENT); maxLines = 2 })
        addView(TextView(context).apply {
            this.text = (runCatching { java.net.URI(src.url).host?.removePrefix("www.") }.getOrNull() ?: src.url) + if (src.snippet.isNotBlank()) " — " + src.snippet.take(140) else ""
            textSize = 12f; setTextColor(C.MUTED); maxLines = 2
        })
        // What Clu actually did with the source: opened the page or used only the provider's snippet (and why).
        val status = listOfNotNull(
            if (src.opened) "Page read" else if (src.note.isNotBlank()) "Snippet only (${src.note})" else "Snippet only",
            src.published.takeIf { it.isNotBlank() }?.let { if (it.startsWith("updated")) it else "published $it" },
            src.retrievedAt.takeIf { it > 0 }?.let { "retrieved ${fmtTime(it)}" })
        addView(TextView(context).apply { this.text = status.joinToString(" · "); textSize = 11f; setTextColor(C.MUTED); setPadding(0, dp(2), 0, 0) })
        contentDescription = "Source $n: ${src.title}. ${status.joinToString(". ")}. Opens in the browser."
        setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(src.url))) }.onFailure { toast("No browser available") }
        }
    }

    private fun suggestionRow(p: ai.clu.core.memory.MemoryProposal): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(30), dp(6), dp(16), dp(6))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; background = roundBg(C.SURFACE, dp(14).toFloat(), C.BORDER); setPadding(dp(14), dp(10), dp(14), dp(6))
            addView(TextView(context).apply { this.text = "Remember this? (Clu's suggestion — not saved unless you approve)"; textSize = 12f; setTextColor(C.MUTED) })
            addView(TextView(context).apply { this.text = p.text; textSize = 15f; setTextColor(C.TEXT); setPadding(0, dp(4), 0, dp(4)) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                fun action(label: String, f: () -> Unit) = addView(TextView(context).apply {
                    this.text = label; textSize = 14f; setTextColor(C.ACCENT); setPadding(dp(4), dp(10), dp(18), dp(10)); setOnClickListener { f() } })
                action("Save") { runCatching { core.proposals.approve(p.id) }.onSuccess { toast("Saved to memory") }.onFailure { toast(it.message ?: "Couldn't save") } }
                action("Edit") { prompt("Edit before saving", p.text, multiline = true) { t -> runCatching { core.proposals.approve(p.id, t) }.onSuccess { toast("Saved to memory") } } }
                action("Dismiss") { core.proposals.reject(p.id) }
            })
        })
    }

    /** Long press on a message: copy, keep it in the diary, or ask Clu to remember it (explicit, so it is saved directly). */
    private fun messageMenu(m: Message) {
        choose("Message", listOf("Copy", "Save to diary", "Remember this")) { i ->
            when (i) {
                0 -> copy(m.content)
                1 -> runCatching { core.diary.add(if (m.role == Role.USER) m.content else "Clu: ${m.content}",
                        source = if (m.role == Role.USER) ai.clu.core.diary.DiarySource.MANUAL else ai.clu.core.diary.DiarySource.CONVERSATION,
                        conversationId = m.conversationId) }.onSuccess { toast("Saved to your diary") }.onFailure { toast(it.message ?: "Couldn't save") }
                2 -> runCatching { core.memories.save(ai.clu.core.memory.Memory(category = ai.clu.core.memory.MemoryCategory.classify(m.content),
                        title = m.content.take(60), content = m.content, source = if (m.role == Role.USER) ai.clu.core.memory.MemorySource.OWNER_INSTRUCTION
                        else ai.clu.core.memory.MemorySource.CONVERSATION)) }.onSuccess { toast("Saved to memory") }.onFailure { toast(it.message ?: "Couldn't save") }
            }
        }
    }

    private fun problemRow(text: String, retry: String?): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(30), dp(6), dp(16), dp(6))
        addView(TextView(context).apply { this.text = text; textSize = 14f; setTextColor(C.WARN) })
        if (retry != null) addView(TextView(context).apply {
            this.text = "Retry"; textSize = 14f; setTextColor(C.ACCENT); setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { app.liveKit.live.onError(null); input.setText(retry); send() }
        })
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Clu", text)); toast("Copied")
    }

    // ------------------------------------------------------------------------------------------------ menu
    private fun menu() {
        val items = listOf("New chat", "Conversations", "Voice chat", "Memory", "Diary", "Connection", "Settings")
        choose("Clu", items) { i ->
            when (i) {
                0 -> app.openConversation(null)
                1 -> startActivity(Intent(this, ConversationsActivity::class.java))
                2 -> startActivity(Intent(this, VoiceChatActivity::class.java))
                3 -> startActivity(Intent(this, MemoryActivity::class.java))
                4 -> startActivity(Intent(this, DiaryActivity::class.java))
                5 -> startActivity(Intent(this, ConnectionActivity::class.java))
                6 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
    }

    private fun onboarding() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.p("I'm Clu. Talk to me by voice (⬡) or type here.", C.TEXT)
        val name = box.field("Your name", "")
        box.p("Next: connect me to my server in Connection, or choose \"This device\" to use the on-device model.", C.MUTED, 12f)
        AlertDialog.Builder(this).setTitle("Welcome").setView(ScrollView(this).apply { addView(box) }).setCancelable(false)
            .setPositiveButton("Continue") { _, _ ->
                core.settings.set(Settings.OWNER_NAME, name.text.toString().trim())
                core.people.owner()?.let { core.people.save(it.copy(name = name.text.toString().trim().ifBlank { "Owner" })) }
                core.store.setMeta("onboarded", "1")
                startActivity(Intent(this, ConnectionActivity::class.java))
            }.show()
    }
}
