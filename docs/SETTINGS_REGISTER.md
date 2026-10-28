# Settings Implementation Register

Generated from the settings registry (`core/.../config/Settings.kt`) and the JUnit results of the verifying tests. Version 1.6.2-prototype.

* Settings: **202** · verifying test passed: **202** · other: **0**
* Storage: every value is persisted in the SQLite table `settings` (key, JSON value, scope, HLC, origin). `SHARED` settings
  synchronise between paired devices; `DEVICE` settings stay on the device. Snapshots live in `settings_snapshots` (rollback / known-good).
* Persistence across restart and rejection of invalid values are verified for every setting by `RegistryTest`.
* "Device effect" = the binding is unit-tested, but the audible/visible effect itself can only be confirmed on a physical device
  (**unverified on device** in this build: no device was available to the build environment).

## General & personality

| Setting ID | Display name | Default | Accepted values | Scope | Responsible component | Observable effect | Applies | Test | Result | Known limitations |
|---|---|---|---|---|---|---|---|---|---|---|
| `identity.cluName` | Clu's name 🔒 | `"Clu"` | text ≤ 40 chars, non-empty | SHARED | PromptBuilder, UI | System prompt identity line and screen titles use this name | Applies immediately | `PersonaSettingsTest#cluName` | passed | – |
| `identity.ownerName` | Owner's name | `""` | text ≤ 60 chars | SHARED | PromptBuilder | System prompt states who the owner is | Applies immediately | `PersonaSettingsTest#ownerName` | passed | – |
| `identity.ownerAddress` | Form of address | `""` | text ≤ 40 chars | SHARED | PromptBuilder, ResponseTemplates | Prompt instruction and spoken templates ({owner}) use it | Applies immediately | `PersonaSettingsTest#ownerAddress` | passed | – |
| `persona.language` | Response language | `"en"` | `en`, `hi`, `hinglish` | SHARED | PromptBuilder | Adds a language instruction to the system prompt | Applies immediately | `PersonaSettingsTest#language` | passed | Offline speech recognition model bundled for download is English; Hindi affects text replies only. |
| `persona.pronunciations` | Pronunciation fixes | `[]` | list of ≤ 50 entries (≤ 120 chars each) | SHARED | SpeechTextPreprocessor | Matching words are replaced in the text sent to the TTS engine | Applies immediately | `PersonaSettingsTest#pronunciations` | passed | – |
| `persona.responseLength` | Response length | `"balanced"` | `brief`, `balanced`, `detailed` | SHARED | PromptBuilder, GenerationParams | Changes the length instruction and the token budget | Applies from the next response | `PersonaSettingsTest#responseLength` | passed | – |
| `persona.formality` | Formality | `"neutral"` | `casual`, `neutral`, `formal` | SHARED | PromptBuilder | Adds a formality instruction | Applies from the next response | `PersonaSettingsTest#formality` | passed | – |
| `persona.humor` | Humour | `"dry"` | `none`, `dry`, `frequent` | SHARED | PromptBuilder | Adds a humour instruction | Applies from the next response | `PersonaSettingsTest#humor` | passed | – |
| `persona.sarcasm` | Sarcasm | `"light"` | `off`, `light`, `sharp` | SHARED | PromptBuilder, RefusalTemplates | Adds a sarcasm instruction; selects refusal line style | Applies from the next response | `PersonaSettingsTest#sarcasm` | passed | – |
| `persona.directness` | Directness | `"direct"` | `diplomatic`, `direct`, `blunt` | SHARED | PromptBuilder | Adds a directness instruction | Applies from the next response | `PersonaSettingsTest#directness` | passed | – |
| `persona.explanation` | Explanations | `"balanced"` | `minimal`, `balanced`, `thorough` | SHARED | PromptBuilder | Adds an explanation-depth instruction | Applies from the next response | `PersonaSettingsTest#explanation` | passed | – |
| `persona.proactivity` | Unprompted observations | `"occasional"` | `off`, `occasional`, `active` | SHARED | PromptBuilder | Adds an instruction about unrequested observations | Applies from the next response | `PersonaSettingsTest#proactivity` | passed | – |
| `persona.greetOnSessionStart` | Greet when activated | `true` | true / false | SHARED | VoiceSession, ResponseTemplates | A greeting line is spoken/shown on activation | Applies immediately | `PersonaSettingsTest#greetOnStart` | passed | – |
| `persona.greetings` | Greeting lines | `["{greeting}, {owner}.","I'm listening, {owner}.","Yes, {own` | list of ≤ 20 entries (≤ 160 chars each, at least 1) | SHARED | ResponseTemplates | One line is chosen for activation greetings | Applies immediately | `PersonaSettingsTest#greetings` | passed | – |
| `persona.customInstructions` | Custom instructions | `""` | text ≤ 2000 chars | SHARED | PromptBuilder | Included verbatim in the system prompt | Applies from the next response | `PersonaSettingsTest#customInstructions` | passed | – |
| `quiet.enabled` | Quiet hours | `true` | true / false | SHARED | QuietHours, ProactivePlanner, SpeechOutput | Proactive messages are not issued inside the window | Applies immediately | `PersonaSettingsTest#quietHours` | passed | – |
| `quiet.start` | Quiet hours start | `"22:30"` | HH:MM (24 h) | SHARED | QuietHours | Defines the quiet window | Applies immediately | `PersonaSettingsTest#quietHours` | passed | – |
| `quiet.end` | Quiet hours end | `"07:00"` | HH:MM (24 h) | SHARED | QuietHours | Defines the quiet window | Applies immediately | `PersonaSettingsTest#quietHours` | passed | – |
| `quiet.muteSpeech` | Mute speech in quiet hours | `false` | true / false | SHARED | SpeechOutput | TTS is skipped inside the quiet window | Applies immediately | `PersonaSettingsTest#quietMuteSpeech` | passed | – |
| `persona.active` | Active persona | `"clu"` | text ≤ 200 chars | SHARED | PersonaManager | Prompt identity/instructions/memory scope switch | Applies immediately | `PersonaSettingsTest#activePersona` | passed | – |

## Voice

| Setting ID | Display name | Default | Accepted values | Scope | Responsible component | Observable effect | Applies | Test | Result | Known limitations |
|---|---|---|---|---|---|---|---|---|---|---|
| `voice.ttsEngine` | TTS engine | `"piper"` | `piper`, `kokoro`, `kitten`, `hayai`, `system` | SHARED | SpeechOutput, TtsSelection | Selects the TTS backend used for every spoken phrase | Applies immediately | `VoiceSettingsTest#ttsEngine` | passed (binding) · device effect unverified | Piper and KittenTTS need their voice files (downloaded on the Voice screen). HayaiTTS and other engines are separate apps; they synthesise each phrase completely before returning it (phrase-level streaming only). |
| `voice.piperVoice` | Piper English voice | `"tts-joe"` | `tts-joe`, `tts-arctic-rms`, `tts-alan`, `tts-northern-male`, `tts-ryan` | SHARED | SpeechOutput | Selects the Piper model file | Applies immediately | `VoiceSettingsTest#piperVoice` | passed (binding) · device effect unverified | Each voice is a separate download. |
| `voice.sherpaSpeaker` | Piper speaker id | `0` | integer 0–1000 | SHARED | SpeechOutput | Passed as the speaker id to the Piper synthesiser | Applies immediately | `VoiceSettingsTest#speaker` | passed (binding) · device effect unverified | Single-speaker voices ignore values other than 0. |
| `voice.kittenSpeaker` | KittenTTS voice | `"2"` | `2`, `4`, `0`, `6` | SHARED | SpeechOutput | Kitten speaker id | Applies immediately | `VoiceSettingsTest#kittenSpeaker` | passed (binding) · device effect unverified | – |
| `voice.hindiVoice` | Hindi voice | `"pratham"` | `pratham`, `engine`, `off` | SHARED | SpeechOutput, TtsSelection | Routes Devanagari phrases | Applies immediately | `VoiceSettingsTest#hindiVoice` | passed (binding) · device effect unverified | Pratham's training data is licensed CC BY-NC-SA 4.0 (non-commercial). Romanized Hindi is spoken by the English voice and is hard to understand. |
| `voice.systemEngine` | Android TTS engine package | `""` | text ≤ 200 chars | SHARED | SpeechOutput | TextToSpeech engine package | Applies immediately | `VoiceSettingsTest#systemEngine` | passed (binding) · device effect unverified | – |
| `voice.androidVoice` | Android TTS English voice | `""` | text ≤ 200 chars | SHARED | SpeechOutput | Selects TextToSpeech voice | Applies immediately | `VoiceSettingsTest#androidVoice` | passed (binding) · device effect unverified | – |
| `voice.androidVoiceHi` | Android TTS Hindi voice | `""` | text ≤ 200 chars | SHARED | SpeechOutput | Selects TextToSpeech voice for Devanagari phrases | Applies immediately | `VoiceSettingsTest#androidVoiceHi` | passed (binding) · device effect unverified | – |
| `voice.speed` | Speaking speed | `0.92` | number 0.5–2.0 | SHARED | SpeechOutput | Synthesis speed (sherpa) / speech rate (Android) | Applies immediately | `VoiceSettingsTest#speed` | passed (binding) · device effect unverified | – |
| `voice.pitch` | Pitch adjustment | `1.0` | number 0.8–1.2 | SHARED | AudioTrackSink | AudioTrack PlaybackParams pitch | Applies immediately | `VoiceSettingsTest#pitch` | passed (binding) · device effect unverified | – |
| `voice.metallic` | Metallic tone | `0.25` | number 0.0–1.0 | SHARED | VoiceEffect | PCM processed by a ring-modulator + comb filter before playback | Applies immediately | `VoiceSettingsTest#metallic` | passed | – |
| `voice.volume` | Speech volume | `1.0` | number 0.0–1.0 | SHARED | AudioTrackSink | Playback gain | Applies immediately | `VoiceSettingsTest#volume` | passed | – |
| `voice.speakResponses` | Speak replies to typed messages | `false` | true / false | SHARED | ChatController | TTS invoked for text-mode replies | Applies immediately | `VoiceSettingsTest#speakResponses` | passed | – |
| `voice.incremental` | Speak while generating | `true` | true / false | SHARED | AdaptiveChunker, StreamingSpeech | Phrases are synthesised and played while the model is still generating | Applies immediately | `VoiceSettingsTest#incremental` | passed | – |
| `voice.outputRoute` | Speech output path | `"communication"` | `communication`, `media` | SHARED | VoiceSession, AudioTrackSink | communication: MODE_IN_COMMUNICATION + USAGE_VOICE_COMMUNICATION so the device echo canceller removes Clu's voice from the microphone; media: USAGE_ASSISTANT | Applies immediately | `VoiceSettingsTest#outputRoute` | passed (binding) · device effect unverified | Echo cancellation quality is device dependent; with 'media' Clu may hear itself and needs louder speech to be interrupted. |
| `voice.bargeIn` | Interrupt by speaking | `true` | true / false | SHARED | TurnManager | Speech detected during SPEAKING stops playback and starts a new turn | Applies immediately | `VoiceSettingsTest#bargeIn` | passed | Requires echo cancellation to avoid self-interruption; reliability depends on the device's AEC. |
| `voice.asrEngine` | Speech recognition | `"clu"` | `clu`, `android_ondevice`, `android` | SHARED | SpeechInput, WhisperStt | Selects the ASR backend | Restarts the listening session | `VoiceSettingsTest#asrEngine` | passed (binding) · device effect unverified | English is recognised live while you speak. Hindi, mixed Hindi-English and automatic detection use Whisper, which transcribes after you stop speaking and needs a one-time model download (466 MB). Android recognizers depend on what is installed; the default recognizer may send audio to its provider. |
| `voice.whisperModel` | Whisper model | `"whisper-small"` | `whisper-small`, `whisper-base` | SHARED | WhisperStt | Selects the ggml model file | Restarts the listening session | `VoiceSettingsTest#whisperModel` | passed (binding) · device effect unverified | base does not recognise Hindi reliably (measured). |
| `voice.sttLanguage` | Spoken language | `"auto"` | `auto`, `en`, `hi`, `mixed` | SHARED | WhisperStt, VoiceSession | Routes each utterance to the live English recogniser or to Whisper with this language | Applies immediately | `VoiceSettingsTest#sttLanguage` | passed | Automatic, Hindi and mixed need the Whisper model; until it is installed Clu recognises English only and says so. |
| `voice.vadSensitivity` | Voice detection sensitivity | `0.5` | number 0.0–1.0 | SHARED | VoiceActivityDetector | Silero VAD threshold = 0.75 - 0.5 x sensitivity | Restarts the listening session | `VoiceSettingsTest#vadSensitivity` | passed | – |
| `voice.endSilenceMs` | End-of-speech pause (ms) | `800` | integer 300–2500 | SHARED | VoiceActivityDetector, Endpointer | Minimum silence before the utterance is finalised | Restarts the listening session | `VoiceSettingsTest#endSilence` | passed | – |
| `voice.ackMode` | Activation acknowledgement | `"tone"` | `none`, `tone`, `speech` | SHARED | VoiceSession | Plays a tone / speaks a phrase on activation | Applies immediately | `VoiceSettingsTest#ackMode` | passed | – |
| `voice.ackPhrases` | Acknowledgement phrases | `["Yes?","Listening.","Go on."]` | list of ≤ 20 entries (≤ 60 chars each, at least 1) | SHARED | VoiceSession | One phrase is spoken on activation | Applies immediately | `VoiceSettingsTest#ackMode` | passed | – |
| `voice.micMode` | Listening mode | `"conversation"` | `conversation`, `tap`, `hold` | SHARED | ChatController, VoiceSession | Conversation: the session stays open for follow-ups (no wake word needed) until the follow-up window passes in silence; One question: ends after the reply | Applies immediately | `VoiceSettingsTest#micMode` | passed | – |
| `voice.continueAfterReply` | Keep listening after replies | `true` | true / false | SHARED | TurnManager | Session returns to LISTENING instead of WAITING_FOR_WAKE | Applies immediately | `VoiceSettingsTest#continueListening` | passed | – |
| `voice.followUpTimeoutSec` | Follow-up window (s) | `30` | integer 3–300 | SHARED | TurnManager | Idle timeout of an active session | Applies immediately | `VoiceSettingsTest#followUpTimeout` | passed | – |
| `voice.audioSource` | Microphone processing | `"voice_communication"` | `voice_communication`, `voice_recognition`, `mic` | SHARED | AudioCapture | AudioRecord source selection | Restarts the listening session | `VoiceSettingsTest#audioSource` | passed (binding) · device effect unverified | – |
| `voice.noiseSuppression` | Noise suppression | `true` | true / false | SHARED | AudioCapture | android.media.audiofx.NoiseSuppressor enabled on the capture session | Restarts the listening session | `VoiceSettingsTest#noiseSuppression` | passed (binding) · device effect unverified | Only if the device exposes NoiseSuppressor. |

## Wake words & activation

| Setting ID | Display name | Default | Accepted values | Scope | Responsible component | Observable effect | Applies | Test | Result | Known limitations |
|---|---|---|---|---|---|---|---|---|---|---|
| `wake.enabled` | Wake word | `true` | true / false | SHARED | WakeDetector | Wake detection runs on the audio stream | Restarts the listening session | `WakeSettingsTest#wakeEnabled` | passed | – |
| `wake.words` | Wake words | `[{"phrase":"Clue","enabled":true,"primary":true,"aliases":["` | structured list, validated (one primary, unique, ≥1 enabled) | SHARED | WakeDetector, WakePolicy | Detector keyword set and policy; disabled phrases never activate | Restarts the listening session | `WakeSettingsTest#wakeWords` | passed | – |
| `wake.engine` | Wake detection method | `"kws"` | `kws`, `asr` | SHARED | WakeDetector | Selects the detector implementation | Restarts the listening session | `WakeSettingsTest#engine` | passed (binding) · device effect unverified | Keyword spotter phrases must be expressible in its English token set. Speech-gated recognition is less reliable on short names (measured). |
| `wake.kwsThreshold` | Keyword spotter threshold | `0.1` | number 0.02–0.5 | SHARED | WakeDetector | sherpa-onnx keywords_threshold | Applies immediately | `WakeSettingsTest#engine` | passed | – |
| `wake.matchThreshold` | Wake match strictness | `0.75` | number 0.4–1.0 | SHARED | WakePhraseMatcher | Minimum fuzzy-match score for activation | Applies immediately | `WakeSettingsTest#threshold` | passed | – |
| `wake.requireLeading` | Name must start the sentence | `true` | true / false | SHARED | WakeDetector, WakePolicy | Keyword detections that start > 0.45 s into an utterance are ignored | Applies immediately | `WakeSettingsTest#leading` | passed | Keyword spotter only. Tuned on synthetic speech; verify on the device. |
| `wake.clap` | Clap activation | `false` | true / false | SHARED | ClapDetector, WakePolicy | Clap patterns can activate a session | Restarts the listening session | `WakeSettingsTest#clap` | passed | – |
| `wake.clapSensitivity` | Clap sensitivity | `0.5` | number 0.0–1.0 | SHARED | ClapDetector | Transient peak/rise thresholds | Applies immediately | `WakeSettingsTest#clapSensitivity` | passed | – |
| `wake.clapCount` | Claps required | `2` | integer 1–3 | SHARED | ClapDetector | Pattern length required | Applies immediately | `WakeSettingsTest#clapCount` | passed | – |
| `wake.noiseAdaptive` | Noise-aware activation | `true` | true / false | SHARED | NoiseEstimator, WakePolicy | Policy switches between quiet and noisy rules based on the measured noise floor | Applies immediately | `WakeSettingsTest#noiseAdaptive` | passed | – |
| `wake.noisyThresholdDb` | Noisy threshold (dBFS) | `-42.0` | number -70.0–-15.0 | SHARED | WakePolicy | Quiet/noisy decision boundary | Applies immediately | `WakeSettingsTest#noisyThreshold` | passed | dBFS depends on the microphone; check the live meter in diagnostics to calibrate. |
| `wake.strictInNoise` | Primary name only when noisy | `true` | true / false | SHARED | WakePolicy | Secondary phrases and claps ignored when noisy | Applies immediately | `WakeSettingsTest#strictInNoise` | passed | – |
| `wake.background` | Background listening | `false` | true / false | SHARED | ListeningService | Starts/stops the microphone foreground service | Applies immediately | `WakeSettingsTest#background` | passed (binding) · device effect unverified | Android stops listening after force-stop, permission revocation, or when the OS kills the service. Uses battery. |
| `wake.terminationPhrases` | Session end phrases | `["end of the line"]` | list of ≤ 10 entries (≤ 60 chars each, at least 1) | SHARED | TurnManager | Matching utterance ends the session | Applies immediately | `WakeSettingsTest#termination` | passed | – |
| `wake.terminationResponses` | Session end responses | `["Understood, {owner}. Standing by.","As you wish. {farewell` | list of ≤ 20 entries (≤ 160 chars each, at least 1) | SHARED | ResponseTemplates | One response is spoken when the session ends | Applies immediately | `WakeSettingsTest#termination` | passed | – |

## AI model

| Setting ID | Display name | Default | Accepted values | Scope | Responsible component | Observable effect | Applies | Test | Result | Known limitations |
|---|---|---|---|---|---|---|---|---|---|---|
| `model.activeId` | Active model | `""` | text ≤ 200 chars | DEVICE | LlmService | The selected GGUF is loaded for inference | Reloads the model | `ModelSettingsTest#activeModel` | passed | – |
| `model.contextLength` | Context length (tokens) | `4096` | integer 512–16384 | DEVICE | LlmService, ContextBudget | llama.cpp n_ctx and history trimming budget | Reloads the model | `ModelSettingsTest#contextLength` | passed | – |
| `model.maxTokens` | Max reply tokens | `400` | integer 32–4096 | SHARED | GenerationParams | Generation stops at this many tokens | Applies from the next response | `ModelSettingsTest#maxTokens` | passed | – |
| `model.temperature` | Temperature | `0.7` | number 0.0–1.5 | SHARED | GenerationParams | Sampler temperature (0 selects greedy) | Applies from the next response | `ModelSettingsTest#sampling` | passed | – |
| `model.topK` | Top-K | `40` | integer 0–200 | SHARED | GenerationParams | Sampler top-k | Applies from the next response | `ModelSettingsTest#sampling` | passed | – |
| `model.topP` | Top-P | `0.9` | number 0.05–1.0 | SHARED | GenerationParams | Sampler top-p | Applies from the next response | `ModelSettingsTest#sampling` | passed | – |
| `model.minP` | Min-P | `0.05` | number 0.0–0.5 | SHARED | GenerationParams | Sampler min-p | Applies from the next response | `ModelSettingsTest#sampling` | passed | – |
| `model.repeatPenalty` | Repetition penalty | `1.1` | number 1.0–1.5 | SHARED | GenerationParams | Sampler repetition penalty | Applies from the next response | `ModelSettingsTest#sampling` | passed | – |
| `model.threads` | CPU threads | `0` | integer 0–8 | DEVICE | LlmService | llama.cpp n_threads | Reloads the model | `ModelSettingsTest#threads` | passed | – |
| `model.batchSize` | Prompt batch size | `256` | integer 32–1024 | DEVICE | LlmService | llama.cpp n_batch | Reloads the model | `ModelSettingsTest#batch` | passed | – |
| `model.mmap` | Memory-map model file | `true` | true / false | DEVICE | LlmService | llama.cpp load mode MMAP vs fully read | Reloads the model | `ModelSettingsTest#mmap` | passed | – |
| `model.acceleration` | Acceleration | `"cpu"` | `cpu`, `gpu` | DEVICE | LlmService | Selects the backend / GPU layer offload | Reloads the model | `ModelSettingsTest#acceleration` | passed | This build contains only the CPU backend; GPU is shown as unavailable. |
| `model.autoUnloadMin` | Unload when idle (min) | `20` | integer 0–240 | DEVICE | LlmService | Model is released after the idle period | Applies immediately | `ModelSettingsTest#autoUnload` | passed | – |
| `model.loadOnStart` | Load model at start | `true` | true / false | DEVICE | LlmService | Model load begins at app start | Applies immediately | `ModelSettingsTest#loadOnStart` | passed | – |
| `model.thermalGuard` | Thermal protection | `true` | true / false | DEVICE | ThermalGovernor | Threads/max tokens reduced at SEVERE+ thermal status | Applies immediately | `ModelSettingsTest#thermal` | passed | – |
| `model.chatTemplate` | Chat template | `"auto"` | `auto`, `chatml`, `llama3`, `gemma`, `phi3` | DEVICE | LlmService | Template passed to llama_chat_apply_template | Applies immediately | `ModelSettingsTest#chatTemplate` | passed | – |

## Memory & diary

| Setting ID | Display name | Default | Accepted values | Scope | Responsible component | Observable effect | Applies | Test | Result | Known limitations |
|---|---|---|---|---|---|---|---|---|---|---|
| `memory.enabled` | Persistent memory | `true` | true / false | SHARED | MemoryService | All persistent memory writes are refused | Applies immediately | `MemorySettingsTest#enabled` | passed | – |
| `memory.retrieval` | Use memories in replies | `true` | true / false | SHARED | MemoryRetriever, PromptBuilder | Relevant memories are added to the prompt | Applies immediately | `MemorySettingsTest#retrieval` | passed | – |
| `memory.maxInjected` | Memories per reply | `4` | integer 0–12 | SHARED | MemoryRetriever | Caps retrieved memories | Applies immediately | `MemorySettingsTest#maxInjected` | passed | – |
| `memory.minScore` | Relevance threshold | `1.2` | number 0.0–10.0 | SHARED | MemoryRetriever | Memories below the BM25 score are not injected | Applies immediately | `MemorySettingsTest#minScore` | passed | – |
| `memory.temporaryDays` | Temporary context retention (days) | `7` | integer 1–90 | SHARED | MemoryService | expires_at for TEMPORARY memories; expired ones are purged | Applies immediately | `MemorySettingsTest#temporaryRetention` | passed | – |
| `memory.proposeInsights` | Suggest insights | `true` | true / false | SHARED | InsightProposer | Proposals are created for review | Applies immediately | `MemorySettingsTest#proposeInsights` | passed | – |
| `memory.rememberCommand` | "Remember that…" saves directly | `true` | true / false | SHARED | CommandRouter, MemoryService | Command saves a memory instead of creating a proposal | Applies immediately | `MemorySettingsTest#rememberCommand` | passed | – |
| `diary.mode` | Conversation diary | `"propose"` | `off`, `propose`, `automatic` | SHARED | DiaryWriter | Creates proposals / entries when a conversation ends | Applies immediately | `MemorySettingsTest#diaryMode` | passed | – |

## Owner, people & security

| Setting ID | Display name | Default | Accepted values | Scope | Responsible component | Observable effect | Applies | Test | Result | Known limitations |
|---|---|---|---|---|---|---|---|---|---|---|
| `security.speakerId` | Recognise speakers by voice | `true` | true / false | SHARED | SpeakerIdentifier | Each voice turn is attributed to a person or 'unknown' | Applies immediately | `SecuritySettingsTest#speakerId` | passed (binding) · device effect unverified | Probabilistic. Never used as the sole credential for sensitive actions. |
| `security.speakerThreshold` | Voice match threshold | `0.55` | number 0.2–0.95 | SHARED | SpeakerIdentifier | Match decision boundary | Applies immediately | `SecuritySettingsTest#threshold` | passed | – |
| `security.unknownSpeaker` | Unknown voices 🔒 | `"guest"` | `guest`, `ignore`, `owner` | SHARED | AuthorizationPolicy | Determines the actor for unrecognised speakers | Applies immediately | `SecuritySettingsTest#unknownPolicy` | passed | – |
| `security.refusalLines` | Refusal lines | `["I don't take orders from you.","Nice try. Only my owner ge` | list of ≤ 20 entries (≤ 160 chars each, at least 1) | SHARED | AuthorizationPolicy | One line is used for refusals | Applies immediately | `SecuritySettingsTest#refusal` | passed | – |
| `security.pinForSensitive` | Verify for sensitive actions 🔒 | `true` | true / false | SHARED | AuthorizationPolicy | Sensitive actions demand fresh verification | Applies immediately | `SecuritySettingsTest#pinForSensitive` | passed | – |
| `security.authWindowSec` | Verification valid for (s) | `120` | integer 0–900 | SHARED | AuthorizationPolicy | Grace period after verification | Applies immediately | `SecuritySettingsTest#authWindow` | passed | – |

## Device control & tools

| Setting ID | Display name | Default | Accepted values | Scope | Responsible component | Observable effect | Applies | Test | Result | Known limitations |
|---|---|---|---|---|---|---|---|---|---|---|
| `tools.appPolicy` | Apps Clu may open | `"all"` | `all`, `allowlist` | SHARED | OpenAppTool | Apps outside the allowlist are refused | Applies immediately | `ToolSettingsTest#appPolicy` | passed | – |
| `tools.appAllowlist` | Allowed apps | `[]` | list of ≤ 100 entries (≤ 120 chars each) | SHARED | OpenAppTool | Allowlist entries | Applies immediately | `ToolSettingsTest#appPolicy` | passed | – |
| `tools.historyDays` | Keep tool history (days) | `30` | integer 1–365 | SHARED | ToolExecutor | Older tool runs are purged | Applies immediately | `ToolSettingsTest#historyRetention` | passed | – |
| `tools.maxSteps` | Max steps per task | `4` | integer 1–8 | SHARED | CluAgent | Agent loop stops after this many tool steps | Applies immediately | `ToolSettingsTest#maxSteps` | passed | – |
| `tool.time.now.enabled` | Allow time.now | `true` | true / false | SHARED | ToolExecutor | When off, time.now is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.time.now.confirm` | Confirm before time.now | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.calc.enabled` | Allow calc | `true` | true / false | SHARED | ToolExecutor | When off, calc is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.calc.confirm` | Confirm before calc | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.memory.search.enabled` | Allow memory.search | `true` | true / false | SHARED | ToolExecutor | When off, memory.search is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.memory.search.confirm` | Confirm before memory.search | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.memory.remember.enabled` | Allow memory.remember | `true` | true / false | SHARED | ToolExecutor | When off, memory.remember is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.memory.remember.confirm` | Confirm before memory.remember | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.diary.recent.enabled` | Allow diary.recent | `true` | true / false | SHARED | ToolExecutor | When off, diary.recent is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.diary.recent.confirm` | Confirm before diary.recent | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.diary.search.enabled` | Allow diary.search | `true` | true / false | SHARED | ToolExecutor | When off, diary.search is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.diary.search.confirm` | Confirm before diary.search | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.diary.add.enabled` | Allow diary.add | `true` | true / false | SHARED | ToolExecutor | When off, diary.add is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.diary.add.confirm` | Confirm before diary.add | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.persona.switch.enabled` | Allow persona.switch | `true` | true / false | SHARED | ToolExecutor | When off, persona.switch is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.persona.switch.confirm` | Confirm before persona.switch | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.web.search.enabled` | Allow web.search | `true` | true / false | SHARED | ToolExecutor | When off, web.search is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.web.search.confirm` | Confirm before web.search | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.maps.find.enabled` | Allow maps.find | `true` | true / false | SHARED | ToolExecutor | When off, maps.find is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.maps.find.confirm` | Confirm before maps.find | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.maps.distance.enabled` | Allow maps.distance | `true` | true / false | SHARED | ToolExecutor | When off, maps.distance is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.maps.distance.confirm` | Confirm before maps.distance | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.trains.lookup.enabled` | Allow trains.lookup | `true` | true / false | SHARED | ToolExecutor | When off, trains.lookup is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.trains.lookup.confirm` | Confirm before trains.lookup | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.external.ai.enabled` | Allow external.ai | `true` | true / false | SHARED | ToolExecutor | When off, external.ai is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.external.ai.confirm` | Confirm before external.ai | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.app.open.enabled` | Allow app.open | `true` | true / false | SHARED | ToolExecutor | When off, app.open is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.app.open.confirm` | Confirm before app.open | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.alarm.set.enabled` | Allow alarm.set | `true` | true / false | SHARED | ToolExecutor | When off, alarm.set is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.alarm.set.confirm` | Confirm before alarm.set | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.timer.set.enabled` | Allow timer.set | `true` | true / false | SHARED | ToolExecutor | When off, timer.set is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.timer.set.confirm` | Confirm before timer.set | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.device.flashlight.enabled` | Allow device.flashlight | `true` | true / false | SHARED | ToolExecutor | When off, device.flashlight is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.device.flashlight.confirm` | Confirm before device.flashlight | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.device.volume.enabled` | Allow device.volume | `true` | true / false | SHARED | ToolExecutor | When off, device.volume is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.device.volume.confirm` | Confirm before device.volume | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.maps.open.enabled` | Allow maps.open | `true` | true / false | SHARED | ToolExecutor | When off, maps.open is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.maps.open.confirm` | Confirm before maps.open | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.phone.dial.enabled` | Allow phone.dial | `true` | true / false | SHARED | ToolExecutor | When off, phone.dial is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.phone.dial.confirm` | Confirm before phone.dial | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.web.open.enabled` | Allow web.open | `true` | true / false | SHARED | ToolExecutor | When off, web.open is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.web.open.confirm` | Confirm before web.open | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.device.settings.enabled` | Allow device.settings | `true` | true / false | SHARED | ToolExecutor | When off, device.settings is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.device.settings.confirm` | Confirm before device.settings | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.calendar.add.enabled` | Allow calendar.add | `true` | true / false | SHARED | ToolExecutor | When off, calendar.add is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.calendar.add.confirm` | Confirm before calendar.add | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |
| `tool.device.status.enabled` | Allow device.status | `true` | true / false | SHARED | ToolExecutor | When off, device.status is refused | Applies immediately | `ToolSettingsTest#perToolEnable` | passed | – |
| `tool.device.status.confirm` | Confirm before device.status | `"default"` | `default`, `always`, `never` | SHARED | ToolExecutor | Confirmation prompt before execution | Applies immediately | `ToolSettingsTest#perToolConfirm` | passed | – |

## Internet & external services

| Setting ID | Display name | Default | Accepted values | Scope | Responsible component | Observable effect | Applies | Test | Result | Known limitations |
|---|---|---|---|---|---|---|---|---|---|---|
| `net.enabled` | Allow internet access | `true` | true / false | SHARED | NetworkPolicy | All network tools/downloads/external AI are refused | Applies immediately | `NetworkSettingsTest#master` | passed | – |
| `net.web` | Web research | `true` | true / false | SHARED | NetworkPolicy, WebSearchTool | Web search tool permitted | Applies immediately | `NetworkSettingsTest#categories` | passed | – |
| `net.maps` | Maps & places | `true` | true / false | SHARED | NetworkPolicy, MapsTools | Geocode/route tools permitted | Applies immediately | `NetworkSettingsTest#categories` | passed | – |
| `net.travel` | Travel / trains | `true` | true / false | SHARED | NetworkPolicy, TrainTool | Train lookup permitted | Applies immediately | `NetworkSettingsTest#categories` | passed | – |
| `net.downloads` | Model & voice downloads | `true` | true / false | SHARED | NetworkPolicy, Downloader | Downloads permitted | Applies immediately | `NetworkSettingsTest#categories` | passed | – |
| `net.searchProvider` | Search provider | `"duckduckgo"` | `duckduckgo`, `wikipedia`, `brave`, `searxng` | SHARED | SearchService | Selects the provider queried | Applies immediately | `NetworkSettingsTest#searchProvider` | passed | – |
| `net.searchAuto` | Search automatically | `"auto"` | `auto`, `explicit` | SHARED | SearchNeed, CluAgent | A detected need runs web.search before the reply | Applies immediately | `SearchAgentTest#autoSetting` | passed | – |
| `net.searchResults` | Results per search | `4` | integer 3–5 | SHARED | SearchService | count sent to the provider; results passed to the model | Applies immediately | `SearchAgentTest#resultCount` | passed | – |
| `net.searchReadPages` | Read top result pages | `true` | true / false | SHARED | PageReader, RobotsPolicy | Up to 2 page fetches per search after a robots.txt check | Applies immediately | `SearchTest#pageReading` | passed | – |
| `net.searxngUrl` | SearXNG address | `""` | text ≤ 300 chars, validated format | SHARED | SearxngSearch | Searches go to this instance | Applies immediately | `SearchTest#searxng` | passed | – |
| `net.externalAi` | External AI | `"off"` | `off`, `on_request`, `auto` | SHARED | ExternalAiTool, CluAgent | Whether the external AI tool can be used | Applies immediately | `NetworkSettingsTest#externalAi` | passed | – |
| `net.externalAiUrl` | External AI endpoint | `""` | text ≤ 300 chars, validated format | SHARED | ExternalAiTool | Requests go to this endpoint | Applies immediately | `NetworkSettingsTest#externalAi` | passed | – |
| `net.externalAiModel` | External AI model | `""` | text ≤ 100 chars | SHARED | ExternalAiTool | Model field of the request | Applies immediately | `NetworkSettingsTest#externalAi` | passed | – |
| `net.shareMemories` | Share memories with external AI | `false` | true / false | SHARED | ExternalAiTool | Memories stripped from external requests when off | Applies immediately | `NetworkSettingsTest#shareMemories` | passed | – |
| `net.showSources` | Show where answers came from | `true` | true / false | SHARED | ChatUI | Source badge shown under replies | Applies immediately | `NetworkSettingsTest#showSources` | passed | – |
| `net.offlineBehavior` | When offline | `"local_answer"` | `explain`, `local_answer` | SHARED | CluAgent | Offline fallback text/flow | Applies immediately | `NetworkSettingsTest#offlineBehavior` | passed | – |

## Phone & tablet

| Setting ID | Display name | Default | Accepted values | Scope | Responsible component | Observable effect | Applies | Test | Result | Known limitations |
|---|---|---|---|---|---|---|---|---|---|---|
| `device.role` | This device's role | `"portable"` | `home`, `portable` | DEVICE | SyncEngine, RemoteInference | Conflict policy 'prefer home' and remote inference target | Applies immediately | `SyncSettingsTest#role` | passed | – |
| `device.name` | Device name | `""` | text ≤ 40 chars | DEVICE | SyncEngine | Advertised in discovery/pairing | Applies immediately | `SyncSettingsTest#role` | passed | – |
| `sync.enabled` | Synchronisation | `true` | true / false | DEVICE | SyncService | Sync sessions run when a paired peer is reachable | Applies immediately | `SyncSettingsTest#enabled` | passed | – |
| `sync.serve` | Accept connections | `true` | true / false | DEVICE | SyncServer | TCP listener is started | Applies immediately | `SyncSettingsTest#serve` | passed (binding) · device effect unverified | – |
| `sync.discovery` | Discovery | `"auto"` | `auto`, `manual` | DEVICE | PeerLocator | Candidate addresses tried | Applies immediately | `SyncSettingsTest#discovery` | passed | – |
| `sync.manualAddress` | Manual peer address | `""` | text ≤ 80 chars, validated format | DEVICE | PeerLocator | Address tried first | Applies immediately | `SyncSettingsTest#discovery` | passed | – |
| `sync.port` | Sync port | `47823` | integer 1024–65535 | DEVICE | SyncServer, PeerLocator | Listening/connecting port | Applies immediately | `SyncSettingsTest#port` | passed | – |
| `sync.intervalMin` | Auto-sync interval (min) | `5` | integer 0–240 | DEVICE | SyncService | Periodic sync attempts | Applies immediately | `SyncSettingsTest#interval` | passed | – |
| `sync.memories` | Sync memories | `true` | true / false | SHARED | SyncEngine | memories table included | Applies immediately | `SyncSettingsTest#scopes` | passed | – |
| `sync.diary` | Sync diary | `false` | true / false | SHARED | SyncEngine, SyncBundle | diary entries included | Applies immediately | `SyncStage2Test#diaryScope` | passed | – |
| `sync.conversations` | Sync conversation history | `true` | true / false | SHARED | SyncEngine | conversations/messages included | Applies immediately | `SyncSettingsTest#scopes` | passed | – |
| `sync.settings` | Sync shared settings | `true` | true / false | SHARED | SyncEngine | shared settings included | Applies immediately | `SyncSettingsTest#scopes` | passed | – |
| `sync.characters` | Sync characters | `true` | true / false | SHARED | SyncEngine | characters included | Applies immediately | `SyncSettingsTest#scopes` | passed | – |
| `sync.preferences` | Sync learned preferences | `true` | true / false | SHARED | SyncEngine | preferences included | Applies immediately | `SyncSettingsTest#scopes` | passed | – |
| `sync.commands` | Sync custom commands | `true` | true / false | SHARED | SyncEngine | custom commands included | Applies immediately | `SyncSettingsTest#scopes` | passed | – |
| `sync.people` | Sync people (without voiceprints) | `true` | true / false | SHARED | SyncEngine | people included | Applies immediately | `SyncSettingsTest#scopes` | passed | – |
| `sync.conflicts` | Conflict resolution | `"latest"` | `latest`, `home`, `keep_both` | SHARED | SyncEngine | Merge decision for conflicting rows | Applies immediately | `SyncSettingsTest#conflicts` | passed | – |
| `remote.inference` | Use the home device's model | `"fallback"` | `local`, `prefer_home`, `fallback` | DEVICE | InferenceRouter | Selects local vs remote engine | Applies immediately | `SyncSettingsTest#remoteInference` | passed | – |

## LiveKit voice (optional, self-hosted)

| Setting ID | Display name | Default | Accepted values | Scope | Responsible component | Observable effect | Applies | Test | Result | Known limitations |
|---|---|---|---|---|---|---|---|---|---|---|
| `voice.mode` | Conversation engine | `"offline"` | `offline`, `livekit` | DEVICE | Engine, LiveKitSession, LiveConversation | Voice and chat use the selected engine | Applies immediately | `LiveKitSettingsTest#mode` | passed | The Clu server needs a LiveKit server (Cloud or self-hosted), the token endpoint and the Clu agent (docs/LIVEKIT_AGENT.md). |
| `livekit.tokenEndpoint` | Token endpoint 🔒 | `""` | text ≤ 300 chars, validated format | SHARED | TokenProvisioner | Token requests go to this URL | Applies immediately | `LiveKitSettingsTest#tokenEndpoint` | passed | https:// required, except http:// to a private-network address when allowed below. |
| `livekit.agentName` | Agent name | `"clu"` | text ≤ 64 chars, non-empty, validated format | SHARED | TokenProvisioner | Sent in the token request (room_config.agents) | Applies immediately | `LiveKitSettingsTest#agentName` | passed | – |
| `livekit.allowLanCleartext` | Allow unencrypted LAN server 🔒 | `true` | true / false | SHARED | EndpointPolicy | ws:// and http:// accepted for private addresses only | Applies immediately | `LiveKitSettingsTest#lanCleartext` | passed | Audio is always encrypted by WebRTC (DTLS-SRTP); this only concerns the signalling connection and token request. |

## Proactive behaviour

| Setting ID | Display name | Default | Accepted values | Scope | Responsible component | Observable effect | Applies | Test | Result | Known limitations |
|---|---|---|---|---|---|---|---|---|---|---|
| `proactive.enabled` | Proactive conversation | `true` | true / false | SHARED | ProactivePlanner | Planner may produce messages | Applies immediately | `ProactiveSettingsTest#enabled` | passed | – |
| `proactive.maxPerDay` | Max per day | `2` | integer 0–12 | SHARED | ProactivePlanner | Daily cap | Applies immediately | `ProactiveSettingsTest#limits` | passed | – |
| `proactive.minGapHours` | Minimum gap (hours) | `4` | integer 1–48 | SHARED | ProactivePlanner | Spacing between messages | Applies immediately | `ProactiveSettingsTest#limits` | passed | – |
| `proactive.checkIns` | Daily check-ins | `true` | true / false | SHARED | ProactivePlanner | Evening check-in candidate | Applies immediately | `ProactiveSettingsTest#kinds` | passed | – |
| `proactive.followUps` | Follow-ups | `true` | true / false | SHARED | ProactivePlanner | Follow-up candidates from dated memories | Applies immediately | `ProactiveSettingsTest#kinds` | passed | – |
| `proactive.wellbeing` | Gentle well-being check-ins | `true` | true / false | SHARED | ProactivePlanner | Well-being candidate | Applies immediately | `ProactiveSettingsTest#kinds` | passed | – |
| `proactive.greetOnOpen` | Greet when the app opens | `true` | true / false | SHARED | ProactivePlanner | Greeting candidate | Applies immediately | `ProactiveSettingsTest#kinds` | passed | – |
| `proactive.delivery` | Delivery | `"notification"` | `notification`, `speak` | SHARED | ProactiveScheduler | Notification vs speech | Applies immediately | `ProactiveSettingsTest#delivery` | passed (binding) · device effect unverified | – |
| `awareness.battery` | Battery awareness | `true` | true / false | SHARED | ProactivePlanner | Low-battery candidate | Applies immediately | `ProactiveSettingsTest#battery` | passed | – |

## Learning & adaptation

| Setting ID | Display name | Default | Accepted values | Scope | Responsible component | Observable effect | Applies | Test | Result | Known limitations |
|---|---|---|---|---|---|---|---|---|---|---|
| `learning.enabled` | Adapt to my preferences | `true` | true / false | SHARED | PreferenceEngine, PromptBuilder | Preference instructions included in the prompt | Applies immediately | `LearningSettingsTest#enabled` | passed | – |
| `learning.alternatives` | Occasionally offer two styles | `true` | true / false | SHARED | PreferenceEngine | Alternative-response prompts are offered | Applies immediately | `LearningSettingsTest#alternatives` | passed | – |
| `learning.alternativesEvery` | Alternatives at most every N replies | `25` | integer 5–200 | SHARED | PreferenceEngine | Cooldown between alternative offers | Applies immediately | `LearningSettingsTest#alternatives` | passed | – |
| `learning.questions` | Preference questions | `true` | true / false | SHARED | PreferenceEngine | Questions are offered | Applies immediately | `LearningSettingsTest#questions` | passed | – |
| `learning.questionsEvery` | Questions at most every N replies | `15` | integer 3–200 | SHARED | PreferenceEngine | Cooldown between questions | Applies immediately | `LearningSettingsTest#questions` | passed | – |

## Developer & diagnostics

| Setting ID | Display name | Default | Accepted values | Scope | Responsible component | Observable effect | Applies | Test | Result | Known limitations |
|---|---|---|---|---|---|---|---|---|---|---|
| `dev.diagnostics` | Developer diagnostics | `false` | true / false | DEVICE | UI | Diagnostics entry and advanced settings become visible | Applies immediately | `DevSettingsTest#diagnostics` | passed | – |
| `dev.logLevel` | Log level | `"info"` | `error`, `warn`, `info`, `debug` | DEVICE | DiagnosticsLog | Events below the level are not recorded | Applies immediately | `DevSettingsTest#logLevel` | passed | – |
| `dev.logCapacity` | Log capacity (events) | `3000` | integer 200–50000 | DEVICE | DiagnosticsLog | Bounded log size | Applies immediately | `DevSettingsTest#logCapacity` | passed | – |
| `dev.showLatency` | Show timings under replies | `false` | true / false | DEVICE | ChatUI | ASR/model/TTS timings displayed | Applies immediately | `DevSettingsTest#showLatency` | passed | – |
| `dev.toolTraces` | Show tool traces in chat | `true` | true / false | DEVICE | ChatUI | Tool trace lines displayed | Applies immediately | `DevSettingsTest#toolTraces` | passed | – |

🔒 = changing it requires owner verification (PIN / device credential).
