"""Tests for Optimus Prime RVC TTS."""

from __future__ import annotations

import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import numpy as np


class OptimusTtsTest(unittest.TestCase):
    def test_default_model_path(self) -> None:
        from ui.voice.optimus_tts import default_optimus_dir, resolve_model_path
        from astra.configuration.settings import VoiceSettings

        expected = default_optimus_dir() / "optimus_720e_5040s.pth"
        if not expected.is_file():
            self.skipTest("Optimus model missing")
        self.assertEqual(expected.resolve(), resolve_model_path(VoiceSettings()))

    def test_empty_text_rejected(self) -> None:
        from astra.configuration.settings import VoiceSettings
        from ui.voice.optimus_tts import OptimusTtsError, synthesize

        with self.assertRaises(OptimusTtsError):
            synthesize(" ", settings=VoiceSettings())

    def test_kokoro_then_rvc_pipeline(self) -> None:
        from astra.configuration.settings import VoiceSettings
        from ui.voice.optimus_tts import OptimusTtsResult, synthesize

        settings = VoiceSettings()
        import numpy as np

        with patch(
            "ui.voice.kokoro_tts.synthesize_waveform",
            return_value=(np.array([0.1, -0.2], dtype=np.float32), 24000),
        ), patch(
            "rvc_py.rvc_infer.rvc_infer",
            return_value=(np.array([0.1, -0.2], dtype=np.float32), 48000),
        ) as rvc_mock, patch("ui.voice.optimus_tts.resolve_model_path", return_value=Path("model.pth")), patch(
            "ui.voice.optimus_tts.resolve_index_path", return_value=Path("model.index")
        ):
            result = synthesize("Roll out.", settings=settings)
        self.assertIsInstance(result, OptimusTtsResult)
        self.assertEqual(48000, result.sample_rate)
        self.assertGreater(len(result.pcm), 0)
        rvc_mock.assert_called_once()
        kwargs = rvc_mock.call_args.kwargs
        self.assertLess(kwargs["rms_mix_rate"], 1.0)
        self.assertLess(kwargs["protect"], 0.5)

    def test_prosody_keeps_punctuation(self) -> None:
        from ui.voice.optimus_tts import _prosody_text, _split_sentences

        self.assertEqual(["Hello.", "How are you?"], _split_sentences("Hello. How are you?"))
        self.assertEqual("Wait, stop.", _prosody_text("Wait,   stop."))


__all__ = []
