#!/usr/bin/env python3
"""Clu voice server: token endpoint for the Clu app, optionally supervising the conversation agent worker.

    python clu_voice_server.py add-device phone        # prints a device key once; paste it into Clu
    python clu_voice_server.py list-devices
    python clu_voice_server.py revoke-device phone
    python clu_voice_server.py serve                   # token endpoint on :8089 (+ clu_agent.py worker unless CLU_RUN_AGENT=0)

When a device asks for a token, this server explicitly dispatches the Clu agent (clu_agent.py, LiveKit Agents
framework) to the device's room unless an agent is already there, so a session is waiting when the device joins.
Works with self-hosted livekit-server and with LiveKit Cloud.

Security model
* The LiveKit API key/secret live only here (environment), never in the app.
* A device authenticates with its device key "<id>.<secret>" (Authorization: Bearer). Only a SHA-256 of the
  secret is stored; keys can be revoked at any time.
* A device can only get a short-lived token for its own room "clu-<id>" as identity "clu-<id>", with permission to
  publish its microphone and data - no admin, no room creation, no other rooms.
* Repeated bad keys from one address are throttled.

Configuration (environment)
  LIVEKIT_API_KEY / LIVEKIT_API_SECRET   keys of your LiveKit server (livekit-server generate-keys)
  CLU_PUBLIC_LIVEKIT_URL                 URL the phone/tablet uses, e.g. ws://192.168.43.10:7880 or wss://lk.example.net
  CLU_INTERNAL_LIVEKIT_URL               URL this server uses (default ws://127.0.0.1:7880)
  CLU_AGENT_NAME                         agent to dispatch (default clu; must match clu_agent.py)
  CLU_RUN_AGENT                          1 (default): also run `clu_agent.py start` as a child process
  CLU_DEVICES                            device key file (default ./devices.json)
  CLU_BIND / CLU_PORT                    token endpoint address (default 0.0.0.0:8089)
  CLU_TOKEN_TTL_S                        participant token lifetime (default 600)
"""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import logging
import os
import re
import secrets
import sys
import time
from datetime import timedelta
from pathlib import Path

from aiohttp import web
from livekit import api

# The agent module lives next to this file; make it importable however Python was started (e.g. `python -I`).
sys.path.insert(0, str(Path(__file__).resolve().parent))

log = logging.getLogger("clu.server")
ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,30}$")


# ------------------------------------------------------------------------------------------------ device keys
class DeviceRegistry:
    def __init__(self, path: Path):
        self.path = path

    def _load(self) -> dict:
        return json.loads(self.path.read_text()) if self.path.exists() else {}

    def _save(self, d: dict) -> None:
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(d, indent=2))
        os.chmod(tmp, 0o600)
        tmp.replace(self.path)

    def add(self, device_id: str) -> str:
        if not ID_RE.match(device_id):
            raise ValueError("device id: lowercase letters, digits and '-', max 31 characters")
        secret = secrets.token_urlsafe(32)
        d = self._load()
        d[device_id] = {"sha256": hashlib.sha256(secret.encode()).hexdigest(), "created": int(time.time())}
        self._save(d)
        return f"{device_id}.{secret}"

    def revoke(self, device_id: str) -> bool:
        d = self._load()
        if d.pop(device_id, None) is None:
            return False
        self._save(d)
        return True

    def ids(self) -> list[str]:
        return sorted(self._load())

    def authenticate(self, bearer: str | None) -> str | None:
        """Returns the device id for a valid "<id>.<secret>" key, else None (constant-time compare)."""
        if not bearer or "." not in bearer:
            return None
        device_id, secret = bearer.split(".", 1)
        entry = self._load().get(device_id)
        if entry is None:
            hashlib.sha256(secret.encode()).hexdigest()  # similar timing for unknown ids
            return None
        return device_id if hmac.compare_digest(entry["sha256"], hashlib.sha256(secret.encode()).hexdigest()) else None


class Throttle:
    """At most [limit] failed attempts per address per minute."""

    def __init__(self, limit: int = 10):
        self.limit, self.fails = limit, {}

    def blocked(self, addr: str) -> bool:
        now = time.monotonic()
        self.fails[addr] = [t for t in self.fails.get(addr, []) if now - t < 60]
        return len(self.fails[addr]) >= self.limit

    def fail(self, addr: str) -> None:
        self.fails.setdefault(addr, []).append(time.monotonic())


# ------------------------------------------------------------------------------------------------ server
class CluVoiceServer:
    def __init__(self, env: dict):
        self.key = env["LIVEKIT_API_KEY"]; self.secret = env["LIVEKIT_API_SECRET"]
        self.public_url = env["CLU_PUBLIC_LIVEKIT_URL"]
        self.internal_url = env.get("CLU_INTERNAL_LIVEKIT_URL", "ws://127.0.0.1:7880")
        self.agent_name = env.get("CLU_AGENT_NAME", "clu")
        self.ttl = int(env.get("CLU_TOKEN_TTL_S", "600"))
        self.devices = DeviceRegistry(Path(env.get("CLU_DEVICES", "devices.json")))
        self.throttle = Throttle()
        self._dispatched: dict[str, float] = {}

    def participant_token(self, device_id: str) -> tuple[str, str]:
        room = identity = f"clu-{device_id}"
        token = (api.AccessToken(self.key, self.secret).with_identity(identity).with_name(f"Clu ({device_id})")
                 .with_ttl(timedelta(seconds=self.ttl))
                 .with_grants(api.VideoGrants(room_join=True, room=room, can_publish=True, can_subscribe=True, can_publish_data=True,
                                              can_publish_sources=["microphone"], room_admin=False, room_create=False))
                 .to_jwt())
        return room, token

    async def handle_token(self, request: web.Request) -> web.Response:
        addr = request.remote or "?"
        if self.throttle.blocked(addr):
            return web.json_response({"error": "too many attempts"}, status=429)
        auth = request.headers.get("Authorization", "")
        device_id = self.devices.authenticate(auth[7:] if auth.startswith("Bearer ") else None)
        if device_id is None:
            self.throttle.fail(addr)
            log.warning("rejected token request from %s", addr)
            return web.json_response({"error": "invalid device key"}, status=401)
        try:
            body = await request.json()
        except (json.JSONDecodeError, UnicodeDecodeError):
            return web.json_response({"error": "invalid JSON"}, status=400)
        room, token = self.participant_token(device_id)
        if body.get("room_name") != room or body.get("participant_identity") != room:
            return web.json_response({"error": f"this device may only join room '{room}' as '{room}'"}, status=403)
        agents = [a.get("agent_name") for a in (body.get("room_config") or {}).get("agents", []) if isinstance(a, dict)]
        if agents and self.agent_name not in agents:
            return web.json_response({"error": f"unknown agent; this server runs '{self.agent_name}'"}, status=400)
        try:
            await self.ensure_agent(room)
        except Exception as e:  # the token is still useful; the app reports a missing agent itself
            log.warning("could not dispatch the agent for %s: %s", room, e)
        log.info("token issued: device=%s room=%s", device_id, room)
        return web.json_response({"server_url": self.public_url, "participant_token": token})

    async def ensure_agent(self, room: str) -> None:
        """Asks LiveKit to start a Clu agent session in the device's room unless one is already there (or was requested
        moments ago). An explicit dispatch, unlike a token's room configuration, also works for a room that already
        exists, e.g. after the agent worker restarted while the phone stayed connected."""
        if time.monotonic() - self._dispatched.get(room, -1e9) < 20:
            return
        http_url = self.internal_url.replace("ws://", "http://").replace("wss://", "https://")
        lk = api.LiveKitAPI(http_url, self.key, self.secret)
        try:
            try:
                parts = (await lk.room.list_participants(api.ListParticipantsRequest(room=room))).participants
            except Exception:
                parts = []  # room not created yet
            if any(p.kind == api.ParticipantInfo.Kind.AGENT for p in parts):
                return
            await lk.agent_dispatch.create_dispatch(api.CreateAgentDispatchRequest(agent_name=self.agent_name, room=room))
            self._dispatched[room] = time.monotonic()
            log.info("agent '%s' dispatched to %s", self.agent_name, room)
        finally:
            await lk.aclose()

    def app(self) -> web.Application:
        a = web.Application(client_max_size=16 * 1024)
        a.router.add_post("/token", self.handle_token)
        a.router.add_get("/healthz", lambda r: web.json_response({"ok": True, "agent": self.agent_name}))
        return a


def main(argv: list[str]) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
    env = dict(os.environ)
    devices = DeviceRegistry(Path(env.get("CLU_DEVICES", "devices.json")))
    cmd = argv[1] if len(argv) > 1 else "help"
    if cmd == "add-device" and len(argv) == 3:
        key = devices.add(argv[2])
        print(f"Device key for '{argv[2]}' (shown once - enter it in Clu → Settings → LiveKit voice → Device key):\n{key}")
        return 0
    if cmd == "revoke-device" and len(argv) == 3:
        print("revoked" if devices.revoke(argv[2]) else "no such device"); return 0
    if cmd == "list-devices":
        print("\n".join(devices.ids()) or "(none)"); return 0
    if cmd == "serve":
        missing = [k for k in ("LIVEKIT_API_KEY", "LIVEKIT_API_SECRET", "CLU_PUBLIC_LIVEKIT_URL") if not env.get(k)]
        if missing:
            print("missing environment: " + ", ".join(missing), file=sys.stderr); return 2
        srv = CluVoiceServer(env)
        worker = None
        if env.get("CLU_RUN_AGENT", "1") == "1":
            import subprocess
            wenv = dict(env); wenv.setdefault("LIVEKIT_URL", srv.internal_url)
            worker = subprocess.Popen([sys.executable, str(Path(__file__).with_name("clu_agent.py")), "start"], env=wenv)
            log.info("agent worker started (pid %d)", worker.pid)
        try:
            web.run_app(srv.app(), host=env.get("CLU_BIND", "0.0.0.0"), port=int(env.get("CLU_PORT", "8089")))
        finally:
            if worker is not None:
                worker.terminate()
        return 0
    print(__doc__); return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
