# Session Handoff

Updated at the end of each significant session. Read the latest entry first.

---

## Update: 2026-08-16 — Persistent memory recovery

- **Priority:** real `Primus.exe` remembers the user after close. Not a new agent.
- **Store:** one Memory Engine SQLite file. Source: `data/astra.db`. Exe: `dist_windows/data/astra.db`.
- **Docs:** `docs/CURRENT.md`, `docs/memory.md`. Historical ADRs stay in `docs/adr/`.
- **Do not start:** personality redesign, UI redesign, B-042, new tools.

### How to resume

1. Read `docs/CURRENT.md` and `docs/memory.md`.
2. `python -m unittest tests.test_persistent_memory -q`

---

## Update: 2026-08-16 — Runtime intelligence integration

- **Phase:** Repair of the existing B-039 talk path. Not B-042. Do not start B-042.
- **ADR:** ADR-0041 accepted. ADR-0014–0040 not rewritten.
- **Path:** UI → ConversationPort → Orchestrator → composite plan (memory / knowledge / research / planning / goals / tools as justified) → structured ChatML context → LLM → persistence.
- **Personality:** `[personality]` settings. Independent of the GGUF.
- **Probes:** `--probe` and `--probe-research` unchanged. Added `--probe-runtime-write` / `--probe-runtime-read` for process-restart identity.
- **Security:** Schema 6. Allowlist `{read_text, read_project}`. SSRF and ApprovalRecord unchanged.
- **Frozen:** `dist_windows/Primus.exe` rebuilt. `--probe` still works. `--probe-runtime-write` / `--probe-runtime-read` prove identity across process restart.
- **Tests:** `tests/test_runtime_intelligence.py`. Full suite: **1353 tests, 0 failures** (9 skipped).

### How to resume

1. Read ADR-0041.
2. `python -m unittest discover -s tests -t . -q`
3. Do not start B-042. Do not add a web Tool. Do not redesign the UI.

---

## Update: 2026-08-15 — B-041 controlled external research

- **Phase:** B-041 complete. B-039 orchestrator remains the talk boundary. Do not start B-042.
- **ADR:** ADR-0040 accepted. ADR-0039 already recorded B-040; it was not reused. ADR-0014–0039 not rewritten.
- **Path:** UI → ConversationPort → Orchestrator → Research Agent → `HttpResearchSource` → Security → evidence → LLM → ConversationReply.
- **Security:** deny-by-default `research.allow_external`. SSRF on URL, redirect, resolved IP. `check_network_access` unchanged (tools still cannot fetch).
- **Not claimed:** autonomy, unrestricted internet, a browser, a network Tool.
- **Frozen:** rebuild `dist_windows/Primus.exe`. `Primus.exe --probe` still works. Optional `--probe-research` hits `https://example.com`.
- **Tests:** `tests/test_b041_research.py`. Full suite: **1340 tests, 0 failures** (9 skipped).

### How to resume

1. Read ADR-0040.
2. `python -m unittest discover -s tests -t . -q`
3. Do not start B-042. Do not add a web Tool.

---

## Update: 2026-08-15 — B-040 runtime capability completion

- **Phase:** B-040 complete. B-039 orchestrator unchanged as the talk boundary.
- **ADR:** ADR-0039 accepted. ADR-0014–0038 not rewritten.
- **Frozen:** rebuild `dist_windows/Primus.exe`. Verify with `Primus.exe --probe`.
- **Research:** local corpus + knowledge + memory. No HTTP. No web pretend.
- **Not claimed:** autonomy, internet research, “everything integrated.”
- **Tests:** `tests/test_b040_runtime.py` including frozen `--probe`. Full suite: **1299 tests, 0 failures** (9 skipped).

### How to resume

1. Read ADR-0039.
2. `python -m unittest discover -s tests -t . -q`
3. Do not start B-041 without approval.

---

## Update: 2026-08-15 — B-039 production conversation orchestrator

- **Phase:** B-039 complete. Backend remains closed at B-038. Talk path is no longer a direct LLM wrapper.
- **ADR:** ADR-0038 accepted. ADR-0037 (UI identity) not rewritten. ADR-0014–0036 not rewritten.
- **Entrypoint:** `ConversationOrchestrator.from_controller` in `astra.boot.launch`.
- **Path:** UI `_send` → `ask` → `process` → existing memory/knowledge/research/planning/tools/goals when selected → `llm.generate` → persist session + justified durable facts.
- **Not claimed:** autonomy, learning-as-magic, “everything integrated.”
- **Tests:** `tests/test_b039_orchestration.py` plus full suite: **1280 tests, 0 failures** (9 skipped: symlink privilege).

### How to resume

1. Read ADR-0038 and `docs/runtime-reality.md`.
2. `python -m unittest discover -s tests -t . -q`
3. Do not route every chat turn through research. Do not invoke testing/deploy from conversation.

---

## Update: 2026-08-15 — Primus UI + Windows packaging

- **Phase:** B-001–B-038 backend frozen. UI-001 conversation-first Primus window. Product name Primus. Internal package `astra`.
- **Launch:** `python -m astra` (controller + conversation UI)
  or `python -m astra --demo` or `python -m astra --smoke`
  or `Primus.exe` after `python scripts/build_windows.py`.
- **ADR:** ADR-0037 accepted. ADR-0014–0036 files not edited. Constitution not edited.
- **Tests after this change:** **1243 tests, 0 failures** (9 skipped: symlink privilege).
- **Primus.exe:** built and smoke-tested (`--demo --smoke`, exit 0).

### What landed

- Conversation-first primary view; System View secondary; Settings discreet
- Primus Core (layered lattice, not an orb), driven by visual state + optional real audio
- Refresh no longer rebuilds conversation (old 5s full-layout delete was the shift bug)
- `astra.product.PRODUCT_NAME = "Primus"`
- Frozen `project_root()` is the directory next to Primus.exe
- PyInstaller build script; models/.env/data not bundled

### Residual UI limits

No backend TTS/STT stream. Listen is honest about that. Conversation live path uses the research agent, not a dedicated chat model. Reduced motion via `PRIMUS_REDUCED_MOTION=1`.

### How to resume

1. Read ADR-0037 and `docs/ui.md`.
2. `python -m unittest discover -s tests -t . -q`
3. Do **not** invent B-039 backend milestones.

---

## Update: 2026-08-15 — B-038 done (production-readiness and closure)

- **Phase:** B-001–B-038 complete. Architecture closed at this stage.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests before this milestone:** **1213 tests, 0 failures** (9 skipped: symlink privilege on this host).
- **Tests after this milestone:** **1218 tests, 0 failures** (9 skipped: symlink privilege on this host).
- **ADR:** No ADR-0037. ADR-0036 remains the state-of-architecture record. ADR-0014–0036 files not edited.
- **Next milestone:** none. Do **not** invent B-039.

### What landed

- `TestReport.passed` requires `tests_run > 0` (crafted outcomes cannot fake a pass)
- Deploy and evaluate reject zero-run evidence; live project bytes unchanged
- Milestone B-038 behavioral readiness check
- Schema stays **6**. Allowlist `{read_text, read_project}`

### Residual limits

Not an OS sandbox. Rollback not atomic. Snapshot persist failure after a write
is reported as deployment failure with bytes left in place. Git failure after
write does not auto-rollback. Child processes may use OS-permitted host
resources. No child network jail. Grandchildren may survive.

### Invariants kept

Dual-key. GuardedTool. Allowlist `{read_text, read_project}`. Coding never writes. ProcessRunner sole testing spawn in `astra.testing`. Git spawn only in `astra.deployment.gitrun`. No third tool. No auto-approve. `check_permanent_change` never ALLOW. No OS sandbox claim. Git is not authorization. Evaluation is not authorization. VersionManager is not authorization. UI render-only.

### How to resume

1. Read ADR-0036, then this entry, then `docs/architecture.md` current-architecture section.
2. `python -m unittest discover -s tests -t . -q`
3. Do **not** invent a next milestone unless the user asks.

---

## Update: 2026-08-15 — B-037 done (architecture and documentation consistency)

- **Phase:** B-001–B-037 complete. Do **not** start B-038, a third tool, auto-approve UI, or agent loops.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests before this milestone:** **1211 tests, 0 failures** (9 skipped: symlink privilege on this host).
- **Tests after this milestone:** **1213 tests, 0 failures** (9 skipped: symlink privilege on this host).
- **ADR:** ADR-0036 accepted. State-of-architecture record. ADR-0014–0035 files not edited.
- **Next milestone:** B-038 final acceptance — **NOT STARTED**.

### What landed

- `docs/adr/ADR-0036-final-architecture-state.md`
- Current-state docs reconciled with the live pipeline
- Schema stays **6**. Allowlist `{read_text, read_project}`

### Residual limits

Not an OS sandbox. Rollback not atomic. Snapshot persist failure after a write
is reported as deployment failure with bytes left in place. Git failure after
write does not auto-rollback. Child processes may use OS-permitted host
resources. No child network jail. Grandchildren may survive.

### Invariants kept

Dual-key. GuardedTool. Allowlist `{read_text, read_project}`. Coding never writes. ProcessRunner sole testing spawn in `astra.testing`. Git spawn only in `astra.deployment.gitrun`. No third tool. No auto-approve. `check_permanent_change` never ALLOW. No OS sandbox claim. Git is not authorization. Evaluation is not authorization. VersionManager is not authorization. UI render-only.

### How to resume

1. Read ADR-0036, then this entry, then `docs/architecture.md` current-architecture section.
2. `python -m unittest discover -s tests -t . -q`
3. Do **not** start B-038 unless the user asks.

---

## Update: 2026-08-15 — B-036 done (end-to-end integration and recovery)

- **Phase:** B-001–B-036 complete. Do **not** start B-037, a third tool, auto-approve UI, or agent loops.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **1211 tests, 0 failures** (9 skipped: symlink privilege on this host).
- **ADR:** ADR-0035 accepted and implemented. ADR-0014–0034 files not edited.
- **Next milestone:** not started. B-037 was **not** started.

### What landed

- `docs/adr/ADR-0035-end-to-end-integration-and-recovery.md`
- `tests/test_integration.py`: controller-owned success, stop, rollback, Git, cleanup
- Schema stays **6**. Allowlist `{read_text, read_project}`

### Residual integration limits

Not an OS sandbox. Rollback not atomic. Snapshot persist failure after a write
is reported as deployment failure with bytes left in place. Git failure after
write does not auto-rollback.

### Invariants kept

Dual-key. GuardedTool. Allowlist `{read_text, read_project}`. Coding never writes. ProcessRunner sole spawn in `astra.testing`. Git spawn only in `astra.deployment.gitrun`. No third tool. No auto-approve. `check_permanent_change` never ALLOW. No OS sandbox claim. Git is not authorization.

### How to resume

1. Read ADR-0035, then this entry.
2. `python -m unittest discover -s tests -t . -q`
3. Do **not** start B-037 unless the user asks.

---

## Update: 2026-08-15 — B-035 done (security and hardening audit)

- **Phase:** B-001–B-035 complete. Do **not** start B-036, a third tool, auto-approve UI, or agent loops.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **1189 tests, 0 failures** (9 skipped: symlink privilege on this host).
- **ADR:** ADR-0034 accepted and implemented. ADR-0014–0033 files not edited.
- **Next milestone:** not started. B-036 was **not** started.

### What landed

- `docs/adr/ADR-0034-security-hardening-audit.md`
- Case-fold copy denials; secret-shaped overlay refusal
- Absolute / UNC / drive proposal paths rejected
- Write-time symlink/project re-check on deploy/rollback
- Non-zero `exit_code` is not passing evidence
- Gated `tests/__init__.py`; hardened presentation `nvidia-smi`
- Schema stays **6**. Allowlist `{read_text, read_project}`

### Residual risks (accepted)

Spawned Python can use OS-permitted host resources. No child network jail.
Grandchildren may outlive the direct child. No Windows Job Object / AppContainer.
Multi-file rollback is not atomic. Kernel TOCTOU is not eliminated.

### Invariants kept

Dual-key. GuardedTool. Allowlist `{read_text, read_project}`. Coding never writes. ProcessRunner sole spawn in `astra.testing`. Git spawn only in `astra.deployment.gitrun`. No third tool. No auto-approve. `check_permanent_change` never ALLOW. No OS sandbox claim. Plugin metadata is not authority.

### How to resume

1. Read ADR-0034, then this entry.
2. `python -m unittest discover -s tests -t . -q`
3. Do **not** start B-036 unless the user asks.

---

## Update: 2026-08-15 — B-034 done (controlled plugin registry)

- **Phase:** B-001–B-034 complete. Do **not** start a third tool, auto-approve UI, or agent loops.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **1172 tests, 0 failures** (8 skipped: symlink privilege on this host).
- **ADR:** ADR-0033 accepted and implemented. ADR-0014–0032 files not edited.
- **Next milestone:** not started.

### What landed

- `docs/adr/ADR-0033-controlled-plugin-extension-architecture.md`
- `astra/plugins/registry.py`: `LocalPluginRegistry` — controller-owned, not a Tool
- Static `plugin.toml` discovery; no arbitrary Python import
- Disabled plugins do not execute; external execution disabled
- Schema stays **6**. Allowlist `{read_text, read_project}`

### Invariants kept

Dual-key. GuardedTool. Allowlist `{read_text, read_project}`. Coding never writes. ProcessRunner sole spawn in `astra.testing`. Git spawn only in `astra.deployment.gitrun`. No third tool. No auto-approve. `check_permanent_change` never ALLOW. No OS sandbox claim. Plugin metadata is not authority.

### How to resume

1. Read ADR-0033, then this entry.
2. `python -m unittest discover -s tests -t . -q`
3. Do **not** start a third tool or auto-approve UI unless the user asks.

---

## Update: 2026-08-14 — B-033 done (controlled Git version history)

- **Phase:** B-001–B-033 complete. Do **not** start a third tool, auto-approve UI, or agent loops.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **1139 tests, 0 failures** (7 skipped: symlink privilege on this host).
- **ADR:** ADR-0032 accepted and implemented. ADR-0014–0031 files not edited.
- **Next milestone:** not started. Closest later work: UI Deploy/Rollback — only after explicit approval.

### What landed

- `docs/adr/ADR-0032-controlled-git-version-history.md`
- `astra/deployment/gitrun.py`: sole Git subprocess boundary; fixed argv; `shell=False`
- `astra/deployment/gitstore.py`: `GitVersionStore` / `GitRecord`
- Deploy records Git history after VersionManager snapshot
- Honest `deployed_history_failed` when Git fails after a successful write
- Git restore requires `git_restore_fingerprint` ApprovalRecord
- Production Git executable is explicit (`GitSettings.executable`); no PATH search
- Schema stays **6**. Allowlist `{read_text, read_project}`

### Invariants kept

Dual-key. GuardedTool. Write roots not expanded. Scratch temporary-only. Allowlist `{read_text, read_project}`. Coding never writes. Testing never writes `project_root`. Evaluation does not write `project_root`. ProcessRunner sole spawn in `astra.testing`. Git spawn only in `astra.deployment.gitrun`. No listing. No third tool. No auto-approve. `check_permanent_change` never ALLOW. No OS sandbox claim. Global Git config untouched.

### How to resume

1. Read ADR-0032, then this entry.
2. `python -m unittest discover -s tests -t . -q`
3. Do **not** start a third tool or auto-approve UI unless the user asks.

---

## Update: 2026-08-14 — B-032 done (adversarial fail-closed hardening)

- **Phase:** B-001–B-032 complete. Do **not** start Git, a third tool, auto-approve UI, or agent loops.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **1108 tests, 0 failures** (6 skipped: symlink privilege on this host).
- **ADR:** ADR-0031 accepted and implemented. ADR-0014–0030 files not edited.
- **Next milestone:** not started. Closest later work: Git or UI Deploy/Rollback — only after explicit approval.

### What landed

- `docs/adr/ADR-0031-adversarial-end-to-end-safety-hardening.md`
- `tests/test_adversarial.py`: traversal, forbidden destinations, forged/stale approval, process attacks, dishonest reports, unauthorized rollback
- Live `project_root` bytes unchanged after unauthorized attempts
- Policy-gated, not an OS sandbox. Git still absent
- Schema stays **6**. Allowlist `{read_text, read_project}`

### Invariants kept

Dual-key. GuardedTool. Write roots not expanded. Scratch temporary-only. Allowlist `{read_text, read_project}`. Coding never writes. Testing never writes `project_root`. Evaluation does not write `project_root`. ProcessRunner sole spawn in `astra.testing`. No listing. No third tool. No Git. No auto-approve. `check_permanent_change` never ALLOW. No OS sandbox claim.

### How to resume

1. Read ADR-0031, then this entry.
2. `python -m unittest discover -s tests -t . -q`
3. Do **not** start Git, a third tool, or auto-approve UI unless the user asks.

---

## Update: 2026-08-14 — B-031 done (controlled change pipeline)

- **Phase:** B-001–B-031 complete. Do **not** start Git, a third tool, auto-approve UI, or agent loops.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **1089 tests, 0 failures** (5 skipped: symlink privilege on this host).
- **ADR:** ADR-0030 accepted and implemented. ADR-0014–0029 files not edited.
- **Next milestone:** not started. Closest later work: Git or UI Deploy/Rollback — only after explicit approval.

### What landed

- `docs/adr/ADR-0030-end-to-end-controlled-change-pipeline.md`
- `AstraController.process_proposal(proposal, approval=None)` composes test → eval → optional deploy
- `PipelineResult` statuses: invalid_proposal, testing_failed, testing_incomplete, evaluation_failed, approval_missing, approval_invalid, deployment_failed, deployed
- Failed stages skip later agents. No auto-approve. No ApprovalRecord construction
- `rollback_change` delegates; still a separate human gate
- Schema stays **6**. Allowlist `{read_text, read_project}`

### Invariants kept

Dual-key. GuardedTool. Write roots not expanded. Scratch temporary-only. Allowlist `{read_text, read_project}`. Coding never writes. Testing never writes `project_root`. Evaluation does not write `project_root`. ProcessRunner sole spawn in `astra.testing`. No listing. No third tool. No Git. No auto-approve. `check_permanent_change` never ALLOW. No OS sandbox claim.

### How to resume

1. Read ADR-0030, then this entry.
2. `python -m unittest discover -s tests -t . -q`
3. Do **not** start Git, a third tool, or auto-approve UI unless the user asks.

---

## Update: 2026-08-14 — B-030 done (canonical isolated run_suite)

- **Phase:** B-001–B-030 complete. Do **not** start Git, a third tool, auto-approve UI, or agent loops.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **1069 tests, 0 failures** (5 skipped: symlink privilege on this host).
- **ADR:** ADR-0029 accepted and implemented. ADR-0014–0028 files not edited.
- **Next milestone:** not started. Closest later work: Git or UI Deploy/Rollback — only after explicit approval.

### What landed

- `docs/adr/ADR-0029-canonical-isolated-test-suite-execution.md`
- `LocalTestingAgent.run_suite`: copy unchanged snapshot → ProcessRunner unittest → TestReport → discard
- No overlay. Live `project_root` is never the test cwd.
- Same unittest command and parser as `test_proposal`
- Not a Tool. No Evaluation / Deployment / Approval / Coding invocation
- Schema stays **6**. Allowlist `{read_text, read_project}`

### Invariants kept

Dual-key. GuardedTool. Write roots not expanded. Scratch temporary-only. Allowlist `{read_text, read_project}`. Coding never writes. Testing never writes `project_root`. Evaluation does not write `project_root`. ProcessRunner sole spawn in `astra.testing`. No listing. No third tool. No Git. No auto-approve. `check_permanent_change` never ALLOW. No OS sandbox claim.

### How to resume

1. Read ADR-0029, then this entry.
2. `python -m unittest discover -s tests -t . -q`
3. Do **not** start Git, a third tool, or auto-approve UI unless the user asks.

---

## Update: 2026-08-14 — B-029 done (baseline + comparative evaluation)

- **Phase:** B-001–B-029 complete. Do **not** start Git, a third tool, auto-approve UI, `run_suite`, or agent loops.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **1054 tests, 0 failures** (5 skipped: symlink privilege on this host).
- **ADR:** ADR-0028 accepted and implemented. ADR-0014–0027 files not edited.
- **Next milestone:** not started. Closest later work: Git, `run_suite`, or UI Deploy/Rollback — only after explicit approval.

### What landed

- `docs/adr/ADR-0028-baseline-and-comparative-evaluation.md`
- `LocalBaselineStore`: immutable samples under `{scratch}/baselines/`
- `record_baseline` rejects duplicates; corrupt/malformed/traversal fail closed
- `evaluate` / comparative `evaluate_proposal` use exact higher/lower/equal rules
- Tests passing with no samples → `improved` False
- Improvement does not set `deployment_authorized` and does not create `ApprovalRecord`
- Schema stays **6**. Allowlist `{read_text, read_project}`

### Invariants kept

Dual-key. GuardedTool. Write roots not expanded. Scratch baseline store is `check_temporary_write` only. Allowlist `{read_text, read_project}`. Coding never writes. Testing never writes `project_root`. Evaluation does not write `project_root`. No listing. No third tool. No Git. No auto-approve. `check_permanent_change` never ALLOW.

### How to resume

1. Read ADR-0028, then this entry.
2. `python -m unittest discover -s tests -t . -q`
3. Do **not** start Git, a third tool, or auto-approve UI unless the user asks.

---

## Update: 2026-08-14 — B-028 done (VersionManager + honest rollback)

- **Phase:** B-001–B-028 complete. Do **not** start Git, a third tool, auto-approve UI, `run_suite`, or agent loops.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **1026 tests, 0 failures** (5 skipped: symlink privilege on this host).
- **ADR:** ADR-0027 accepted and implemented. ADR-0014–0026 files not edited.
- **Next milestone:** not started. Closest later work: Git, `run_suite`, or UI Deploy/Rollback — only after explicit approval.

### What landed

- `docs/adr/ADR-0027-version-manager-and-honest-rollback.md`
- `LocalVersionManager`: immutable pre-image under `{scratch}/versions/` after successful deploy
- Version id `d-{fingerprint[:16]}-{n:04d}`; snapshot is the state immediately before that deploy
- `rollback(version_id, approval)` requires `rollback_fingerprint(version_id)`
- Sole project restore/delete site: `astra.deployment.apply.restore_entries`
- DENY / unexpected ALLOW / missing approval / corrupted snapshot → zero writes
- Partial restore reported as `rollback_error`; no atomicity claim
- Schema stays **6**. Allowlist `{read_text, read_project}`

### Invariants kept

Dual-key. GuardedTool. Write roots not expanded. Scratch version store is `check_temporary_write` only. Allowlist `{read_text, read_project}`. Coding never writes. Testing never writes `project_root`. Evaluation write-free. No listing. No third tool. UI no Rollback button that synthesises approval. `check_permanent_change` never ALLOW. No Git. No auto-rollback.

### How to resume

1. Read ADR-0027, then this entry.
2. `python -m unittest discover -s tests -t . -q`
3. Do **not** start Git, a third tool, or auto-approve UI unless the user asks.

---

## Update: 2026-08-14 — B-027 done (human-approved controlled deployment)

- **Phase:** B-001–B-027 complete. Do **not** start Git, VersionManager/rollback, a third tool, auto-approve UI, `run_suite`, or agent loops.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **1000 tests, 0 failures** (4 skipped: symlink privilege on this host).
- **ADR:** ADR-0026 accepted and implemented. ADR-0014–0025 files not edited.
- **Next milestone:** not started. Closest later work: VersionManager / rollback, or Git — only after explicit approval.

### What landed

- `docs/adr/ADR-0026-human-approved-controlled-deployment.md`
- `LocalDeploymentAgent.deploy`: proposal + TestReport + EvaluationReport + human ApprovalRecord → `project_root` write
- `evidence_fingerprint` binds approval to exact proposal and evidence
- `check_permanent_change` still never ALLOW; REQUIRES_APPROVAL + matching record may write
- Sole write site: `astra.deployment.apply.commit_files`
- `rollback` remains `NotImplementedError` (no VersionManager)
- Schema stays **6**. Allowlist `{read_text, read_project}`

### Invariants kept

Dual-key. GuardedTool. Write roots not expanded. Allowlist `{read_text, read_project}`. Coding never writes. Testing never writes `project_root`. Evaluation write-free. No listing. No third tool. UI no Deploy button that synthesises approval. `check_permanent_change` never ALLOW. No Git. No rollback claim.

### How to resume

1. Read ADR-0026, then this entry.
2. `python -m unittest discover -s tests -t . -q`
3. Do **not** start Git, rollback, or a third tool unless the user asks.

---

## Update: 2026-08-14 — B-026 done (proposal evaluation)

- **Phase:** B-001–B-026 complete. Do **not** start Approval, Deployment, Apply, Git, directory listing, a third tool, `run_suite`, baseline `evaluate`, or agent loops.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **969 tests, 0 failures** (3 skipped: symlink privilege on this host).
- **ADR:** ADR-0025 accepted and implemented. ADR-0014–0024 files not edited.
- **Next milestone:** not started. Closest later work: human Approval / Deployment Agent — only after explicit approval.

### What landed

- `docs/adr/ADR-0025-evaluation-of-tested-proposals.md`
- `LocalEvaluationAgent.evaluate_proposal`: judges `ChangeProposal` + existing `TestReport`
- Fail closed on missing/empty/incomplete/timeout/spawn-failed/malformed/failing evidence
- `evaluation_passed` ≠ `improved` ≠ `deployment_authorized` (always False)
- No `ApprovalRecord`. No Deployment call. No test re-run. No proposal mutation
- `evaluate` / `record_baseline` remain `NotImplementedError`
- Schema stays **6**. Allowlist `{read_text, read_project}`

### Invariants kept

Dual-key. GuardedTool. Write roots not expanded. Allowlist `{read_text, read_project}`. Coding never applies / never writes. No listing. No third tool. UI no Apply / no Approve / no Deploy. 3B model unchanged. `check_permanent_change` never ALLOW. Evaluation does not import `subprocess`. Architectural gate, not OS sandbox.

### How to resume

1. Read ADR-0025, then this entry.
2. `python -m unittest discover -s tests -t . -q`
3. Do **not** start Approval, Deployment, Apply, listing, or a third tool unless the user asks.

---

## Update: 2026-08-14 — B-025 done (honest proposal testing)

- **Phase:** B-001–B-025 complete. Do **not** start Evaluation, Deployment, Apply, Git, directory listing, a third tool, Approval UI, `run_suite`, or agent loops.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **946 tests, 0 failures** (3 skipped: symlink privilege on this host).
- **ADR:** ADR-0024 accepted and implemented. ADR-0014–0023 files not edited.
- **Next milestone:** not started. Closest later work: Evaluation Agent — only after explicit approval.

### What landed

- `docs/adr/ADR-0024-honest-proposal-testing.md`
- `LocalTestingAgent.test_proposal`: copy → overlay → unittest via `ProcessRunner` → honest `TestReport` → discard
- Parser uses actual unittest `Ran N` / `OK` / `FAILED` output; exit code is recorded, not pass/fail
- Empty / truncated / timeout / spawn failure / unparseable → not passing
- `run_suite` remains `NotImplementedError`
- Schema stays **6**. Allowlist `{read_text, read_project}`. Coding still write-free
- Copy + spawn + unittest are not an OS sandbox

### Invariants kept

Dual-key. GuardedTool. Write roots not expanded. Allowlist `{read_text, read_project}`. Coding never applies / never writes. No listing. No third tool. UI no Apply / no Run Tests. 3B model unchanged. `check_permanent_change` never ALLOW. `nvidia-smi` stays in presentation. `ProcessRunner` sole spawn in `astra.testing`. Architectural gate, not OS sandbox.

### How to resume

1. Read ADR-0024, then this entry.
2. `python -m unittest discover -s tests -t . -q`
3. Do **not** start Evaluation, Apply, listing, or a third tool unless the user asks.

---

## Update: 2026-08-14 — B-024 done (controlled process spawn)

- **Phase:** B-001–B-024 complete. Do **not** start TestingAgent, pytest/unittest as a product feature, Evaluation, Deployment, directory listing, a third tool, Apply UI, or agent loops.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **920 tests, 0 failures** (3 skipped: symlink privilege on this host).
- **ADR:** ADR-0023 accepted and implemented. ADR-0014–0022 files not edited.
- **Next milestone:** not started. Closest later work: Testing Agent (`test_proposal` / `TestReport`) — only after explicit approval.

### What landed

- `docs/adr/ADR-0023-controlled-process-spawn.md`
- `ProcessRunner.run(spec, session_id=...)` spawns `sys.executable` only, `shell=False`, in a known copied-subset session
- Explicit child env: `PYTHONPATH` = session; no `ASTRA_HOME`; Windows host vars only as required
- Timeout (0, 60]; probe default 5s; stdout/stderr 64 KiB; `ProcessStatus` distinguishes outcomes
- Harmless `astra.testing.probe` (`ASTRA_PROCESS_PROBE_OK`)
- Schema stays **6**. `test_proposal` / `run_suite` still `NotImplementedError`. No `TestReport` producer
- Copy + spawn are not an OS sandbox; `check_network_access` does not wrap the child

### Invariants kept

Dual-key. GuardedTool. Write roots not expanded. Allowlist `{read_text, read_project}`. Coding never applies / never writes. No listing. No third tool. No TestingAgent. UI no Apply / no Run Tests. 3B model unchanged. `check_permanent_change` never ALLOW. `nvidia-smi` stays in presentation. Architectural gate, not OS sandbox.

### How to resume

1. Read ADR-0023, then this entry.
2. `python -m unittest discover -s tests -t . -q` — expect 920 passing.
3. Do **not** start TestingAgent, listing, Apply, or a third tool unless the user asks.

---

## Update: 2026-08-14 — B-023 done (copied-subset isolated test workspace)

- **Phase:** B-001–B-023 complete. Do **not** start TestingAgent, spawn, pytest, unittest execution as a product feature, Evaluation, Deployment, directory listing, a third tool, Apply UI, or agent loops.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **904 tests, 0 failures** (3 skipped: symlink privilege on this host).
- **ADR:** ADR-0022 accepted and implemented. ADR-0014–0021 files not edited.
- **Next milestone:** not started. Closest later work: spawn under policy, then Testing Agent — only after explicit approval.

### What landed

- `docs/adr/ADR-0022-copied-subset-workspace.md`
- `CopiedSubsetWorkspace` (`astra/testing/subset.py`): allowlisted copy under scratch; overlay onto existing session
- Controller owns subset workspace; not an agent; not injected into Coding
- Schema stays **6**. `ProcessRunner.spawned` still `False`. `test_proposal` / `run_suite` still `NotImplementedError`
- Copy is filesystem layout only — not an OS sandbox

### Invariants kept

Dual-key. GuardedTool. Write roots not expanded. Allowlist `{read_text, read_project}`. Coding never applies / never writes. No listing. No third tool. No TestingAgent. No spawn. UI no Apply / no Run Tests. 3B model unchanged. `check_permanent_change` never ALLOW. Architectural gate, not OS sandbox.

### How to resume

1. Read ADR-0022, then this entry.
2. `python -m unittest discover -s tests -t . -q` — expect 904 passing.
3. Do **not** start TestingAgent, spawn, listing, Apply, or a third tool unless the user asks.

---

## Update: 2026-08-14 — B-022 done (process policy, fail-closed runner)

- **Phase:** B-001–B-022 complete. Do **not** start TestingAgent, spawn, pytest, Evaluation, Deployment, directory listing, a third tool, Apply UI, or agent loops.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **882 tests, 0 failures** (2 skipped: symlink privilege on this host).
- **ADR:** ADR-0021 accepted and implemented. ADR-0014–0020 files not edited.
- **Next milestone:** not started. Closest later work: copied-subset workspace, then spawn under policy, then Testing Agent — only after explicit approval.

### What landed

- `docs/adr/ADR-0021-process-execution-policy.md`
- `ProcessSpec` + `SecurityAgent.check_process_execution` (ALLOW/DENY; defaults never ALLOW)
- `ProcessRunner.run()` consults Security; `spawned=False`; no `subprocess` in `astra.testing`
- Controller owns runner; not an agent; not injected into Coding
- Schema stays **6**. Overlay unchanged. `test_proposal` / `run_suite` still `NotImplementedError`
- `nvidia-smi` remains the only production `subprocess` (presentation probe)

### Invariants kept

Dual-key. GuardedTool. Write roots not expanded. Allowlist `{read_text, read_project}`. Coding never applies / never writes. No listing. No third tool. No TestingAgent. No spawn. UI no Apply / no Run Tests. 3B model unchanged. `check_permanent_change` never ALLOW. Architectural gate, not OS sandbox.

### How to resume

1. Read ADR-0021, then this entry.
2. `python -m unittest discover -s tests -t . -q` — expect 882 passing.
3. Do **not** start TestingAgent, spawn, listing, Apply, or a third tool unless the user asks.

---

## Update: 2026-08-14 — B-021 done (isolated proposal workspace)

- **Phase:** B-001–B-021 complete. Do **not** start TestingAgent, Evaluation, Deployment, directory listing, a third tool, Apply UI, subprocess test runner, or agent loops.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **857 tests, 0 failures** (2 skipped: symlink privilege on this host).
- **ADR:** ADR-0020 accepted and implemented. ADR-0014–0019 files not edited.
- **Next milestone:** not started. Closest later work: Testing Agent (needs process policy) or directory listing — only after explicit approval.

### What landed

- `docs/adr/ADR-0020-isolated-proposal-workspace.md`
- `TestingSettings.scratch_directory` (default `<temp>/astra-scratch`)
- `SecurityAgent.check_temporary_write` — ALLOW under scratch only; never a `check_file_write` root
- `ProposalWorkspace.materialize` / `discard` — overlay of proposed files; `project_root` unchanged
- Controller owns workspace; not an agent; not injected into Coding
- Schema stays **6**. No `TestingSnapshot`. `test_proposal` / `run_suite` still `NotImplementedError`

### Invariants kept

Dual-key. GuardedTool. Write roots not expanded. Allowlist `{read_text, read_project}`. Coding never applies / never writes. No listing. No third tool. No TestingAgent. UI no Apply. 3B model unchanged. `check_permanent_change` never ALLOW.

### How to resume

1. Read ADR-0020, then this entry.
2. `python -m unittest discover -s tests -t . -q` — expect 857 passing.
3. Do **not** start TestingAgent, listing, Apply, subprocess, or a third tool unless the user asks.

---

## Update: 2026-08-14 — B-020 done (proposal inspectability)

- **Phase:** B-001–B-020 complete. Do **not** start Testing, Evaluation, Deployment, directory listing, a third tool, Apply UI, or agent loops.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **829 tests, 0 failures** (2 skipped: symlink privilege on this host).
- **ADR:** ADR-0019 accepted and implemented. ADR-0014–0018 files not edited.
- **Next milestone:** not started. Closest later work: Testing Agent or directory listing — only after explicit approval.

### What landed

- `docs/adr/ADR-0019-coding-proposal-inspectability.md`
- `LocalCodingAgent.last_proposal` retained after `propose_change` / `run`
- `run()` calls `review()` once; no retry generate
- `CodingSnapshot` schema v6 inspectable fields; `applied` always `False`
- Full `new_content` stays on the agent, not in `ApplicationState`
- Dashboard Coding panel shows paths / risks / objections / bounded preview; no Apply

### Invariants kept

Dual-key. GuardedTool. Write roots not expanded. Allowlist `{read_text, read_project}`. Coding never applies. No listing. No third tool. No Testing Agent. UI no Apply. 3B model unchanged.

### How to resume

1. Read ADR-0019, then this entry.
2. `python -m unittest discover -s tests -t . -q` — expect 829 passing.
3. Do **not** start Testing, listing, Apply, or a third tool unless the user asks.

---

## Update: 2026-08-14 — B-019 done (proposal-only Coding Agent)

- **Phase:** B-001–B-019 complete. Do **not** start Testing, Evaluation, Deployment, directory listing, a third tool, Apply UI, or agent loops.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **822 tests, 0 failures** (2 skipped: symlink privilege on this host).
- **ADR:** ADR-0018 accepted and implemented. ADR-0014, ADR-0015, ADR-0016, and ADR-0017 files not edited.
- **Next milestone:** not started. Closest later work: Testing Agent or directory listing — only after explicit approval.

### What landed

- `docs/adr/ADR-0018-coding-agent-proposal-only.md`
- `LocalCodingAgent` (`astra/coding/agent.py`) — proposal-only
- Controller injects `ToolInvoker`, not the registry; caller `"coding"`; only `read_project`
- `targets=None` / empty → zero tool calls (no `list_directory`)
- `CodingSnapshot`; `ApplicationState.schema_version = 5`; `applied` always `False`
- Dashboard Coding panel render-only; no Apply
- Qwen2.5-3B unchanged; 3B inadequacy documented

### Invariants kept

Dual-key. GuardedTool. Write roots not expanded. `read_text` unchanged. `read_project` exclusions unchanged. Production allowlist exactly `{read_text, read_project}`. Coding never applies. No `astra.security` import from `astra.coding`. UI no Apply. 3B model unchanged. No listing. No third tool.

### How to resume

1. Read ADR-0018, then this entry.
2. `python -m unittest discover -s tests -t . -q` — expect 822 passing.
3. Do **not** start Testing, listing, Apply, or a third tool unless the user asks.

---

## Update: 2026-08-14 — B-018 done (read_project)

- **Phase:** B-001–B-018 complete. Do **not** start B-019, Coding, Testing, Evaluation, Deployment, directory listing, or a third tool.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **801 tests, 0 failures** (2 skipped: symlink privilege on this host).
- **ADR:** ADR-0017 accepted and implemented. ADR-0014, ADR-0015, and ADR-0016 files not edited.
- **Next milestone:** not started. Closest later work: directory listing or Coding Agent — only after explicit approval.

### What landed

- `docs/adr/ADR-0017-controlled-project-read.md`
- `SecurityAgent.check_file_read` + `PolicySecurityAgent` implementation
- `ToolSpec.reads_files`; gate maps True → `check_file_read`
- `astra/tools/read_project.py` — `ReadProjectTool`
- Production allowlist exactly `{"read_text", "read_project"}`
- Tools panel copy: `read_project` (project_root, exclusions, 64 KiB)

### Invariants kept

Dual-key. GuardedTool. Write roots not expanded. `read_text` unchanged. Schema v4. UI render-only. No agent `ToolInvoker`. No directory listing. No Coding Agent. `check_file_read` never `REQUIRES_APPROVAL`.

### How to resume

1. Read ADR-0017, then this entry.
2. `python -m unittest discover -s tests -t . -q` — expect 801 passing.
3. Do **not** start Coding or listing unless the user asks.

---

## Update: 2026-08-14 — B-017 done (read_text)

- **Phase:** B-001–B-017 complete. Do **not** start B-018, Coding, Testing, Evaluation, Deployment, or a second tool.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **764 tests, 0 failures.**
- **ADR:** ADR-0016 accepted and implemented. ADR-0014 and ADR-0015 files not edited.
- **Next milestone:** not started. Closest later work: `check_file_read` / repo inspect, or Coding Agent — only after explicit approval.

### What landed

- `docs/adr/ADR-0016-read-text-tool.md`
- `astra/tools/read_text.py` — `ReadTextTool`
- Production allowlist exactly `{"read_text"}`
- Controller registers that one tool
- Tools panel copy: `read_text` (data/log roots, 64 KiB)

### Invariants kept

Dual-key. GuardedTool. No `check_file_read`. `project_root` DENY. No agent `ToolInvoker`. Schema v4. UI render-only. No subprocess/HTTP/eval.

### How to resume

1. Read ADR-0016, then this entry.
2. `python -m unittest discover -s tests -t . -q`
3. Do **not** start Coding or a second tool unless the user asks.

---

## Update: 2026-08-14 — B-016 done (Tool Interface)

- **Phase:** B-001–B-016 complete. Do **not** start B-017, Coding, Testing, Evaluation, Deployment, or real tools.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **746 tests, 0 failures.**
- **ADR:** ADR-0015 accepted and implemented. Existing ADRs not silently amended.
- **Next milestone:** not started. Closest later work: a real tool or Coding Agent — only after explicit approval. Both need this gate.

### What landed

- `docs/adr/ADR-0015-tool-interface.md`
- `InMemoryToolRegistry`, `GuardedTool`, `GatedToolInvoker`
- Controller `ctrl.tools`, `ctrl.request_tool`, `ctrl.record_human_approval`
- Production: zero registered tools, empty execution allowlist, never executes
- `ToolSnapshot`; `ApplicationState.schema_version = 4`
- Dashboard Tools panel — render-only, no approval buttons

### Invariants kept

Architectural gate, not OS sandbox. No subprocess/HTTP/fs-mutation/plugins.
ALLOW ≠ execute. Approval ≠ execute. Security ABC unchanged. Research unused tools.
Engine SQLite writes stay outside the gate. UI no `astra.tools` import.

### How to resume

1. Read ADR-0015, then this entry.
2. `python -m unittest discover -s tests -t . -q` — expect 746 passing.
3. Do **not** start Coding or real tools unless the user asks.

---

## Update: 2026-08-14 — B-015 done (Security Agent)

- **Phase:** B-001–B-015 complete. Do **not** start B-016, Tools, or Coding.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **710 tests, 0 failures.**
- **ADR:** ADR-0014 accepted and implemented. ADR-0011 open question 5 narrowly amended (consult, still no network I/O).
- **Next milestone:** not started. Closest later work: Tool Interface (gated by this agent) — only after explicit approval.

### What landed

- `docs/adr/ADR-0014-security-agent.md`
- `astra/security/agent.py` — `PolicySecurityAgent`
- Controller `ctrl.security` + register `"security"`
- Research consults Security when `allow_network=True`
- `SecuritySnapshot`; `ApplicationState.schema_version = 3`
- Dashboard Security panel — render-only

### Invariants kept

Policy only. No subprocess/HTTP/file mutation/tools. Loopback denied. Permanent change never ALLOW. UI no `astra.security` import. SQLite truth unchanged.

### How to resume

1. Read ADR-0014, then this entry.
2. `python -m unittest discover -s tests -t . -q` — expect 710 passing.
3. Do **not** start Tools or Coding unless the user asks.

---

## Update: 2026-08-14 — B-014 done (Goal Manager)

- **Phase:** B-001–B-014 complete. Do **not** start B-015 or any other milestone.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **679 tests, 0 failures.**
- **ADR:** ADR-0013 accepted and implemented. No new packages, no downloads, no Ollama, no production-model change.
- **Next milestone:** not started. Closest later work: Coding Agent (needs Security + tools first) — only after explicit approval.

### What landed

- `docs/adr/ADR-0013-goal-manager.md` — accepted before implementation
- `planning.db` schema v2 — goals, criteria, links, evaluation snapshots
- `astra/planning/goal_manager.py` — `SQLiteGoalManager`
- Additive lifecycle: `activate`, `abandon`, `mark_achieved`, `last_evaluation`
- `evaluate()` observational bool + persisted `GoalEvaluation`
- `GoalSnapshot` in `astra.state`; live via `ControllerStateProvider`
- Dashboard Goals panel — render-only, no dispatch

### Invariants kept

`Goal` / `GoalStatus` / original five methods unchanged. Task DONE is progress only.
FTS guaranteed. Embeddings optional. No LLM evaluation. No agent loop.
UI imports `astra.state` / `astra.presentation` only.

### How to resume

1. Read ADR-0013, then this entry.
2. `python -m unittest discover -s tests -t . -q` — expect 679 passing.
3. Do **not** start B-015 unless the user asks.

---

## Update: 2026-08-14 — B-013 done (Memory semantic recall)

- **Phase:** B-001–B-013 complete. Goal Manager still deferred (ADR-0009 D12). Do not start it in this session.
- **Launch:** `python -m astra` (controller + **ControllerStateProvider** dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **651 tests, 0 failures.**
- **ADR:** ADR-0012 accepted and implemented. No new packages, no downloads, no Ollama, no production-model change.
- **Next milestone:** Goal Manager (not numbered in the original backlog; do not auto-start). Then a Coding Agent needs its own entry.

### What landed

- `astra/memory/indexer.py` — `MemoryIndexer(store, index, backend)`
- Provenance API on `SQLiteMemoryStore` (columns already in schema v1)
- `rebuild_index()` availability guard before `clear()`
- Controller: load/save FAISS; `rebuild_memory_index()`; no embed on `start()`
- `MemorySource(store, indexer=None)` — FTS5 baseline + optional semantic merge
- Live `ControllerStateProvider` wired from `__main__.py`
- Memory panel renders `ApplicationState`; dropped "pending B-003"
- `progress.py` B-013 import check (not hard-coded `True`)

### Invariants kept

SQLite is truth. FAISS is derived. FTS5 intact. Whole-record embeddings only.
Knowledge vectors, Goal Manager, UI dispatch, chunking: not implemented.

### Dashboard

Live mode observes the running controller. Vector index status follows ADR-0012 D13:
empty + no backend → `configured`; never `operational` for an empty index.
Probe provider (`AstraStateProvider`) still cannot see live vector counts and
must not report FAISS as `operational`.

### How to resume

1. Read ADR-0012, then this entry.
2. `python -m unittest discover -s tests -t . -q` — expect 651 passing.
3. Do **not** start Goal Manager unless the user asks.

---

## Update: 2026-08-14 — B-005 done (Pydantic configuration)

- **Phase:** B-001–B-007, B-009–B-012 complete. No remaining numbered `todo` in the backlog.
- **Launch:** `python -m astra` (controller + dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **617 tests, 0 failures** (585 prior + 32 B-005).
- **Dependency:** pydantic **2.13.4** already installed. Nothing newly installed. No network.
- **Next milestone:** none numbered. Closest deferred work: Goal Manager (ADR-0009 D12). Then a new agent (Coding) needs a backlog entry. Stop here.

### Files changed

- `astra/configuration/settings.py` — dataclasses → Pydantic v2 models
- `astra/configuration/loader.py` — `model_fields` + same Path coerce / unknown-key rules
- `astra/configuration/__init__.py` — docstring; example backend `llama_cpp`
- `astra/presentation/progress.py` — machine-checkable B-005 milestone
- `tests/test_configuration.py` — compatibility + loader + pydantic tests added (32 net new across config/offline)
- `tests/test_offline.py` — pydantic allowed only in `settings.py`; load_settings socket/HTTP/subprocess guards
- `tests/__init__.py` — pydantic note
- `ui/demo/provider.py` — demo test totals 617
- `docs/backlog.md`, `docs/architecture.md`, `docs/session-handoff.md`, `README.md`

No new ADR. ADR-0001 already named pydantic for config.

### Public API kept

`AstraSettings`, nested `*Settings`, `load_settings`, `find_config_file`,
`ConfigurationError`, path helpers, `SUPPORTED_REASONING_BACKENDS`,
`LOG_LEVELS`, `LoggingSettings.file_path`. TOML sections/keys/defaults
unchanged. `ValidationError` does not leak.

### Assumptions

1. Pydantic v2 (`>=2.7`; installed 2.13.4) is the intended major version.
2. Wrapping `ValidationError` as `ConfigurationError` is required compatibility.
3. `BaseSettings` / env-prefix auto-override would change precedence — not used.
4. Direct `ReasoningSettings(host=...)` now raises `ConfigurationError` (extra forbid) instead of `TypeError`. Invalid kwargs were never a supported API.

### Deviations

1. `tests/test_offline.py` previously scanned for the substring `import pydantic` in every module. That was a scaffold constraint. B-005 requires pydantic in `settings.py`. Check retargeted: pydantic allowed only there; httpx/numpy/faiss still banned. Architectural assertion (no host field) now uses `model_fields` instead of `__dataclass_fields__`. Behaviour under test unchanged.
2. `__init__.py` example previously claimed `backend == 'ollama'` — already false since ADR-0004. Corrected to `llama_cpp`.

### Technical debt

1. Loader still hand-coerces Path and bool/int traps. Could lean on Pydantic-only, but that would risk silent coercion. Kept dual layer on purpose.
2. `embedding_context_tokens` still has no positivity check (same as dataclass era).
3. Configuration package is no longer zero-third-party. Tests that assumed a fully stdlib scaffold need the B-005 exception.

### Dashboard

- B-005 in `MILESTONES`, stage 11. Complete iff settings expose `model_fields` and production defaults still match. Not a hard-coded True.
- No UI redesign. Capability detection still calls `load_settings()`.

### Compatibility notes

- Frozen models remain frozen.
- Extra keys forbidden (TOML and constructor).
- `require_human_approval = false` still invalid.
- Ollama backend still rejected.
- No `reasoning.host`.
- Production defaults: Qwen2.5-3B-Instruct-Q5_K_M, ctx 4096, gpu 99; embedding nomic-embed-text-v1.5, ctx 2048, dim 768, CPU.

---

## Update: 2026-08-14 — B-009 done (Research Agent) — documentation pass

- **Phase:** B-001–B-004, B-006–B-007, B-009–B-012 complete. B-005 **not** started.
- **Launch:** `python -m astra` (controller + dashboard)
  or `python -m astra --demo` (dashboard only).
- **Tests:** **585 tests, 0 failures.**
- **Next milestone:** **B-005** (Adopt pydantic in the Configuration Manager).
  Do not start it in this session. Alternative later work: Goal Manager
  (deferred from B-006 / ADR-0009 D12). No other numbered backlog item is `todo`.

### Files created (implementation, already landed)

- `astra/research/agent.py` — `LocalResearchAgent`
- `astra/research/source.py` — `ResearchSource` ABC + `TextSource`,
  `DocumentSource`, `KnowledgeSource`, `MemorySource`
- `astra/research/errors.py` — `ResearchError`, `SourceError`
- `tests/test_research.py` — 74 tests
- `docs/adr/ADR-0011-research-agent.md` — accepted 2026-08-14 (9 decisions)

### Files modified (implementation, already landed)

- `astra/research/contracts.py` — `Finding.retrieved_at`
- `astra/research/__init__.py` — exports
- `astra/core/astra_controller.py` — `_register_research_agent()` in `start()`
- `astra/presentation/progress.py` — `_check_research_agent()`; B-009 in `MILESTONES`
- `astra/presentation/provider.py` — `astra.research` capability
- `ui/demo/provider.py` — demo module list includes local research

### Files modified (this documentation pass)

- `docs/backlog.md` — B-009 `done`; actual outcome recorded
- `docs/architecture.md` — Research implemented; role + dependency boundaries;
  local-first / no-web limitation; offline invariant corrected (no Ollama loopback)
- `docs/session-handoff.md` — this entry
- `README.md` — status table no longer claims research is interface-only
- `astra/presentation/progress.py` — completion check imports `LocalResearchAgent`
- `astra/presentation/provider.py` — research status probed, not a hard-coded flag;
  `network_research` reported as `planned`
- `ui/demo/provider.py` — local research operational; web research planned

No new ADR. ADR-0011 already records the B-009 decisions.

### Architectural decisions actually implemented (ADR-0011)

| ID | Decision | In code |
|---|---|---|
| D1 | `ResearchSource` ABC in `astra/research/source.py` | yes |
| D2 | Four local sources only | yes |
| D3 | Per-source confidence semantics | yes (memory hits fixed at 0.5) |
| D4 | `Finding.retrieved_at` | yes |
| D5 | `LocalResearchAgent`: investigate + summarise; `run()` never raises | yes |
| D6 | No auto-writes to Memory or Knowledge | yes (tested) |
| D7 | LLM synthesis optional and model-agnostic | yes |
| D8 | Controller auto-registers agent in `start()` with knowledge + memory sources | yes |
| D9 | B-009 complete when `astra.research.agent` is importable | yes (`_check_research_agent`) |

Role split held: Research → evidence; Knowledge → accepted knowledge;
Memory → personal/contextual memory; Planning → tasks/plans;
Reasoning → inference; Controller → orchestration.

### Assumptions

1. Keyword overlap is enough for `TextSource` / `DocumentSource` in this milestone.
2. Fixed memory-hit confidence 0.5 is acceptable until FTS5 rank is exposed.
3. Research must work with no model file present (deterministic summary).
4. Callers, not the agent, decide whether findings become knowledge or memory.
5. No network source will be added without a new ADR and a Security Agent gate.

### Deviations

1. Backlog B-009 listed "tool interface implementation" as a dependency.
   B-009 does **not** use `astra.tools`. Sources replace tools for this milestone.
2. Milestone copy previously said "with tool access". False. Corrected in
   progress metadata.
3. `allow_network=True` is a no-op. No network source exists.
4. `ControllerStateProvider` (inside `astra_controller.py`) still omits
   `astra.research` from its module list. Live dashboard uses
   `AstraStateProvider`, which does report it. Left unchanged: this pass is
   documentation + dashboard metadata only.
5. `summarise()` does not receive the original question; LLM prompt uses the
   placeholder `"research question"`.

### Technical debt

1. `DocumentSource` splits on blank lines only. Chunking deferred (ADR-0011 Q1).
2. Memory/knowledge confidence does not use FTS5 BM25 rank (ADR-0011 Q2).
3. Findings are not re-ranked across sources (ADR-0011 Q3).
4. Synthesis prompt is a minimal template (ADR-0011 Q4).
5. Security gate for future web sources does not exist (ADR-0011 Q5).
6. `ControllerStateProvider` does not surface the research agent (see deviation 4).

### Current dashboard state

- B-009 completion: machine-checked by importing `LocalResearchAgent` and
  verifying `name == "research"`. Not a hard-coded boolean.
- `astra.research` module status: `operational` when the agent is importable
  (it is). Capability `local_research_agent`: operational. Capability
  `network_research`: **planned** — web/network research is not implemented.
- Completed stages include B-001, B-002, B-003, B-004, B-006, B-007, B-008,
  B-009, B-011, B-012. `stage` is the first unfinished milestone in
  `MILESTONES` (currently none of those remain; B-005 is not in `MILESTONES`).
- Coding / testing / evaluation / deployment / security agents: not reported
  as operational.

### Blocked / pending work

- **B-005** — pydantic config. Blocked on `pydantic` being installed. Not started.
- Goal Manager — deferred (ADR-0009 D12). No backlog id.
- Network/web research — not started; needs a new ADR + Security Agent.
- Coding, testing, evaluation, deployment, security agents — interface only.
- CUDA PATH shim for llama.cpp (existing debt, not B-009).

### Exact recommended next milestone

**B-005 — Adopt pydantic in the Configuration Manager.**

Do not start it here. Do not start a Coding Agent or web-research work without
a backlog entry and ADR.

---

## Update: 2026-08-14 — B-007 done (Core Controller)

- **Phase:** B-001–B-004, B-006–B-007, B-010–B-012 complete.
- **Launch:** `python -m astra` (controller starts + dashboard live)
  or `python -m astra --demo` (dashboard only, no controller).
- **Next milestone:** B-005 (pydantic config) or B-009 (Research Agent).

**What changed this session (2026-08-14, B-007):**

1. ADR-0010 written and accepted (Core Controller design, 9 decisions).
2. `astra/core/errors.py` created: `ControllerError`, `ControllerNotStartedError`,
   `AgentAlreadyRegisteredError`, `AgentNotFoundError`.
3. `astra/core/astra_controller.py` created: `AstraController` (implements `Controller`),
   `ControllerStateProvider` (implements `StateProvider`).
   - DI via factory overrides; all engines in `start()`.
   - Idempotent `start()`/`shutdown()`; partial-start cleanup.
   - `dispatch()` wraps agent exceptions in `AgentResult(success=False)`.
   - Truthful `get_state()` — missing model → CONFIGURED not AVAILABLE/OPERATIONAL.
4. `astra/core/__init__.py` updated: exports `AstraController`, `ControllerStateProvider`,
   all error types.
5. `astra/__main__.py` updated: controller started before dashboard, cleanly shut down.
6. `astra/presentation/progress.py`: `_check_core_controller()` added; B-007 milestone
   wired to real check.
7. `tests/test_controller.py`: 59 new tests covering all spec items.
8. `docs/adr/ADR-0010-core-controller.md` created.
9. `docs/architecture.md`, `docs/backlog.md`, `docs/session-handoff.md` updated.
10. Total: **511 tests, 0 failures** (was 452 before B-007).

---

## Update: 2026-08-14 — B-006 done (Planning Engine)

- **Phase:** B-001, B-002, B-003, B-004, B-006, B-010, B-011, B-012 complete.
- **Launch dashboard:** `python -m astra` (live) or `python -m astra --demo`
- **Next milestone:** B-005 (pydantic config) or B-007 (Core Controller).
  B-005 depends on pydantic installed. B-007 depends on B-001/B-002/B-003 (all done).

**What changed this session (2026-08-14, B-006):**

1. ADR-0009 written and accepted (Planning Engine design, 12 decisions).
2. `astra/planning/contracts.py` extended:
   - `Task` extended with `created_at`, `updated_at`, `completed_at`, `completion_note`.
   - `TERMINAL_STATUSES` frozenset added.
   - `Backlog` extended with `initialise()`, `complete()`, `cancel()`, `update_task()`,
     `actionable_tasks()`, `validate_dependencies()`.
   - `list_tasks()` signature extended with optional `priority` filter.
3. `astra/planning/errors.py` created: `PlanningStoreError`, `DuplicateTaskError`,
   `DependencyError`, `InvalidTransitionError`.
4. `astra/planning/schema.py` created: SQLite schema v1, terminal trigger, migrations.
5. `astra/planning/sqlite_store.py` created: `SQLitePlanningStore`.
   - Dep existence validated at add time.
   - Cycle detection via DFS (pre-insert check).
   - Terminal state trigger + Python guard.
   - Pure-SQL `actionable_tasks()` query.
6. `astra/planning/engine.py` created: `SimplePlanningEngine`.
   - No model invocation; no reasoning imports.
   - `plan()` = keyword search + topological sort (Kahn's algorithm).
7. `astra/planning/__init__.py` updated.
8. `astra/configuration/settings.py`: `PlanningSettings` added.
9. `astra/configuration/paths.py`: `default_planning_database_path()` added.
10. `astra/configuration/__init__.py`, `loader.py`: `planning` section wired.
11. `astra/presentation/progress.py`: check functions for B-003, B-004, B-006 added.
12. `astra/presentation/provider.py`: knowledge and planning modules updated to OPERATIONAL.
13. `tests/test_planning.py`: 112 new tests covering all spec items.
14. `tests/test_presentation.py`: 2 tests updated to reflect B-003/B-004/B-006 complete.
15. `docs/adr/ADR-0009-planning-engine.md` created.
16. `docs/architecture.md`, `docs/backlog.md`, `docs/session-handoff.md` updated.
17. Total: **452 tests, 0 failures** (was 340 before B-006).

---

## Update: 2026-08-14 — B-004 done (Knowledge Engine)

- **Phase:** B-001, B-002, B-003, B-004, B-010, B-011, B-012 complete.
- **Launch dashboard:** `python -m astra` (live) or `python -m astra --demo`
  Requires torch CUDA lib in PATH — see technical debt §15.
- **Next milestone:** B-005 (pydantic config) or B-006 (Planning Engine).
  B-004 is done. B-005 depends on pydantic being installed; B-006 depends on B-001.

**What changed this session (2026-08-14, B-004):**

1. ADR-0008 written and accepted (Knowledge Engine design).
2. `astra/knowledge/contracts.py` extended:
   - `KnowledgeVerification` enum added (UNVERIFIED/VERIFIED/REFUTED, independent of Memory).
   - `KnowledgeRecord` extended with `verification`, `updated_at`, `acceptance_note`.
   - `KnowledgeStore` contract extended with `iter_records()` and `delete()`.
3. `astra/knowledge/errors.py` created: `KnowledgeStoreError`, `DuplicateKnowledgeError`,
   `VerifiedKnowledgeProtectedError`.
4. `astra/knowledge/schema.py` created: SQLite schema v1, FTS5 support, migrations.
   Separate from memory schema; independent `user_version` counter.
5. `astra/knowledge/classifier.py` created: `KeywordKnowledgeClassifier`.
   Deterministic, no model invocation, 5 documented confidence constants (1.0/0.75/0.5/0.25/0.0).
   Covers all 13 constitutional knowledge categories with primary and secondary keyword sets.
6. `astra/knowledge/sqlite_store.py` created: `SQLiteKnowledgeStore`.
   Constitutional invariants enforced at DB level (trigger + unique index).
   Context manager, `search_mode` property, streaming `iter_records`.
7. `astra/knowledge/__init__.py` updated to export all new symbols.
8. `astra/configuration/settings.py`: `KnowledgeSettings` added, `AstraSettings` updated.
9. `astra/configuration/paths.py`: `default_knowledge_database_path()` added.
10. `astra/configuration/__init__.py`: `KnowledgeSettings` exported.
11. `astra/configuration/loader.py`: `knowledge` section wired into `_SECTIONS` and `load_settings`.
12. `tests/test_knowledge.py`: 87 new tests. All 25 ADR-0008 coverage points satisfied.
13. `docs/adr/ADR-0008-knowledge-engine.md` created.
14. `docs/architecture.md`, `docs/backlog.md`, `docs/session-handoff.md` updated.
15. Total: **340 tests, 0 failures** (was 253 before B-004).

---

## Update: 2026-08-14 — B-002 done, B-011 done, B-010 blocked

- **Phase:** B-002, B-010, B-011, B-012 complete. Dashboard live.
- **Launch dashboard:** `python -m astra` (live) or `python -m astra --demo`
  Requires torch CUDA lib in PATH — see technical debt §15.
- **Next milestone:** B-004 — Knowledge Engine (classification before storage).
  B-003 is done. B-004 depends on B-001 and B-003.

**What changed this session (2026-08-14):**

1. ADR-0005 written (stage 2, model selection on paper) and approved.
2. ADR-0005 stage 4 empirical benchmark completed:
   - 3B Qwen2.5 Q5_K_M: 46.6 tok/s GPU, ctx=4096 uses 2740 MiB VRAM.
   - 8B Dolphin/Llama3.1 Q5_K_S: 6.3 tok/s at 18/33 GPU layers. Outcome B.
3. ADR-0005 stage 5 approved by user: production default set to 3B.
4. `astra/configuration/settings.py` updated: model label, context_tokens=4096,
   gpu_layers=99. model_path stays None (user provides the GGUF path).
5. 8 new tests in `BenchmarkedDefaultsTest`. Total grew to 102.
6. Benchmark scripts and results saved in `benchmarks/`.
7. ADR-0006 (Presentation Layer) written and accepted. Defines the
   observation/operation separation, five CapabilityStatus values, incremental
   dashboard milestones, theme/logic separation, and single-application target.
8. `astra/state/` package created: `CapabilityStatus`, `CapabilityInfo`,
   `ModuleStatus`, `SystemInfo`, `TestStatus`, `ApplicationState`,
   `StateProvider`. Leaf package — imports nothing from rest of astra.
9. `tests/test_state.py` added: 24 new tests. Total: 126 tests, 0 failures.
10. `architecture.md` and `test_architecture.py` updated for `astra.state`.
11. Backlog: B-010 (embedding acquisition — user), B-011 (done), B-012 (done).
12. ADR-0007: Dear PyGui selected; four alternatives evaluated and rejected.
13. `astra/presentation/` created: `AstraStateProvider`, `MILESTONES`.
14. `ui/` created: themes (futuristic), components (header, timeline, system,
    memory, reasoning, models), app (AstraApp + DPG render loop), demo mode.
15. `astra/__main__.py` created: `python -m astra` launches dashboard.
16. Tests: 41 new (test_presentation 21 + test_ui_contracts 20).
17. B-010 done: nomic-embed-text-v1.5.Q8_0.gguf verified. dim=768, NOT normalized.
    21 tests. Outcome B: B-003 must normalize before IndexFlatIP.
18. B-003 done: FaissVectorIndex (IndexFlatIP, L2-norm, rebuild-from-SQLite).
    Files: astra/memory/faiss_index.py, astra/memory/_vec_norm.py.
    Settings: embedding_context_tokens=2048 added to ReasoningSettings.
    63 tests in test_vector_index.py. Total: 253 tests, 0 failures.

**CUDA runtime dependency (stage 5 debt item):**
llama-cpp-python cu124 requires `cublas64_12.dll` and `cudart64_12.dll`.
These are NOT in system PATH — they live in:
`C:\Users\ASUS\anaconda3\envs\Chamber\Lib\site-packages\torch\lib\`
Before starting any llama-cpp-python process, prepend that directory to PATH.
Not automated yet — tracked as technical debt below.

---

## Original session: 2026-08-12 — Scaffold complete

Written at the end of the scaffolding session so that work can continue in a new
conversation without re-reading the whole repository or re-deriving decisions.

- **Date:** 2026-08-12
- **Phase:** Scaffold complete. No intelligence, memory, or agent behaviour built.
- **Next action at the time:** backlog item B-008, measure the host hardware.

Everything below describes work that exists in the repository. Nothing planned
or discussed but unbuilt is listed as done.

---

## 1. What is complete

1. `CONSTITUTION.md` committed, containing the user-supplied project
   constitution verbatim, formatted as Markdown. Content unaltered.
2. `docs/adr/ADR-0001-local-first-stack.md` written and accepted. It settles
   language, structured store, vector index, inference runtime, and network
   posture.
3. Repository scaffold: fifteen module packages, tests, and documentation.
4. Two modules implemented in full: the Configuration Manager and the Logging
   System. Both use the standard library only.
5. Thirteen module packages containing interfaces only. Every abstract method
   body is `raise NotImplementedError`. No placeholder behaviour exists.
6. B-008 complete: hardware measured, recorded in ADR-0002.
7. B-001 complete: `SQLiteMemoryStore` implemented against the approved schema
   in ADR-0003. Memory records only; knowledge tables and vectors excluded.
8. B-002 stage 1 complete: in-process llama.cpp backends, with no network
   transport anywhere in the inference path (ADR-0004, superseding ADR-0001 D5).
9. Test suite of 94 tests covering architecture, configuration, logging,
   interface honesty, offline enforcement, the memory store, and the reasoning
   backends. All passing.

## 2. What is deliberately not built

- No knowledge store; the knowledge tables are B-004.
- No vector index, no FAISS code, no embedding provenance written.
- No LLM or embedding backend, no Ollama client, no HTTP code.
- No agent implementations of any kind.
- No plugin loader, no tool implementations.
- No controller wiring.
- No dependencies installed. No models downloaded. No network request made.

## 3. Environment

| Fact | Value |
|---|---|
| OS | Windows 11, build 10.0.26200 |
| Python | 3.12.8 (MSC v.1942, 64-bit) |
| Repository path | `C:\Users\ASUS\Documents\Astra` |
| Third-party packages installed | none |
| Ollama installed | unknown, not required by anything in the repository |
| CPU | AMD Ryzen 5 5600H, 6 cores / 12 threads |
| RAM | 15.4 GiB total |
| GPU | NVIDIA RTX 3050 Laptop, 4 GiB VRAM, CUDA 8.6, driver 596.36 |
| Disk free | 236.8 GiB |

Hardware was measured on 2026-08-12 under B-008; see
`docs/adr/ADR-0002-hardware-profile.md`.

The project requires Python 3.11 or newer for `tomllib`. The machine has 3.12.8.

## 4. Architecture as built

The constitution names sixteen components. Fourteen are top-level packages and
two are nested, by decision recorded in `docs/architecture.md`.

| Constitution component | Location | State |
|---|---|---|
| Core Controller | `astra/core/controller.py` | interface |
| Planning Engine | `astra/planning/contracts.py` | interface |
| Memory Engine | `astra/memory/sqlite_store.py` | **implemented** (B-001) |
| Knowledge Engine | `astra/knowledge/contracts.py` | interface |
| Goal Manager | `astra/planning/goals.py` | interface, nested |
| Research Agent | `astra/research/contracts.py` | interface |
| Coding Agent | `astra/coding/contracts.py` | interface |
| Testing Agent | `astra/testing/contracts.py` | interface |
| Evaluation Agent | `astra/evaluation/contracts.py` | interface |
| Deployment Agent | `astra/deployment/contracts.py` | interface |
| Security Agent | `astra/security/contracts.py` | interface |
| Plugin Manager | `astra/plugins/contracts.py` | interface |
| Tool Interface | `astra/tools/contracts.py` | interface |
| Configuration Manager | `astra/configuration/` | **implemented** |
| Logging System | `astra/logging/` | **implemented** |
| Version Manager | `astra/deployment/versioning.py` | interface, nested |

One package is not a constitution component:

| Package | Why it exists |
|---|---|
| `astra/reasoning/` | Home for the swappable `LLMBackend` and `EmbeddingBackend` interfaces. **Implemented** for llama.cpp in-process (ADR-0004); no module here may import a network library. |

Nesting rationale, in short: goals and tasks form a cycle, as do versions and
deployments. Nesting keeps each cycle inside one package boundary instead of
creating a bidirectional dependency between two packages.

## 5. Every file in the repository

Created this session. Nothing pre-existed; the directory was empty.

Root:

- `CONSTITUTION.md`
- `README.md`
- `pyproject.toml`
- `.gitignore`

Documentation:

- `docs/adr/ADR-0001-local-first-stack.md`
- `docs/architecture.md`
- `docs/backlog.md`
- `docs/lifecycle.md`
- `docs/session-handoff.md` (this file)

Package:

- `astra/__init__.py`
- `astra/core/__init__.py`, `contracts.py`, `controller.py`, `lifecycle.py`
- `astra/memory/__init__.py`, `contracts.py`, `vector.py`, `schema.py`,
  `sqlite_store.py`, `errors.py`
- `astra/knowledge/__init__.py`, `contracts.py`
- `astra/planning/__init__.py`, `contracts.py`, `goals.py`
- `astra/reasoning/__init__.py`, `backends.py`, `llama_cpp_backend.py`
- `astra/research/__init__.py`, `contracts.py`
- `astra/coding/__init__.py`, `contracts.py`
- `astra/testing/__init__.py`, `contracts.py`
- `astra/evaluation/__init__.py`, `contracts.py`
- `astra/deployment/__init__.py`, `contracts.py`, `versioning.py`
- `astra/security/__init__.py`, `contracts.py`
- `astra/plugins/__init__.py`, `contracts.py`
- `astra/tools/__init__.py`, `contracts.py`
- `astra/configuration/__init__.py`, `settings.py`, `loader.py`, `paths.py`, `errors.py`
- `astra/logging/__init__.py`, `setup.py`

Tests:

- `tests/__init__.py`
- `tests/test_architecture.py`
- `tests/test_configuration.py`
- `tests/test_logging.py`
- `tests/test_interfaces.py`
- `tests/test_offline.py`
- `tests/test_memory.py`
- `tests/test_reasoning.py`

Files modified after first creation, during the same session:

- `astra/configuration/loader.py` — simplified the boolean handling in
  `_coerce`, because `bool` is a subclass of `int` and an integer field would
  otherwise have accepted `true`.
- `docs/architecture.md` — added the rule permitting same-layer contract
  imports, which the Deployment Agent relies on.
- `tests/test_configuration.py` — removed a stray line left in
  `test_defaults_are_local`.
- `tests/test_offline.py` — hoisted a file read out of an inner loop.

## 6. ADR-0001 decisions, in brief

Read the ADR itself before changing any of this. Summary only:

- **D1.** SQLite is the source of truth. The vector index is derived and
  disposable. This is the load-bearing decision: it is why an embedding model
  upgrade costs a rebuild rather than data loss.
- **D2.** Python 3.11+.
- **D3.** SQLite over PostgreSQL. Single user, single process, no daemon,
  backup is a file copy. Access confined behind `MemoryStore`.
- **D4.** FAISS (`faiss-cpu`) for the vector index. ChromaDB rejected: its
  default embedding function downloads a model from HuggingFace on first use,
  a silent network dependency, plus a heavy dependency tree and repeated
  breaking API changes. `sqlite-vec` retained as the documented alternative;
  not chosen because it is early-version with a small maintainer base.
- **D5.** Ollama as the default runtime, `llama-cpp-python` as the second
  implementation. Both run GGUF locally; Ollama installs cleanly on Windows and
  detects GPUs without a build toolchain. Both sit behind `LLMBackend` and
  `EmbeddingBackend`.
- **D6.** No network I/O at runtime. Models may be provisioned offline by
  importing a local GGUF file through a `Modelfile`.

Deferred inside the ADR: exact index type (`Flat` versus `HNSW`), pending
corpus measurements; reranking model choice.

## 7. Dependencies

Declared in `pyproject.toml`, **none installed**, none imported anywhere in the
repository:

| Package | Purpose | Licence | Offline |
|---|---|---|---|
| `llama-cpp-python` | in-process GGUF inference and embeddings, no transport (ADR-0004). Imported lazily; the suite passes without it | MIT | yes |
| `numpy` | vector arrays | BSD-3 | yes |
| `faiss-cpu` | vector index | MIT | yes |
| `pydantic` | config and record validation | MIT | yes |
| `pytest` (dev extra) | test runner | MIT | yes |

The scaffold and its tests run on the standard library alone. This is enforced
by `tests/test_offline.py::DependencyTest`, which scans every module's source
for `import httpx`, `import numpy`, `import faiss`, and `import pydantic`.

## 8. Tests

Command:

```
python -m unittest discover -s tests -t . -v
```

Result at time of writing: **94 tests, 0 failures, 0 errors**, roughly 0.8
seconds. `pytest` also collects the suite but is not installed and not needed.

What each file guards:

- `test_architecture.py` — every expected package imports; no undocumented
  top-level package appears; the two nested managers exist; memory and
  knowledge category enums match the constitution exactly; lifecycle stage
  order is as written; human approval precedes deployment.
- `test_configuration.py` — defaults, TOML loading, unknown key and unknown
  section rejection, wrong-type rejection, missing explicit file, environment
  variable resolution, malformed TOML, remote host rejection, LAN address
  rejection, approval cannot be disabled.
- `test_logging.py` — file output, no handler stacking on reconfiguration, the
  root logger is untouched, null handler when both sinks are off, level
  applied, logger name re-parenting, standard library `logging` unshadowed.
- `test_interfaces.py` — every contract is abstract, cannot be instantiated,
  and each abstract method raises `NotImplementedError` when invoked. This is
  what makes "no fake implementations" a checked property rather than a claim.
- `test_memory.py` — the twelve-point plan from ADR-0003, expanded to 27 tests:
  schema version and idempotent `initialise`, round trip with timezone-aware
  timestamps and tags, deduplication including whitespace and case behaviour,
  the verified-record rule proved against raw SQL as well as through the store,
  streaming iteration, cascade deletion, search in both filtered and limited
  forms, and survival across reopening the database.
- `test_reasoning.py` — scans every module under `astra/reasoning/` for network
  imports and for process-spawning call sites, then exercises the llama.cpp
  adapters against a fake binding: option pass-through including seeds, single
  load with reuse, reload after unload, CPU default for embeddings, refusal of
  token-level embeddings, and clear errors when the binding or model file is
  absent.
- `test_offline.py` — imports every module with `socket.socket`,
  `socket.create_connection`, and `socket.getaddrinfo` replaced by raising
  stubs; default reasoning host is loopback; outbound network off by default;
  no third-party imports in the scaffold.

## 9. Architectural rules currently enforced by code

These are not documentation-only. Breaking them fails a test or raises at
runtime.

1. **No network transport in the inference path.** ADR-0004 replaced the older
   loopback rule with a stronger one: `ReasoningSettings` has no host, port, or
   URL field at all, and `tests/test_reasoning.py` fails the build if any module
   under `astra/reasoning/` imports a network library or spawns a process.
2. **Human approval cannot be switched off.** `SecuritySettings` raises when
   `require_human_approval` is false.
3. **Approval is structurally required for deployment.**
   `DeploymentAgent.deploy()` takes an `ApprovalRecord` as a required
   parameter. No overload omits it.
4. **Security may never unilaterally allow a permanent change.**
   `SecurityAgent.check_permanent_change()` documents that only
   `REQUIRES_APPROVAL` or `DENY` are valid answers.
5. **Truth and derived data must not share a path.** `MemorySettings` rejects
   equal `database_path` and `vector_index_path`.
6. **Unknown configuration keys are errors**, not ignored input.
7. **No import-time network access**, enforced across the whole package tree.
8. **No third-party imports** while the scaffold remains dependency-free.
9. **Module map is fixed.** Adding an undocumented top-level package fails
   `test_architecture.py`.

Rules that are documented but *not* yet machine-checked, because the code they
would govern does not exist: verified knowledge never overwritten by unknown
information; no duplication of verified knowledge; classification before
storage. These belong to B-001 and B-004 and must be enforced inside the store,
not in its callers.

## 10. Dependency direction

```
core → agents → engines → extension → foundation
```

- `configuration` and `logging` import nothing from `astra`. They are leaves.
- Same-layer imports are permitted for contracts only. The Deployment Agent
  imports `ChangeProposal`, `TestReport`, and `EvaluationReport` because its
  approval gate is defined in terms of them.
- Only the Core Controller may construct concrete implementations.

## 11. Assumptions on record

1. ~~Hardware is unmeasured.~~ Resolved by B-008 on 2026-08-12. Measurements and
   consequences are in ADR-0002. The machine has a CUDA GPU with 4 GiB of VRAM,
   which is now the binding constraint on model size.
2. Corpus size assumed within one to two orders of magnitude of 10^5 chunks.
   Untested; drives the choice of exact versus approximate search.
3. Single user, single machine, single concurrent writer. This is what makes
   SQLite sufficient.
4. Ollama can be provisioned offline via a local GGUF file and a `Modelfile`.
   Documented in the ADR, not yet exercised on this machine.
5. Default embedding dimension is configured as 768, matching
   `nomic-embed-text`. Not verified against a running model.
6. The configured default chat model, `llama3.1:8b`, was chosen before
   measurement and does not fit 4 GiB of VRAM. Left unchanged deliberately;
   replacing it belongs to B-002, where it can be measured.

## 12. Backlog

Full detail in `docs/backlog.md`. All entries are `todo`; none started.

| ID | Title | Priority | Depends on |
|---|---|---|---|
| B-001 | Memory Engine: SQLite schema and store | P0 | **done 2026-08-12, ADR-0003** |
| B-002 | Local reasoning runtime and models, five gated stages | P0 | **stage 1 done 2026-08-12, ADR-0004** |
| B-003 | Vector index adapter over FAISS | P1 | B-001, B-002 |
| B-004 | Knowledge Engine: classification before storage | P1 | B-001, B-002 |
| B-005 | Adopt pydantic in the Configuration Manager | P2 | pydantic installed |
| B-006 | Planning Engine and Goal Manager | P2 | B-001 |
| B-007 | Core Controller wiring | P2 | B-001, B-002, B-003 |
| B-008 | Measure the host hardware | P1 | **done 2026-08-12, ADR-0002** |
| B-009 | First agent implementation (Research Agent) | P3 | B-001, B-002, B-004 |

## 13. Unresolved decisions

1. **Index type.** `IndexFlatIP` versus `IndexHNSWFlat`. Deferred until corpus
   size is known. Start exact.
2. **Embedding model.** `nomic-embed-text` is the configured default but has
   not been benchmarked against alternatives on this hardware.
3. **Chat model.** `llama3.1:8b` remains the placeholder default in
   configuration and is known to be wrong: ADR-0002 shows it does not fit 4 GiB
   of VRAM. Which 3B-class model replaces it is undecided and must be measured
   during B-002.
4. **Memory schema shape.** Table layout, identifier scheme, and migration
   strategy are undesigned. Must be settled at the start of B-001, since
   migrations get expensive once data exists.
5. **Chunking strategy** for embedding long documents. Untouched.
8. **Embedding pooling.** The stage 1 adapter refuses token-level embeddings
   rather than averaging them silently. ADR-0005 must either choose a model that
   returns pooled vectors or choose a pooling rule.
6. **Reranking.** Whether to rerank retrieval results, and with what.
7. **Whether the approval gate needs a durable record store**, or whether an
   `ApprovalRecord` passed in at call time is sufficient.

## 14. Known technical debt

1. `astra/configuration/loader.py::_coerce` infers expected types from default
   values. Adequate for four flat sections; wrong if settings ever nest or use
   optional types. Rewrite when B-005 introduces pydantic.
2. Configuration uses `dataclasses` while ADR-0001 D-register names pydantic.
   Deliberate, so the repository runs with nothing installed. Tracked as B-005.
   The ADR was not edited to match the implementation.
3. No dependency-register lint. A new third-party import would be caught by
   `test_offline.py` only while the forbidden list is maintained by hand.
4. No type checker configured. Annotations exist throughout but nothing
   verifies them. Not yet worth a dependency.
5. `tests/test_offline.py` mutates `sys.modules` and patches `socket`. Correct
   but invasive; it must stay isolated from other tests that import `astra`.
6. Log directory creation happens inside `configure_logging`, mixing a
   filesystem side effect into configuration. Acceptable now, worth splitting
   if startup order becomes complex.
7. The `LIKE` search fallback in `SQLiteMemoryStore` is written but never
   executed on this machine, because SQLite 3.45.3 here has FTS5. Untested code
   on a path that only appears on a different build.
8. `SQLiteMemoryStore` holds one connection and is not thread-safe. Correct for
   the single-process design, but it will need attention if the Core Controller
   ever runs agents concurrently.
9. Search ranking is FTS5's default `rank` (BM25). Chosen for lack of data
   rather than by measurement; ADR-0003 leaves the decision open.
10. `store()` performs several statements per call with no batch path. Fine for
    interactive use, slow for a bulk import of thousands of records.
11. ~~The llama.cpp adapters have never run against a real GGUF file.~~ Resolved
    by B-002 stage 4. Adapters verified against real GGUF files. Timings dict
    from `create_chat_completion` is empty on this build (speed was measured
    from wall time). The `timings_raw: {}` in benchmark results is expected.
12. ~~`seed` per call unverified against a real install.~~ Resolved. `seed` is
    passed and accepted; benchmark used `seed=42` throughout.
13. Generation has no timeout or cancellation. In-process inference cannot be
    interrupted by closing a socket, so a long generation blocks its caller
    until it finishes.
14. `from_settings` takes `Any` rather than `ReasoningSettings`, to keep the
    reasoning package from importing configuration types. Loses type checking at
    that boundary; revisit if a type checker is adopted.
15. **CUDA PATH dependency (new, B-002 stage 3/5).** llama-cpp-python cu124
    on this machine requires `cublas64_12.dll` and `cudart64_12.dll` in the
    process PATH at startup. These are not in system PATH — they live in the
    Anaconda Chamber torch environment. Workaround: prepend that directory to
    PATH before launching. Not yet automated; must be resolved before Astra
    can start inference reliably. Options: (a) install CUDA Toolkit
    standalone; (b) write a startup shim that patches PATH; (c) switch to a
    wheel that bundles CUDA runtime. No ADR yet — small enough to fix without
    one, but must not be forgotten.
16. **Embedding model not benchmarked (outstanding stage 4 item).** nomic-
    embed-text-v1.5 GGUF was not downloaded or tested. Its pooling behaviour,
    actual dimension, and CPU throughput are unverified. B-003 depends on this
    being resolved before embedding provenance can be written.

## 15. Exact next action (as of 2026-08-14)

**B-002 is done. Next: B-003 — Vector index adapter over FAISS.**

B-002 pipeline — all stages complete:

```
B-008 hardware (done 2026-08-12)
   ↓
ADR-0004 runtime architecture, no transport (accepted)
   ↓
stage 1  in-process llama.cpp backends (done 2026-08-12)
   ↓
ADR-0005 model selection, provisional (done 2026-08-14)
   ↓
stage 3  user installed llama-cpp-python cu124 + GGUF files (done 2026-08-14)
   ↓
stage 4  benchmark on this machine (done 2026-08-14)
   ↓
stage 5  production default set, settings.py updated (done 2026-08-14)   ← here
   ↓
B-003  vector index adapter over FAISS                                    ← next
```

**Production configuration now in effect:**

```toml
[reasoning]
model = "Qwen2.5-3B-Instruct-Q5_K_M"
context_tokens = 4096
gpu_layers = 99
# model_path = "/path/to/qwen2.5-3b-instruct-q5_k_m.gguf"  # set this
```

**To activate inference:** set `model_path` (and optionally
`embedding_model_path`) in `astra.toml` or via `ASTRA_CONFIG`. Astra never
downloads models. Add torch lib to PATH before starting (see CUDA debt below).

Intended order: B-003 (FAISS adapter), then B-004 (Knowledge Engine).
B-005 (pydantic config) can happen whenever pydantic is installed.

## 16. How to resume

1. Read `CONSTITUTION.md`, then the ADRs in order (ADR-0001 with D5 superseded,
   ADR-0002 hardware, ADR-0003 memory schema, ADR-0004 runtime), then
   `docs/architecture.md`.
2. Run `python -m unittest discover -s tests -t . -v` and confirm 94 passing
   tests before changing anything.
3. Pick up B-002 stage 2, one milestone at a time, stopping for review before
   the next.
4. Do not rewrite existing modules. Extend them.
5. Do not modify the constitution or ADR-0001 to accommodate an implementation
   shortcut. If a decision turns out to be wrong, supersede it with a new ADR
   and say why.
