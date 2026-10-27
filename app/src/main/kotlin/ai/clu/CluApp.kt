package ai.clu

import ai.clu.core.CluCore
import ai.clu.core.config.ServicePolicy
import ai.clu.core.config.Settings
import ai.clu.core.security.Actor
import ai.clu.core.security.PersonRole
import ai.clu.platform.AndroidPlatform
import ai.clu.platform.AndroidSqlDriver
import ai.clu.proactive.ProactiveScheduler
import ai.clu.sync.SyncManager
import ai.clu.tools.*
import ai.clu.voice.*
import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android composition root. There is exactly one Clu entity per installation (CluCore); everything here is
 * an execution-environment service acting on its behalf.
 */
class CluApp : Application() {
    lateinit var platform: AndroidPlatform
    lateinit var core: CluCore
    lateinit var packs: VoicePackManager
    lateinit var sherpa: SherpaEngines
    lateinit var speech: SpeechOutput
    lateinit var callAudio: ai.clu.voice.CallAudio
    lateinit var whisper: ai.clu.voice.WhisperStt
    lateinit var voice: VoiceSession
    lateinit var liveKit: LiveKitSession
    /** The conversation engine both screens use (server agent or on-device). */
    lateinit var engine: ai.clu.voice.Engine
    lateinit var sync: SyncManager
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _conversation = MutableStateFlow<String?>(null)
    val conversation: StateFlow<String?> get() = _conversation
    @Volatile var lastInteractionAt = 0L
    @Volatile var inForeground = false
    /** Time of the last successful owner verification (PIN/device credential), for sensitive actions. */
    @Volatile var verifiedAt = 0L
    val startedAt = System.currentTimeMillis()

    /** False if startup failed; the UI then shows [CrashReportActivity] instead of touching the core. */
    val isReady get() = ::core.isInitialized && ::sync.isInitialized && CrashReport.startupError == null

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashReport.install(this)
        CrashReport.startupError = null
        try { start() } catch (e: Throwable) {
            CrashReport.startupError = e
            runCatching { CrashReport.save(this, "startup", e) }
        }
    }

    private fun start() {
        val t0 = System.currentTimeMillis()
        platform = platformFactory?.invoke(this) ?: AndroidPlatform(this)
        core = CluCore(AndroidSqlDriver(this), platform)
        core.agent.trace.sink = { android.util.Log.i("CluTurn", it) } // request lifecycle, no content
        if (core.settings.isDefault(Settings.DEVICE_NAME)) core.settings.set(Settings.DEVICE_NAME, platform.deviceName())
        packs = VoicePackManager(this, core.diag)
        sherpa = SherpaEngines(this, packs)
        // Engine ids from earlier versions ("sherpa" = Piper, "android" = system engine) are rewritten once.
        core.settings.string(Settings.TTS_ENGINE).let { e -> ai.clu.core.voice.TtsSelection.normalise(e).let { n -> if (n != e) core.settings.set(Settings.TTS_ENGINE, n) } }
        speech = SpeechOutput(this, sherpa, packs, core.diag)
        callAudio = ai.clu.voice.CallAudio(this, core.diag)
        // An unavailable or failing speech engine is shown to the owner (never replaced by another engine silently).
        scope.launch {
            var last: String? = null
            speech.status.collect { st ->
                val problem = st.lastError
                if (problem != null && problem != last) launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@CluApp, "Clu cannot speak: $problem", android.widget.Toast.LENGTH_LONG).show()
                }
                last = problem
            }
        }
        whisper = ai.clu.voice.WhisperStt(this, core.diag)
        registerAndroidTools()
        voice = VoiceSession(this)
        liveKit = LiveKitSession(this)
        engine = ai.clu.voice.Engine(this)
        sync = SyncManager(this)
        createChannels()
        // The model loads when a screen becomes visible or voice starts (LlmService.prepare), not in a process Android
        // started in the background, which it throttles and freezes.
        core.diag.setForeground(false)
        trackVisibility()
        core.startModel(preload = false)
        sync.start()
        ProactiveScheduler.schedule(this)
        scope.launch { core.settings.values.collect { applyServicePolicy() } }
        core.diag.perf("app_create_ms", (System.currentTimeMillis() - t0).toDouble())
        core.diag.i("app", "started on ${ai.clu.platform.DeviceInfo.summary(this)}")
    }

    private var startedActivities = 0

    /** Any Clu screen visible = foreground (Diagnostics keeps background-spanning timings apart; the model gets ready). */
    private fun trackVisibility() = registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
        override fun onActivityStarted(a: android.app.Activity) {
            if (startedActivities++ == 0 && isReady) { core.diag.setForeground(true); core.llm.prepare() }
        }
        override fun onActivityStopped(a: android.app.Activity) {
            if (--startedActivities == 0 && !a.isChangingConfigurations && isReady) core.diag.setForeground(false)
        }
        override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
        override fun onActivityResumed(a: android.app.Activity) {}
        override fun onActivityPaused(a: android.app.Activity) {}
        override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
        override fun onActivityDestroyed(a: android.app.Activity) {}
    })

    private fun registerAndroidTools() {
        val r = core.toolRegistry
        listOf(OpenAppTool(this, core.settings), AlarmTool(this), TimerTool(this), FlashlightTool(this), VolumeTool(this), MapsOpenTool(this),
            DialTool(this), WebOpenTool(this), SettingsPanelTool(this), CalendarAddTool(this),
            DeviceStatusTool(this, platform::isOnline) { statusLine() }).forEach(r::register)
    }

    fun statusLine(): String {
        val llm = core.llm.state.value
        return "Model: ${llm.modelName ?: "none"} (${llm.status.name.lowercase()}). Voice: ${if (voice.isRunning) "listening" else "off"}. " +
            "Memories: ${core.memories.count()}."
    }

    fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts/stops the background listening service according to settings and permission. */
    fun applyServicePolicy() {
        val shouldRun = ServicePolicy.shouldRunListeningService(core.settings, hasMic())
        if (shouldRun && !ListeningService.running) runCatching { startForegroundService(Intent(this, ListeningService::class.java)) }
            .onFailure { core.diag.w("app", "cannot start listening service: ${it.message}") }
        if (!shouldRun && ListeningService.running) stopService(Intent(this, ListeningService::class.java))
    }

    fun stopListening() {
        voice.stop()
        if (ListeningService.running) stopService(Intent(this, ListeningService::class.java))
    }

    fun currentConversation(): String {
        _conversation.value?.let { id -> if (core.conversations.get(id)?.deleted == false) return id }
        val c = core.conversations.list().firstOrNull() ?: core.conversations.create(personaId = core.personas.active().id)
        _conversation.value = c.id
        return c.id
    }

    fun openConversation(id: String?) { _conversation.value = id ?: core.conversations.create(personaId = core.personas.active().id).id }

    fun ownerActor() = Actor(core.people.owner()?.id, PersonRole.OWNER, viaVoice = false, verifiedAt = verifiedAt)

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_LISTEN, "Background listening", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_CLU, "Messages from Clu", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_DOWNLOAD, "Downloads", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!isReady) return
        // Release heavy engines when the system is short of memory and Clu is not actively in a conversation.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && !voice.isRunning) {
            sherpa.releaseAll()
            whisper.release()
            scope.launch { core.llm.unload() }
            core.diag.i("app", "memory pressure (level $level): released models")
        }
    }

    companion object {
        lateinit var instance: CluApp
        /** Test hook (Robolectric has no Android Keystore); null in production. */
        @Volatile var platformFactory: ((android.content.Context) -> AndroidPlatform)? = null
        const val CH_LISTEN = "listen"
        const val CH_CLU = "clu"
        const val CH_DOWNLOAD = "download"
    }
}
