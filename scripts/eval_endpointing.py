#!/usr/bin/env python3
"""Turn-taking test: sentences with a thinking pause in the middle. Replays Clu's endpointing on real VAD decisions.

Old app: the turn ends when the Silero VAD reports 650 ms of silence (VAD min_silence = 0.65 s).
New app (Endpointer.kt): VAD hangover 0.25 s; the turn ends after 700 ms of silence, or 1400 ms when the live words so far
end in an unfinished phrase ("and", "the", "to", ...); live words come from the streaming recogniser.
Reports how often a sentence is cut in two (premature turn end) and the delay from the real end of speech to the end
of the turn.

usage: eval_endpointing.py <tts voices dir> <asr model dir> <silero_vad.onnx>
"""
import sys
from pathlib import Path

import numpy as np
import sherpa_onnx

SR, FRAME = 16000, 480
CONT = {"and", "or", "but", "so", "because", "the", "a", "an", "to", "of", "for", "with", "in", "on", "at", "my", "your", "is", "are",
        "was", "were", "if", "that", "then", "than", "about", "from", "into", "like", "um", "uh", "er", "hmm", "please", "also", "which",
        "when", "where", "who", "what", "how", "can", "could", "would", "should", "will", "i", "we", "you", "it's", "its", "this", "these"}
PAIRS = [("Remind me to", "buy milk tomorrow"), ("Set an alarm for", "seven in the morning"), ("Call Priya and", "tell her I'm late"),
         ("What is the weather", "in Delhi tomorrow"), ("Turn off the", "bedroom lights"), ("I would like a cup of tea", "and some biscuits"),
         ("Play some music", "by Arijit Singh"), ("Add eggs bread and", "coffee to my list"), ("Can you tell me", "how far the airport is"),
         ("Book a table for two", "at eight tonight")]
VOICES = [("vits-piper-en_US-joe-medium", 0), ("vits-piper-en_US-arctic-medium", 3), ("vits-piper-en_US-arctic-medium", 2)]


def tts(d):
    onnx = next(d.glob("*.onnx"))
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(onnx), tokens=str(d / "tokens.txt"), data_dir=str(d / "espeak-ng-data")), num_threads=4)))


def gen(e, text, sid):
    a = e.generate(text, sid=sid)
    x = np.asarray(a.samples, np.float32)
    x = np.interp(np.linspace(0, len(x) - 1, int(len(x) * SR / a.sample_rate)), np.arange(len(x)), x).astype(np.float32)
    nz = np.nonzero(np.abs(x) > 0.01)[0]  # trim TTS leading/trailing silence so the pause length is exact
    return 0.5 * x[nz[0]:nz[-1] + 1] / (np.max(np.abs(x)) + 1e-9)


def vad(model, min_sil):
    c = sherpa_onnx.VadModelConfig(); c.silero_vad.model = model; c.silero_vad.threshold = 0.5
    c.silero_vad.min_silence_duration = min_sil; c.silero_vad.min_speech_duration = 0.2; c.silero_vad.window_size = 512; c.sample_rate = SR
    return sherpa_onnx.VoiceActivityDetector(c, buffer_size_in_seconds=30)


def unfinished(p):
    t = p.strip().lower()
    return bool(t) and t[-1] not in ".?!" and t.split()[-1].strip(",") in CONT


def trace(x, vad_model, rec, min_sil):
    """Per-frame (time, VAD speech, live partial) for one clip."""
    v = vad(vad_model, min_sil)
    s = rec.create_stream(); s.accept_waveform(SR, np.zeros(SR, np.float32))
    out = []
    for i in range(0, len(x) - FRAME + 1, FRAME):
        f = x[i:i + FRAME]; t = (i + FRAME) / SR
        v.accept_waveform(f)
        s.accept_waveform(SR, f)
        while rec.is_ready(s): rec.decode_stream(s)
        out.append((t, v.is_speech_detected(), rec.get_result(s)))
    return out


def policy_old(tr):
    in_sp, ends = False, []
    for t, sp, _ in tr:
        if sp and not in_sp: in_sp = True
        if in_sp and not sp: in_sp = False; ends.append(t)
    return ends


def policy_new(tr, base, cont, hang=0.25):
    in_sp, last_sp, ends = False, 0.0, []
    for t, sp, partial in tr:
        if sp: last_sp = t; in_sp = True
        elif in_sp:
            silence = t - last_sp + hang  # the VAD flips to silence `hang` seconds after the last voiced frame
            if silence >= (cont if unfinished(partial) else base): in_sp = False; ends.append(t)
    return ends


def main():
    V, M, VAD = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
    f = lambda p: str(sorted(M.glob(p), key=lambda q: ("int8" not in q.name, len(q.name)))[0])
    rec = sherpa_onnx.OnlineRecognizer.from_transducer(tokens=str(M / "tokens.txt"), encoder=f("encoder*.onnx"), decoder=f("decoder*.onnx"),
                                                        joiner=f("joiner*.onnx"), num_threads=2, sample_rate=SR, feature_dim=80)
    engines = {}
    res = {}
    for vd, sid in VOICES:
        e = engines.get(vd) or engines.setdefault(vd, tts(V / vd))
        for a, b in PAIRS:
            xa, xb = gen(e, a, sid), gen(e, b, sid)
            for pause in (0.4, 0.7, 1.0):
                x = np.concatenate([np.zeros(int(0.5 * SR), np.float32), xa, np.zeros(int(pause * SR), np.float32), xb, np.zeros(int(2.5 * SR), np.float32)])
                speech_end = (0.5 * SR + len(xa) + pause * SR + len(xb)) / SR
                variants = {f"vad {int(ms * 1000)}": policy_old(trace(x, VAD, rec, ms)) for ms in (0.5, 0.65, 0.8, 1.0, 1.2)}
                for mode, ends in variants.items():
                    split = len([t for t in ends if t < speech_end])
                    final = [t for t in ends if t >= speech_end]
                    r = res.setdefault((mode, pause), {"n": 0, "split": 0, "delay": []})
                    r["n"] += 1; r["split"] += 1 if split else 0
                    if final: r["delay"].append(final[0] - speech_end)
    for (mode, pause), r in sorted(res.items()):
        d = np.array(r["delay"]) * 1000
        print(f"{mode:<13} pause {int(pause * 1000):>4} ms: sentence cut in two {r['split']:>2}/{r['n']}  end-of-turn delay p50 {np.percentile(d, 50):.0f} ms p90 {np.percentile(d, 90):.0f} ms")


if __name__ == "__main__":
    main()
