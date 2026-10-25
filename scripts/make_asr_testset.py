#!/usr/bin/env python3
"""Builds Clu's recognition test set: 16 kHz mono WAVs + manifest.json.

Content: common words (including "Apple" as a regression item), names, short commands, numbers and sentences.
Voices: Piper voices with different accents - US (Joe, Arctic bdl, rms), female (Arctic slt), Scottish (Arctic awb)
and Indian-accented English (Arctic ksp, aup, gka; VCTK p251, p376). Conditions: clean, pink noise at 10 dB SNR,
and quiet speech (-26 dB) over a faint noise floor. Real human speech: the LibriSpeech test clips shipped with the
sherpa-onnx models. Synthetic speech is cleaner than a real voice through a phone microphone, so absolute accuracy
here is optimistic; the comparison between recognisers is what this set is for.

usage: make_asr_testset.py <tts voices dir> <dir with sherpa model test_wavs> <out dir>
"""
import json
import sys
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx

ITEMS = [
    ("word", "Apple"), ("word", "Banana"), ("word", "Water"), ("word", "Yes"), ("word", "Stop"), ("word", "Hello"),
    ("word", "Calendar"), ("word", "Weather"),
    ("name", "Call Priya"), ("name", "Message Rahul"), ("name", "Remind Michael at noon"), ("name", "Play music by Arijit Singh"),
    ("command", "Set an alarm for seven tomorrow morning"), ("command", "Turn off the bedroom lights"),
    ("command", "What is the weather tomorrow"), ("command", "Remind me to buy milk"), ("command", "Open my calendar"),
    ("number", "Twenty five"), ("number", "Set a timer for fifteen minutes"), ("number", "My PIN is four three two one"),
    ("number", "The meeting is at three thirty"),
    ("sentence", "I would like an apple and a cup of tea please"),
    ("sentence", "Can you tell me how long it takes to drive to the airport"),
]
VOICES = [  # (label, model dir, speaker id, accent)
    ("joe", "vits-piper-en_US-joe-medium", 0, "US"), ("bdl", "vits-piper-en_US-arctic-medium", 7, "US"),
    ("rms", "vits-piper-en_US-arctic-medium", 1, "US"), ("slt", "vits-piper-en_US-arctic-medium", 2, "US female"),
    ("awb", "vits-piper-en_US-arctic-medium", 0, "Scottish"), ("ksp", "vits-piper-en_US-arctic-medium", 3, "Indian"),
    ("aup", "vits-piper-en_US-arctic-medium", 13, "Indian"), ("gka", "vits-piper-en_US-arctic-medium", 17, "Indian"),
    ("p251", "vits-piper-en_GB-vctk-medium", 92, "Indian"),
]


def tts(d: Path):
    onnx = next(d.glob("*.onnx"))
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(onnx), tokens=str(d / "tokens.txt"), data_dir=str(d / "espeak-ng-data")),
        num_threads=4)))


def to16k(x, sr):
    if sr == 16000:
        return x.astype(np.float32)
    return np.interp(np.linspace(0, len(x) - 1, int(len(x) * 16000 / sr)), np.arange(len(x)), x).astype(np.float32)


def pink(n, rng):
    w = rng.standard_normal(n)
    f = np.fft.rfft(w); f[1:] /= np.sqrt(np.arange(1, len(f))); f[0] = 0
    p = np.fft.irfft(f, n)
    return (p / (np.std(p) + 1e-9)).astype(np.float32)


def save(p: Path, x):
    with wave.open(str(p), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())


def read(p: Path):
    with wave.open(str(p)) as w:
        x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768
        return to16k(x, w.getframerate())


def main():
    V, T, OUT = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    OUT.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(7)
    clips, engines = [], {}
    lead, tail = np.zeros(int(0.6 * 16000), np.float32), np.zeros(int(0.4 * 16000), np.float32)
    for label, model, sid, accent in VOICES:
        e = engines.get(model) or engines.setdefault(model, tts(V / model))
        for kind, text in ITEMS:
            a = e.generate(text, sid=sid, speed=1.0)
            speech = to16k(np.asarray(a.samples), a.sample_rate)
            speech = 0.5 * speech / (np.max(np.abs(speech)) + 1e-9)  # peak -6 dBFS
            base = np.concatenate([lead, speech, tail])
            sp_rms = np.sqrt(np.mean(speech ** 2))
            for cond in ("clean", "noise10", "quiet"):
                if cond == "clean":
                    x = base
                elif cond == "noise10":
                    x = base + pink(len(base), rng) * sp_rms / (10 ** (10 / 20))
                else:
                    x = base * 10 ** (-26 / 20) + pink(len(base), rng) * sp_rms * 10 ** (-26 / 20) / (10 ** (20 / 20))
                name = f"{label}_{cond}_{len(clips):04d}"
                save(OUT / f"{name}.wav", x)
                clips.append({"file": f"{name}.wav", "ref": text, "kind": kind, "voice": label, "accent": accent, "cond": cond,
                              "speech_onset_s": len(lead) / 16000, "real": False})
    # Real human speech (LibriSpeech test clips with transcripts).
    for d in sorted(T.glob("*/test_wavs")):
        tr = d / "trans.txt"
        if not tr.exists():
            continue
        for line in tr.read_text().splitlines():
            if " " not in line.strip():
                continue
            f, ref = line.split(" ", 1)
            if not (d / f).exists() or f == "8k.wav":
                continue
            name = f"real_{d.parent.name[:24]}_{f}"
            if any(c["file"] == name for c in clips):
                continue
            save(OUT / name, read(d / f))
            clips.append({"file": name, "ref": ref.strip(), "kind": "real", "voice": "human", "accent": "US", "cond": "clean",
                          "speech_onset_s": None, "real": True})
    (OUT / "manifest.json").write_text(json.dumps(clips, indent=1))
    print(f"{len(clips)} clips")


if __name__ == "__main__":
    main()
