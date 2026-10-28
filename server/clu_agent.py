#!/usr/bin/env python3
"""Clu conversation agent (LiveKit Agents framework).

One agent session per Clu device room: speech recognition, the language model and speech synthesis run here (or
in services this worker calls), and the phone only streams audio and text over LiveKit. The framework provides
voice-activity detection, turn detection, streaming LLM -> streaming TTS, interruption (barge-in) and live
transcripts.

    python clu_agent.py download-files    # once: turn-detector and VAD model files
    python clu_agent.py start             # production worker (connects to LIVEKIT_URL)
    python clu_agent.py dev               # development worker with hot reload

Stacks (CLU_STACK):
  cloud  (default) LiveKit Inference through your LiveKit Cloud project - no other provider keys needed:
         STT deepgram/nova-3 (language "multi": English incl. Indian English, Hindi, code-switching),
         LLM openai/gpt-4.1-mini, TTS deepgram/aura-2 voice "zeus" (deep male). Billed to the LiveKit project
         (free tier includes a monthly allowance; see docs/LIVEKIT_AGENT.md).
  local  Everything on your own computer, no cloud: sherpa-onnx speech recognition (Kroko streaming English
         model) and voice (Piper Joe or Kokoro), and any OpenAI-compatible LLM server (Ollama, llama.cpp
         llama-server). Quality and speed depend on the computer.

Protocol with the Clu app (all standard LiveKit Agents conventions plus three small RPCs):
  * microphone audio track          -> STT              (agent audio track <- TTS)
  * text stream topic "lk.chat"     -> typed messages   (text stream "lk.transcription" <- user and agent transcripts)
  * participant attribute "lk.agent.state": initializing / listening / thinking / speaking
  * RPC "clu.set_audio_output" {"enabled": bool}  spoken replies on/off (text replies always)
  * RPC "clu.interrupt"                            stop the current reply now
  * RPC "clu.load_history" {"messages": [{"role": "user"|"assistant", "text": str}]}  continue an earlier chat
  * data topic "clu.metrics" (agent -> app): per-turn latency measured by the framework
  * data topic "clu.events" (agent -> app): {"type": "error", "component": "stt"|"llm"|"tts"|..., "recoverable": bool,
    "message": str} and {"type": "session_closed", "reason": str}. Messages carry no conversation content.
  * participant attributes "clu.stack", "clu.stt", "clu.llm", "clu.tts", "clu.turn": which providers this agent runs

Answers follow the same agent contract as the on-device engine (clu_controller.py): automatic research of people,
named subjects, follow-ups and current information through keyless Wikipedia search, answers checked against the
evidence before they are spoken, and honesty when the search fails or finds nothing (CLU_SEARCH=off disables it).
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import sys
import time
from pathlib import Path

from livekit import rtc
from livekit.agents import Agent, AgentSession, JobContext, TurnHandlingOptions, WorkerOptions, cli, llm
from livekit.agents.voice import room_io

log = logging.getLogger("clu.agent")
AGENT_NAME = os.environ.get("CLU_AGENT_NAME", "clu")


def _turn_model_files_present() -> bool:
    """The multilingual end-of-turn model's files in the local Hugging Face cache (python clu_agent.py download-files).
    Repository and revision as pinned by livekit-plugins-turn-detector 1.8.9 (models.py)."""
    try:
        from huggingface_hub import hf_hub_download
        for name, sub in (("languages.json", None), ("model_q8.onnx", "onnx")):
            hf_hub_download("livekit/turn-detector", name, subfolder=sub, revision="v0.4.1-intl", local_files_only=True)
        return True
    except Exception:
        return False


# The turn detector must be imported at module load: importing it registers an inference runner, and the worker
# starts its inference process only if runners are registered when it starts (a later import inside the job leaves
# the model without a process to run on). It is imported only when its files exist, because the inference process
# cannot start without them; `download-files` imports it so that the files get downloaded.
TURN_MODEL = os.environ.get("CLU_TURN", "model") != "vad" and ("download-files" in sys.argv or _turn_model_files_present())
if TURN_MODEL:
    from livekit.plugins.turn_detector.multilingual import MultilingualModel

INSTRUCTIONS = """You are Clu, the owner's personal AI assistant. You talk with the owner by voice and text.
Style: calm, composed, precise and direct; dry wit when it fits; never servile.
Rules:
- Answer the question first. No greetings, no "As an AI", no filler, no repeating the question.
- Keep spoken answers short (one to three sentences) unless asked for detail. Use plain spoken sentences:
  no markdown, lists, emoji or URLs.
- Speech is transcribed automatically and names or places may be misheard. Use the conversation to work out the
  meaning; if it is clear, act on it; if two readings remain plausible, ask one short question.
- If you don't know something or it may be out of date, say so plainly. Never invent facts.
- Reply in the language the owner used (English or Hindi, or Hinglish when they mix)."""


# ------------------------------------------------------------------------------------------------ component stacks
def cloud_stack():
    from livekit.agents import inference
    stt = inference.STT(os.environ.get("CLU_STT", "deepgram/nova-3"), language=os.environ.get("CLU_STT_LANGUAGE", "multi"))
    llm_ = inference.LLM(os.environ.get("CLU_LLM", "openai/gpt-4.1-mini"))
    tts_model = os.environ.get("CLU_TTS", "deepgram/aura-2")
    tts = inference.TTS(tts_model, voice=os.environ.get("CLU_TTS_VOICE", "zeus"))
    return stt, llm_, tts


def load_local_speech():
    """Loads the sherpa-onnx models once per worker process (prewarm), not when a call starts."""
    from local_speech import SherpaSTT, SherpaTTS
    models = Path(os.environ.get("CLU_MODELS", Path(__file__).parent / "models"))
    return (SherpaSTT(models / os.environ.get("CLU_ASR_DIR", "kroko-en")),
            SherpaTTS(models / os.environ.get("CLU_TTS_DIR", "vits-piper-en_US-joe-medium"),
                      speaker=int(os.environ.get("CLU_TTS_SPEAKER", "0")), speed=float(os.environ.get("CLU_TTS_SPEED", "0.95"))))


def local_stack(vad, speech):
    from livekit.agents import stt as lk_stt, tts as lk_tts, tokenize
    from livekit.plugins import openai as lk_openai
    sherpa_stt, sherpa_tts = speech
    # Utterances are cut by the VAD and recognised whole; replies are spoken sentence by sentence as they stream.
    stt = lk_stt.StreamAdapter(stt=sherpa_stt, vad=vad)
    tts = lk_tts.StreamAdapter(tts=sherpa_tts, sentence_tokenizer=tokenize.blingfire.SentenceTokenizer())
    llm_ = lk_openai.LLM(model=os.environ.get("CLU_LLM_MODEL", "llama3.2:3b"), base_url=os.environ.get("CLU_LLM_URL", "http://127.0.0.1:11434/v1"),
                         api_key=os.environ.get("CLU_LLM_KEY", "local"), max_completion_tokens=int(os.environ.get("CLU_LLM_MAX_TOKENS", "300")))
    return stt, llm_, tts


def turn_detection():
    """LiveKit's multilingual end-of-turn model (understands that "I want to go to..." is unfinished) when its model
    files are present; plain VAD silence otherwise (CLU_TURN=vad forces that)."""
    if TURN_MODEL:
        return MultilingualModel()
    if os.environ.get("CLU_TURN", "model") != "vad":
        log.warning("turn detector model files not found; using VAD silence. Run: python clu_agent.py download-files")
    return "vad"


def providers(stack: str, turn) -> dict[str, str]:
    """What this agent runs, for the app's diagnostics (no keys or URLs with credentials)."""
    if stack == "local":
        stt, tts = f"sherpa-onnx {os.environ.get('CLU_ASR_DIR', 'kroko-en')}", f"sherpa-onnx {os.environ.get('CLU_TTS_DIR', 'vits-piper-en_US-joe-medium')}"
        llm_ = f"{os.environ.get('CLU_LLM_MODEL', 'llama3.2:3b')} (OpenAI-compatible server)"
    else:
        stt = os.environ.get("CLU_STT", "deepgram/nova-3")
        llm_ = os.environ.get("CLU_LLM", "openai/gpt-4.1-mini")
        tts = f"{os.environ.get('CLU_TTS', 'deepgram/aura-2')} {os.environ.get('CLU_TTS_VOICE', 'zeus')}"
    return {"clu.stack": stack, "clu.stt": stt, "clu.llm": llm_, "clu.tts": tts, "clu.turn": "vad" if turn == "vad" else "turn-detector"}


# ------------------------------------------------------------------------------------------------ agent
def search_provider():
    """Keyless Wikipedia search, as on the device (CLU_SEARCH=off turns research off; CLU_SEARCH_URL points elsewhere,
    e.g. a test server). Only the subject of the owner's own question is ever sent."""
    from clu_controller import WikipediaSearch
    if os.environ.get("CLU_SEARCH", "wikipedia") == "off":
        return None
    return WikipediaSearch(os.environ.get("CLU_SEARCH_URL", "https://en.wikipedia.org/w/api.php"))


class Clu(Agent):
    """The same agent behaviour as the on-device engine (clu_controller.py, contract/agent_contract.json): decides when
    to research, runs bounded searches, gives the model the evidence, checks the answer before it is spoken (one
    correction attempt, then a clearly attributed quote) and is honest when a search fails or finds nothing."""

    def __init__(self) -> None:
        super().__init__(instructions=INSTRUCTIONS)
        from clu_controller import Controller
        self.controller = Controller(search_provider(), instructions=INSTRUCTIONS)

    async def llm_node(self, chat_ctx: llm.ChatContext, tools, model_settings):
        msgs = [i for i in chat_ctx.items if getattr(i, "type", "message") == "message" and i.role in ("user", "assistant")]
        text = next((m.text_content or "" for m in reversed(msgs) if m.role == "user"), "")
        history = [m.text_content or "" for m in msgs[:-1]]

        from clu_controller import EXTRACT_PREFIX, for_prompt
        base = chat_ctx.copy()
        for n, i in enumerate(base.items):  # earlier quotes go back to the model without Clu's attribution framing
            if getattr(i, "type", "message") == "message" and i.role == "assistant" and (i.text_content or "").startswith(EXTRACT_PREFIX):
                base.items[n] = i.model_copy(update={"content": [for_prompt(i.text_content)]})  # (the session's own history is untouched)

        async def generate(extra: list[dict]):
            ctx = base.copy()
            for m in extra:
                ctx.add_message(role=m["role"], content=m["content"])
            async for chunk in Agent.default.llm_node(self, ctx, tools, model_settings):
                t = chunk if isinstance(chunk, str) else (chunk.delta.content if getattr(chunk, "delta", None) else None)
                if t:
                    yield t

        async for piece in self.controller.respond(text, history, generate):
            yield piece
        t = self.controller.last
        if t is not None:  # no conversation content in the log
            log.info("turn: decision=%s queries=%d answer=%s corrections=%d", t.decision.kind, len(t.queries), t.kind, t.repairs)


def prewarm(proc) -> None:
    from livekit.plugins import silero
    proc.userdata["vad"] = silero.VAD.load(min_silence_duration=float(os.environ.get("CLU_MIN_SILENCE", "0.45")))
    if os.environ.get("CLU_STACK", "cloud") == "local":
        proc.userdata["speech"] = load_local_speech()


async def entrypoint(ctx: JobContext) -> None:
    await ctx.connect()
    stack = os.environ.get("CLU_STACK", "cloud")
    stt, llm_, tts = local_stack(ctx.proc.userdata["vad"], ctx.proc.userdata["speech"]) if stack == "local" else cloud_stack()
    turn = turn_detection()
    session = AgentSession(
        stt=stt, llm=llm_, tts=tts, vad=ctx.proc.userdata["vad"], user_away_timeout=None,
        turn_handling=TurnHandlingOptions(
            turn_detection=turn,
            endpointing={"min_delay": float(os.environ.get("CLU_MIN_ENDPOINT", "0.4")), "max_delay": float(os.environ.get("CLU_MAX_ENDPOINT", "3.0"))},
            # Barge-in: the owner's speech stops Clu. "adaptive" (LiveKit Cloud) tells real interruptions from "mm-hm";
            # "vad" uses voice activity only. A cough that stops playback resumes it after 1.5 s.
            interruption={"enabled": True, "mode": os.environ.get("CLU_INTERRUPTION", "adaptive" if stack == "cloud" else "vad"),
                          "min_duration": 0.4, "resume_false_interruption": True, "false_interruption_timeout": 1.5},
            preemptive_generation={"enabled": True},  # start the reply while the end of turn is being confirmed
        ),
    )
    agent = Clu()
    _wire_rpc(ctx, session, agent)
    _wire_metrics(ctx, session)
    _wire_errors(ctx, session)
    await ctx.room.local_participant.set_attributes(providers(stack, turn))
    await session.start(agent=agent, room=ctx.room, room_options=room_io.RoomOptions(
        text_input=True, audio_input=True, audio_output=True, text_output=room_io.TextOutputOptions(sync_transcription=True),
        close_on_disconnect=False))
    log.info("session started in %s (stack=%s)", ctx.room.name, stack)


def _wire_rpc(ctx: JobContext, session: AgentSession, agent: Agent) -> None:
    lp = ctx.room.local_participant

    @lp.register_rpc_method("clu.set_audio_output")
    async def set_audio_output(data: rtc.RpcInvocationData) -> str:
        enabled = bool(json.loads(data.payload or "{}").get("enabled", True))
        session.output.set_audio_enabled(enabled)
        return json.dumps({"enabled": enabled})

    @lp.register_rpc_method("clu.interrupt")
    async def interrupt(data: rtc.RpcInvocationData) -> str:
        await session.interrupt()
        return "{}"

    @lp.register_rpc_method("clu.load_history")
    async def load_history(data: rtc.RpcInvocationData) -> str:
        """Continues an earlier conversation in a fresh session. A session that already has turns (the app just
        reconnected to the same session) keeps its own history, so nothing is duplicated."""
        msgs = json.loads(data.payload or "{}").get("messages", [])[-24:]
        if any(getattr(i, "role", None) in ("user", "assistant") for i in agent.chat_ctx.items):
            return json.dumps({"loaded": 0, "reason": "session already has history"})
        chat = agent.chat_ctx.copy()
        for m in msgs:
            role, text = m.get("role"), (m.get("text") or "").strip()
            if role in ("user", "assistant") and text:
                chat.add_message(role=role, content=text[:2000])
        await agent.update_chat_ctx(chat)
        return json.dumps({"loaded": len(msgs)})


def _wire_metrics(ctx: JobContext, session: AgentSession) -> None:
    """Per-turn latency measured by the framework (end of speech -> transcript, LLM first token, TTS first audio,
    end-to-end), logged and sent to the app on data topic "clu.metrics"."""
    keys = {"transcription_delay": "transcription_delay_ms", "end_of_turn_delay": "end_of_turn_delay_ms", "llm_node_ttft": "llm_ttft_ms",
            "tts_node_ttfb": "tts_ttfb_ms", "e2e_latency": "speech_end_to_first_audio_ms"}

    user_metrics: dict = {}

    @session.on("conversation_item_added")
    def on_item(ev) -> None:
        item = ev.item
        m = getattr(item, "metrics", None) or {}
        role = getattr(item, "role", None)
        if role == "user":  # transcription and end-of-turn delays are reported on the owner's message
            user_metrics.clear(); user_metrics.update(m)
            return
        if role != "assistant" or not m:
            return
        m = {**user_metrics, **m}; user_metrics.clear()
        payload = {out: round(m[k] * 1000) for k, out in keys.items() if isinstance(m.get(k), (int, float)) and m[k] >= 0}
        if payload:
            log.info("turn metrics %s", payload)
            asyncio.ensure_future(ctx.room.local_participant.publish_data(json.dumps(payload), topic="clu.metrics", reliable=True))


def _wire_errors(ctx: JobContext, session: AgentSession) -> None:
    """Speech recognition, model and voice failures go to the app (data topic "clu.events") so it can say what is
    wrong instead of waiting. When the framework gives up (repeated unrecoverable errors) the job ends: the app then
    sees that the agent left, reports it, and asks the token server for a fresh agent."""
    kinds = {"stt_error": "stt", "llm_error": "llm", "tts_error": "tts", "realtime_model_error": "llm"}

    def publish(payload: dict) -> None:
        asyncio.ensure_future(ctx.room.local_participant.publish_data(json.dumps(payload), topic="clu.events", reliable=True))

    def describe(err) -> str:
        e = getattr(err, "error", None)
        # Provider errors name the failure (connection refused, 401, timeout); keep it short and free of payloads.
        text = f"{type(e).__name__}: {e}" if e is not None else str(err)
        return text.replace("\n", " ")[:200]

    @session.on("error")
    def on_error(ev) -> None:
        err = ev.error
        component = kinds.get(getattr(err, "type", ""), "agent")
        recoverable = bool(getattr(err, "recoverable", False))
        log.warning("%s error (recoverable=%s): %s", component, recoverable, describe(err))
        publish({"type": "error", "component": component, "recoverable": recoverable, "message": describe(err)})

    @session.on("close")
    def on_close(ev) -> None:
        reason = getattr(ev.reason, "value", str(ev.reason))
        payload = {"type": "session_closed", "reason": reason}
        if ev.error is not None:
            payload.update(component=kinds.get(getattr(ev.error, "type", ""), "agent"), message=describe(ev.error))
        log.warning("session closed: %s", payload)
        publish(payload)
        if reason == "error":
            asyncio.get_event_loop().call_later(0.5, lambda: ctx.shutdown(reason="agent session failed"))


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    cli.run_app(WorkerOptions(
        entrypoint_fnc=entrypoint, prewarm_fnc=prewarm, agent_name=AGENT_NAME,
        # One owner, one conversation: one warm process is enough. The framework's production default is up to four,
        # each loading the speech models at once, which on a 4-core computer made every one of them miss the 10 s
        # start-up timeout and get killed (measured: first connection 20.5 s instead of ~1 s).
        num_idle_processes=int(os.environ.get("CLU_IDLE_PROCESSES", "1")),
        initialize_process_timeout=float(os.environ.get("CLU_PROCESS_INIT_TIMEOUT", "60")),
        # The default (0.7) marks the worker "full" whenever the computer is busy - e.g. while a local language model
        # is generating on the same machine - and LiveKit then dispatches no agent.
        load_threshold=float(os.environ.get("CLU_LOAD_THRESHOLD", "0.95")),
    ))
