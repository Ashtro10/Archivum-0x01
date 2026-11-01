package ai.clu.desktop.voice

import ai.clu.desktop.Call
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/**
 * The window's voice API (owner token required, like everything on 127.0.0.1):
 *
 * | | |
 * |---|---|
 * | GET /v1/voice/status | engines, LiveKit server, sessions with their measured timings |
 * | POST /v1/voice/session, /v1/voice/session/stop | start a voice session for this window (CLU joins a room; the window gets a token) / end it |
 * | GET, PUT /v1/voice/settings | the voice settings (voice, speed, volume, language, listening mode, timings, wake words, farewells, pronunciations, phone access) |
 * | GET /v1/voice/voices | every voice with language, accent, presentation, character, installed or not, size, licence, source |
 * | POST /v1/voice/voices/{id}/preview | `{text?}` synthesises with the real engine and returns audio/wav |
 * | POST /v1/voice/packs/{id}/download, GET /v1/voice/downloads | `{confirm:true}` download a voice (checked, then installed; not selected) / progress |
 * | GET, POST /v1/voice/devices, POST /v1/voice/devices/{id}/remove | paired phones; pairing returns the phone's key once |
 */
class VoiceRoutes(private val voice: VoiceService) {
    fun handle(c: Call): Boolean {
        val p = c.path
        if (!p.startsWith("/v1/voice/")) return false
        val preview = Regex("^/v1/voice/voices/([a-z0-9-]+)/preview$").find(p)
        val dl = Regex("^/v1/voice/packs/([a-z0-9-]+)/download$").find(p)
        val rm = Regex("^/v1/voice/devices/([a-z0-9]+)/remove$").find(p)
        try {
            when {
                p == "/v1/voice/status" && c.method == "GET" -> c.ok(voice.status())
                p == "/v1/voice/session" && c.method == "POST" -> c.ok(runBlocking { voice.startDesktop(c.str("conversationId")) })
                p == "/v1/voice/session/stop" && c.method == "POST" -> { val room = c.str("room") ?: return true.also { c.fail(400, "room is required") }; runBlocking { voice.stop(room) }; c.ok() }
                p == "/v1/voice/settings" && c.method == "GET" -> c.ok(settingsJson())
                p == "/v1/voice/settings" && c.method == "PUT" -> updateSettings(c)
                p == "/v1/voice/voices" && c.method == "GET" -> c.ok(voices())
                preview != null && c.method == "POST" -> {
                    val wav = voice.preview(preview.groupValues[1], c.str("text"))
                    c.ex.responseHeaders.apply { set("Content-Type", "audio/wav"); set("Cache-Control", "no-store"); set("X-Content-Type-Options", "nosniff") }
                    c.ex.sendResponseHeaders(200, wav.size.toLong()); c.ex.responseBody.use { it.write(wav) }
                }
                dl != null && c.method == "POST" -> {
                    if (c.body["confirm"]?.jsonPrimitive?.booleanOrNull != true) return true.also { c.fail(400, "Downloads start only after you confirm them (confirm: true), having seen the size, source and licence.") }
                    c.send(202, downloadJson(voice.download(dl.groupValues[1], confirmed = true)))
                }
                p == "/v1/voice/downloads" && c.method == "GET" -> c.ok(buildJsonArray { voice.downloads().forEach { add(downloadJson(it)) } })
                p == "/v1/voice/devices" && c.method == "GET" -> c.ok(devices())
                p == "/v1/voice/devices" && c.method == "POST" -> {
                    val (d, key) = voice.pair(c.str("name") ?: "Phone")
                    c.send(201, buildJsonObject { put("id", d.id); put("name", d.name); put("key", key); put("room", "clu-${d.id}"); voice.phoneEndpoint()?.let { put("endpoint", it) }
                        put("note", "Enter the endpoint and this key in the CLU app on the phone (Settings → Connection). The key is shown only now.") })
                }
                rm != null && c.method == "POST" -> if (voice.unpair(rm.groupValues[1])) c.ok() else c.fail(404, "no such phone")
                else -> c.fail(404, "no such endpoint")
            }
        } catch (e: IllegalStateException) { c.fail(409, e.message ?: "Voice is not available.")
        } catch (e: IllegalArgumentException) { c.fail(400, e.message ?: "Invalid request.") }
        return true
    }

    private fun settingsJson() = buildJsonObject {
        val s = voice.settings
        put("voice", s.voice); put("speed", s.speed); put("volume", s.volume); put("language", s.language); put("mode", s.mode.name.lowercase())
        put("endSilenceMs", s.endSilenceMs); put("bargeInMs", s.bargeInMs); put("wakeWords", JsonArray(s.wakeWords.map(::JsonPrimitive))); put("wakeThreshold", s.wakeThreshold)
        put("farewells", JsonArray(s.farewells.map(::JsonPrimitive))); put("pronunciations", JsonObject(s.pronunciations.mapValues { JsonPrimitive(it.value) })); put("phoneAccess", s.phoneAccess)
        put("vocabulary", JsonArray(s.vocabulary.map(::JsonPrimitive)))
        put("whisperInstalled", voice.models.whisper != null)
        putJsonObject("supports") { put("pitch", false); put("pitchNote", "The installed voice engines (Piper, Kitten, Kokoro) have no pitch control; choose a deeper or lighter voice instead.") }
    }

    private fun updateSettings(c: Call) {
        val b = c.body; val cur = voice.settings
        fun d(k: String) = b[k]?.jsonPrimitive?.doubleOrNull
        fun i(k: String) = b[k]?.jsonPrimitive?.intOrNull
        fun st(k: String) = b[k]?.jsonPrimitive?.contentOrNull
        fun list(k: String) = (b[k] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
        b["voice"]?.jsonPrimitive?.contentOrNull?.let { id -> val v = VoiceCatalog.voice(id) ?: return c.fail(400, "no such voice"); if (!voice.models.installed(v.pack)) return c.fail(409, "That voice is not installed yet; download it first.") }
        val mode = st("mode")?.let { m -> ListenMode.entries.firstOrNull { it.name.equals(m.replace('-', '_'), true) } ?: return c.fail(400, "mode must be continuous, push_to_talk, wake_word or manual") }
        if (st("language")?.let { it != "en" } == true && voice.models.whisper == null) return c.fail(409, "Other languages need the optional multilingual speech model (Whisper base), which is not installed.")
        val next = cur.copy(voice = st("voice") ?: cur.voice, speed = d("speed") ?: cur.speed, volume = d("volume") ?: cur.volume, language = st("language") ?: cur.language,
            mode = mode ?: cur.mode, endSilenceMs = i("endSilenceMs") ?: cur.endSilenceMs, bargeInMs = i("bargeInMs") ?: cur.bargeInMs, wakeWords = list("wakeWords") ?: cur.wakeWords,
            wakeThreshold = d("wakeThreshold") ?: cur.wakeThreshold, farewells = list("farewells") ?: cur.farewells,
            pronunciations = (b["pronunciations"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: cur.pronunciations, vocabulary = list("vocabulary") ?: cur.vocabulary, phoneAccess = b["phoneAccess"]?.jsonPrimitive?.booleanOrNull ?: cur.phoneAccess)
        voice.store.put(next)
        val note = if (next.phoneAccess != cur.phoneAccess) voice.applyPhoneAccess() else null
        c.ok(buildJsonObject { settingsJson().forEach { (k, v) -> put(k, v) }; note?.let { put("note", it) } })
    }

    private fun voices() = buildJsonArray {
        val sel = voice.settings.voice
        VoiceCatalog.voices.forEach { v -> val pack = VoiceCatalog.pack(v.pack)!!
            addJsonObject {
                put("id", v.id); put("name", v.name); put("language", v.language); put("accent", v.accent); put("presentation", v.presentation); put("character", v.character)
                put("pack", pack.id); put("packTitle", pack.title); put("installed", voice.models.installed(pack.id)); put("bundled", pack.bundled); put("selected", v.id == sel)
                put("sizeBytes", pack.archiveBytes); put("license", pack.license); put("source", pack.source); pack.url?.let { put("url", it) }; put("engine", "sherpa-onnx " + pack.kind.name.lowercase())
            } }
    }

    private fun downloadJson(d: VoiceService.Download) = buildJsonObject { put("pack", d.pack); put("bytes", d.bytes); put("total", d.total); put("state", d.state); put("message", d.message) }
    private fun devices() = buildJsonObject {
        putJsonArray("devices") { voice.devices().forEach { d -> addJsonObject { put("id", d.id); put("name", d.name); put("createdAt", d.createdAt); put("lastSeen", d.lastSeen); put("room", "clu-${d.id}") } } }
        voice.phoneEndpoint()?.let { put("endpoint", it) }; put("phoneAccess", voice.settings.phoneAccess)
    }
}
