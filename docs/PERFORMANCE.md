# Performance report

**No phone or tablet was available to the build environment. No on-device number below is measured; none is
invented.** What *was* measured is on an x86_64 build container (4 vCPU, JDK 21) and is labelled *host*. Raw data:
[`measurements/voice_pipeline_host.json`](measurements/voice_pipeline_host.json),
[`measurements/core_host.json`](measurements/core_host.json).

## 1. Measured on the host

### Voice models (sherpa-onnx 1.13.8, same models as the app, 2 threads)
| Metric | Result |
|---|---|
| Piper TTS (Ryan medium) real-time factor | 0.10–0.18 (5.5 s of audio in 0.56–0.94 s) |
| Piper TTS first sentence (5 words) | 150–167 ms |
| Streaming ASR processing (int8) | 0.12 s for 2.2 s audio; 0.32 s for 5.2 s audio (RTF ≈ 0.06) |
| ASR finalisation after end of audio | 34–47 ms |
| ASR word error rate (synthetic speech) | 0.33–0.35 (first word weak; mitigated by stream priming) |
| VAD segmentation | 2 / 2 utterances found |
| Wake word (threshold 0.1) | 3 / 3 leading wake phrases detected; 1 of 3 mid-sentence negatives triggered without the leading-word rule (rejected by it) |
| Speaker embeddings | same voice 0.97 vs different voice 0.11 / 0.89 (synthetic voices) |

### Core (JVM + sqlite-jdbc)
| Metric | Result |
|---|---|
| Insert one memory incl. index update | 5.3 ms |
| Retrieve relevant memories among 2,000 (dense synthetic text) | 44 ms |
| Build a system prompt incl. retrieval | 27 ms |
| Write one setting | 1.6 ms |
| Database size with 2,000 memories | 7.6 MB |
| Sync 1,000 memories over localhost TCP (encrypted) | 1.19 s |

Optimisations made from these measurements: batched document-length lookups (retrieval 76 → 44 ms),
one transaction per received sync batch (sync 4.1 → 1.2 s).

### APK and storage
| Item | Size |
|---|---|
| APK | 58.4 MB |
| `libonnxruntime.so` / `libsherpa-onnx-jni.so` / `libclu_llama.so` | 22.2 / 4.8 / 6.3 MB (uncompressed) |
| Bundled ASR / KWS / VAD models | 43.6 / 5.6 / 0.6 MB |
| Clu voice pack / speaker model (optional) | 82 MB / 30 MB on disk |
| 3B Q4_K_M model (user-supplied) | ≈ 2.1 GB |

## 2. Design choices for sustained use on mid-range phones

* **Memory:** model memory-mapped; one model loaded at a time; idle auto-unload (default 20 min); RAM check before
  loading; `onTrimMemory` releases models and voice engines; memories retrieved by index (never the whole DB);
  bounded logs (default 3,000 events), perf samples (5,000), tool history (30 days), snapshots (30).
* **CPU/thermal:** threads = performance cores (≥ 70 % of the top core's max frequency: 4 on SD870, 5 on SD 8 Gen 2);
  thermal guard halves threads and shortens replies at `THERMAL_STATUS_SEVERE`; KV-cache prefix reuse avoids
  re-processing conversation history; explicit commands bypass the model entirely.
* **Battery:** wake detection uses the 3.3M-parameter keyword spotter (not the LLM, not the full ASR); the LLM
  never runs in the background; background listening is opt-in with a visible notification; sync is periodic only
  while the app runs; proactive checks are batched by JobScheduler (~hourly).
* **Voice latency:** streaming ASR (decoded while you speak), VAD end-of-speech (650 ms default), sentence-level TTS
  starts at the first complete sentence, KWS → ASR hand-over without re-capture.
* **Storage:** models resumable through DownloadManager; voice packs extract only needed files; nothing duplicates
  model files; raw audio is never stored (only voiceprint embeddings).

## 3. Targets and how to measure them on the devices

| Metric | Initial target | Measure with |
|---|---|---|
| End of speech → first audio (simple exchange) | ≈ 1 s with a 1.5B model, ≤ 2 s with 3B (to be confirmed) | Developer diagnostics → *Show timings*; Diagnostics → `first_audio_ms` |
| Time to first token | < 1 s for short prompts with cache reuse | AI models → *Benchmark*; Diagnostics → `first_token_ms` |
| Generation speed | ≥ 8 tokens/s for 3B Q4 on SD 8 Gen 2 (expected lower on SD870) | `tokens_per_s` |
| Model load | < 10 s (warm file cache) | `model_load_ms` |
| Cold start of the app | < 1.5 s to first screen | `app_create_ms` |
| ASR finalisation | < 300 ms | `asr_finalize_ms` |
| Peak memory with 3B @ 4k context | < 3.2 GB process PSS | Diagnostics → Device & resources |
| Background listening battery cost | to be measured over 1 h with the screen off | Android battery usage |

These targets are engineering goals pending measurement, not results. After a few days of use, Tool history →
*Suggestions* turns the collected samples into concrete tuning advice.
