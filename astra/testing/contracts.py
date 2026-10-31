"""Testing Agent contract.

Testing is a mandatory lifecycle stage. A proposal that has not been tested
cannot proceed to evaluation.

Interfaces only: no behaviour is implemented here.
"""

from __future__ import annotations

from abc import abstractmethod
from dataclasses import dataclass, field

from astra.coding.contracts import ChangeProposal
from astra.core.contracts import Agent


@dataclass(frozen=True, slots=True)
class TestOutcome:
    """Result of a single test or a parsed unittest detail line."""

    test_id: str
    passed: bool
    message: str = ""
    duration_seconds: float = 0.0
    kind: str = "passed"


@dataclass(frozen=True, slots=True)
class TestReport:
    """Aggregate result of a test run.

    Counts come from unittest output. Non-zero ``exit_code`` is not a pass.
    An empty, truncated, timed-out, or unparseable run is not a pass.
    """

    outcomes: tuple[TestOutcome, ...] = field(default=())
    command: str = ""
    tests_run: int = 0
    tests_passed: int = 0
    tests_failed: int = 0
    tests_errors: int = 0
    tests_skipped: int = 0
    exit_code: int | None = None
    timed_out: bool = False
    spawn_failed: bool = False
    incomplete: bool = False
    process_reason: str = ""

    @property
    def passed(self) -> bool:
        """True when unittest reported a non-empty run with no failures/errors.

        An empty run is not a pass: nothing was demonstrated. Timeout, spawn
        failure, truncation, unparseable output, and non-zero exit are not a pass.
        """
        if self.incomplete or self.timed_out or self.spawn_failed:
            return False
        if self.exit_code not in (0, None):
            return False
        if self.tests_run <= 0:
            return False
        return self.tests_failed == 0 and self.tests_errors == 0

    @property
    def failures(self) -> tuple[TestOutcome, ...]:
        """Outcomes that did not pass."""
        return tuple(o for o in self.outcomes if not o.passed)


class TestingAgent(Agent):
    """Runs tests and reports results without interpreting them."""

    name = "testing"

    @abstractmethod
    def run_suite(self, path: str | None = None) -> TestReport:
        """Execute the test suite, or a subset of it.

        Implementations must run locally and offline.
        """
        raise NotImplementedError

    @abstractmethod
    def test_proposal(self, proposal: ChangeProposal) -> TestReport:
        """Test a proposed change in isolation.

        The working tree must be left unmodified; a proposal is not applied in
        order to be tested.
        """
        raise NotImplementedError


__all__ = ["TestOutcome", "TestReport", "TestingAgent"]
