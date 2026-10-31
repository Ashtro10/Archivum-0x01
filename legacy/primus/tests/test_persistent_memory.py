"""Persistent memory on the real talk path. No hardcoded Kishore replies."""

from __future__ import annotations

import json
import os
import secrets
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from astra.configuration.settings import (
    AstraSettings,
    KnowledgeSettings,
    LoggingSettings,
    MemorySettings,
    PlanningSettings,
)
from astra.core.astra_controller import AstraController
from astra.presentation.orchestration import ConversationOrchestrator, _durable_facts


ROOT = Path(__file__).resolve().parents[1]
EXE = ROOT / "dist_windows" / "Primus.exe"
CREATE_NO_WINDOW = 0x08000000


class _SpyLLM:
    def __init__(self) -> None:
        self.prompts: list[str] = []

    def is_available(self) -> bool:
        return True

    def generate(self, prompt: str, options: object = None) -> str:
        self.prompts.append(prompt)
        if "Kishore" in prompt and "name" in prompt.lower():
            return "Your name is Kishore."
        if "Ash" in prompt and "name" in prompt.lower():
            return "Your name is Ash."
        return "Understood."


def _settings() -> AstraSettings:
    root = Path(tempfile.mkdtemp())
    return AstraSettings(
        memory=MemorySettings(
            database_path=root / "mem.db",
            vector_index_path=root / "vectors.faiss",
        ),
        knowledge=KnowledgeSettings(database_path=root / "knowledge.db"),
        planning=PlanningSettings(database_path=root / "planning.db"),
        logging=LoggingSettings(directory=root / "logs"),
    )


def _rows(path: Path) -> list[tuple[str, str]]:
    if not path.is_file():
        return []
    con = sqlite3.connect(path)
    try:
        return list(con.execute("SELECT content, source FROM memory_records"))
    finally:
        con.close()


class DurablePhraseTest(unittest.TestCase):
    def test_natural_name_and_compound_sentence(self) -> None:
        names = [content for _c, content, kind, _k in _durable_facts("I'm Kishore.") if kind == "name"]
        self.assertEqual(["The user's name is Kishore."], names)
        called = [content for _c, content, kind, _k in _durable_facts("You can call me Kishore.") if kind == "preferred"]
        self.assertEqual(["You can call me Kishore."], called)
        compound = _durable_facts("I prefer C programming and I'm working on Primus.")
        kinds = {kind: content for _c, content, kind, _k in compound}
        self.assertIn("prefer", kinds)
        self.assertIn("C programming", kinds["prefer"])
        self.assertNotIn("working on", kinds["prefer"])
        self.assertIn("project", kinds)
        self.assertIn("Primus", kinds["project"])


class SourceRestartTest(unittest.TestCase):
    def test_a_name_survives_reinit(self) -> None:
        settings = _settings()
        spy = _SpyLLM()
        ctrl = AstraController(settings, llm_factory=lambda _s: spy)
        ctrl.start()
        ConversationOrchestrator.from_controller(ctrl).process("My name is Kishore.")
        ctrl.shutdown()
        spy2 = _SpyLLM()
        ctrl2 = AstraController(settings, llm_factory=lambda _s: spy2)
        ctrl2.start()
        self.addCleanup(ctrl2.shutdown)
        result = ConversationOrchestrator.from_controller(ctrl2).process("What is my name?")
        self.assertTrue(any("Kishore" in item for item in result.memory_retrieved))
        self.assertIn("Kishore", spy2.prompts[-1])
        self.assertIn("Kishore", result.text)

    def test_b_preference_and_project(self) -> None:
        settings = _settings()
        spy = _SpyLLM()
        ctrl = AstraController(settings, llm_factory=lambda _s: spy)
        ctrl.start()
        orch = ConversationOrchestrator.from_controller(ctrl)
        orch.process("I prefer C programming and I'm working on Primus.")
        ctrl.shutdown()
        spy2 = _SpyLLM()
        ctrl2 = AstraController(settings, llm_factory=lambda _s: spy2)
        ctrl2.start()
        self.addCleanup(ctrl2.shutdown)
        asked = ConversationOrchestrator.from_controller(ctrl2).process(
            "What do you remember about my programming preference and current project?"
        )
        joined = "\n".join(asked.memory_retrieved) + spy2.prompts[-1]
        self.assertIn("C programming", joined)
        self.assertIn("Primus", joined)

    def test_same_name_revives_refuted_row(self) -> None:
        settings = _settings()
        spy = _SpyLLM()
        ctrl = AstraController(settings, llm_factory=lambda _s: spy)
        ctrl.start()
        orch = ConversationOrchestrator.from_controller(ctrl)
        orch.process("My name is Kishore.")
        orch.process("Actually, call me Ash.")
        orch.process("My name is Kishore.")
        ctrl.shutdown()
        spy2 = _SpyLLM()
        ctrl2 = AstraController(settings, llm_factory=lambda _s: spy2)
        ctrl2.start()
        self.addCleanup(ctrl2.shutdown)
        result = ConversationOrchestrator.from_controller(ctrl2).process("What is my name?")
        self.assertTrue(any("Kishore" in item for item in result.memory_retrieved))
        self.assertFalse(
            any("Ash" in item and "Kishore" not in item for item in result.memory_retrieved)
        )

    def test_d_correction_keeps_newest_preferred_name(self) -> None:
        settings = _settings()
        spy = _SpyLLM()
        ctrl = AstraController(settings, llm_factory=lambda _s: spy)
        ctrl.start()
        orch = ConversationOrchestrator.from_controller(ctrl)
        orch.process("My name is Kishore.")
        orch.process("Actually, call me Ash.")
        ctrl.shutdown()
        spy2 = _SpyLLM()
        ctrl2 = AstraController(settings, llm_factory=lambda _s: spy2)
        ctrl2.start()
        self.addCleanup(ctrl2.shutdown)
        result = ConversationOrchestrator.from_controller(ctrl2).process("What should you call me?")
        self.assertTrue(any("Ash" in item for item in result.memory_retrieved))
        self.assertIn("Ash", spy2.prompts[-1])

    def test_question_does_not_overwrite_preference(self) -> None:
        settings = _settings()
        spy = _SpyLLM()
        ctrl = AstraController(settings, llm_factory=lambda _s: spy)
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        orch = ConversationOrchestrator.from_controller(ctrl)
        orch.process("I prefer working on AI projects.")
        orch.process("What do I prefer working on?")
        prefs = [
            record.content
            for record in ctrl.memory.iter_records()
            if "prefers" in record.content
            and "refuted" not in str(getattr(record.verification, "value", record.verification))
        ]
        self.assertTrue(any("AI projects" in item for item in prefs), prefs)
        self.assertFalse(any(item.endswith("working on.") for item in prefs), prefs)

    def test_e_unrelated_question_does_not_dump_prefs(self) -> None:
        settings = _settings()
        spy = _SpyLLM()
        ctrl = AstraController(settings, llm_factory=lambda _s: spy)
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        orch = ConversationOrchestrator.from_controller(ctrl)
        orch.process("I prefer practical programming examples.")
        orch.process("What is a pointer?")
        system = spy.prompts[-1].split("<|im_start|>user")[0].lower()
        self.assertNotIn("the user prefers", system)
        self.assertNotIn("preferences:", system)


class ProductExecutableMemoryTest(unittest.TestCase):
    """Uses Primus.exe default data dir. No ASTRA_HOME. Real install path."""

    def _run_exe(self, *args: str) -> subprocess.CompletedProcess[bytes]:
        env = os.environ.copy()
        env.pop("ASTRA_HOME", None)
        env.pop("PRIMUS_HOME", None)
        env.pop("ASTRA_CONFIG", None)
        return subprocess.run(
            [str(EXE), *args],
            cwd=str(EXE.parent),
            env=env,
            timeout=180,
            creationflags=CREATE_NO_WINDOW if sys.platform == "win32" else 0,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    def test_frozen_default_path_three_restarts(self) -> None:
        if not EXE.is_file():
            self.skipTest("rebuild Primus.exe with scripts/build_windows.py")
        write = self._run_exe("--probe-runtime-write")
        self.assertEqual(0, write.returncode)
        write_path = EXE.parent / "logs" / "probe-runtime-write.json"
        self.assertTrue(write_path.is_file(), write_path)
        write_payload = json.loads(write_path.read_text(encoding="utf-8"))
        self.assertTrue(write_payload.get("ok"), write_payload)
        probe_name = str(write_payload.get("probe_name", "") or "")
        probe_home = Path(str(write_payload.get("probe_home", "") or ""))
        self.assertTrue(probe_name, write_payload)
        self.assertTrue(probe_home.is_dir(), write_payload)
        probe_db = probe_home / "astra.db"
        self.assertTrue(any(probe_name in row[0] for row in _rows(probe_db)), _rows(probe_db))
        prod_db = EXE.parent / "data" / "astra.db"
        if prod_db.is_file():
            self.assertFalse(
                any(probe_name in row[0] for row in _rows(prod_db)),
                _rows(prod_db),
            )
        for cycle in range(3):
            read = self._run_exe("--probe-runtime-read")
            self.assertEqual(0, read.returncode, f"cycle {cycle}")
            payload = json.loads(
                (EXE.parent / "logs" / "probe-runtime-read.json").read_text(encoding="utf-8")
            )
            self.assertTrue(payload.get("name_retrieved"), payload)
            self.assertTrue(payload.get("prefer_in_context"), payload)
            self.assertTrue(payload.get("project_in_context"), payload)

    def test_frozen_crush_survives_exe_restart(self) -> None:
        if not EXE.is_file():
            self.skipTest("rebuild Primus.exe with scripts/build_windows.py")
        home = Path(tempfile.mkdtemp())
        env = os.environ.copy()
        env["ASTRA_HOME"] = str(home)
        env.pop("ASTRA_CONFIG", None)
        token = secrets.token_hex(4)
        crush = f"ExeCrush-{token}"
        store = subprocess.run(
            [str(EXE), "--probe-memory-store", f"My first crush's name was {crush}."],
            cwd=str(EXE.parent),
            env=env,
            timeout=180,
            creationflags=CREATE_NO_WINDOW if sys.platform == "win32" else 0,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self.assertEqual(0, store.returncode, store)
        ask = subprocess.run(
            [str(EXE), "--probe-memory-ask", "What was my first crush's name?"],
            cwd=str(EXE.parent),
            env=env,
            timeout=180,
            creationflags=CREATE_NO_WINDOW if sys.platform == "win32" else 0,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self.assertEqual(0, ask.returncode, ask)
        ask_payload = json.loads(
            (EXE.parent / "logs" / "probe-memory-ask.json").read_text(encoding="utf-8")
        )
        hits = ask_payload.get("memory_retrieved") or []
        self.assertTrue(any(crush in str(item) for item in hits), ask_payload)

    def test_f_missing_data_is_honest_fresh(self) -> None:
        if not EXE.is_file():
            self.skipTest("rebuild Primus.exe with scripts/build_windows.py")
        data = EXE.parent / "data"
        parked = EXE.parent / "data.parked-memory-test"
        if parked.exists():
            self.skipTest("parked data dir already exists")
        if data.exists():
            data.rename(parked)
        try:
            write_log = EXE.parent / "logs" / "probe-runtime-write.json"
            if write_log.is_file():
                write_log.unlink()
            read = self._run_exe("--probe-runtime-read")
            payload_path = EXE.parent / "logs" / "probe-runtime-read.json"
            payload = json.loads(payload_path.read_text(encoding="utf-8")) if payload_path.is_file() else {}
            self.assertFalse(payload.get("name_retrieved", True), payload)
        finally:
            fresh = EXE.parent / "data"
            if fresh.exists() and parked.exists():
                import shutil

                shutil.rmtree(fresh)
            if parked.exists():
                parked.rename(data)

    def test_probe_home_does_not_clobber_install_db(self) -> None:
        if not EXE.is_file():
            self.skipTest("rebuild Primus.exe with scripts/build_windows.py")
        before = _rows(EXE.parent / "data" / "astra.db")
        home = Path(tempfile.mkdtemp())
        env = os.environ.copy()
        env["ASTRA_HOME"] = str(home)
        env.pop("ASTRA_CONFIG", None)
        isolated = subprocess.run(
            [str(EXE), "--probe-runtime-write"],
            cwd=str(EXE.parent),
            env=env,
            timeout=180,
            creationflags=CREATE_NO_WINDOW if sys.platform == "win32" else 0,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self.assertEqual(0, isolated.returncode)
        payload = json.loads(
            (EXE.parent / "logs" / "probe-runtime-write.json").read_text(encoding="utf-8")
        )
        probe_name = str(payload.get("probe_name", "") or "")
        self.assertTrue(probe_name, payload)
        self.assertTrue(_rows(home / "astra.db"))
        after = _rows(EXE.parent / "data" / "astra.db")
        self.assertEqual(before, after)
        if after:
            self.assertFalse(any(probe_name in row[0] for row in after), after)


if __name__ == "__main__":
    unittest.main()
