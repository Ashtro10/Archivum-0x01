#!/usr/bin/env python3
"""Builds the STT test clips (16 kHz mono WAV + manifest.json) used by WhisperIntegrationTest.

English: two real human recordings from the sherpa-onnx test set (LibriSpeech) and a synthetic short command.
Hindi and mixed Hindi/English: synthetic speech (Piper hi_IN Pratham + en_US Ryan), because no licensed real Hindi
recordings are available in the build container. Synthetic speech is cleaner than real speech, so accuracy on these
clips is an upper bound; real-device testing with the owner's voice is still required.

usage: make_stt_fixtures.py <models dir> <out dir>
"""
import json
import sys
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx


def piper(d: Path):
    onnx = next(d.glob("*.onnx"))
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(onnx), tokens=str(d / "tokens.txt"), data_dir=str(d / "espeak-ng-data")),
        num_threads=4)))


def to16k(x: np.ndarray, sr: int) -> np.ndarray:
    if sr == 16000:
        return x.astype(np.float32)
    n = int(len(x) * 16000 / sr)
    return np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)


def read(p: Path) -> np.ndarray:
    with wave.open(str(p)) as w:
        x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768
        return to16k(x, w.getframerate())


def save(p: Path, x: np.ndarray):
    with wave.open(str(p), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())


def main():
    M, OUT = Path(sys.argv[1]), Path(sys.argv[2]); OUT.mkdir(parents=True, exist_ok=True)
    en, hi = piper(M / "vits-piper-en_US-ryan-medium"), piper(M / "vits-piper-hi_IN-pratham-medium")
    tts = lambda engine, text: to16k(np.asarray(engine.generate(text, sid=0, speed=1.0).samples), engine.sample_rate)
    pad = np.zeros(4000, dtype=np.float32)
    clips = []

    def add(name, lang, kind, audio, ref):
        save(OUT / f"{name}.wav", np.concatenate([pad, audio, pad]))
        clips.append({"name": name, "lang": lang, "kind": kind, "file": f"{name}.wav", "ref": ref, "seconds": round((len(audio) + 8000) / 16000, 2)})

    tw = M / "sherpa-onnx-whisper-base" / "test_wavs"
    refs = dict(line.split(" ", 1) for line in (tw / "trans.txt").read_text().splitlines() if line.strip())
    add("en_real_short", "en", "real", read(tw / "0.wav"), refs["0.wav"].strip())
    add("en_real_long", "en", "real", read(tw / "1.wav"), refs["1.wav"].strip())
    add("en_cmd", "en", "synthetic", tts(en, "Set an alarm for seven tomorrow morning."), "Set an alarm for seven tomorrow morning.")
    add("hi_cmd", "hi", "synthetic", tts(hi, "कल सुबह सात बजे का अलार्म लगा दो।"), "कल सुबह सात बजे का अलार्म लगा दो")
    add("hi_question", "hi", "synthetic", tts(hi, "आज शाम को मौसम कैसा रहेगा?"), "आज शाम को मौसम कैसा रहेगा")
    gap = np.zeros(1200, dtype=np.float32)
    mixed = np.concatenate([tts(hi, "कल की"), gap, tts(en, "meeting"), gap, tts(hi, "के लिए मुझे नौ बजे याद दिलाना।")])
    add("mixed_cmd", "mixed", "synthetic", mixed, "कल की meeting के लिए मुझे नौ बजे याद दिलाना")
    (OUT / "manifest.json").write_text(json.dumps(clips, ensure_ascii=False, indent=1))
    print(json.dumps(clips, ensure_ascii=False))


if __name__ == "__main__":
    main()
