#!/usr/bin/env python3
"""Ranks offline voices for Clu by measured depth, using sherpa-onnx 1.13.8 (the runtime in the app).

For every model and speaker: synthesises representative Clu replies, measures median F0 (fundamental frequency of
voiced frames, YIN; adult male speech is typically ~85-155 Hz, female ~165-255 Hz), F0 spread (10th-90th percentile in
semitones; very low spread = monotone/robotic), first-audio latency and real-time factor. Multi-speaker models are
screened with one sentence first; the deepest speakers get the full evaluation. Optionally checks intelligibility by
transcribing the audio back with whisper.cpp (whisper-cli + ggml model) and reports the word error rate.

Writes WAV samples of the full evaluation for listening. Host x86 measurements, not phone numbers.

usage: scan_voices.py <dir with vits-piper-*/kitten-*/kokoro-*/supertonic dirs> <out dir> [--whisper-cli BIN --whisper-model M]
       [--lang en|hi] [--top 4]
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import time
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx

EN = ["Good evening. All systems are running normally, and I have nothing urgent to report.",
      "Your meeting with the design team starts at nine tomorrow morning. I will remind you an hour before.",
      "I could not reach the thermostat. Check that it is powered on, and I will try again in a minute."]
HI = ["नमस्ते। सभी सिस्टम सामान्य रूप से चल रहे हैं।",
      "कल सुबह नौ बजे आपकी मीटिंग है। मैं एक घंटा पहले याद दिला दूँगा।",
      "मैं थर्मोस्टेट से संपर्क नहीं कर पाया। कृपया जाँच लें कि वह चालू है।"]


def f0_track(x: np.ndarray, sr: int) -> np.ndarray:
    """F0 of voiced 50 ms frames with YIN (cumulative-mean-normalised difference, threshold 0.15, 60-400 Hz,
    parabolic refinement). Same method as core PitchAnalysis.kt; medians agree with librosa pYIN within 1-10 Hz."""
    x = x.astype(np.float64)
    frame, hop = int(0.05 * sr), int(0.01 * sr)
    tmin, tmax = int(sr / 400), int(sr / 60)
    w = frame - tmax
    rms_all = np.sqrt(np.mean(x ** 2)) + 1e-9
    out = []
    for i in range(0, len(x) - frame, hop):
        seg = x[i:i + frame]
        if np.sqrt(np.mean(seg ** 2)) < 0.3 * rms_all:
            continue
        a = seg[:w]
        d = np.array([0.0] + [np.sum((a - seg[t:t + w]) ** 2) for t in range(1, tmax + 1)])
        cs = np.cumsum(d[1:])
        cm = np.ones_like(d)
        cm[1:] = d[1:] * np.arange(1, tmax + 1) / np.maximum(cs, 1e-12)
        t = tmin
        while t < tmax and cm[t] >= 0.15:
            t += 1
        if t >= tmax:
            continue
        while t + 1 < tmax and cm[t + 1] < cm[t]:
            t += 1
        if cm[t] > 0.35:
            continue
        p, b, c = cm[t - 1], cm[t], cm[t + 1]
        dd = p - 2 * b + c
        out.append(sr / (t + (0.5 * (p - c) / dd if dd else 0.0)))
    return np.array(out)


def pitch_stats(x, sr):
    f = f0_track(x, sr)
    if len(f) < 20:
        return None, None
    p10, p50, p90 = np.percentile(f, [10, 50, 90])
    return float(p50), float(12 * np.log2(p90 / p10))


def load(d: Path, threads: int):
    j = next(d.glob("*.onnx.json"), None)
    if j is not None or next(d.glob("*.onnx"), None) and (d / "espeak-ng-data").exists() and not (d / "voices.bin").exists():
        onnx = next(p for p in d.glob("*.onnx"))
        cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(onnx), tokens=str(d / "tokens.txt"), data_dir=str(d / "espeak-ng-data")),
            num_threads=threads))
        names = {}
        if j is not None:
            m = json.loads(j.read_text())
            names = {v: k for k, v in (m.get("speaker_id_map") or {}).items()}
        return sherpa_onnx.OfflineTts(cfg), names, "piper"
    if (d / "voices.bin").exists() and "kokoro" in d.name:
        onnx = next(d.glob("model*.onnx"))
        cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
            kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(model=str(onnx), voices=str(d / "voices.bin"), tokens=str(d / "tokens.txt"),
                                                           data_dir=str(d / "espeak-ng-data")), num_threads=threads))
        # Speaker order of kokoro-en-v0_19 (sherpa-onnx model card).
        names = dict(enumerate(["af", "af_bella", "af_nicole", "af_sarah", "af_sky", "am_adam", "am_michael", "bf_emma", "bf_isabella",
                                "bm_george", "bm_lewis"]))
        return sherpa_onnx.OfflineTts(cfg), names, "kokoro"
    if (d / "voices.bin").exists():
        onnx = next(d.glob("model*.onnx"))
        cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
            kitten=sherpa_onnx.OfflineTtsKittenModelConfig(model=str(onnx), voices=str(d / "voices.bin"), tokens=str(d / "tokens.txt"),
                                                           data_dir=str(d / "espeak-ng-data")), num_threads=threads))
        return sherpa_onnx.OfflineTts(cfg), {}, "kitten"
    raise ValueError(f"unknown model layout: {d}")


def synth(tts, text, sid):
    t0 = time.perf_counter(); first = [None]

    def cb(samples, progress):
        if first[0] is None:
            first[0] = time.perf_counter() - t0
        return 1
    a = tts.generate(text, sid=sid, speed=1.0, callback=cb)
    total = time.perf_counter() - t0
    return np.asarray(a.samples, dtype=np.float32), a.sample_rate, (first[0] or total), total


def save(p: Path, x, sr):
    with wave.open(str(p), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())


def wer(ref: str, hyp: str) -> float:
    n = lambda s: re.sub(r"[^\w\s]", " ", s.lower()).split()
    a, b = n(ref), n(hyp)
    d = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(b) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (a[i - 1] != b[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(b)] / max(1, len(a))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("models"); ap.add_argument("out")
    ap.add_argument("--whisper-cli"); ap.add_argument("--whisper-model")
    ap.add_argument("--lang", default="en"); ap.add_argument("--top", type=int, default=4); ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--only", default="")
    a = ap.parse_args()
    OUT = Path(a.out); OUT.mkdir(parents=True, exist_ok=True)
    texts = EN if a.lang == "en" else HI
    results = []
    for d in sorted(Path(a.models).iterdir()):
        if not d.is_dir() or (a.only and not any(o in d.name for o in a.only.split(","))):
            continue
        try:
            tts, names, kind = load(d, a.threads)
        except Exception as e:
            print("skip", d.name, e); continue
        n = tts.num_speakers
        # Screen all speakers with one sentence (multi-speaker models), keep the deepest ones.
        screen = []
        for sid in range(n):
            x, sr, _, _ = synth(tts, texts[0], sid)
            f0, spread = pitch_stats(x, sr)
            if f0 is not None:
                screen.append((f0, sid))
        screen.sort()
        chosen = [sid for f0, sid in screen[: a.top if n > 1 else 1]]
        for sid in chosen:
            synth(tts, "Ready.", sid)  # warm-up
            xs, firsts, totals, durs = [], [], [], []
            for i, t in enumerate(texts):
                x, sr, first, total = synth(tts, t, sid)
                xs.append(x); firsts.append(first); totals.append(total); durs.append(len(x) / sr)
            x = np.concatenate(xs)
            f0, spread = pitch_stats(x, sr)
            name = f"{d.name}-sid{sid}"
            wav = OUT / f"{name}.wav"; save(wav, x, sr)
            r = {"model": d.name, "kind": kind, "sid": sid, "speaker": names.get(sid, ""), "speakers_in_model": n,
                 "median_f0_hz": round(f0, 1) if f0 else None, "f0_spread_semitones": round(spread, 1) if spread else None,
                 "first_audio_ms_median": round(1000 * float(np.median(firsts))), "rtf": round(sum(totals) / sum(durs), 3),
                 "sample_rate": sr, "size_mb": round(sum(p.stat().st_size for p in d.rglob("*") if p.is_file()) / 1e6, 1), "wav": wav.name}
            if a.whisper_cli and a.whisper_model:
                w16 = OUT / f"{name}.16k.wav"
                y = np.interp(np.linspace(0, len(x) - 1, int(len(x) * 16000 / sr)), np.arange(len(x)), x).astype(np.float32)
                save(w16, y, 16000)
                env = dict(os.environ, LD_LIBRARY_PATH=str(Path(a.whisper_cli).parent))
                hyp = subprocess.run([a.whisper_cli, "-m", a.whisper_model, "-f", str(w16), "-l", a.lang, "-nt", "-t", "4", "-np"],
                                     capture_output=True, text=True, env=env).stdout.strip()
                r["asr"] = hyp; r["wer"] = round(wer(" ".join(texts), hyp), 3)
                w16.unlink()
            results.append(r)
            print(json.dumps(r, ensure_ascii=False), flush=True)
    results.sort(key=lambda r: r["median_f0_hz"] or 999)
    (OUT / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
