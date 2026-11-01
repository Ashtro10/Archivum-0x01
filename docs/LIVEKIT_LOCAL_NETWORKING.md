# CLU's LiveKit server on your network

CLU runs its own LiveKit server (open-source `livekit-server` 1.9.12, a single native Windows program — no Docker, no
LiveKit Cloud). The host starts it when voice is first used and stops it after 10 minutes without a voice session
(unless phone access is on).

## What listens where

| Listener | Address | When | Who uses it |
|---|---|---|---|
| CLU's window/API | `127.0.0.1:<random>` | always | the window (owner token) |
| LiveKit signalling (WebSocket) + health | `127.0.0.1:7880` (next free port if taken) | while voice is in use | the window, CLU's agent |
| LiveKit media (WebRTC) | ICE/TCP 7881, ICE/UDP 7882 (next free ports) | same | the window, CLU's agent |
| **Phone access only:** the same three LiveKit ports on the PC's **private-network** address | e.g. `192.168.1.20:7880-7882` | only while *phone access* is on | paired phones |
| **Phone access only:** phone token endpoint | `<private address>:47822/token` | only while *phone access* is on | paired phones (device key) |

The configuration (`%LOCALAPPDATA%\CLU\voice\livekit.yaml`, owner-only) is rewritten on every start:

* `bind_addresses`: `127.0.0.1` and, only with phone access, the one private address CLU detected (10/8, 172.16/12,
  192.168/16, or 100.64/10 used by some hotspots). Never `0.0.0.0`, never a public address.
* `rtc.use_external_ip: false`, no STUN lookups, no TURN, `node_ip` = loopback or that private address, and ICE
  candidates restricted to `127.0.0.0/8` plus that address. Nothing is advertised beyond the local network.
* keys: CLU's own generated API key and 288-bit secret (`voice\livekit-keys.json`, owner-only), never LiveKit's
  development keys.
* rooms auto-created, closed 120 s after the last participant leaves, at most 6 participants.

The LiveKit server never needs the internet. The phone and PC talk directly over Wi-Fi or the phone's hotspot.

## Windows firewall

When phone access is turned on for the first time, Windows Defender Firewall may ask whether **livekit-server** and
**CLU (OpenJDK Platform binary)** may communicate. Allow **private networks** only. If the prompt was dismissed or
"public" was chosen, the phone cannot connect: Windows Security → Firewall & network protection → Allow an app →
tick *Private* for both. Also make sure the Wi-Fi network is set to *Private* in Windows network settings.

Nothing has to be opened on the router: do **not** forward these ports to the internet.

## Changing networks

The private address is detected when the server starts. If the PC moves to another network (or the address changes),
turn phone access off and on (Settings → Voice) and re-enter the endpoint on the phone if it changed. A running desktop
voice session reconnects by itself when the server restarts.

## Checking it

* Settings → Voice shows the server state, its local URL and the phone endpoint.
* `GET /v1/voice/status` (owner token) returns the same as JSON, including per-session timings.
* Log: `%LOCALAPPDATA%\CLU\voice\livekit.log` (rotated at 2 MB). The host restarts a crashed server up to 3 times in
  5 minutes, then reports the failure instead of looping.
