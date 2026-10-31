"""Security Agent contract.

Guards six boundaries: outbound network access, filesystem writes outside
Astra's own directories, filesystem reads outside the authorized project
root, temporary overlay writes under a dedicated scratch root, process
execution requests, and anything that would make a permanent change without
approval.

Decisions are explicit values rather than exceptions so that a refusal can be
logged, explained to the user, and audited later.

Interfaces only: no behaviour is implemented here.
"""

from __future__ import annotations

from abc import abstractmethod
from dataclasses import dataclass
from enum import Enum
from pathlib import Path

from astra.core.contracts import Agent


class Verdict(Enum):
    """Outcome of a policy check."""

    ALLOW = "allow"
    DENY = "deny"
    REQUIRES_APPROVAL = "requires_approval"


@dataclass(frozen=True, slots=True)
class PolicyDecision:
    """The result of a security check.

    Attributes:
        verdict: What the agent decided.
        reason: Why. Required for every verdict, including ``ALLOW``, so that
            behaviour is explainable rather than hidden.
    """

    verdict: Verdict
    reason: str

    @property
    def allowed(self) -> bool:
        """True only for an unconditional allow."""
        return self.verdict is Verdict.ALLOW


@dataclass(frozen=True, slots=True)
class ProcessSpec:
    """Requested process. Policy input only. Does not spawn.

    ``argv`` is an argument vector, never a shell string. ``env`` is explicit
    pairs; an empty tuple does not mean inherit the parent environment.
    ``timeout_seconds`` of ``0`` or less is invalid (fail closed).
    """

    argv: tuple[str, ...] = ()
    cwd: Path | None = None
    env: tuple[tuple[str, str], ...] = ()
    timeout_seconds: float = 0.0


class SecurityAgent(Agent):
    """Evaluates proposed actions against project policy."""

    name = "security"

    @abstractmethod
    def check_network_access(self, url: str) -> PolicyDecision:
        """Decide whether an outbound request may proceed.

        Loopback is network access, not an inference transport (ADR-0004,
        ADR-0014 D5). Implementations must deny it. Default for all other
        destinations is deny; ``allow_outbound_network=True`` must not become
        unconditional ALLOW.
        """
        raise NotImplementedError

    @abstractmethod
    def check_file_write(self, path: Path) -> PolicyDecision:
        """Decide whether a write to ``path`` is permitted."""
        raise NotImplementedError

    @abstractmethod
    def check_file_read(self, path: Path) -> PolicyDecision:
        """Decide whether a read of ``path`` is permitted.

        Implementations must return ALLOW or DENY only. Reads are not
        permanent changes, so REQUIRES_APPROVAL is not a valid verdict.
        """
        raise NotImplementedError

    @abstractmethod
    def check_temporary_write(self, path: Path) -> PolicyDecision:
        """Decide whether a temporary overlay write to ``path`` is permitted.

        Implementations must return ALLOW or DENY only. Overlay writes are
        not permanent changes, so REQUIRES_APPROVAL is not a valid verdict.
        Scratch is not a ``check_file_write`` root.
        """
        raise NotImplementedError

    @abstractmethod
    def check_process_execution(self, spec: ProcessSpec) -> PolicyDecision:
        """Decide whether a process request may proceed.

        Implementations must return ALLOW or DENY only. Process execution is
        not a permanent change, so REQUIRES_APPROVAL is not a valid verdict.
        Production defaults must never ALLOW. This check authorizes the
        request; it does not jail the child.
        """
        raise NotImplementedError

    @abstractmethod
    def check_permanent_change(self, description: str) -> PolicyDecision:
        """Decide whether a permanent change may proceed.

        Implementations must never return ``ALLOW`` here: permanent changes
        are the human's decision, so the only correct answers are
        ``REQUIRES_APPROVAL`` or ``DENY``.
        """
        raise NotImplementedError


__all__ = ["Verdict", "PolicyDecision", "ProcessSpec", "SecurityAgent"]
