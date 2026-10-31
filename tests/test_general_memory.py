"""General-purpose persistent memory. Dynamic values only in assertions."""

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
from astra.memory.user_facts import USER_FIRST_CRUSH


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
        lowered = prompt.lower()
        for token in getattr(self, "tokens", ()):
            if token.lower() in lowered:
                return token
        if "no matching durable memory" in lowered:
            return "I don't remember you telling me that."
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


def _fresh_orchestrator(
    settings: AstraSettings | None = None,
    *,
    tokens: tuple[str, ...] = (),
) -> tuple[AstraController, ConversationOrchestrator, _RecallSpyLLM]:
    settings = settings or _settings()
    spy = _RecallSpyLLM()
    spy.tokens = tokens
    ctrl = AstraController(settings, llm_factory=lambda _s: spy)
    ctrl.start()
    return ctrl, ConversationOrchestrator.from_controller(ctrl), spy


class ExtractionTest(unittest.TestCase):
    def test_relationship_and_enjoy_patterns(self) -> None:
        token = secrets.token_hex(4)
        crush = f"Isha-{token}"
        facts = _durable_facts(f"My first crush's name was {crush}.")
        self.assertTrue(
            any(crush in content for _c, content, kind, key in facts if key == USER_FIRST_CRUSH)
        )
        enjoy = _durable_facts(f"I really enjoy programming in Lang-{token}.")
        self.assertTrue(any(f"Lang-{token}" in content for _c, content, _k, _f in enjoy))


class GeneralRecallTest(unittest.TestCase):
    def test_crush_beats_identity_after_restart(self) -> None:
        name = f"Name-{secrets.token_hex(3)}"
        crush = f"Crush-{secrets.token_hex(3)}"
        settings = _settings()
        ctrl, orch, _spy = _fresh_orchestrator(settings, tokens=(crush, name))
        orch.process(f"My name is {name}.")
        orch.process(f"My first crush's name was {crush}.")
        ctrl.shutdown()
        ctrl2, orch2, spy2 = _fresh_orchestrator(settings, tokens=(crush, name))
        self.addCleanup(ctrl2.shutdown)
        result = orch2.process("Who was my first crush?")
        joined = "\n".join(result.memory_retrieved)
        self.assertIn(crush, joined)
        self.assertNotIn(name, joined)

    def test_semantic_wording_mismatch(self) -> None:
        cases = (
            (f"My first crush's name was Rel-{secrets.token_hex(3)}.", "Who was my first crush?"),
            (f"I really enjoy programming in Lang-{secrets.token_hex(3)}.", "Which programming language do I like?"),
            (f"I have a dog called Dog-{secrets.token_hex(3)}.", "What is my dog's name?"),
            (f"My current project is Proj-{secrets.token_hex(3)}.", "What am I currently building?"),
        )
        for stored, asked in cases:
            with self.subTest(stored=stored, asked=asked):
                token = stored.rsplit("-", 1)[-1].rstrip(".")
                settings = _settings()
                ctrl, orch, _spy = _fresh_orchestrator(settings, tokens=(token,))
                orch.process(stored)
                ctrl.shutdown()
                ctrl2, orch2, _spy2 = _fresh_orchestrator(settings, tokens=(token,))
                self.addCleanup(ctrl2.shutdown)
                result = orch2.process(asked)
                self.assertTrue(any(token in item for item in result.memory_retrieved), result.memory_retrieved)

    def test_multiple_memories_in_any_order(self) -> None:
        name = f"N-{secrets.token_hex(3)}"
        crush = f"C-{secrets.token_hex(3)}"
        pref = f"P-{secrets.token_hex(3)}"
        project = f"J-{secrets.token_hex(3)}"
        settings = _settings()
        ctrl, orch, _spy = _fresh_orchestrator(settings, tokens=(name, crush, pref, project))
        orch.process(f"My name is {name}.")
        orch.process(f"My first crush's name was {crush}.")
        orch.process(f"I prefer {pref}.")
        orch.process(f"My current project is {project}.")
        ctrl.shutdown()
        ctrl2, orch2, _spy2 = _fresh_orchestrator(settings, tokens=(name, crush, pref, project))
        self.addCleanup(ctrl2.shutdown)
        questions = (
            ("What is my name?", name),
            ("Who was my first crush?", crush),
            ("What do I prefer?", pref),
            ("What am I currently working on?", project),
        )
        for question, expected in questions:
            with self.subTest(question=question):
                result = orch2.process(question)
                self.assertTrue(any(expected in item for item in result.memory_retrieved), result.memory_retrieved)

    def test_relationship_facts_do_not_refute_each_other(self) -> None:
        crush = f"C-{secrets.token_hex(3)}"
        friend = f"F-{secrets.token_hex(3)}"
        settings = _settings()
        ctrl, orch, _spy = _fresh_orchestrator(settings, tokens=(crush, friend))
        orch.process(f"My first crush's name was {crush}.")
        orch.process(f"My childhood friend's name was {friend}.")
        crush_result = orch.process("What was my first crush's name?")
        friend_result = orch.process("What was my childhood friend's name?")
        self.assertTrue(any(crush in item for item in crush_result.memory_retrieved), crush_result.memory_retrieved)
        self.assertTrue(any(friend in item for item in friend_result.memory_retrieved), friend_result.memory_retrieved)
        ctrl.shutdown()

    def test_unknown_does_not_invent(self) -> None:
        settings = _settings()
        ctrl, orch, spy = _fresh_orchestrator(settings)
        self.addCleanup(ctrl.shutdown)
        result = orch.process("What was my childhood friend's name?")
        prompt = spy.prompts[-1].lower()
        self.assertIn("no matching durable memory", prompt)
        self.assertIn("do not invent", prompt)
        self.assertNotIn("kishore", prompt)

    def test_correction_after_restart(self) -> None:
        old = f"Old-{secrets.token_hex(3)}"
        new = f"New-{secrets.token_hex(3)}"
        settings = _settings()
        ctrl, orch, _spy = _fresh_orchestrator(settings, tokens=(old, new))
        orch.process(f"My first crush's name was {old}.")
        orch.process(f"Actually, my first crush's name was {new}.")
        ctrl.shutdown()
        ctrl2, orch2, _spy2 = _fresh_orchestrator(settings, tokens=(new,))
        self.addCleanup(ctrl2.shutdown)
        result = orch2.process("Who was my first crush?")
        joined = "\n".join(result.memory_retrieved)
        self.assertIn(new, joined)
        self.assertNotIn(old, joined)

    def test_forget_after_restart(self) -> None:
        crush = f"Forgot-{secrets.token_hex(3)}"
        settings = _settings()
        ctrl, orch, _spy = _fresh_orchestrator(settings, tokens=(crush,))
        orch.process(f"My first crush's name was {crush}.")
        orch.process("Forget the name of my first crush.")
        ctrl.shutdown()
        ctrl2, orch2, _spy2 = _fresh_orchestrator(settings, tokens=(crush,))
        self.addCleanup(ctrl2.shutdown)
        result = orch2.process("What was my first crush's name?")
        joined = "\n".join(result.memory_retrieved).lower()
        self.assertNotIn(crush.lower(), joined)


class FrozenGeneralMemoryTest(unittest.TestCase):
    def _run(self, *args: str, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[bytes]:
        base = os.environ.copy()
        base.pop("ASTRA_HOME", None)
        base.pop("PRIMUS_HOME", None)
        base.pop("ASTRA_CONFIG", None)
        if env:
            base.update(env)
        return subprocess.run(
            [str(EXE), *args],
            cwd=str(EXE.parent),
            env=base,
            timeout=180,
            creationflags=CREATE_NO_WINDOW if sys.platform == "win32" else 0,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    def test_dynamic_crush_survives_exe_restart(self) -> None:
        if not EXE.is_file():
            self.skipTest("rebuild Primus.exe with scripts/build_windows.py")
        home = Path(tempfile.mkdtemp())
        env = {"ASTRA_HOME": str(home)}
        token = secrets.token_hex(4)
        crush = f"ExeCrush-{token}"
        store = self._run("--probe-memory-store", f"My first crush's name was {crush}.", env=env)
        self.assertEqual(0, store.returncode, store)
        store_payload = json.loads(
            (EXE.parent / "logs" / "probe-memory-store.json").read_text(encoding="utf-8")
        )
        self.assertTrue(store_payload.get("memory_persisted"), store_payload)
        friend = f"Friend-{secrets.token_hex(4)}"
        store2 = self._run(
            "--probe-memory-store",
            f"My childhood friend's name was {friend}.",
            env=env,
        )
        self.assertEqual(0, store2.returncode, store2)
        ask = self._run("--probe-memory-ask", "What was my childhood friend's name?", env=env)
        self.assertEqual(0, ask.returncode, ask)
        ask_payload = json.loads(
            (EXE.parent / "logs" / "probe-memory-ask.json").read_text(encoding="utf-8")
        )
        hits = ask_payload.get("memory_retrieved") or []
        self.assertTrue(any(friend in str(item) for item in hits), ask_payload)
        ask_crush = self._run("--probe-memory-ask", "What was my first crush's name?", env=env)
        self.assertEqual(0, ask_crush.returncode, ask_crush)
        crush_payload = json.loads(
            (EXE.parent / "logs" / "probe-memory-ask.json").read_text(encoding="utf-8")
        )
        crush_hits = crush_payload.get("memory_retrieved") or []
        self.assertTrue(any(crush in str(item) for item in crush_hits), crush_payload)


if __name__ == "__main__":
    unittest.main()
