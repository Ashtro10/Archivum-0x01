# Clu server

Clu's **server engine**: the LiveKit Agents worker that listens, reasons and speaks for Clu, plus the token endpoint
that lets your phone join. The full guide (architecture, LiveKit Cloud vs. self-hosted, costs, measured latency) is
[docs/LIVEKIT_AGENT.md](../docs/LIVEKIT_AGENT.md). The *This device* engine in the app needs none of this.

| File | What it does |
|---|---|
| `clu_agent.py` | LiveKit Agents worker: VAD, turn detection, STT → streaming LLM → streaming TTS, interruption, transcripts, three small RPCs for the app |
| `clu_voice_server.py` | Token endpoint (device key → 10-minute room token), dispatches the agent, runs `clu_agent.py` as a child |
| `local_speech.py` | sherpa-onnx STT/TTS plugins for the fully local stack (`CLU_STACK=local`) |
| `fetch_models.sh` | Downloads the local stack's speech models (~124 MB, SHA-256 checked). The cloud stack needs none |
| `livekit.yaml.example` | Starting point for a self-hosted `livekit-server` |
| `tests/e2e_agents.py` | End-to-end test: real livekit-server + worker + local speech, mock LLM, Python stand-in phone |

## Quick start (LiveKit Cloud)

```bash
cd server && python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
python clu_agent.py download-files
export LIVEKIT_URL=wss://<project>.livekit.cloud LIVEKIT_API_KEY=<key> LIVEKIT_API_SECRET=<secret>
export CLU_PUBLIC_LIVEKIT_URL=$LIVEKIT_URL CLU_INTERNAL_LIVEKIT_URL=$LIVEKIT_URL
python clu_voice_server.py add-device phone     # prints the device key once
python clu_voice_server.py serve                # :8089
```

Then in Clu: Settings → Connection → *Clu server* → token endpoint `http://<this-computer's-LAN-IP>:8089/token`,
paste the device key, then **Test connection**.

Self-hosted without any cloud account: see docs/LIVEKIT_AGENT.md §3B (`CLU_STACK=local`, Ollama or another
OpenAI-compatible LLM).

## Security

* The LiveKit API secret (and any provider key) stays on this computer. Clu never receives it.
* Device keys: `add-device <id>`, `list-devices`, `revoke-device <id>`. Only a SHA-256 of each key is stored, in
  `devices.json` (mode 600). A device can join only its own room `clu-<id>`; tokens last 10 minutes and grant no
  admin rights. Repeated bad keys are throttled.
* The agent hears what you say and writes Clu's answers, so run it on a machine you trust. With the cloud stack,
  audio and text also go to LiveKit Inference and the model providers behind it.
* Plain `http://` and `ws://` are accepted only for private-network addresses. Put internet-facing servers behind TLS.

## Test

```bash
python tests/e2e_agents.py --livekit-server "$(command -v livekit-server)" \
  --kroko models/kroko-en --voice models/vits-piper-en_US-joe-medium --out report.json
```

Checks dispatch, the four input/output paths, follow-up context, interruption and reconnect. The language model is a
mock, so the test measures the pipeline, not answer quality. Note: if your shell has an HTTP(S) proxy set, unset it
for this test; the worker's local `ws://` connection must not go through the proxy.
