# Clu voice server (optional, self-hosted)

Everything Clu's **optional LiveKit mode** needs on the server side, all free and open source. Offline mode, the
default, needs none of this.

| Component | What it does | Where |
|---|---|---|
| `livekit-server` | Media/signalling server (LiveKit, Apache-2.0) | [GitHub releases](https://github.com/livekit/livekit/releases) (tested: v1.13.10) |
| `clu_voice_server.py serve` | Token endpoint (device keys → short-lived room tokens) and voice-agent supervisor | this folder |
| `clu_voice_agent.py` | Clu's "ears and mouth": sherpa-onnx speech recognition + Piper voice, barge-in | this folder |

Clu's brain is **not** here. Model, memory, tools and permissions stay on the phone or tablet. The agent only turns
speech into text for Clu, and Clu's replies back into speech.

## Requirements

* A computer on the same network as the phone/tablet, e.g. a laptop or desktop on home Wi-Fi or on the phone's
  hotspot. Linux, macOS or Windows (WSL); x86-64 or arm64; Python 3.10+.
* About 400 MB disk. CPU only, no GPU needed. The agent uses roughly one core while you speak.
* No cloud account, no paid subscription, no internet once the files below are downloaded.

## Setup (one time)

```bash
cd server
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt          # pinned versions, see requirements.txt
./fetch_models.sh models                 # ~115 MB, SHA-256 checked (same models as the app)

# LiveKit server: download the release for your OS, then create your own keys:
livekit-server generate-keys             # prints an API key and secret
cp livekit.yaml.example livekit.yaml     # paste the key/secret into livekit.yaml
```

## Run

```bash
livekit-server --config livekit.yaml &                         # ports 7880 (signalling), 7881/tcp, 50000-50100/udp

export LIVEKIT_API_KEY=<key> LIVEKIT_API_SECRET=<secret>
export CLU_PUBLIC_LIVEKIT_URL=ws://<this-computer's-LAN-IP>:7880
export CLU_MODELS=$PWD/models
python clu_voice_server.py add-device phone                    # prints the device key ONCE
python clu_voice_server.py serve                               # token endpoint on :8089
```

Then in Clu, go to **⋮ → Voice → Conversation mode**:
1. **Set device key**: paste the key printed by `add-device`. It is stored encrypted on the device.
2. **LiveKit settings → Token endpoint**: `http://<this-computer's-LAN-IP>:8089/token`.
3. **Use LiveKit**, then **Start LiveKit conversation** (or just use the wake word).

Unencrypted `http://` / `ws://` is accepted **only for private-network addresses** (home Wi-Fi, hotspot). For a
server reachable from the internet, put both behind TLS (`https://`, `wss://`), for example with Caddy or nginx,
and set `use_external_ip: true` or configure TURN in `livekit.yaml`.

## Security

* The LiveKit API secret stays on this computer. Clu never receives it.
* Device keys: `add-device <id>`, `list-devices`, `revoke-device <id>`. Only a SHA-256 of each key is stored, in
  `devices.json` (mode 600).
* Each device can only join its own room (`clu-<id>`), publish only its microphone, for 10 minutes per token. It
  gets no admin and cannot create rooms. Repeated bad keys are throttled.
* The agent sees what you say and what Clu answers, because it does the speech processing. Run it on a machine you
  trust.

## Test

```bash
python tests/test_e2e.py "$(command -v livekit-server)" models results.json
```

This starts a local `livekit-server` with test keys and checks:
* token security
* that an agent joins
* speech → transcript → reply → audio
* barge-in
* "End of the line"
* agent shutdown
