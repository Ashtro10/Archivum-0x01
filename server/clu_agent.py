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
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from pathlib import Path

from livekit import rtc
from livekit.agents import Agent, AgentSession, JobContext, TurnHandlingOptions, WorkerOptions, cli, llm
from livekit.agents.voice import room_io

log = logging.getLogger("clu.agent")
AGENT_NAME = os.environ.get("CLU_AGENT_NAME", "clu")

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
    if os.environ.get("CLU_TURN", "model") == "vad":
        return "vad"
    try:
        from livekit.plugins.turn_detector.multilingual import MultilingualModel
        return MultilingualModel()
    except Exception as e:  # model files not downloaded
        log.warning("turn detector unavailable (%s); using VAD silence. Run: python clu_agent.py download-files", e)
        return "vad"


# ------------------------------------------------------------------------------------------------ agent
class Clu(Agent):
    def __init__(self) -> None:
        super().__init__(instructions=INSTRUCTIONS)


def prewarm(proc) -> None:
    from livekit.plugins import silero
    proc.userdata["vad"] = silero.VAD.load(min_silence_duration=float(os.environ.get("CLU_MIN_SILENCE", "0.45")))
    if os.environ.get("CLU_STACK", "cloud") == "local":
        proc.userdata["speech"] = load_local_speech()


async def entrypoint(ctx: JobContext) -> None:
    await ctx.connect()
    stack = os.environ.get("CLU_STACK", "cloud")
    stt, llm_, tts = local_stack(ctx.proc.userdata["vad"], ctx.proc.userdata["speech"]) if stack == "local" else cloud_stack()
    session = AgentSession(
        stt=stt, llm=llm_, tts=tts, vad=ctx.proc.userdata["vad"], user_away_timeout=None,
        turn_handling=TurnHandlingOptions(
            turn_detection=turn_detection(),
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

    @session.on("conversation_item_added")
    def on_item(ev) -> None:
        item = ev.item
        m = getattr(item, "metrics", None) or {}
        if getattr(item, "role", None) != "assistant" or not m:
            return
        payload = {out: round(m[k] * 1000) for k, out in keys.items() if isinstance(m.get(k), (int, float)) and m[k] >= 0}
        if payload:
            log.info("turn metrics %s", payload)
            asyncio.ensure_future(ctx.room.local_participant.publish_data(json.dumps(payload), topic="clu.metrics", reliable=True))


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    cli.run_app(WorkerOptions(entrypoint_fnc=entrypoint, prewarm_fnc=prewarm, agent_name=AGENT_NAME))
