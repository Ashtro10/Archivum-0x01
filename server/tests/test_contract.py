#!/usr/bin/env python3
"""The behavioural contract shared with the on-device agent (contract/agent_contract.json; core's AgentContractTest runs
the same file): research decisions, claim checking, answer clean-up and whole turns must agree on both engines.

    python -m unittest server/tests/test_contract.py      (standard library only)
"""
from __future__ import annotations

import asyncio
import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import clu_controller as cc  # noqa: E402
import clu_context as ctx  # noqa: E402

CONTRACT = json.loads((HERE.parent.parent / "contract" / "agent_contract.json").read_text())


def hits(results: list[dict]) -> list[cc.Hit]:
    return [cc.Hit(r["title"], r["url"], r["snippet"]) for r in results]


def wiki_json(hits_: list[dict], suggestion: str | None = None) -> str:
    info = {"totalhits": len(hits_)}
    if suggestion:
        info["suggestion"] = suggestion
    return json.dumps({"query": {"searchinfo": info, "search": [{"title": h["title"], "snippet": h["snippet"], "timestamp": "2025-11-02T10:00:00Z"} for h in hits_]}})


class Contract(unittest.TestCase):
    def test_claims(self):
        failures = []
        for c in CONTRACT["claims"]:
            hs = hits(c["results"])
            units = cc.Evidence.units(cc.summary(hs), hs)
            v, why = cc.Claims.check(c["sentence"], units, c["question"], c["subjects"], hs)
            if v != c["verdict"]:
                failures.append(f"{c['why']}: '{c['sentence']}' -> {v} ({why}), expected {c['verdict']}")
        self.assertEqual([], failures)

    def test_research_decisions(self):
        failures = []
        for p in CONTRACT["policy"]:
            d = cc.ResearchPolicy.decide(p["text"], p.get("previous_subject"))
            if d.kind != p["kind"] or ("query" in p and d.query != p["query"]) or ("subject" in p and d.subject != p["subject"]):
                failures.append(f"'{p['text']}' -> {d.kind} query='{d.query}' subject='{d.subject}', expected {p}")
        self.assertEqual([], failures)

    def test_answer_clean_up(self):
        for s in CONTRACT["style"]:
            out = cc.AnswerStyle.without_internal_markup(s["in"]) if s.get("internal") else cc.AnswerStyle.without_source_talk(s["in"])
            self.assertEqual(s["out"], out, s["in"])

    def test_turns(self):
        """Whole turns with a scripted model and Wikipedia fixtures: the same final answer as the on-device agent."""
        for scenario in CONTRACT["turns"]:
            with self.subTest(scenario["name"]):
                run_scenario(self, scenario)


class Cancellation(unittest.TestCase):
    """Barge-in or Stop cancels the reply task: the search and the generation stop; nothing more is asked of the model."""

    def test_cancel_during_search_stops_before_the_model(self):
        calls = {"model": 0, "search_cancelled": False}

        async def search(q):
            try:
                await asyncio.sleep(10)
            except asyncio.CancelledError:
                calls["search_cancelled"] = True
                raise

        async def generate(extra):
            calls["model"] += 1
            yield "x"

        async def run():
            ctl = cc.Controller(search)
            task = asyncio.ensure_future(_drain(ctl.respond("Who is Leman Russ?", [], generate)))
            await asyncio.sleep(0.05)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task

        asyncio.run(run())
        self.assertTrue(calls["search_cancelled"])
        self.assertEqual(0, calls["model"])

    def test_cancel_during_the_answer_closes_the_generation(self):
        state = {"closed": False, "words": 0}
        r = CONTRACT["turns"][0]["search"]["Leman Russ"]

        async def search(q):
            return cc.parse_wikipedia(wiki_json(r["results"]), q)

        async def generate(extra):
            try:
                yield "He is the Primarch of the Space Wolves in Warhammer 40,000. "
                while True:
                    state["words"] += 1
                    yield "He leads the VI Legion from Fenris. "
                    await asyncio.sleep(0.01)
            finally:
                state["closed"] = True

        async def run():
            ctl = cc.Controller(search)
            got = []

            async def consume():
                async for p in ctl.respond("Who is Leman Russ?", [], generate):
                    got.append(p)
            task = asyncio.ensure_future(consume())
            await asyncio.sleep(0.2)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
            return got

        got = asyncio.run(run())
        self.assertTrue(got and got[0].startswith("He is the Primarch"), "checked sentences are spoken while generating")
        self.assertTrue(state["closed"], "the model's generation was closed")
        self.assertLess(state["words"], 100)


class Context(unittest.TestCase):
    """Server-only details of the conversation context (the contract's turns cover the behaviour)."""

    def test_nothing_personal_reaches_the_alert_or_forecast_services(self):
        url = ctx.Gdacs.url("TC", 1_760_000_000_000)
        self.assertNotIn("Chennai", url)
        self.assertNotIn("lat", url.lower())
        pune = ctx.Places.resolve("Pune")
        self.assertIn("latitude=18.52&longitude=73.86", ctx.OpenMeteo.url(pune))

    def test_state_survives_a_restart(self):
        async def hz(t):
            return ctx.Gdacs.check(t, ctx.now_ms(), 200, json.dumps(next(s for s in CONTRACT["turns"] if "gdacs" in s)["gdacs"]))

        async def gen(extra):
            yield "OK."
        async def search(q):
            return cc.SearchResult(False, empty=True, query=q)
        a = cc.Controller(search, hazards=hz, home="Chennai")
        asyncio.run(_drain(a.respond("Which cyclone is heading towards us?", [], gen)))
        b = cc.Controller(search, hazards=hz, home="Chennai")  # a new process: only the saved state carries over
        b.state = ctx.ConversationState.from_json(a.state.to_json())
        said = "".join(asyncio.run(_drain(b.respond("Is it dangerous?", [], gen))))
        self.assertTrue(said.startswith("GDACS rates Tropical Cyclone DANA-24 Orange"), said)

    def test_unknown_place_is_never_guessed(self):
        self.assertIsNone(ctx.Places.resolve("Atlantis"))
        self.assertEqual("Chennai", ctx.Places.resolve("we are in Chennai now").name)


async def _drain(agen):
    return [p async for p in agen]


def run_scenario(test: unittest.TestCase, scenario: dict) -> None:
    search_routes = scenario.get("search", {})
    state = {"online": True, "fail": False}

    async def search(q: str) -> cc.SearchResult:
        if state["fail"]:
            return cc.SearchResult(False, error="Wikipedia could not be reached (connection refused).", query=q)
        r = search_routes.get(q)
        if r is None:  # as the on-device fixture: an unknown query gets HTTP 404, a failure (not "nothing found")
            return cc.SearchResult(False, error="Wikipedia returned HTTP 404.", query=q)
        return cc.parse_wikipedia(wiki_json(r.get("results", []), r.get("suggestion")), q)

    gdacs = json.dumps(scenario["gdacs"]) if "gdacs" in scenario else None

    async def hazards(type_: str) -> ctx.HazardCheck:  # the official alert service's fixture (no network in tests)
        return ctx.Gdacs.check(type_, ctx.now_ms(), 200 if gdacs is not None else 404, gdacs or "")

    async def weather(place) -> ctx.WeatherNow:
        return ctx.OpenMeteo.parse(place, ctx.now_ms(), 404, "")

    ctl = cc.Controller(search, instructions="You are Clu.", online=lambda: state["online"], hazards=hazards, weather=weather, home=scenario.get("home"))
    history: list[str] = []
    for step in scenario["steps"]:
        replies = list(step.get("model", []))
        prompts: list[list[dict]] = []

        async def generate(extra: list[dict]):
            prompts.append(extra)
            text = replies.pop(0) if replies else "OK."
            for i in range(0, len(text), 5):
                yield text[i:i + 5]
                await asyncio.sleep(0)

        state["online"] = not step.get("offline", False)
        state["fail"] = step.get("search_fails", False)

        async def run():
            return "".join([p async for p in ctl.respond(step["say"], history, generate, via_voice=step.get("voice", False))])

        said = asyncio.run(run()).strip()
        turn = ctl.last
        exp = step["expect"]
        if "decision" in exp:
            test.assertEqual(exp["decision"], turn.decision.kind, f"{step['say']}: research decision")
        test.assertEqual(exp["queries"], turn.queries, f"{step['say']}: queries sent")
        if "final" in exp:
            test.assertEqual(exp["final"], said, f"{step['say']}: final answer")
        if "final_prefix" in exp:
            test.assertTrue(said.startswith(exp["final_prefix"]), f"{step['say']}: final answer '{said}'")
        if "model_calls" in exp:
            test.assertEqual(exp["model_calls"], len(prompts), f"{step['say']}: model calls")
        if "interpreted" in exp:
            test.assertEqual(exp["interpreted"], turn.interpreted, f"{step['say']}: re-heard as")
        test.assertEqual(said, turn.text)
        if "kind" in exp:
            test.assertEqual(exp["kind"], turn.kind, f"{step['say']}: kind")
        if exp.get("evidence_reaches_model"):
            test.assertTrue(any(m["content"].startswith("[Information found]") and exp["evidence_reaches_model"] in m["content"] for p in prompts for m in p),
                            "the evidence reaches the model's answer step")
        if exp.get("prompt_excludes"):  # earlier replies as clu_agent.py hands them back to the model (cc.for_prompt)
            test.assertFalse(any(exp["prompt_excludes"] in cc.for_prompt(h) for h in history[1::2]), "an earlier quote's framing reached the prompt")
        history += [step["say"], said]


if __name__ == "__main__":
    unittest.main()
