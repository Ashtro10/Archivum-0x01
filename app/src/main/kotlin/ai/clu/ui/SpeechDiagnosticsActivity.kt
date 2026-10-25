package ai.clu.ui

import ai.clu.core.config.Settings
import ai.clu.core.voice.AsrRouting
import ai.clu.voice.SAMPLE_RATE
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Speech recognition diagnostics: what Clu heard, with which recogniser and language, how long the endpoint and
 * recognition took, and the audio levels of each utterance. The last utterance can be played back to check for cut-off
 * words or a bad microphone route. Everything stays in memory on this device; nothing is uploaded or saved.
 */
class SpeechDiagnosticsActivity : BaseActivity() {
    override fun onResume() { super.onResume(); build() }

    private fun build(): Unit = screen("Speech recognition") {
        val cfg = ai.clu.core.voice.VoiceConfig.from(core.settings)
        val whisper = app.whisper.model(cfg.whisperModel)?.takeIf { app.whisper.isInstalled(it) }
        val route = AsrRouting.plan(cfg.sttLanguage, whisper != null)
        card {
            p("Spoken language: ${cfg.sttLanguage} → final transcript by ${route.engineLabel}", C.TEXT, 14f)
            p("Live English recogniser: ${app.sherpa.asrModelName}", C.MUTED, 12f)
            p("Whisper: ${whisper?.fileName ?: "not installed"}", C.MUTED, 12f)
            route.note?.let { p("⚠ $it", C.WARN, 12f) }
            p("Microphone: 16 kHz mono, ${cfg.audioSource} source · ${app.voice.state.value.info.ifBlank { "session not started" }}", C.MUTED, 12f)
            p("Turn ends after ${cfg.minSilenceSec * 1000} ms of silence (about twice that after \"and\", \"the\", …). Pre-roll 1.0 s.", C.MUTED, 12f)
        }
        row {
            smallButton("Play what Clu heard") {
                val a = app.voice.asrLog.lastAudio ?: run { toast("Nothing recorded yet – talk to Clu first."); return@smallButton }
                play(a)
            }
            smallButton("Clear") { app.voice.asrLog.clear(); build() }
            smallButton("Refresh") { build() }
        }
        p("Regression check: start a conversation and say \"Apple\" a few times, then compare the lines below.", C.MUTED, 12f)
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val records = app.voice.asrLog.all()
        if (records.isEmpty()) p("No utterances yet in this session.")
        records.forEach { r ->
            card {
                p("\"${r.text.ifBlank { "(nothing recognised)" }}\"", if (r.text.isBlank()) C.WARN else C.TEXT, 15f)
                p("${fmt.format(Date(r.at))} · ${r.engine} · language ${r.language}", C.MUTED, 12f)
                p("speech ${r.speechMs} ms · pre-roll ${r.preRollMs} ms · end-of-speech wait ${r.endSilenceMs} ms · recognition ${r.asrMs} ms · " +
                    "total after you stopped ${r.endSilenceMs + r.asrMs} ms", C.MUTED, 12f)
                val levelNote = when {
                    r.clippedPct > 0.5 -> "  ⚠ clipping – speak further from the microphone"
                    r.peakDbfs < -35 -> "  ⚠ very quiet – check the microphone route"
                    else -> ""
                }
                p("level peak ${"%.0f".format(r.peakDbfs)} dBFS · rms ${"%.0f".format(r.rmsDbfs)} dBFS · clipped ${"%.2f".format(r.clippedPct)}% · ${r.capture}$levelNote",
                    if (levelNote.isNotEmpty()) C.WARN else C.MUTED, 12f)
                if (r.note.isNotBlank()) p(r.note, C.WARN, 12f)
            }
        }
    }

    private fun play(a: FloatArray) {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(a.size * 4).setTransferMode(AudioTrack.MODE_STATIC).build()
        t.write(a, 0, a.size, AudioTrack.WRITE_BLOCKING)
        t.setNotificationMarkerPosition(a.size)
        t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack) { track.release() }
            override fun onPeriodicNotification(track: AudioTrack) {}
        })
        t.play()
        toast("Playing ${"%.1f".format(a.size / SAMPLE_RATE.toFloat())} s")
    }
}
