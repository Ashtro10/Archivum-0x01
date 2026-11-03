# AEE Unified Roadmap

This roadmap replaces the convergence roadmap's open items (M7 computer use, M9 change pipeline, M10 remainder) and the
initial A–I hypothesis.

## 1. What changed from the A–I hypothesis, and why

| Hypothesis | Final | Reason |
|---|---|---|
| — | **E0 Foundation gate** (new) | The AEE must not start on an unverified baseline. Milestone 1 is unfinished, and nothing has run on the Windows PC. The coding-model question can only be answered there. |
| A Self-awareness + B Observation | **E1 Self-knowledge** (A and B merged; the dev-knowledge store moves here) | Both are read-only, low-risk and share the same new store (`evolution.db`). Reconciliation needs both the index and the repository monitoring. |
| E Development, F Verification, G Deployment | **E2 Safe change substrate** (F and G plus the sandbox and workspaces from E come **before** any autonomous coding) | A judge and a safety net must exist before a coder. Every later milestone depends on them. |
| C Diagnostics + E coding | **E3 Diagnose and repair** | Repair is the narrowest and best-measured use of coding: a confirmed cause, a repro test, small diffs. |
| D Research and planning | **E4 Goals and research** | Planning without the ability to deliver (E2, E3) produces backlog noise |
| H Continuous evolution | **E5 Continuous evolution** | The scheduler is only useful once tasks exist |
| I Capability expansion | **E6 Capability expansion** (open-ended, no fixed scope) | It uses the system; it is not part of building it |

## 2. Autonomy gates (measured, not declared)

| Gate | Unlocks | Measurement |
|---|---|---|
| G0 Coding competence | E3 code writing; L2 | **CLU-bench**: at least 20 historical CLU defects reconstructed from Git history, each with its fixing commit's tests as the oracle and the pre-fix commit as the start. Pass threshold proposed: ≥40 % solved with zero test weakening. This is an owner decision (§5). |
| G1 Safe repair | L3 (auto-deploy fixes) | ≥10 consecutive E3 fixes reviewed by the owner and accepted with no post-deploy regression; rollback drill 5/5 |
| G2 Safe improvement | L4 | 30 days at L3 with no unrecovered incident and a regression rate under 5 % of deployments; ≥3 owner-accepted improvements with measured benefit |

## 3. Milestones

### E0 — Foundation gate

* **Objective:** a trustworthy baseline and real hardware facts.
* **Reuse:**
  * MS1 work (`1fd8d98`, perf harness);
  * the M1 benchmark pack;
  * the installer and the M2 host.
* **New:** nothing architectural. The work is:
  * finish MS1: the warm perf run, its four documents, and a green `./gradlew check` plus Playwright;
  * the owner runs `bench/Run-CluBenchmark.ps1` on the Ryzen AI 7 350;
  * a Windows smoke run of the installer, host and shell;
  * a G0 *feasibility* probe: run 3 CLU-bench tasks with 1–2 candidate coding models on the PC.
* **Dependencies:** owner time on the PC.
* **Research:** R9, R11.
* **Resources:** benchmarks take minutes; coding-model probes need up to roughly 9 GB peak (estimate).
* **Security:** none new.
* **Tests:** the existing suites; the benchmark JSON committed.
* **Acceptance criteria:**
  * full check green on `main`-equivalent;
  * PC benchmark results committed;
  * a measured statement of the best coding model that fits within 8 GB, and its probe outcome.
* **Completion:** all three artefacts exist in `docs/measurements/`.

### E1 — Self-knowledge (Architecture Intelligence + Observer + Knowledge)

* **Objective:** CLU knows its own code, notices external changes, and distinguishes normal behaviour from faults.
  It does not modify anything.
* **Reuse:**
  * `DiagnosticsLog`, `RequestTrace`, `perf_samples`, `MemoryHealth`, `ResourceGovernor` probes;
  * the settings registry generator;
  * the `MaintenanceScheduler` pattern;
  * the `SqlDriver` migration framework;
  * `ImprovementAdvisor` as the first rule set.
* **New:**
  * `evolution.db` v1;
  * the Git CLI adapter (read-only);
  * the indexer (inventory, Gradle graph, lexer symbols, imports, schema, config and API maps, test-name map);
  * reconciliation with provenance;
  * the Observer (baselines, anomaly rules, incidents, CPU/disk/crash probes);
  * knowledge items, with a backfill from `docs/` (the measurement JSONs become `benchmark_runs`; documented defects
    become `bug`/`fix` items, labelled `imported-from-docs`);
  * an "Ask CLU about itself" conversation route that answers from the index with file and line citations;
  * a Status → Self page.
* **Dependencies:** E0.
* **Research:** R1, R5, R12, R16.
* **Resources:** a full index under 2 min and 200 MB, incremental updates under 5 s; Observer under 1 % CPU (targets,
  measured in acceptance).
* **Security:** read-only repository access; the repository path is configured by the owner; no network.
* **Tests:**
  * index-accuracy tests on a fixture repository;
  * the real repository: indexed file count = `git ls-files` count for supported types;
  * Gradle edges equal to declared dependencies;
  * reconciliation tests (add, modify, delete, rename, dependency, schema, API change fixtures; uncommitted changes
    untouched);
  * anomaly-rule tests on synthetic series (no incident from a single spike, incident from a sustained shift);
  * redaction tests;
  * resource tests.
* **Acceptance criteria:**
  * 100 % of tracked `.kt` / `.kts` / `.sq` / `.json` / `.js` files indexed;
  * module-graph edges match Gradle exactly;
  * symbol extraction ≥95 % precision and recall against a hand-checked sample of 200 declarations;
  * a Claude Code commit on the owner's branch is reconciled within 15 min with correct categories, 10/10 fixture
    cases;
  * a false-positive incident rate under 1 per week over a 7-day soak on the PC;
  * questions such as "which tests cover the PermissionBroker?" are answered correctly in 9/10 checks.
* **Completion:** shipped behind L1, on by default after owner opt-in.

### E2 — Safe change substrate (workspaces, sandbox, verification, Supervisor, deployment)

* **Objective:** any change, whoever wrote it, can be built, verified, evaluated, deployed and rolled back
  automatically and safely. **The owner's own and Claude Code's branches benefit immediately:** CLU can verify and
  stage them on request.
* **Reuse:**
  * Gradle, the test suites, Playwright, `verify_installer.sh`, the three benchmark harnesses;
  * `DatabaseBackup`, transactional migrations;
  * the installer and launcher;
  * the M7 broker and audit;
  * the Primus pipeline *rules* (ported as tests).
* **New:**
  * the Supervisor (a separate process): policy custody and signature, version slots, activation, heartbeat
    watchdog, crash-loop rollback, `--safe`;
  * changes to the launcher and installer for slots (an owner-confirmed, trust-critical change done by a human-led
    session);
  * the sandbox account and Job Object helper;
  * the workspace manager (Git worktrees);
  * the verification runner with result parsers, JaCoCo, a diff-only detekt/ktlint, the secret scan, an OSV check
    and the schema compatibility gate;
  * the evaluation engine (A/B benchmarks with statistics, complexity deltas);
  * deployment and rollback records;
  * the kill switch on PC and phone;
  * the autonomy level UI.
* **Dependencies:** E1 (the test map and knowledge), E0 (Windows facts).
* **Research:** R13, R14, R17, R18.
* **Resources:** verification at up to 3 GB and 50 % CPU, suspendable; slots at ~300 MB each, keep 3.
* **Security:** Ring 0/1 implemented; the trust-critical path list; network blocked for the sandbox; the
  owner-confirmation flow for the listed operations.
* **Tests:**
  * a rollback drill: deploy a deliberately crashing build, and the previous version must be active and serving
    within 2 min, 5/5;
  * expand-migration compatibility, with the old binary on the new schema;
  * a destructive migration is refused without confirmation;
  * the sandbox cannot read `%LOCALAPPDATA%\CLU`, the keystore or the user profile, or reach the network (negative
    tests);
  * policy tampering by the host is rejected;
  * a test-weakening diff is flagged;
  * a flaky test is detected;
  * the "empty evidence is never a pass" rule (from Primus).
* **Acceptance criteria:**
  * an owner-written commit is verified, evaluated and staged end to end by CLU, then deployed on approval and rolled
    back on request with no data loss;
  * a crash-looping version auto-rolls back with no owner action;
  * all negative sandbox tests pass on Windows.
* **Completion:** the pipeline is used for one real owner change in daily operation.

### E3 — Diagnose and repair (Diagnostics engine + Development engine for fixes)

* **Objective:** CLU investigates real faults to a confirmed cause and produces verified repairs.
* **Reuse:** E1 and E2, M7 orchestrator, tools and roles, `InferenceService`, the knowledge store.
* **New:**
  * the investigation state machine;
  * evidence collectors;
  * SBFL from coverage;
  * automatic `git bisect run`;
  * hypothesis and experiment records;
  * the Agentless-style repair loop with search/replace edit tools and a component-scoped edit guard;
  * the verifier-role review;
  * duplicate and recurrence detection (Workflow 6);
  * CLU-bench as a permanent regression benchmark of the AEE itself.
* **Dependencies:** E2, and gate G0 to enable code writing. Without G0, E3 runs investigations to `Confirmed` and
  hands the repair proposal to the owner or Claude Code.
* **Research:** R2, R3, R10, R11, R12, R15, R19.
* **Resources:** the coding model only in idle windows, ≤8 GB AEE cap.
* **Security:** edits only in worktrees, inside the declared components; trust-critical paths escalate.
* **Tests:**
  * seeded-fault suite: inject 20 known faults into a sandbox copy, and require ≥80 % detection by the Observer and
    tests, and ≥60 % correct localisation in the top 3;
  * a confirmed-vs-hypothesis distinction test (no repair without a confirmed or localised cause);
  * the loop budget is enforced;
  * a recurrence is linked;
  * the edit guard refuses out-of-scope files.
* **Acceptance criteria:**
  * G0 passed;
  * on 10 real or seeded incidents, every investigation ends `Confirmed` with a reproducing test, or `Parked` with
    evidence, and none ends with an unexplained patch;
  * G1 progress tracked.
* **Completion:** L2 (propose) is available to the owner; L3 becomes available after G1.

### E4 — Goals and research (Planning + Research)

* **Objective:** CLU proposes worthwhile, measurable improvements and researches them responsibly.
* **Reuse:**
  * the conversation research stack (pinned fetcher, evidence, untrusted wrapping);
  * the M7 planner;
  * memory proposals UX for owner approval;
  * E1 capability and debt signals.
* **New:**
  * goal store, priority model, quotas and duplicate detection;
  * the repeated-difficulty detector (counts only);
  * engineering sources (arXiv API, repository metadata, LICENSE detection to SPDX, official-docs cache);
  * research records with grades;
  * a design-record step for features;
  * the post-deploy usefulness counter and removal proposals.
* **Dependencies:** E3 (delivery) and E2.
* **Research:** R4, R6, R7, R8, R20.
* **Resources:** research ≤30 min per goal; network only through the allow-list.
* **Security:** domain allow-list; license allow-list; prompt-injection tests on research pages instructing tool use.
* **Tests:**
  * a goal without a metric cannot activate;
  * quotas are enforced;
  * a frozen goal is not retried without new evidence;
  * license detection fixtures (MIT, Apache, AGPL, missing);
  * speculation is never stored as a finding;
  * injection pages have no effect.
* **Acceptance criteria:**
  * over a 30-day trial, the owner rates ≥70 % of proposed goals as worthwhile;
  * zero goals without completion criteria;
  * every cited source resolves and its license matches its repository's LICENSE file in 20/20 checks.
* **Completion:** planning and research run at L2 or above.

### E5 — Continuous evolution (Scheduler)

* **Objective:** useful work happens in idle time without the owner noticing any cost.
* **Reuse:** `MaintenanceScheduler`, the governor, the window-aware full-screen detection, Job Objects (from E2).
* **New:**
  * idle detection (`GetLastInputInfo`, turns, voice);
  * power, thermal, disk and working-hours gates;
  * backlog selection;
  * checkpointed resumable steps;
  * preemption with build suspend and low-priority inference cancel;
  * chat-model pre-warm on return;
  * an evolution digest in the morning (PC and phone).
* **Dependencies:** E2–E4.
* **Research:** R16.
* **Resources:** enforced budgets (spec §K).
* **Security:** inherits; the kill switch stops within 1 s.
* **Tests:**
  * a user-activity preemption latency test (under 1 s to pause, chat TTFT unaffected compared with the AEE off,
    within noise);
  * a resume after a host restart;
  * no start in the last 10 min of the window;
  * thermal and disk stop;
  * a 7-day soak on the PC: no user-visible slowdown reported, and a game or benchmark score with the AEE armed
    within 3 % of disarmed.
* **Acceptance criteria:** all of the above on the owner's PC, plus G2 tracking.
* **Completion:** the owner leaves L3 (or L4 after G2) on for 30 days.

### E6 — Capability expansion

* **Objective:** use the established loop to improve intelligence, memory, tools and efficiency. Examples, each to
  become an E4 goal with metrics:
  * an embedding model for memory and knowledge retrieval (the M10 remainder);
  * the archive sync gap;
  * Android approval UI;
  * voice task control;
  * faster first text.
* **Reuse:** everything.
* **New:** per goal.
* **Dependencies:** E5 and G2 for autonomous delivery.
* **Acceptance criteria:** per goal, by the evaluation records.
* **Completion:** ongoing; reviewed quarterly against the metrics in §4.

## 4. Success metrics (tracked from E1 onward)

| Area | Metric | Target (proposed) |
|---|---|---|
| Architecture awareness | Files indexed / tracked | 100 % |
| | Gradle dependency mapping accuracy | 100 % |
| | Changed-component identification on reconciliation fixtures | ≥95 % |
| | Doc drift reports confirmed by the owner as real | ≥80 % |
| Diagnostics | Fault detection latency (crash, service down) | ≤2 probe intervals |
| | Perf-regression detection | ≤1 day |
| | Root-cause accuracy (confirmed causes still valid 30 days later) | ≥90 % |
| | Reproduction success on reproducible seeded faults | ≥80 % |
| | False-positive incidents | <1 per week |
| Development | CLU-bench solve rate | ≥40 % (G0); tracked per model |
| | Build success of submitted candidates | tracked |
| | Regression rate after deploy | <5 % |
| | Rollback success | 100 % |
| Improvement quality | Verified perf improvements, defects fixed, capabilities added (with evidence links) | counted |
| | Redundant changes avoided (duplicates caught) | counted |
| | Resource efficiency (idle RSS, model RAM) not worse release over release | — |
| Autonomy | Tasks completed without the owner | counted |
| | Tasks safely paused or resumed | counted |
| | Approval requests limited to owner-confirmation classes | 100 % |
| Reliability | Main app availability | ≥99.5 % of PC uptime |
| | Data-loss events | 0 |
| | Background CPU and RAM within budget | 100 % of samples |

These metrics measure engineering capability only. They do not demonstrate understanding or consciousness, and the
documentation must not claim otherwise.

## 5. Mapping from earlier milestone items

| Earlier item | Goes to |
|---|---|
| Convergence M1 PC benchmark (unrun) | E0 |
| M2/M3/M6 Windows verification | E0 |
| Convergence M9 change pipeline | E2 and E3 |
| Primus coding, testing, evaluation and deployment (legacy) | E2 (rules ported as tests) |
| `ImprovementAdvisor` | E1 Observer rules |
| M7 broker, orchestrator and tools | Reused by E2 and E3; the trust anchor moves to the Supervisor in E2 |
| Convergence M7 Windows computer use | Deferred (not needed for evolution) |
| Convergence M10 embeddings and import | E6 candidate goals |
| Android approvals, voice → tasks (M7 gaps) | E2 (kill switch and confirmations on the phone) and E6 |
