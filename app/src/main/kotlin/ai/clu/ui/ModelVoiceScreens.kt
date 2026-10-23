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
        super.onResume(); build()
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
        confirm("Download ${fmtBytes(c.approxBytes)}?", "${c.name}\n\nThis uses ${fmtBytes(c.approxBytes)} of data and storage. Use Wi-Fi if your data plan is limited.", "Download") {
            val name = c.url.substringAfterLast('/')
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
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        val dlg = android.app.AlertDialog.Builder(this).setTitle("Copying model…").setView(bar).setCancelable(false).show()
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val total = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { o ->
                        val buf = ByteArray(1 shl 20); var done = 0L
                        while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n
                            if (total > 0) launch(Dispatchers.Main) { bar.progress = (done * 100 / total).toInt() } }
                    } }
                    core.models.register(dest)
                }
            }
            dlg.dismiss()
            r.onSuccess { e -> if (core.settings.string(Settings.MODEL_ACTIVE).isEmpty()) core.settings.set(Settings.MODEL_ACTIVE, e.id); toast("Imported ${e.name}"); build() }
                .onFailure { dest.delete(); toast(it.message ?: "Import failed") }
        }
    }
}

private fun AlertDialog(a: android.app.Activity, title: String, msg: String) =
    android.app.AlertDialog.Builder(a).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()

class VoiceActivity : BaseActivity() {
    override fun onResume() { super.onResume(); build() }

    private fun build(): Unit = screen("Voice") {
        val v = app.voice.state.value
        p("Offline speech recognition, wake-word and voice-activity models are built in. The Clu voice and speaker recognition are optional downloads.", C.MUTED, 12f)
        h1("Voice packs")
        VoicePacks.all.forEach { pack -> card { packCard(pack) } }
        h1("Test")
        row {
            smallButton("Speak test phrase") {
                app.speech.speak("I am ${core.settings.string(Settings.CLU_NAME)}. Systems are nominal, and I am listening.", ai.clu.core.voice.VoiceConfig.from(core.settings))
                toast("Engine: " + app.speech.backend(ai.clu.core.voice.VoiceConfig.from(core.settings)).id)
            }
            smallButton("Stop") { app.speech.stop() }
        }
        row {
            smallButton(if (v.running) "Stop listening" else "Start conversation") { if (v.running) app.voice.stop() else if (app.hasMic()) app.voice.start(false) else toast("Grant microphone permission first (mic button in chat).") }
            smallButton("Test wake word") { if (app.hasMic()) { app.voice.start(true); toast("Say \"${core.settings.wakeWords().first { it.primary }.phrase}\".") } }
        }
        val live = p("", C.TEXT, 13f)
        scope.launch { app.voice.state.collect { s -> live.text = "State: ${s.turn.name.lowercase()} · noise ${"%.1f".format(s.noiseDb)} dBFS (${s.environment.name.lowercase()})\n" +
            "Heard: ${s.lastHeard}\nPartial: ${s.partial}\nSpeaker: ${s.speaker}\n${s.info}${if (s.error.isNotBlank()) "\n⚠ " + s.error else ""}" +
            (if (s.lastLatency.isNotEmpty()) "\nLast latency: " + s.lastLatency.entries.joinToString { "${it.key}=${"%.0f".format(it.value)}" } else "") } }
        h1("Android voices (for Android TTS)")
        val voices = runCatching { app.speech.androidVoices() }.getOrDefault(emptyList())
        if (voices.isEmpty()) p("No Android TTS voices reported yet (install/enable a TTS engine in system settings).")
        else smallButton("Choose Android voice (${voices.size})") { choose("Voice", voices) { i -> core.settings.set(Settings.ANDROID_VOICE, voices[i]); toast("Selected ${voices[i]}") } }
        button("Voice settings") { startActivity(Intent(this@VoiceActivity, SettingsCategoryActivity::class.java).putExtra("cat", "VOICE")) }
        button("Wake words") { startActivity(Intent(this@VoiceActivity, WakeWordsActivity::class.java)) }
    }

    private fun LinearLayout.packCard(pack: VoicePack) {
        val installed = app.packs.isInstalled(pack)
        p(pack.title, C.TEXT, 15f)
        p(if (installed) "Installed · ${fmtBytes(app.packs.sizeOnDisk(pack))}" else "Not installed · download ${fmtBytes(pack.bytes)}", if (installed) C.OK else C.MUTED, 12f)
        val prog = p("", C.MUTED, 12f)
        row {
            if (!installed) smallButton("Download ${fmtBytes(pack.bytes)}") {
                core.network.check(ai.clu.core.tools.NetCategory.DOWNLOADS)?.let { toast(it); return@smallButton }
                confirm("Download ${fmtBytes(pack.bytes)}?", "${pack.title}\nFrom the sherpa-onnx releases on GitHub. Verified by SHA-256 before use.", "Download") {
                    scope.launch {
                        val r = app.packs.download(pack) { d, t -> launch(Dispatchers.Main) { prog.text = "${fmtBytes(d)} / ${fmtBytes(t)}" } }
                        r.onSuccess { app.sherpa.releaseAll(); toast("${pack.title} installed."); build() }.onFailure { toast("Download failed: ${it.message}") }
                    }
                }
            }
            smallButton("Import file…") { pendingImport = pack; startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 7) }
            if (installed) smallButton("Remove") { confirm("Remove ${pack.title}?", "") { app.sherpa.releaseAll(); app.packs.remove(pack); build() } }
        }
        p("Offline install: download ${pack.url.substringAfterLast('/')} on any device and import it here.", C.MUTED, 11f)
    }

    private var pendingImport: VoicePack? = null

    @Deprecated("framework API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        val pack = pendingImport ?: return
        if (resultCode != RESULT_OK) return
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
