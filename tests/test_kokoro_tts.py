"""Tests for Kokoro TTS."""

from __future__ import annotations

import base64
import io
import json
import unittest
from unittest.mock import MagicMock, patch

import numpy as np


class _FakeKokoroProc:
    def __init__(self, pcm: bytes = b"\x00\x01", sample_rate: int = 24000) -> None:
        self._pcm = pcm
        self._rate = sample_rate
        self.requests = 0
        self.stdin = self
        self.stdout = self
        self.stderr = None
        self.returncode = None
        self._line = b""

    def poll(self) -> int | None:
        return self.returncode

    def write(self, data: bytes) -> int:
        self.requests += 1
        payload = {
            "ok": True,
            "pcm_b64": base64.b64encode(self._pcm).decode("ascii"),
            "sample_rate": self._rate,
            "channels": 1,
        }
        self._line = json.dumps(payload).encode("utf-8") + b"\n"
        return len(data)

    def flush(self) -> None:
        return None

    def readline(self) -> bytes:
        return self._line

    def kill(self) -> None:
        self.returncode = 1

    def wait(self, timeout: float | None = None) -> int | None:
        return self.returncode


class KokoroTtsTest(unittest.TestCase):
    def test_empty_text_rejected(self) -> None:
        from astra.configuration.settings import VoiceSettings
        from ui.voice.kokoro_tts import KokoroTtsError, synthesize

        with self.assertRaises(KokoroTtsError):
            synthesize(" ", settings=VoiceSettings(tts_engine="kokoro"))

    def test_returns_pcm_from_mock_pipeline(self) -> None:
        from astra.configuration.settings import VoiceSettings
        from ui.voice.kokoro_tts import synthesize

        result_obj = MagicMock()
        result_obj.audio = np.array([0.25, -0.5], dtype=np.float32)
        pipe = MagicMock()
        pipe.return_value = [result_obj]

        with patch("ui.voice.kokoro_tts._load", return_value=pipe):
            result = synthesize("Hello Primus.", settings=VoiceSettings(tts_engine="kokoro"))
        self.assertEqual(24000, result.sample_rate)
        self.assertGreater(len(result.pcm), 0)


class KokoroIsolatedWorkerTest(unittest.TestCase):
    def tearDown(self) -> None:
        from ui.voice.speech import _stop_kokoro_worker

        _stop_kokoro_worker()

    def test_speech_synthesize_uses_kokoro_subprocess(self) -> None:
        from astra.configuration.settings import VoiceSettings
        from ui.voice.speech import synthesize

        fake = _FakeKokoroProc(pcm=b"\x11\x22")
        with patch("ui.voice.speech.subprocess.Popen", return_value=fake) as popen:
            result = synthesize("Hello Primus.", settings=VoiceSettings(tts_engine="kokoro"))
            again = synthesize("Second line.", settings=VoiceSettings(tts_engine="kokoro"))
        self.assertEqual(b"\x11\x22", result.pcm)
        self.assertEqual(24000, result.sample_rate)
        self.assertEqual(b"\x11\x22", again.pcm)
        self.assertEqual(1, popen.call_count)
        self.assertEqual(2, fake.requests)
        argv = popen.call_args[0][0]
        self.assertEqual("-m", argv[1])
        self.assertEqual("ui.voice.kokoro_worker", argv[2])

    def test_warmup_does_not_load_kokoro_in_parent(self) -> None:
        from astra.configuration.settings import VoiceSettings
        from ui.voice.speech import warmup

        fake = _FakeKokoroProc()
        with patch("ui.voice.speech.subprocess.Popen", return_value=fake), patch(
            "ui.voice.kokoro_tts.warmup"
        ) as in_process:
            warmup(VoiceSettings(tts_engine="kokoro", tts_enabled=True))
        in_process.assert_not_called()
        self.assertEqual(1, fake.requests)

    def test_worker_json_protocol(self) -> None:
        from astra.configuration.settings import VoiceSettings
        from ui.voice.kokoro_tts import KokoroTtsResult
        from ui.voice.kokoro_worker import main

        class _Bundle:
            voice = VoiceSettings(tts_engine="kokoro")

        stdout = io.StringIO()
        stdin = io.StringIO(json.dumps({"text": "Hi"}) + "\n")
        result = KokoroTtsResult(pcm=b"\x01\x02", sample_rate=24000, channels=1)
        with patch("sys.stdin", stdin), patch("sys.stdout", stdout), patch(
            "astra.configuration.loader.load_settings", return_value=_Bundle()
        ), patch("ui.voice.kokoro_tts.synthesize", return_value=result):
            code = main()
        self.assertEqual(0, code)
        payload = json.loads(stdout.getvalue().strip())
        self.assertTrue(payload["ok"])
        self.assertEqual(base64.b64encode(b"\x01\x02").decode("ascii"), payload["pcm_b64"])
        self.assertEqual(24000, payload["sample_rate"])


class WebServerClientAbortTest(unittest.TestCase):
    def test_handler_ignores_client_abort(self) -> None:
        from pathlib import Path

        source = Path(__file__).resolve().parents[1] / "ui" / "web_server.py"
        text = source.read_text(encoding="utf-8")
        self.assertIn("PrimusHTTPServer", text)
        self.assertIn("_CLIENT_ABORT", text)
        self.assertIn("ConnectionAbortedError", text)
        self.assertIn("handle_error", text)


__all__ = []
