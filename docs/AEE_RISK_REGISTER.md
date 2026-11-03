# AEE Risk Register

Likelihood and impact: H/M/L. "Owner" names the subsystem responsible for the mitigation.

| # | Category | Risk | L | I | Mitigation | Residual | Owner |
|---|---|---|---|---|---|---|---|
| T1 | Technical | **Local models cannot produce correct patches** for a Kotlin codebase of this size | H | H | Gate G0 (CLU-bench from real past fixes) before enabling F. An optional owner-configured endpoint. The Agentless pipeline and deterministic localisation reduce the model burden. | Autonomy may stop at L1–L2 until a capable model fits | F, roadmap |
| T2 | Technical | Patch overfitting: tests pass, behaviour is still wrong | M | H | Repro test must fail before and pass after; changed-line coverage; test-weakening detection; staging soak; mutation spot-checks | Some semantic bugs escape and are caught by the Observer after deploy, then rolled back | G, H |
| T3 | Technical | The index drifts from the code or is imprecise (lexer approximations) | M | M | Blob-hash freshness, `stale` flags, `approximate` edge labels, nightly consistency check vs the Gradle graph, tree-sitter stage 2 | Low | B |
| T4 | Technical | Flaky tests (desktop tests depend on host memory and timing, as the MS1 baseline showed) produce false verdicts | H | M | Rerun policy, a flaky registry, a second clean run, flaky tests become reliability goals | Medium until flakes are fixed | G |
| T5 | Technical | The heavy custom Android build makes verification slow | H | M | Targeted tests first; Android build only when `core`/`app` changes; results cached by commit | Long feedback loops | G, K |
| S1 | Security | **Recursive self-modification weakens the safeguards** (broker, gates, tests) | M | H | Ring 0/1 outside the host; trust-critical globs need owner confirmation; gates in policy, not the repository; diff detection of test changes | Low | SUP |
| S2 | Security | Prompt injection through repository files, docs, research pages or dependency READMEs | H | H | Untrusted-data wrapping; plans fixed before reading; tools take no paths or commands from untrusted fields; policy only from Ring 0; obedient-model tests extended to AEE roles | Low-medium | broker, F, D |
| S3 | Security | Malicious or compromised dependency | L | H | Offline cache, checksum verification metadata, owner confirmation for new dependencies, OSV check, no install scripts | Low | G, policy |
| S4 | Security | Credential exposure (signing key, device keys, API keys) through logs, diffs or prompts | M | H | Sandbox account cannot read secrets; `redact` on all records; secret scan on diffs and logs; signing stays with the owner | Low | SUP, G |
| S5 | Security | Privilege escalation from the sandbox | L | H | Standard user, no admin, Job Object, no UAC paths, network blocked per account | Low | SUP |
| S6 | Security | Uncontrolled network use (exfiltration through research fetches) | M | M | Domain allow-list, the pinned fetcher, no POST bodies with repository content, rate limits, audit | Low | D, broker |
| R1 | Reliability | **A self-update leaves CLU unbootable** | M | H | Side-by-side slots, staging on a DB copy, heartbeat watchdog in the Supervisor, crash-loop rollback, `--safe` slot 0, reinstall path | Very low | I, SUP |
| R2 | Reliability | The Supervisor itself has a defect | L | H | Tiny scope, owner-only updates, extensive tests, `clu.exe --safe` bypass | Low | SUP |
| R3 | Reliability | Infinite repair or goal loops | M | M | Attempt budgets, freeze after 2 failures, signature "already tried", retry block after rollback, weekly quotas, kill switch | Low | C, E, K |
| R4 | Reliability | Concurrent owner and AEE edits conflict | M | M | AEE uses worktrees only; owner changes always win; rebase or abandon; never touches dirty owner files | Low | B, F |
| P1 | Resource | AEE work makes the PC sluggish or competes with games and editing | M | H | Idle detection, a full-screen guard, below-normal priority, a 50 % CPU cap, suspend on user activity, working hours, thermal pause | Low | K |
| P2 | Resource | Coding model plus chat model exceed the 16 GB ceiling | M | H | The governor forbids co-residence beyond budget; the coding model is loaded only in windows; the chat model is warmed before the user returns | Brief warm-up delay after AEE windows | K, governor |
| P3 | Resource | Disk fills with worktrees, build outputs and logs | M | M | Quotas, at most 2 worktrees, 14-day raw logs, a stop below 10 GB free | Low | K, J |
| P4 | Resource | Laptop battery and thermals | M | M | AC-only by default, thermal pause | Low | K |
| D1 | Data loss | AEE change corrupts or migrates user data badly | L | H | Data never in the sandbox ACL; expand/contract migrations; compatibility gate; verified backup before activation; destructive migrations need owner confirmation | Very low | I, G |
| D2 | Data loss | `evolution.db` loss erases development history | L | M | Daily JSONL export, keep 7; index rebuildable from Git | Low | J |
| D3 | Data loss | Owner's uncommitted work overwritten | L | H | AEE never writes the owner's working tree or switches branches (enforced by path policy) | Very low | F, policy |
| A1 | Architectural | Feature proliferation and growing complexity | M | M | Measurable goals only, weekly quotas, a complexity metric in evaluation, a removal check for unused features | Medium (needs owner review of trends) | E, H |
| A2 | Architectural | Second identity or memory creeping in through "roles" | L | M | Roles are prompt and tool configs over one service; a test asserts one inference service and no role-local persistent memory | Low | spec AD-1 |
| A3 | Architectural | Desktop-only logic leaks into `core`, breaking Android | M | M | The existing `FoundationTest` import rule plus `apiCheck`; AEE adapters live in `desktop` | Low | B, G |
| A4 | Architectural | Milestone 1 unfinished, so the AEE builds on an unverified baseline | H (today) | M | Roadmap gate E0: MS1 complete with a green full check before AEE code | — | roadmap |
| A5 | Architectural | Windows behaviour unverified (no PC runs yet) | H | H | Gate E0 includes the M1 benchmark and an M2/M6 Windows smoke run before the Supervisor and sandbox work | Medium | roadmap |
| O1 | Ownership | Signing key exists only in the build container | M | H | The owner keeps a copy (an open convergence decision); the AEE never holds it | — | owner |
