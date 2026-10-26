"""Local (no-cloud) speech components for the Clu agent: sherpa-onnx speech recognition and voice, as LiveKit Agents
plugins. Used by CLU_STACK=local.

SherpaSTT recognises each utterance after the VAD closes it (the framework's StreamAdapter does the segmenting),
with the Kroko streaming English transducer (13% WER on Clu's test set vs 64% for the old 20M model; see
docs/SPEECH_RECOGNITION.md). SherpaTTS speaks one sentence at a time with a Piper or Kokoro voice; the framework
streams sentences as the LLM produces them.
"""
from __future__ import annotations

import asyncio
import uuid
from pathlib import Path

import numpy as np
import sherpa_onnx
from livekit import rtc
from livekit.agents import APIConnectOptions, stt, tts, utils
from livekit.agents.types import DEFAULT_API_CONNECT_OPTIONS, NOT_GIVEN, NotGivenOr

ASR_RATE = 16000
TAIL_SEC = 1.5  # Kroko emits the last word only after ~1.5 s of look-ahead (measured)


class SherpaSTT(stt.STT):
    def __init__(self, model_dir: Path, threads: int = 2) -> None:
        super().__init__(capabilities=stt.STTCapabilities(streaming=False, interim_results=False))
        d = Path(model_dir)
        self._rec = sherpa_onnx.OnlineRecognizer.from_transducer(
            tokens=str(d / "tokens.txt"), encoder=str(d / "encoder.onnx"), decoder=str(d / "decoder.onnx"),
            joiner=str(d / "joiner.onnx"), num_threads=threads, sample_rate=ASR_RATE, feature_dim=80, decoding_method="greedy_search")
        self._lock = asyncio.Lock()

    @property
    def model(self) -> str:
        return "sherpa-onnx kroko-en"

    @property
    def provider(self) -> str:
        return "local"

    def transcribe(self, samples: np.ndarray) -> str:
        s = self._rec.create_stream()
        s.accept_waveform(ASR_RATE, np.zeros(ASR_RATE // 4, dtype=np.float32))  # stable features before speech
        s.accept_waveform(ASR_RATE, samples)
        s.accept_waveform(ASR_RATE, np.zeros(int(ASR_RATE * TAIL_SEC), dtype=np.float32))
        s.input_finished()
        while self._rec.is_ready(s):
            self._rec.decode_stream(s)
        return self._rec.get_result(s).strip().lower()

    async def _recognize_impl(self, buffer: utils.AudioBuffer, *, language: NotGivenOr[str] = NOT_GIVEN,
                              conn_options: APIConnectOptions = DEFAULT_API_CONNECT_OPTIONS) -> stt.SpeechEvent:
        frame = rtc.combine_audio_frames(buffer)
        pcm = np.frombuffer(frame.data, dtype=np.int16).astype(np.float32) / 32768.0
        if frame.num_channels > 1:
            pcm = pcm.reshape(-1, frame.num_channels).mean(axis=1)
        if frame.sample_rate != ASR_RATE:
            # Band-limited resampling (the VAD hands over 16 kHz already in practice; this covers other rates).
            r = rtc.AudioResampler(frame.sample_rate, ASR_RATE, num_channels=1, quality=rtc.AudioResamplerQuality.HIGH)
            data = (np.clip(pcm, -1, 1) * 32767).astype(np.int16)
            out = r.push(rtc.AudioFrame(data.tobytes(), frame.sample_rate, 1, len(data))) + r.flush()
            pcm = np.concatenate([np.frombuffer(f.data, dtype=np.int16) for f in out]).astype(np.float32) / 32768.0 if out else np.zeros(0, np.float32)
        async with self._lock:
            text = await asyncio.get_running_loop().run_in_executor(None, self.transcribe, pcm)
        return stt.SpeechEvent(type=stt.SpeechEventType.FINAL_TRANSCRIPT, request_id=uuid.uuid4().hex,
                               alternatives=[stt.SpeechData(language="en", text=text)])


class SherpaTTS(tts.TTS):
    def __init__(self, model_dir: Path, speaker: int = 0, speed: float = 1.0, threads: int = 4) -> None:
        d = Path(model_dir)
        if (d / "voices.bin").exists():  # Kokoro
            cfg = sherpa_onnx.OfflineTtsModelConfig(kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
                model=str(next(d.glob("model*.onnx"))), voices=str(d / "voices.bin"), tokens=str(d / "tokens.txt"),
                data_dir=str(d / "espeak-ng-data")), num_threads=threads)
        else:  # Piper (VITS)
            cfg = sherpa_onnx.OfflineTtsModelConfig(vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=str(next(p for p in d.glob("*.onnx"))), tokens=str(d / "tokens.txt"), data_dir=str(d / "espeak-ng-data")),
                num_threads=threads)
        self._tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=cfg, max_num_sentences=1))
        super().__init__(capabilities=tts.TTSCapabilities(streaming=False), sample_rate=self._tts.sample_rate, num_channels=1)
        self.speaker, self.speed = speaker, speed
        self._lock = asyncio.Lock()

    @property
    def model(self) -> str:
        return "sherpa-onnx"

    @property
    def provider(self) -> str:
        return "local"

    def synthesize(self, text: str, *, conn_options: APIConnectOptions = DEFAULT_API_CONNECT_OPTIONS) -> tts.ChunkedStream:
        return _SherpaChunk(tts=self, input_text=text, conn_options=conn_options)

    def render(self, text: str) -> bytes:
        a = self._tts.generate(text, sid=self.speaker, speed=self.speed)
        return (np.clip(np.asarray(a.samples, dtype=np.float32), -1, 1) * 32767).astype("<i2").tobytes()


class _SherpaChunk(tts.ChunkedStream):
    async def _run(self, output_emitter: tts.AudioEmitter) -> None:
        t: SherpaTTS = self._tts  # type: ignore[assignment]
        output_emitter.initialize(request_id=uuid.uuid4().hex, sample_rate=t.sample_rate, num_channels=1, mime_type="audio/pcm")
        async with t._lock:
            pcm = await asyncio.get_running_loop().run_in_executor(None, t.render, self._input_text)
        output_emitter.push(pcm)
        output_emitter.flush()
