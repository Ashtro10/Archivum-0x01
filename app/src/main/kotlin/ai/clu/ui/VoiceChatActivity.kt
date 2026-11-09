package ai.clu.ui

import ai.clu.core.live.AgentPhase
import ai.clu.core.live.Link
import ai.clu.voice.EngineUi
import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Voice chat: CLU's entity full screen (particles and eyes). Everything on screen reflects the engine's real state
 * ([EngineUi]): it listens only while the microphone is live, "thinks" only while the agent is thinking, and moves with
 * the level of CLU's actual audio while speaking. Leaving the screen stops listening (the conversation history stays).
 */
class VoiceChatActivity : BaseActivity() {
    private lateinit var hud: EntityView
    private lateinit var phaseLabel: TextView
    private lateinit var timerLabel: TextView
    private lateinit var connDot: View
    private lateinit var connText: TextView
    private lateinit var youText: TextView
    private lateinit var cluText: TextView
    private lateinit var errorText: TextView
    private lateinit var mainBtn: android.widget.ImageView
    private var mainBg: HexButtonBg? = null
    private var mainActive: Boolean? = null
    private lateinit var speakerBtn: android.widget.ImageView
    private lateinit var top: View
    private lateinit var controls: View
    override val ownEntrance = true
    private var sessionStart = 0L
    private var last = EngineUi()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
        scope.launch { app.engine.ui.collect { render(it) } }
        scope.launch { while (true) { tick(); delay(500) } }
    }

    override fun onStart() {
        super.onStart()
        app.engine.screenVisible("voice", true)
        if (!app.hasMic()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        else { app.engine.startVoice(); sessionStart = System.currentTimeMillis() }
    }

    override fun onStop() {
        if (!isChangingConfigurations) app.engine.stopVoice()
        app.engine.screenVisible("voice", false)
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (app.hasMic()) { app.engine.startVoice(); sessionStart = System.currentTimeMillis() }
        else errorText.text = "Clu needs the microphone for voice chat. Audio goes only to CLU on your PC (or stays on this device)."
    }

    private fun build(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.BLACK, Color.parseColor("#02060A"), Color.parseColor("#051019"))) }
        // Top: back to chat, connection status, end.
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        bar.addView(iconButton(Icon.Kind.BACK, "Back to chat") { finish() }, LinearLayout.LayoutParams(dp(46), dp(46)))
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; background = panel(C.SURFACE2, C.BORDER_HI, 6f); setPadding(dp(14), dp(8), dp(14), dp(8))
            connDot = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.MUTED) } }
            addView(connDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) })
            connText = TextView(context).apply { text = "Clu"; label(C.TEXT, 10.5f) }
            addView(connText)
        }
        bar.addView(FrameLayout(this).apply { addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(iconButton(Icon.Kind.CLOSE, "End voice session") { Sfx.play(SfxSynth.Sound.SHUTDOWN); app.engine.end(); finish() }, LinearLayout.LayoutParams(dp(46), dp(46)))
        top = bar
        root.addView(bar)

        // Centre: CLU (no text inside). The state is written on one line just below it.
        val stage = FrameLayout(this)
        hud = EntityView(this)
        stage.addView(hud, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1.3f))
        val status = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(2)) }
        phaseLabel = TextView(this).apply { textSize = 13f; setTextColor(C.ACCENT); typeface = Type.monoBold; letterSpacing = 0.45f; gravity = Gravity.CENTER }
        timerLabel = TextView(this).apply { textSize = 12f; setTextColor(C.MUTED); typeface = Type.mono; gravity = Gravity.CENTER; setPadding(dp(14), 0, 0, 0) }
        status.addView(phaseLabel); status.addView(timerLabel)
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        // Live words: what CLU heard, and what CLU is saying.
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(28), dp(6), dp(28), dp(4)) }
        youText = TextView(this).apply { textSize = 14.5f; setTextColor(C.MUTED); gravity = Gravity.CENTER; maxLines = 2; typeface = Type.body }
        cluText = TextView(this).apply { textSize = 19f; setTextColor(C.TEXT); gravity = Gravity.CENTER; maxLines = 5; setLineSpacing(0f, 1.15f); setPadding(0, dp(8), 0, 0); typeface = Type.light }
        errorText = TextView(this).apply { textSize = 13f; setTextColor(C.WARN); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
            setOnClickListener { if (last.link == Link.NOT_CONFIGURED) startActivity(Intent(this@VoiceChatActivity, ConnectionActivity::class.java)) } }
        words.addView(youText); words.addView(cluText); words.addView(errorText)
        root.addView(words, LinearLayout.LayoutParams(-1, 0, 0.7f))

        // Controls, kept away from the screen edges and the gesture bar.
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(28), dp(8), dp(28), dp(22)) }
        speakerBtn = iconButton(Icon.Kind.SPEAKER, "Spoken replies on/off") { Sfx.play(SfxSynth.Sound.CLICK); app.engine.setSpokenReplies(!last.spokenReplies) }
        row.addView(speakerBtn, LinearLayout.LayoutParams(dp(54), dp(54)))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        mainBtn = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER; Motion.press(this)
            setOnClickListener {
                when {
                    last.busy -> { Sfx.play(SfxSynth.Sound.CLICK); app.engine.stop() }                                   // interrupt CLU
                    last.link == Link.NOT_CONFIGURED -> startActivity(Intent(this@VoiceChatActivity, ConnectionActivity::class.java))
                    !last.micOn && Sfx.play(SfxSynth.Sound.LISTEN) -> postDelayed({ app.engine.setMic(true) }, 150)    // tone first, then the mic
                    else -> app.engine.setMic(!last.micOn)
                }
            }
        }
        row.addView(mainBtn, LinearLayout.LayoutParams(dp(84), dp(84)))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(iconButton(Icon.Kind.KEYBOARD, "Text chat") { finish() }, LinearLayout.LayoutParams(dp(54), dp(54)))
        controls = row
        root.addView(row)
        applySystemInsets(root)
        if (Motion.enabled()) { Motion.expand(top, 0, 360); Motion.rise(stage, 80, 16f, 520); Motion.rise(status, 260); Motion.rise(words, 320); Motion.expand(row, 180, 420) }
        return root
    }

    private fun iconButton(kind: Icon.Kind, label: String, onClick: () -> Unit) = android.widget.ImageView(this).apply {
        setImageDrawable(icon(kind, C.ACCENT2, 22f)); scaleType = android.widget.ImageView.ScaleType.CENTER; background = panel(C.SURFACE2, C.BORDER_HI, 8f); contentDescription = label
        Motion.press(this); setOnClickListener { onClick() }
    }

    private fun render(s: EngineUi) {
        last = s
        val (dot, conn) = when {
            !s.server -> C.MUTED to "On-device"
            s.link == Link.CONNECTED && s.phase == AgentPhase.ABSENT -> C.WARN to "Waiting for Clu…"
            s.link == Link.CONNECTED -> C.ACCENT to "Connected"
            s.link == Link.CONNECTING -> C.WARN to "Connecting…"
            s.link == Link.RECONNECTING -> C.WARN to "Reconnecting…"
            s.link == Link.NOT_CONFIGURED -> C.ERROR to "Not set up"
            else -> C.ERROR to "Disconnected"
        }
        (connDot.background as GradientDrawable).setColor(dot); connText.text = conn
        val connected = !s.server || s.link == Link.CONNECTED
        hud.mode = when {
            !connected -> EntityView.Mode.OFFLINE
            s.phase == AgentPhase.SPEAKING -> EntityView.Mode.SPEAKING
            s.phase == AgentPhase.THINKING || s.thinking -> EntityView.Mode.THINKING
            s.phase == AgentPhase.LISTENING && s.micOn -> EntityView.Mode.LISTENING
            else -> EntityView.Mode.IDLE
        }
        hud.level = if (hud.mode == EntityView.Mode.SPEAKING) s.agentLevel else s.micLevel
        phaseLabel.text = when (hud.mode) {
            EntityView.Mode.LISTENING -> "LISTENING"; EntityView.Mode.THINKING -> "THINKING"; EntityView.Mode.SPEAKING -> "SPEAKING"
            EntityView.Mode.OFFLINE -> if (s.link == Link.NOT_CONFIGURED) "NOT SET UP" else if (s.link == Link.CONNECTING || s.link == Link.RECONNECTING) "CONNECTING" else "OFFLINE"
            EntityView.Mode.IDLE -> when {
                s.server && s.phase == AgentPhase.ABSENT -> "WAITING"
                s.phase == AgentPhase.INITIALIZING -> "STARTING"
                !s.micOn -> "MIC OFF"
                else -> "READY"
            }
        }
        phaseLabel.setTextColor(if (hud.mode == EntityView.Mode.OFFLINE) C.ERROR else C.ACCENT)
        hud.contentDescription = "Clu: " + phaseLabel.text.toString().lowercase()
        youText.text = s.userLive.ifBlank { lastMessage(true) }
        cluText.text = s.agentLive.ifBlank { lastMessage(false) }
        errorText.text = s.error ?: if (s.server && s.link == Link.RECONNECTING && s.reconnectAttempt > 0) "Reconnecting (attempt ${s.reconnectAttempt})…" else ""
        mainBtn.setImageDrawable(icon(if (s.busy) Icon.Kind.STOP else if (s.micOn) Icon.Kind.MIC_ON else Icon.Kind.MIC, if (s.busy || s.micOn) C.ACCENT2 else C.TEXT, 28f))
        val active = s.busy || s.micOn
        if (mainActive != active) { mainBg = HexButtonBg(resources.displayMetrics.density, active); mainActive = active; mainBtn.background = mainBg }
        mainBg?.level = if (s.micOn && !s.busy) s.micLevel else 0f
        mainBtn.contentDescription = if (s.busy) "Stop Clu" else if (s.micOn) "Mute microphone" else "Unmute microphone"
        mainBtn.alpha = if (s.busy || s.micOn) 1f else 0.75f
        speakerBtn.setImageDrawable(icon(if (s.spokenReplies) Icon.Kind.SPEAKER else Icon.Kind.SPEAKER_OFF, if (s.spokenReplies) C.ACCENT2 else C.MUTED, 22f))
    }

    private fun lastMessage(user: Boolean): String = runCatching {
        core.conversations.messages(app.currentConversation()).lastOrNull { (it.role == ai.clu.core.conversation.Role.USER) == user && it.role != ai.clu.core.conversation.Role.SYSTEM }?.content.orEmpty()
    }.getOrDefault("")

    private fun tick() {
        if (last.micOn && sessionStart > 0) {
            val s = (System.currentTimeMillis() - sessionStart) / 1000
            timerLabel.text = "%02d:%02d".format(s / 60, s % 60)
        } else timerLabel.text = ""
    }
}
