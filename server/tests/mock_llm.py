"""MOCK OpenAI-compatible chat-completions server for end-to-end tests of the agent pipeline (not a language model).

Streams deterministic replies with a configurable first-token delay and token rate, so speech recognition, turn
handling, streaming TTS, interruption and transcripts can be tested without a real LLM. Replies reveal what the
agent sent: the last user message and how many messages were in the context.

    python mock_llm.py PORT [first_token_ms] [ms_per_word]
"""
import asyncio
import json
import sys
import time

from aiohttp import web

FIRST_MS = int(sys.argv[2]) if len(sys.argv) > 2 else 300
WORD_MS = int(sys.argv[3]) if len(sys.argv) > 3 else 30


def reply_for(messages: list) -> str:
    users = [m for m in messages if m.get("role") == "user"]
    last = users[-1]["content"] if users else ""
    if isinstance(last, list):
        last = " ".join(p.get("text", "") for p in last if isinstance(p, dict))
    n = sum(1 for m in messages if m.get("role") in ("user", "assistant"))
    if "story" in last.lower():
        return ("Once there was a lighthouse keeper who counted ships every night. " * 6 + "The end.").strip()
    return f"I heard: {last.strip().rstrip('.?!')}. Context has {n} messages."


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
