"""Voice bus tests."""

from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path

from ui.voice.bus import VoiceBus, read_voice_state, write_voice_state


class VoiceBusTest(unittest.TestCase):
    def test_state_file_roundtrip(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            env = os.environ.copy()
            env["ASTRA_HOME"] = tmp
            old = os.environ.get("ASTRA_HOME")
            try:
                os.environ["ASTRA_HOME"] = tmp
                write_voice_state("listening")
                self.assertEqual("listening", read_voice_state())
                bus = VoiceBus()
                bus.set_state("speaking")
                features = bus.ingest_pcm([0.0, 0.5, -0.4, 0.2])
                self.assertFalse(features.silent)
                snap = bus.snapshot()
                self.assertEqual("speaking", snap["state"])
                self.assertTrue((Path(tmp) / "signals" / ".voice_state").is_file())
            finally:
                if old is None:
                    os.environ.pop("ASTRA_HOME", None)
                else:
                    os.environ["ASTRA_HOME"] = old
