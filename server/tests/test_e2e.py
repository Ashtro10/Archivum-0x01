#!/usr/bin/env python3
"""End-to-end test of the self-hosted LiveKit path against a real livekit-server (local, dev keys).

A Python participant stands in for the Clu device: it fetches a token from the Clu token endpoint with its device
key, joins, publishes synthetic speech as its microphone, answers `clu.transcript` with streamed `clu.reply`
sentences (as Clu's brain would), receives the agent's spoken audio, and tests barge-in.

usage: test_e2e.py <livekit-server binary> <models dir> [results.json]
"""
from __future__ import annotations

import asyncio
import base64
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import aiohttp
import numpy as np
from aiohttp import web
from livekit import rtc

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from clu_voice_agent import Models  # noqa: E402
from clu_voice_server import CluVoiceServer, DeviceRegistry  # noqa: E402

LK_PORT, TOKEN_PORT = 17880, 18089
results: dict = {"checks": {}, "metrics": {}}


def check(name: str, ok: bool, detail: str = "") -> None:
    results["checks"][name] = {"ok": bool(ok), "detail": detail}
    print(("PASS " if ok else "FAIL ") + name + (f" - {detail}" if detail else ""), flush=True)


def jwt_claims(t: str) -> dict:
    p = t.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)))


def wait_port(port: int, timeout: float = 20) -> None:
    end = time.time() + timeout
    while time.time() < end:
        with socket.socket() as s:
            if s.connect_ex(("127.0.0.1", port)) == 0:
                return
        time.sleep(0.2)
    raise RuntimeError(f"port {port} did not open")


def resample(x: np.ndarray, src: int, dst: int) -> np.ndarray:
    n = int(len(x) * dst / src)
    return np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)


async def publish_speech(source: rtc.AudioSource, samples: np.ndarray, rate: int = 48000, stop: asyncio.Event | None = None) -> None:
    pcm = (np.clip(samples, -1, 1) * 32767).astype(np.int16)
    step = rate // 100
    for i in range(0, len(pcm), step):
        if stop is not None and stop.is_set():
            return
        c = pcm[i:i + step]
        if len(c) < step:
            c = np.pad(c, (0, step - len(c)))
        await source.capture_frame(rtc.AudioFrame(c.tobytes(), rate, 1, step))


async def main(lk_bin: str, models_dir: str, out_path: str | None) -> int:
    work = Path(tempfile.mkdtemp(prefix="clu-lk-"))
    cfg = work / "livekit.yaml"
    cfg.write_text(f"port: {LK_PORT}\nbind_addresses: [127.0.0.1]\nrtc:\n  tcp_port: {LK_PORT + 1}\n  port_range_start: 51000\n"
                   f"  port_range_end: 51100\n  use_external_ip: false\n  node_ip: 127.0.0.1\nkeys:\n  devkey: secret-secret-secret-secret-1234\n"
                   "logging:\n  level: warn\n")
    lk = subprocess.Popen([lk_bin, "--config", str(cfg)], stdout=subprocess.DEVNULL, stderr=open(work / "livekit.log", "w"))
    try:
        wait_port(LK_PORT)
        env = {"LIVEKIT_API_KEY": "devkey", "LIVEKIT_API_SECRET": "secret-secret-secret-secret-1234",
               "CLU_PUBLIC_LIVEKIT_URL": f"ws://127.0.0.1:{LK_PORT}", "CLU_INTERNAL_LIVEKIT_URL": f"ws://127.0.0.1:{LK_PORT}",
               "CLU_DEVICES": str(work / "devices.json"), "CLU_MODELS": models_dir}
        key = DeviceRegistry(work / "devices.json").add("phone")
        srv = CluVoiceServer(env)
        runner = web.AppRunner(srv.app()); await runner.setup()
        await web.TCPSite(runner, "127.0.0.1", TOKEN_PORT).start()
        url = f"http://127.0.0.1:{TOKEN_PORT}/token"
        room_name = "clu-phone"
        req = {"room_name": room_name, "participant_identity": room_name, "participant_name": "Clu (phone)",
               "room_config": {"agents": [{"agent_name": "clu-voice"}]}}

        # ---------------------------------------------------------------- token endpoint security
        async with aiohttp.ClientSession() as http:
            r = await http.post(url, json=req, headers={"Authorization": "Bearer phone.wrong-secret"})
            check("token: wrong device key rejected (401)", r.status == 401)
            r = await http.post(url, json={**req, "room_name": "clu-someone-else"}, headers={"Authorization": f"Bearer {key}"})
            check("token: other room refused (403)", r.status == 403)
            r = await http.post(url, json=req, headers={"Authorization": f"Bearer {key}"})
            body = await r.json()
            check("token: valid device key accepted", r.status == 200 and "participant_token" in body, str(r.status))
            claims = jwt_claims(body["participant_token"])
            v = claims["video"]
            check("token: grants only this room, no admin/create",
                  v.get("room") == room_name and v.get("roomJoin") and not v.get("roomAdmin") and not v.get("roomCreate")
                  and v.get("canPublishSources") == ["microphone"], json.dumps(v))
            check("token: short-lived", claims["exp"] - time.time() <= 610, f"{claims['exp'] - time.time():.0f} s")
            check("token: no API secret in response", "secret-secret" not in json.dumps(body))
            # throttling (separate registry state is shared; use a burst of bad keys)
            codes = [(await http.post(url, json=req, headers={"Authorization": "Bearer x.y"})).status for _ in range(12)]
            check("token: repeated bad keys throttled (429)", 429 in codes, str(codes[-3:]))
            srv.throttle.fails.clear()

        # ---------------------------------------------------------------- voice round trip
        models = Models(Path(models_dir))
        tts = models.tts()
        room = rtc.Room()
        transcripts, states, interrupts, spoken = [], [], [], []
        agent_audio_t: list[float] = []      # times of loud agent frames
        agent_frames: list[tuple[float, bool]] = []  # (time, loud) for every received agent frame
        loop = asyncio.get_running_loop()
        replies = {
            "what time is it": ["It is a quarter past nine.", "Anything else?"],
            "tell me a long story": ["Once upon a time there was a robot named Clu who lived in a tablet.",
                                     "Every day it learned something new about its owner, and remembered all of it carefully.",
                                     "One day it decided to explore the whole house, room by room, very slowly."],
            "end of the line": ["Understood. Standing by."],
        }

        def on_data(p: rtc.DataPacket):
            msg = json.loads(p.data.decode())
            if p.topic == "clu.transcript":
                transcripts.append((time.monotonic(), msg))
                text = msg["text"]
                key_ = next((k for k in replies if k in text), None)
                chunks = replies.get(key_, ["I heard you."])
                end = key_ == "end of the line"
                async def send_reply():
                    for i, c in enumerate(chunks):
                        await room.local_participant.publish_data(json.dumps({"v": 1, "turn": msg["turn"], "seq": i, "text": c, "final": False}),
                                                                  reliable=True, topic="clu.reply")
                    await room.local_participant.publish_data(json.dumps({"v": 1, "turn": msg["turn"], "seq": len(chunks), "text": "",
                                                                          "final": True, "end_session": end}), reliable=True, topic="clu.reply")
                asyncio.ensure_future(send_reply())
            elif p.topic == "clu.agent_state":
                states.append((time.monotonic(), msg["state"]))
            elif p.topic == "clu.interrupt":
                interrupts.append((time.monotonic(), msg))
            elif p.topic == "clu.spoken":
                spoken.append((time.monotonic(), msg))

        async def watch_agent_audio(track: rtc.Track):
            async for ev in rtc.AudioStream(track, sample_rate=16000, num_channels=1):
                a = np.frombuffer(ev.frame.data, dtype=np.int16)
                loud = bool(np.abs(a).max() > 300)
                agent_frames.append((time.monotonic(), loud))
                if loud:
                    agent_audio_t.append(time.monotonic())

        room.on("data_received", on_data)
        room.on("track_subscribed", lambda t, pub, p: asyncio.ensure_future(watch_agent_audio(t)) if t.kind == rtc.TrackKind.KIND_AUDIO else None)
        t_conn = time.monotonic()
        await room.connect(body["server_url"], body["participant_token"])
        mic = rtc.AudioSource(48000, 1)
        await room.local_participant.publish_track(rtc.LocalAudioTrack.create_audio_track("mic", mic),
                                                   rtc.TrackPublishOptions(source=rtc.TrackSource.SOURCE_MICROPHONE))
        asyncio.ensure_future(publish_speech(mic, np.zeros(48000, dtype=np.float32)))  # microphone starts streaming (silence)
        # wait for agent participant + its audio track
        end = time.monotonic() + 60
        while time.monotonic() < end and not any(p.identity == "clu-voice-agent" for p in room.remote_participants.values()):
            await asyncio.sleep(0.1)
        agent = next((p for p in room.remote_participants.values() if p.identity == "clu-voice-agent"), None)
        check("agent joined the device's room", agent is not None, f"{time.monotonic() - t_conn:.1f} s after connect")
        check("agent is marked as an agent participant", agent is not None and agent.kind == rtc.ParticipantKind.PARTICIPANT_KIND_AGENT,
              str(agent.kind if agent else None))
        while time.monotonic() < end and not any(s == "listening" for _, s in states):
            await asyncio.sleep(0.1)
        check("agent ready once the device's audio arrives", any(s == "listening" for _, s in states))
        await asyncio.sleep(1.0)

        silence = np.zeros(48000, dtype=np.float32)

        async def say(text: str, stop: asyncio.Event | None = None) -> float:
            a = tts.generate(text, sid=0, speed=1.0)
            x = resample(np.asarray(a.samples, dtype=np.float32), a.sample_rate, 48000)
            await publish_speech(mic, x, stop=stop)
            await mic.wait_for_playout()  # AudioSource buffers up to 1 s: timestamp when the audio has really been sent
            t_end = time.monotonic()
            await publish_speech(mic, silence)  # trailing silence for endpointing
            return t_end

        # Turn 1: question -> transcript -> reply -> spoken audio
        n0 = len(transcripts)
        t_end_speech = await say("What time is it?")
        end = time.monotonic() + 20
        while time.monotonic() < end and not spoken:
            await asyncio.sleep(0.05)
        heard = transcripts[n0][1]["text"] if len(transcripts) > n0 else ""
        check("turn 1: speech transcribed by the agent", "time" in heard, repr(heard))
        first_audio = next((t for t in agent_audio_t if t > t_end_speech), None)
        check("turn 1: reply spoken back as audio", first_audio is not None and bool(spoken))
        if first_audio and len(transcripts) > n0:
            results["metrics"]["end_of_speech_to_transcript_ms"] = round((transcripts[n0][0] - t_end_speech) * 1000)
            results["metrics"]["end_of_speech_to_first_reply_audio_ms"] = round((first_audio - t_end_speech) * 1000)
            results["metrics"]["transcript_to_first_reply_audio_ms"] = round((first_audio - transcripts[n0][0]) * 1000)

        # Turn 2: long reply, interrupted by speech while the agent talks (barge-in)
        n1, i0 = len(transcripts), len(interrupts)
        await say("Tell me a long story.")
        end = time.monotonic() + 20
        while time.monotonic() < end and not any(s == "speaking" and t > transcripts[-1][0] for t, s in states if len(transcripts) > n1):
            await asyncio.sleep(0.05)
        await asyncio.sleep(1.0)
        a = tts.generate("Stop, wait a moment.", sid=0, speed=1.0)
        stop_audio = resample(np.asarray(a.samples, dtype=np.float32), a.sample_rate, 48000)
        lead = int(np.argmax(np.abs(stop_audio) > 0.02))  # Piper adds leading silence; measure from the first sound
        await mic.wait_for_playout()
        t_bi = time.monotonic() + lead / 48000
        await publish_speech(mic, stop_audio)
        await publish_speech(mic, silence)
        end = time.monotonic() + 10
        while time.monotonic() < end and len(interrupts) == i0:
            await asyncio.sleep(0.02)
        check("barge-in: agent sent clu.interrupt when the owner spoke", len(interrupts) > i0)
        if len(interrupts) > i0:
            results["metrics"]["barge_in_speech_start_to_interrupt_ms"] = round((interrupts[i0][0] - t_bi) * 1000)
            await asyncio.sleep(1.5)
            # The interrupted reply must go quiet: find the first >=300 ms silent stretch after the interrupt. (The agent then
            # correctly answers "stop, wait a moment" as a new turn, so later speech is expected.)
            t_int = interrupts[i0][0]
            after = [(t, l) for t, l in agent_frames if t >= t_int]
            quiet_from, silent_start = None, None
            for t, loud in after:
                if loud:
                    silent_start = None
                else:
                    silent_start = silent_start or t
                    if t - silent_start >= 0.3:
                        quiet_from = silent_start; break
            stop_ms = round((quiet_from - t_int) * 1000) if quiet_from else None
            check("barge-in: interrupted reply went silent", stop_ms is not None and stop_ms < 500, f"{stop_ms} ms after interrupt")
            if stop_ms is not None:
                results["metrics"]["interrupt_to_agent_audio_silent_ms"] = stop_ms

        # Turn 3: "End of the line" -> farewell spoken, then clu.spoken for that turn
        await asyncio.sleep(2.0)
        s0 = len(spoken)
        await say("End of the line.")
        end = time.monotonic() + 20
        while time.monotonic() < end and len(spoken) == s0:
            await asyncio.sleep(0.05)
        check("end of the line: farewell spoken and completion reported", len(spoken) > s0)

        await room.disconnect()
        end = time.monotonic() + 10
        while time.monotonic() < end and not all(t.done() for t in srv.agents.values()):
            await asyncio.sleep(0.1)
        check("agent leaves when the device leaves", bool(srv.agents) and all(t.done() for t in srv.agents.values()),
              f"{sum(not t.done() for t in srv.agents.values())} agent(s) still running")
        await runner.cleanup()
    finally:
        lk.terminate(); lk.wait(timeout=10)
    ok = all(c["ok"] for c in results["checks"].values())
    results["summary"] = {"passed": sum(c["ok"] for c in results["checks"].values()), "total": len(results["checks"])}
    print(json.dumps(results["metrics"], indent=2))
    if out_path:
        Path(out_path).write_text(json.dumps(results, indent=2))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)))
