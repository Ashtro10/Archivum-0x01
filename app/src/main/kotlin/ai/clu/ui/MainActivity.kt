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
import android.graphics.Color
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
            combine(core.llm.state, app.voice.state, app.sync.state, app.liveKit.state) { a, b, c, d -> listOf(a, b, c, d) }.collect { x ->
                updateStatus(x[0] as ai.clu.core.llm.LlmState, x[1] as ai.clu.voice.VoiceUiState, x[2] as ai.clu.sync.SyncUiState, x[3] as ai.clu.voice.LiveKitUiState)
            }
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
        app.speech.refresh(ai.clu.core.voice.VoiceConfig.from(core.settings)) // voice packs may have been installed meanwhile
        bannerKey = null; updateBanner()
        scope.launch {
            delay(800)
            runCatching { ProactiveScheduler.evaluate(app, appOpenedNow = true) }.onFailure { core.diag.e("proactive", "evaluation failed", it) }
        }
    }

    private lateinit var banner: LinearLayout
    private lateinit var emptyState: View
    private lateinit var heroOrb: OrbView
    private lateinit var voiceOverlay: View
    private lateinit var voiceOrb: OrbView
    private lateinit var voiceLabel: TextView
    private lateinit var voiceTranscript: TextView
    private lateinit var voiceTimer: TextView
    private lateinit var voiceMic: TextView
    private lateinit var speakerBtn: TextView
    private var voiceStartedAt = 0L
    private val chipRows = mutableListOf<View>()

    private fun buildUi() {
        val frame = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))

        // Header: new chat (glass) · Clu pill (avatar, name, status) · spoken replies on/off (glass).
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        bar.addView(glassButton("✎", "New chat") { app.openConversation(null) }, LinearLayout.LayoutParams(dp(46), dp(46)))
        val pill = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = glassBg(); setPadding(dp(6), dp(5), dp(16), dp(5)) }
        pill.addView(OrbView(this, 220).apply { mode = OrbView.Mode.IDLE }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(this).apply { textSize = 15f; setTextColor(C.TEXT); typeface = Typeface.DEFAULT_BOLD; text = core.personas.active().name }
        status = TextView(this).apply { textSize = 11f; setTextColor(C.MUTED); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        titles.addView(title); titles.addView(status)
        pill.addView(titles)
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(8), 0, dp(8), 0) })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        speakerBtn = glassButton("🔊", "Speak replies") {
            core.settings.set(Settings.SPEAK_RESPONSES, !core.settings.bool(Settings.SPEAK_RESPONSES)); updateSpeaker()
            toast(if (core.settings.bool(Settings.SPEAK_RESPONSES)) "Clu will speak replies to typed messages." else "Typed messages get text replies only (voice conversations are always spoken).")
        }
        bar.addView(speakerBtn, LinearLayout.LayoutParams(dp(46), dp(46)))
        root.addView(bar)

        // Setup / recovery notices (e.g. the selected voice is not installed) with a direct action.
        banner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(14), dp(4), dp(14), dp(4)) }
        root.addView(banner)

        val middle = FrameLayout(this)
        adapter = RowAdapter(emptyList()) { item, ctx -> rowFor(item) }
        list = ListView(this).apply { this.adapter = this@MainActivity.adapter; divider = null; transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL; isStackFromBottom = true
            setPadding(dp(10), dp(4), dp(10), dp(4)); clipToPadding = false; selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT) }
        middle.addView(list, FrameLayout.LayoutParams(-1, -1))
        emptyState = buildEmptyState()
        middle.addView(emptyState, FrameLayout.LayoutParams(-1, -1))
        root.addView(middle, LinearLayout.LayoutParams(-1, 0, 1f))

        attachRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), 0) }
        root.addView(attachRow)

        // Composer: menu (navigation, within thumb reach) · message pill with attach · gradient microphone / send.
        val inputBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(10)) }
        inputBar.addView(glassButton("☰", "Menu") { showNavSheet() }, LinearLayout.LayoutParams(dp(50), dp(50)).apply { marginEnd = dp(8) })
        val field = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = roundBg(C.SURFACE2, 200f, C.BORDER)
            setPadding(dp(4), 0, dp(6), 0); minimumHeight = dp(54) }
        field.addView(TextView(this).apply { text = "+"; textSize = 24f; setTextColor(C.TEXT); gravity = Gravity.CENTER; contentDescription = "Attach"
            setOnClickListener { pickAttachment() } }, LinearLayout.LayoutParams(dp(44), dp(44)))
        input = EditText(this).apply { hint = "Message…"; setTextColor(C.TEXT); setHintTextColor(C.MUTED); textSize = 16f; background = null
            setPadding(dp(2), dp(12), dp(8), dp(12)); maxLines = 5 }
        field.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        sendBtn = Button(this).apply { text = "➤"; textSize = 16f; background = roundBg(C.SURFACE, 200f, C.BORDER); setTextColor(C.TEXT); contentDescription = "Send"
            setOnClickListener { onSendOrStop() } }
        field.addView(sendBtn, LinearLayout.LayoutParams(dp(42), dp(42)))
        inputBar.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        micBtn = Button(this).apply { text = ""; background = android.graphics.drawable.LayerDrawable(arrayOf(accentBg(), MicDrawable())); contentDescription = "Talk to Clu"; elevation = dp(6).toFloat() }
        micBtn.setOnTouchListener { _, e -> onMicTouch(e) }
        inputBar.addView(micBtn, LinearLayout.LayoutParams(dp(58), dp(58)).apply { marginStart = dp(8) })
        root.addView(inputBar)

        voiceOverlay = buildVoiceOverlay()
        frame.addView(voiceOverlay, FrameLayout.LayoutParams(-1, -1))
        applySystemInsets(frame) { ime ->
            // While typing, the empty-state orb and chips make room for the keyboard.
            heroOrb.visibility = if (ime) View.GONE else View.VISIBLE
            chipRows.forEach { it.visibility = if (ime) View.GONE else View.VISIBLE }
        }
        setContentView(frame)
        updateSpeaker()
    }

    private fun glassButton(icon: String, label: String, onClick: () -> Unit) = TextView(this).apply {
        text = icon; textSize = 18f; setTextColor(C.TEXT); gravity = Gravity.CENTER; contentDescription = label; background = glassBg()
        setOnClickListener { onClick() }
    }

    private fun updateSpeaker() { speakerBtn.alpha = if (core.settings.bool(Settings.SPEAK_RESPONSES)) 1f else 0.45f }

    /** Empty conversation: the orb, a greeting and suggestion chips (tap to send). */
    private fun buildEmptyState(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(8), 0, dp(8)) }
        heroOrb = OrbView(this).apply { mode = OrbView.Mode.IDLE }
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 0.6f))
        col.addView(heroOrb, LinearLayout.LayoutParams(dp(230), dp(230)))
        col.addView(TextView(this).apply { text = "Hi, I'm ${core.settings.string(Settings.CLU_NAME)}!"; textSize = 15f; setTextColor(C.MUTED); gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(4)) })
        col.addView(TextView(this).apply { text = "How can I help\nyou today?"; textSize = 30f; setTextColor(C.TEXT); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setLineSpacing(0f, 1.05f) })
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val chips = listOf("⏰  Set a reminder" to "Remind me in 30 minutes to stretch", "📅  My day" to "What's on my schedule today?",
            "🧠  Remember this" to "Remember that ", "🌐  Translate" to "Translate into Hindi: ", "🎙  Talk to me" to "", "📝  Summarise" to "Summarise this: ")
        listOf(chips.take(3), chips.drop(3)).forEach { rowChips ->
            val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; setPadding(dp(12), 0, dp(12), 0) }
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowChips.forEach { (label, prompt) ->
                r.addView(TextView(this).apply {
                    text = label; textSize = 14f; setTextColor(C.TEXT); background = roundBg(C.SURFACE, 200f, C.BORDER); setPadding(dp(16), dp(10), dp(16), dp(10))
                    setOnClickListener {
                        when {
                            prompt.isEmpty() -> onMicTouch(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0f, 0f, 0))
                            prompt.endsWith(" ") -> { input.setText(prompt); input.setSelection(prompt.length); input.requestFocus() }
                            else -> send(prompt)
                        }
                    }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            }
            hs.addView(r)
            chipRows += hs
            col.addView(hs, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        return col
    }

    /** Full-screen voice mode: orb that follows your voice, live transcript, timer, interrupt/stop controls. */
    private fun buildVoiceOverlay(): View {
        val fl = FrameLayout(this).apply { visibility = View.GONE; isClickable = true
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.BLACK, Color.BLACK, Color.BLACK, Color.parseColor("#1E0830"), Color.parseColor("#5E1250"), Color.parseColor("#B8203E"))) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(16), 0) }
        top.addView(glassButton("←", "Back to chat") { app.voice.stop(); app.speech.stop() }, LinearLayout.LayoutParams(dp(46), dp(46)))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(glassButton("⋮", "Speech diagnostics") { startActivity(Intent(this, SpeechDiagnosticsActivity::class.java)) }, LinearLayout.LayoutParams(dp(46), dp(46)))
        col.addView(top)
        voiceOrb = OrbView(this, 1800)
        col.addView(voiceOrb, LinearLayout.LayoutParams(dp(300), dp(300)).apply { topMargin = dp(24) })
        voiceTimer = TextView(this).apply { textSize = 22f; setTextColor(C.TEXT); gravity = Gravity.CENTER; typeface = Typeface.MONOSPACE }
        col.addView(voiceTimer)
        voiceLabel = TextView(this).apply { textSize = 15f; setTextColor(C.MUTED); gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(6)) }
        col.addView(voiceLabel)
        voiceTranscript = TextView(this).apply { textSize = 22f; setTextColor(C.TEXT); gravity = Gravity.CENTER; setPadding(dp(28), 0, dp(28), 0); maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.START }
        col.addView(voiceTranscript)
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(28), 0, dp(28), dp(26)) }
        controls.addView(glassButton("⌨", "Type instead") { app.voice.stop(); app.speech.stop(); input.requestFocus() }, LinearLayout.LayoutParams(dp(52), dp(52)))
        controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        voiceMic = TextView(this).apply { textSize = 30f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = android.graphics.drawable.LayerDrawable(arrayOf(accentBg(), MicDrawable())); elevation = dp(10).toFloat()
            contentDescription = "Talk / interrupt"
            setOnClickListener {
                val v = app.voice.state.value
                when (v.turn) {
                    TurnState.SPEAKING, TurnState.PROCESSING -> app.voice.activate()   // interrupt Clu and listen
                    TurnState.LISTENING -> app.voice.endUtteranceSoon()             // "I'm done": answer now
                    else -> app.voice.activate()
                }
            } }
        val micWrap = FrameLayout(this).apply { background = roundBg(Color.argb(40, 255, 255, 255), 400f); setPadding(dp(14), dp(14), dp(14), dp(14)) }
        micWrap.addView(voiceMic, FrameLayout.LayoutParams(dp(84), dp(84)))
        controls.addView(micWrap)
        controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(glassButton("✕", "End conversation") { app.voice.stop(); app.speech.stop() }, LinearLayout.LayoutParams(dp(52), dp(52)))
        col.addView(controls, LinearLayout.LayoutParams(-1, -2))
        fl.addView(col, FrameLayout.LayoutParams(-1, -1))
        return fl
    }

    private fun updateStatus(l: ai.clu.core.llm.LlmState, v: ai.clu.voice.VoiceUiState, s: ai.clu.sync.SyncUiState, lk: ai.clu.voice.LiveKitUiState) {
        val model = when (l.status) {
            LlmStatus.NO_MODEL -> "no model"; LlmStatus.UNLOADED -> "AI assistant"; LlmStatus.LOADING -> "loading model…"
            LlmStatus.READY -> "AI assistant"; LlmStatus.GENERATING -> "thinking…"; LlmStatus.ERROR -> "model error"
        }
        val net = if (app.platform.isOnline()) "" else " · offline"
        val live = when (lk.status) {
            ai.clu.voice.LiveKitUiState.Status.CONNECTING -> " · LiveKit…"
            ai.clu.voice.LiveKitUiState.Status.CONNECTED -> " · LiveKit"
            else -> ""
        }
        status.text = "$model$net$live"
        status.setTextColor(if (l.status == LlmStatus.ERROR || l.status == LlmStatus.NO_MODEL) C.WARN else C.MUTED)
        heroOrb.mode = if (l.status == LlmStatus.GENERATING) OrbView.Mode.THINKING else OrbView.Mode.IDLE

        // Voice mode overlay: open whenever the microphone session runs (never hidden while the mic is on).
        val active = v.running && v.turn != TurnState.OFF
        if (active && voiceOverlay.visibility != View.VISIBLE) { voiceStartedAt = System.currentTimeMillis(); voiceOverlay.visibility = View.VISIBLE; tickTimer() }
        if (!active) voiceOverlay.visibility = View.GONE
        val (label, mode) = when {
            v.error.isNotBlank() -> "Error: ${v.error}" to OrbView.Mode.IDLE
            v.turn == TurnState.LISTENING -> "${core.settings.string(Settings.CLU_NAME)} is listening…" to OrbView.Mode.LISTENING
            v.turn == TurnState.PROCESSING -> "Thinking…" to OrbView.Mode.THINKING
            v.turn == TurnState.SPEAKING -> "Speaking — talk to interrupt" to OrbView.Mode.SPEAKING
            v.turn == TurnState.WAITING_FOR_WAKE -> "Say \"${core.settings.wakeWords().first { it.primary }.phrase}\"" to OrbView.Mode.IDLE
            else -> "" to OrbView.Mode.IDLE
        }
        voiceLabel.text = label
        voiceLabel.setTextColor(if (v.error.isNotBlank()) C.WARN else C.MUTED)
        voiceOrb.mode = mode
        voiceOrb.level = ((v.levelDb + 60) / 45).toFloat().coerceIn(0f, 1f)
        voiceTranscript.text = when (v.turn) {
            TurnState.LISTENING -> v.partial
            TurnState.PROCESSING, TurnState.SPEAKING -> v.lastHeard
            else -> ""
        }
        val interruptible = v.turn == TurnState.SPEAKING || v.turn == TurnState.PROCESSING
        voiceMic.text = if (interruptible) "✋" else ""
        voiceMic.background = if (interruptible) accentBg() else android.graphics.drawable.LayerDrawable(arrayOf(accentBg(), MicDrawable()))
        val listening = v.running && v.turn == TurnState.LISTENING
        micBtn.background = android.graphics.drawable.LayerDrawable(arrayOf(accentBg(), MicDrawable(stop = listening)))
        micBtn.contentDescription = if (listening) "Stop listening" else "Talk to Clu"
        sendBtn.text = if (streaming != null || v.turn == TurnState.SPEAKING) "■" else "➤"
        updateBanner()
    }

    private fun tickTimer() {
        if (voiceOverlay.visibility != View.VISIBLE) return
        val s = (System.currentTimeMillis() - voiceStartedAt) / 1000
        voiceTimer.text = "%02d:%02d".format(s / 60, s % 60)
        voiceTimer.postDelayed({ tickTimer() }, 500)
    }

    /** Navigation as a bottom sheet (reachable with the thumb): history, memory, voice, settings and the rest. */
    private fun showNavSheet() {
        val entries = mutableListOf(
            "💬  Chats" to ConversationsActivity::class.java, "🧠  Memory & diary" to MemoryActivity::class.java,
            "🔊  Voice & speech" to VoiceActivity::class.java, "⚙  Settings" to SettingsActivity::class.java,
            "🤖  AI models" to ModelsActivity::class.java, "💡  Memory suggestions" to ProposalsActivity::class.java,
            "🎭  Characters" to CharactersActivity::class.java, "🔐  People & security" to PeopleActivity::class.java,
            "⌘  Custom commands" to CommandsActivity::class.java, "📱  Phone & tablet" to DevicesActivity::class.java,
            "📈  Learned preferences" to PreferencesActivity::class.java, "🛠  Tool history" to ToolHistoryActivity::class.java,
            "🎤  Speech recognition diagnostics" to SpeechDiagnosticsActivity::class.java,
        )
        if (UiPolicy.diagnosticsVisible(core.settings)) entries += "🩺  Diagnostics" to DiagnosticsActivity::class.java
        val d = android.app.Dialog(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = roundBg(C.SURFACE, 56f, C.BORDER); setPadding(dp(10), dp(10), dp(10), dp(18)) }
        col.addView(View(this).apply { background = roundBg(C.BORDER, 20f) }, LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })
        val grid = GridLayout(this).apply { columnCount = 2 }
        entries.forEach { (label, cls) ->
            grid.addView(TextView(this).apply {
                text = label; textSize = 15f; setTextColor(C.TEXT); setPadding(dp(14), dp(14), dp(10), dp(14)); maxLines = 2
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(C.SURFACE2), null, roundBg(Color.WHITE, 28f))
                setOnClickListener { d.dismiss(); startActivity(Intent(this@MainActivity, cls)) }
            }, GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) })
        }
        col.addView(ScrollView(this).apply { addView(grid) })
        d.setContentView(col)
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM); setLayout(-1, -2)
            attributes = attributes.apply { y = dp(8) }
        }
        d.show()
    }

    /** Recovery actions for problems that would otherwise leave Clu silent (missing voice, missing model). */
    private var bannerKey: String? = null

    private fun updateBanner() {
        val cfg = ai.clu.core.voice.VoiceConfig.from(core.settings)
        val speakingWanted = UiPolicy.shouldSpeak(core.settings, viaVoice = true, nowMs = System.currentTimeMillis())
        val plan = app.speech.status.value.plan
        val key = "$speakingWanted|${plan.problem}|${cfg.piperVoice}"
        if (key == bannerKey) return
        bannerKey = key
        banner.removeAllViews()
        if (speakingWanted && plan.engine.isNotBlank() && !plan.available) {
            val missingVoice = plan.engine == ai.clu.core.voice.TtsSelection.PIPER && plan.english == null
            banner.addView(TextView(this).apply {
                text = if (missingVoice) "Clu's voice isn't downloaded yet, so replies are text only. Download it once (works offline afterwards) or pick another voice."
                    else "Clu can't speak: ${plan.problem}"
                textSize = 13f; setTextColor(C.WARN)
            })
            banner.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val missing = if (plan.engine == ai.clu.core.voice.TtsSelection.PIPER) ai.clu.voice.VoicePacks.byId(cfg.piperVoice) else null
                if (missing != null) smallButton("Download ${missing.title.substringBefore(" (")} (${fmtBytes(missing.bytes)})") {
                    startActivity(Intent(this@MainActivity, VoiceActivity::class.java).putExtra("download", missing.id))
                }
                smallButton("Choose voice") { startActivity(Intent(this@MainActivity, VoiceActivity::class.java)) }
            })
        }
        banner.visibility = if (banner.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun refresh() {
        val convId = app.currentConversation()
        val msgs = core.conversations.messages(convId).map { Item.Msg(it) as Item }
        adapter.update(msgs + extra + listOfNotNull(streaming?.let { Item.Streaming(it) }))
        emptyState.visibility = if (adapter.count == 0) View.VISIBLE else View.GONE
        title.text = core.personas.active().name
    }

    /** Scrolls to the newest message only if the user is already reading the end (or [force], e.g. after sending). */
    private fun scrollToEnd(force: Boolean = false) = list.post {
        if (force || list.lastVisiblePosition >= adapter.count - 3) list.setSelection(adapter.count - 1)
    }

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
        refresh(); scrollToEnd(force = true)
        val actor = app.ownerActor().let { if (verified) it.copy(verifiedAt = System.currentTimeMillis()) else it }
        turnJob = scope.launch {
            val speak = UiPolicy.shouldSpeak(core.settings, viaVoice = false, nowMs = System.currentTimeMillis())
            val cfg = ai.clu.core.voice.VoiceConfig.from(core.settings)
            // Spoken reply streams through the same pipeline as voice (speaks while generating; stop button cancels both).
            val reply = if (speak) app.speech.reply(cfg) { core.agent.cancel() } else null
            core.agent.respond(TurnRequest(app.currentConversation(), fullText, actor, attachments = atts.map { it.first })).collect { ev ->
                when (ev) {
                    is AgentEvent.Text -> { streaming = (streaming ?: "") + ev.delta; if (cfg.incremental) reply?.text(ev.delta); refreshStreaming() }
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
                        if (reply != null) { if (cfg.incremental) reply.finish() else reply.say(ev.text) }
                    }
                    is AgentEvent.UserMessageSaved -> {}
                }
            }
            reply?.finish()
            streaming = null
            refresh(); scrollToEnd()
        }
    }

    private fun refreshStreaming() { refresh(); scrollToEnd() }

    // ---------------------------------------------------------------- voice
    private fun onMicTouch(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_DOWN && e.action != MotionEvent.ACTION_UP) return true
        if (!app.hasMic()) {
            if (e.action == MotionEvent.ACTION_UP) AlertDialog.Builder(this).setTitle("Talk to ${core.settings.string(Settings.CLU_NAME)}")
                .setMessage("Clu needs the microphone to hear you. Speech is recognised on this device; recordings are not uploaded or saved " +
                    "(only the last utterance is kept in memory for Speech diagnostics).\n\nThe microphone is on only while the voice screen is open " +
                    "(or when you enable Background listening). Tap ✕ or Stop to close it.")
                .setPositiveButton("Allow microphone") { _, _ -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
                .setNegativeButton("Not now", null).show()
            return true
        }
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
            background = if (mine) roundBg(Color.parseColor("#2B2B31"), 44f) else roundBg(C.SURFACE, 44f, C.BORDER); setPadding(dp(16), dp(11), dp(16), dp(11))
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
