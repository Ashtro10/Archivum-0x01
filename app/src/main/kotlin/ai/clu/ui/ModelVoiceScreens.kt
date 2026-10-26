package ai.clu.ui

import ai.clu.core.config.Settings
import ai.clu.core.llm.*
import ai.clu.core.voice.WakeWord
import ai.clu.voice.VoicePack
import ai.clu.voice.VoicePacks
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ModelsActivity : BaseActivity() {
    private val modelsDir get() = File(getExternalFilesDir(null), "models").apply { mkdirs() }

    override fun onResume() {
        super.onResume()
        // Leftovers of an import interrupted by the app being closed (never registered as models).
        modelsDir.listFiles { f -> f.name.endsWith(".part") && System.currentTimeMillis() - f.lastModified() > 120_000 }?.forEach { it.delete() }
        build()
        scope.launch { core.llm.state.collect { if (!isFinishing) statusView?.text = describe(it) } }
        scope.launch { while (isActive) { delay(1500); checkDownloads() } }
    }

    private var statusView: TextView? = null
    private fun describe(s: LlmState) = "Status: ${s.status.name.lowercase()}${s.modelName?.let { " · $it" } ?: ""}${if (s.loadMs > 0) " · loaded in ${s.loadMs} ms" else ""}" +
        (if (s.contextSize > 0) " · context ${s.contextSize}" else "") + (if (s.message.isNotBlank()) "\n${s.message}" else "")

    private fun build(): Unit = screen("AI models") {
        statusView = p(describe(core.llm.state.value), C.TEXT)
        row {
            smallButton("Load") { scope.launch { core.llm.loadIfNeeded() } }
            smallButton("Unload") { scope.launch { core.llm.unload() } }
            smallButton("Reload") { scope.launch { core.llm.reload() } }
            smallButton("Benchmark") { benchmark() }
        }
        p("Native backend: " + runCatching { if (!LlamaBridge.isLoaded) LlamaBridge.load(); LlamaBridge.nativeSystemInfo() }.getOrDefault("not loaded"), C.MUTED, 11f)
        h1("Installed models")
        val active = core.settings.string(Settings.MODEL_ACTIVE)
        val models = core.models.list()
        if (models.isEmpty()) p("No models yet. Import a GGUF file you already have, or download one below.", C.TEXT)
        models.forEach { m ->
            card {
                p((if (m.id == active) "● " else "") + m.name, C.TEXT, 15f)
                p("${m.format} · ${m.info.architecture} · ${m.info.quantization} · ${fmtBytes(m.sizeBytes)} · trained context ${m.info.contextLength}" +
                    " · needs ≈${fmtBytes(ModelRegistry.estimateRamBytes(m, core.settings.int(Settings.CONTEXT_LENGTH)))} RAM", C.MUTED, 12f)
                p("${m.status.label}${m.lastError?.let { "\n" + it.take(300) } ?: ""}", if (m.status == ModelStatus.INCOMPATIBLE || m.status == ModelStatus.MISSING) C.WARN else C.MUTED, 12f)
                row {
                    if (m.id != active) smallButton("Use this model") { core.settings.set(Settings.MODEL_ACTIVE, m.id); scope.launch { core.llm.reload() }; build() }
                    smallButton("Rename") { prompt("Name", m.name) { core.models.rename(m.id, it); build() } }
                    smallButton("Remove") { confirm("Remove ${m.name}?", "Files copied into Clu's storage are deleted.") {
                        if (m.id == active) core.settings.set(Settings.MODEL_ACTIVE, ""); core.models.remove(m.id, modelsDir); build() } }
                }
            }
        }
        button("Import GGUF file…") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 1) }
        h1("Download (needs internet, asks first)")
        p("Downloads run in the background via Android's download manager and resume after interruptions. The links point to the models' public Hugging Face pages.", C.MUTED, 12f)
        ModelRegistry.catalog.forEach { c ->
            card {
                p(c.name, C.TEXT); p("${fmtBytes(c.approxBytes)} · ${c.notes}", C.MUTED, 12f)
                smallButton("Download ${fmtBytes(c.approxBytes)}") { startDownload(c) }
            }
        }
        h1("Recovery")
        button("Restore known-good configuration") { verifyOwner("Restore configuration") { ok -> if (ok) toast(core.settings.restoreKnownGood(true).joinToString().ifEmpty { "Restored." }) } }
    }

    private fun startDownload(c: CatalogModel) {
        core.network.check(ai.clu.core.tools.NetCategory.DOWNLOADS)?.let { toast(it); return }
        val free = File(getExternalFilesDir(null)!!.path).usableSpace
        if (free < c.approxBytes * 1.1) { toast("Not enough storage: ${fmtBytes(free)} free, ${fmtBytes(c.approxBytes)} needed."); return }
        val name = c.url.substringAfterLast('/')
        // A valid local copy is never downloaded again (it survives app updates in Clu's storage).
        val existing = File(modelsDir, name)
        if (existing.exists()) {
            val registered = core.models.list().firstOrNull { it.path == existing.absolutePath }
            val valid = registered ?: runCatching { core.models.register(existing) }.getOrNull()
            if (valid != null) { toast("${c.name} is already on this device."); build(); return }
            toast("The earlier copy of ${c.name} is incomplete or damaged; it will be replaced.")
            existing.delete()
        }
        confirm("Download ${fmtBytes(c.approxBytes)}?", "${c.name}\n\nThis uses ${fmtBytes(c.approxBytes)} of data and storage. Use Wi-Fi if your data plan is limited.", "Download") {
            val req = DownloadManager.Request(Uri.parse(c.url)).setTitle(c.name).setDestinationInExternalFilesDir(this, "models", name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            val id = getSystemService(DownloadManager::class.java).enqueue(req)
            core.store.setMeta("download:$id", name)
            toast("Download started. It will appear here when finished.")
        }
    }

    private fun checkDownloads() {
        val dm = getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL))?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                val name = core.store.meta("download:$id") ?: continue
                core.store.setMeta("download:$id", "")
                if (name.isEmpty()) continue
                val f = File(modelsDir, name)
                runCatching { core.models.register(f) }.onSuccess { e ->
                    if (core.settings.string(Settings.MODEL_ACTIVE).isEmpty()) core.settings.set(Settings.MODEL_ACTIVE, e.id)
                    toast("Model ready: ${e.name}"); build()
                }.onFailure { toast("Downloaded file is not a valid model: ${it.message}") }
            }
        }
    }

    private fun benchmark() = scope.launch {
        val e = core.llm.engine() ?: return@launch toast(core.llm.state.value.message.ifBlank { "No model" })
        toast("Benchmarking…")
        val r = withContext(Dispatchers.IO) { runCatching { e.generate(listOf(ChatMessage("user", "Write three sentences about the ocean.")), GenParams(maxTokens = 64, temperature = 0f)) {} } }
        r.onSuccess { g ->
            core.diag.perf("bench_tok_per_s", g.stats.tokensPerSecond, core.llm.state.value.modelName ?: "")
            AlertDialog(this@ModelsActivity, "Benchmark", "Prompt: ${g.stats.promptTokens} tokens in ${"%.0f".format(g.stats.promptMs)} ms\n" +
                "Generated: ${g.stats.generatedTokens} tokens in ${"%.0f".format(g.stats.generateMs)} ms (${"%.1f".format(g.stats.tokensPerSecond)} tok/s)\n" +
                "First token: ${"%.0f".format(g.stats.firstTokenMs)} ms")
        }.onFailure { toast("Benchmark failed: ${it.message}") }
    }

    @Deprecated("framework API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        val name = (contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "model.gguf")
        val dest = File(modelsDir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        // Never overwrite a model that is registered (it may be loaded right now); the same file is not copied twice.
        core.models.list().firstOrNull { it.path == dest.absolutePath }?.let { m ->
            if (m.status != ModelStatus.MISSING && dest.exists()) { toast("${m.name} is already imported."); return }
        }
        // Copied to a temporary name and renamed only after it validates: an interrupted copy never looks like a model.
        val part = File(modelsDir, dest.name + ".part")
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        val dlg = android.app.AlertDialog.Builder(this).setTitle("Copying model…").setView(bar).setCancelable(false).show()
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val total = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    contentResolver.openInputStream(uri)!!.use { i -> part.outputStream().use { o ->
                        val buf = ByteArray(1 shl 20); var done = 0L
                        while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n
                            if (total > 0) launch(Dispatchers.Main) { bar.progress = (done * 100 / total).toInt() } }
                    } }
                    if (total > 0 && part.length() != total) throw java.io.IOException("copy incomplete (${part.length()} of $total bytes)")
                    ai.clu.core.llm.GgufReader.read(part) // validates header and completeness before it becomes a model
                    if (!part.renameTo(dest)) throw java.io.IOException("could not move the model into place")
                    core.models.register(dest)
                }
            }
            dlg.dismiss()
            r.onSuccess { e -> if (core.settings.string(Settings.MODEL_ACTIVE).isEmpty()) core.settings.set(Settings.MODEL_ACTIVE, e.id); toast("Imported ${e.name}"); build() }
                .onFailure { part.delete(); toast("Import failed: ${it.message ?: it::class.simpleName}") }
        }
    }
}

private fun AlertDialog(a: android.app.Activity, title: String, msg: String) =
    android.app.AlertDialog.Builder(a).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()

class VoiceActivity : BaseActivity() {
    override fun onResume() {
        super.onResume(); build()
        // Opened from the chat banner's "Download" action: offer that voice pack straight away.
        intent.getStringExtra("download")?.let { id -> intent.removeExtra("download"); VoicePacks.byId(id)?.takeIf { !app.packs.isInstalled(it) }?.let { downloadPack(it, null) } }
    }

    private fun build(): Unit = screen("Voice") {
        val v = app.voice.state.value
        p("Offline speech recognition, wake-word and voice-activity models are built in. The Clu voice and speaker recognition are optional downloads.", C.MUTED, 12f)
        h1("Listening & speech recognition")
        card {
            val c = cfg()
            val whisperOk = app.whisper.model(c.whisperModel)?.let { app.whisper.isInstalled(it) } == true
            val route = ai.clu.core.voice.AsrRouting.plan(c.sttLanguage, whisperOk)
            fun choiceLabel(key: String) = (Settings[key]!!.type as ai.clu.core.config.SettingType.Choice).options
            p("Spoken language: ${choiceLabel("voice.sttLanguage").first { it.first == c.sttLanguage }.second}", C.TEXT, 14f)
            p("Final transcript by the ${route.engineLabel}. Live English recogniser: ${app.sherpa.asrModelName} (built in, offline).", C.MUTED, 12f)
            route.note?.let { p("⚠ $it", C.WARN, 12f) }
            p("Listening mode: ${choiceLabel("voice.micMode").first { it.first == core.settings.string(Settings.MIC_MODE) }.second}", C.TEXT, 14f)
            p("End-of-speech pause: ${core.settings.int(Settings.END_SILENCE)} ms · follow-up window ${core.settings.int(Settings.FOLLOWUP_TIMEOUT)} s", C.TEXT, 14f)
            row {
                smallButton("Language…") { val o = choiceLabel("voice.sttLanguage"); choose("Spoken language", o.map { it.second }) { i -> core.settings.set(Settings.STT_LANGUAGE, o[i].first); build() } }
                smallButton("Listening…") { val o = choiceLabel("voice.micMode"); choose("Listening mode", o.map { it.second }) { i -> core.settings.set(Settings.MIC_MODE, o[i].first); build() } }
                smallButton("Pause…") {
                    val o = listOf(650, 800, 1000, 1300)
                    choose("End-of-speech pause", o.map { "$it ms" + when (it) { 650 -> " (fast, may cut pauses)"; 800 -> " (default)"; 1000 -> " (relaxed)"; else -> " (slow speakers)" } }) { i ->
                        core.settings.set(Settings.END_SILENCE, o[i]); build() }
                }
            }
            smallButton("Speech recognition diagnostics") { startActivity(Intent(this@VoiceActivity, SpeechDiagnosticsActivity::class.java)) }
        }
        p("Whisper (offline, English + Hindi) is used for Hindi, mixed and automatic language. It transcribes when you stop speaking.", C.MUTED, 12f)
        ai.clu.core.stt.WhisperModels.all.forEach { m -> card { whisperCard(m) } }
        ttsSection()
        h1("Voice packs")
        VoicePacks.all.forEach { pack -> card { packCard(pack) } }
        h1("Test")
        row {
            smallButton(if (v.running) "Stop listening" else "Start conversation") { if (v.running) app.voice.stop() else if (app.hasMic()) app.voice.start(false) else toast("Grant microphone permission first (mic button in chat).") }
            smallButton("Test wake word") { if (app.hasMic()) { app.voice.start(true); toast("Say \"${core.settings.wakeWords().first { it.primary }.phrase}\".") } }
        }
        val live = p("", C.TEXT, 13f)
        scope.launch { app.voice.state.collect { s -> live.text = "State: ${s.turn.name.lowercase()} · noise ${"%.1f".format(s.noiseDb)} dBFS (${s.environment.name.lowercase()})\n" +
            "Heard: ${s.lastHeard}\nPartial: ${s.partial}\nSpeaker: ${s.speaker}\n${s.info}${if (s.error.isNotBlank()) "\n⚠ " + s.error else ""}" +
            (if (s.lastLatency.isNotEmpty()) "\nLast latency: " + s.lastLatency.entries.joinToString { "${it.key}=${"%.0f".format(it.value)}" } else "") } }
        h1("Conversation mode")
        val lkMode = core.settings.string(Settings.VOICE_MODE) == "livekit"
        val decision = app.liveKit.decide()
        p(if (lkMode) "LiveKit: speech recognition and Clu's voice run on your own LiveKit server; the model, memory, tools and permissions stay on this device."
          else "Offline: microphone, speech recognition, model and voice all run on this device. No network needed.", C.MUTED, 12f)
        p("Next conversation: ${if (decision.path == ai.clu.core.livekit.ConversationPath.LIVEKIT) "LiveKit" else "offline"} (${decision.reason})", C.TEXT, 13f)
        row {
            smallButton(if (lkMode) "Use offline" else "Use LiveKit") {
                core.settings.set(Settings.VOICE_MODE, if (lkMode) "offline" else "livekit"); build()
            }
            smallButton(if (app.liveKit.deviceKey() == null) "Set device key" else "Replace device key") {
                verifyOwner("Change the LiveKit device key") { ok ->
                    if (ok) prompt("Device key", hint = "id.secret from: clu_voice_server.py add-device <id>", password = true) { key ->
                        if (key.matches(Regex("[a-z0-9][a-z0-9-]{0,30}\\.[A-Za-z0-9_-]{16,}"))) { app.liveKit.setDeviceKey(key); toast("Device key saved (encrypted)."); build() }
                        else toast("That does not look like a device key (id.secret).")
                    }
                }
            }
            if (app.liveKit.deviceKey() != null) smallButton("Remove key") {
                verifyOwner("Remove the LiveKit device key") { ok -> if (ok) { app.liveKit.setDeviceKey(null); toast("Device key removed."); build() } }
            }
        }
        row {
            smallButton(if (app.liveKit.active) "End LiveKit session" else "Start LiveKit conversation") {
                when {
                    app.liveKit.active -> app.liveKit.stop()
                    !app.hasMic() -> toast("Grant microphone permission first (mic button in chat).")
                    else -> { app.voice.stop(); app.liveKit.start(resumeWakeAfter = false) }
                }
            }
            smallButton("LiveKit settings") { startActivity(Intent(this@VoiceActivity, SettingsCategoryActivity::class.java).putExtra("cat", "LIVEKIT")) }
        }
        val lkLive = p("", C.TEXT, 13f)
        scope.launch { app.liveKit.state.collect { s -> lkLive.text = "LiveKit: ${s.status.name.lowercase()}" +
            (if (s.agentState.isNotBlank()) " · agent ${s.agentState}" else "") + (s.connectMs?.let { " · connected in $it ms" } ?: "") +
            (if (s.message.isNotBlank()) "\n${s.message}" else "") + (if (s.partial.isNotBlank()) "\nHearing: ${s.partial}" else "") +
            (if (s.lastHeard.isNotBlank()) "\nHeard: ${s.lastHeard}" else "") + (if (s.lastReply.isNotBlank()) "\nReplied: ${s.lastReply.take(160)}" else "") } }
        button("Voice settings") { startActivity(Intent(this@VoiceActivity, SettingsCategoryActivity::class.java).putExtra("cat", "VOICE")) }
        button("Wake words") { startActivity(Intent(this@VoiceActivity, WakeWordsActivity::class.java)) }
    }

    private fun LinearLayout.packCard(pack: VoicePack) {
        val installed = app.packs.isInstalled(pack)
        p(pack.title, C.TEXT, 15f)
        p(if (installed) "Installed · ${fmtBytes(app.packs.sizeOnDisk(pack))}" else "Not installed · download ${fmtBytes(pack.bytes)}", if (installed) C.OK else C.MUTED, 12f)
        val prog = p("", C.MUTED, 12f)
        row {
            if (!installed) smallButton("Download ${fmtBytes(pack.bytes)}") { downloadPack(pack, prog) }
            smallButton("Import file…") { pendingImport = pack; startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 7) }
            if (installed) smallButton("Remove") { confirm("Remove ${pack.title}?", "") { app.sherpa.releaseAll(); app.packs.remove(pack); build() } }
        }
        p("Offline install: download ${pack.url.substringAfterLast('/')} on any device and import it here.", C.MUTED, 11f)
    }

    private fun cfg() = ai.clu.core.voice.VoiceConfig.from(core.settings)

    /** TTS engine, voices and measurements: everything shown here is what synthesis actually uses. */
    private fun LinearLayout.ttsSection() {
        h1("Speech output (TTS)")
        val c = cfg()
        val plan = app.speech.refresh(c)
        val st = app.speech.status.value
        card {
            p("Engine: ${ai.clu.core.voice.TtsSelection.label(plan.engine)}", C.TEXT, 15f)
            p("English voice: ${plan.english?.voice ?: "–"}", C.TEXT, 13f)
            p("Hindi voice: ${plan.hindi?.voice ?: (plan.hindiNote ?: "–")}", if (plan.hindi != null) C.TEXT else C.WARN, 13f)
            p("Languages spoken now: ${plan.languages.ifEmpty { listOf("none") }.joinToString()}", C.MUTED, 12f)
            if (st.lastSpoke.isNotBlank()) p("Last phrase spoken by: ${st.lastSpoke}", C.MUTED, 12f)
            (plan.problem ?: st.lastError)?.let { p("⚠ $it", C.WARN, 13f) }
            // The selected voice is missing: the fix is one tap away (no other voice is used meanwhile).
            if (plan.engine == ai.clu.core.voice.TtsSelection.PIPER && plan.english == null) VoicePacks.byId(c.piperVoice)?.let { pack ->
                button("Download ${pack.title.substringBefore(" (")} – ${fmtBytes(pack.bytes)}", primary = true) { downloadPack(pack, null) }
            }
            if (plan.hindi == null && c.hindiVoice == "pratham" && !app.packs.isInstalled(VoicePacks.TTS_HI_PRATHAM))
                smallButton("Download Hindi voice Pratham – ${fmtBytes(VoicePacks.TTS_HI_PRATHAM.bytes)}") { downloadPack(VoicePacks.TTS_HI_PRATHAM, null) }
            val status = p("", C.MUTED, 12f)
            row {
                smallButton("TTS engine…") {
                    val ids = listOf(ai.clu.core.voice.TtsSelection.PIPER, ai.clu.core.voice.TtsSelection.KITTEN, ai.clu.core.voice.TtsSelection.HAYAI, ai.clu.core.voice.TtsSelection.SYSTEM)
                    val labels = ids.map { id ->
                        val pl = ai.clu.core.voice.TtsSelection.plan(c.copy(ttsEngine = id), app.speech.facts())
                        ai.clu.core.voice.TtsSelection.label(id) + if (pl.available) "" else "  – unavailable"
                    }
                    choose("TTS engine", labels) { i ->
                        core.settings.set(Settings.TTS_ENGINE, ids[i])
                        val pl = app.speech.refresh(cfg())
                        if (!pl.available) toast("Selected, but unavailable: ${pl.problem}") else toast("Clu now speaks with ${ai.clu.core.voice.TtsSelection.label(ids[i])}.")
                        build()
                    }
                }
                smallButton("Voice…") { chooseVoice(plan) }
                smallButton("Hindi voice…") {
                    val ids = listOf("pratham", "engine", "off")
                    val opts = (Settings["voice.hindiVoice"]!!.type as ai.clu.core.config.SettingType.Choice).options.associate { it }
                    choose("Hindi voice", ids.map { opts[it] ?: it }) { i -> core.settings.set(Settings.TTS_HINDI_VOICE, ids[i]); build() }
                }
            }
            row {
                smallButton("Speak test") {
                    app.speech.speak("I am ${core.settings.string(Settings.CLU_NAME)}. All systems are running normally, and I am listening.", cfg())
                    if (plan.hindi != null) app.speech.speak("नमस्ते, मैं सुन रहा हूँ।", cfg())
                    if (!plan.available) toast(plan.problem ?: "")
                }
                smallButton("Stop") { app.speech.stop() }
                smallButton("Measure voice") {
                    status.text = "Measuring on this device…"
                    scope.launch(Dispatchers.Default) {
                        val lines = listOf("en", "hi").filter { it == "en" || plan.hindi != null }.map { lang ->
                            runCatching { app.speech.measureVoice(cfg(), lang) }.fold({ m ->
                                "${if (lang == "hi") "Hindi" else "English"} – ${m.label}: first phrase ${m.firstPhraseMs} ms, RTF ${"%.2f".format(m.rtf)}, " +
                                    "median pitch ${m.pitch?.let { "%.0f Hz (range %.1f semitones)".format(it.medianHz, it.spreadSemitones) } ?: "–"}"
                            }, { "${lang}: ${it.message}" })
                        }
                        launch(Dispatchers.Main) { status.text = lines.joinToString("\n") + "\n(male voices: ~85-155 Hz; recorded in Diagnostics)" }
                    }
                }
                smallButton("Streaming test") {
                    status.text = "Playing a long reply and interrupting it after 4 s…"
                    scope.launch {
                        val m = app.speech.benchmarkStreaming(cfg())
                        status.text = listOf("first_tts_to_first_audio_ms", "max_chunk_gap_ms", "interrupt_to_silence_ms", "cancel_complete_ms")
                            .joinToString("\n") { k -> "$k: ${m[k]?.let { "%.0f".format(it) } ?: "–"}" } + "\n(recorded in Diagnostics as bench_*)"
                    }
                }
            }
        }
        if (plan.engine == ai.clu.core.voice.TtsSelection.HAYAI) card {
            val installed = ai.clu.core.voice.TtsSelection.HAYAI_PACKAGE in app.speech.facts().systemEngines
            p(if (installed) "HayaiTTS is installed. Voices are downloaded inside HayaiTTS; then use Voice… → Find deepest voice." else
                "HayaiTTS is not installed. It is a separate open-source app (GPL-3.0): github.com/HayaiApp/HayaiTTS/releases", if (installed) C.MUTED else C.WARN, 12f)
            row {
                if (!installed) smallButton("Open HayaiTTS releases") {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/HayaiApp/HayaiTTS/releases"))) }
                } else smallButton("Manage HayaiTTS voices") {
                    runCatching { startActivity(Intent("dev.ahmedmohamed.hayaitts.MANAGE_VOICES").setPackage(ai.clu.core.voice.TtsSelection.HAYAI_PACKAGE)) }
                        .onFailure { runCatching { startActivity(packageManager.getLaunchIntentForPackage(ai.clu.core.voice.TtsSelection.HAYAI_PACKAGE)) } }
                }
            }
        }
    }

    private fun chooseVoice(plan: ai.clu.core.voice.TtsSelection.TtsPlan) {
        val T = ai.clu.core.voice.TtsSelection
        when (plan.engine) {
            T.PIPER -> {
                val opts = (Settings["voice.piperVoice"]!!.type as ai.clu.core.config.SettingType.Choice).options
                val installed = app.packs.installedIds()
                choose("Piper English voice", opts.map { (id, label) -> label + if (id in installed) "" else "  (not installed – download below)" }) { i ->
                    core.settings.set(Settings.TTS_PIPER_VOICE, opts[i].first)
                    VoicePacks.byId(opts[i].first)?.defaultSpeaker?.let { core.settings.set(Settings.TTS_SPEAKER, it) }
                    build()
                }
            }
            T.KITTEN -> {
                val opts = (Settings["voice.kittenSpeaker"]!!.type as ai.clu.core.config.SettingType.Choice).options
                choose("KittenTTS voice", opts.map { it.second }) { i -> core.settings.set(Settings.TTS_KITTEN_SPEAKER, opts[i].first); build() }
            }
            else -> {
                val pkg = plan.systemPackage ?: run { toast(plan.problem ?: "engine unavailable"); return }
                val langs = listOf("en", "hi")
                choose("Find the deepest installed voice", listOf("English voices", "Hindi voices", "Use the engine's default voices")) { i ->
                    if (i == 2) { core.settings.set(Settings.ANDROID_VOICE, ""); core.settings.set(Settings.ANDROID_VOICE_HI, ""); build(); return@choose }
                    val lang = langs[i]
                    toast("Measuring the pitch of each installed ${if (lang == "hi") "Hindi" else "English"} voice…")
                    scope.launch(Dispatchers.Default) {
                        val r = runCatching { app.speech.rankSystemVoices(cfg(), pkg, lang) }
                        launch(Dispatchers.Main) {
                            r.onFailure { toast(it.message ?: "failed") }.onSuccess { ranked ->
                                if (ranked.isEmpty()) { toast("No installed ${if (lang == "hi") "Hindi" else "English"} voices in this engine."); return@onSuccess }
                                choose("Deepest first (median pitch)", ranked.map { (n, f0) -> "${f0?.let { "%.0f Hz".format(it) } ?: "?"}  $n" }) { k ->
                                    core.settings.set(if (lang == "hi") Settings.ANDROID_VOICE_HI else Settings.ANDROID_VOICE, ranked[k].first); build()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun LinearLayout.whisperCard(m: ai.clu.core.stt.WhisperModel) {
        val installed = app.whisper.isInstalled(m)
        val selected = core.settings.string(Settings.WHISPER_MODEL) == m.id
        p(m.title + if (selected) "  ✓ selected" else "", C.TEXT, 15f)
        p((if (installed) "Installed (SHA1 verified)" else "Not installed") + " · ${fmtBytes(m.bytes)} on disk · about ${m.ramMb} MB memory while listening",
            if (installed) C.OK else C.MUTED, 12f)
        val prog = p("", C.MUTED, 12f)
        row {
            if (!installed) smallButton("Download ${fmtBytes(m.bytes)}") {
                core.network.check(ai.clu.core.tools.NetCategory.DOWNLOADS)?.let { toast(it); return@smallButton }
                app.whisper.storageProblem(m)?.let { toast(it); return@smallButton }
                val free = android.os.StatFs(app.whisper.dir.path).availableBytes
                confirm("Download ${fmtBytes(m.bytes)}?", "${m.fileName} from the official whisper.cpp model repository (huggingface.co/ggerganov/whisper.cpp).\n" +
                    "Needs ${fmtBytes(m.bytes)} of storage (${fmtBytes(free)} free) and about ${m.ramMb} MB of memory while listening.\n" +
                    "Verified by SHA1 before use. Use Wi-Fi.", "Download") {
                    scope.launch {
                        val r = app.whisper.download(m) { d, t -> launch(Dispatchers.Main) { prog.text = "${fmtBytes(d)} / ${fmtBytes(t)}" } }
                        r.onSuccess { toast("${m.fileName} installed."); build() }.onFailure { toast("Download failed: ${it.message}") }
                    }
                }
            }
            smallButton("Import file…") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 8) }
            if (installed && !selected) smallButton("Use") { core.settings.set(Settings.WHISPER_MODEL, m.id); core.settings.set(Settings.ASR_ENGINE, "whisper"); build() }
            if (installed) smallButton("Remove") { confirm("Remove ${m.fileName}?", "") { app.whisper.remove(m); build() } }
        }
        app.whisper.memoryProblem(m)?.let { p("⚠ $it", C.MUTED, 11f) }
        p("Offline install: copy ${m.fileName} (from ${m.url.substringBefore("/resolve")}) to this device and import it here.", C.MUTED, 11f)
    }

    private var pendingImport: VoicePack? = null

    /** Confirms size and source, downloads, verifies and installs [pack]; [prog] shows progress. */
    private fun downloadPack(pack: VoicePack, prog: TextView?) {
        core.network.check(ai.clu.core.tools.NetCategory.DOWNLOADS)?.let { toast(it); return }
        val why = if (pack.id == core.settings.string(Settings.TTS_PIPER_VOICE)) "\nThis is the voice Clu is set to speak with." else ""
        confirm("Download ${fmtBytes(pack.bytes)}?", "${pack.title}\nFrom the sherpa-onnx releases on GitHub. Verified by SHA-256 before use.$why", "Download") {
            toast("Downloading ${pack.title}…")
            scope.launch {
                val r = app.packs.download(pack) { d, t -> launch(Dispatchers.Main) { prog?.text = "${fmtBytes(d)} / ${fmtBytes(t)}" } }
                r.onSuccess { app.sherpa.releaseAll(); app.speech.refresh(cfg()); toast("${pack.title} installed."); build() }
                    .onFailure { toast("Download failed: ${it.message}") }
            }
        }
    }

    @Deprecated("framework API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        if (requestCode == 8) {
            toast("Importing and verifying…")
            scope.launch { app.whisper.import(uri).onSuccess { m -> toast("${m.fileName} installed."); build() }.onFailure { toast("Import failed: ${it.message}") } }
            return
        }
        val pack = pendingImport ?: return
        toast("Importing…")
        scope.launch { app.packs.import(pack, uri).onSuccess { app.sherpa.releaseAll(); toast("Installed."); build() }.onFailure { toast("Import failed: ${it.message}") } }
    }
}

/** Wake word editor: add/remove/enable, primary name, aliases, recorded pronunciation samples, live test. */
class WakeWordsActivity : BaseActivity() {
    override fun onResume() { super.onResume(); build() }

    private fun save(words: List<WakeWord>) {
        when (val r = core.settings.setWakeWords(words)) {
            is ai.clu.core.config.SettingResult.Invalid -> toast("Not saved: ${r.reason}")
            else -> toast("Saved – listening restarts with the new words.")
        }
        build()
    }

    private fun build(): Unit = screen("Wake words") {
        val words = core.settings.wakeWords()
        p("In noisy surroundings (and with strict mode on) only the primary name activates Clu. After activation you can keep talking without repeating it; say \"${core.settings.list(Settings.TERMINATION_PHRASES).first()}\" to end.", C.MUTED, 12f)
        words.forEachIndexed { i, w ->
            card {
                p((if (w.primary) "★ " else "") + w.phrase, C.TEXT, 16f)
                if (w.aliases.isNotEmpty()) p("Also accepts: ${w.aliases.joinToString()}", C.MUTED, 12f)
                check("Enabled", w.enabled) { on -> save(words.toMutableList().also { it[i] = w.copy(enabled = on) }) }
                row {
                    if (!w.primary) smallButton("Make primary") { save(words.map { it.copy(primary = it.phrase == w.phrase) }) }
                    smallButton("Rename") { prompt("Wake phrase", w.phrase) { t -> save(words.toMutableList().also { it[i] = w.copy(phrase = t.trim()) }) } }
                    smallButton("Aliases") { prompt("Alternative spellings (one per line)", w.aliases.joinToString("\n"), multiline = true) { t ->
                        save(words.toMutableList().also { it[i] = w.copy(aliases = t.lines().map { a -> a.trim() }.filter { a -> a.isNotEmpty() }) }) } }
                    smallButton("Record sample") {
                        if (!app.hasMic()) return@smallButton toast("Microphone permission needed.")
                        toast("Say \"${w.phrase}\" now…")
                        scope.launch {
                            val heard = app.voice.transcribeSample(3)
                            if (heard.isBlank()) toast("Didn't catch anything.")
                            else confirm("Heard \"$heard\"", "Add as an accepted variant of ${w.phrase}? (Used by speech-gated wake detection.)") {
                                save(words.toMutableList().also { it[i] = w.copy(aliases = (w.aliases + heard).distinct()) })
                            }
                        }
                    }
                    if (!w.primary) smallButton("Delete") { save(words.filterIndexed { j, _ -> j != i }) }
                }
            }
        }
        button("+ Add wake word") { prompt("New wake phrase") { t -> if (t.isNotBlank()) save(words + WakeWord(t.trim())) } }
        button("Test now") { if (app.hasMic()) { app.voice.start(true); toast("Listening for wake words – watch the status line in chat or the Voice screen.") } }
        button("Activation settings") { startActivity(Intent(this@WakeWordsActivity, SettingsCategoryActivity::class.java).putExtra("cat", "WAKE")) }
    }
}
