"""Testing package.

``ProposalWorkspace`` materializes overlays (B-021). ``ProcessRunner`` is
the sole testing spawn boundary (B-024). ``CopiedSubsetWorkspace`` copies an
allowlisted subset (B-023). ``LocalTestingAgent.test_proposal`` overlays
then tests (B-025). ``run_suite`` tests an unchanged copy (B-030).
"""

from __future__ import annotations

from astra.testing.agent import LocalTestingAgent
from astra.testing.contracts import TestingAgent, TestOutcome, TestReport
from astra.testing.runner import ProcessResult, ProcessRunner, ProcessStatus
from astra.testing.subset import CopiedSubsetWorkspace, SubsetResult
from astra.testing.workspace import MaterializeResult, ProposalWorkspace

__all__ = [
    "TestOutcome",
    "TestReport",
    "LocalTestingAgent",
    "TestingAgent",
    "MaterializeResult",
    "ProposalWorkspace",
    "ProcessResult",
    "ProcessRunner",
    "ProcessStatus",
    "CopiedSubsetWorkspace",
    "SubsetResult",
]
