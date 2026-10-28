# Clu server

Clu's **server engine**: the LiveKit Agents worker that listens, reasons and speaks for Clu, plus the token endpoint
that lets your phone join. The full guide (architecture, LiveKit Cloud vs. self-hosted, costs, measured latency) is
[docs/LIVEKIT_AGENT.md](../docs/LIVEKIT_AGENT.md). The *This device* engine in the app needs none of this.

| File | What it does |
|---|---|
| `clu_agent.py` | LiveKit Agents worker: VAD, turn detection, STT → streaming LLM → streaming TTS, interruption, transcripts, three small RPCs for the app |
| `clu_voice_server.py` | Token endpoint (device key → 10-minute room token), dispatches the agent, runs `clu_agent.py` as a child |
| `clu_controller.py` | The agent controller shared in behaviour with the on-device engine: decides when to research, keyless Wikipedia search, evidence for the model, answers checked before they are spoken, honest failures. Standard library only |
| `local_speech.py` | sherpa-onnx STT/TTS plugins for the fully local stack (`CLU_STACK=local`) |
| `fetch_models.sh` | Downloads the local stack's speech models (~124 MB, SHA-256 checked). The cloud stack needs none |
| `livekit.yaml.example` | Starting point for a self-hosted `livekit-server` |
| `tests/e2e_agents.py` | End-to-end test: real livekit-server + worker + local speech, mock LLM, Python stand-in phone |
| `tests/test_contract.py` | The behavioural contract shared with the device (`../contract/agent_contract.json`): `python -m unittest tests/test_contract.py` |

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

## Worker settings for a personal server

| Variable | Default | Why |
|---|---|---|
| `CLU_IDLE_PROCESSES` | `1` | One warm agent process. The framework's default (up to 4) loads the speech models several times at once and, on a 4-core computer, made every process miss the start-up timeout (first connection 20.5 s; 1.2 s after the fix) |
| `CLU_PROCESS_INIT_TIMEOUT` | `60` | Seconds a process may take to load its models (framework default 10) |
| `CLU_LOAD_THRESHOLD` | `0.95` | The framework's 0.7 marks the worker "full" whenever the computer is busy, e.g. while a local model is generating, and no agent gets dispatched |
| `CLU_MIN_SILENCE` | `0.45` | Silence (s) that ends an utterance. The largest share of the local stack's reply delay; lower is faster but can cut you off mid-pause |
| `CLU_SEARCH` | `wikipedia` | Automatic research (people, named subjects, follow-ups, current information) through Wikipedia's free API, as on the device. Only the subject of your own question is sent. `off` disables it (Clu then says it can't check) |
| `CLU_SEARCH_URL` | Wikipedia's API | Another MediaWiki-compatible search endpoint (the end-to-end test uses a local one) |
| `CLU_TURN` | `model` | `model` = LiveKit turn detector if its files were downloaded (`python clu_agent.py download-files`), otherwise VAD with a warning in the log; `vad` forces VAD |

## Test

```bash
# Real language model (any OpenAI-compatible server the test can start; {port} is filled in):
python tests/e2e_agents.py --livekit-server "$(command -v livekit-server)" \
  --kroko models/kroko-en --voice models/vits-piper-en_US-joe-medium --phone-voice <another Piper voice dir> \
  --llm-cmd "llama-server -m <model.gguf> --host 127.0.0.1 --port {port}" --llm-model <name> --out report.json --trace trace.jsonl
# Scripted mock instead of a model (pipeline only; replies say nothing about quality):
python tests/e2e_agents.py ... --mock-llm
```

The test plays the owner's questions (synthesised with a second Piper voice) into a real WebRTC microphone track and
checks the four input/output paths, that earlier turns reach the model, research through the shared agent contract
(evidence reaches the model, follow-up, injected instruction ignored, provider outage, no needless search), interruption by speech, the stop button, a
short reconnect, rejoining after the room closed, a LiveKit server outage, and short and prolonged model outages.
It measures connection, agent start, speech → first audio, text → first text and first audio, and recognition errors.
Note: if your shell has an HTTP(S) proxy set, unset it for this test; the worker's local `ws://` connection must not
go through the proxy.
