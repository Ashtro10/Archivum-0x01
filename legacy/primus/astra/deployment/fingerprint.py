"""Deterministic evidence fingerprint for approval binding (B-027, ADR-0026).

Not object identity. Not a timestamp. SHA-256 over canonical proposal bytes
and TestReport / EvaluationReport outcome fields.
"""

from __future__ import annotations

import hashlib
import os
from typing import Any


def evidence_fingerprint(proposal: Any, tests: Any, evaluation: Any) -> str:
    """Return hex digest binding approval to this exact proposal and evidence."""
    hasher = hashlib.sha256()
    hasher.update(b"astra-deploy-v1\n")
    hasher.update(_proposal_bytes(proposal))
    hasher.update(b"\n")
    hasher.update(_tests_bytes(tests))
    hasher.update(b"\n")
    hasher.update(_eval_bytes(evaluation))
    return hasher.hexdigest()


def _proposal_bytes(proposal: Any) -> bytes:
    if proposal is None:
        return b"proposal:none"
    objective = str(getattr(proposal, "objective", "") or "")
    chunks = [b"objective:", objective.encode("utf-8"), b"\n"]
    changes = list(getattr(proposal, "changes", ()) or ())
    changes.sort(
        key=lambda item: os.path.normcase(
            str(getattr(item, "path", "") or "")
        ).replace("\\", "/")
    )
    for change in changes:
        rel = os.path.normcase(str(getattr(change, "path", "") or "")).replace(
            "\\", "/"
        )
        content = getattr(change, "new_content", "") or ""
        chunks.append(rel.encode("utf-8"))
        chunks.append(b"\0")
        chunks.append(content.encode("utf-8"))
        chunks.append(b"\n")
    return b"".join(chunks)


def _tests_bytes(tests: Any) -> bytes:
    if tests is None:
        return b"tests:none"
    passed = int(bool(getattr(tests, "passed", False)))
    return (
        f"passed={passed} run={int(getattr(tests, 'tests_run', 0) or 0)} "
        f"fail={int(getattr(tests, 'tests_failed', 0) or 0)} "
        f"err={int(getattr(tests, 'tests_errors', 0) or 0)} "
        f"skip={int(getattr(tests, 'tests_skipped', 0) or 0)} "
        f"inc={int(bool(getattr(tests, 'incomplete', False)))} "
        f"to={int(bool(getattr(tests, 'timed_out', False)))} "
        f"sp={int(bool(getattr(tests, 'spawn_failed', False)))} "
        f"ex={getattr(tests, 'exit_code', None)}"
    ).encode("utf-8")


def _eval_bytes(evaluation: Any) -> bytes:
    if evaluation is None:
        return b"eval:none"
    return (
        f"eval={int(bool(getattr(evaluation, 'evaluation_passed', False)))} "
        f"tpass={int(bool(getattr(evaluation, 'tests_passed', False)))} "
        f"inc={int(bool(getattr(evaluation, 'incomplete', False)))}"
    ).encode("utf-8")


def rollback_fingerprint(version_id: str) -> str:
    """Bind an ApprovalRecord.subject to one version id for rollback."""
    hasher = hashlib.sha256()
    hasher.update(b"astra-rollback-v1\n")
    hasher.update(str(version_id or "").encode("utf-8"))
    return hasher.hexdigest()


def git_restore_fingerprint(commit_id: str, version_id: str = "") -> str:
    """Bind an ApprovalRecord.subject to one Git restore target."""
    hasher = hashlib.sha256()
    hasher.update(b"astra-git-restore-v1\n")
    hasher.update(str(commit_id or "").encode("utf-8"))
    hasher.update(b"\n")
    hasher.update(str(version_id or "").encode("utf-8"))
    return hasher.hexdigest()


__all__ = ["evidence_fingerprint", "git_restore_fingerprint", "rollback_fingerprint"]
