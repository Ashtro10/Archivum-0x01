#!/usr/bin/env python3
"""Evaluates offline TTS voices for Clu with the same sherpa-onnx version the app bundles (1.13.8).

Per voice/speaker: model load time, time to first audio for a short phrase (via the streaming callback), real-time
factor, how incrementally audio arrives for a multi-sentence input (callback count, first vs total), median pitch F0
(an objective proxy for "deep"; also identifies speaker gender), and optional Hindi intelligibility by transcribing
the synthesised audio back with a Whisper model. Writes WAV samples for listening.

Host measurements (x86-64 container) - NOT phone numbers.

usage: eval_tts.py <models dir> <out dir> [--asr <sherpa-onnx-whisper dir>] [--threads 4]
"""
from __future__ import annotations

import argparse
import json
import time
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx

EN_SHORT = "Sure, let me check that for you."
EN_LONG = ("The reactor is stable and all systems are running normally. "
           "I have scheduled the maintenance check for tomorrow morning, and I will remind you an hour before it starts.")
HI_SHORT = "ठीक है, मैं अभी देखता हूँ।"
HI_LONG = "आज मौसम साफ़ रहेगा। शाम को हल्की बारिश हो सकती है, इसलिए छाता साथ रखना।"
MIX = "Kal meeting ke liye mujhe नौ बजे remind karna."


def piper(d: Path, threads: int):
    onnx = next(p for p in d.glob("*.onnx"))
    return sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(onnx), tokens=str(d / "tokens.txt"), data_dir=str(d / "espeak-ng-data")),
        num_threads=threads))


def kitten(d: Path, threads: int):
    onnx = next(p for p in d.glob("model*.onnx"))
    return sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        kitten=sherpa_onnx.OfflineTtsKittenModelConfig(model=str(onnx), voices=str(d / "voices.bin"), tokens=str(d / "tokens.txt"),
                                                       data_dir=str(d / "espeak-ng-data")),
        num_threads=threads))


def supertonic(d: Path, threads: int):
    f = lambda pat: str(next(d.glob(pat)))
    return sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        supertonic=sherpa_onnx.OfflineTtsSupertonicModelConfig(
            duration_predictor=f("duration_predictor*.onnx"), text_encoder=f("text_encoder*.onnx"),
            vector_estimator=f("vector_estimator*.onnx"), vocoder=f("vocoder*.onnx"), tts_json=f("tts.json"),
            unicode_indexer=f("unicode_indexer.bin"), voice_style=f("voice.bin")),
        num_threads=threads))


def median_f0(x: np.ndarray, sr: int) -> float | None:
    """Median fundamental frequency of voiced frames (autocorrelation, 60-320 Hz)."""
    frame, hop = int(0.04 * sr), int(0.01 * sr)
    lo, hi = int(sr / 320), int(sr / 60)
    rms_all = np.sqrt(np.mean(x ** 2)) + 1e-9
    f0s = []
    for i in range(0, len(x) - frame, hop):
        w = x[i:i + frame] * np.hanning(frame)
        if np.sqrt(np.mean(w ** 2)) < 0.3 * rms_all:
            continue
        ac = np.correlate(w, w, "full")[frame - 1:]
        if ac[0] <= 0:
            continue
        lag = lo + int(np.argmax(ac[lo:hi]))
        if ac[lag] / ac[0] > 0.45:  # clearly periodic = voiced
            f0s.append(sr / lag)
    return float(np.median(f0s)) if len(f0s) > 10 else None


def save(path: Path, x: np.ndarray, sr: int):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())


def synth(tts, text: str, sid: int, lang: str | None):
    """Returns (samples, sr, first_audio_s, total_s, n_callbacks)."""
    t0 = time.perf_counter(); first = [None]; n = [0]

    def cb(samples, progress):
        if first[0] is None:
            first[0] = time.perf_counter() - t0
        n[0] += 1
        return 1  # 1 = continue (sherpa-onnx 1.13.8: returning 0 stops after the current sentence, despite the docstring)

    if lang is not None:
        cfg = sherpa_onnx.GenerationConfig(); cfg.sid = sid; cfg.speed = 1.0; cfg.extra = {"lang": lang}
        a = tts.generate(text, cfg, callback=cb)
    else:
        a = tts.generate(text, sid=sid, speed=1.0, callback=cb)
    total = time.perf_counter() - t0
    x = np.asarray(a.samples, dtype=np.float32)
    return x, a.sample_rate, first[0] if first[0] is not None else total, total, n[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("models"); ap.add_argument("out")
    ap.add_argument("--asr"); ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--only", default="", help="comma-separated substrings of voice names to evaluate")
    a = ap.parse_args()
    M, OUT = Path(a.models), Path(a.out); OUT.mkdir(parents=True, exist_ok=True)
    asr = None
    if a.asr:
        d = Path(a.asr)
        enc = next(d.glob("*encoder.int8.onnx")); dec = next(d.glob("*decoder.int8.onnx")); tok = next(d.glob("*tokens.txt"))
        # sherpa-onnx decodes Whisper tokens one by one and drops tokens that hold partial UTF-8 sequences (common in
        # Devanagari). Work around it: a token table whose entries decode to "<id>" markers; rebuild the bytes ourselves.
        import base64
        raw, marked = {}, OUT / "marked-tokens.txt"
        with open(tok) as f, open(marked, "w") as g:
            for line in f:
                b64, i = line.rsplit(" ", 1); i = int(i); raw[i] = base64.b64decode(b64)
                g.write(base64.b64encode(f"<{i}>".encode()).decode() + f" {i}\n")
        asr_cfg = dict(encoder=str(enc), decoder=str(dec), tokens=str(marked), num_threads=a.threads, task="transcribe")

        def detok(text: str) -> str:
            import re
            return b"".join(raw[int(m)] for m in re.findall(r"<(\d+)>", text)).decode("utf-8", errors="replace").strip()

        def transcribe(x, sr, lang):
            rec = sherpa_onnx.OfflineRecognizer.from_whisper(language=lang, **asr_cfg)
            if sr != 16000:
                x = np.interp(np.linspace(0, len(x) - 1, int(len(x) * 16000 / sr)), np.arange(len(x)), x).astype(np.float32)
            s = rec.create_stream(); s.accept_waveform(16000, x); rec.decode_stream(s)
            return detok(s.result.text)
        asr = transcribe

    voices = [
        ("piper-ryan-medium (Clu current)", piper, "vits-piper-en_US-ryan-medium", [0], ["en"]),
        ("kitten-nano-v0.8-int8", kitten, "kitten-nano-en-v0_8-int8", list(range(8)), ["en"]),
        ("kitten-mini-v0.8", kitten, "kitten-mini-en-v0_8", list(range(8)), ["en"]),
        ("piper-hi-rohan-medium", piper, "vits-piper-hi_IN-rohan-medium", [0], ["hi"]),
        ("piper-hi-pratham-medium", piper, "vits-piper-hi_IN-pratham-medium", [0], ["hi"]),
        ("supertonic-3-int8", supertonic, "sherpa-onnx-supertonic-3-tts-int8-2026-05-11", list(range(10)), ["en", "hi"]),
    ]
    results = []
    only = [o for o in a.only.split(",") if o]
    for name, maker, dirname, sids, langs in voices:
        if only and not any(o in name for o in only):
            continue
        d = M / dirname
        if not d.exists():
            print("skip (missing):", name); continue
        t0 = time.perf_counter(); tts = sherpa_onnx.OfflineTts(maker(d, a.threads)); load_s = time.perf_counter() - t0
        size_mb = sum(p.stat().st_size for p in d.rglob("*") if p.is_file()) / 1e6
        for sid in sids:
            r = {"voice": name, "sid": sid, "load_s": round(load_s, 2), "size_mb": round(size_mb, 1)}
            for lang in langs:
                short, long_ = (EN_SHORT, EN_LONG) if lang == "en" else (HI_SHORT, HI_LONG)
                use_lang = lang if maker is supertonic else None
                synth(tts, short, sid, use_lang)  # warm-up
                x, sr, first, tot, _ = synth(tts, short, sid, use_lang)
                xl, srl, first_l, tot_l, ncb = synth(tts, long_, sid, use_lang)
                r[lang] = {"short_first_audio_ms": round(first * 1000), "short_total_ms": round(tot * 1000),
                           "short_audio_s": round(len(x) / sr, 2), "long_first_audio_ms": round(first_l * 1000),
                           "long_total_ms": round(tot_l * 1000), "long_audio_s": round(len(xl) / srl, 2),
                           "long_callbacks": ncb, "rtf": round(tot_l / max(len(xl) / srl, 1e-6), 3),
                           "f0_hz": median_f0(xl, srl), "sample_rate": srl}
                save(OUT / f"{dirname}-sid{sid}-{lang}.wav", xl, srl)
                if asr is not None and lang == "hi":
                    r[lang]["asr_roundtrip"] = asr(xl, srl, "hi")
                if maker is supertonic and lang == "hi":
                    xn, srn, *_ = synth(tts, long_, sid, None)  # without the language hint, for comparison
                    if asr is not None:
                        r[lang]["asr_roundtrip_no_lang_hint"] = asr(xn, srn, "hi")
            if maker is supertonic:
                xm, srm, fm, tm, _ = synth(tts, MIX, sid, "hi")
                r["mixed"] = {"total_ms": round(tm * 1000), "audio_s": round(len(xm) / srm, 2)}
                save(OUT / f"{dirname}-sid{sid}-mixed.wav", xm, srm)
                if asr is not None:
                    r["mixed"]["asr_roundtrip"] = asr(xm, srm, "hi")
            results.append(r)
            print(json.dumps(r, ensure_ascii=False), flush=True)
    (OUT / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
