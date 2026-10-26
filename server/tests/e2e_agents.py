#!/usr/bin/env python3
"""End-to-end test of the Clu conversation pipeline on one machine, with the same protocol the Android app uses.

Starts: livekit-server (dev keys), the token server (clu_voice_server.py serve), the agent worker (clu_agent.py,
CLU_STACK=local: sherpa-onnx Kroko STT + Piper voice) and a MOCK OpenAI-compatible LLM (tests/mock_llm.py: scripted
replies, 300 ms first token). A Python LiveKit client plays the phone: fetches a token with a device key, joins,
publishes a microphone track fed from synthesised speech, sends typed messages on "lk.chat", reads "lk.transcription",
listens to the agent's audio and calls the clu.* RPCs.

Checks the four interaction paths, follow-up context, history loading, interruption and reconnection, and measures
end of speech -> first agent audio. Writes a JSON report.

usage: e2e_agents.py --livekit-server BIN --kroko DIR --voice DIR [--out report.json]
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import socket
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import aiohttp
import sherpa_onnx
from livekit import rtc

HERE = Path(__file__).resolve().parent
SERVER = HERE.parent
RATE = 48000
FRAME = RATE // 100  # 10 ms


def free_port() -> int:
    s = socket.socket(); s.bind(("127.0.0.1", 0)); p = s.getsockname()[1]; s.close(); return p


class Speech:
    """Synthesises the 'owner's' questions (Piper) at 48 kHz for the microphone track."""

    def __init__(self, voice: Path):
        onnx = next(p for p in voice.glob("*.onnx"))
        self.tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(onnx), tokens=str(voice / "tokens.txt"), data_dir=str(voice / "espeak-ng-data")),
            num_threads=2)))

    def say(self, text: str) -> np.ndarray:
        a = self.tts.generate(text, sid=0, speed=1.0)
        x = np.asarray(a.samples, dtype=np.float32)
        y = np.interp(np.linspace(0, len(x) - 1, int(len(x) * RATE / a.sample_rate)), np.arange(len(x)), x)
        return (np.clip(y, -1, 1) * 30000).astype(np.int16)


class Phone:
    def __init__(self) -> None:
        self.room = rtc.Room()
        self.src = rtc.AudioSource(RATE, 1)
        self.transcripts: list[dict] = []
        self.agent_identity: str | None = None
        self.agent_state = ""
        self.metrics: list[dict] = []
        self.last_audio_at = 0.0     # last agent audio frame with sound
        self.first_audio_after: dict[float, float] = {}
        self._mark = 0.0
        self.mic_task: asyncio.Task | None = None
        self.queue: list[np.ndarray] = []

    async def join(self, url: str, token: str) -> None:
        self.room.on("participant_attributes_changed", self._attrs)
        self.room.on("track_subscribed", self._track)
        self.room.on("data_received", self._data)
        self.room.register_text_stream_handler("lk.transcription", self._transcript)
        await self.room.connect(url, token)
        for p in self.room.remote_participants.values():  # an agent already in the room (rejoin)
            if "lk.agent.state" in p.attributes: self.agent_identity, self.agent_state = p.identity, p.attributes["lk.agent.state"]
        track = rtc.LocalAudioTrack.create_audio_track("microphone", self.src)
        await self.room.local_participant.publish_track(track, rtc.TrackPublishOptions(source=rtc.TrackSource.SOURCE_MICROPHONE))
        self.mic_task = asyncio.ensure_future(self._mic())

    def _attrs(self, changed: dict, p: rtc.Participant) -> None:
        if "lk.agent.state" in changed:
            self.agent_identity = p.identity; self.agent_state = changed["lk.agent.state"]

    def _data(self, pkt: rtc.DataPacket) -> None:
        if pkt.topic == "clu.metrics":
            self.metrics.append(json.loads(pkt.data))

    def _transcript(self, reader, identity: str) -> None:
        async def read():
            text = await reader.read_all()
            attrs = reader.info.attributes or {}
            self.transcripts.append({"from": identity, "text": text, "final": attrs.get("lk.transcription_final") == "true",
                                     "user": "lk.transcribed_track_id" in attrs and identity != self.agent_identity, "at": time.monotonic()})
        asyncio.ensure_future(read())

    def _track(self, track: rtc.Track, pub, p: rtc.RemoteParticipant) -> None:
        if track.kind == rtc.TrackKind.KIND_AUDIO:
            self.agent_identity = p.identity
            asyncio.ensure_future(self._listen(track))

    async def _listen(self, track: rtc.Track) -> None:
        async for ev in rtc.AudioStream(track, sample_rate=RATE, num_channels=1):
            x = np.frombuffer(ev.frame.data, dtype=np.int16)
            if x.size and np.sqrt(np.mean(x.astype(np.float32) ** 2)) > 300:
                now = time.monotonic(); self.last_audio_at = now
                if self._mark and self._mark not in self.first_audio_after:
                    self.first_audio_after[self._mark] = now

    async def _mic(self) -> None:
        """Real-time microphone: queued speech, otherwise silence (as a real mic sends)."""
        silence = np.zeros(FRAME, dtype=np.int16)
        t = time.monotonic()
        while True:
            chunk = silence
            if self.queue:
                buf = self.queue[0]
                chunk, self.queue[0] = buf[:FRAME], buf[FRAME:]
                if len(chunk) < FRAME: chunk = np.pad(chunk, (0, FRAME - len(chunk)))
                if not len(self.queue[0]): self.queue.pop(0)
            await self.src.capture_frame(rtc.AudioFrame(chunk.tobytes(), RATE, 1, FRAME))
            t += 0.01
            await asyncio.sleep(max(0.0, t - time.monotonic()))

    async def speak(self, pcm: np.ndarray) -> float:
        """Plays speech into the mic; returns the moment the speech ended (latency is measured from there)."""
        self.queue.append(pcm)
        await asyncio.sleep(len(pcm) / RATE)
        while self.queue: await asyncio.sleep(0.01)
        end = time.monotonic(); self._mark = end
        return end

    def mark(self) -> float:
        self._mark = time.monotonic(); return self._mark

    async def rpc(self, method: str, payload: dict | None = None) -> str:
        return await self.room.local_participant.perform_rpc(destination_identity=self.agent_identity, method=method,
                                                             payload=json.dumps(payload or {}), response_timeout=5.0)

    def agent_text_since(self, t: float) -> str:
        return " ".join(x["text"] for x in self.transcripts if x["at"] >= t and x["from"] == self.agent_identity and x["final"])

    def user_text_since(self, t: float) -> str:
        return " ".join(x["text"] for x in self.transcripts if x["at"] >= t and x["from"] != self.agent_identity and x["final"])


async def wait_for(cond, timeout: float, step: float = 0.05) -> bool:
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if cond(): return True
        await asyncio.sleep(step)
    return cond()


async def run(a) -> dict:
    procs: list[subprocess.Popen] = []
    tmp = Path(a.work); tmp.mkdir(parents=True, exist_ok=True)
    lk_port, tok_port, llm_port = free_port(), free_port(), free_port()
    report: dict = {"note": "host x86 build container; LLM is a MOCK (scripted, 300 ms first token); STT/TTS/VAD/LiveKit are real", "checks": {}}
    try:
        procs.append(subprocess.Popen([a.livekit_server, "--dev", "--bind", "127.0.0.1", "--port", str(lk_port)],
                                      stdout=open(tmp / "livekit.log", "w"), stderr=subprocess.STDOUT))
        procs.append(subprocess.Popen([sys.executable, str(HERE / "mock_llm.py"), str(llm_port)], stdout=open(tmp / "llm.log", "w"), stderr=subprocess.STDOUT))
        models = tmp / "models"; models.mkdir(exist_ok=True)
        for name, src in (("kroko-en", a.kroko), ("voice", a.voice)):
            if not (models / name).exists(): (models / name).symlink_to(Path(src).resolve())
        env = dict(os.environ, LIVEKIT_URL=f"ws://127.0.0.1:{lk_port}", LIVEKIT_API_KEY="devkey", LIVEKIT_API_SECRET="secret",
                   CLU_PUBLIC_LIVEKIT_URL=f"ws://127.0.0.1:{lk_port}", CLU_INTERNAL_LIVEKIT_URL=f"ws://127.0.0.1:{lk_port}",
                   CLU_STACK="local", CLU_TURN="vad", CLU_MODELS=str(models), CLU_ASR_DIR="kroko-en", CLU_TTS_DIR="voice",
                   CLU_LLM_URL=f"http://127.0.0.1:{llm_port}/v1", CLU_LLM_MODEL="mock", CLU_PORT=str(tok_port), CLU_BIND="127.0.0.1",
                   CLU_DEVICES=str(tmp / "devices.json"), CLU_RUN_AGENT="1")
        # Everything is on 127.0.0.1; an outbound proxy in the environment would intercept the worker's ws:// connection.
        for k in [k for k in env if k.lower() in ("http_proxy", "https_proxy", "all_proxy")]:
            env.pop(k)
        out = subprocess.run([sys.executable, str(SERVER / "clu_voice_server.py"), "add-device", "phone"], env=env, capture_output=True, text=True)
        device_key = out.stdout.strip().splitlines()[-1]
        procs.append(subprocess.Popen([sys.executable, str(SERVER / "clu_voice_server.py"), "serve"], env=env,
                                      stdout=open(tmp / "server.log", "w"), stderr=subprocess.STDOUT))
        speech = Speech(Path(a.voice))
        await wait_for(lambda: "registered worker" in (tmp / "server.log").read_text(errors="ignore"), 60, 0.2)

        async def token() -> dict:
            async with aiohttp.ClientSession() as s:
                body = {"room_name": "clu-phone", "participant_identity": "clu-phone", "room_config": {"agents": [{"agent_name": "clu"}]}}
                async with s.post(f"http://127.0.0.1:{tok_port}/token", json=body, headers={"Authorization": f"Bearer {device_key}"}) as r:
                    return await r.json()

        t0 = time.monotonic()
        tok = await token()
        phone = Phone()
        await phone.join(tok["server_url"], tok["participant_token"])
        ok = await wait_for(lambda: phone.agent_state == "listening", 30)
        report["agent_ready_s"] = round(time.monotonic() - t0, 2)
        report["checks"]["agent dispatched and listening"] = ok
        if not ok: return report

        # 1. Voice -> spoken reply (+ 3. displayed text)
        q1 = "What time does the train to Pune leave tomorrow?"
        end = await phone.speak(speech.say(q1))
        await wait_for(lambda: end in phone.first_audio_after and phone.agent_state == "listening", 20)
        heard = phone.user_text_since(end - 10); said = phone.agent_text_since(end)
        report["voice_q1"] = {"asked": q1, "recognised": heard, "reply_text": said,
                              "speech_end_to_first_audio_ms": round(1000 * (phone.first_audio_after.get(end, 0) - end)) if end in phone.first_audio_after else None}
        report["checks"]["1 voice -> spoken reply"] = end in phone.first_audio_after
        report["checks"]["3 voice -> displayed text"] = said.lower().startswith("i heard:") and len(heard) > 10
        report["voice_q1"]["recognition_word_errors"] = sum(1 for a_, b_ in zip(q1.lower().rstrip("?").split(), heard.split()) if a_ != b_) + abs(len(q1.split()) - len(heard.split()))

        # 2/4. Text -> reply. Spoken on (4), then off (2: text only, no audio).
        t = phone.mark()
        await phone.room.local_participant.send_text("Remember the number forty two.", topic="lk.chat")
        await wait_for(lambda: "forty two" in phone.agent_text_since(t).lower() and phone.agent_state == "listening" and t in phone.first_audio_after, 15)
        report["checks"]["4 text -> spoken reply"] = t in phone.first_audio_after and "forty two" in phone.agent_text_since(t).lower()
        report["text_to_first_audio_ms"] = round(1000 * (phone.first_audio_after[t] - t)) if t in phone.first_audio_after else None
        await phone.rpc("clu.set_audio_output", {"enabled": False})
        t = phone.mark()
        await phone.room.local_participant.send_text("What number did I ask you to remember?", topic="lk.chat")
        await wait_for(lambda: "number did i ask" in phone.agent_text_since(t).lower(), 15)
        await asyncio.sleep(1.5)
        report["checks"]["2 text -> displayed text (no audio)"] = "number did i ask" in phone.agent_text_since(t).lower() and t not in phone.first_audio_after
        ctx_n = phone.agent_text_since(t)
        # voice turn (2) + "forty two" turn (2) + this question = 5 messages in the model's context
        report["checks"]["follow-up keeps context"] = "context has 5 messages" in ctx_n.lower()
        report["follow_up_reply"] = ctx_n
        await phone.rpc("clu.set_audio_output", {"enabled": True})

        # Interruption: long reply, owner talks over it.
        end = await phone.speak(speech.say("Tell me a long story please."))
        await wait_for(lambda: phone.agent_state == "speaking", 15)
        await asyncio.sleep(1.5)
        pcm = speech.say("Stop. What is two plus two?")
        start_barge = time.monotonic()  # the owner starts talking over Clu
        barge_task = asyncio.ensure_future(phone.speak(pcm))
        silent = await wait_for(lambda: time.monotonic() - phone.last_audio_at > 0.3 and phone.agent_state != "speaking", 3, 0.01)
        stopped_at = phone.last_audio_at  # last audible agent frame before the silence
        end2 = await barge_task
        await wait_for(lambda: "two plus two" in phone.agent_text_since(end2 - 3).lower(), 20)
        report["interruption"] = {"agent_stopped": silent, "owner_starts_talking_to_agent_silent_ms": round(1000 * (stopped_at - start_barge)) if silent else None,
                                  "new_reply": phone.agent_text_since(end2 - 3)}
        report["checks"]["interruption stops the reply and answers the new question"] = silent and "two plus two" in phone.agent_text_since(end2 - 3).lower()

        # Reconnect: leave, rejoin, load history, follow up.
        await phone.room.disconnect()
        if phone.mic_task: phone.mic_task.cancel()
        tok = await token()
        phone2 = Phone()
        t = time.monotonic()
        await phone2.join(tok["server_url"], tok["participant_token"])
        ok = await wait_for(lambda: phone2.agent_state == "listening", 30)
        report["reconnect_to_listening_s"] = round(time.monotonic() - t, 2)
        r = await phone2.rpc("clu.load_history", {"messages": [{"role": "user", "text": "My name is Asha."}, {"role": "assistant", "text": "Noted, Asha."}]})
        t = phone2.mark()
        await phone2.room.local_participant.send_text("Who am I?", topic="lk.chat")
        await wait_for(lambda: "who am i" in phone2.agent_text_since(t).lower(), 15)
        # The agent session survived the short disconnect, so it keeps its own 9 messages and ignores the reload
        # (no duplicates): voice 2 + text 4 + interruption turns + this question.
        report["checks"]["reconnect keeps the conversation (no duplicate history)"] = ok and json.loads(r).get("loaded") == 0 and "who am i" in phone2.agent_text_since(t).lower()
        report["after_reconnect_reply"] = phone2.agent_text_since(t)
        report["metrics_from_agent"] = phone.metrics + phone2.metrics
        await phone2.room.disconnect()
        if phone2.mic_task: phone2.mic_task.cancel()
    finally:
        for p in procs:
            p.terminate()
        for p in procs:
            try: p.wait(10)
            except Exception: p.kill()
    report["passed"] = all(report["checks"].values()) and len(report["checks"]) >= 8
    return report


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--livekit-server", required=True); ap.add_argument("--kroko", required=True); ap.add_argument("--voice", required=True)
    ap.add_argument("--work", default="/tmp/clu-e2e"); ap.add_argument("--out", default="")
    a = ap.parse_args()
    r = asyncio.run(run(a))
    s = json.dumps(r, indent=1)
    print(s)
    if a.out: Path(a.out).write_text(s)
    return 0 if r.get("passed") else 1


if __name__ == "__main__":
    sys.exit(main())
