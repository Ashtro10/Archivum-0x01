# Architecture

## 1. One entity, several execution environments

| Concept | Where it lives | Notes |
|---|---|---|
| **Clu's identity** | `kv_meta.entity_id`, shared settings (`identity.*`, `persona.*`), memories, preferences, characters, people, commands, conversation history | Synchronised. During pairing the portable device adopts the home device's entity id, so both run the same Clu; any earlier history merges. |
| **Execution environment** | `kv_meta.device_id`, `DEVICE`-scoped settings (role, model, context, threads, sync port…), model files, voice packs, voiceprints, PIN | Never synchronised. The tablet and phone may have different models and capabilities. |
| **Models and engines** | `LlmEngine` (local llama.cpp, home-device remote engine), `TtsBackend`, ASR, KWS, VAD | Interchangeable components behind interfaces. |
| **Companion characters** | `characters` table, memory scope = character id | Separate fictional personas. They never modify Clu's identity; private memory only with explicit permission. |

Internal modules (agent, memory, tools, sync…) are software components acting for the single Clu entity. There is
no multi-agent swarm.

## 2. Modules

```
┌──────────────────────────────── app (Android) ────────────────────────────────┐
│ UI (framework Views)   VoiceSession ── AudioCapture/AudioPlayer               │
│ ListeningService (FGS)   SherpaEngines (VAD, KWS, ASR, TTS, speaker)          │
│ AndroidTools (apps, alarms, torch…)   AndroidAsr / AndroidTts                  │
│ SyncManager (TCP server, mDNS, gateway)   ProactiveScheduler (JobScheduler)    │
│ AndroidPlatform: SQLite driver, Keystore vault, HTTP, thermal/RAM/CPU info     │
└──────────────────────────────────┬────────────────────────────────────────────┘
                                   │ CluCore (composition root)
┌──────────────────────────────── core (pure Kotlin) ───────────────────────────┐
│ config: Settings registry, SettingsStore (reactive, validated, snapshots)     │
│ agent: CluAgent, IntentRouter, MemoryRetriever, InsightProposer               │
│ llm: LlmService, LlamaEngine (JNI), ModelRegistry, GgufReader                 │
│ memory: MemoryRepository (BM25 index), ProposalRepository                     │
│ conversation, persona (prompt builder, characters, templates), learning       │
│ tools: ToolRegistry/Executor (permissions, network policy, confirmation, log) │
│ security: people, voiceprints, CredentialStore, AuthorizationPolicy           │
│ voice: VoiceConfig, TurnManager, WakePolicy, ClapDetector, NoiseEstimator,     │
│        SentenceChunker, VoiceEffect, SentencePiece tokenizer                   │
│ sync: SecureChannel, SyncEngine (change log, conflicts), SyncNode (TCP, RPC)  │
│ proactive, diag (log, perf, improvement advisor), db (SqlDriver, Schema)      │
└───────────────────────────────────────────────────────────────────────────────┘
native/: libclu_llama.so = llama.cpp (CPU, armv8.2-a+dotprod+fp16) + JNI bridge
```

`core` contains all business logic and SQL; it is tested on the JVM against real SQLite (sqlite-jdbc), the real
llama.cpp engine (host build of the same JNI code) and real TCP sockets. `app` only adds Android integrations.

## 3. A conversational turn

1. **Input**: typed text, or voice: `AudioCapture` (16 kHz, AEC/NS) → Silero VAD + noise estimator + clap detector →
   KWS wake word (keywords tokenised from the configured phrases) → streaming zipformer ASR (stream primed with 1 s
   of silence + 0.4 s pre-roll; end of speech = VAD silence) → speaker embedding (optional) → `AuthorizationPolicy`.
2. **CluAgent.handle**: termination phrase → shutdown (owner only) → owner's custom commands → deterministic
   `IntentRouter` for explicit device commands (no model call; instant) → otherwise the model.
3. **Model path**: `MemoryRetriever` (BM25 + size-scaled threshold, persona scopes, private-memory check) →
   `PromptBuilder` (personality, style settings, learned preferences, provenance-labelled memories, tools, offline
   flag) → `InferenceRouter` (local model, or the home device's model per `remote.inference`) → history trimmed to
   the context window → streaming generation (`<tool>{…}</tool>` calls executed through `ToolExecutor`, results fed
   back, max steps) → reply persisted with metadata (source, tools, timings).
4. **Output**: text streams to the UI; sentences go to `SpeechOutput` while the model is still generating; playback
   through one `AudioPlayer` (pitch, volume, metallic effect). Barge-in stops playback and generation.
5. **After the turn**: preference question or A/B alternatives (text only, rate-limited), memory proposals (never
   saved without approval), diary entry at session end.

## 4. Persistence

SQLite (`clu.db`), schema owned by `core/db/Schema.kt` (versioned migrations). Key tables: `settings`,
`settings_snapshots`, `conversations`, `messages`, `memories`, `memory_terms`/`memory_stats` (BM25 index),
`memory_proposals`, `preferences`, `characters`, `people`, `voiceprints`, `credentials`, `custom_commands`,
`tool_runs`, `models`, `sync_peers`, `change_log`, `sync_history`, `diag_events`, `perf_samples`.
Conversation history (`messages`) is separate from derived memory records (`memories`). Memories keep the original
text, an optional summary and provenance (`OWNER_SUPPLIED`, `OWNER_INSTRUCTION`, `CONVERSATION`,
`APPROVED_INFERENCE`, `IMPORTED`). Deletions are tombstones so they synchronise.

## 5. Synchronisation (phone ↔ tablet)

* **Transport**: TCP (port 47823), listening only while a device is paired or the pairing window is open, max 4 concurrent connections. Peers are found by manual address, mDNS (`_clu-sync._tcp`), last known address
  and the **default gateway** (on the phone's hotspot the phone *is* the tablet's gateway, which works even when the
  hotspot blocks multicast).
* **Pairing**: ephemeral ECDH P-256; both devices display a 6-digit code derived from the shared secret and the
  handshake transcript; the owner confirms they match (protects against man-in-the-middle). Long-term P-256 identity
  keys are stored wrapped by an Android Keystore AES-GCM key.
* **Sessions**: ephemeral + static-static ECDH → HKDF → per-direction AES-256-GCM keys; mutual MAC proves possession
  of the paired identity keys; frames carry strictly increasing nonces (replay/reorder rejected).
* **Data**: every change to a synchronised table appends to `change_log` with a hybrid logical clock (HLC). A
  session pushes rows changed since the peer's last acknowledged sequence in chunks of 200, each acknowledged and
  applied atomically, so an interrupted sync resumes without loss; re-delivery is idempotent. Conflicts: latest
  edit wins (default), home device wins, or keep both (memories). Device-scoped settings, voiceprints and PINs
  never leave the device.
* **Remote inference**: the same authenticated channel carries `infer` requests; the home device streams tokens
  back; cancellation uses a second authenticated connection. If the tablet is unreachable the phone uses its own
  model (or reports that none is loaded).

## 6. Security

* PIN / security phrase: salted PBKDF2-HMAC-SHA256 (60k iterations), temporary lockout (30 s doubling, max 15 min),
  never permanent. Device screen lock (BiometricPrompt with device credential) as an alternative.
* `AuthorizationPolicy`: actors (owner, trusted person, guest, unknown) × sensitivity (routine, personal, config,
  sensitive). Voice recognition only identifies the likely speaker; sensitive actions always need fresh verification.
* Characters get no device tools or private memory unless the owner grants it (granting private memory requires
  verification). External AI receives only the owner's words unless memory sharing is enabled.
* Diagnostics log redacts PINs, keys and bearer tokens; API keys are stored encrypted with the Keystore.

## 7. Configuration

All settings are declared once in `Settings.kt` (type, default, validation, scope, component, effect, apply mode,
sensitivity, limitations, verifying test). The same registry drives persistence, validation, the generated settings
UI, synchronisation and [SETTINGS_REGISTER.md](SETTINGS_REGISTER.md). Components observe values through flows;
voice and model services rebuild when relevant keys change. UI and service decisions go through tested policy
functions (`UiPolicy`, `ServicePolicy`, `AppPolicy`). Snapshots and a known-good configuration allow rollback.

## 8. Self-improvement (foundation)

Tool runs (success, verification, errors, duration) and performance samples are recorded; `ImprovementAdvisor`
turns them into suggestions shown in Tool history. Changes are applied by the owner through settings (versioned,
reversible). No autonomous code modification or on-device retraining.

## 9. Build pipeline

Gradle with Kotlin/JVM modules; `buildSrc` tasks: aapt2 compile/link (binary manifest + resources), D8 (min API 33),
packaging with 4-byte alignment of stored entries, apksig v1–v3 signing + verification, `apiCheck` (all platform
references must exist in the API 33 android.jar), `verifyApk` (manifest components, JNI symbols, assets).
