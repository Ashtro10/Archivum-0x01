"""Controller-owned Git version history (B-033, ADR-0032).

Records successful deployments. Restores a known commit after a matching
human ApprovalRecord. Not authorization. Not a Tool. Not VersionManager.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from astra.deployment.contracts import ApprovalRecord
from astra.deployment.fingerprint import git_restore_fingerprint
from astra.deployment.gitrun import GitRunner, parse_commit_id
from astra.deployment.paths import canonical, denied_reason, is_link, is_under, resolve_dest
from astra.logging import get_logger
from astra.security.contracts import Verdict

_LOGGER = get_logger(__name__)

GIT_UNAVAILABLE = "git_unavailable"
GIT_NOT_REPOSITORY = "git_not_repository"
GIT_SECURITY_DENIED = "git_security_denied"
GIT_OPERATION_FAILED = "git_operation_failed"
HISTORY_RECORDED = "history_recorded"
HISTORY_NOT_RECORDED = "history_not_recorded"
RESTORE_DENIED = "restore_denied"
RESTORE_FAILED = "restore_failed"
RESTORE_COMPLETED = "restored"

_COMMIT_MESSAGE_PREFIX = "Astra deployment "


@dataclass(frozen=True, slots=True)
class GitRecord:
    """Smallest additive Git history DTO. Not a second DeploymentResult."""

    status: str
    version_id: str = ""
    commit_id: str = ""
    repository_root: str = ""
    recorded_at: datetime | None = None
    parent_commit: str = ""
    reason: str = ""

    @property
    def recorded(self) -> bool:
        return self.status == HISTORY_RECORDED

    @property
    def restored(self) -> bool:
        return self.status == RESTORE_COMPLETED


class GitVersionStore:
    """Git history/recovery for the configured project_root only."""

    def __init__(
        self,
        *,
        security: Any,
        project_root: Path | str,
        scratch_root: Path | str | None = None,
        executable: Path | str | None = None,
        init_if_missing: bool = False,
        author_name: str = "Astra",
        author_email: str = "astra@localhost",
        timeout_seconds: float = 30.0,
    ) -> None:
        self._security = security
        self._project = canonical(project_root)
        self._scratch = canonical(scratch_root) if scratch_root is not None else None
        self._init_if_missing = bool(init_if_missing)
        self._runner = GitRunner(
            project_root=project_root,
            executable=executable,
            scratch_root=scratch_root,
            author_name=author_name,
            author_email=author_email,
            timeout_seconds=timeout_seconds,
        )

    def repository_status(self) -> GitRecord:
        """Read-only status. Does not mutate the repository."""
        blocked = self._boundary_failure(mutating=False)
        if blocked is not None:
            return blocked
        result = self._runner.status()
        if not result.ok:
            if not self._git_dir_present():
                return self._record(GIT_NOT_REPOSITORY, reason="git repository absent")
            return self._record(GIT_OPERATION_FAILED, reason=result.reason or "git status failed")
        return self._record(HISTORY_NOT_RECORDED, reason="repository present")

    def history(self, limit: int = 20) -> tuple[GitRecord, ...]:
        """Inspect recorded Astra deployment commits."""
        blocked = self._boundary_failure(mutating=False)
        if blocked is not None:
            return (blocked,)
        result = self._runner.log(limit=limit)
        if not result.ok:
            if not self._git_dir_present():
                return (self._record(GIT_NOT_REPOSITORY, reason="git repository absent"),)
            return (self._record(GIT_OPERATION_FAILED, reason=result.reason or "git log failed"),)
        records: list[GitRecord] = []
        for line in result.stdout.splitlines():
            parsed = _parse_log_line(line)
            if parsed is None:
                continue
            version_id, commit_id, parent, stamp = parsed
            records.append(
                self._record(
                    HISTORY_RECORDED,
                    version_id=version_id,
                    commit_id=commit_id,
                    parent_commit=parent,
                    recorded_at=stamp,
                )
            )
        return tuple(records)

    def record_deployment(
        self,
        *,
        version_id: str,
        written: tuple[str, ...],
        approval: ApprovalRecord | None = None,
    ) -> GitRecord:
        """Record repository state after a successful deploy. Not authorization."""
        del approval  # history recording uses the already-approved deploy; not a new gate
        blocked = self._boundary_failure(mutating=True)
        if blocked is not None:
            return blocked
        if not self._git_dir_present():
            if not self._init_if_missing:
                return self._record(GIT_NOT_REPOSITORY, reason="git repository absent")
            policy = self._require_approval_policy("git init")
            if policy is not None:
                return policy
            inited = self._runner.init_repository()
            if not inited.ok:
                return self._record(
                    GIT_OPERATION_FAILED,
                    version_id=version_id,
                    reason=inited.reason or "git init failed",
                )
        policy = self._require_approval_policy(f"git commit {version_id}")
        if policy is not None:
            return policy
        added = self._runner.add(written)
        if not added.ok:
            return self._record(
                GIT_OPERATION_FAILED,
                version_id=version_id,
                reason=added.reason or "git add failed",
            )
        committed = self._runner.commit(version_id)
        if not committed.ok:
            return self._record(
                GIT_OPERATION_FAILED,
                version_id=version_id,
                reason=committed.reason or "git commit failed",
            )
        head = self._runner.rev_parse("HEAD")
        commit_id = parse_commit_id(head.stdout)
        if not head.ok or not commit_id:
            return self._record(
                GIT_OPERATION_FAILED,
                version_id=version_id,
                reason="git commit id missing or invalid",
            )
        parent = ""
        parent_result = self._runner.rev_parse("HEAD^")
        if parent_result.ok:
            parent = parse_commit_id(parent_result.stdout)
        return self._record(
            HISTORY_RECORDED,
            version_id=version_id,
            commit_id=commit_id,
            parent_commit=parent,
            recorded_at=datetime.now(timezone.utc),
        )

    def restore(
        self,
        commit_id: str,
        approval: ApprovalRecord,
        *,
        version_id: str = "",
    ) -> GitRecord:
        """Restore a known commit after a matching human ApprovalRecord."""
        digest = str(commit_id or "").strip()
        ident = str(version_id or "").strip()
        if approval is None:
            return self._record(RESTORE_DENIED, reason="ApprovalRecord missing")
        if not isinstance(approval, ApprovalRecord):
            return self._record(RESTORE_DENIED, reason="ApprovalRecord malformed")
        approved_by = str(getattr(approval, "approved_by", "") or "").strip()
        subject = str(getattr(approval, "subject", "") or "").strip()
        if not approved_by or not subject:
            return self._record(RESTORE_DENIED, reason="ApprovalRecord incomplete")
        expected = git_restore_fingerprint(digest, ident)
        if subject != expected:
            return self._record(
                RESTORE_DENIED,
                commit_id=digest,
                version_id=ident,
                reason="ApprovalRecord does not match this restore target",
            )
        blocked = self._boundary_failure(mutating=True)
        if blocked is not None:
            return GitRecord(
                status=RESTORE_DENIED if blocked.status == GIT_SECURITY_DENIED else blocked.status,
                version_id=ident,
                commit_id="",
                repository_root=blocked.repository_root,
                reason=blocked.reason,
            )
        policy = self._require_approval_policy(f"git restore {digest}")
        if policy is not None:
            return GitRecord(
                status=RESTORE_DENIED,
                version_id=ident,
                commit_id=digest,
                repository_root=policy.repository_root,
                reason=policy.reason,
            )
        typed = self._runner.cat_file_type(digest)
        kind = (typed.stdout or "").splitlines()[0].strip() if typed.ok else ""
        if not typed.ok or kind != "commit":
            return self._record(
                RESTORE_FAILED,
                version_id=ident,
                reason=typed.reason or "commit object missing or invalid",
            )
        listed = self._runner.ls_tree(digest)
        if not listed.ok:
            return self._record(
                RESTORE_FAILED,
                version_id=ident,
                commit_id=digest,
                reason=listed.reason or "git ls-tree failed",
            )
        paths = _safe_tree_paths(listed.stdout, self._project)
        if not paths:
            return self._record(
                RESTORE_FAILED,
                version_id=ident,
                commit_id=digest,
                reason="no restorable paths in commit",
            )
        checked_out = self._runner.checkout_paths(digest, paths)
        if not checked_out.ok:
            return self._record(
                RESTORE_FAILED,
                version_id=ident,
                commit_id=digest,
                reason=checked_out.reason or "git checkout failed",
            )
        verified = self._runner.diff_quiet(digest, paths)
        if not verified.ok:
            return self._record(
                RESTORE_FAILED,
                version_id=ident,
                commit_id=digest,
                reason=verified.reason or "restore verification failed",
            )
        return self._record(
            RESTORE_COMPLETED,
            version_id=ident,
            commit_id=digest,
            recorded_at=datetime.now(timezone.utc),
            reason="restored",
        )

    def _boundary_failure(self, *, mutating: bool) -> GitRecord | None:
        project = self._project
        if project is None or not project.is_dir():
            return self._record(GIT_SECURITY_DENIED, reason="repository path unresolvable")
        if is_link(project):
            return self._record(
                GIT_SECURITY_DENIED, reason="repository path is a symlink or junction"
            )
        if self._scratch is not None and (
            project == self._scratch or is_under(project, self._scratch)
        ):
            return self._record(GIT_SECURITY_DENIED, reason="scratch cannot be a Git repository")
        git_dir = project / ".git"
        if git_dir.exists() or git_dir.is_symlink():
            if git_dir.is_file() or not git_dir.is_dir():
                return self._record(GIT_SECURITY_DENIED, reason="fake .git path rejected")
            if is_link(git_dir):
                return self._record(GIT_SECURITY_DENIED, reason=".git is a symlink or junction")
        if not self._runner.configured:
            return self._record(GIT_UNAVAILABLE, reason="git executable not configured")
        if mutating:
            return None
        return None

    def _require_approval_policy(self, description: str) -> GitRecord | None:
        check = getattr(self._security, "check_permanent_change", None)
        if check is None:
            return self._record(
                GIT_SECURITY_DENIED, reason="security has no check_permanent_change"
            )
        decision = check(description)
        verdict = getattr(decision, "verdict", None)
        if verdict is Verdict.DENY:
            return self._record(
                GIT_SECURITY_DENIED,
                reason=str(getattr(decision, "reason", "permanent change denied") or "denied"),
            )
        if verdict is not Verdict.REQUIRES_APPROVAL:
            return self._record(
                GIT_SECURITY_DENIED,
                reason="permanent change is not REQUIRES_APPROVAL; fail closed; never ALLOW",
            )
        return None

    def _git_dir_present(self) -> bool:
        project = self._project
        if project is None:
            return False
        git_dir = project / ".git"
        return git_dir.is_dir() and not is_link(git_dir)

    def _record(
        self,
        status: str,
        *,
        version_id: str = "",
        commit_id: str = "",
        parent_commit: str = "",
        recorded_at: datetime | None = None,
        reason: str = "",
    ) -> GitRecord:
        root = str(self._project) if self._project is not None else ""
        return GitRecord(
            status=status,
            version_id=version_id,
            commit_id=commit_id,
            repository_root=root,
            recorded_at=recorded_at,
            parent_commit=parent_commit,
            reason=reason or status,
        )


def _parse_log_line(
    line: str,
) -> tuple[str, str, str, datetime | None] | None:
    parts = line.split("\t")
    if len(parts) < 3:
        return None
    commit_id = parse_commit_id(parts[0])
    if not commit_id:
        return None
    parents = parts[1].split()
    parent = parse_commit_id(parents[0]) if parents else ""
    subject = parts[2].strip()
    if not subject.startswith(_COMMIT_MESSAGE_PREFIX):
        return None
    version_id = subject[len(_COMMIT_MESSAGE_PREFIX) :].strip()
    stamp = None
    if len(parts) >= 4 and parts[3].strip():
        try:
            stamp = datetime.fromisoformat(parts[3].strip())
        except ValueError:
            stamp = None
    return version_id, commit_id, parent, stamp


def _safe_tree_paths(stdout: str, project: Path | None) -> tuple[str, ...]:
    if project is None:
        return ()
    paths: list[str] = []
    for raw in stdout.splitlines():
        text = raw.strip()
        if not text or text.startswith("-"):
            continue
        dest, rel, err = resolve_dest(text, project)
        if err or dest is None or rel is None:
            continue
        if denied_reason(rel):
            continue
        paths.append(rel.as_posix())
    return tuple(paths)


__all__ = [
    "GIT_NOT_REPOSITORY",
    "GIT_OPERATION_FAILED",
    "GIT_SECURITY_DENIED",
    "GIT_UNAVAILABLE",
    "HISTORY_NOT_RECORDED",
    "HISTORY_RECORDED",
    "RESTORE_COMPLETED",
    "RESTORE_DENIED",
    "RESTORE_FAILED",
    "GitRecord",
    "GitVersionStore",
]
