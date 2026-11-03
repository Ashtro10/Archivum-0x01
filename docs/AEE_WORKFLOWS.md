# AEE End-to-End Workflows (proposed)

Component letters refer to `AEE_ARCHITECTURE_SPEC.md` §4: A Observer, B Architecture Intelligence, C Diagnostics,
D Research, E Planning, F Development, G Build/Test, H Evaluation, I Deployment, J Knowledge, K Scheduler, and the
Supervisor (SUP).

---

## Workflow 1 — External development reconciliation

Trigger: the owner changes CLU through Claude Code, an IDE, or `git pull`.

```mermaid
sequenceDiagram
  participant Owner as Owner / Claude Code
  participant Repo as Owner repo
  participant B as B Arch Intelligence
  participant G as G Build/Test
  participant J as J Knowledge
  participant F as F Dev tasks
  Owner->>Repo: commits / edits
  B->>Repo: periodic or on-start check (HEAD, status, branches)
  B->>B: diff last reconciled snapshot → name-status -M
  B->>B: classify (dependency, config, schema, API, architecture)
  B->>B: re-index changed blobs; recompute edges; mark test maps stale
  B->>J: reconciliation record + architecture-change knowledge item (if structural)
  B->>F: affected AEE tasks → rebase check
  alt rebase clean
    F->>G: re-verify task branch on new base
  else conflict
    F->>F: abandon or re-plan task (never touch owner files)
  end
  B-->>G: (idle) refresh coverage map for stale components
```

* **Data:** `repo_snapshots`, `source_files` (provenance `external`), `symbols`, `dependency_edges`, `test_mappings`
  (stale), `reconciliations`, `knowledge_items` (`architecture-change`).
* **Errors:**
  * Repository unavailable or locked (`index.lock`): retry with backoff and report after 3 failures.
  * Unparseable file: indexed as text-only with `parse_error`, no crash.
  * Huge change (more than 500 files): full re-index scheduled for idle time; queries return `stale` meanwhile.
* **Recovery:** the index is derived, so a corrupt index is dropped and rebuilt from Git. Uncommitted owner changes
  are read-only for the AEE and indexed provisionally.
* **Never:** revert, "correct", or flag an external change as malicious; write in the owner's working tree; switch
  the owner's branch.

## Workflow 2 — Automatic failure repair

```mermaid
sequenceDiagram
  participant A as A Observer
  participant C as C Diagnostics
  participant J as J Knowledge
  participant D as D Research
  participant F as F Development
  participant G as G Build/Test
  participant H as H Evaluation
  participant I as I Deploy + SUP
  A->>C: incident (signature, evidence ref)
  C->>J: lookup signature / components
  alt known & fixed in a later version
    C->>C: link duplicate, close
  else new or recurring
    C->>C: evidence → localize (B) → reproduce (repro test / bisect)
    C->>C: hypotheses → discriminating experiments → Confirmed | Parked
  end
  C->>D: (optional) research known fixes for confirmed cause
  C->>F: repair proposal → goal (E auto-approved if L≥2 and policy allows)
  F->>F: worktree from last verified commit; patch (≤3 candidates × ≤4 iterations)
  F->>G: build, targeted tests, full suite, static, security, compat
  G->>H: results + coverage (repro fails before, passes after)
  H->>H: verdict
  alt improved & L3 & fix-class & not trust-critical
    H->>I: deploy request → staging → activate → watch
  else
    H->>Owner: verified branch awaiting review (L2) / confirmation
  end
  I->>J: deployment record; knowledge: root cause + fix
```

* **Error conditions:**
  * The cause is not reproducible: `Parked`, with the evidence kept and the Observer told to capture more context
    (for example, raise the log level for that component temporarily, within budget).
  * All candidate patches fail: the attempt budget is spent, `failed-attempt` knowledge is written, and the goal
    is frozen.
  * Build infrastructure failure (out of disk, Gradle daemon crash): the task is paused, not failed. A resource
    incident is opened.
  * The user becomes active: the scheduler pauses the task (§K).
* **Recovery:** the worktree persists, and the task resumes from its checkpoint. A failed deployment follows
  Workflow 5.

## Workflow 3 — Autonomous feature development

1. **Gap identified (E).** Sources:
   * an owner request ("CLU, learn to …");
   * a repeated-difficulty signal (the same unmet intent class ≥3 times in 14 days, counted from conversation
     outcome labels, never raw text);
   * a capability marked `missing` in `capabilities`.
2. **Goal created** with metric, target and completion criteria. The acceptance check is written **before** design.
   Self-originated feature goals respect the weekly quota and need owner origin or repeated-difficulty evidence.
3. **Priority** computed and logged. At L2/L3 the goal waits for owner approval; at L4 it is auto-approved within
   policy.
4. **Research (D)** bounded to the goal: at most 10 sources and a 30-minute budget, with findings graded and
   licensed.
5. **Design (F, planner role).** A short design record covers affected components (B), the interface, a test plan,
   resource impact, and a schema plan (expand-only). Trust-critical components → owner confirmation.
6. **Code (F)** in a worktree, with tests first (the acceptance test fails before).
7. **Verify (G)**, **evaluate (H)** including usefulness and complexity.
8. **Deploy (I).** L4 auto-deploys through staging. Otherwise the change is presented for review.
9. **After deployment:** a usage counter (count only) for 30 days. A feature that is unused, and not owner-requested,
   is proposed for removal: an anti-bloat check.

* **Errors:**
  * Research finds only incompatible licenses: the goal is marked `blocked: license` and the owner is told.
  * The design needs a new dependency: owner confirmation.
  * The design crosses the diff limit: the goal is split, or escalated to the owner.

## Workflow 4 — Idle-time evolution

```mermaid
flowchart TD
  T[Scheduler tick every 5 min] --> L{Autonomy ≥ L1 and policy hours?}
  L -- no --> S[sleep]
  L -- yes --> I{Idle ≥10 min, no fullscreen, AC power, temps OK?}
  I -- no --> S
  I -- yes --> R{Budget: RAM avail ≥ need, disk ≥10 GB, evolution quota OK?}
  R -- no --> S2[record skip reason] --> S
  R -- yes --> P{Paused task to resume?}
  P -- yes --> RES[resume from checkpoint]
  P -- no --> B[Backlog: highest priority approved goal/task not frozen, not recently failed, dependencies met]
  B --> CH{Fits remaining window?}
  CH -- no --> SM[pick smaller task (index refresh, coverage refresh, investigation step)]
  CH -- yes --> RUN[run step: load coding model if needed (governor check)]
  RES --> RUN
  SM --> RUN
  RUN --> CP[checkpoint after each step]
  CP --> U{User active / budget hit / window end?}
  U -- yes --> PA[pause: suspend build Job Object, cancel low-priority inference, unload coding model] --> S
  U -- no --> RUN
```

* **Data:** `dev_tasks.checkpoint`, `outbox`, and the scheduler's skip reasons in `health_observations` (as a metric
  `aee_skip{reason}`).
* **Errors:**
  * A crash mid-step: the step is idempotent (Git state and DB checkpoint), so it is re-run. A step that crashes the
    host twice is quarantined and becomes an incident.
  * Thermal or disk alarm: an immediate pause and an incident.
* **Guarantees:** a user turn preempts within 1 s; the chat model is reloaded *before* the next user turn if it was
  unloaded (the M8.5 warm-model rule); no work starts in the last 10 minutes of the configured window.

## Workflow 5 — Failed deployment

```mermaid
sequenceDiagram
  participant H as Host (new version)
  participant SUP as Supervisor
  participant OLD as Previous slot
  participant J as Knowledge
  SUP->>SUP: activate v(n): current.txt → v(n) (atomic rename)
  SUP->>H: start; expect heartbeat ≤60 s and /v1/health OK, smoke turn OK
  H--xSUP: crash / no heartbeat / health fail (3 tries in 10 min)
  SUP->>SUP: current.txt → v(n-1)
  alt schema unchanged or expand-only
    SUP->>OLD: start previous version on same clu.db
  else destructive migration (owner-confirmed earlier)
    SUP->>SUP: restore verified pre-activation backup, record data-loss window
    SUP->>OLD: start previous version
  end
  OLD->>J: (on start) read SUP log → rollback record, incident (signature = deployment failure), block retry
  OLD->>Owner: notify PC + phone: "Update v(n) failed and was rolled back"
```

* **User data:** never in the slot. Expand-only migrations mean the previous binary runs on the current DB (verified
  by the compatibility gate before activation). A restore happens only after an owner-approved destructive migration,
  and the data-loss window is reported.
* **Retry block:** `deployments.blocked_until_investigation = true`. The goal cannot re-deploy until a new
  investigation concludes with a different confirmed cause or the owner overrides.
* **If the Supervisor itself is broken:** `clu.exe --safe` starts slot 0 (the installer version) with the AEE
  disabled. A reinstall from the original installer also restores a working program, and the data folder is
  untouched (existing installer behaviour).

## Workflow 6 — Repeated failure prevention

1. Incident X occurs. Its signature is a hash of the error class, normalised top stack frames, the component, and the
   metric kind.
2. Investigation confirms cause K, and the fix is deployed. Knowledge items are written:
   * `bug` (signature to symptoms);
   * `root-cause` (K, with evidence links);
   * `fix` (commit, evaluation);
   * any `failed-attempt` rows, with the reason each failed.
3. Later, incident Y arrives with a similar signature, or touches the same component with the same error class.
4. C queries J before collecting new evidence: an exact signature match, then a component and error-class match, then
   FTS over the message.
5. Outcomes:
   * **Same signature after the fix was deployed** → a regression. Re-open the old investigation, run its repro test
     against the current version, and bisect from the fix commit. A new investigation starts from the known cause.
   * **Similar but different:** prior hypotheses and failed attempts are included in the context. The repair must
     state how it differs from failed attempts, or it is refused.
   * **Known flaky test:** route to a test-reliability goal instead of a code investigation.
6. Metrics: the redundant-investigation rate (investigations that ended as duplicates after full evidence collection)
   should fall over time.

* **Errors:** a false match (different cause, same signature) shows up when the repro test of the old fix passes. The
  incident is then split, and the signature function records a collision for tuning.

---

## Cross-cutting error handling

| Failure | Behaviour |
|---|---|
| Model unavailable or too slow | Deterministic steps continue (index, bisect, tests); model steps wait; the task pauses after a 30-minute budget |
| `evolution.db` corrupt | AEE off, Observer reports, rebuild index from Git, restore knowledge from the daily JSONL export |
| Policy unreadable or signature invalid | Fail closed: L0 until the owner re-confirms |
| Clock jumps | Durations use a monotonic clock (existing M8 practice); records keep wall time for display only |
| Owner kill switch | SUP sets L0 → the host scheduler stops within 1 s, builds are terminated, worktrees are kept |
