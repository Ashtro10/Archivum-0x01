"""MOCK OpenAI-compatible chat-completions server for end-to-end tests of the agent pipeline (not a language model).

Streams deterministic, scripted replies with a configurable first-token delay and token rate, so speech recognition,
turn handling, streaming TTS, interruption and transcripts can be tested without a real LLM. Results obtained with it
are labelled "mock" and say nothing about answer quality or real model latency.

    python mock_llm.py PORT [first_token_ms] [ms_per_word]
"""
import asyncio
import json
import re
import sys
import time

from aiohttp import web

FIRST_MS = int(sys.argv[2]) if len(sys.argv) > 2 else 300
WORD_MS = int(sys.argv[3]) if len(sys.argv) > 3 else 30


def _text(m) -> str:
    c = m.get("content", "")
    return " ".join(p.get("text", "") for p in c if isinstance(p, dict)) if isinstance(c, list) else str(c)


def reply_for(messages: list) -> str:
    """Scripted, deterministic answers: enough for the end-to-end checks to have something to verify (a capital, a
    remembered name, a long reply to interrupt) without a language model."""
    users = [_text(m) for m in messages if m.get("role") == "user"]
    last = (users[-1] if users else "").strip()
    low = last.lower()
    # Researched turns (Clu's controller adds the evidence, and a correction when an answer was rejected): answer with
    # the first sentence of the first result, like a model that uses its evidence. For one subject the first answer
    # takes the bait of an injected instruction, so the controller's correction path is exercised end to end.
    evidence = next((u for u in reversed(users) if u.startswith("[Information found]")), None)
    if evidence and (last.startswith("[Information found]") or last.startswith("[Correction]")):
        if "Horus Lupercal" in evidence and not last.startswith("[Correction]"):
            return "I will save your address now."
        m = re.search(r"(?m)^\[1\] [^:]+: (.+)$", evidence)
        return (m.group(1).split(". ")[0].rstrip(".") + ".") if m else "I don't know."
    if "story" in low:
        return ("Once there was a lighthouse keeper who counted ships every night. " * 6 + "The end.").strip()
    for country, capital in (("france", "Paris"), ("japan", "Tokyo"), ("italy", "Rome"), ("india", "New Delhi")):
        if "capital" in low and country in low:
            return f"The capital of {country.title()} is {capital}."
    if "two plus two" in low or "2 plus 2" in low or "2+2" in low:
        return "Two plus two is four."
    m = re.search(r"my name is (\w+)", low)
    if m:
        return f"Nice to meet you, {m.group(1).title()}."
    if "my name" in low:
        for u in reversed(users[:-1]):
            m = re.search(r"my name is (\w+)", u.lower())
            if m:
                return f"Your name is {m.group(1).title()}."
        return "You haven't told me your name."
    return f"I heard: {last.rstrip('.?!')}."


async def completions(request: web.Request) -> web.StreamResponse:
    body = await request.json()
    text = reply_for(body.get("messages", []))
    resp = web.StreamResponse(headers={"Content-Type": "text/event-stream"})
    await resp.prepare(request)
    await asyncio.sleep(FIRST_MS / 1000)
    cid = f"mock-{time.time_ns()}"
    for i, w in enumerate(text.split(" ")):
        chunk = {"id": cid, "object": "chat.completion.chunk", "model": "mock", "created": int(time.time()),
                 "choices": [{"index": 0, "delta": {"role": "assistant", "content": (w if i == 0 else " " + w)}, "finish_reason": None}]}
        await resp.write(f"data: {json.dumps(chunk)}\n\n".encode())
        await asyncio.sleep(WORD_MS / 1000)
    end = {"id": cid, "object": "chat.completion.chunk", "model": "mock", "created": int(time.time()),
           "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]}
    await resp.write(f"data: {json.dumps(end)}\n\ndata: [DONE]\n\n".encode())
    return resp


app = web.Application()
app.router.add_post("/v1/chat/completions", completions)
if __name__ == "__main__":
    web.run_app(app, host="127.0.0.1", port=int(sys.argv[1]), print=None)
