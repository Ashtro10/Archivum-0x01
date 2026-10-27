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
 * Voice chat: Clu's hexagonal HUD. Everything on screen reflects the engine's real state ([EngineUi]): the HUD
 * listens only while the microphone is live, "thinks" only while the agent is thinking, and pulses with the level of
 * Clu's actual audio while speaking. Leaving the screen stops listening (the conversation history stays).
 */
class VoiceChatActivity : BaseActivity() {
    private lateinit var hud: HexHudView
    private lateinit var phaseLabel: TextView
    private lateinit var timerLabel: TextView
    private lateinit var connDot: View
    private lateinit var connText: TextView
    private lateinit var youText: TextView
    private lateinit var cluText: TextView
    private lateinit var errorText: TextView
    private lateinit var mainBtn: TextView
    private lateinit var speakerBtn: TextView
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
        else errorText.text = "Clu needs the microphone for voice chat. Audio goes only to your Clu server (or stays on this device)."
    }

    private fun build(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        // Top: back to chat, title and connection status.
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        top.addView(iconButton("←", "Back to chat") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; background = glassBg(); setPadding(dp(14), dp(8), dp(14), dp(8))
            connDot = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.MUTED) } }
            addView(connDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
            connText = TextView(context).apply { text = "Clu"; textSize = 13f; setTextColor(C.TEXT) }
            addView(connText)
        }
        top.addView(FrameLayout(this).apply { addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(iconButton("✕", "End voice session") { app.engine.end(); finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(top)

        // Centre: the HUD (Clu's eyes; no text inside). The state is written on one line just below it.
        val stage = FrameLayout(this)
        hud = HexHudView(this)
        stage.addView(hud, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1.25f).apply { setMargins(dp(16), 0, dp(16), 0) })
        val status = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(2)) }
        phaseLabel = TextView(this).apply { textSize = 14f; setTextColor(C.ACCENT); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); letterSpacing = 0.35f; gravity = Gravity.CENTER }
        timerLabel = TextView(this).apply { textSize = 12f; setTextColor(C.MUTED); typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER; setPadding(dp(12), 0, 0, 0) }
        status.addView(phaseLabel); status.addView(timerLabel)
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        // Live words: what Clu heard, and what Clu is saying.
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(28), dp(4), dp(28), dp(4)) }
        youText = TextView(this).apply { textSize = 15f; setTextColor(C.MUTED); gravity = Gravity.CENTER; maxLines = 2 }
        cluText = TextView(this).apply { textSize = 19f; setTextColor(C.TEXT); gravity = Gravity.CENTER; maxLines = 5; setLineSpacing(0f, 1.15f); setPadding(0, dp(8), 0, 0) }
        errorText = TextView(this).apply { textSize = 13f; setTextColor(C.WARN); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
            setOnClickListener { if (last.link == Link.NOT_CONFIGURED) startActivity(Intent(this@VoiceChatActivity, ConnectionActivity::class.java)) } }
        words.addView(youText); words.addView(cluText); words.addView(errorText)
        root.addView(words, LinearLayout.LayoutParams(-1, 0, 0.75f))

        // Controls, kept away from the screen edges and the gesture bar.
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(8), dp(24), dp(20)) }
        speakerBtn = iconButton("🔊", "Spoken replies on/off") { app.engine.setSpokenReplies(!last.spokenReplies) }
        controls.addView(speakerBtn, LinearLayout.LayoutParams(dp(56), dp(56)))
        controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        mainBtn = TextView(this).apply {
            gravity = Gravity.CENTER; textSize = 26f; setTextColor(Color.WHITE); background = accentBg(); elevation = dp(6).toFloat()
            setOnClickListener {
                when {
                    last.busy -> app.engine.stop()                                   // interrupt Clu
                    last.link == Link.NOT_CONFIGURED -> startActivity(Intent(this@VoiceChatActivity, ConnectionActivity::class.java))
                    else -> app.engine.setMic(!last.micOn)
                }
            }
        }
        controls.addView(mainBtn, LinearLayout.LayoutParams(dp(78), dp(78)))
        controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(iconButton("⌨", "Text chat") { finish() }, LinearLayout.LayoutParams(dp(56), dp(56)))
        root.addView(controls)
        applySystemInsets(root)
        return root
    }

    private fun iconButton(icon: String, label: String, onClick: () -> Unit) = TextView(this).apply {
        text = icon; textSize = 20f; gravity = Gravity.CENTER; setTextColor(C.TEXT); background = glassBg(); contentDescription = label
        setOnClickListener { onClick() }
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
            !connected -> HexHudView.Mode.OFFLINE
            s.phase == AgentPhase.SPEAKING -> HexHudView.Mode.SPEAKING
            s.phase == AgentPhase.THINKING || s.thinking -> HexHudView.Mode.THINKING
            s.phase == AgentPhase.LISTENING && s.micOn -> HexHudView.Mode.LISTENING
            else -> HexHudView.Mode.IDLE
        }
        hud.level = if (hud.mode == HexHudView.Mode.SPEAKING) s.agentLevel else s.micLevel
        phaseLabel.text = when (hud.mode) {
            HexHudView.Mode.LISTENING -> "LISTENING"; HexHudView.Mode.THINKING -> "THINKING"; HexHudView.Mode.SPEAKING -> "SPEAKING"
            HexHudView.Mode.OFFLINE -> if (s.link == Link.NOT_CONFIGURED) "NOT SET UP" else if (s.link == Link.CONNECTING || s.link == Link.RECONNECTING) "CONNECTING" else "OFFLINE"
            HexHudView.Mode.IDLE -> when {
                s.server && s.phase == AgentPhase.ABSENT -> "WAITING"
                s.phase == AgentPhase.INITIALIZING -> "STARTING"
                !s.micOn -> "MIC OFF"
                else -> "READY"
            }
        }
        phaseLabel.setTextColor(if (hud.mode == HexHudView.Mode.OFFLINE) C.ERROR else C.ACCENT)
        hud.contentDescription = "Clu: " + phaseLabel.text.toString().lowercase()
        youText.text = s.userLive.ifBlank { lastMessage(true) }
        cluText.text = s.agentLive.ifBlank { lastMessage(false) }
        errorText.text = s.error ?: if (s.server && s.link == Link.RECONNECTING && s.reconnectAttempt > 0) "Reconnecting (attempt ${s.reconnectAttempt})…" else ""
        mainBtn.text = if (s.busy) "■" else if (s.micOn) "🎙" else "🎤"
        mainBtn.contentDescription = if (s.busy) "Stop Clu" else if (s.micOn) "Mute microphone" else "Unmute microphone"
        mainBtn.alpha = if (s.busy || s.micOn) 1f else 0.55f
        speakerBtn.alpha = if (s.spokenReplies) 1f else 0.4f
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
