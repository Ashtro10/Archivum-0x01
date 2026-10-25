#!/usr/bin/env python3
"""Evaluates offline recognisers for Clu on the test set from make_asr_testset.py, replaying Clu's capture pipeline.

Pipeline replay (VoiceSession): 30 ms frames -> Silero VAD (same settings as the app) -> at speech onset a streaming
stream is created and primed with 1 s of silence plus the last PRE-ROLL of audio from the ring buffer -> frames are
fed while speaking -> at end of speech (VAD silence) 0.5 s of silence is appended and the stream is finalised.
Modes:
  full     - the whole clip goes to the recogniser (upper bound, no pipeline losses)
  app0.4   - the app before this fix (0.4 s pre-roll at VAD onset)
  app1.0   - 1.0 s pre-roll
  masked   - speech starts 0.15 s after the mic opens while the activation tone keeps the VAD masked for 0.45 s
             (the app before this fix ignored speech during its own playback), pre-roll 0.4 s
Also reports VAD onset delay, finalisation latency and real-time factor. Whisper models run through whisper-cli.

usage: eval_asr.py <testset dir> <out json> --model NAME=DIR[:type] ... [--whisper NAME=MODEL] [--modes full,app0.4,...]
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import tempfile
import time
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx

SR = 16000
FRAME = 480


def read(p):
    with wave.open(str(p)) as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768


def norm_words(s: str):
    s = s.lower().replace("'", "")
    num = {"0": "zero", "1": "one", "2": "two", "3": "three", "4": "four", "5": "five", "6": "six", "7": "seven", "8": "eight", "9": "nine"}
    s = re.sub(r"(\d)", lambda m: " " + num[m.group(1)] + " ", s)
    return re.sub(r"[^a-z\s]", " ", s).split()


def wer(ref, hyp):
    a, b = norm_words(ref), norm_words(hyp)
    d = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(b) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (a[i - 1] != b[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(b)], max(1, len(a))


def find(d: Path, pat: str):
    c = sorted(d.glob(pat), key=lambda p: ("int8" not in p.name, len(p.name)))
    return str(c[0]) if c else None


def recognizer(d: Path, threads=2):
    enc, dec, joi = find(d, "encoder*.onnx"), find(d, "decoder*.onnx"), find(d, "joiner*.onnx")
    return sherpa_onnx.OnlineRecognizer.from_transducer(tokens=str(d / "tokens.txt"), encoder=enc, decoder=dec, joiner=joi,
                                                        num_threads=threads, sample_rate=SR, feature_dim=80, decoding_method="greedy_search")


def vad_make(vad_model):
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = vad_model
    cfg.silero_vad.threshold = 0.5
    cfg.silero_vad.min_silence_duration = 0.65
    cfg.silero_vad.min_speech_duration = 0.2
    cfg.silero_vad.window_size = 512
    cfg.silero_vad.max_speech_duration = 25
    cfg.sample_rate = SR
    return sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=30)


TAIL = {"": 0.5}


def run_stream(rec, audio_parts, prime=True, tail=0.5):
    s = rec.create_stream()
    if prime:
        s.accept_waveform(SR, np.zeros(SR, np.float32))
    for a in audio_parts:
        s.accept_waveform(SR, a)
        while rec.is_ready(s):
            rec.decode_stream(s)
    t0 = time.perf_counter()
    s.accept_waveform(SR, np.zeros(int(SR * tail), np.float32))
    s.input_finished()
    while rec.is_ready(s):
        rec.decode_stream(s)
    fin = (time.perf_counter() - t0) * 1000
    return rec.get_result(s).strip(), fin


def app_replay(rec, x, vad_model, preroll, lead_cut=None, mask_s=0.0, tail=0.5):
    """Returns (text, onset_delay_s or None, finalise_ms, utterance audio)."""
    if lead_cut is not None:
        x = x[lead_cut:]
    vad = vad_make(vad_model)
    ring = np.zeros(4 * SR, np.float32); filled = 0
    speech, stream_parts, onset_at, utt = False, [], None, []
    for i in range(0, len(x) - FRAME + 1, FRAME):
        f = x[i:i + FRAME]
        ring = np.roll(ring, -FRAME); ring[-FRAME:] = f; filled = min(4 * SR, filled + FRAME)
        vad.accept_waveform(f)
        t = (i + FRAME) / SR
        is_sp = vad.is_speech_detected() and not (t < mask_s)
        if is_sp and not speech:
            speech = True; onset_at = t
            n = min(int(preroll * SR), filled)
            stream_parts = [ring[-n:].copy()]
            utt = [ring[-min(int(0.3 * SR), filled):].copy()]
            continue
        if speech and is_sp:
            stream_parts.append(f); utt.append(f)
        if speech and not is_sp:
            break
    if not stream_parts:
        return "", None, 0.0, np.zeros(0, np.float32)
    text, fin = run_stream(rec, stream_parts, tail=tail)
    return text, onset_at, fin, np.concatenate(utt)


def whisper_text(cli, model, audio, lang="en"):
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
        p = f.name
    with wave.open(p, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(audio, -1, 1) * 32767).astype("<i2").tobytes())
    env = dict(os.environ, LD_LIBRARY_PATH=str(Path(cli).parent))
    t0 = time.perf_counter()
    out = subprocess.run([cli, "-m", model, "-f", p, "-l", lang, "-nt", "-t", "4", "-np"], capture_output=True, text=True, env=env).stdout
    ms = (time.perf_counter() - t0) * 1000
    os.unlink(p)
    return out.strip(), ms


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("testset"); ap.add_argument("out")
    ap.add_argument("--model", action="append", default=[]); ap.add_argument("--whisper", action="append", default=[])
    ap.add_argument("--whisper-cli"); ap.add_argument("--vad", required=True)
    ap.add_argument("--modes", default="full,app0.4,app1.0,masked"); ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()
    T = Path(a.testset)
    clips = json.loads((T / "manifest.json").read_text())
    if a.limit:
        clips = clips[:: max(1, len(clips) // a.limit)]
    modes = a.modes.split(",")
    rows = []
    for spec in a.model:
        name, d = spec.split("=", 1)
        tail = 0.5
        if "@" in d:
            d, tail = d.split("@"); tail = float(tail)
        t0 = time.perf_counter(); rec = recognizer(Path(d)); load_ms = (time.perf_counter() - t0) * 1000
        size = sum(p.stat().st_size for p in Path(d).glob("*.onnx") if ("int8" in p.name) or not list(Path(d).glob("*int8*.onnx")))
        for c in clips:
            x = read(T / c["file"])
            for m in modes:
                if c["real"] and m == "masked":
                    continue
                t1 = time.perf_counter()
                if m == "full":
                    text, fin = run_stream(rec, [x], tail=tail); onset = None
                elif m == "masked":
                    text, onset, fin, _ = app_replay(rec, x, a.vad, 0.4, lead_cut=int(0.45 * SR), mask_s=0.45, tail=tail)
                else:
                    text, onset, fin, _ = app_replay(rec, x, a.vad, float(m[3:]), tail=tail)
                proc = (time.perf_counter() - t1) * 1000
                e, n = wer(c["ref"], text)
                rows.append({**c, "model": name, "mode": m, "hyp": text, "err": e, "n": n, "finalise_ms": round(fin, 1),
                             "proc_ms": round(proc), "audio_s": round(len(x) / SR, 2), "onset_delay_s": None if onset is None or c["speech_onset_s"] is None
                             else round(onset - (c["speech_onset_s"] - (0.45 if m == "masked" else 0)), 3), "load_ms": round(load_ms), "model_mb": round(size / 1e6, 1)})
        print("done", name, flush=True)
    for spec in a.whisper:
        name, model = spec.split("=", 1)
        for c in clips:
            x = read(T / c["file"])
            text, ms = whisper_text(a.whisper_cli, model, x)
            e, n = wer(c["ref"], text)
            rows.append({**c, "model": name, "mode": "full", "hyp": text, "err": e, "n": n, "proc_ms": round(ms), "audio_s": round(len(x) / SR, 2)})
        print("done", name, flush=True)
    Path(a.out).write_text(json.dumps(rows, indent=0))
    # Summary
    from collections import defaultdict
    agg = defaultdict(lambda: [0, 0, 0, 0.0])
    for r in rows:
        for key in [(r["model"], r["mode"], "ALL"), (r["model"], r["mode"], r["cond"] if not r["real"] else "real"),
                    (r["model"], r["mode"], "kind:" + r["kind"]), (r["model"], r["mode"], "accent:" + r["accent"])]:
            g = agg[key]; g[0] += r["err"]; g[1] += r["n"]; g[2] += 1; g[3] += r["proc_ms"] / max(0.1, r["audio_s"]) / 1000
    for k in sorted(agg):
        g = agg[k]
        print(f"{k[0]:<14} {k[1]:<7} {k[2]:<18} WER {100 * g[0] / g[1]:5.1f}%  n={g[2]:<4} rtf={g[3] / g[2]:.3f}")
    fins = defaultdict(list)
    for r in rows:
        if r.get("finalise_ms") is not None and r["mode"] != "full":
            fins[(r["model"], r["mode"])].append(r["finalise_ms"])
    for k, v in sorted(fins.items()):
        print(f"FINALISE {k[0]:<14} {k[1]:<7} p50 {np.percentile(v, 50):.0f} ms  p90 {np.percentile(v, 90):.0f} ms")
    ons = [r["onset_delay_s"] for r in rows if r.get("onset_delay_s") is not None and r["mode"] == "app1.0"]
    if ons:
        print(f"VAD ONSET DELAY p50 {np.percentile(ons, 50) * 1000:.0f} ms p90 {np.percentile(ons, 90) * 1000:.0f} ms max {max(ons) * 1000:.0f} ms (n={len(ons)})")
    apple = [r for r in rows if r["ref"] == "Apple"]
    for m in sorted({(r["model"], r["mode"]) for r in apple}):
        hs = [r["hyp"] for r in apple if (r["model"], r["mode"]) == m]
        ok = sum(1 for h in hs if norm_words(h) == ["apple"])
        print(f"APPLE {m[0]:<14} {m[1]:<7} {ok}/{len(hs)} correct; wrong: {sorted(set(h for h in hs if norm_words(h) != ['apple']))[:8]}")


if __name__ == "__main__":
    main()
