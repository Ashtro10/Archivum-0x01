"""Isolated Kokoro TTS subprocess — keeps PyTorch out of the Primus/LLM process."""

from __future__ import annotations

import base64
import json
import sys


def main() -> int:
    try:
        from astra.configuration.loader import load_settings
        from ui.voice.kokoro_tts import KokoroTtsError, synthesize
    except Exception as exc:
        print(json.dumps({"ok": False, "error": f"Kokoro worker import failed: {exc}"}), flush=True)
        return 1

    try:
        settings = load_settings().voice
    except Exception as exc:
        print(json.dumps({"ok": False, "error": f"Kokoro worker settings failed: {exc}"}), flush=True)
        return 1

    for raw in sys.stdin:
        line = raw.strip()
        if not line:
            continue
        try:
            payload = json.loads(line)
        except json.JSONDecodeError:
            print(json.dumps({"ok": False, "error": "invalid worker request"}), flush=True)
            continue
        if str(payload.get("cmd") or "") == "quit":
            print(json.dumps({"ok": True}), flush=True)
            return 0
        text = str(payload.get("text") or "").strip()
        if not text:
            print(json.dumps({"ok": False, "error": "empty text"}), flush=True)
            continue
        try:
            result = synthesize(text, settings=settings)
        except KokoroTtsError as exc:
            print(json.dumps({"ok": False, "error": str(exc)}), flush=True)
            continue
        except Exception as exc:
            print(json.dumps({"ok": False, "error": str(exc)}), flush=True)
            continue
        print(
            json.dumps(
                {
                    "ok": True,
                    "pcm_b64": base64.b64encode(result.pcm).decode("ascii"),
                    "sample_rate": result.sample_rate,
                    "channels": result.channels,
                }
            ),
            flush=True,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
