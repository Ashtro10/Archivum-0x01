"""User durable-memory boundary.

This module is deliberately strict: production user memory is only eligible for
retrieval when it has explicit user provenance and a durable tag. Probe/test/
system records are never allowed to become personal context, even if they leak
into a shared database. Field-aware ranking prevents an unrelated identity or
goal record from answering a different personal question.
"""

from __future__ import annotations

import re
from typing import Any, Iterable, Sequence

USER_NAME = "user.name"
USER_PREFERRED_NAME = "user.preferred_name"
USER_CREATOR = "user.creator"
USER_LOCATION = "user.location"
USER_FIRST_CRUSH = "user.first_crush"
USER_CHILDHOOD_FRIEND = "user.childhood_friend"
USER_DOG_NAME = "user.dog_name"
USER_PREFERENCE = "user.preference"
USER_PROJECT = "user.project"
USER_BACKGROUND = "user.background"
USER_FACT = "user.fact"

SOURCE_EXPLICIT_STATEMENT = "explicit_user_statement"
SOURCE_EXPLICIT_PREFERENCE = "explicit_user_preference"
SOURCE_EXPLICIT_CORRECTION = "explicit_user_correction"
SOURCE_PROBE = "probe"
SOURCE_TEST = "test"
SOURCE_CONVERSATION = "conversation"
SOURCE_SYSTEM = "system_generated"

USER_DURABLE_SOURCES = frozenset(
    {
        SOURCE_EXPLICIT_STATEMENT,
        SOURCE_EXPLICIT_PREFERENCE,
        SOURCE_EXPLICIT_CORRECTION,
    }
)

_BLOCKED_SOURCES = frozenset({SOURCE_PROBE, SOURCE_TEST, SOURCE_SYSTEM})

# These patterns are only a legacy backstop. Provenance is the primary boundary.
_PROBE_CONTENT = re.compile(
    r"\b(?:MemProbe[a-f0-9]+|ProbeUser[a-f0-9]*|Project-[0-9a-f]{6,}|"
    r"topic-[0-9a-f]{6,}|runtime milestone|Probe Goal)\b",
    re.IGNORECASE,
)


def field_key_tag(field_key: str) -> str:
    return f"key:{field_key}"


def normalize_custom_field_key(label: str) -> str:
    """Turn a user label into a stable custom field key."""
    slug = re.sub(r"[^a-z0-9]+", "_", str(label or "").lower()).strip("_")
    if not slug:
        return USER_FACT
    return f"user.custom.{slug}"


def memory_display_label(field_key: str) -> str | None:
    """User-facing label for a stored field key, when the user set one."""
    key = str(field_key or "").strip()
    if key.startswith("user.custom."):
        return key[len("user.custom.") :].replace("_", " ").strip() or None
    return None


def record_field_key(record: Any) -> str | None:
    for tag in tuple(getattr(record, "tags", ()) or ()):
        if str(tag).startswith("key:"):
            return str(tag)[4:]
    return _infer_field_key(str(getattr(record, "content", "") or ""))


def _infer_field_key(content: str) -> str | None:
    lowered = content.lower().strip()
    if re.match(r"^(?:the user's|my) name is\b", lowered):
        return USER_NAME
    if re.match(r"^(?:you can call me|call me|the user prefers to be called)\b", lowered):
        return USER_PREFERRED_NAME
    if "creator of primus" in lowered or lowered.startswith("the user is the creator"):
        return USER_CREATOR
    if lowered.startswith("the user lives in ") or lowered.startswith("i live in "):
        return USER_LOCATION
    if lowered.startswith("the user prefers ") or lowered.startswith("i prefer "):
        return USER_PREFERENCE
    if lowered.startswith("the user's project is ") or lowered.startswith("my current project is "):
        return USER_PROJECT
    if "first crush" in lowered:
        return USER_FIRST_CRUSH
    if "childhood friend" in lowered:
        return USER_CHILDHOOD_FRIEND
    if "has a dog" in lowered or "dog called" in lowered or "dog named" in lowered:
        return USER_DOG_NAME
    if lowered.startswith("the user grew") or lowered.startswith("the user studied"):
        return USER_BACKGROUND
    return USER_FACT


def _capture(patterns: Sequence[str], text: str) -> str | None:
    for pattern in patterns:
        match = re.match(pattern, text, flags=re.IGNORECASE)
        if match:
            return match.group(1).strip().rstrip(".")
    return None


def record_value(content: str, field_key: str | None = None) -> str:
    """Extract the user-facing value from normalized durable content."""
    text = str(content or "").strip()
    key = field_key or _infer_field_key(text) or ""

    if key == USER_NAME:
        value = _capture(
            (r"^the user's name is\s+(.+)$", r"^my name is\s+(.+)$"), text
        )
        if value:
            return value

    if key == USER_PREFERRED_NAME:
        value = _capture(
            (
                r"^you can call me\s+(.+)$",
                r"^call me\s+(.+)$",
                r"^the user prefers to be called\s+(.+)$",
            ),
            text,
        )
        if value:
            return value

    if key == USER_CREATOR:
        if "creator of primus" in text.lower():
            return "creator of Primus"
        return "creator of Primus"

    if key == USER_LOCATION:
        value = _capture(
            (r"^the user lives in\s+(.+)$", r"^i live in\s+(.+)$"), text
        )
        if value:
            return value

    if key == USER_PREFERENCE:
        value = _capture(
            (r"^the user prefers\s+(.+)$", r"^i prefer\s+(.+)$"), text
        )
        if value:
            return value

    if key == USER_PROJECT:
        value = _capture(
            (r"^the user's project is\s+(.+)$", r"^my current project is\s+(.+)$"),
            text,
        )
        if value:
            return value

    if key in {USER_FIRST_CRUSH, USER_CHILDHOOD_FRIEND}:
        match = re.search(r"\bwas\s+(.+)$", text, flags=re.IGNORECASE)
        if match:
            return match.group(1).strip().rstrip(".")

    if key == USER_DOG_NAME:
        match = re.search(r"\b(?:called|named)\s+(.+)$", text, flags=re.IGNORECASE)
        if match:
            return match.group(1).strip().rstrip(".")

    if key == USER_BACKGROUND and text.lower().startswith("the user "):
        return text[len("The user ") :].rstrip(".")

    return text.rstrip(".")


def is_probe_contamination(record: Any) -> bool:
    source = str(getattr(record, "source", "") or "").strip().lower()
    if source in _BLOCKED_SOURCES:
        return True
    tags = {str(tag).lower() for tag in tuple(getattr(record, "tags", ()) or ())}
    if "probe" in tags or "test" in tags or "system_generated" in tags:
        return True
    content = str(getattr(record, "content", "") or "")
    return bool(_PROBE_CONTENT.search(content))


def is_user_durable(record: Any) -> bool:
    """Return True only for current, explicitly user-sourced durable memory.

    Important: legacy empty-source/conversation rows are intentionally NOT
    trusted anymore. They were the mechanism by which probe/test/task records
    could be mistaken for personal memory.
    """
    tags = {str(tag).lower() for tag in tuple(getattr(record, "tags", ()) or ())}
    if "conversation" in tags or "durable" not in tags:
        return False

    verification = getattr(record, "verification", None)
    state = str(getattr(verification, "value", verification) or "").lower()
    if state == "refuted":
        return False

    if is_probe_contamination(record):
        return False

    source = str(getattr(record, "source", "") or "").strip()
    return source in USER_DURABLE_SOURCES


def filter_user_durable(records: Iterable[Any]) -> tuple[Any, ...]:
    """Keep only records that are safe to expose as user memory."""
    return tuple(record for record in records if is_user_durable(record))


def write_source(*, probe: bool, test: bool, kind: str, correction: bool = False) -> str:
    if probe:
        return SOURCE_PROBE
    if test:
        return SOURCE_TEST
    if correction:
        return SOURCE_EXPLICIT_CORRECTION
    if kind in {"prefer", "preference"}:
        return SOURCE_EXPLICIT_PREFERENCE
    return SOURCE_EXPLICIT_STATEMENT


def query_field_key(message: str) -> str | None:
    lowered = str(message or "").lower()
    if any(
        marker in lowered
        for marker in (
            "what is my name",
            "what's my name",
            "whats my name",
            "who am i",
            "do you remember my name",
        )
    ):
        return USER_NAME
    if any(
        marker in lowered
        for marker in (
            "what should you call me",
            "what can you call me",
            "what do you call me",
            "my preferred name",
            "what should i call myself",
        )
    ):
        return USER_PREFERRED_NAME
    if any(
        marker in lowered
        for marker in (
            "who is your creator",
            "who created you",
            "who built primus",
            "who made you",
            "who made primus",
        )
    ):
        return USER_CREATOR
    if "first crush" in lowered:
        return USER_FIRST_CRUSH
    if "childhood friend" in lowered:
        return USER_CHILDHOOD_FRIEND
    if "dog" in lowered and ("name" in lowered or "called" in lowered):
        return USER_DOG_NAME
    if any(marker in lowered for marker in ("where do i live", "where i live", "live in")):
        return USER_LOCATION
    if any(
        marker in lowered
        for marker in ("prefer", "preference", "favorite", "favourite", "like to use")
    ):
        return USER_PREFERENCE
    if any(marker in lowered for marker in ("project", "working on", "building", "currently")):
        return USER_PROJECT
    return None


def score_user_fact(query: str, record: Any, field_key: str | None = None) -> float:
    """Score a user-memory candidate with a strong field-match preference."""
    query_tokens = set(re.findall(r"[a-z0-9']+", str(query).lower()))
    content = str(getattr(record, "content", "") or "").lower()
    content_tokens = set(re.findall(r"[a-z0-9']+", content))
    overlap = len(query_tokens & content_tokens)

    requested = field_key or query_field_key(query)
    actual = record_field_key(record)
    field_bonus = 100.0 if requested and actual == requested else 0.0

    # Prefer explicit durable evidence and newer records on ties.
    source_bonus = 10.0 if str(getattr(record, "source", "")) in USER_DURABLE_SOURCES else 0.0
    updated = getattr(record, "updated_at", None)
    recency = 0.0
    if updated is not None:
        try:
            recency = updated.timestamp() / 10_000_000_000.0
        except Exception:
            recency = 0.0
    return field_bonus + overlap + source_bonus + recency


def select_user_fact(
    query: str,
    records: Iterable[Any],
    *,
    field_key: str | None = None,
) -> Any | None:
    """Return the best relevant user fact, or None when there is no safe fact."""
    candidates = filter_user_durable(records)
    if not candidates:
        return None
    requested = field_key or query_field_key(query)
    scored = sorted(
        candidates,
        key=lambda record: score_user_fact(query, record, requested),
        reverse=True,
    )
    if requested:
        exact = [r for r in scored if record_field_key(r) == requested]
        if exact:
            return exact[0]
    return scored[0]


def format_summary_line(field_key: str, content: str) -> str:
    value = record_value(content, field_key)
    labels = {
        USER_NAME: "name",
        USER_PREFERRED_NAME: "preferred name",
        USER_CREATOR: "creator",
        USER_LOCATION: "location",
        USER_FIRST_CRUSH: "first crush",
        USER_CHILDHOOD_FRIEND: "childhood friend",
        USER_DOG_NAME: "dog",
        USER_PREFERENCE: "preference",
        USER_PROJECT: "project",
        USER_BACKGROUND: "background",
    }
    label = labels.get(field_key)
    if label is None and field_key.startswith("user.custom."):
        label = memory_display_label(field_key)
    if label is None:
        label = field_key.removeprefix("user.")
    return f"{label}: {value}"


def inspect_record(record: Any) -> dict[str, str]:
    field_key = record_field_key(record) or ""
    content = str(getattr(record, "content", "") or "")
    verification = getattr(record, "verification", None)
    status = str(getattr(verification, "value", verification) or "")
    if status == "refuted":
        status_label = "REFUTED"
    elif is_user_durable(record):
        status_label = "ACTIVE"
    elif is_probe_contamination(record):
        status_label = "PROBE/TEST"
    else:
        status_label = status.upper() or "UNKNOWN"
    created = getattr(record, "created_at", None)
    updated = getattr(record, "updated_at", None)
    return {
        "field": field_key,
        "label": memory_display_label(field_key) or "",
        "content": content,
        "value": record_value(content, field_key or None) if field_key else content,
        "source": str(getattr(record, "source", "") or ""),
        "created_at": str(created or ""),
        "updated_at": str(updated or ""),
        "status": status_label,
    }


def is_probe_goal_text(text: str) -> bool:
    return bool(_PROBE_CONTENT.search(str(text or "")))


__all__ = [
    "USER_NAME",
    "USER_PREFERRED_NAME",
    "USER_CREATOR",
    "USER_LOCATION",
    "USER_FIRST_CRUSH",
    "USER_CHILDHOOD_FRIEND",
    "USER_DOG_NAME",
    "USER_PREFERENCE",
    "USER_PROJECT",
    "USER_BACKGROUND",
    "USER_FACT",
    "SOURCE_EXPLICIT_STATEMENT",
    "SOURCE_EXPLICIT_PREFERENCE",
    "SOURCE_EXPLICIT_CORRECTION",
    "SOURCE_PROBE",
    "SOURCE_TEST",
    "filter_user_durable",
    "field_key_tag",
    "normalize_custom_field_key",
    "memory_display_label",
    "record_field_key",
    "record_value",
    "is_user_durable",
    "is_probe_contamination",
    "write_source",
    "query_field_key",
    "score_user_fact",
    "select_user_fact",
    "format_summary_line",
    "inspect_record",
    "is_probe_goal_text",
]
