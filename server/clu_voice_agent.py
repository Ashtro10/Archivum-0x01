"""Clu voice agent: the ears and mouth of Clu on the owner's own LiveKit server.

It joins the room of one Clu device, transcribes the device's microphone with sherpa-onnx (offline, on this
server), sends each finished utterance to Clu as a `clu.transcript` data message, and speaks Clu's streamed
`clu.reply` sentences with a Piper voice. Clu's brain - model, memory, tools, permissions - stays on the device;
this agent never generates replies itself.

Barge-in: if the owner starts speaking while the agent is talking, playback stops at once and Clu is told to
cancel the reply (`clu.interrupt`).

No cloud service and no paid subscription is involved. Runs on CPU.
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import sherpa_onnx
from livekit import rtc

log = logging.getLogger("clu.agent")

PROTOCOL_VERSION = 1
T_TRANSCRIPT, T_INTERRUPT, T_SPOKEN = "clu.transcript", "clu.interrupt", "clu.spoken"
T_STATE, T_PARTIAL, T_REPLY = "clu.agent_state", "clu.partial", "clu.reply"
ASR_RATE = 16000


@dataclass
class Models:
    """Paths of the sherpa-onnx models (see fetch_models.sh)."""
    root: Path
    asr_dir: str = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
    tts_dir: str = "vits-piper-en_US-ryan-medium"
    vad_file: str = "silero_vad.onnx"
    threads: int = 2

    def recognizer(self) -> sherpa_onnx.OnlineRecognizer:
        d = self.root / self.asr_dir
        enc = next(d.glob("encoder*.int8.onnx")); dec = next(d.glob("decoder*.int8.onnx")); join = next(d.glob("joiner*.int8.onnx"))
        return sherpa_onnx.OnlineRecognizer.from_transducer(
            tokens=str(d / "tokens.txt"), encoder=str(enc), decoder=str(dec), joiner=str(join),
            num_threads=self.threads, sample_rate=ASR_RATE, feature_dim=80, enable_endpoint_detection=True,
            rule1_min_trailing_silence=2.4, rule2_min_trailing_silence=0.65, rule3_min_utterance_length=20,
            decoding_method="greedy_search")

    def vad(self) -> sherpa_onnx.VoiceActivityDetector:
        c = sherpa_onnx.VadModelConfig()
        c.silero_vad.model = str(self.root / self.vad_file)
        c.silero_vad.threshold = 0.5
        c.silero_vad.min_silence_duration = 0.3
        c.silero_vad.min_speech_duration = 0.2
        c.sample_rate = ASR_RATE
        return sherpa_onnx.VoiceActivityDetector(c, buffer_size_in_seconds=10)

    def tts(self) -> sherpa_onnx.OfflineTts:
        d = self.root / self.tts_dir
        onnx = next(p for p in d.glob("*.onnx"))
        cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(onnx), tokens=str(d / "tokens.txt"), data_dir=str(d / "espeak-ng-data")),
            num_threads=self.threads))
        return sherpa_onnx.OfflineTts(cfg)


@dataclass
class TurnPlayback:
    turn: int
    chunks: dict[int, str] = field(default_factory=dict)
    next_seq: int = 0
    final_seq: int | None = None
    end_session: bool = False


class CluVoiceAgent:
    """One agent per Clu room. [clu_identity] is the device participant it serves (data is only sent to it)."""

    # Extra speech required on top of the VAD's own 0.2 s minimum before Clu stops talking.
    BARGE_IN_SPEECH_S = 0.1

    def __init__(self, models: Models, url: str, token: str, clu_identity: str, tts: sherpa_onnx.OfflineTts | None = None):
        self.models, self.url, self.token, self.clu_identity = models, url, token, clu_identity
        self.room = rtc.Room()
        self.tts = tts or models.tts()
        self.source = rtc.AudioSource(self.tts.sample_rate, 1, queue_size_ms=10_000)
        self.turn = 0
        self.playback: TurnPlayback | None = None
        self.speaking = False
        self.done = asyncio.Event()
        self.metrics: list[dict] = []
        self._play_lock = asyncio.Lock()
        self._play_task: asyncio.Task | None = None
        self._audio_tasks: set[asyncio.Task] = set()
        self._t_end_of_speech = 0.0

    # ---------------------------------------------------------------- lifecycle
    async def run(self, idle_timeout_s: float = 120.0) -> None:
        self.room.on("track_subscribed", self._on_track)
        self.room.on("data_received", self._on_data)
        self.room.on("participant_disconnected", self._on_left)
        await self.room.connect(self.url, self.token, rtc.RoomOptions(auto_subscribe=True))
        track = rtc.LocalAudioTrack.create_audio_track("clu-voice", self.source)
        await self.room.local_participant.publish_track(track, rtc.TrackPublishOptions(source=rtc.TrackSource.SOURCE_MICROPHONE))
        log.info("agent joined room %s", self.room.name)
        await self._state("listening")
        # Stay while the Clu device is present; leave after idle_timeout_s without it.
        t0 = time.monotonic()
        while not self.done.is_set():
            present = any(p.identity == self.clu_identity for p in self.room.remote_participants.values())
            if present:
                t0 = time.monotonic()
            elif time.monotonic() - t0 > idle_timeout_s:
                break
            try:
                await asyncio.wait_for(self.done.wait(), timeout=1.0)
            except asyncio.TimeoutError:
                pass
        await self.room.disconnect()
        log.info("agent left room %s", self.room.name)

    def _on_left(self, p: rtc.RemoteParticipant) -> None:
        if p.identity == self.clu_identity:
            self.done.set()

    def _on_track(self, track: rtc.Track, pub: rtc.RemoteTrackPublication, p: rtc.RemoteParticipant) -> None:
        if p.identity != self.clu_identity or track.kind != rtc.TrackKind.KIND_AUDIO:
            return
        t = asyncio.ensure_future(self._listen(track))
        self._audio_tasks.add(t); t.add_done_callback(self._audio_tasks.discard)

    # ---------------------------------------------------------------- data protocol
    async def _send(self, topic: str, payload: dict) -> None:
        payload = {"v": PROTOCOL_VERSION, **payload}
        await self.room.local_participant.publish_data(json.dumps(payload), reliable=True, topic=topic,
                                                       destination_identities=[self.clu_identity])

    async def _state(self, state: str) -> None:
        await self._send(T_STATE, {"state": state})

    def _on_data(self, packet: rtc.DataPacket) -> None:
        if packet.participant is None or packet.participant.identity != self.clu_identity or packet.topic != T_REPLY:
            return
        try:
            msg = json.loads(packet.data.decode("utf-8"))
            turn, seq = int(msg["turn"]), int(msg["seq"])
        except (ValueError, KeyError, UnicodeDecodeError):
            log.warning("ignored malformed reply"); return
        pb = self.playback
        if pb is None or turn != pb.turn:
            return  # reply to an old or interrupted turn
        if msg.get("text"):
            pb.chunks[seq] = str(msg["text"])[:2000]
        if msg.get("final"):
            pb.final_seq = seq
            pb.end_session = bool(msg.get("end_session"))
        if self._play_task is None or self._play_task.done():
            self._play_task = asyncio.ensure_future(self._play(pb))

    # ---------------------------------------------------------------- speaking
    async def _play(self, pb: TurnPlayback) -> None:
        async with self._play_lock:
            loop = asyncio.get_running_loop()
            while self.playback is pb:
                if pb.next_seq in pb.chunks:
                    text = pb.chunks.pop(pb.next_seq); pb.next_seq += 1
                    audio = await loop.run_in_executor(None, lambda: self.tts.generate(text, sid=0, speed=1.0))
                    if self.playback is not pb:
                        return
                    if not self.speaking:
                        self.speaking = True
                        await self._state("speaking")
                        if self._t_end_of_speech:
                            self.metrics.append({"turn": pb.turn, "end_of_speech_to_first_audio_ms": round((time.monotonic() - self._t_end_of_speech) * 1000)})
                    await self._push_audio(np.asarray(audio.samples, dtype=np.float32), audio.sample_rate)
                elif pb.final_seq is not None and pb.next_seq >= pb.final_seq:
                    await self.source.wait_for_playout()
                    if self.playback is pb:
                        self.speaking = False
                        await self._send(T_SPOKEN, {"turn": pb.turn})
                        await self._state("listening")
                        if pb.end_session:
                            log.info("session ended by Clu (turn %d)", pb.turn)
                    return
                else:
                    await asyncio.sleep(0.01)

    async def _push_audio(self, samples: np.ndarray, rate: int) -> None:
        pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype(np.int16)
        step = rate // 50  # 20 ms frames
        for i in range(0, len(pcm), step):
            if not self.speaking:
                return
            chunk = pcm[i:i + step]
            if len(chunk) < step:
                chunk = np.pad(chunk, (0, step - len(chunk)))
            await self.source.capture_frame(rtc.AudioFrame(chunk.tobytes(), rate, 1, step))

    async def _barge_in(self) -> None:
        pb = self.playback
        t0 = time.monotonic()
        self.speaking = False
        self.playback = None
        self.source.clear_queue()
        if pb is not None:
            await self._send(T_INTERRUPT, {"turn": pb.turn})
            self.metrics.append({"turn": pb.turn, "barge_in_stop_ms": round((time.monotonic() - t0) * 1000)})
            log.info("barge-in: stopped turn %d", pb.turn)
        await self._state("listening")

    # ---------------------------------------------------------------- listening
    def _new_stream(self, rec: sherpa_onnx.OnlineRecognizer):
        stream = rec.create_stream()
        stream.accept_waveform(ASR_RATE, np.zeros(ASR_RATE, dtype=np.float32))  # priming: avoids losing the first word
        return stream

    async def _listen(self, track: rtc.Track) -> None:
        rec = self.models.recognizer()
        vad = self.models.vad()
        stream = self._new_stream(rec)
        speech_s = 0.0
        last_partial = ""
        t_first_audio = None
        preroll: list[np.ndarray] = []  # last ~1 s of audio while Clu talks, so an interrupting sentence is heard whole
        announced = False
        async for ev in rtc.AudioStream(track, sample_rate=ASR_RATE, num_channels=1):
            f = np.frombuffer(ev.frame.data, dtype=np.int16).astype(np.float32) / 32768.0
            if not announced:
                # Ready only once the device's audio really arrives (the agent usually joins before the device).
                announced = True
                await self._state("speaking" if self.speaking else "listening")
            vad.accept_waveform(f)
            in_speech = vad.is_speech_detected()
            while not vad.empty():
                vad.pop()
            speech_s = speech_s + len(f) / ASR_RATE if in_speech else 0.0
            if self.speaking:
                preroll.append(f)
                while sum(len(x) for x in preroll) > ASR_RATE:
                    preroll.pop(0)
                if speech_s >= self.BARGE_IN_SPEECH_S:
                    await self._barge_in()
                    t_first_audio = time.monotonic() - sum(len(x) for x in preroll) / ASR_RATE
                    for x in preroll:
                        stream.accept_waveform(ASR_RATE, x)
                    preroll.clear()
                continue  # don't transcribe Clu's own voice (echo); barge-in above handles real speech
            preroll.clear()
            stream.accept_waveform(ASR_RATE, f)
            if t_first_audio is None and in_speech:
                t_first_audio = time.monotonic()
            while rec.is_ready(stream):
                rec.decode_stream(stream)
            text = rec.get_result(stream).strip()
            if text and text != last_partial:
                last_partial = text
                await self._send(T_PARTIAL, {"text": text.lower()})
            if rec.is_endpoint(stream):
                # Tail padding: let the streaming model flush the last word before reading the final result.
                stream.accept_waveform(ASR_RATE, np.zeros(int(0.3 * ASR_RATE), dtype=np.float32))
                while rec.is_ready(stream):
                    rec.decode_stream(stream)
                text = rec.get_result(stream).strip()
                if text:
                    t_dec = time.monotonic()
                    self._t_end_of_speech = t_dec
                    self.turn += 1
                    self.playback = TurnPlayback(self.turn)
                    speech_ms = round((t_dec - t_first_audio) * 1000) if t_first_audio else None
                    await self._send(T_TRANSCRIPT, {"turn": self.turn, "text": text.lower(), "speech_ms": speech_ms})
                    await self._state("thinking")
                    log.info("turn %d heard: %s", self.turn, text.lower())
                # reset() keeps the recogniser warm; measured: a fresh stream per utterance loses or garbles first words.
                rec.reset(stream)
                last_partial = ""
                t_first_audio = None
