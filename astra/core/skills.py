"""Reusable strategies derived from repeated experience. Not model weights."""

from __future__ import annotations

import re
from dataclasses import dataclass, replace
from typing import Any, Sequence

from astra.core.experience import EXPERIENCE_SOURCE, utc_now, _clip, _join, _search_queries, _split
from astra.memory.contracts import MemoryCategory

SKILL_MARK = "SKILL v1"
SKILL_SOURCE = EXPERIENCE_SOURCE
_VERIFY_SUCCESSES = 3
_CREATE_SUCCESSES = 2
_DEGRADE_STREAK = 2
_FAIL_STREAK = 3
_TOKEN = re.compile(r"[A-Za-z0-9]{4,}")
_CORE = frozenset(
    {
        "fail",
        "test",
        "inspect",
        "project",
        "python",
        "config",
        "report",
        "write",
        "read",
        "search",
    }
)


@dataclass(frozen=True, slots=True)
class SkillRecord:
    """Durable strategy. Not code. Not a new model."""

    name: str
    purpose: str
    trigger: str
    strategy: str
    tools: tuple[str, ...] = ()
    attempts: int = 0
    successes: int = 0
    failures: int = 0
    consecutive_failures: int = 0
    status: str = "candidate"
    fingerprint: str = ""
    created_at: str = ""
    updated_at: str = ""
    record_id: str = ""

    @property
    def success_rate(self) -> float:
        if self.attempts <= 0:
            return 0.0
        return self.successes / self.attempts

    @property
    def confidence(self) -> float:
        return self.success_rate


def format_skill(record: SkillRecord) -> str:
    rate = int(round(record.success_rate * 100))
    return "\n".join(
        (
            SKILL_MARK,
            f"name: {record.name}",
            f"purpose: {record.purpose}",
            f"trigger: {record.trigger}",
            f"strategy: {record.strategy}",
            f"tools: {_join(record.tools)}",
            f"attempts: {record.attempts}",
            f"successes: {record.successes}",
            f"failures: {record.failures}",
            f"success_rate: {rate}",
            f"confidence: {record.confidence:.2f}",
            f"consecutive_failures: {record.consecutive_failures}",
            f"status: {record.status}",
            f"fingerprint: {record.fingerprint}",
            f"created_at: {record.created_at}",
            f"updated_at: {record.updated_at}",
        )
    )


def parse_skill(content: str) -> SkillRecord | None:
    text = str(content or "").strip()
    if not text.startswith(SKILL_MARK):
        return None
    fields: dict[str, str] = {}
    for line in text.splitlines()[1:]:
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        fields[key.strip()] = value.strip()
    attempts = _int(fields.get("attempts", "0"))
    successes = _int(fields.get("successes", "0"))
    failures = _int(fields.get("failures", "0"))
    return SkillRecord(
        name=fields.get("name", "") or "unnamed_skill",
        purpose=fields.get("purpose", ""),
        trigger=fields.get("trigger", ""),
        strategy=fields.get("strategy", ""),
        tools=_split(fields.get("tools", "")),
        attempts=attempts,
        successes=successes,
        failures=failures,
        consecutive_failures=_int(fields.get("consecutive_failures", "0")),
        status=fields.get("status", "candidate") or "candidate",
        fingerprint=fields.get("fingerprint", ""),
        created_at=fields.get("created_at", ""),
        updated_at=fields.get("updated_at", ""),
    )


def skill_fingerprint(goal: str, tools: Sequence[str] = ()) -> str:
    tokens = _norm_tokens(goal, tools)
    core = sorted(token for token in tokens if token in _CORE)
    if not core:
        core = sorted(tokens)[:4]
    return "|".join(core[:6])


def skill_name_for(fingerprint: str, goal: str) -> str:
    parts = [part for part in fingerprint.split("|") if part]
    if "test" in parts and "fail" in parts:
        return "python_test_failure_diagnosis"
    if "report" in parts and "write" in parts:
        return "inspection_report_write"
    if "inspect" in parts:
        return "project_inspect"
    if parts:
        return "_".join(parts[:4])
    tokens = [tok.lower() for tok in _TOKEN.findall(goal)[:4]]
    return "_".join(tokens) if tokens else "generic_strategy"


def retrieve_skills(
    memory: Any, goal: str, *, limit: int = 3, include_failed: bool = False
) -> list[tuple[Any, SkillRecord]]:
    """Return matching skills. Failed skills omitted unless asked."""
    found: list[tuple[Any, SkillRecord]] = []
    seen: set[str] = set()
    needle = skill_fingerprint(goal)
    for item in _iter_skill_memories(memory, goal):
        parsed = parse_skill(str(getattr(item, "content", "") or ""))
        if parsed is None:
            continue
        ident = str(getattr(item, "record_id", "") or "")
        if ident:
            parsed = replace(parsed, record_id=ident)
        if parsed.name in seen:
            continue
        if not include_failed and parsed.status == "failed":
            continue
        if needle and parsed.fingerprint and not _fp_match(needle, parsed.fingerprint):
            if not _norm_tokens(goal) & _norm_tokens(parsed.trigger or parsed.purpose):
                continue
        seen.add(parsed.name)
        found.append((item, parsed))
        if len(found) >= limit:
            break
    return found


def suggestable(record: SkillRecord) -> bool:
    return record.status not in {"failed"}


def apply_skill_outcome(record: SkillRecord, *, success: bool) -> tuple[SkillRecord, str]:
    """Return updated skill and a UI note. Does not write memory."""
    now = utc_now()
    attempts = record.attempts + 1
    successes = record.successes + (1 if success else 0)
    failures = record.failures + (0 if success else 1)
    streak = 0 if success else record.consecutive_failures + 1
    status = record.status
    note = ""
    if success:
        if status == "candidate" and successes >= _VERIFY_SUCCESSES:
            status = "verified"
            note = f"Skill {record.name} verified."
        elif status == "degraded" and streak == 0 and successes > failures:
            status = "verified"
            note = f"Skill {record.name} recovered."
    else:
        if status == "verified" and streak >= _DEGRADE_STREAK:
            status = "degraded"
            note = "Skill performance degraded."
        elif status == "degraded" and streak >= _FAIL_STREAK:
            status = "failed"
            note = f"Skill {record.name} marked failed."
        elif status == "candidate" and streak >= _DEGRADE_STREAK:
            status = "failed"
            note = f"Skill {record.name} marked failed."
    updated = replace(
        record,
        attempts=attempts,
        successes=successes,
        failures=failures,
        consecutive_failures=streak,
        status=status,
        updated_at=now,
    )
    return updated, note


def maybe_create_skill(memory: Any, *, goal: str, tools: Sequence[str], strategy: str) -> SkillRecord | None:
    """Create skill_candidate after two matching successful experiences."""
    fingerprint = skill_fingerprint(goal, tools)
    existing = _skill_by_fingerprint(memory, fingerprint)
    if existing is not None:
        return None
    successes = _matching_success_count(memory, fingerprint)
    if successes < _CREATE_SUCCESSES:
        return None
    now = utc_now()
    name = skill_name_for(fingerprint, goal)
    record = SkillRecord(
        name=name,
        purpose=_purpose_for(name, goal),
        trigger=goal,
        strategy=strategy or "Inspect relevant files, then diagnose.",
        tools=tuple(str(item) for item in tools if str(item)),
        attempts=successes,
        successes=successes,
        failures=0,
        consecutive_failures=0,
        status="candidate",
        fingerprint=fingerprint,
        created_at=now,
        updated_at=now,
    )
    ident = _write_skill(memory, record, record_id="")
    if not ident:
        return None
    return replace(record, record_id=ident)


def persist_skill(memory: Any, record: SkillRecord, memory_item: Any | None = None) -> str:
    ident = str(getattr(memory_item, "record_id", "") or record.record_id or "")
    return _write_skill(memory, record, record_id=ident)


def skill_tags(status: str) -> tuple[str, ...]:
    status_tag = {
        "candidate": "skill_candidate",
        "verified": "skill_verified",
        "degraded": "skill_degraded",
        "failed": "skill_failed",
    }.get(status, "skill_candidate")
    return ("skill", status_tag)


def skill_plan_hint(records: Sequence[SkillRecord]) -> str:
    if not records:
        return ""
    lines = [
        "Relevant skills (suggestions). You may USE, MODIFY, or IGNORE.",
        "Do not override the current goal or security policy. Allowed tools only.",
    ]
    for item in records[:2]:
        rate = f"{item.successes}/{item.attempts}" if item.attempts else "0/0"
        lines.append(f"Relevant skill: {item.name}")
        lines.append(f"Previous success rate: {rate}")
        lines.append(f"Status: {item.status}")
        if item.strategy:
            lines.append(f"Suggested strategy: {_clip(item.strategy, 220)}")
    return "\n".join(lines) + "\n"


def skill_status_messages(record: SkillRecord) -> list[str]:
    rate = f"{record.successes}/{record.attempts}" if record.attempts else "0/0"
    messages = [f"Relevant skill found: {record.name}"]
    if record.status == "degraded":
        messages.append("Skill performance degraded.")
    else:
        messages.append("Using previous successful strategy.")
    messages.append(f"Skill confidence: {rate} successful runs.")
    return messages


def _write_skill(memory: Any, record: SkillRecord, *, record_id: str) -> str:
    store = getattr(memory, "store", None)
    if not callable(store):
        return ""
    try:
        from astra.memory.contracts import MemoryRecord, VerificationState

        ident = store(
            MemoryRecord(
                record_id=record_id,
                category=MemoryCategory.EXPERIMENTS,
                content=format_skill(record),
                verification=VerificationState.UNVERIFIED,
                source=SKILL_SOURCE,
                tags=skill_tags(record.status),
            )
        )
        return str(ident or record_id or "")
    except Exception:
        return ""


def _skill_by_fingerprint(memory: Any, fingerprint: str) -> SkillRecord | None:
    if not fingerprint:
        return None
    for item in _iter_skill_memories(memory, fingerprint):
        parsed = parse_skill(str(getattr(item, "content", "") or ""))
        if parsed is None:
            continue
        if parsed.fingerprint == fingerprint or _fp_match(fingerprint, parsed.fingerprint):
            ident = str(getattr(item, "record_id", "") or "")
            return replace(parsed, record_id=ident) if ident else parsed
    return None


def _matching_success_count(memory: Any, fingerprint: str) -> int:
    from astra.core.experience import parse_experience

    count = 0
    for item in _iter_memories(memory):
        tags = set(str(tag) for tag in (getattr(item, "tags", ()) or ()))
        content = str(getattr(item, "content", "") or "")
        if content.startswith(SKILL_MARK):
            continue
        parsed = parse_experience(content)
        if parsed is None:
            continue
        if parsed.outcome != "COMPLETE":
            continue
        if tags and "strategy_ok" not in tags and "skill" not in tags:
            continue
        fp = skill_fingerprint(parsed.goal, parsed.tools)
        if _fp_match(fingerprint, fp):
            count += 1
    return count


def _iter_skill_memories(memory: Any, goal: str) -> list[Any]:
    items: list[Any] = []
    seen: set[int] = set()
    for item in _iter_memories(memory):
        content = str(getattr(item, "content", "") or "")
        if not content.startswith(SKILL_MARK):
            continue
        ident = id(item)
        if ident in seen:
            continue
        seen.add(ident)
        items.append(item)
    if items:
        return items
    search = getattr(memory, "search", None)
    if not callable(search):
        return []
    for query in ("SKILL",) + _search_queries(goal)[:4]:
        try:
            hits = search(query, category=MemoryCategory.EXPERIMENTS, limit=20)
        except TypeError:
            try:
                hits = search(query, limit=20)
            except Exception:
                continue
        except Exception:
            continue
        for item in hits or ():
            content = str(getattr(item, "content", "") or "")
            if content.startswith(SKILL_MARK):
                items.append(item)
    return items


def _iter_memories(memory: Any) -> list[Any]:
    iterate = getattr(memory, "iter_records", None)
    if callable(iterate):
        try:
            return list(iterate())
        except TypeError:
            try:
                return list(iterate(category=MemoryCategory.EXPERIMENTS))
            except Exception:
                return []
        except Exception:
            return []
    records = getattr(memory, "records", None)
    if isinstance(records, list):
        return list(records)
    return []


def _norm_tokens(goal: str, tools: Sequence[str] = ()) -> set[str]:
    text = f"{goal} {' '.join(str(item) for item in tools)}"
    tokens = {tok.lower() for tok in _TOKEN.findall(text)}
    if any(token.startswith("fail") for token in tokens):
        tokens.add("fail")
    if "tests" in tokens or "unittest" in tokens:
        tokens.add("test")
    if "investigate" in tokens or "diagnose" in tokens:
        tokens.add("inspect")
    if "fail" in tokens and "test" in tokens:
        tokens.add("inspect")
        tokens.add("python")
    return tokens


def _fp_match(left: str, right: str) -> bool:
    a = {part for part in left.split("|") if part}
    b = {part for part in right.split("|") if part}
    if not a or not b:
        return False
    return len(a & b) >= min(2, len(a), len(b))


def _purpose_for(name: str, goal: str) -> str:
    if name == "python_test_failure_diagnosis":
        return "Diagnose failures in Python test suites."
    if name == "inspection_report_write":
        return "Inspect a project and save a short report."
    if name == "project_inspect":
        return "Inspect project files and summarize findings."
    return _clip(goal, 160)


def _int(raw: str) -> int:
    try:
        return int(float(raw))
    except (TypeError, ValueError):
        return 0


__all__ = [
    "SKILL_MARK",
    "SkillRecord",
    "apply_skill_outcome",
    "format_skill",
    "maybe_create_skill",
    "parse_skill",
    "persist_skill",
    "retrieve_skills",
    "skill_fingerprint",
    "skill_name_for",
    "skill_plan_hint",
    "skill_status_messages",
    "skill_tags",
    "suggestable",
]
