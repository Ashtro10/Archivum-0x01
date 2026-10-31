"""Tests for offline Whisper STT."""

from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch

from astra.configuration.settings import VoiceSettings
from ui.voice.whisper_stt import WhisperSttError, transcribe_pcm_bytes


class TranscribePcmTest(unittest.TestCase):
    def test_empty_audio_rejected(self) -> None:
        settings = VoiceSettings()
        with self.assertRaises(WhisperSttError):
            transcribe_pcm_bytes(b"", 16000, settings)

    def test_transcribes_with_mock_model(self) -> None:
        settings = VoiceSettings()
        segment = MagicMock()
        segment.text = " hello primus "
        model = MagicMock()
        model.transcribe.return_value = ([segment], None)

        with patch("ui.voice.whisper_stt._import_whisper"), patch(
            "ui.voice.whisper_stt._get_model", return_value=model
        ):
            text = transcribe_pcm_bytes(b"\x00\x01" * 8000, 16000, settings)
        self.assertEqual("hello primus", text)


__all__ = []
