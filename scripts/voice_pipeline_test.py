#!/usr/bin/env python3
"""Host-side verification of Clu's offline voice stack with the exact models/config the app uses.

Runs sherpa-onnx (same version as the Android JNI libs) on x86_64:
  1. Piper TTS (en_US-ryan-medium) synthesis + real-time factor
  2. Streaming zipformer ASR (int8) on the synthesised speech -> word accuracy, latency
  3. Silero VAD segmentation of speech separated by silence
  4. Keyword spotting of the wake word "CLUE" with the gigaspeech KWS model
  5. Speaker embeddings (3D-Speaker CAM++): same-voice vs different-voice similarity
Numbers are host CPU measurements, NOT phone/tablet measurements.

usage: voice_pipeline_test.py <models dir> [report.json]
"""
import json
import sys
import time
from pathlib import Path

import numpy as np
import sherpa_onnx

M = Path(sys.argv[1])
out = {}
THREADS = 2


def resample(x, sr_from, sr_to=16000):
    if sr_from == sr_to:
        return x
    n = int(len(x) * sr_to / sr_from)
    return np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)


def tts_engine(voice):
    d = M / voice
    onnx = next(d.glob("*.onnx"))
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(onnx), tokens=str(d / "tokens.txt"), data_dir=str(d / "espeak-ng-data")),
            num_threads=THREADS),
        max_num_sentences=1)
    return sherpa_onnx.OfflineTts(cfg)


# 1. TTS ------------------------------------------------------------------------------------------
tts = tts_engine("vits-piper-en_US-ryan-medium")
lines = [
    "Clue, what is the distance from Pune to Mumbai?",
    "I have analysed your schedule. You are overcommitted on Friday, and I suggest moving the dentist appointment.",
]
t0 = time.time()
audio = tts.generate(lines[1], sid=0, speed=0.92)
dt = time.time() - t0
dur = len(audio.samples) / audio.sample_rate
out["tts"] = {"voice": "en_US-ryan-medium", "sample_rate": audio.sample_rate, "audio_seconds": round(dur, 2),
              "synthesis_seconds": round(dt, 3), "real_time_factor": round(dt / dur, 3)}
t0 = time.time()
first = tts.generate("I have analysed your schedule.", sid=0, speed=0.92)
out["tts"]["first_sentence_ms"] = round((time.time() - t0) * 1000)

# 2. ASR ------------------------------------------------------------------------------------------
asr_dir = M / "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
rec = sherpa_onnx.OnlineRecognizer.from_transducer(
    tokens=str(asr_dir / "tokens.txt"), encoder=str(asr_dir / "encoder-epoch-99-avg-1.int8.onnx"),
    decoder=str(asr_dir / "decoder-epoch-99-avg-1.int8.onnx"), joiner=str(asr_dir / "joiner-epoch-99-avg-1.int8.onnx"),
    num_threads=THREADS, sample_rate=16000, feature_dim=80, enable_endpoint_detection=True,
    rule1_min_trailing_silence=2.4, rule2_min_trailing_silence=0.65, rule3_min_utterance_length=20, decoding_method="greedy_search")


def wer(ref, hyp):
    r, h = ref.lower().replace(",", "").replace("?", "").replace(".", "").split(), hyp.lower().split()
    d = np.zeros((len(r) + 1, len(h) + 1), dtype=int)
    d[:, 0] = range(len(r) + 1); d[0, :] = range(len(h) + 1)
    for i in range(1, len(r) + 1):
        for j in range(1, len(h) + 1):
            d[i, j] = min(d[i - 1, j] + 1, d[i, j - 1] + 1, d[i - 1, j - 1] + (r[i - 1] != h[j - 1]))
    return d[len(r), len(h)] / max(1, len(r))


asr_results = []
for line in lines:
    a = tts.generate(line, sid=0, speed=1.0)
    x = resample(np.array(a.samples, dtype=np.float32), a.sample_rate)
    s = rec.create_stream()
    s.accept_waveform(16000, np.zeros(16000, dtype=np.float32))  # app primes streams with 1 s of silence
    t_feed = time.time()
    chunk = 1600  # 100 ms, like the app's capture buffer
    for i in range(0, len(x), chunk):
        s.accept_waveform(16000, x[i:i + chunk])
        while rec.is_ready(s):
            rec.decode_stream(s)
    t_last_audio = time.time()
    s.accept_waveform(16000, np.zeros(int(0.66 * 16000), dtype=np.float32))
    s.input_finished()
    while rec.is_ready(s):
        rec.decode_stream(s)
    text = rec.get_result(s).strip()
    asr_results.append({"reference": line, "hypothesis": text, "wer": round(wer(line, text), 3),
                        "audio_seconds": round(len(x) / 16000, 2), "processing_seconds": round(t_last_audio - t_feed, 3),
                        "finalize_ms_after_audio": round((time.time() - t_last_audio) * 1000)})
out["asr"] = {"model": asr_dir.name + " (int8)", "results": asr_results}

# 3. VAD ------------------------------------------------------------------------------------------
vcfg = sherpa_onnx.VadModelConfig()
vcfg.silero_vad.model = str(M / "silero_vad.onnx")
vcfg.silero_vad.threshold = 0.5
vcfg.silero_vad.min_silence_duration = 0.65
vcfg.silero_vad.min_speech_duration = 0.25
vcfg.sample_rate = 16000
vad = sherpa_onnx.VoiceActivityDetector(vcfg, buffer_size_in_seconds=30)
a1 = resample(np.array(tts.generate(lines[0]).samples, dtype=np.float32), audio.sample_rate)
sil = np.zeros(16000, dtype=np.float32)
noise = (np.random.default_rng(0).standard_normal(len(sil)) * 0.003).astype(np.float32)
signal = np.concatenate([sil + noise, a1, sil + noise, a1, sil + noise])
segments = []
for i in range(0, len(signal), 512):
    vad.accept_waveform(signal[i:i + 512])
    while not vad.empty():
        seg = vad.front
        segments.append((round(seg.start / 16000, 2), round(len(seg.samples) / 16000, 2)))
        vad.pop()
out["vad"] = {"expected_segments": 2, "detected_segments": len(segments), "segments_start_dur_s": segments}

# 4. KWS ------------------------------------------------------------------------------------------
kws_dir = M / "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
import sentencepiece as spm
sp = spm.SentencePieceProcessor(model_file=str(kws_dir / "bpe.model"))
WAKE = ["CLUE", "HEY CLUE", "BUDDY", "FRIEND"]  # app defaults (primary + secondary wake words)
kw_tokens = {w: " ".join(sp.encode(w, out_type=str)) for w in WAKE}
kws_file = Path("/tmp/clu_kws.txt"); kws_file.write_text("".join(f"{t} @{w.replace(' ', '_')}\n" for w, t in kw_tokens.items()))
kws = sherpa_onnx.KeywordSpotter(
    tokens=str(kws_dir / "tokens.txt"), encoder=str(kws_dir / "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
    decoder=str(kws_dir / "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"), joiner=str(kws_dir / "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
    keywords_file=str(kws_file), num_threads=1, keywords_threshold=0.1, keywords_score=1.5)  # app default


def spot(text):
    a = tts.generate(text, sid=0, speed=1.0)
    x = np.concatenate([np.zeros(8000, dtype=np.float32), resample(np.array(a.samples, dtype=np.float32), a.sample_rate), np.zeros(16000, dtype=np.float32)])
    s = kws.create_stream()
    hits = []
    for i in range(0, len(x), 1600):
        s.accept_waveform(16000, x[i:i + 1600])
        while kws.is_ready(s):
            kws.decode_stream(s)
            r = kws.get_result(s)
            if r:
                hits.append(r); kws.reset_stream(s)
    return hits


positives = ["Clue, open YouTube.", "Hey Clue, what time is it?", "Buddy, are you there?"]
negatives = ["Please open the window for me.", "The glue is drying on the table.", "I have a clue about the answer, maybe."]
out["kws"] = {"threshold": 0.1, "keyword_tokens": kw_tokens,
              "positives": {t: spot(t) for t in positives}, "negatives": {t: spot(t) for t in negatives}}

# 5. Speaker embeddings ---------------------------------------------------------------------------
ext = sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(
    model=str(M / "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"), num_threads=THREADS))


def embed(engine, text):
    a = engine.generate(text, sid=0, speed=1.0)
    s = ext.create_stream()
    s.accept_waveform(a.sample_rate, a.samples)
    s.input_finished()
    return np.array(ext.compute(s))


def cos(a, b):
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))


alan = tts_engine("vits-piper-en_GB-alan-medium")
joe = tts_engine("vits-piper-en_US-joe-medium")
r1 = embed(tts, "My name is Aishwarjya and this is my voice for Clu.")
r2 = embed(tts, "Set an alarm for seven thirty tomorrow morning please.")
a2 = embed(alan, "Set an alarm for seven thirty tomorrow morning please.")
j2 = embed(joe, "Set an alarm for seven thirty tomorrow morning please.")
out["speaker"] = {"model": "3dspeaker CAM++ voxceleb", "same_voice": round(cos(r1, r2), 3),
                  "different_voice_alan": round(cos(r1, a2), 3), "different_voice_joe": round(cos(r1, j2), 3),
                  "default_threshold": 0.55}

print(json.dumps(out, indent=2))
if len(sys.argv) > 2:
    Path(sys.argv[2]).write_text(json.dumps(out, indent=2))
