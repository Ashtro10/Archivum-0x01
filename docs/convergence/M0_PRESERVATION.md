# M0 — Preservation record

_2026-10-31. Completed on branch `claude/clu-android-ai-build-5cspi2` (the session's designated branch, now the
unified working branch)._

## Original commits

| Source | Repository | Commit | How it is preserved |
|---|---|---|---|
| CLU | github.com/Ashtro10/Archivum-0x01 | `1899d1d` (Clu 1.8.2, last release) / `fb17728` (audit) | The unified repository itself; history intact |
| Primus / Astra | github.com/Ashtro10/astra | `16764d2c16f2d0edda111b9b75f2eeeae00c0ee2` ("Initial commit - Astra project", its entire Git history) | Subtree merge `463f62b`: `16764d2` is a parent of that merge, so it is reachable from this branch; files unchanged under `legacy/primus/` (301 files, byte-identical to the source checkout) |
| fullstack-agent | github.com/jaredrhod/fullstack-agent | `5bb159f` | Not imported (installer for Claude Code; AGPL-3.0). Concepts recorded in `CONVERGENCE_AUDIT.md` §3 |
| backtalk | github.com/jaredrhod/backtalk | `84b3a6c` | Not imported (AGPL-3.0; brain is the Claude Agent SDK). Concepts only |
| ai-visualizer | github.com/jaredrhod/ai-visualizer | `6921e1d` | Not imported (AGPL-3.0). Concepts only |
| barehands | github.com/jaredrhod/barehands | `eb23bed` | Not imported (AGPL-3.0). Optional later concept |
| ai-memory-vault | github.com/jaredrhod/ai-memory-vault | `659bba9` | Not imported (CC BY-SA 4.0). Concepts only |
| ai-marketing-skills | github.com/jaredrhod/ai-marketing-skills | `47b68a5` | Not imported (CC BY-SA 4.0). Optional later |

* **Originals untouched:** no original repository was modified, deleted or pushed to.
* **Backup tags:** not pushed, because this session may push only to the designated branch. The commit hashes above are
  the recovery points, and every one stays reachable (CLU's own history; Primus through the merge parent).

## What `legacy/primus/` is

Reference material, unchanged, until each part is ported into CLU (M6–M10). It isn't built or run by CLU's build:
* Gradle only includes CLU's modules;
* `legacy/` contains no Gradle project.

Its own test suite still runs from that folder:
```
cd legacy/primus && python -m unittest discover -s tests -t .
```

**Missing from Primus's repository** (not recoverable from GitHub):
* the `docs/adr/` folder, referenced by its README, docs and 8 tests;
* its development history before the single upload commit.

If you have them, they can be added under `legacy/primus/docs/adr/`.

## Test baselines (recorded at import)

### CLU (1.8.2, unchanged by M0)

Full check from scratch (2026-10-31): core 488 tests (13 opt-in skipped), app 74, server 9/9, offline test passed, 0
failures. Re-run after the import: see M1 below.

### Primus

Linux build container, Python 3.13, with numpy, pydantic and faiss-cpu installed; without llama-cpp-python, dearpygui
or torch:

**1,585 tests: 16 failures, 9 errors, 12 skipped.** (Its README claims 1,353 collected and 0 failures. That was
measured on the author's Windows machine at an earlier point.)

| Group | Count | Classification |
|---|---|---|
| `docs/adr/*.md` missing from the repository | 8 errors | Repository incomplete |
| `rvc_py` (optional RVC voice) not installed | 1 error | Environment |
| Windows case-insensitive filesystem assumed ("DATA" == "data") | 2 failures | Platform: must re-run on Windows |
| Symlink fail-closed tests (running as root on Linux) | 4 failures | Must re-run on Windows to classify |
| Web UI imports memory contracts / opens SQLite (ADR-0006 forbids it); UI calls `ask` contract | 3 failures | **Real**: the newer web UI breaks Astra's own architecture rules |
| Milestone "B-035/B-037 complete" assertions | 4 failures | **Real**: documentation drift |
| Frozen `Primus.exe` not present | 1 failure | Build artefact not in Git |
| `same_name_revives_refuted_row`, `milestone_check_not_hard_coded` | 2 failures | Unexplained here; re-run on Windows |

Earlier, without faiss installed, the same suite showed 25 failures and 49 errors. 40 of those errors were only the
missing `faiss` package.

## Rollback

M0 changes nothing in CLU's code:
* **Undo the import:** `git revert -m 1 463f62b` removes `legacy/primus/` again.
* **Undo the documents:** delete `docs/convergence/` and `bench/`.
