package ai.clu.core.config

import ai.clu.core.config.SettingCategory.*
import ai.clu.core.config.SettingScope.DEVICE
import ai.clu.core.voice.WakeWord
import ai.clu.core.util.CluJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement

/**
 * The complete settings registry. Each entry is wired to a consuming component; see `verifiedBy` for the test that
 * demonstrates the behaviour change. docs/SETTINGS_REGISTER.md is generated from this list.
 */
object Settings {
    private val defs = LinkedHashMap<String, SettingDef>()
    val all: List<SettingDef> get() = defs.values.toList()
    operator fun get(key: String): SettingDef? = defs[key]

    private fun def(d: SettingDef): String { require(d.key !in defs) { "duplicate ${d.key}" }; defs[d.key] = d; return d.key }

    private fun bool(key: String, title: String, cat: SettingCategory, default: Boolean, desc: String, component: String, effect: String,
                     test: String, scope: SettingScope = SettingScope.SHARED, apply: ApplyMode = ApplyMode.IMMEDIATE,
                     sensitive: Boolean = false, limits: String = "", deviceOnly: Boolean = false, advanced: Boolean = false) =
        def(SettingDef(key, title, cat, SettingType.Bool, jsonOf(default), desc, component, effect, scope, apply, sensitive, test, limits, deviceOnly, advanced))

    private fun int(key: String, title: String, cat: SettingCategory, default: Int, min: Int, max: Int, desc: String, component: String,
                    effect: String, test: String, scope: SettingScope = SettingScope.SHARED, apply: ApplyMode = ApplyMode.IMMEDIATE,
                    limits: String = "", deviceOnly: Boolean = false, advanced: Boolean = false) =
        def(SettingDef(key, title, cat, SettingType.IntRange(min, max), jsonOf(default), desc, component, effect, scope, apply, false, test, limits, deviceOnly, advanced))

    private fun float(key: String, title: String, cat: SettingCategory, default: Double, min: Double, max: Double, desc: String,
                      component: String, effect: String, test: String, scope: SettingScope = SettingScope.SHARED,
                      apply: ApplyMode = ApplyMode.IMMEDIATE, limits: String = "", deviceOnly: Boolean = false, advanced: Boolean = false) =
        def(SettingDef(key, title, cat, SettingType.FloatRange(min, max), jsonOf(default), desc, component, effect, scope, apply, false, test, limits, deviceOnly, advanced))

    private fun choice(key: String, title: String, cat: SettingCategory, default: String, options: List<Pair<String, String>>, desc: String,
                       component: String, effect: String, test: String, scope: SettingScope = SettingScope.SHARED,
                       apply: ApplyMode = ApplyMode.IMMEDIATE, sensitive: Boolean = false, limits: String = "",
                       deviceOnly: Boolean = false, advanced: Boolean = false) =
        def(SettingDef(key, title, cat, SettingType.Choice(options), jsonOf(default), desc, component, effect, scope, apply, sensitive, test, limits, deviceOnly, advanced))

    private fun text(key: String, title: String, cat: SettingCategory, default: String, desc: String, component: String, effect: String,
                     test: String, type: SettingType.Text = SettingType.Text(), scope: SettingScope = SettingScope.SHARED,
                     apply: ApplyMode = ApplyMode.IMMEDIATE, sensitive: Boolean = false, limits: String = "", advanced: Boolean = false,
                     deviceOnly: Boolean = false) =
        def(SettingDef(key, title, cat, type, jsonOf(default), desc, component, effect, scope, apply, sensitive, test, limits, deviceOnly, advanced))

    private fun list(key: String, title: String, cat: SettingCategory, default: List<String>, desc: String, component: String,
                     effect: String, test: String, type: SettingType.TextList = SettingType.TextList(), limits: String = "") =
        def(SettingDef(key, title, cat, type, jsonOf(default), desc, component, effect, SettingScope.SHARED, ApplyMode.IMMEDIATE, false, test, limits))

    private fun time(key: String, title: String, cat: SettingCategory, default: String, desc: String, component: String, effect: String, test: String) =
        def(SettingDef(key, title, cat, SettingType.TimeOfDay, jsonOf(default), desc, component, effect, SettingScope.SHARED, ApplyMode.IMMEDIATE, false, test))

    // ---------------------------------------------------------------- LiveKit voice (optional)
    val VOICE_MODE = choice("voice.mode", "Conversation engine", LIVEKIT, "offline",
        listOf("offline" to "On this device: local model, speech recognition and voice (no network)",
            "livekit" to "Clu server (LiveKit): speech recognition, reasoning and voice on Clu's agent server"),
        "Which engine answers voice and text. The Clu server gives the best understanding and the fastest, most natural replies; " +
            "the device engine works without any network.", "Engine, LiveKitSession, LiveConversation",
        "Voice and chat use the selected engine", "LiveKitSettingsTest#mode", scope = DEVICE,
        limits = "The Clu server needs a LiveKit server (Cloud or self-hosted), the token endpoint and the Clu agent (docs/LIVEKIT_AGENT.md).")
    val LK_TOKEN_URL = text("livekit.tokenEndpoint", "Token endpoint", LIVEKIT, "",
        "URL of your token server. Clu asks it for a short-lived room token; no LiveKit API key or secret is stored in the app.",
        "TokenProvisioner", "Token requests go to this URL", "LiveKitSettingsTest#tokenEndpoint",
        SettingType.Text(300, pattern = Regex("https?://\\S+")), sensitive = true,
        limits = "https:// required, except http:// to a private-network address when allowed below.")
    val LK_AGENT_NAME = text("livekit.agentName", "Agent name", LIVEKIT, "clu",
        "Name of the agent your server dispatches to Clu's room (clu_agent.py).", "TokenProvisioner",
        "Sent in the token request (room_config.agents)", "LiveKitSettingsTest#agentName",
        SettingType.Text(64, allowEmpty = false, pattern = Regex("[A-Za-z0-9_.-]+")))
    val LK_LAN_CLEARTEXT = bool("livekit.allowLanCleartext", "Allow unencrypted LAN server", LIVEKIT, true,
        "Allow http:// / ws:// only to private-network addresses (home Wi-Fi, phone hotspot). Internet servers always need TLS.",
        "EndpointPolicy", "ws:// and http:// accepted for private addresses only", "LiveKitSettingsTest#lanCleartext", sensitive = true,
        limits = "Audio is always encrypted by WebRTC (DTLS-SRTP); this only concerns the signalling connection and token request.")

    // ---------------------------------------------------------------- General & personality
    val CLU_NAME = text("identity.cluName", "Clu's name", GENERAL, "Clu", "The name Clu uses for itself.", "PromptBuilder, UI",
        "System prompt identity line and screen titles use this name", "PersonaSettingsTest#cluName",
        SettingType.Text(40, allowEmpty = false), sensitive = true)
    val OWNER_NAME = text("identity.ownerName", "Owner's name", GENERAL, "", "Your name, as Clu should know it.", "PromptBuilder",
        "System prompt states who the owner is", "PersonaSettingsTest#ownerName", SettingType.Text(60))
    val HOME_LOCATION = text("identity.homeLocation", "Home location", GENERAL, "", "Your city (or \"latitude, longitude\"), for questions like " +
        "\"is a cyclone heading towards us?\". Resolved on this device and never sent to a search service; for the weather, only the city's " +
        "approximate coordinates go to the forecast service.", "CluAgent, Places",
        "Hazard and weather answers measure distance from it; unknown → Clu asks", "HomeLocationTest#homeLocation", SettingType.Text(80))
    val OWNER_ADDRESS = text("identity.ownerAddress", "Form of address", GENERAL, "", "How Clu addresses you (e.g. \"sir\", your first name). Empty = your name.",
        "PromptBuilder, ResponseTemplates", "Prompt instruction and spoken templates ({owner}) use it", "PersonaSettingsTest#ownerAddress", SettingType.Text(40))
    val LANGUAGE = choice("persona.language", "Response language", GENERAL, "en",
        listOf("en" to "English", "hi" to "Hindi", "hinglish" to "Hinglish (Hindi-English mix)"),
        "Language Clu answers in.", "PromptBuilder", "Adds a language instruction to the system prompt", "PersonaSettingsTest#language",
        limits = "Offline speech recognition model bundled for download is English; Hindi affects text replies only.")
    val PRONUNCIATIONS = list("persona.pronunciations", "Pronunciation fixes", GENERAL, emptyList(),
        "Entries like \"Ashutosh=Aa-shu-tosh\". Applied to text before speech synthesis.", "SpeechTextPreprocessor",
        "Matching words are replaced in the text sent to the TTS engine", "PersonaSettingsTest#pronunciations",
        SettingType.TextList(50, 120))
    val RESPONSE_LENGTH = choice("persona.responseLength", "Response length", GENERAL, "balanced",
        listOf("brief" to "Brief", "balanced" to "Balanced", "detailed" to "Detailed"), "Default length of answers.",
        "PromptBuilder, GenerationParams", "Changes the length instruction and the token budget", "PersonaSettingsTest#responseLength", apply = ApplyMode.NEXT_TURN)
    val FORMALITY = choice("persona.formality", "Formality", GENERAL, "neutral",
        listOf("casual" to "Casual", "neutral" to "Neutral", "formal" to "Formal"), "Register of speech.", "PromptBuilder",
        "Adds a formality instruction", "PersonaSettingsTest#formality", apply = ApplyMode.NEXT_TURN)
    val HUMOR = choice("persona.humor", "Humour", GENERAL, "dry",
        listOf("none" to "None", "dry" to "Dry, occasional", "frequent" to "Frequent"), "How much humour Clu uses.", "PromptBuilder",
        "Adds a humour instruction", "PersonaSettingsTest#humor", apply = ApplyMode.NEXT_TURN)
    val SARCASM = choice("persona.sarcasm", "Sarcasm", GENERAL, "light",
        listOf("off" to "Off", "light" to "Light", "sharp" to "Sharp"), "Sarcasm level.", "PromptBuilder, RefusalTemplates",
        "Adds a sarcasm instruction; selects refusal line style", "PersonaSettingsTest#sarcasm", apply = ApplyMode.NEXT_TURN)
    val DIRECTNESS = choice("persona.directness", "Directness", GENERAL, "direct",
        listOf("diplomatic" to "Diplomatic", "direct" to "Direct", "blunt" to "Blunt"), "How directly Clu delivers judgements.",
        "PromptBuilder", "Adds a directness instruction", "PersonaSettingsTest#directness", apply = ApplyMode.NEXT_TURN)
    val EXPLANATION = choice("persona.explanation", "Explanations", GENERAL, "balanced",
        listOf("minimal" to "Conclusions only", "balanced" to "Brief reasoning", "thorough" to "Step-by-step reasoning"),
        "How much of the reasoning behind an answer Clu explains.", "PromptBuilder", "Adds an explanation-depth instruction",
        "PersonaSettingsTest#explanation", apply = ApplyMode.NEXT_TURN)
    val PROACTIVITY = choice("persona.proactivity", "Unprompted observations", GENERAL, "occasional",
        listOf("off" to "Never – answer only what I ask", "occasional" to "When clearly useful", "active" to "Point out risks and better options freely"),
        "Whether Clu adds observations you didn't ask for (a risk, a better option, a related point).", "PromptBuilder",
        "Adds an instruction about unrequested observations", "PersonaSettingsTest#proactivity", apply = ApplyMode.NEXT_TURN)
    val GREET_ON_START = bool("persona.greetOnSessionStart", "Greet when activated", GENERAL, true,
        "Speak a greeting when a voice session starts.", "VoiceSession, ResponseTemplates", "A greeting line is spoken/shown on activation",
        "PersonaSettingsTest#greetOnStart")
    val GREETINGS = list("persona.greetings", "Greeting lines", GENERAL,
        listOf("{greeting}, {owner}.", "I'm listening, {owner}.", "Yes, {owner}?"),
        "Templates. {owner} = form of address, {greeting} = good morning/afternoon/evening.", "ResponseTemplates",
        "One line is chosen for activation greetings", "PersonaSettingsTest#greetings", SettingType.TextList(20, 160, minItems = 1))
    val CUSTOM_INSTRUCTIONS = text("persona.customInstructions", "Custom instructions", GENERAL, "",
        "Free-form behavioural instructions added to Clu's system prompt.", "PromptBuilder", "Included verbatim in the system prompt",
        "PersonaSettingsTest#customInstructions", SettingType.Text(2000, multiline = true), apply = ApplyMode.NEXT_TURN)
    val QUIET_ENABLED = bool("quiet.enabled", "Quiet hours", GENERAL, true, "Suppress proactive messages and (optionally) speech during quiet hours.",
        "QuietHours, ProactivePlanner, SpeechOutput", "Proactive messages are not issued inside the window", "PersonaSettingsTest#quietHours")
    val QUIET_START = time("quiet.start", "Quiet hours start", GENERAL, "22:30", "Start of quiet hours.", "QuietHours",
        "Defines the quiet window", "PersonaSettingsTest#quietHours")
    val QUIET_END = time("quiet.end", "Quiet hours end", GENERAL, "07:00", "End of quiet hours.", "QuietHours",
        "Defines the quiet window", "PersonaSettingsTest#quietHours")
    val QUIET_MUTE_SPEECH = bool("quiet.muteSpeech", "Mute speech in quiet hours", GENERAL, false,
        "Reply in text only (no spoken audio) during quiet hours.", "SpeechOutput", "TTS is skipped inside the quiet window", "PersonaSettingsTest#quietMuteSpeech")

    // ---------------------------------------------------------------- Voice
    val TTS_ENGINE = choice("voice.ttsEngine", "TTS engine", VOICE, "piper",
        listOf("piper" to "Piper – on-device (English + Hindi voices)", "kokoro" to "Kokoro – on-device, voice Lewis (English)", "kitten" to "KittenTTS nano – on-device (English only)",
            "hayai" to "HayaiTTS – installed app (Android TTS service)", "system" to "Other Android TTS engine"),
        "Which engine synthesises Clu's voice. The selection is used as-is: if the engine is not installed, Clu says so and stays silent instead of switching engines.",
        "SpeechOutput, TtsSelection", "Selects the TTS backend used for every spoken phrase", "VoiceSettingsTest#ttsEngine",
        limits = "Piper and KittenTTS need their voice files (downloaded on the Voice screen). HayaiTTS and other engines are separate apps; " +
            "they synthesise each phrase completely before returning it (phrase-level streaming only).", deviceOnly = true)
    val TTS_PIPER_VOICE = choice("voice.piperVoice", "Piper English voice", VOICE, ai.clu.core.voice.TtsSelection.PIPER_DEFAULT, ai.clu.core.voice.TtsSelection.PIPER_VOICES,
        "English voice for the Piper engine (median pitch measured on representative Clu replies).", "SpeechOutput",
        "Selects the Piper model file", "VoiceSettingsTest#piperVoice", deviceOnly = true, limits = "Each voice is a separate download.")
    val TTS_SPEAKER = int("voice.sherpaSpeaker", "Piper speaker id", VOICE, 0, 0, 1000, "Speaker index for multi-speaker Piper voices.",
        "SpeechOutput", "Passed as the speaker id to the Piper synthesiser", "VoiceSettingsTest#speaker", deviceOnly = true,
        limits = "Single-speaker voices ignore values other than 0.")
    val TTS_KITTEN_SPEAKER = choice("voice.kittenSpeaker", "KittenTTS voice", VOICE, "2",
        listOf("2" to "expr-voice-3-m – deepest (~116 Hz measured)", "4" to "expr-voice-4-m (~167 Hz)", "0" to "expr-voice-2-m (~190 Hz)",
            "6" to "expr-voice-5-m (~190 Hz)"),
        "Speaker of the KittenTTS model. Only the male-labelled speakers are offered; only expr-voice-3-m measures in the deep male range.",
        "SpeechOutput", "Kitten speaker id", "VoiceSettingsTest#kittenSpeaker", deviceOnly = true)
    val TTS_HINDI_VOICE = choice("voice.hindiVoice", "Hindi voice", VOICE, "pratham",
        listOf("pratham" to "Pratham – Piper hi_IN, deep male (~107 Hz measured)", "engine" to "The selected engine's Hindi voice (HayaiTTS / Android engines)",
            "off" to "Do not speak Hindi text"),
        "Voice used for Devanagari (Hindi) phrases. English and Hindi are different models, so they sound like different speakers.",
        "SpeechOutput, TtsSelection", "Routes Devanagari phrases", "VoiceSettingsTest#hindiVoice", deviceOnly = true,
        limits = "Pratham's training data is licensed CC BY-NC-SA 4.0 (non-commercial). Romanized Hindi is spoken by the English voice and is hard to understand.")
    val TTS_SYSTEM_ENGINE = text("voice.systemEngine", "Android TTS engine package", VOICE, "",
        "Package of the Android TTS engine for 'Other Android TTS engine' (empty = the device default engine).", "SpeechOutput",
        "TextToSpeech engine package", "VoiceSettingsTest#systemEngine", deviceOnly = true)
    val ANDROID_VOICE = text("voice.androidVoice", "Android TTS English voice", VOICE, "", "Voice name in HayaiTTS / the Android engine for English. Empty = the engine's choice (Voice screen → Find deepest voice).",
        "SpeechOutput", "Selects TextToSpeech voice", "VoiceSettingsTest#androidVoice", deviceOnly = true)
    val ANDROID_VOICE_HI = text("voice.androidVoiceHi", "Android TTS Hindi voice", VOICE, "", "Voice name in HayaiTTS / the Android engine for Hindi (used when Hindi voice = the selected engine).",
        "SpeechOutput", "Selects TextToSpeech voice for Devanagari phrases", "VoiceSettingsTest#androidVoiceHi", deviceOnly = true)
    val SPEED = float("voice.speed", "Speaking speed", VOICE, 0.92, 0.5, 2.0, "1.0 = normal.", "SpeechOutput",
        "Synthesis speed (sherpa) / speech rate (Android)", "VoiceSettingsTest#speed", deviceOnly = true)
    val PITCH = float("voice.pitch", "Pitch adjustment", VOICE, 1.0, 0.8, 1.2, "1.0 = the voice's natural pitch (recommended: choose a naturally deep voice). Small changes only; applied at playback to on-device engines, sent to the engine for Android TTS.",
        "AudioTrackSink", "AudioTrack PlaybackParams pitch", "VoiceSettingsTest#pitch", deviceOnly = true)
    val METALLIC = float("voice.metallic", "Metallic tone", VOICE, 0.25, 0.0, 1.0, "Amount of the metallic (ring-modulation/comb) effect.",
        "VoiceEffect", "PCM processed by a ring-modulator + comb filter before playback", "VoiceSettingsTest#metallic")
    val VOLUME = float("voice.volume", "Speech volume", VOICE, 1.0, 0.0, 1.0, "Relative speech volume.", "AudioTrackSink",
        "Playback gain", "VoiceSettingsTest#volume")
    val SPEAK_RESPONSES = bool("voice.speakResponses", "Speak replies to typed messages", VOICE, false,
        "Also speak replies when you type (voice mode always speaks).", "ChatController", "TTS invoked for text-mode replies", "VoiceSettingsTest#speakResponses")
    val INCREMENTAL_TTS = bool("voice.incremental", "Speak while generating", VOICE, true,
        "Start speaking at the first short phrase instead of waiting for the full reply.", "AdaptiveChunker, StreamingSpeech",
        "Phrases are synthesised and played while the model is still generating", "VoiceSettingsTest#incremental")
    val OUTPUT_ROUTE = choice("voice.outputRoute", "Speech output path", VOICE, "communication",
        listOf("communication" to "Call audio (best echo cancellation for interrupting)", "media" to "Assistant audio (louder, weaker echo cancellation)"),
        "Audio path used for Clu's voice during a voice conversation.", "VoiceSession, AudioTrackSink",
        "communication: MODE_IN_COMMUNICATION + USAGE_VOICE_COMMUNICATION so the device echo canceller removes Clu's voice from the microphone; media: USAGE_ASSISTANT",
        "VoiceSettingsTest#outputRoute", deviceOnly = true,
        limits = "Echo cancellation quality is device dependent; with 'media' Clu may hear itself and needs louder speech to be interrupted.")
    val BARGE_IN = bool("voice.bargeIn", "Interrupt by speaking", VOICE, true,
        "When you start speaking during playback, Clu stops talking and listens.", "TurnManager",
        "Speech detected during SPEAKING stops playback and starts a new turn", "VoiceSettingsTest#bargeIn",
        limits = "Requires echo cancellation to avoid self-interruption; reliability depends on the device's AEC.")
    val ASR_ENGINE = choice("voice.asrEngine", "Speech recognition", VOICE, "clu",
        listOf("clu" to "Clu offline (live English recogniser + Whisper for Hindi/mixed)", "android_ondevice" to "Android on-device recognizer",
            "android" to "Android default recognizer (may use network)"),
        "Engine used to transcribe speech.", "SpeechInput, WhisperStt", "Selects the ASR backend", "VoiceSettingsTest#asrEngine",
        apply = ApplyMode.VOICE_RESTART, deviceOnly = true,
        limits = "English is recognised live while you speak. Hindi, mixed Hindi-English and automatic detection use Whisper, which transcribes after you stop speaking and needs a one-time model download (466 MB). Android recognizers depend on what is installed; the default recognizer may send audio to its provider.")
    val WHISPER_MODEL = choice("voice.whisperModel", "Whisper model", VOICE, "whisper-small",
        listOf("whisper-small" to "small (466 MB; needed for Hindi)", "whisper-base" to "base (142 MB; faster, English)"),
        "Whisper model used for speech recognition.", "WhisperStt", "Selects the ggml model file", "VoiceSettingsTest#whisperModel",
        apply = ApplyMode.VOICE_RESTART, deviceOnly = true, limits = "base does not recognise Hindi reliably (measured).")
    val STT_LANGUAGE = choice("voice.sttLanguage", "Spoken language", VOICE, "auto",
        listOf("auto" to "Automatic (English, Hindi or mixed – Whisper)", "en" to "English (live, fastest)", "hi" to "Hindi (Whisper)",
            "mixed" to "Hindi + English mixed (Whisper)"),
        "Language you speak to Clu. English is recognised live; the others use Whisper after you stop speaking.", "WhisperStt, VoiceSession",
        "Routes each utterance to the live English recogniser or to Whisper with this language", "VoiceSettingsTest#sttLanguage",
        apply = ApplyMode.IMMEDIATE, limits = "Automatic, Hindi and mixed need the Whisper model; until it is installed Clu recognises English only and says so.")
    val VAD_SENSITIVITY = float("voice.vadSensitivity", "Voice detection sensitivity", VOICE, 0.5, 0.0, 1.0,
        "Higher = detects quieter speech (and more noise).", "VoiceActivityDetector", "Silero VAD threshold = 0.75 - 0.5 x sensitivity",
        "VoiceSettingsTest#vadSensitivity", apply = ApplyMode.VOICE_RESTART)
    val END_SILENCE = int("voice.endSilenceMs", "End-of-speech pause (ms)", VOICE, 800, 300, 2500,
        "Silence that ends your turn. Longer = more time to think mid-sentence, but Clu answers later (800 ms tolerated 0.7 s pauses in tests; 1000 ms tolerates ~1 s).", "VoiceActivityDetector, Endpointer", "Minimum silence before the utterance is finalised",
        "VoiceSettingsTest#endSilence", apply = ApplyMode.VOICE_RESTART)
    val ACK_MODE = choice("voice.ackMode", "Activation acknowledgement", VOICE, "tone",
        listOf("none" to "None", "tone" to "Short tone", "speech" to "Spoken phrase"), "Feedback when Clu starts listening.",
        "VoiceSession", "Plays a tone / speaks a phrase on activation", "VoiceSettingsTest#ackMode")
    val ACK_PHRASES = list("voice.ackPhrases", "Acknowledgement phrases", VOICE, listOf("Yes?", "Listening.", "Go on."),
        "Used when acknowledgement is set to spoken phrase.", "VoiceSession", "One phrase is spoken on activation", "VoiceSettingsTest#ackMode",
        SettingType.TextList(20, 60, minItems = 1))
    val MIC_MODE = choice("voice.micMode", "Listening mode", VOICE, "conversation",
        listOf("conversation" to "Conversation – keep listening for follow-ups until you tap Stop or stay silent",
            "tap" to "One question – stop listening after Clu replies", "hold" to "Hold to talk"),
        "What happens after you tap the microphone. Clu never listens in the background unless Background listening is on.",
        "ChatController, VoiceSession", "Conversation: the session stays open for follow-ups (no wake word needed) until the follow-up window passes in silence; One question: ends after the reply",
        "VoiceSettingsTest#micMode")
    val CONTINUE_LISTENING = bool("voice.continueAfterReply", "Keep listening after replies", VOICE, true,
        "After Clu replies in a voice session, listen for a follow-up without requiring the wake word.", "TurnManager",
        "Session returns to LISTENING instead of WAITING_FOR_WAKE", "VoiceSettingsTest#continueListening")
    val FOLLOWUP_TIMEOUT = int("voice.followUpTimeoutSec", "Follow-up window (s)", VOICE, 30, 3, 300,
        "In a conversation, how long Clu keeps listening for your next sentence before closing the microphone.", "TurnManager", "Idle timeout of an active session",
        "VoiceSettingsTest#followUpTimeout")
    val AUDIO_SOURCE = choice("voice.audioSource", "Microphone processing", VOICE, "voice_communication",
        listOf("voice_communication" to "Echo-cancelled (recommended for interruption)", "voice_recognition" to "Raw recognition tuning", "mic" to "Unprocessed microphone"),
        "Android capture source.", "AudioCapture", "AudioRecord source selection", "VoiceSettingsTest#audioSource",
        apply = ApplyMode.VOICE_RESTART, deviceOnly = true)
    val NOISE_SUPPRESSION = bool("voice.noiseSuppression", "Noise suppression", VOICE, true, "Attach the platform noise suppressor if available.",
        "AudioCapture", "android.media.audiofx.NoiseSuppressor enabled on the capture session", "VoiceSettingsTest#noiseSuppression",
        apply = ApplyMode.VOICE_RESTART, deviceOnly = true, limits = "Only if the device exposes NoiseSuppressor.")

    // ---------------------------------------------------------------- Wake words & activation
    val WAKE_ENABLED = bool("wake.enabled", "Wake word", WAKE, true, "Activate Clu by name while background listening runs.",
        "WakeDetector", "Wake detection runs on the audio stream", "WakeSettingsTest#wakeEnabled", apply = ApplyMode.VOICE_RESTART)
    val WAKE_WORDS = def(SettingDef("wake.words", "Wake words", WAKE,
        SettingType.Structured(::validateWakeWords),
        CluJson.encodeToJsonElement(ListSerializer(WakeWord.serializer()), WakeWord.defaults()),
        "Activation phrases. The primary phrase is the only one accepted in noisy surroundings (if strict mode is on).",
        "WakeDetector, WakePolicy", "Detector keyword set and policy; disabled phrases never activate", SettingScope.SHARED,
        ApplyMode.VOICE_RESTART, false, "WakeSettingsTest#wakeWords"))
    val WAKE_ENGINE = choice("wake.engine", "Wake detection method", WAKE, "kws",
        listOf("kws" to "Keyword spotter (recommended: lowest power, most reliable)", "asr" to "Speech-gated recognition (any phrase, recorded samples supported)"),
        "How wake phrases are detected.", "WakeDetector", "Selects the detector implementation", "WakeSettingsTest#engine",
        apply = ApplyMode.VOICE_RESTART, deviceOnly = true,
        limits = "Keyword spotter phrases must be expressible in its English token set. Speech-gated recognition is less reliable on short names (measured).")
    val KWS_THRESHOLD = float("wake.kwsThreshold", "Keyword spotter threshold", WAKE, 0.1, 0.02, 0.5,
        "Lower = more sensitive (more false activations). 0.1 detected \"Clue\" without false positives in testing.", "WakeDetector",
        "sherpa-onnx keywords_threshold", "WakeSettingsTest#engine")
    val WAKE_THRESHOLD = float("wake.matchThreshold", "Wake match strictness", WAKE, 0.75, 0.4, 1.0,
        "Similarity required between heard words and a wake phrase (higher = stricter).", "WakePhraseMatcher",
        "Minimum fuzzy-match score for activation", "WakeSettingsTest#threshold")
    val WAKE_LEADING = bool("wake.requireLeading", "Name must start the sentence", WAKE, true,
        "Only activate when the wake word begins what you say (\"Clue, …\"), not when it appears mid-sentence.", "WakeDetector, WakePolicy",
        "Keyword detections that start > 0.45 s into an utterance are ignored", "WakeSettingsTest#leading",
        limits = "Keyword spotter only. Tuned on synthetic speech; verify on the device.")
    val CLAP_ENABLED = bool("wake.clap", "Clap activation", WAKE, false, "Double clap activates Clu (quiet surroundings only).",
        "ClapDetector, WakePolicy", "Clap patterns can activate a session", "WakeSettingsTest#clap", apply = ApplyMode.VOICE_RESTART)
    val CLAP_SENSITIVITY = float("wake.clapSensitivity", "Clap sensitivity", WAKE, 0.5, 0.0, 1.0, "Higher = softer claps count.",
        "ClapDetector", "Transient peak/rise thresholds", "WakeSettingsTest#clapSensitivity")
    val CLAP_COUNT = int("wake.clapCount", "Claps required", WAKE, 2, 1, 3, "Number of claps in quick succession.", "ClapDetector",
        "Pattern length required", "WakeSettingsTest#clapCount")
    val NOISE_ADAPTIVE = bool("wake.noiseAdaptive", "Noise-aware activation", WAKE, true,
        "Measure background noise and restrict activation methods when it is loud.", "NoiseEstimator, WakePolicy",
        "Policy switches between quiet and noisy rules based on the measured noise floor", "WakeSettingsTest#noiseAdaptive")
    val NOISY_DB = float("wake.noisyThresholdDb", "Noisy threshold (dBFS)", WAKE, -42.0, -70.0, -15.0,
        "Noise floor above which surroundings count as noisy.", "WakePolicy", "Quiet/noisy decision boundary", "WakeSettingsTest#noisyThreshold",
        limits = "dBFS depends on the microphone; check the live meter in diagnostics to calibrate.")
    val STRICT_IN_NOISE = bool("wake.strictInNoise", "Primary name only when noisy", WAKE, true,
        "In noisy surroundings only the primary wake phrase activates Clu; clap activation is disabled.", "WakePolicy",
        "Secondary phrases and claps ignored when noisy", "WakeSettingsTest#strictInNoise")
    val BACKGROUND_LISTENING = bool("wake.background", "Background listening", WAKE, false,
        "Keep listening for the wake word with a foreground service (shows a notification).", "ListeningService",
        "Starts/stops the microphone foreground service", "WakeSettingsTest#background", deviceOnly = true,
        limits = "Android stops listening after force-stop, permission revocation, or when the OS kills the service. Uses battery.")
    val TERMINATION_PHRASES = list("wake.terminationPhrases", "Session end phrases", WAKE, listOf("end of the line"),
        "Saying one of these ends the active voice session.", "TurnManager", "Matching utterance ends the session", "WakeSettingsTest#termination",
        SettingType.TextList(10, 60, minItems = 1))
    val TERMINATION_RESPONSES = list("wake.terminationResponses", "Session end responses", WAKE,
        listOf("Understood, {owner}. Standing by.", "As you wish. {farewell}, {owner}.", "Very well. I'll be here."),
        "Spoken when a session ends. {farewell} = good night / goodbye depending on time.", "ResponseTemplates",
        "One response is spoken when the session ends", "WakeSettingsTest#termination", SettingType.TextList(20, 160, minItems = 1))

    // ---------------------------------------------------------------- AI model
    val MODEL_ACTIVE = text("model.activeId", "Active model", MODEL, "", "Identifier of the active primary model (choose in Models).",
        "LlmService", "The selected GGUF is loaded for inference", "ModelSettingsTest#activeModel", scope = DEVICE, apply = ApplyMode.MODEL_RELOAD)
    val CONTEXT_LENGTH = int("model.contextLength", "Context length (tokens)", MODEL, 4096, 512, 16384,
        "Larger = more history/memory per reply, more RAM.", "LlmService, ContextBudget", "llama.cpp n_ctx and history trimming budget",
        "ModelSettingsTest#contextLength", scope = DEVICE, apply = ApplyMode.MODEL_RELOAD)
    val MAX_TOKENS = int("model.maxTokens", "Max reply tokens", MODEL, 400, 32, 4096, "Upper bound on generated tokens per reply.",
        "GenerationParams", "Generation stops at this many tokens", "ModelSettingsTest#maxTokens", apply = ApplyMode.NEXT_TURN)
    val TEMPERATURE = float("model.temperature", "Temperature", MODEL, 0.7, 0.0, 1.5, "0 = deterministic.", "GenerationParams",
        "Sampler temperature (0 selects greedy)", "ModelSettingsTest#sampling", apply = ApplyMode.NEXT_TURN)
    val TOP_K = int("model.topK", "Top-K", MODEL, 40, 0, 200, "0 disables.", "GenerationParams", "Sampler top-k", "ModelSettingsTest#sampling",
        apply = ApplyMode.NEXT_TURN, advanced = true)
    val TOP_P = float("model.topP", "Top-P", MODEL, 0.9, 0.05, 1.0, "1 disables.", "GenerationParams", "Sampler top-p", "ModelSettingsTest#sampling",
        apply = ApplyMode.NEXT_TURN, advanced = true)
    val MIN_P = float("model.minP", "Min-P", MODEL, 0.05, 0.0, 0.5, "0 disables.", "GenerationParams", "Sampler min-p", "ModelSettingsTest#sampling",
        apply = ApplyMode.NEXT_TURN, advanced = true)
    val REPEAT_PENALTY = float("model.repeatPenalty", "Repetition penalty", MODEL, 1.1, 1.0, 1.5, "1 disables.", "GenerationParams",
        "Sampler repetition penalty", "ModelSettingsTest#sampling", apply = ApplyMode.NEXT_TURN, advanced = true)
    val THREADS = int("model.threads", "CPU threads", MODEL, 0, 0, 8, "0 = automatic (performance cores).", "LlmService",
        "llama.cpp n_threads", "ModelSettingsTest#threads", scope = DEVICE, apply = ApplyMode.MODEL_RELOAD)
    val BATCH = int("model.batchSize", "Prompt batch size", MODEL, 256, 32, 1024, "Tokens processed per prompt step.", "LlmService",
        "llama.cpp n_batch", "ModelSettingsTest#batch", scope = DEVICE, apply = ApplyMode.MODEL_RELOAD, advanced = true)
    val USE_MMAP = bool("model.mmap", "Memory-map model file", MODEL, true, "Lower RAM pressure; pages are loaded on demand.",
        "LlmService", "llama.cpp load mode MMAP vs fully read", "ModelSettingsTest#mmap", scope = DEVICE, apply = ApplyMode.MODEL_RELOAD, advanced = true)
    val ACCELERATION = choice("model.acceleration", "Acceleration", MODEL, "cpu",
        listOf("cpu" to "CPU (ARM NEON + dot-product)", "gpu" to "GPU (OpenCL/Vulkan)"), "Compute backend.", "LlmService",
        "Selects the backend / GPU layer offload", "ModelSettingsTest#acceleration", scope = DEVICE, apply = ApplyMode.MODEL_RELOAD,
        limits = "This build contains only the CPU backend; GPU is shown as unavailable.")
    val GPU_LAYERS = int("model.gpuLayers", "Layers on the GPU", MODEL, 99, 0, 99,
        "With Acceleration = GPU: how many layers to offload (99 = all). If the GPU can't be initialised, CLU runs on the CPU and says so.",
        "LlmService", "n_gpu_layers passed to the engine; ignored when acceleration is CPU", "InferenceSettingsTest#gpuLayers", scope = DEVICE,
        apply = ApplyMode.MODEL_RELOAD, advanced = true)
    val FIT_CONTEXT = bool("model.fitContext", "Shrink the context to fit memory", MODEL, true,
        "If the model plus its context would not fit in the memory CLU may use, load it with a smaller context instead of refusing.",
        "LlmService", "Context length is reduced to the largest that fits; the new length is reported", "InferenceSettingsTest#fitContext", scope = DEVICE, advanced = true)
    val MAX_QUEUED = int("model.maxQueued", "Replies that may wait", MODEL, 4, 0, 16,
        "Requests beyond the one being generated that may queue for the model. More are refused with a clear message instead of piling up.",
        "InferenceService", "Requests over the limit fail immediately with 'busy'", "InferenceSettingsTest#maxQueued", scope = DEVICE, advanced = true)
    val FIRST_TOKEN_TIMEOUT = int("model.firstTokenTimeoutS", "Wait for the first word (s)", MODEL, 180, 10, 900,
        "A request that has started but produced nothing for this long is stopped with an error.", "InferenceService",
        "Watchdog on time-to-first-token", "InferenceSettingsTest#timeouts", scope = DEVICE, advanced = true)
    val STALL_TIMEOUT = int("model.stallTimeoutS", "Stall limit (s)", MODEL, 60, 5, 300,
        "A reply that stops producing words for this long is stopped.", "InferenceService", "Watchdog on gaps between tokens", "InferenceSettingsTest#timeouts", scope = DEVICE, advanced = true)
    val TOTAL_TIMEOUT = int("model.totalTimeoutS", "Longest reply (s)", MODEL, 900, 30, 3600,
        "No single request may use the model longer than this.", "InferenceService", "Watchdog on total generation time", "InferenceSettingsTest#timeouts", scope = DEVICE, advanced = true)
    val AUTO_UNLOAD = int("model.autoUnloadMin", "Unload when idle (min)", MODEL, 20, 0, 240, "Free model RAM after this idle time. 0 = never.",
        "LlmService", "Model is released after the idle period", "ModelSettingsTest#autoUnload", scope = DEVICE)
    val LOAD_ON_START = bool("model.loadOnStart", "Load model at start", MODEL, true, "Load the model when the app starts for a faster first reply.",
        "LlmService", "Model load begins at app start", "ModelSettingsTest#loadOnStart", scope = DEVICE)
    val THERMAL = bool("model.thermalGuard", "Thermal protection", MODEL, true, "Reduce threads and reply length when the device reports thermal stress.",
        "ThermalGovernor", "Threads/max tokens reduced at SEVERE+ thermal status", "ModelSettingsTest#thermal", scope = DEVICE)
    val CHAT_TEMPLATE = choice("model.chatTemplate", "Chat template", MODEL, "auto",
        listOf("auto" to "From model file", "chatml" to "ChatML (Qwen)", "llama3" to "Llama 3", "gemma" to "Gemma", "phi3" to "Phi-3"),
        "Prompt format. Use auto unless the model's built-in template is missing.", "LlmService", "Template passed to llama_chat_apply_template",
        "ModelSettingsTest#chatTemplate", scope = DEVICE, advanced = true)

    // ---------------------------------------------------------------- Memory
    val MEMORY_ENABLED = bool("memory.enabled", "Persistent memory", MEMORY, true,
        "When off, nothing new is written to long-term memory (conversation history is separate).", "MemoryService",
        "All persistent memory writes are refused", "MemorySettingsTest#enabled")
    val MEMORY_RETRIEVAL = bool("memory.retrieval", "Use memories in replies", MEMORY, true, "Retrieve relevant memories for each reply.",
        "MemoryRetriever, PromptBuilder", "Relevant memories are added to the prompt", "MemorySettingsTest#retrieval")
    val MEMORY_MAX = int("memory.maxInjected", "Memories per reply", MEMORY, 4, 0, 12, "Maximum memories added to a prompt.",
        "MemoryRetriever", "Caps retrieved memories", "MemorySettingsTest#maxInjected")
    val MEMORY_MIN_SCORE = float("memory.minScore", "Relevance threshold", MEMORY, 1.2, 0.0, 10.0,
        "Minimum relevance (BM25) for a memory to be used; automatically scaled with the number of memories.", "MemoryRetriever",
        "Memories below the BM25 score are not injected", "MemorySettingsTest#minScore", advanced = true)
    val TEMP_RETENTION = int("memory.temporaryDays", "Temporary context retention (days)", MEMORY, 7, 1, 90,
        "Temporary memories expire after this many days.", "MemoryService", "expires_at for TEMPORARY memories; expired ones are purged",
        "MemorySettingsTest#temporaryRetention")
    val WORKING_HOURS = int("memory.workingHours", "Working memory lifetime (hours)", MEMORY, 24, 1, 168,
        "Short-lived context (today's plans, temporary details) is dropped after this long unless you save it.", "WorkingMemory",
        "expires_at of working memory items", "MemoryM4SettingsTest#workingHours")
    val FORMATION = choice("memory.formation", "Learning facts from conversation", MEMORY, "suggest",
        listOf("off" to "Off", "suggest" to "Suggest what to remember (you approve)", "auto" to "Save facts I state clearly (inferences still need approval)"),
        "What Clu does when you state a fact, a preference or a procedure in conversation. Inferences about you always need your approval.",
        "MemoryFormation", "Candidates become suggestions, direct memories (stated facts only) or nothing", "MemoryM4SettingsTest#formation")
    val CONSOLIDATION = bool("memory.consolidate", "Tidy up memory in the background", MEMORY, true,
        "Merges exact duplicates (keeping the earlier versions) and suggests merging near-duplicates; never deletes information.", "Consolidator",
        "Background job runs consolidation", "MemoryM4SettingsTest#consolidate")
    val LEARN_PREFERENCES = bool("memory.learnPreferences", "Learn how you like replies", MEMORY, true,
        "Notices repeated feedback and asks before turning it into a standing preference.", "PreferenceLearner",
        "Signals are recorded and may produce a question", "MemoryM4SettingsTest#learnPreferences")
    val PROPOSE_INSIGHTS = bool("memory.proposeInsights", "Suggest insights", MEMORY, true,
        "Clu may propose inferred memories; nothing is saved without your approval.", "InsightProposer", "Proposals are created for review",
        "MemorySettingsTest#proposeInsights")
    val REMEMBER_COMMAND = bool("memory.rememberCommand", "\"Remember that…\" saves directly", MEMORY, true,
        "Explicit owner instructions to remember something are saved immediately as owner-supplied facts.", "CommandRouter, MemoryService",
        "Command saves a memory instead of creating a proposal", "MemorySettingsTest#rememberCommand")
    val DIARY_MODE = choice("diary.mode", "Conversation diary", MEMORY, "propose",
        listOf("off" to "Off", "propose" to "Propose entries for approval", "automatic" to "Write entries automatically"),
        "Whether meaningful conversations become diary entries.", "DiaryWriter", "Creates proposals / entries when a conversation ends",
        "MemorySettingsTest#diaryMode")

    // ---------------------------------------------------------------- Personal archive & timeline (1.8.0)
    private val ISO_DAY = Regex("\\d{4}-\\d{2}-\\d{2}")
    val TIMELINE_START = text("history.timelineStart", "Clu timeline start", MEMORY, "2018-01-01",
        "When your history with Clu begins (yyyy-mm-dd). Clu's identity, the timeline view and gap detection start here; " +
            "nothing is invented to fill it.", "CluTime, PromptBuilder, ArchiveTimeline",
        "Prompt says when Clu's history began; timeline and gaps start here; 'told' dates are not earlier", "HistorySettingsTest#timelineStart",
        SettingType.Text(10, pattern = ISO_DAY))
    val SIMULATED_TIME = choice("history.simulatedTime", "Simulated current date", MEMORY, "off",
        listOf("off" to "Off: use the device date", "fixed" to "Fixed: always the date below", "advancing" to "Advancing: starts at the date below and moves on day by day"),
        "Lets Clu talk as if today were another date (e.g. while building its history). The device clock is still used for the database.",
        "CluTime", "Clu's 'today', 'last year' and 'N years ago' use the simulated date", "HistorySettingsTest#simulatedTime")
    val SIMULATED_DATE = text("history.simulatedDate", "Simulated date", MEMORY, "",
        "The simulated current date (yyyy-mm-dd), used when simulated time is on.", "CluTime", "Sets Clu's conversational 'today'",
        "HistorySettingsTest#simulatedTime", SettingType.Text(10, pattern = ISO_DAY))
    val HISTORY_PERSPECTIVE = text("history.perspective", "Historical perspective (as of)", MEMORY, "",
        "When set (yyyy-mm-dd), Clu answers as of that date: only memories it knew by then are used, and later events are not mentioned. Empty = off.",
        "CluTime, Recall, MemoryRetriever", "Recall and memory retrieval are cut off at this date", "HistorySettingsTest#perspective",
        SettingType.Text(10, pattern = ISO_DAY))
    val TOLD_DEFAULT = choice("history.toldDefault", "When Clu learned an undated-told memory", MEMORY, "event",
        listOf("event" to "When it happened (you told Clu at the time)", "unknown" to "Unknown"),
        "For a historical memory without a 'told to Clu' date: assume you told Clu when it happened, or treat that as unknown.",
        "ArchiveDates, Recall", "'How long ago did I tell you…' and the historical perspective use this", "HistorySettingsTest#toldDefault")
    val RECALL_IN_CHAT = bool("history.recallInChat", "Answer memory questions from the archive", MEMORY, true,
        "\"Do you remember…\", \"what did I tell you about…\", \"what happened in March 2018?\": answered directly from your records, with their dates.",
        "Recall, CluAgent", "Memory questions get record-based answers instead of a model reply", "HistorySettingsTest#recallInChat")
    val RESURFACE = bool("history.resurface", "Bring up related earlier decisions", MEMORY, true,
        "When a project or person comes up, related earlier decisions and memories are given to Clu (a few, only when relevant).",
        "MemoryRetriever, ProjectContext", "Related decisions are added to the reply's context", "HistorySettingsTest#resurface")
    val SUGGEST_MISSING = bool("history.suggestMissing", "Suggest missing details", MEMORY, true,
        "While entering or importing, point out missing dates or unknown people (never blocks saving).", "ArchiveService",
        "Editor and import preview list missing-context hints", "HistorySettingsTest#suggestMissing")

    // ---------------------------------------------------------------- Security
    val SPEAKER_VERIFY = bool("security.speakerId", "Recognise speakers by voice", SECURITY, true,
        "Identify the owner and registered people from their voice.", "SpeakerIdentifier", "Each voice turn is attributed to a person or 'unknown'",
        "SecuritySettingsTest#speakerId", limits = "Probabilistic. Never used as the sole credential for sensitive actions.",
        deviceOnly = true, scope = SettingScope.SHARED)
    val SPEAKER_THRESHOLD = float("security.speakerThreshold", "Voice match threshold", SECURITY, 0.55, 0.2, 0.95,
        "Cosine similarity required to recognise a voice.", "SpeakerIdentifier", "Match decision boundary", "SecuritySettingsTest#threshold")
    val UNKNOWN_POLICY = choice("security.unknownSpeaker", "Unknown voices", SECURITY, "guest",
        listOf("guest" to "Treat as guest (routine actions only)", "ignore" to "Ignore commands", "owner" to "Treat as owner (no voice gating)"),
        "Permissions for unrecognised voices.", "AuthorizationPolicy", "Determines the actor for unrecognised speakers",
        "SecuritySettingsTest#unknownPolicy", sensitive = true)
    val REFUSAL_LINES = list("security.refusalLines", "Refusal lines", SECURITY,
        listOf("I don't take orders from you.", "Nice try. Only my owner gets to do that.", "Denied. You're not who I answer to."),
        "Said when someone without permission issues a command.", "AuthorizationPolicy", "One line is used for refusals",
        "SecuritySettingsTest#refusal", SettingType.TextList(20, 160, minItems = 1))
    val PIN_FOR_SENSITIVE = def(SettingDef("security.pinForSensitive", "Verify for sensitive actions", SECURITY, SettingType.Bool, jsonOf(true),
        "Require PIN / device credential for security changes, deletions and other sensitive actions.", "AuthorizationPolicy",
        "Sensitive actions demand fresh verification", SettingScope.SHARED, ApplyMode.IMMEDIATE, true, "SecuritySettingsTest#pinForSensitive"))
    val AUTH_WINDOW = int("security.authWindowSec", "Verification valid for (s)", SECURITY, 120, 0, 900,
        "How long a successful verification authorises further sensitive actions.", "AuthorizationPolicy", "Grace period after verification",
        "SecuritySettingsTest#authWindow")

    // ---------------------------------------------------------------- Tools
    val APP_POLICY = choice("tools.appPolicy", "Apps Clu may open", TOOLS, "all",
        listOf("all" to "Any launchable app", "allowlist" to "Only listed apps"), "Restricts the open-app tool.", "OpenAppTool",
        "Apps outside the allowlist are refused", "ToolSettingsTest#appPolicy")
    val APP_ALLOWLIST = list("tools.appAllowlist", "Allowed apps", TOOLS, emptyList(), "App names or package ids allowed when policy = allowlist.",
        "OpenAppTool", "Allowlist entries", "ToolSettingsTest#appPolicy", SettingType.TextList(100, 120))
    val TOOL_HISTORY_DAYS = int("tools.historyDays", "Keep tool history (days)", TOOLS, 30, 1, 365, "Execution history retention.",
        "ToolExecutor", "Older tool runs are purged", "ToolSettingsTest#historyRetention")
    val AGENT_MAX_STEPS = int("tools.maxSteps", "Max steps per task", TOOLS, 4, 1, 8, "Upper bound on tool calls in one request.",
        "CluAgent", "Agent loop stops after this many tool steps", "ToolSettingsTest#maxSteps")

    // ---------------------------------------------------------------- Network
    val NET_ENABLED = bool("net.enabled", "Allow internet access", NETWORK, true,
        "Master switch. When off, Clu never uses the network (local features keep working).", "NetworkPolicy",
        "All network tools/downloads/external AI are refused", "NetworkSettingsTest#master")
    val NET_WEB = bool("net.web", "Web research", NETWORK, true, "Allow web/Wikipedia lookups for current information.", "NetworkPolicy, WebSearchTool",
        "Web search tool permitted", "NetworkSettingsTest#categories")
    val NET_MAPS = bool("net.maps", "Maps & places", NETWORK, true, "Allow geocoding and route distance lookups (OpenStreetMap services).",
        "NetworkPolicy, MapsTools", "Geocode/route tools permitted", "NetworkSettingsTest#categories")
    val NET_TRAVEL = bool("net.travel", "Travel / trains", NETWORK, true, "Allow travel information lookups.", "NetworkPolicy, TrainTool",
        "Train lookup permitted", "NetworkSettingsTest#categories")
    val NET_DOWNLOADS = bool("net.downloads", "Model & voice downloads", NETWORK, true, "Allow downloading models/voice packs (always with your confirmation).",
        "NetworkPolicy, Downloader", "Downloads permitted", "NetworkSettingsTest#categories")
    val SEARCH_PROVIDER = choice("net.searchProvider", "Search provider", NETWORK, "duckduckgo",
        listOf("duckduckgo" to "Wikipedia + DuckDuckGo Instant Answers (free, no key; not for news)", "wikipedia" to "Wikipedia only (free, no key)",
            "brave" to "Brave Search API (your key; full web incl. news)", "searxng" to "SearXNG (your own instance)"),
        "Which documented search API answers searches. Only the search words are sent.", "SearchService",
        "Selects the provider queried", "NetworkSettingsTest#searchProvider")
    val SEARCH_AUTO = choice("net.searchAuto", "Search automatically", NETWORK, "auto",
        listOf("auto" to "When a question needs current information", "explicit" to "Only when I say \"search\" or \"look up\""),
        "Whether Clu searches on its own for news, prices, releases, results and other time-sensitive questions, or only on request. " +
            "Ordinary conversation never triggers a search.", "SearchNeed, CluAgent", "A detected need runs web.search before the reply",
        "SearchAgentTest#autoSetting")
    val SEARCH_RESULTS = int("net.searchResults", "Results per search", NETWORK, 4, 3, 5, "How many results Clu retrieves and reads.",
        "SearchService", "count sent to the provider; results passed to the model", "SearchAgentTest#resultCount", advanced = true)
    val SEARCH_READ_PAGES = bool("net.searchReadPages", "Read top result pages", NETWORK, true,
        "Also read short excerpts of the two best result pages when their robots.txt allows it (more accurate answers, ~2 extra requests).",
        "PageReader, RobotsPolicy", "Up to 2 page fetches per search after a robots.txt check", "SearchTest#pageReading", advanced = true)
    val SEARXNG_URL = text("net.searxngUrl", "SearXNG address", NETWORK, "", "Base URL of a SearXNG instance with JSON output enabled.",
        "SearxngSearch", "Searches go to this instance", "SearchTest#searxng", SettingType.Text(300, pattern = Regex("https?://\\S+")))
    val EXT_AI_MODE = choice("net.externalAi", "External AI", NETWORK, "off",
        listOf("off" to "Never", "on_request" to "Only when I ask for deep analysis", "auto" to "When Clu judges it useful"),
        "Optional OpenAI-compatible service for deeper analysis. Not required for anything.", "ExternalAiTool, CluAgent",
        "Whether the external AI tool can be used", "NetworkSettingsTest#externalAi")
    val EXT_AI_URL = text("net.externalAiUrl", "External AI endpoint", NETWORK, "", "Base URL of an OpenAI-compatible API (e.g. a free tier or your own server).",
        "ExternalAiTool", "Requests go to this endpoint", "NetworkSettingsTest#externalAi",
        SettingType.Text(300, pattern = Regex("https?://\\S+")))
    val EXT_AI_MODEL = text("net.externalAiModel", "External AI model", NETWORK, "", "Model name sent to the endpoint.", "ExternalAiTool",
        "Model field of the request", "NetworkSettingsTest#externalAi", SettingType.Text(100))
    val EXT_AI_SHARE_MEMORY = bool("net.shareMemories", "Share memories with external AI", NETWORK, false,
        "Include retrieved private memories when calling an external AI.", "ExternalAiTool", "Memories stripped from external requests when off",
        "NetworkSettingsTest#shareMemories", sensitive = false)
    val SHOW_SOURCES = bool("net.showSources", "Show where answers came from", NETWORK, true,
        "Label replies as local, tablet-hosted, or external, and list tools used.", "ChatUI", "Source badge shown under replies",
        "NetworkSettingsTest#showSources")
    val OFFLINE_BEHAVIOR = choice("net.offlineBehavior", "When offline", NETWORK, "local_answer",
        listOf("explain" to "Say live information is unavailable", "local_answer" to "Answer from local knowledge, clearly marked as possibly outdated"),
        "What Clu does when a request needs the internet but none is available.", "CluAgent", "Offline fallback text/flow",
        "NetworkSettingsTest#offlineBehavior")

    // ---------------------------------------------------------------- Devices
    val DEVICE_ROLE = choice("device.role", "This device's role", DEVICES, "portable",
        listOf("home" to "Home / base (e.g. Xiaomi Pad 6)", "portable" to "Portable (e.g. iQOO Neo 9 Pro)"),
        "Role of this execution environment.", "SyncEngine, RemoteInference", "Conflict policy 'prefer home' and remote inference target",
        "SyncSettingsTest#role", scope = DEVICE)
    val DEVICE_NAME = text("device.name", "Device name", DEVICES, "", "Name shown to the paired device. Empty = model name.", "SyncEngine",
        "Advertised in discovery/pairing", "SyncSettingsTest#role", SettingType.Text(40), scope = DEVICE)
    val SYNC_ENABLED = bool("sync.enabled", "Synchronisation", DEVICES, true, "Sync shared data with paired devices on the local network.",
        "SyncService", "Sync sessions run when a paired peer is reachable", "SyncSettingsTest#enabled", scope = DEVICE)
    val SYNC_SERVE = bool("sync.serve", "Accept connections", DEVICES, true, "Listen for the paired device (needed on at least one side).",
        "SyncServer", "TCP listener is started", "SyncSettingsTest#serve", scope = DEVICE, deviceOnly = true)
    val SYNC_DISCOVERY = choice("sync.discovery", "Discovery", DEVICES, "auto",
        listOf("auto" to "Automatic (mDNS + hotspot gateway + last address)", "manual" to "Manual address only"),
        "How the peer is found. Hotspots often block mDNS; automatic mode also tries the hotspot gateway.", "PeerLocator",
        "Candidate addresses tried", "SyncSettingsTest#discovery", scope = DEVICE)
    val SYNC_ADDRESS = text("sync.manualAddress", "Manual peer address", DEVICES, "", "host or host:port of the other device.", "PeerLocator",
        "Address tried first", "SyncSettingsTest#discovery", SettingType.Text(80, pattern = Regex("[A-Za-z0-9.\\-:\\[\\]]+")), scope = DEVICE)
    val SYNC_PORT = int("sync.port", "Sync port", DEVICES, 47823, 1024, 65535, "TCP port.", "SyncServer, PeerLocator", "Listening/connecting port",
        "SyncSettingsTest#port", scope = DEVICE, advanced = true)
    val SYNC_INTERVAL = int("sync.intervalMin", "Auto-sync interval (min)", DEVICES, 5, 0, 240, "While the app runs. 0 = manual only.",
        "SyncService", "Periodic sync attempts", "SyncSettingsTest#interval", scope = DEVICE)
    val SYNC_MEMORIES = bool("sync.memories", "Sync memories", DEVICES, true, "Approved memories, standing instructions and project notes.", "SyncEngine", "memories table included", "SyncSettingsTest#scopes")
    val SYNC_DIARY = bool("sync.diary", "Sync diary", DEVICES, false, "Off by default: the diary is private. When on, entries go only to your paired device, encrypted in transit, " +
        "and are included in sync files you choose to export.", "SyncEngine, SyncBundle", "diary entries included", "SyncStage2Test#diaryScope")
    val SYNC_CONVERSATIONS = bool("sync.conversations", "Sync conversation history", DEVICES, true, "", "SyncEngine", "conversations/messages included", "SyncSettingsTest#scopes")
    val SYNC_SETTINGS = bool("sync.settings", "Sync shared settings", DEVICES, true, "Device-specific settings never sync.", "SyncEngine", "shared settings included", "SyncSettingsTest#scopes")
    val SYNC_CHARACTERS = bool("sync.characters", "Sync characters", DEVICES, true, "", "SyncEngine", "characters included", "SyncSettingsTest#scopes")
    val SYNC_PREFERENCES = bool("sync.preferences", "Sync learned preferences", DEVICES, true, "", "SyncEngine", "preferences included", "SyncSettingsTest#scopes")
    val SYNC_COMMANDS = bool("sync.commands", "Sync custom commands", DEVICES, true, "", "SyncEngine", "custom commands included", "SyncSettingsTest#scopes")
    val SYNC_PEOPLE = bool("sync.people", "Sync people (without voiceprints)", DEVICES, true, "Voiceprints and PIN stay on each device.", "SyncEngine",
        "people included", "SyncSettingsTest#scopes")
    val CONFLICT_POLICY = choice("sync.conflicts", "Conflict resolution", DEVICES, "latest",
        listOf("latest" to "Latest edit wins", "home" to "Home device wins concurrent edits", "keep_both" to "Keep both (memories duplicated as conflict copies)"),
        "How simultaneous edits are resolved.", "SyncEngine", "Merge decision for conflicting rows", "SyncSettingsTest#conflicts")
    val REMOTE_INFERENCE = choice("remote.inference", "Use the home device's model", DEVICES, "fallback",
        listOf("local" to "Never (always local)", "prefer_home" to "Prefer home device when reachable", "fallback" to "Only if no local model is loaded"),
        "Whether this device may run replies on the paired home device.", "InferenceRouter", "Selects local vs remote engine",
        "SyncSettingsTest#remoteInference", scope = DEVICE)

    // ---------------------------------------------------------------- Proactive
    val PROACTIVE_ENABLED = bool("proactive.enabled", "Proactive conversation", PROACTIVE, true, "Clu may start conversations.",
        "ProactivePlanner", "Planner may produce messages", "ProactiveSettingsTest#enabled")
    val PROACTIVE_MAX = int("proactive.maxPerDay", "Max per day", PROACTIVE, 2, 0, 12, "", "ProactivePlanner", "Daily cap", "ProactiveSettingsTest#limits")
    val PROACTIVE_GAP = int("proactive.minGapHours", "Minimum gap (hours)", PROACTIVE, 4, 1, 48, "", "ProactivePlanner", "Spacing between messages", "ProactiveSettingsTest#limits")
    val PROACTIVE_CHECKINS = bool("proactive.checkIns", "Daily check-ins", PROACTIVE, true, "Ask about your day in the evening.",
        "ProactivePlanner", "Evening check-in candidate", "ProactiveSettingsTest#kinds")
    val PROACTIVE_FOLLOWUPS = bool("proactive.followUps", "Follow-ups", PROACTIVE, true, "Follow up on dated events you mentioned (e.g. an exam).",
        "ProactivePlanner", "Follow-up candidates from dated memories", "ProactiveSettingsTest#kinds")
    val PROACTIVE_WELLBEING = bool("proactive.wellbeing", "Gentle well-being check-ins", PROACTIVE, true,
        "If a recent memory says you were unwell or upset, ask how you are.", "ProactivePlanner", "Well-being candidate", "ProactiveSettingsTest#kinds")
    val PROACTIVE_GREET_OPEN = bool("proactive.greetOnOpen", "Greet when the app opens", PROACTIVE, true, "First open of the day gets a greeting.",
        "ProactivePlanner", "Greeting candidate", "ProactiveSettingsTest#kinds")
    val PROACTIVE_DELIVERY = choice("proactive.delivery", "Delivery", PROACTIVE, "notification",
        listOf("notification" to "Notification", "speak" to "Speak if the app is open, otherwise notify"), "How proactive messages reach you.",
        "ProactiveScheduler", "Notification vs speech", "ProactiveSettingsTest#delivery", deviceOnly = true)
    val AWARE_BATTERY = bool("awareness.battery", "Battery awareness", PROACTIVE, true, "Mention low battery in proactive messages.",
        "ProactivePlanner", "Low-battery candidate", "ProactiveSettingsTest#battery")

    // ---------------------------------------------------------------- Learning
    val LEARNING_ENABLED = bool("learning.enabled", "Adapt to my preferences", LEARNING, true,
        "Apply learned response preferences.", "PreferenceEngine, PromptBuilder", "Preference instructions included in the prompt", "LearningSettingsTest#enabled")
    val LEARNING_ALTERNATIVES = bool("learning.alternatives", "Occasionally offer two styles", LEARNING, true,
        "Sometimes show two alternative replies and learn from your choice.", "PreferenceEngine", "Alternative-response prompts are offered",
        "LearningSettingsTest#alternatives")
    val LEARNING_ALT_EVERY = int("learning.alternativesEvery", "Alternatives at most every N replies", LEARNING, 25, 5, 200, "",
        "PreferenceEngine", "Cooldown between alternative offers", "LearningSettingsTest#alternatives")
    val LEARNING_QUESTIONS = bool("learning.questions", "Preference questions", LEARNING, true, "Occasionally ask a short multiple-choice question.",
        "PreferenceEngine", "Questions are offered", "LearningSettingsTest#questions")
    val LEARNING_Q_EVERY = int("learning.questionsEvery", "Questions at most every N replies", LEARNING, 15, 3, 200, "",
        "PreferenceEngine", "Cooldown between questions", "LearningSettingsTest#questions")

    // ---------------------------------------------------------------- Developer
    val DEV_DIAGNOSTICS = bool("dev.diagnostics", "Developer diagnostics", DEVELOPER, false, "Show diagnostics screens and advanced settings.",
        "UI", "Diagnostics entry and advanced settings become visible", "DevSettingsTest#diagnostics", scope = DEVICE)
    val DEV_LOG_LEVEL = choice("dev.logLevel", "Log level", DEVELOPER, "info",
        listOf("error" to "Errors", "warn" to "Warnings", "info" to "Info", "debug" to "Debug"), "", "DiagnosticsLog",
        "Events below the level are not recorded", "DevSettingsTest#logLevel", scope = DEVICE)
    val DEV_LOG_CAP = int("dev.logCapacity", "Log capacity (events)", DEVELOPER, 3000, 200, 50000, "Oldest events are dropped beyond this.",
        "DiagnosticsLog", "Bounded log size", "DevSettingsTest#logCapacity", scope = DEVICE)
    val DEV_SHOW_LATENCY = bool("dev.showLatency", "Show timings under replies", DEVELOPER, false, "", "ChatUI",
        "ASR/model/TTS timings displayed", "DevSettingsTest#showLatency", scope = DEVICE)
    val DEV_TOOL_TRACES = bool("dev.toolTraces", "Show tool traces in chat", DEVELOPER, true, "Show which tools ran and their results.", "ChatUI",
        "Tool trace lines displayed", "DevSettingsTest#toolTraces", scope = DEVICE)
    val ACTIVE_PERSONA = text("persona.active", "Active persona", GENERAL, "clu", "\"clu\" or a character id (switch in Characters).",
        "PersonaManager", "Prompt identity/instructions/memory scope switch", "PersonaSettingsTest#activePersona", advanced = true)

    /** Settings registered by components at runtime (e.g. per-tool enable/confirm switches). */
    fun registerDynamic(d: SettingDef) { if (d.key !in defs) defs[d.key] = d }

    private fun validateWakeWords(v: JsonElement): String? {
        val list = runCatching { CluJson.decodeFromJsonElement(ListSerializer(WakeWord.serializer()), v) }.getOrElse { return "invalid wake word list" }
        if (list.isEmpty()) return "at least one wake word"
        if (list.size > 12) return "at most 12 wake words"
        if (list.count { it.primary } != 1) return "exactly one primary wake word"
        if (list.any { it.phrase.isBlank() || it.phrase.length > 40 }) return "phrases must be 1-40 characters"
        if (list.map { it.phrase.lowercase().trim() }.toSet().size != list.size) return "duplicate phrases"
        if (list.none { it.enabled }) return "at least one enabled wake word"
        return null
    }
}
