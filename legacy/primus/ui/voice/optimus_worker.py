"""Isolated Optimus TTS subprocess — keeps RVC out of the Primus/LLM process."""

from __future__ import annotations

import base64
import json
import os
import sys

os.environ.setdefault("RVC_ENCODER", "contentvec")


def main() -> int:
    try:
        payload = json.loads(sys.stdin.read())
        text = str(payload.get("text", "") or "").strip()
        if not text:
            print(json.dumps({"ok": False, "error": "empty text"}))
            return 1
        from astra.configuration.loader import load_settings
        from ui.voice.optimus_tts import OptimusTtsError, synthesize

        settings = load_settings().voice
        result = synthesize(text, settings=settings)
        print(
            json.dumps(
                {
                    "ok": True,
                    "pcm_b64": base64.b64encode(result.pcm).decode("ascii"),
                    "sample_rate": result.sample_rate,
                    "channels": result.channels,
                }
            )
        )
        return 0
    except OptimusTtsError as exc:
        print(json.dumps({"ok": False, "error": str(exc)}))
        return 1
    except Exception as exc:
        print(json.dumps({"ok": False, "error": str(exc)}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
