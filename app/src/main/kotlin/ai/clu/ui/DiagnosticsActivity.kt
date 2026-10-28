package ai.clu.ui

import ai.clu.core.config.Settings
import ai.clu.core.diag.Level
import ai.clu.core.llm.ChatMessage
import ai.clu.core.llm.GenParams
import ai.clu.core.llm.LlamaBridge
import ai.clu.core.memory.*
import ai.clu.platform.DeviceInfo
import android.content.Intent
import android.os.Debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Developer diagnostics: resources, performance, model, voice, memory, sync, logs, feature tests, config recovery. */
class DiagnosticsActivity : BaseActivity() {
    private var level = Level.INFO
    override fun onResume() { super.onResume(); build() }

    private fun build(): Unit = screen("Diagnostics") {
        h1("Device & resources")
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        p(DeviceInfo.summary(this@DiagnosticsActivity), C.TEXT, 13f)
        p("Clu process: PSS ${mi.totalPss / 1024} MB (native ${mi.nativePss / 1024} MB) · Java heap ${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576} MB · " +
            "uptime ${(System.currentTimeMillis() - app.startedAt) / 1000} s", C.MUTED, 12f)
        val storage = listOf(filesDir, getExternalFilesDir(null)).filterNotNull().sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        p("Storage used by Clu: ${fmtBytes(storage)} · database ${fmtBytes(getDatabasePath("clu.db").length())}", C.MUTED, 12f)
        h1("Model")
        val s = core.llm.state.value
        p("${s.status} ${s.modelName ?: ""} ${s.message}\n${runCatching { LlamaBridge.nativeSystemInfo() }.getOrDefault("native library not loaded")}", C.MUTED, 12f)
        h1("Requests (latest first)")
        p("Lifecycle of each message: received → created → model ready → generation started → first token → completed / cancelled / " +
            "timeout / error. Times are from the moment the message was received. No message content is recorded. Also in logcat (tag CluTurn).", C.MUTED, 12f)
        val reqs = core.agent.trace.recent()
        if (reqs.isEmpty()) p("No requests yet.", C.MUTED, 12f)
        reqs.take(15).forEach { r ->
            p(r.summary() + "\n" + r.events.joinToString("  ") { "${it.name.lowercase()} +${it.atMs - r.startedAt}ms" }, C.MUTED, 12f)
        }
        h1("Performance (measured on this device)")
        val perf = core.diag.perfSummary()
        p("Timings in milliseconds unless stated. \"Not measured yet\" means the action hasn't happened since the data was cleared.", C.MUTED, 12f)
        ai.clu.core.diag.PerfReport.rows(perf).forEach { (k, v) -> p("$k: $v", if (v == "not measured yet") C.MUTED else C.TEXT, 12f) }
        p("Battery: not measured by Clu — see Android Settings → Battery.", C.MUTED, 12f)
        row {
            smallButton("Share performance report") { shareReport() }
            smallButton("Clear measurements") { confirm("Clear all measurements?", "Timings start again from zero.") { core.diag.clearPerf(); build() } }
        }
        h1("Voice")
        val v = app.voice.state.value
        p("Running: ${v.running} · turn ${v.turn} · noise ${"%.1f".format(v.noiseDb)} dBFS (${v.environment}) · ${v.info}${if (v.error.isNotBlank()) " · ⚠ ${v.error}" else ""}\n" +
            app.speech.status.value.let { t -> "TTS engine: ${ai.clu.core.voice.TtsSelection.label(t.plan.engine)} · last spoke: ${t.lastSpoke.ifEmpty { "-" }}" +
                (t.plan.problem ?: t.lastError)?.let { " · ⚠ $it" }.orEmpty() }, C.MUTED, 12f)
        h1("Memory")
        p("Memories: ${core.memories.count()} · pending suggestions: ${core.proposals.pending().size} · messages: ${core.conversations.countMessages()} · " +
            "diary entries: ${core.diary.count()}", C.MUTED, 12f)
        h1("Sync")
        p("Device ${core.store.deviceId.take(8)} · entity ${core.store.entityId.take(8)} · peers ${core.syncEngine.peers().size} · change log seq ${core.syncEngine.maxSeq()}", C.MUTED, 12f)
        h1("Feature tests")
        button("Run self-tests on this device") { runSelfTests() }
        h1("Configuration")
        row {
            smallButton("Export settings") { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "clu-settings.json"), 1) }
            smallButton("Import settings") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 2) }
        }
        core.settings.snapshots().take(10).forEach { snap ->
            row {
                addView(android.widget.TextView(context).apply { text = "${fmtTime(snap.createdAt)} · ${snap.label}${if (snap.knownGood) " ★" else ""}"; setTextColor(C.MUTED); textSize = 12f },
                    android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                smallButton("Restore") { verifyOwner("Restore configuration") { if (it) { toast(core.settings.restore(snap.id, true).joinToString().ifEmpty { "Restored." }); build() } } }
                if (!snap.knownGood) smallButton("★") { core.settings.markKnownGood(snap.id); build() }
            }
        }
        row {
            smallButton("Snapshot now") { core.settings.snapshot("Manual snapshot"); build() }
            smallButton("Reset all settings") { verifyOwner("Reset all settings") { ok -> if (ok) { core.settings.snapshot("Before reset"); ai.clu.core.config.Settings.all.forEach { core.settings.reset(it.key, true) }; build() } } }
        }
        h1("Logs")
        row {
            Level.values().forEach { l -> smallButton(l.name.lowercase()) { level = l; build() } }
            smallButton("Clear") { core.diag.clear(); build() }
        }
        core.diag.recent(300, level).forEach { e -> p("${fmtTime(e.ts)} ${e.level.name.first()} ${e.tag}: ${e.message}", if (e.level >= Level.WARN) C.WARN else C.MUTED, 11f) }
    }

    /** Text report for the device acceptance checklist: versions, device, conditions, timings with units. No private data. */
    private fun shareReport() {
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).let { "${it.versionName} (versionCode ${it.longVersionCode})" } }.getOrDefault("?")
        val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
        val conditions = linkedMapOf(
            "online" to if (app.platform.isOnline()) "yes" else "no",
            "engine" to if (core.settings.string(Settings.VOICE_MODE) == "livekit") "Clu server" else "on-device",
            "model loaded" to core.llm.state.value.status.let { st -> if (st == ai.clu.core.llm.LlmStatus.READY || st == ai.clu.core.llm.LlmStatus.GENERATING) "yes" else st.name.lowercase() },
            "uptime" to "${(System.currentTimeMillis() - app.startedAt) / 1000} s")
        core.llm.loadedSummary()?.let { conditions.putAll(it) }
        val memory = "PSS ${mi.totalPss / 1024} MB (native ${mi.nativePss / 1024} MB), Java heap ${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576} MB"
        val report = ai.clu.core.diag.PerfReport.build(version, device, conditions, memory, core.diag.perfSummary(), System.currentTimeMillis(),
            core.diag.perfDetails(ai.clu.core.diag.PerfReport.VOICE_TIMELINE, 15))
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Clu performance report", report))
        runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
            .putExtra(Intent.EXTRA_SUBJECT, "Clu performance report"), "Share performance report")) }
        toast("Report copied to the clipboard.")
    }

    private fun runSelfTests() = scope.launch {
        val results = mutableListOf<String>()
        suspend fun t(name: String, block: suspend () -> String) {
            val t0 = System.currentTimeMillis()
            results += try { "✓ $name: ${block()} (${System.currentTimeMillis() - t0} ms)" } catch (e: Throwable) { "✗ $name: ${e.message ?: e::class.simpleName}" }
        }
        withContext(Dispatchers.IO) {
            t("Database write/read") {
                val m = core.memories.save(Memory(category = MemoryCategory.TEMPORARY, title = "self-test", content = "Clu self test marker zebra-42", source = MemorySource.OWNER_INSTRUCTION))
                val found = core.memories.rank("zebra-42 self test marker").any { it.memory.id == m.id }
                core.memories.delete(m.id)
                check(found) { "inserted memory was not retrieved" }; "memory inserted, retrieved and deleted"
            }
            t("VAD model") { app.sherpa.vad(0.5f, 0.5f).also { it.release() }; "loaded" }
            t("Wake-word model") { app.sherpa.kws(core.settings.double(Settings.KWS_THRESHOLD).toFloat()); "loaded" }
            t("Speech recognition model") { val a = app.sherpa.asr(); val s = a.createStream(); s.acceptWaveform(FloatArray(16000), 16000); s.inputFinished(); while (a.isReady(s)) a.decode(s); s.release(); "loaded and decoded 1 s" }
            t("Speech synthesis") { val cfg = ai.clu.core.voice.VoiceConfig.from(core.settings); app.speech.refresh(cfg); val b = app.speech.backendFor(cfg, "en") ?: error("no English voice"); val pcm = b.synthesize("Test.", cfg); "${b.label}: ${pcm.samples.size} samples @ ${pcm.rate} Hz" }
            t("Speaker model") { if (app.sherpa.speaker() == null) "not installed (optional)" else "loaded" }
            t("Language model") {
                val e = core.llm.engine() ?: error(core.llm.state.value.message)
                val r = e.generate(listOf(ChatMessage("user", "Say OK.")), GenParams(maxTokens = 8, temperature = 0f)) {}
                "${r.stats.generatedTokens} tokens, first token ${"%.0f".format(r.stats.firstTokenMs)} ms, ${"%.1f".format(r.stats.tokensPerSecond)} tok/s"
            }
            t("Network") { if (app.platform.isOnline()) "online" else "offline (local features still work)" }
            t("Sync server") { if (app.sync.state.value.serving) "listening on ${app.sync.state.value.port}" else "not listening (no paired device and pairing closed, or turned off in settings)" }
        }
        core.diag.i("selftest", results.joinToString(" | "))
        android.app.AlertDialog.Builder(this@DiagnosticsActivity).setTitle("Self-tests").setMessage(results.joinToString("\n\n")).setPositiveButton("OK", null).show()
    }

    @Deprecated("framework API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            1 -> contentResolver.openOutputStream(uri)!!.use { it.write(core.settings.exportJson().toByteArray()) }.also { toast("Exported.") }
            2 -> verifyOwner("Import settings") { ok ->
                if (ok) { val txt = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    core.settings.snapshot("Before import"); val p = core.settings.importJson(txt, verified = true); toast(if (p.isEmpty()) "Imported." else p.joinToString("\n")); build() }
            }
        }
    }
}
