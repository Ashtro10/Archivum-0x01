"""Mock environments for the AgentRun harness. No real desktop. No GGUF."""

from __future__ import annotations

from astra.core.agent_loop import ToolCallResult
from astra.deployment.contracts import (
    STATUS_APPROVAL_INVALID,
    STATUS_APPROVAL_MISSING,
    STATUS_DEPLOYED,
    DeploymentResult,
)
from astra.deployment.fingerprint import evidence_fingerprint
from tests.test_agent_loop import (
    FakeCoding,
    FakeEvaluation,
    FakeLLM,
    FakeMemory,
    FakeTesting,
    FakeTools,
)
from tests.test_persistent_work import FakeBacklog, FakeGoals

_DENIED_APPS = frozenset(
    {"cmd", "cmd.exe", "powershell", "powershell.exe", "bash", "sh"}
)
_DENIED_PATH = (
    "windows\\system32",
    "c:\\windows",
    "/etc/",
    "system32/config",
    "system32\\config",
)
_DENIED_KEYS = ("shutdown", "ctrl+alt+del", "alt+f4")


class BenchmarkTools(FakeTools):
    """Fake tools plus security-shaped denials. Does not loosen real gates."""

    def __init__(self) -> None:
        super().__init__()
        self.denied: list[str] = []

    def __call__(self, name: str, arguments: dict[str, str]) -> ToolCallResult:
        app = str(arguments.get("application") or arguments.get("executable") or "")
        path = str(arguments.get("path") or "").replace("/", "\\").lower()
        keys = " ".join(
            str(arguments.get(key) or "")
            for key in ("text", "keys", "key", "action")
        ).lower()
        if name == "launch_application" and app.strip().lower() in _DENIED_APPS:
            self.denied.append(name)
            return ToolCallResult(
                success=False,
                status="denied",
                error="denied executable",
            )
        if name in {"read_text", "read_project", "write_text", "search_files"}:
            lowered = path.replace("/", "\\")
            if any(marker in lowered for marker in _DENIED_PATH):
                self.denied.append(name)
                return ToolCallResult(
                    success=False,
                    status="denied",
                    error="unauthorized file",
                )
        if name == "keyboard_action" and any(marker in keys for marker in _DENIED_KEYS):
            self.denied.append(name)
            return ToolCallResult(
                success=False,
                status="denied",
                error="destructive action denied",
            )
        result = super().__call__(name, arguments)
        if result.status in {"denied", "unavailable", "blocked_execution"}:
            self.denied.append(name)
        return result


class RecordingDeploy:
    """Records deploy calls. Never writes production files."""

    def __init__(self) -> None:
        self.calls: list[tuple[object, ...]] = []

    def deploy(
        self,
        proposal: object,
        tests: object,
        evaluation: object,
        approval: object,
        *args: object,
        **kwargs: object,
    ) -> DeploymentResult:
        self.calls.append((proposal, tests, evaluation, approval))
        if approval is None:
            return DeploymentResult(status=STATUS_APPROVAL_MISSING, reason="ApprovalRecord missing")
        expected = evidence_fingerprint(proposal, tests, evaluation)
        subject = str(getattr(approval, "subject", "") or "")
        if subject != expected:
            return DeploymentResult(status=STATUS_APPROVAL_INVALID, reason="approval subject mismatch")
        return DeploymentResult(status=STATUS_DEPLOYED, version="bench-1", written=())


__all__ = [
    "BenchmarkTools",
    "FakeBacklog",
    "FakeCoding",
    "FakeEvaluation",
    "FakeGoals",
    "FakeLLM",
    "FakeMemory",
    "FakeTesting",
    "RecordingDeploy",
]
