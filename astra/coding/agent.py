"""Proposal-only Coding Agent (B-019 / B-020, ADR-0018 / ADR-0019).

Reads current files only through ``ToolInvoker`` / ``read_project``.
Retains the last proposal for inspection. Never writes, applies, or lists
directories.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Mapping, Sequence

from astra.coding.contracts import ChangeProposal, CodingAgent, FileChange
from astra.core.contracts import AgentRequest, AgentResult
from astra.core.lifecycle import MeasurableProperty
from astra.logging import get_logger
from astra.tools.contracts import ToolInvocationRequest

_LOGGER = get_logger(__name__)

_TOOL_NAME = "read_project"
_MAX_TARGETS = 3
_PROMPT_BUDGET = 8000
_PREVIEW_LIMIT = 2048
_TEXT_CAP = 16
_TEXT_WIDTH = 200
_FORBIDDEN_STATUS = frozenset({"applied", "approved", "tested", "deployed"})
_FILE_BLOCK = re.compile(
    r"<<<FILE\s+(?P<path>.+?)\n(?P<body>.*?)>>>END",
    re.DOTALL,
)

_PROPERTY_KEYWORDS: tuple[tuple[str, MeasurableProperty], ...] = (
    ("performance", MeasurableProperty.PERFORMANCE),
    ("latency", MeasurableProperty.PERFORMANCE),
    ("simpl", MeasurableProperty.SIMPLICITY),
    ("maintain", MeasurableProperty.MAINTAINABILITY),
    ("reliab", MeasurableProperty.RELIABILITY),
    ("secur", MeasurableProperty.SECURITY),
    ("accur", MeasurableProperty.ACCURACY),
    ("resource", MeasurableProperty.RESOURCE_EFFICIENCY),
    ("memory", MeasurableProperty.RESOURCE_EFFICIENCY),
    ("experience", MeasurableProperty.USER_EXPERIENCE),
    ("usability", MeasurableProperty.USER_EXPERIENCE),
)


def _as_path(value: Any) -> Path | None:
    if isinstance(value, Path):
        return value
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    return Path(text)


def _parse_targets(raw: Any) -> tuple[Path, ...] | None:
    """Return explicit targets, or None if the caller authorized none."""
    if raw is None:
        return None
    if isinstance(raw, (str, Path)):
        path = _as_path(raw)
        return (path,) if path is not None else None
    if not isinstance(raw, (list, tuple)):
        return None
    found: list[Path] = []
    for item in raw:
        path = _as_path(item)
        if path is not None:
            found.append(path)
    return tuple(found)


def _improves_from(
    objective: str, context: Mapping[str, Any] | None
) -> tuple[tuple[MeasurableProperty, ...], bool]:
    """Return (properties, inferred). inferred True when caller did not supply them."""
    raw = None if context is None else context.get("improves")
    parsed: list[MeasurableProperty] = []
    if raw is not None:
        values = raw if isinstance(raw, (list, tuple)) else (raw,)
        for item in values:
            if isinstance(item, MeasurableProperty):
                parsed.append(item)
                continue
            token = str(getattr(item, "value", item) or "").strip().lower()
            for prop in MeasurableProperty:
                if prop.value == token or prop.name.lower() == token:
                    parsed.append(prop)
                    break
        if parsed:
            return tuple(dict.fromkeys(parsed)), False
    found: list[MeasurableProperty] = []
    blob = objective.lower()
    for keyword, prop in _PROPERTY_KEYWORDS:
        if keyword in blob and prop not in found:
            found.append(prop)
    if found:
        return tuple(found), True
    return (MeasurableProperty.MAINTAINABILITY,), True


def _safe_status(status: str) -> str:
    text = (status or "none").strip() or "none"
    if text in _FORBIDDEN_STATUS:
        return "generated"
    return text


def _clip_texts(items: Sequence[Any], *, limit: int = _TEXT_CAP, width: int = _TEXT_WIDTH) -> tuple[str, ...]:
    out: list[str] = []
    for item in items:
        if len(out) >= limit:
            break
        text = str(item)
        if len(text) > width:
            text = text[:width]
        out.append(text)
    return tuple(out)


def _preview_from(proposal: ChangeProposal | None) -> str:
    if proposal is None or not proposal.changes:
        return ""
    parts: list[str] = []
    for change in proposal.changes:
        parts.append(f"--- {change.path} ---")
        parts.append(change.new_content)
    blob = "\n".join(parts)
    if len(blob) > _PREVIEW_LIMIT:
        return blob[:_PREVIEW_LIMIT]
    return blob


class LocalCodingAgent(CodingAgent):
    """Produces reviewable proposals. Does not apply them."""

    name = "coding"

    def __init__(self, tools: Any | None = None, llm: Any | None = None) -> None:
        self._tools = tools
        self._llm = llm
        self._last_objective = ""
        self._last_proposal_status = "none"
        self._last_change_count = 0
        self._last_risk_count = 0
        self._llm_used = False
        self._last_reads: dict[str, str] = {}
        self._last_proposal: ChangeProposal | None = None
        self._last_objections: tuple[str, ...] = ()

    @property
    def last_objective(self) -> str:
        return self._last_objective

    @property
    def last_proposal(self) -> ChangeProposal | None:
        return self._last_proposal

    @property
    def last_objections(self) -> tuple[str, ...]:
        return self._last_objections

    @property
    def last_change_paths(self) -> tuple[str, ...]:
        if self._last_proposal is None:
            return ()
        return tuple(str(c.path) for c in self._last_proposal.changes)

    @property
    def last_risks(self) -> tuple[str, ...]:
        if self._last_proposal is None:
            return ()
        return _clip_texts(self._last_proposal.risks)

    @property
    def last_improves(self) -> tuple[str, ...]:
        if self._last_proposal is None:
            return ()
        names: list[str] = []
        for prop in self._last_proposal.improves:
            names.append(str(getattr(prop, "value", prop)))
        return tuple(names)

    @property
    def last_preview(self) -> str:
        return _preview_from(self._last_proposal)

    @property
    def last_objection_count(self) -> int:
        return len(self._last_objections)

    @property
    def last_proposal_status(self) -> str:
        return _safe_status(self._last_proposal_status)

    @property
    def last_change_count(self) -> int:
        return self._last_change_count

    @property
    def last_risk_count(self) -> int:
        return self._last_risk_count

    @property
    def llm_used(self) -> bool:
        return self._llm_used

    def propose_change(
        self, objective: str, targets: Sequence[Path] | None = None
    ) -> ChangeProposal:
        risks: list[str] = []
        improves, inferred = _improves_from(objective, None)
        self._last_objective = objective
        self._llm_used = False
        self._last_reads = {}
        self._last_objections = ()

        if inferred:
            risks.append("improves inferred from objective; caller should pass improves")

        if self._tools is None:
            risks.append("ToolInvoker missing")
            return self._finish(objective, (), improves, risks, "failed")

        if not (objective or "").strip():
            risks.append("objective is empty")
            return self._finish(objective, (), improves, risks, "failed")

        if targets is None or len(tuple(targets)) == 0:
            risks.append("targets required; B-019 does not list directories")
            return self._finish(objective, (), improves, risks, "failed")

        authorized = tuple(targets)
        if len(authorized) > _MAX_TARGETS:
            risks.append(
                f"only first {_MAX_TARGETS} targets are authorized; extras ignored"
            )
            authorized = authorized[:_MAX_TARGETS]

        current: list[tuple[Path, str]] = []
        for path in authorized:
            text = self._read_via_tool(path, risks)
            if text is None:
                continue
            self._last_reads[str(path)] = text
            current.append((path, text))

        if not current:
            risks.append("no authorized files could be read")
            return self._finish(objective, (), improves, risks, "failed")

        if self._llm is None or not self._llm_available():
            risks.append("LLM unavailable; no proposal generated")
            return self._finish(objective, (), improves, risks, "failed")

        try:
            from astra.reasoning.backends import GenerationOptions

            prompt = _build_prompt(objective, current)
            opts = GenerationOptions(temperature=0.1, max_tokens=1024)
            raw = self._llm.generate(prompt, opts)
            self._llm_used = True
        except Exception as exc:  # noqa: BLE001
            _LOGGER.warning("coding LLM generate failed: %s", exc)
            risks.append(f"LLM generate failed: {exc}")
            return self._finish(objective, (), improves, risks, "failed")

        changes = _parse_changes(raw, current, risks)
        status = "generated" if changes else "failed"
        if not changes:
            risks.append("LLM output produced no FileChanges")
        return self._finish(objective, changes, improves, risks, status)

    def review(self, proposal: ChangeProposal) -> Sequence[str]:
        objections: list[str] = []
        if not proposal.improves:
            objections.append("improves is empty")
        for change in proposal.changes:
            if change.path is None or str(change.path).strip() == "":
                objections.append("FileChange path is missing")
            if change.new_content == "":
                objections.append(f"FileChange new_content is empty for {change.path}")
            key = str(change.path)
            previous = self._last_reads.get(key)
            if previous is not None and previous == change.new_content:
                objections.append(f"no-op proposal for {change.path}")
        return tuple(objections)

    def run(self, request: AgentRequest) -> AgentResult:
        try:
            if request is None or not str(request.objective or "").strip():
                proposal = ChangeProposal(
                    objective=str(getattr(request, "objective", "") or ""),
                    changes=(),
                    improves=(MeasurableProperty.MAINTAINABILITY,),
                    risks=("objective is empty",),
                )
                self._last_objective = proposal.objective
                self._last_proposal_status = "failed"
                self._llm_used = False
                self._retain(proposal)
                self._review_once(proposal)
                return AgentResult(
                    agent=self.name,
                    success=False,
                    summary="coding objective must not be empty",
                    details=self._result_details(proposal),
                )
            context = dict(request.context or {})
            if "targets" in context and context["targets"] is not None:
                if not isinstance(context["targets"], (list, tuple, str, Path)):
                    proposal = ChangeProposal(
                        objective=request.objective,
                        changes=(),
                        improves=_improves_from(request.objective, context)[0],
                        risks=("malformed targets in request context",),
                    )
                    self._last_objective = request.objective
                    self._last_proposal_status = "failed"
                    self._llm_used = False
                    self._retain(proposal)
                    self._review_once(proposal)
                    return AgentResult(
                        agent=self.name,
                        success=False,
                        summary="malformed targets in request context",
                        details=self._result_details(proposal),
                    )
            targets = _parse_targets(context.get("targets"))
            improves, inferred = _improves_from(request.objective, context)
            proposal = self.propose_change(request.objective, targets)
            risks = proposal.risks
            if not inferred:
                risks = tuple(
                    r for r in risks if "improves inferred" not in r
                )
            if proposal.improves != improves or risks != proposal.risks:
                proposal = ChangeProposal(
                    objective=proposal.objective,
                    changes=proposal.changes,
                    improves=improves,
                    risks=risks,
                )
            self._retain(proposal)
            self._review_once(proposal)
            return AgentResult(
                agent=self.name,
                success=bool(proposal.changes),
                summary=(
                    f"proposal generated: {len(proposal.changes)} file(s)"
                    if proposal.changes
                    else "no file changes proposed"
                ),
                details=self._result_details(proposal),
                evidence=tuple(str(c.path) for c in proposal.changes),
            )
        except Exception as exc:  # noqa: BLE001
            _LOGGER.warning("coding run failed: %s", exc)
            self._last_proposal_status = "failed"
            return AgentResult(
                agent=self.name,
                success=False,
                summary=f"coding agent failed: {exc}",
                details={"applied": False},
            )

    def _llm_available(self) -> bool:
        check = getattr(self._llm, "is_available", None)
        if not callable(check):
            return False
        try:
            return bool(check())
        except Exception:
            return False

    def _read_via_tool(self, path: Path, risks: list[str]) -> str | None:
        request = getattr(self._tools, "request", None)
        if not callable(request):
            risks.append("ToolInvoker.request missing")
            return None
        try:
            rec = request(
                ToolInvocationRequest(
                    caller="coding",
                    tool_name=_TOOL_NAME,
                    arguments={"path": str(path)},
                )
            )
        except Exception as exc:  # noqa: BLE001
            risks.append(f"read_project raised for {path}: {exc}")
            return None
        if not getattr(rec, "executed", False):
            risks.append(
                f"read_project denied or blocked for {path}: "
                f"{getattr(rec, 'reason', 'no reason')}"
            )
            return None
        result = getattr(rec, "result", None)
        if result is None or not getattr(result, "success", False):
            err = getattr(result, "error", "no result") if result is not None else "no result"
            risks.append(f"read_project failed for {path}: {err}")
            return None
        details = getattr(result, "details", None) or {}
        if details.get("truncated"):
            risks.append(f"read truncated at 64 KiB; omitted {path}")
            return None
        text = getattr(result, "output", "") or ""
        if len(text) > _PROMPT_BUDGET:
            risks.append(f"file exceeds prompt budget; omitted {path}")
            return None
        return text

    def _retain(self, proposal: ChangeProposal) -> None:
        self._last_proposal = proposal
        self._last_change_count = len(proposal.changes)
        self._last_risk_count = len(proposal.risks)

    def _review_once(self, proposal: ChangeProposal) -> tuple[str, ...]:
        objections = tuple(self.review(proposal))
        self._last_objections = _clip_texts(objections)
        return self._last_objections

    def _result_details(self, proposal: ChangeProposal) -> dict[str, Any]:
        return {
            "change_count": len(proposal.changes),
            "risk_count": len(proposal.risks),
            "applied": False,
            "llm_used": self._llm_used,
            "proposal_status": self.last_proposal_status,
            "objection_count": len(self._last_objections),
            "change_paths": tuple(str(c.path) for c in proposal.changes),
        }

    def _finish(
        self,
        objective: str,
        changes: tuple[FileChange, ...],
        improves: tuple[MeasurableProperty, ...],
        risks: list[str],
        status: str,
    ) -> ChangeProposal:
        self._last_proposal_status = _safe_status(status)
        proposal = ChangeProposal(
            objective=objective,
            changes=changes,
            improves=improves,
            risks=tuple(risks),
        )
        self._retain(proposal)
        return proposal


def _build_prompt(objective: str, files: Sequence[tuple[Path, str]]) -> str:
    parts = [
        "You propose complete replacement contents for existing files.",
        "Do not explain. For each file output exactly:",
        "<<<FILE path",
        "full new file contents",
        ">>>END",
        f"Objective: {objective}",
        "Current files:",
    ]
    for path, text in files:
        parts.append(f"--- {path} ---")
        parts.append(text)
    return "\n".join(parts)


def _parse_changes(
    raw: str,
    current: Sequence[tuple[Path, str]],
    risks: list[str],
) -> tuple[FileChange, ...]:
    if not (raw or "").strip():
        risks.append("LLM returned empty output")
        return ()
    by_name = {str(path): (path, text) for path, text in current}
    by_suffix = {path.name: (path, text) for path, text in current}
    found: list[FileChange] = []
    seen: set[str] = set()
    for match in _FILE_BLOCK.finditer(raw):
        label = match.group("path").strip()
        body = match.group("body")
        if body.endswith("\n"):
            body = body[:-1]
        pair = by_name.get(label) or by_suffix.get(Path(label).name)
        if pair is None:
            risks.append(f"LLM named unauthorized path {label!r}; ignored")
            continue
        path, previous = pair
        key = str(path)
        if key in seen:
            continue
        if body.rstrip("\n") == previous.rstrip("\n"):
            risks.append(f"no-op omitted for {path}")
            continue
        if body == "":
            risks.append(f"empty new_content omitted for {path}")
            continue
        seen.add(key)
        found.append(
            FileChange(
                path=path,
                new_content=body,
                rationale="model proposal; not applied",
            )
        )
    return tuple(found)


__all__ = ["LocalCodingAgent"]
