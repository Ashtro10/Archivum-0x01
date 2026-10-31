"""Security-gated tool invoker (ADR-0015 D1, D5–D8).

Architectural gate, not an OS sandbox. Controller supplies the production
allowlist (B-018: ``read_text`` and ``read_project``).
"""

from __future__ import annotations

import uuid
from collections import deque
from datetime import datetime, timezone
from pathlib import Path
from types import MappingProxyType
from typing import Any, Mapping

from astra.logging import get_logger
from astra.tools.contracts import (
    ExecutionDecision,
    InvocationStatus,
    SecurityPolicyPort,
    Tool,
    ToolInvocationRecord,
    ToolInvocationRequest,
    ToolRegistry,
    ToolResult,
    ToolSpec,
)
from astra.tools.guard import GATE_TOKEN

_LOGGER = get_logger(__name__)

_HISTORY_MAX = 32
TOOL_NETWORK_SENTINEL = "https://tool-network.invalid/"

_VERDICT_ALLOW = "allow"
_VERDICT_DENY = "deny"
_VERDICT_REQUIRES = "requires_approval"

_PATH_KEYS = frozenset({"path", "file", "filepath"})
_URL_KEYS = frozenset({"url", "uri", "href", "endpoint"})


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _freeze_args(arguments: Mapping[str, Any] | None) -> Mapping[str, Any]:
    if not arguments:
        return MappingProxyType({})
    return MappingProxyType(dict(arguments))


def _verdict_value(decision: object | None) -> str:
    """Read a duck-typed PolicyDecision. Unknown values fail closed as deny."""
    if decision is None:
        return _VERDICT_DENY
    raw = getattr(decision, "verdict", None)
    if raw is None:
        return _VERDICT_DENY
    text = str(getattr(raw, "value", raw)).lower().strip()
    if text in {_VERDICT_ALLOW, _VERDICT_DENY, _VERDICT_REQUIRES}:
        return text
    return _VERDICT_DENY


def _decision_reason(decision: object | None, fallback: str) -> str:
    if decision is None:
        return fallback
    reason = str(getattr(decision, "reason", "") or "").strip()
    return reason or fallback


def _path_like_values(arguments: Mapping[str, Any]) -> list[Path]:
    found: list[Path] = []
    for key, value in arguments.items():
        if isinstance(value, Path):
            found.append(value)
            continue
        if key in _PATH_KEYS and value is not None and str(value).strip():
            found.append(Path(str(value)))
    return found


def _network_url(arguments: Mapping[str, Any]) -> str:
    for key in _URL_KEYS:
        value = arguments.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return TOOL_NETWORK_SENTINEL


def _input_control_description(spec: ToolSpec, arguments: Mapping[str, Any]) -> str:
    action = str(arguments.get("action") or "").strip()
    text = str(arguments.get("text") or "").strip()
    key = str(arguments.get("key") or "").strip()
    keys = str(arguments.get("keys") or "").strip()
    x = str(arguments.get("x") or "").strip()
    y = str(arguments.get("y") or "").strip()
    return (
        f"{spec.name} action={action or '(none)'} text={text} "
        f"key={key} keys={keys} x={x} y={y}"
    )


def _change_description(spec: ToolSpec, request: ToolInvocationRequest) -> str:
    caller = (request.caller or "").strip() or "unknown"
    desc = (spec.description or "").strip() or spec.name
    return f"{caller} requests {spec.name}: {desc}"


def _combine(
    parts: list[tuple[str, str]],
) -> tuple[str, str]:
    """DENY wins, then REQUIRES_APPROVAL, then ALLOW."""
    if not parts:
        return _VERDICT_DENY, "no applicable Security check; deny by default"
    denies = [(v, r) for v, r in parts if v == _VERDICT_DENY]
    if denies:
        return denies[0]
    requires = [(v, r) for v, r in parts if v == _VERDICT_REQUIRES]
    if requires:
        return requires[0]
    allows = [(v, r) for v, r in parts if v == _VERDICT_ALLOW]
    if allows:
        return allows[0]
    return _VERDICT_DENY, "unrecognised combined verdict; fail closed"


class GatedToolInvoker:
    """Consults Security, then maybe executes. Dual-key. Deny by default."""

    def __init__(
        self,
        registry: ToolRegistry,
        security: SecurityPolicyPort | None = None,
        execution_allowlist: object = (),
    ) -> None:
        self._registry = registry
        self._security = security
        self._allowlist = frozenset(str(n) for n in execution_allowlist)
        self._history: deque[ToolInvocationRecord] = deque(maxlen=_HISTORY_MAX)
        self._pending: dict[str, ToolInvocationRecord] = {}

    @property
    def execution_allowlist(self) -> frozenset[str]:
        return self._allowlist

    @property
    def pending_approvals(self) -> int:
        return sum(1 for rec in self._pending.values() if not rec.approved)

    @property
    def executed_count(self) -> int:
        return sum(1 for rec in self._history if rec.executed)

    @property
    def last_record(self) -> ToolInvocationRecord | None:
        if not self._history:
            return None
        return self._history[-1]

    def request(self, request: ToolInvocationRequest) -> ToolInvocationRecord:
        args = _freeze_args(request.arguments)
        request = ToolInvocationRequest(
            caller=request.caller,
            tool_name=request.tool_name,
            arguments=args,
        )
        name = (request.tool_name or "").strip()

        if self._security is None:
            return self._finish(
                request,
                spec_name=name,
                verdict=_VERDICT_DENY,
                reason="security missing; fail closed",
                status=InvocationStatus.DENIED,
            )

        if not name:
            return self._finish(
                request,
                spec_name="",
                verdict=_VERDICT_DENY,
                reason="empty tool name; fail closed",
                status=InvocationStatus.DENIED,
            )

        tool = self._registry.get(name)
        if tool is None:
            return self._finish(
                request,
                spec_name=name,
                verdict=_VERDICT_DENY,
                reason="unknown tool; fail closed",
                status=InvocationStatus.DENIED,
            )

        spec = tool.spec
        verdict, reason = self._consult(spec, request, args)

        if verdict == _VERDICT_DENY:
            return self._finish(
                request,
                spec_name=spec.name,
                verdict=verdict,
                reason=reason,
                status=InvocationStatus.DENIED,
            )

        if verdict == _VERDICT_REQUIRES:
            rec = self._finish(
                request,
                spec_name=spec.name,
                verdict=verdict,
                reason=reason,
                status=InvocationStatus.PENDING_APPROVAL,
            )
            self._pending[rec.invocation_id] = rec
            return rec

        decision = self._execution_decision(spec.name)
        if not decision.permitted:
            return self._finish(
                request,
                spec_name=spec.name,
                verdict=verdict,
                reason=f"{reason}; {decision.reason}",
                status=InvocationStatus.BLOCKED_EXECUTION,
                execution_permitted=False,
            )

        result = self._invoke_guarded(tool, args)
        return self._finish(
            request,
            spec_name=spec.name,
            verdict=verdict,
            reason=reason,
            status=InvocationStatus.EXECUTED,
            execution_permitted=True,
            executed=True,
            result=result,
        )

    def record_human_approval(self, invocation_id: str) -> ToolInvocationRecord:
        """Stamp approval. Does not invoke. Does not expand the allowlist."""
        rec = self._pending.get(invocation_id)
        if rec is None:
            return self._finish(
                ToolInvocationRequest(caller="", tool_name=""),
                spec_name="",
                verdict=_VERDICT_DENY,
                reason="unknown invocation id; fail closed; not executed",
                status=InvocationStatus.DENIED,
            )
        approved = ToolInvocationRecord(
            invocation_id=rec.invocation_id,
            request=rec.request,
            spec_name=rec.spec_name,
            verdict=rec.verdict,
            reason=rec.reason,
            status=rec.status,
            execution_permitted=False,
            executed=False,
            approved=True,
            result=rec.result,
            timestamp=_utc_now(),
        )
        self._pending.pop(invocation_id, None)
        self._replace_history(approved)
        _LOGGER.debug("tool approval recorded for %s; not executed", invocation_id)
        return approved

    def _consult(
        self,
        spec: ToolSpec,
        request: ToolInvocationRequest,
        arguments: Mapping[str, Any],
    ) -> tuple[str, str]:
        security = self._security
        assert security is not None
        parts: list[tuple[str, str]] = []
        if spec.requires_network:
            decision = security.check_network_access(_network_url(arguments))
            parts.append(
                (
                    _verdict_value(decision),
                    _decision_reason(decision, "network check failed closed"),
                )
            )
        if spec.makes_permanent_change:
            decision = security.check_permanent_change(
                _change_description(spec, request)
            )
            parts.append(
                (
                    _verdict_value(decision),
                    _decision_reason(
                        decision, "permanent-change check failed closed"
                    ),
                )
            )
        if getattr(spec, "requires_process", False):
            exe = str(
                arguments.get("executable")
                or arguments.get("application")
                or arguments.get("path")
                or ""
            ).strip()
            check = getattr(security, "check_application_launch", None)
            if not callable(check):
                parts.append(
                    (
                        _VERDICT_DENY,
                        "check_application_launch missing; fail closed",
                    )
                )
            else:
                decision = check(exe)
                parts.append(
                    (
                        _verdict_value(decision),
                        _decision_reason(
                            decision, "application-launch check failed closed"
                        ),
                    )
                )
        if getattr(spec, "inspects_processes", False):
            query = str(
                arguments.get("query") or arguments.get("pid") or ""
            ).strip()
            check = getattr(security, "check_process_inspection", None)
            if not callable(check):
                parts.append(
                    (
                        _VERDICT_DENY,
                        "check_process_inspection missing; fail closed",
                    )
                )
            else:
                decision = check(query)
                parts.append(
                    (
                        _verdict_value(decision),
                        _decision_reason(
                            decision, "process-inspection check failed closed"
                        ),
                    )
                )
        if getattr(spec, "inspects_environment", False):
            kind = str(arguments.get("kind") or spec.name or "").strip()
            check = getattr(security, "check_environment_perception", None)
            if not callable(check):
                parts.append(
                    (
                        _VERDICT_DENY,
                        "check_environment_perception missing; fail closed",
                    )
                )
            else:
                decision = check(kind)
                parts.append(
                    (
                        _verdict_value(decision),
                        _decision_reason(
                            decision, "environment-perception check failed closed"
                        ),
                    )
                )
        if getattr(spec, "controls_input", False):
            check = getattr(security, "check_input_control", None)
            if not callable(check):
                parts.append(
                    (
                        _VERDICT_DENY,
                        "check_input_control missing; fail closed",
                    )
                )
            else:
                decision = check(_input_control_description(spec, arguments))
                parts.append(
                    (
                        _verdict_value(decision),
                        _decision_reason(
                            decision, "input-control check failed closed"
                        ),
                    )
                )
        if (
            not getattr(spec, "requires_process", False)
            and not getattr(spec, "inspects_processes", False)
            and not getattr(spec, "inspects_environment", False)
            and not getattr(spec, "controls_input", False)
        ):
            for path in _path_like_values(arguments):
                parts.append(self._consult_path(security, spec, path))
        return _combine(parts)

    def execute_approved(self, invocation_id: str) -> ToolInvocationRecord:
        """Run a previously approved invocation. Does not stamp approval."""
        ident = str(invocation_id or "").strip()
        rec = None
        for item in self._history:
            if item.invocation_id == ident:
                rec = item
                break
        if rec is None or not rec.approved or rec.executed:
            return self._finish(
                ToolInvocationRequest(caller="", tool_name=""),
                spec_name="",
                verdict=_VERDICT_DENY,
                reason="approved invocation not found; fail closed; not executed",
                status=InvocationStatus.DENIED,
            )
        if rec.status is not InvocationStatus.PENDING_APPROVAL:
            return self._finish(
                rec.request,
                spec_name=rec.spec_name,
                verdict=_VERDICT_DENY,
                reason="invocation is not pending approval; fail closed",
                status=InvocationStatus.DENIED,
            )
        tool = self._registry.get(rec.spec_name)
        if tool is None:
            return self._finish(
                rec.request,
                spec_name=rec.spec_name,
                verdict=_VERDICT_DENY,
                reason="unknown tool; fail closed",
                status=InvocationStatus.DENIED,
            )
        if self._security is None:
            return self._finish(
                rec.request,
                spec_name=rec.spec_name,
                verdict=_VERDICT_DENY,
                reason="security missing; fail closed",
                status=InvocationStatus.DENIED,
            )
        path_parts: list[tuple[str, str]] = []
        for path in _path_like_values(rec.request.arguments):
            path_parts.append(self._consult_path(self._security, tool.spec, path))
        if path_parts:
            verdict, reason = _combine(path_parts)
            if verdict == _VERDICT_DENY:
                denied = ToolInvocationRecord(
                    invocation_id=rec.invocation_id,
                    request=rec.request,
                    spec_name=rec.spec_name,
                    verdict=verdict,
                    reason=reason,
                    status=InvocationStatus.DENIED,
                    execution_permitted=False,
                    executed=False,
                    approved=True,
                    result=ToolResult(success=False, error=reason),
                    timestamp=_utc_now(),
                )
                self._replace_history(denied)
                return denied
        decision = self._execution_decision(rec.spec_name)
        if not decision.permitted:
            blocked = ToolInvocationRecord(
                invocation_id=rec.invocation_id,
                request=rec.request,
                spec_name=rec.spec_name,
                verdict=rec.verdict,
                reason=f"{rec.reason}; {decision.reason}",
                status=InvocationStatus.BLOCKED_EXECUTION,
                execution_permitted=False,
                executed=False,
                approved=True,
                result=ToolResult(success=False, error=decision.reason),
                timestamp=_utc_now(),
            )
            self._replace_history(blocked)
            return blocked
        result = self._invoke_guarded(tool, rec.request.arguments)
        executed = ToolInvocationRecord(
            invocation_id=rec.invocation_id,
            request=rec.request,
            spec_name=rec.spec_name,
            verdict=rec.verdict,
            reason=rec.reason,
            status=InvocationStatus.EXECUTED,
            execution_permitted=True,
            executed=True,
            approved=True,
            result=result,
            timestamp=_utc_now(),
        )
        self._replace_history(executed)
        _LOGGER.debug("approved tool %s executed", rec.spec_name)
        return executed

    @staticmethod
    def _consult_path(security: Any, spec: ToolSpec, path: Path) -> tuple[str, str]:
        if getattr(spec, "scratch_writes", False):
            check = getattr(security, "check_temporary_write", None)
            if not callable(check):
                return _VERDICT_DENY, "check_temporary_write missing; fail closed"
            decision = check(path)
            return (
                _verdict_value(decision),
                _decision_reason(decision, "temporary-write check failed closed"),
            )
        if getattr(spec, "reads_files", False):
            check = getattr(security, "check_file_read", None)
            if not callable(check):
                return _VERDICT_DENY, "check_file_read missing; fail closed"
            decision = check(path)
            return (
                _verdict_value(decision),
                _decision_reason(decision, "file-read check failed closed"),
            )
        decision = security.check_file_write(path)
        return (
            _verdict_value(decision),
            _decision_reason(decision, "file-write check failed closed"),
        )

    def _execution_decision(self, name: str) -> ExecutionDecision:
        if name in self._allowlist:
            return ExecutionDecision(
                permitted=True,
                reason="name is on the execution allowlist",
            )
        return ExecutionDecision(
            permitted=False,
            reason="execution allowlist empty or name absent; ALLOW is not sufficient",
        )

    def _invoke_guarded(self, tool: Tool, arguments: Mapping[str, Any]) -> ToolResult:
        token = GATE_TOKEN.set(True)
        try:
            return tool.invoke(**dict(arguments))
        except Exception as exc:  # noqa: BLE001
            _LOGGER.error("tool %s raised: %s", tool.spec.name, exc)
            return ToolResult(
                success=False,
                error=f"tool raised {type(exc).__name__}: {exc}",
            )
        finally:
            GATE_TOKEN.reset(token)

    def _finish(
        self,
        request: ToolInvocationRequest,
        *,
        spec_name: str,
        verdict: str,
        reason: str,
        status: InvocationStatus,
        execution_permitted: bool = False,
        executed: bool = False,
        result: ToolResult | None = None,
        approved: bool = False,
    ) -> ToolInvocationRecord:
        if result is None:
            result = ToolResult(success=False, error=reason)
        rec = ToolInvocationRecord(
            invocation_id=uuid.uuid4().hex,
            request=request,
            spec_name=spec_name,
            verdict=verdict,
            reason=reason,
            status=status,
            execution_permitted=execution_permitted,
            executed=executed,
            approved=approved,
            result=result,
            timestamp=_utc_now(),
        )
        self._history.append(rec)
        _LOGGER.debug(
            "tool request %s %s -> %s executed=%s (%s)",
            request.caller,
            request.tool_name,
            status.value,
            executed,
            reason,
        )
        return rec

    def _replace_history(self, rec: ToolInvocationRecord) -> None:
        items = list(self._history)
        for i, old in enumerate(items):
            if old.invocation_id == rec.invocation_id:
                items[i] = rec
                break
        else:
            items.append(rec)
        self._history.clear()
        self._history.extend(items[-_HISTORY_MAX:])


__all__ = ["GatedToolInvoker", "TOOL_NETWORK_SENTINEL"]
