# CLU self-repair (first working version)

CLU can index its own source, keep up with development done outside it (the owner, Claude Code), detect a failing
test, investigate it from evidence, prepare a repair in an isolated Git worktree, build and test it, compare before and
after, write a report, and integrate the repair **only when the owner approves**. Every step is recorded in CLU's
development history (`<data>/evolution/evolution.db`) and survives restarts.

This is the first slice of the Autonomous Evolution Engine (`docs/AEE_*.md`). It runs on the PC host (`desktop/`). The
platform-independent logic is in `core/.../evolution/`. Android is unchanged, apart from the shared conversation code
receiving facts about CLU itself.

## Use it

```
./gradlew :desktop:installDist                       # builds desktop/build/install/clu-host
clu-host --self-repo /path/to/Archivum-0x01         # connect CLU's own Git checkout; indexes it
clu-host --self-index                               # look for new commits / edits since the last check
clu-host --self-ask "Which tests cover the permission broker?"
clu-host --self-repair --module core --tests 'ai.clu.core.ReasoningTest'          # CLU's model writes the patch
clu-host --self-repair --module core --tests '…' --patch fix.diff                  # a patch from you / Claude Code
clu-host --self-repairs | --self-incidents | --self-history
clu-host --self-report <id>                          # the full review report (diff, tests, commands)
clu-host --self-approve <id>                         # integrate into the branch it was found on
clu-host --self-reject <id> --reason "…"
```

`--home <folder>` selects the data folder, as for every other `clu-host` command. While CLU is running, the same
functions are available through the token-authenticated API: `GET /v1/evolution/status`, `POST /v1/evolution/repo`,
`POST /v1/evolution/reconcile`, `POST /v1/evolution/ask`, `POST /v1/evolution/run`,
`GET /v1/evolution/{incidents,repairs,history}`, `GET /v1/evolution/repairs/{id}[?format=report]` and
`POST /v1/evolution/repairs/{id}/{approve,reject}`.

A running host finds its source repository in this order:

1. one connected earlier;
2. `CLU_SOURCE_REPO`;
3. the folder it runs from.

It reconciles 30 s after start, then every 15 minutes, and only when CLU is not busy answering. In conversation, a
question about CLU's own software ("which files handle model inference?") gets the matching index facts in its
context, so the model answers from the index instead of inventing files.

## What happens in a repair run

| Step | What CLU does | Where |
|---|---|---|
| Observe | Reconcile the repository: new commits (with author, files, categories, provenance), changed files re-indexed by Git blob id, uncommitted files noted and left alone | `Reconciler`, `SourceIndexer` |
| Isolate | `git worktree add -b clu/repair/<id>` from the last commit. Ignored build inputs (`toolchain/`, `native/build`) are linked read-only, never copied | `GitCli`, `SelfRepairService` |
| Detect | `./gradlew :<module>:test --rerun [--tests …]` at reduced priority (`nice`). JUnit XML is parsed, and each failing test becomes an incident (a recurrence is counted, not duplicated). This run is also the "before" of the comparison | `CommandRunner`, `JUnitXml`, `Incidents` |
| Investigate | Stack frames (JDK module prefixes handled) and the identifiers on the failing test line are resolved through the index into suspects. Hypotheses: H1 defect in the top suspect, H2 wrong test expectation (supported only if the test changed after the code), H3 flaky, plus alternatives. Model hypotheses are stored as unverified | `Investigator` |
| Reproduce | The failing test again, alone. If it fails again, H3 is refuted. If it passes, the problem is recorded as flaky and no repair is attempted | experiment record |
| Patch | Either CLU's model (the shared inference service, owner `self-repair`), asked for search/replace edit blocks over bounded context (failure, test excerpt, suspect code, earlier failed attempts), or a patch supplied by the owner or a tool (`--patch`, labelled `external` or `fixture`) | `RepairPrompt`, `PatchFormat` |
| Review | Before anything is built, blocking findings refuse the patch: removing, changing or disabling test assertions, deleting files, touching trust-critical files (permissions, audit, security, self-repair, build, installer), or changing more than 200 lines. A patch already tried for the same problem is refused | `DiffReview`, `RepairStore.failedBefore` |
| Verify | The same test command on the modified code. **Verified** only if it compiled, tests ran, every target test failed before and passes after, and no test that passed before fails or vanished. Compilation alone is never success. A verified fix confined to H1's file confirms H1 | `Verification` |
| Report | Problem, cause (confirmed or not), suspects, hypotheses with evidence for and against, experiments, files, review, the exact commands with exit codes and durations, results, risks, full diff | `RepairReport` (also written to `<data>/evolution/workspaces/reports/`) |
| Decide | Approve: merged into the target branch in the owner's tree (fast-forward when possible), refused if that branch is not checked out or tracked files have uncommitted changes. Reject: worktree and branch removed, and the patch is remembered as rejected. A failed run is discarded automatically | `GitCli.integrate` |

Never done automatically:
- pushing anywhere;
- touching the installed CLU (an integrated repair takes effect when the owner rebuilds and installs);
- changing permissions, the broker or the self-repair code itself;
- deleting data;
- writing to the owner's working tree before approval.

## Demonstration (2026-11-03, this container)

Script: `scripts/self_repair_demo.sh`. Transcript and reports: `docs/measurements/self_repair/`. It ran in a disposable
clone of this repository with a disposable data folder.

1. **Connected and indexed:** 416 files and 3,806 declarations.
2. **External development:** a commit "as Claude Code" plus an uncommitted edit. CLU recorded the commit as
   `external`, re-indexed 2 changed files, answered "Where is reasoningModes implemented?" with the new function's file
   and line, and left the uncommitted file byte-identical (sha256 checked).
3. **Controlled defect:** committed in `Reasoning.disable` (an already-closed think block was closed again).
4. **CLU's model, Qwen3-0.6B:** detected the failing `ReasoningTest` case and localised it to
   `Reasoning.kt:34 disable`, using the failing line through the index. H1 was supported (the code changed in the
   latest commit), H2 had evidence against, and H3 was refuted by the reproduction. **The model could not write a
   patch.** After 19.8 s it returned an edit block with no `FILE:` line, aimed at the *test* file and copying line
   numbers. CLU recorded `patch-failed` with the model's output and touched nothing.
5. **Test fixture** (the reverse of the defect commit, labelled `fixture`): verified. Before: 8/9 tests passed. After:
   9/9, with no regressions. H1 was confirmed by experiment.
6. **Before approval:** the clone's HEAD, `Reasoning.kt` and the owner's uncommitted file were unchanged. The repair
   existed only on `clu/repair/e3c9ea23`.
7. **Approval refused:** the owner's tracked file was modified. Nothing was changed.
8. **Approval applied:** the owner stashed the work, approved, and popped it back. CLU fast-forwarded the branch with
   commit `Repair: … CLU-Repair: <id>`, then re-indexed it as provenance `clu-repair`.
9. **Independent Gradle run** outside CLU on the integrated branch: `ReasoningTest` passed.
10. **Records:** the history, repairs and incident (seen 2×, resolved) were read back by new processes.
11. **The real repository was unchanged** (same HEAD, same `git status` hash).

A running host answered `/v1/evolution/status`, `/ask` and `/repairs` and refused calls without the token. A real chat
turn with Qwen3-0.6B ("Which files in your source code handle model inference?") answered with
`core/.../llm/InferenceService.kt` from the injected index facts.

## Limits (honest)

* **Model capability.** The models available here (Qwen3-0.6B, SmolLM2-135M) cannot write correct patches. The
  pipeline makes no assumption about the model: a capable local coding model can be registered as CLU's model, and a
  patch from the owner or Claude Code goes through the same review, verification and approval (`--patch`). Choosing
  and measuring a capable coding model on the target PC is still open.
* **Detection is test-driven.** Problems are found by running the tests. Runtime errors in the diagnostics log are
  grouped (`Incidents.repeatedErrors`) but are not yet turned into repair runs automatically. Build failures of the
  unchanged code are recorded, not repaired.
* **Verification runs the given test selection.** A repair is checked against the tests of that run, plus a review
  of the diff. A broader regression run (`--tests` omitted means the whole module) is slower. Device, Windows and
  real-model tests are not part of it.
* **Index precision.** The Kotlin index is lexer-level: declarations, imports and packages are exact, but references
  are approximate, and "tests that cover X" is based on imports and names, not measured coverage.
* **One repair at a time**, started by the owner (CLI or API). There is no idle-time scheduler yet.
* **Windows.** The worktree links to ignored inputs use symbolic links, which on Windows need Developer Mode or admin
  rights. Without them, the workspace build lacks `toolchain/` and the run fails visibly. Nothing has been run on
  Windows.
* Uncommitted owner changes are not part of a run (it starts from the last commit), and this is stated in the
  history.

## Resources

Measured in this container (4 vCPU):

| What | Measurement |
|---|---|
| Index of this repository (first run) | 1.16 s for 433 files and 4,047 declarations; later checks re-read only changed files |
| `evolution.db` after the demonstration | 1.7 MB |
| One repair workspace | about 130 MB (a worktree without `toolchain/`, sharing Git objects) |
| Targeted test run with a warm Gradle daemon | 2–4 s |
| Cold test run | about 23 s |
| Qwen3-0.6B attempt | 19.8 s |

Removed when a run ends:
- failed and discarded workspaces at once;
- verified ones on approval or rejection.

Builds and tests run under `nice -n 10` and are skipped while CLU is answering or the PC is short of memory.

## Verification of this change

`./gradlew check :app:assembleDebug --offline --continue` (with the Playwright UI tests) passed on 2026-11-03 in 13 min 6 s:
- core: 645 tests, 14 opt-in skipped, 0 failures;
- desktop: 114 tests, 4 opt-in skipped, 0 failures;
- app: 74 tests, 0 failures;
- Android `apiCheck` and `icuRegexCheck` pass;
- the debug APK builds.

The new tests are `EvolutionTest` (12, core) and `SelfRepairWorkflowTest` (6, desktop, real Git and processes).

Defects found and fixed while building this:
- Stack frames carrying the JDK class-loader prefix (`app//…`) were not parsed.
- The repair upsert dropped incident links, so earlier failed attempts were not remembered.
- Unreadable files were re-counted as added on every check.
- A test result that Gradle restored from its build cache could have counted as a run (`--rerun` is now forced, and restored results are refused).
- Commit messages lost non-ASCII characters.
- In existing code: `DiagnosticsLog.redact` left the secret in `token=Bearer <secret>`. The bearer rule now runs first.
