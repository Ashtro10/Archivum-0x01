"""Memory integrity: user/probe isolation, field retrieval, negative extraction."""

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
from astra.memory.user_facts import (
    USER_FIRST_CRUSH,
    USER_NAME,
    is_user_durable,
    record_field_key,
    record_value,
)
from astra.presentation.orchestration import ConversationOrchestrator, _durable_facts


ROOT = Path(__file__).resolve().parents[1]
EXE = ROOT / "dist_windows" / "Primus.exe"
CREATE_NO_WINDOW = 0x08000000


class _RecallSpyLLM:
    def __init__(self) -> None:
        self.prompts: list[str] = []

    def is_available(self) -> bool:
        return True

    def generate(self, prompt: str, options: object = None) -> str:
        self.prompts.append(prompt)
        for token in getattr(self, "tokens", ()):
            if token.lower() in prompt.lower():
                return token
        if "no matching durable memory" in prompt.lower():
            return "I don't remember you telling me that."
        return "Understood."


def _settings(root: Path | None = None) -> AstraSettings:
    root = root or Path(tempfile.mkdtemp())
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


class FreshDatabaseTest(unittest.TestCase):
    def test_empty_store_has_no_user_facts(self) -> None:
        settings = _settings()
        ctrl = AstraController(settings, llm_factory=lambda _s: _RecallSpyLLM())
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        orch = ConversationOrchestrator.from_controller(ctrl)
        result = orch.process("What is my name?")
        self.assertFalse(result.memory_retrieved)
        prompt = orch.process("What do you remember about me?")
        self.assertFalse(any("MemProbe" in item for item in prompt.memory_retrieved))


class FieldRetrievalTest(unittest.TestCase):
    def test_name_and_crush_do_not_cross(self) -> None:
        name = f"Arven-{secrets.token_hex(3)}"
        crush = f"Mira-{secrets.token_hex(3)}"
        settings = _settings()
        spy = _RecallSpyLLM()
        spy.tokens = (name, crush)
        ctrl = AstraController(settings, llm_factory=lambda _s: spy)
        ctrl.start()
        orch = ConversationOrchestrator.from_controller(ctrl)
        orch.process(f"My name is {name}.")
        orch.process(f"My first crush's name was {crush}.")
        ctrl.shutdown()
        ctrl2 = AstraController(settings, llm_factory=lambda _s: _RecallSpyLLM())
        ctrl2.start()
        self.addCleanup(ctrl2.shutdown)
        orch2 = ConversationOrchestrator.from_controller(ctrl2)
        crush_result = orch2.process("What's my first crush's name?")
        joined = "\n".join(crush_result.memory_retrieved)
        self.assertIn(crush, joined)
        self.assertNotIn(name, joined)


class NegativeExtractionTest(unittest.TestCase):
    def test_questions_and_third_party_do_not_persist(self) -> None:
        settings = _settings()
        ctrl = AstraController(settings, llm_factory=lambda _s: _RecallSpyLLM())
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        orch = ConversationOrchestrator.from_controller(ctrl)
        for message in (
            "What is C programming?",
            "Make a plan to learn C.",
            "My friend likes C.",
            "What is Kishore?",
        ):
            orch.process(message)
        durable = [
            record
            for record in ctrl.memory.iter_records()
            if is_user_durable(record)
        ]
        self.assertFalse(durable, durable)

    def test_durable_facts_skips_questions(self) -> None:
        self.assertEqual([], _durable_facts("What is my name?"))


class ContaminationIsolationTest(unittest.TestCase):
    def test_probe_does_not_touch_production_db(self) -> None:
        if not EXE.is_file():
            self.skipTest("rebuild Primus.exe")
        data = EXE.parent / "data"
        parked = EXE.parent / "data.parked-integrity"
        if parked.exists():
            self.skipTest("parked data dir exists")
        if data.exists():
            data.rename(parked)
        try:
            data.mkdir(parents=True, exist_ok=True)
            prod_db = data / "astra.db"
            env = os.environ.copy()
            env.pop("ASTRA_HOME", None)
            env.pop("PRIMUS_HOME", None)
            env.pop("ASTRA_CONFIG", None)
            probe = subprocess.run(
                [str(EXE), "--probe-runtime-write"],
                cwd=str(EXE.parent),
                env=env,
                timeout=180,
                creationflags=CREATE_NO_WINDOW if sys.platform == "win32" else 0,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            self.assertEqual(0, probe.returncode, probe)
            payload = json.loads(
                (EXE.parent / "logs" / "probe-runtime-write.json").read_text(encoding="utf-8")
            )
            probe_home = Path(str(payload.get("probe_home", "") or ""))
            self.assertTrue(probe_home.is_dir(), payload)
            self.assertNotEqual(probe_home.resolve(), data.resolve())
            rows = _rows(prod_db)
            self.assertFalse(rows, rows)
            self.assertTrue(_rows(probe_home / "astra.db"), payload)
        finally:
            if data.exists() and parked.exists():
                import shutil

                shutil.rmtree(data)
            if parked.exists():
                parked.rename(data)


class FrozenArbitraryValueTest(unittest.TestCase):
    def test_random_values_via_real_exe_path(self) -> None:
        if not EXE.is_file():
            self.skipTest("rebuild Primus.exe")
        home = Path(tempfile.mkdtemp())
        name = f"Arven-{secrets.token_hex(4)}"
        crush = f"Mira-{secrets.token_hex(4)}"
        pref = f"dark-ui-{secrets.token_hex(3)}"
        env = os.environ.copy()
        env["ASTRA_HOME"] = str(home)
        env.pop("ASTRA_CONFIG", None)
        for message in (
            f"My name is {name}.",
            f"My first crush was {crush}.",
            f"I prefer {pref}.",
        ):
            store = subprocess.run(
                [str(EXE), "--probe-memory-store", message],
                cwd=str(EXE.parent),
                env=env,
                timeout=180,
                creationflags=CREATE_NO_WINDOW if sys.platform == "win32" else 0,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            self.assertEqual(0, store.returncode, message)
        for question, expected in (
            ("What is my name?", name),
            ("What's my first crush's name?", crush),
            ("What do I prefer?", pref),
        ):
            ask = subprocess.run(
                [str(EXE), "--probe-memory-ask", question],
                cwd=str(EXE.parent),
                env=env,
                timeout=180,
                creationflags=CREATE_NO_WINDOW if sys.platform == "win32" else 0,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            self.assertEqual(0, ask.returncode, question)
            payload = json.loads(
                (EXE.parent / "logs" / "probe-memory-ask.json").read_text(encoding="utf-8")
            )
            hits = payload.get("memory_retrieved") or []
            self.assertTrue(any(expected in str(item) for item in hits), payload)


class SourceTaggingTest(unittest.TestCase):
    def test_explicit_user_statement_source_on_store(self) -> None:
        token = secrets.token_hex(3)
        settings = _settings()
        ctrl = AstraController(settings, llm_factory=lambda _s: _RecallSpyLLM())
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        orch = ConversationOrchestrator.from_controller(ctrl)
        orch.process(f"My name is Person-{token}.")
        rows = _rows(settings.memory.database_path)
        self.assertTrue(any(source == "explicit_user_statement" for _c, source in rows), rows)


if __name__ == "__main__":
    unittest.main()
