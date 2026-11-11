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
    private lateinit var actionBtn: ImageView
    private lateinit var actionBg: HexButtonBg
    private lateinit var speakerBtn: ImageView
    private lateinit var attachBtn: ImageView
    private lateinit var connDot: View
    private lateinit var connText: TextView
    private lateinit var emptyState: View
    private lateinit var greeting: TextView
    private lateinit var entity: EntityView
    private lateinit var entityBox: FrameLayout
    private lateinit var topBar: View
    private lateinit var composer: View
    private lateinit var drawer: LinearLayout
    private lateinit var scrim: View
    private lateinit var attachStrip: LinearLayout
    private val pending = mutableListOf<ai.clu.core.conversation.Attachment>()
    private var msgItems: List<Item> = emptyList()
    private var last = EngineUi()
    private var lastTyped: String? = null
    private var redirected = false
    private var compact: Boolean? = null
    /** Rows already shown: only rows that appear after the first render animate in. */
    private val seen = HashSet<String>()
    private var firstRender = true
    private var wasBusy = false
    override val ownEntrance = true

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
        // A tool needs a runtime permission (e.g. contacts for "call Mom"): Android's own prompt, asked from the visible screen.
        scope.launch { app.permissionWanted.collect { p -> if (p != null) { app.permissionWanted.value = null; askFor(p) } } }
        scope.launch { core.chat.state.collect { st -> if (!app.engine.server && (st.status != null || st.events.lastOrNull() is ai.clu.core.agent.AgentEvent.Error)) showItems() } }
        if (savedInstanceState == null) startUp()
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (::drawer.isInitialized && drawer.visibility == View.VISIBLE) closeDrawer() else { Sfx.play(SfxSynth.Sound.SHUTDOWN); @Suppress("DEPRECATION") super.onBackPressed() } }

    // ------------------------------------------------------------------------------------------------ start-up
    /**
     * Once per launch: the boot sequence (CLU's real state, line by line, then its name), and the interface opening out
     * of it. Reduced motion or the system's animations off: everything is simply there. The controls work from the first
     * frame; a tap anywhere ends the sequence.
     */
    private fun startUp() {
        val content = findViewById<ViewGroup>(android.R.id.content)
        if (booted || !Motion.enabled()) { booted = true; entrance(); return }
        booted = true
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        val lines = listOf(
            "CLU $version // ${android.os.Build.MODEL}",
            "core ............. online",
            "memory ........... ${runCatching { core.memories.count() }.getOrDefault(0)} records",
            "messages ......... ${runCatching { core.conversations.countMessages() }.getOrDefault(0)}",
            "link ............. " + if (app.engine.server) "CLU on your PC" else "this device",
            "interface sounds . " + if (core.settings.bool(Settings.UI_SOUNDS)) "on" else "off",
            "ready")
        val overlay = BootOverlay(this, lines) { entrance() }
        content.addView(overlay, ViewGroup.LayoutParams(-1, -1))
        bootOverlay = overlay
        topBar.alpha = 0f; composer.alpha = 0f; entityBox.alpha = 0f; emptyState.alpha = 0f
    }
    private var bootOverlay: BootOverlay? = null

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) bootOverlay?.let { if (it.visibility == View.VISIBLE) it.skip() }
        return super.dispatchTouchEvent(ev)
    }

    /** The interface opens: the bars out of a line, the entity and the greeting rising in. */
    private fun entrance() {
        // Never removed during a draw pass: removal is posted (BootOverlay.finish already runs this from a posted message).
        bootOverlay?.let { o -> o.visibility = View.GONE; o.post { (o.parent as? ViewGroup)?.removeView(o) } }; bootOverlay = null
        Motion.expand(topBar, 0, 380)
        Motion.rise(entityBox, 140, 14f, 520)
        Motion.rise(emptyState, 260)
        Motion.expand(composer, 200, 420)
        if (!Motion.enabled()) listOf(topBar, composer, entityBox, emptyState).forEach { it.alpha = 1f }
    }

    // ------------------------------------------------------------------------------------------------ layout
    private fun build(): View {
        val outer = FrameLayout(this).apply { background = backdrop() }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Top: navigation, CLU's name with the real connection state, voice.
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(6)) }
        top.addView(iconButton(Icon.Kind.MENU, "Conversations and settings") { openDrawer() }, LinearLayout.LayoutParams(dp(46), dp(46)))
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(TextView(context).apply { text = core.settings.string(Settings.CLU_NAME).ifBlank { "Clu" }.uppercase(); textSize = 17f; setTextColor(C.TEXT)
                typeface = Type.heading; letterSpacing = 0.45f; gravity = Gravity.CENTER })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                connDot = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.MUTED) } }
                addView(connDot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(6) })
                connText = TextView(context).apply { label(C.MUTED, 9.5f) }
                addView(connText)
            })
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { Sfx.play(SfxSynth.Sound.CLICK); startActivity(Intent(this@MainActivity, ConnectionActivity::class.java)) }
            contentDescription = "Connection status"
        }
        top.addView(FrameLayout(this).apply { addView(title, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(iconButton(Icon.Kind.HEX, "Voice chat") { Sfx.play(SfxSynth.Sound.ACTIVATE); startActivity(Intent(this, VoiceChatActivity::class.java)) },
            LinearLayout.LayoutParams(dp(46), dp(46)))
        topBar = top
        root.addView(top)

        // CLU's presence: large while the conversation is empty, a compact band above the conversation otherwise.
        entityBox = FrameLayout(this)
        entity = EntityView(this)
        entityBox.addView(entity, FrameLayout.LayoutParams(-1, -1))
        root.addView(entityBox, LinearLayout.LayoutParams(-1, dp(300)))

        // Conversation (or the empty state).
        val stage = FrameLayout(this)
        adapter = Adapter()
        list = ListView(this).apply {
            divider = null; dividerHeight = 0; transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL; isStackFromBottom = true
            setPadding(dp(14), dp(4), dp(14), dp(8)); clipToPadding = false; selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(24))
        }
        list.adapter = adapter
        stage.addView(list, FrameLayout.LayoutParams(-1, -1))
        emptyState = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(4), dp(24), 0)
            greeting = TextView(context).apply { label(C.ACCENT, 11f); gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(6)) }
            addView(greeting)
            addView(TextView(context).apply { text = "How can I help?"; textSize = 28f; setTextColor(C.TEXT); typeface = Type.light; gravity = Gravity.CENTER; letterSpacing = 0.02f })
            addView(TextView(context).apply { text = "Type below, or tap the microphone and speak."; textSize = 13f; setTextColor(C.MUTED); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        }
        stage.addView(emptyState, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        // Images waiting to be sent, with a remove control each.
        attachStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(18), dp(4), dp(18), 0); visibility = View.GONE }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(attachStrip) })

        // Composer: attach, speaker toggle, message field, then mic / send / stop.
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(12)) }
        val field = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = panel(Color.argb(225, 10, 16, 22), C.BORDER_HI, 12f, ticks = true); setPadding(dp(4), 0, dp(12), 0)
            attachBtn = ImageView(context).apply { setImageDrawable(icon(Icon.Kind.IMAGE, C.ACCENT2, 20f)); scaleType = ImageView.ScaleType.CENTER; contentDescription = "Attach an image"
                Motion.press(this); setOnClickListener { pickImage() } }
            addView(attachBtn, LinearLayout.LayoutParams(dp(40), dp(48)))
            speakerBtn = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER; contentDescription = "Speak replies aloud"
                Motion.press(this); setOnClickListener { Sfx.play(SfxSynth.Sound.CLICK); app.engine.setSpokenReplies(!last.spokenReplies) } }
            addView(speakerBtn, LinearLayout.LayoutParams(dp(36), dp(48)))
            input = EditText(context).apply {
                hint = "Message Clu"; setHintTextColor(C.MUTED); setTextColor(C.TEXT); textSize = 16f; background = null; maxLines = 5; typeface = Type.body
                textCursorDrawable?.setTint(C.ACCENT); highlightColor = Color.argb(90, 92, 225, 255)
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
        bar.addView(field, LinearLayout.LayoutParams(0, dp(54), 1f))
        actionBtn = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER; Motion.press(this); setOnClickListener { onAction() } }
        bar.addView(actionBtn, LinearLayout.LayoutParams(dp(58), dp(58)).apply { marginStart = dp(8) })
        composer = bar
        root.addView(bar)
        outer.addView(root, FrameLayout.LayoutParams(-1, -1))

        // Navigation drawer (hidden until ☰).
        scrim = View(this).apply { setBackgroundColor(Color.argb(170, 0, 0, 0)); visibility = View.GONE; contentDescription = "Close navigation"
            setOnClickListener { closeDrawer() } }
        outer.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        drawer = buildDrawer()
        outer.addView(drawer, FrameLayout.LayoutParams(dp(290), -1, Gravity.START))
        applySystemInsets(outer)
        updateAction()
        return outer
    }

    private fun backdrop() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.BLACK, Color.parseColor("#03070B"), Color.parseColor("#06101A")))

    private fun iconButton(kind: Icon.Kind, label: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageDrawable(icon(kind, C.ACCENT2, 22f)); scaleType = ImageView.ScaleType.CENTER; background = panel(C.SURFACE2, C.BORDER_HI, 8f); contentDescription = label
        Motion.press(this); setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------------------------------------------ navigation
    private val destinations = listOf("New chat", "Conversations", "Voice chat", "Memory", "Diary", "Connection", "Settings", "CLU window")

    private fun buildDrawer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; visibility = View.GONE; contentDescription = "Navigation"
        background = panel(Color.argb(246, 5, 9, 13), C.BORDER_HI, 18f, ticks = true)
        setPadding(dp(18), dp(18), dp(18), dp(18)); isClickable = true
        addView(TextView(context).apply { text = "CLU // NAVIGATION"; label(C.ACCENT, 10f); setPadding(0, 0, 0, dp(14)) })
        destinations.forEachIndexed { i, name ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(52)
                background = panel(Color.argb(120, 14, 22, 30), C.BORDER, 8f); setPadding(dp(14), 0, dp(12), 0)
                addView(TextView(context).apply { text = "%02d".format(i + 1); label(C.ACCENT, 11f) }, LinearLayout.LayoutParams(dp(34), -2))
                addView(TextView(context).apply { text = name; textSize = 16f; setTextColor(C.TEXT); typeface = Type.heading; letterSpacing = 0.05f })
                contentDescription = name; isClickable = true; Motion.press(this)
                setOnClickListener { Sfx.play(SfxSynth.Sound.CLICK); closeDrawer(); go(i) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    private fun openDrawer() {
        Sfx.play(SfxSynth.Sound.ACTIVATE)
        scrim.visibility = View.VISIBLE; drawer.visibility = View.VISIBLE
        if (Motion.enabled()) {
            scrim.alpha = 0f; scrim.animate().alpha(1f).setDuration(220).start()
            drawer.translationX = -dp(290).toFloat(); drawer.animate().translationX(0f).setDuration(320).setInterpolator(Motion.out).start()
            Motion.stagger(drawer, 120, 40)
        }
    }

    private fun closeDrawer() {
        val done = { scrim.visibility = View.GONE; drawer.visibility = View.GONE }
        if (!Motion.enabled()) { done(); return }
        scrim.animate().alpha(0f).setDuration(180).start()
        drawer.animate().translationX(-dp(290).toFloat()).setDuration(220).setInterpolator(Motion.inOut).withEndAction(done).start()
    }

    private fun go(i: Int) {
        when (i) {
            0 -> app.openConversation(null)
            1 -> startActivity(Intent(this, ConversationsActivity::class.java))
            2 -> startActivity(Intent(this, VoiceChatActivity::class.java))
            3 -> startActivity(Intent(this, MemoryActivity::class.java))
            4 -> startActivity(Intent(this, DiaryActivity::class.java))
            5 -> startActivity(Intent(this, ConnectionActivity::class.java))
            6 -> startActivity(Intent(this, SettingsActivity::class.java))
            7 -> { startActivity(Intent(this, ShellActivity::class.java)); finish() } // back to the PC-style window
        }
    }

    // ------------------------------------------------------------------------------------------------ attachments
    private fun pickImage() {
        Sfx.play(SfxSynth.Sound.CLICK)
        if (app.engine.server) { toast("Images stay on this phone: attach them when this device answers (Connection → Always this device)."); return }
        runCatching { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_IMAGE) }
            .onFailure { toast("No app to choose images from") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK) return
        scope.launch {
            val a = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { ChatImages.importImage(this@MainActivity, uri) }.getOrNull() }
            if (a == null) toast("That image couldn't be opened") else { pending += a; showPending() }
        }
    }

    private fun showPending() {
        attachStrip.removeAllViews()
        attachStrip.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE
        for (a in pending.toList()) attachStrip.addView(FrameLayout(this).apply {
            background = panel(C.SURFACE2, C.ACCENT, 6f)
            addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(ChatImages.thumb(a.uri, dp(64))); contentDescription = "Image to send: ${a.name}" },
                FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER))
            addView(ImageView(context).apply { setImageDrawable(icon(Icon.Kind.CLOSE, C.TEXT, 14f)); scaleType = ImageView.ScaleType.CENTER; contentDescription = "Remove image"
                background = panel(Color.argb(220, 0, 0, 0), C.BORDER_HI, 3f); setOnClickListener { pending.remove(a); showPending() } }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END))
        }, LinearLayout.LayoutParams(dp(66), dp(66)).apply { marginEnd = dp(8) })
        if (pending.isNotEmpty()) attachStrip.addView(TextView(this).apply {
            text = "CLU keeps the image with your message but can't see images yet (no vision model in this build)."; textSize = 11f; setTextColor(C.MUTED)
            maxWidth = dp(200); setPadding(dp(4), 0, 0, 0)
        })
        updateAction()
    }

    // ------------------------------------------------------------------------------------------------ state
    private fun render(s: EngineUi) {
        val prev = last
        last = s
        val (dot, label) = when {
            !s.server -> C.TEAL to "on-device"
            s.link == Link.CONNECTED && s.phase == AgentPhase.ABSENT -> C.WARN to "waiting for agent"
            s.link == Link.CONNECTED -> C.ACCENT to "linked to pc"
            s.link == Link.CONNECTING || s.link == Link.RECONNECTING -> C.WARN to "connecting"
            s.link == Link.NOT_CONFIGURED -> C.ERROR to "not set up"
            else -> C.ERROR to "offline"
        }
        (connDot.background as GradientDrawable).setColor(dot); connText.text = label; connText.setTextColor(if (dot == C.ERROR) C.ERROR else C.MUTED)
        speakerBtn.setImageDrawable(icon(if (s.spokenReplies) Icon.Kind.SPEAKER else Icon.Kind.SPEAKER_OFF, if (s.spokenReplies) C.ACCENT2 else C.MUTED, 20f))
        speakerBtn.contentDescription = if (s.spokenReplies) "Speak replies aloud" else "Speak replies aloud (off)"
        attachBtn.alpha = if (s.server) 0.4f else 1f
        input.hint = if (s.micOn) (s.userLive.ifBlank { "Listening…" }) else "Message Clu"
        val connected = !s.server || s.link == Link.CONNECTED
        entity.mode = when {
            !connected -> EntityView.Mode.OFFLINE
            s.phase == AgentPhase.SPEAKING -> EntityView.Mode.SPEAKING
            s.thinking || s.phase == AgentPhase.THINKING || s.agentLive.isNotBlank() -> EntityView.Mode.THINKING
            s.micOn -> EntityView.Mode.LISTENING
            else -> EntityView.Mode.IDLE
        }
        entity.level = if (entity.mode == EntityView.Mode.SPEAKING) s.agentLevel else s.micLevel
        entity.contentDescription = "Clu: " + entity.mode.name.lowercase()
        // A reply finished while nothing is speaking it: a soft notification tone (sounds stay silent while speaking/listening).
        if (wasBusy && !s.busy && s.error == null && !s.spokenReplies) Sfx.play(SfxSynth.Sound.NOTIFY)
        wasBusy = s.busy
        updateAction()
        showItems()
    }

    private fun updateAction() {
        val typing = input.text.isNotBlank() || pending.isNotEmpty()
        val (kind, desc) = when {
            last.busy -> Icon.Kind.STOP to "Stop"
            typing -> Icon.Kind.SEND to "Send"
            last.micOn -> Icon.Kind.MIC_ON to "Stop listening"
            else -> Icon.Kind.MIC to "Speak"
        }
        val active = last.busy || typing || last.micOn
        actionBtn.setImageDrawable(icon(kind, if (active) C.ACCENT2 else C.TEXT, 22f)); actionBtn.contentDescription = desc
        if (activeBg != active) { actionBg = HexButtonBg(resources.displayMetrics.density, active); activeBg = active; actionBtn.background = actionBg }
        actionBg.level = if (last.micOn) last.micLevel else 0f
    }
    private var activeBg: Boolean? = null

    private fun onAction() {
        when {
            last.busy -> { Sfx.play(SfxSynth.Sound.CLICK); app.engine.stop() }
            input.text.isNotBlank() || pending.isNotEmpty() -> send()
            else -> toggleMic()
        }
    }

    /** A permission or role a tool needs, through Android's own dialog (calendar asks for reading and writing together). */
    private fun askFor(p: String) {
        if (p == ai.clu.tools.CallsAnnounceTool.ROLE_REQUEST) {
            val rm = getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_SCREENING) && !rm.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING))
                runCatching { startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING), 3) }
            askFor(Manifest.permission.READ_CONTACTS); return
        }
        val group = if (p == Manifest.permission.READ_CALENDAR || p == Manifest.permission.WRITE_CALENDAR) arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR) else arrayOf(p)
        val missing = group.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 2)
    }

    private fun toggleMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this).setTitle("Speak to Clu")
                .setMessage("Clu needs the microphone to hear you. Audio goes only to CLU on your PC (or stays on this device when it answers offline).")
                .setPositiveButton("Allow microphone") { _, _ -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
                .setNegativeButton("Not now", null).show().styled()
            return
        }
        // Voice in, answer in the chat (spoken too only if the speaker is on). The listening tone plays just before the
        // microphone opens, so it is never part of what is recognised.
        if (!last.micOn && Sfx.play(SfxSynth.Sound.LISTEN)) actionBtn.postDelayed({ app.engine.setMic(true) }, 150)
        else app.engine.setMic(!last.micOn)
    }

    private fun send() {
        var text = input.text.toString().trim()
        val images = pending.toList()
        if ((text.isEmpty() && images.isEmpty()) || last.busy) return
        // An image on its own is a question about it (CLU then says honestly that it can't see images yet).
        if (text.isEmpty()) text = "What's in this photo?"
        lastTyped = text
        Sfx.play(SfxSynth.Sound.PROCESS)
        if (app.engine.send(text, images)) { input.setText(""); pending.clear(); showPending() }
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
        val empty = adapter.items.isEmpty()
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        setCompact(!empty)
        if (firstRender) { adapter.items.forEach { seen += key(it) }; firstRender = false }
        if (wasAtEnd) scrollToEnd()
    }

    /** The entity shrinks into a band above the conversation once there is one (and grows back for a new chat). */
    private fun setCompact(c: Boolean) {
        if (compact == c) return
        val first = compact == null
        compact = c
        val target = dp(if (c) 104 else 300)
        val lp = entityBox.layoutParams
        entity.compact = c
        if (first || !Motion.enabled()) { lp.height = target; entityBox.layoutParams = lp; return }
        android.animation.ValueAnimator.ofInt(lp.height, target).apply {
            duration = 420; interpolator = Motion.inOut
            addUpdateListener { lp.height = it.animatedValue as Int; entityBox.layoutParams = lp }
        }.start()
    }

    private fun key(it: Item) = when (it) {
        is Item.Msg -> "m:" + it.m.id
        is Item.Live -> "live"
        is Item.Problem -> "p:" + it.text
        is Item.Suggestion -> "s:" + it.p.id
    }

    private fun scrollToEnd() = list.post { if (adapter.count > 0) { if (Motion.enabled()) list.smoothScrollToPosition(adapter.count - 1) else list.setSelection(adapter.count - 1) } }

    private inner class Adapter : BaseAdapter() {
        var items: List<Item> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            val v = when (item) {
                is Item.Msg -> if (item.m.role == Role.USER) userRow(item.m) else cluRow(item.m.content, badge(item.m), muted = false, msg = item.m)
                is Item.Live -> if (item.thinking) thinkingRow(item.text) else cluRow(item.text, null, muted = false, streaming = true)
                is Item.Problem -> problemRow(item.text, item.retry)
                is Item.Suggestion -> suggestionRow(item.p)
            }
            // New rows arrive with the reference's motion: a short rise and fade (history and the streaming row don't).
            val k = key(item)
            if (k != "live" && seen.add(k)) Motion.rise(v, 0, if (item is Item.Msg && item.m.role == Role.USER) 6f else 10f, 300)
            return v
        }
    }

    private fun time(ms: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

    private fun userRow(m: Message): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.END; setPadding(dp(56), dp(8), 0, dp(6))
        addView(TextView(context).apply { text = "YOU · ${time(m.createdAt)}"; label(C.MUTED, 9.5f); setPadding(0, 0, dp(4), dp(4)) })
        m.meta.attachments.filter { it.kind == "image" }.forEach { a ->
            addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(ChatImages.thumb(a.uri, dp(200))); contentDescription = "Image: ${a.name}"
                background = panel(C.SURFACE2, C.BORDER_HI, 8f); setPadding(dp(3), dp(3), dp(3), dp(3)) }, LinearLayout.LayoutParams(dp(180), dp(180)).apply { bottomMargin = dp(6) })
        }
        addView(TextView(context).apply {
            this.text = m.content; textSize = 15.5f; setTextColor(C.TEXT); setLineSpacing(0f, 1.18f)
            background = panel(Color.argb(215, 16, 30, 40), Color.argb(150, 168, 232, 255), 10f); setPadding(dp(14), dp(10), dp(16), dp(11))
            setOnLongClickListener { messageMenu(m); true }
        })
    }

    /** Under a reply: where it came from. A searched answer says so (with its sources below); others show their engine. */
    private fun badge(m: Message): String? =
        listOfNotNull(ai.clu.core.config.UiPolicy.sourceBadge(core.settings, m.meta), "interrupted".takeIf { m.meta.interrupted }).joinToString(" · ").ifEmpty { null }

    /** CLU's reply: a cyan rail, CLU's label and time, the reply rendered from Markdown, then its source line. */
    private fun cluRow(text: String, sub: String?, muted: Boolean = false, msg: Message? = null, streaming: Boolean = false): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), dp(20), dp(8))
        addView(View(context).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(C.ACCENT, Color.argb(0, 92, 225, 255))) },
            LinearLayout.LayoutParams(dp(2), -1).apply { marginEnd = dp(12) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { this.text = core.settings.string(Settings.CLU_NAME).ifBlank { "Clu" }.uppercase() + (msg?.let { " · " + time(it.createdAt) } ?: if (streaming) " · RESPONDING" else ""); label(C.ACCENT, 9.5f); setPadding(0, 0, 0, dp(5)) })
            addView(markdownView(text, if (muted) C.MUTED else C.TEXT, cursor = streaming, onLong = { if (msg != null) messageMenu(msg) else copy(text) }))
            sub?.let { addView(TextView(context).apply { this.text = it; label(C.MUTED, 9.5f); setPadding(0, dp(4), 0, 0) }) }
            // Answer first: the sources are one collapsed line ("Sources (3)" / "Source: GDACS …"); tapping it lists them
            // exactly as the provider returned them, and tapping one opens it in the browser.
            val sources = msg?.meta?.sources.orEmpty()
            ai.clu.core.config.UiPolicy.sourcesSummary(sources)?.let { summary ->
                val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE
                    sources.forEachIndexed { i, src -> addView(sourceRow(i + 1, src)) } }
                addView(TextView(context).apply {
                    this.text = "$summary ▸"; textSize = 12f; setTextColor(C.ACCENT2); typeface = Type.mono; setPadding(0, dp(6), 0, dp(6)); minimumHeight = dp(32)
                    contentDescription = "$summary. Double-tap to show."
                    setOnClickListener {
                        val open = list.visibility != View.VISIBLE
                        Sfx.play(SfxSynth.Sound.CLICK)
                        list.visibility = if (open) View.VISIBLE else View.GONE
                        if (open) Motion.stagger(list, 0, 40)
                        this.text = "$summary ${if (open) "▾" else "▸"}"
                        contentDescription = "$summary. Double-tap to ${if (open) "hide" else "show"}."
                    }
                })
                addView(list)
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    /** Thinking: the scan bar and what CLU is doing (its real status when the device answers). */
    private fun thinkingRow(status: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(14), dp(20), dp(12))
        addView(ProcessingBar(context))
        addView(TextView(context).apply { this.text = status.ifBlank { "Thinking…" }; textSize = 12.5f; setTextColor(C.MUTED); typeface = Type.mono; setPadding(dp(12), 0, 0, 0) })
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
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(16), dp(8))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; background = panel(Color.argb(170, 40, 26, 6), Color.argb(180, 227, 167, 47), 8f); setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(TextView(context).apply { this.text = "NOTICE"; label(C.WARN, 9.5f) })
            addView(TextView(context).apply { this.text = text; textSize = 14f; setTextColor(C.TEXT); setPadding(0, dp(3), 0, 0) })
            if (retry != null) addView(TextView(context).apply {
                this.text = "Retry"; textSize = 14f; setTextColor(C.ACCENT); typeface = Type.headingBold; setPadding(0, dp(8), dp(8), dp(4))
                setOnClickListener { Sfx.play(SfxSynth.Sound.CLICK); app.liveKit.live.onError(null); input.setText(retry); send() }
            })
        })
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Clu", text)); toast("Copied")
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

    companion object {
        private const val REQ_IMAGE = 41
        /** The start-up sequence runs once per launch of the app, not on every return to the chat. */
        @Volatile var booted = false
    }
}
