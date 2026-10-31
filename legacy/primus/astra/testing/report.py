"""Parse unittest runner output into a TestReport (B-025, ADR-0024).

Deterministic. Does not spawn. Does not invent pass counts.
"""

from __future__ import annotations

import re

from astra.testing.contracts import TestOutcome, TestReport

_RAN = re.compile(r"^Ran (\d+) tests? in ", re.MULTILINE)
_OK = re.compile(r"^OK(?:\s+\(([^)]*)\))?\s*$", re.MULTILINE)
_FAILED = re.compile(r"^FAILED(?:\s+\(([^)]*)\))?\s*$", re.MULTILINE)
_DETAIL = re.compile(r"^(FAIL|ERROR):\s+(\S+.*)$", re.MULTILINE)
_COUNT = re.compile(r"(failures|errors|skipped|expected failures|unexpected successes)=(\d+)")


def parse_unittest_output(
    *,
    stdout: str,
    stderr: str = "",
    command: str = "",
    exit_code: int | None = None,
    timed_out: bool = False,
    spawn_failed: bool = False,
    incomplete: bool = False,
    process_reason: str = "",
) -> TestReport:
    """Build a TestReport from captured unittest text.

    Truncation, timeout, spawn failure, and missing summaries are incomplete
    and not passing. Non-zero exit code is stored and is not a pass.
    """
    text = f"{stdout}\n{stderr}"
    ran_match = _RAN.search(text)
    tests_run = int(ran_match.group(1)) if ran_match else 0
    failed_match = _FAILED.search(text)
    ok_match = _OK.search(text)
    paren = ""
    if failed_match is not None:
        paren = failed_match.group(1) or ""
    elif ok_match is not None:
        paren = ok_match.group(1) or ""
    counts = {key: int(val) for key, val in _COUNT.findall(paren)}
    tests_failed = counts.get("failures", 0)
    tests_errors = counts.get("errors", 0)
    tests_skipped = counts.get("skipped", 0)
    if ran_match is None and not timed_out and not spawn_failed and not incomplete:
        incomplete = True
        if not process_reason:
            process_reason = "unittest output missing Ran summary; unparseable"
    tests_passed = max(
        0, tests_run - tests_failed - tests_errors - tests_skipped
    )
    outcomes: list[TestOutcome] = []
    for kind, ident in _DETAIL.findall(text):
        outcomes.append(
            TestOutcome(
                test_id=ident.strip(),
                passed=False,
                message=kind,
                kind="error" if kind == "ERROR" else "failed",
            )
        )
    return TestReport(
        outcomes=tuple(outcomes),
        command=command,
        tests_run=tests_run,
        tests_passed=tests_passed,
        tests_failed=tests_failed,
        tests_errors=tests_errors,
        tests_skipped=tests_skipped,
        exit_code=exit_code,
        timed_out=timed_out,
        spawn_failed=spawn_failed,
        incomplete=incomplete,
        process_reason=process_reason,
    )


__all__ = ["parse_unittest_output"]
