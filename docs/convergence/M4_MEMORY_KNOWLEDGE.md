# M4 — Unified memory, knowledge and learning

_2026-10-31. Built and tested on Linux. Windows-specific acceptance (the window, tray, Edge app mode, DLL load, backup
and restore on NTFS) is still open; see "What is not verified"._

## 1. Starting point (what was inspected, what was kept)

M4 began with an inspection of the code, not a new design. CLU already had one authoritative store, and M4 builds on
it instead of adding a second one.

| Existing | Where | What M4 did with it |
|---|---|---|
| `memories` table, `MemoryRepository` (BM25 term index, revisions, tombstones, sync stamps) | `core/memory/Memory.kt` | **Kept as the one record store.** Two categories added (`KNOWLEDGE`, `PROCEDURE`). |
| Archive: people, projects, documents, links, conflicts, merge with history, duplicate and date-conflict checks | `core/history/*` | **Reused** for relationships (`archive_links`), documents (`document_chunks`, BM25), merging, duplicate detection. |
| `MemoryRetriever`, `MemoryRanking`, `MemoryConflicts`, `ProjectContext` | `core/agent`, `core/memory` | **Kept**; the retriever now also records use and adds connected and semantically close memories. |
| `ProposalRepository` (inferences need approval, rejected text is erased) | `core/memory/Memory.kt` | **Kept and extended** (kinds for stated facts/preferences/procedures; category, conflict, confidence, source reference). |
| `PreferenceEngine`, `FeedbackRouter` | `core/learning` | **Kept**; the new learner sits beside them and feeds `PreferenceEngine` only when the owner approves. |
| Desktop shell, `/v1/memories`, `/v1/suggestions`, host, governor | `desktop/*` (M2/M3) | **Kept**; `/v1/memory/*` added; the Memory view grew tabs. |

What was missing, and is now built: the five memory kinds and per-record metadata, working memory apart from
persistent memory, a formation pipeline with a consent policy, hybrid retrieval over memories and documents,
relationship-aware recall, consolidation with a log, preference learning with scopes, integrity checks, backup and
recovery, and a background job with resource limits.

**M1 benchmark results:** not available (the user's `results.json` has not been supplied). Nothing in M4 depends on a
backend choice. The resource limits used are the existing ones (host.json: 16 GB budget, 2 GB reserve, 1.5 GB pressure
threshold), and background work follows the same free-memory rule as the governor.

**Outstanding M3 acceptance (Windows):** unchanged by M4: Edge app-mode window, tray icon, single-instance hand-off,
display scaling, Edge's own memory use. Linux tests do not establish any of these.

## 2. Architecture

```
 chat / agents / planner / shell / (phone via M5)
                │
          MemoryService  ── one API ──  remember · recall · detail · relate · working · forget
                │
   ┌────────────┼──────────────────────────────────────────────┐
   │ memories (authoritative) + revisions        archive_links (relationships)         │
   │ memory_meta   (kind, confidence, last used, review date, provenance ref, approval) │
   │ working_memory (short-lived)                document_chunks / terms (documents)    │
   │ memory_terms  (BM25 keyword index)          memory_vectors (optional semantic)     │
   │ consolidation_log · preference_signals/candidates · maintenance_jobs               │
   └───────────────────────────────────────────────────────────────────────────────────┘
```

* **One authoritative store.** Records live in `memories`. Everything else is either metadata about a record
  (`memory_meta`, device-local, derived defaults if missing) or a rebuildable index (`memory_terms`, `memory_vectors`).
  There is no second memory database, no vector database and no graph engine.
* **Shared by every component.** The agent's retrieval (`MemoryRetriever`), the formation pipeline, the desktop API
  and the maintenance job all go through `MemoryService`. The Android app uses the same `core` (same schema, same
  repositories); it is not made the memory host, and nothing was added that competes with it. When the phone becomes a
  client of the PC (M5) it will call the same `/v1/memory/*` API.
* **Schema v5 is additive** (`Schema.migrations[4]`): new tables and four nullable/default columns on
  `memory_proposals`. Nothing is dropped or rewritten. The migration runs in one transaction; the desktop host makes a
  verified backup first (`pre-upgrade-v4`).

### The five kinds

`MemoryKind` is derived from the category unless the record's metadata says otherwise:

| Kind | Categories | Notes |
|---|---|---|
| Core identity | core fact, preference, instruction, project/status, manual, approved insight | |
| Episodic | experience, decision, (legacy diary) | dated, with people, places, outcomes |
| Semantic | knowledge (new) | facts and concepts; document passages are a separate source, never mixed in |
| Procedural | procedure (new) | workflows, "how to", lessons |
| Working | temporary context, and the separate working-memory table | expires; never persistent unless the owner keeps it |

### Metadata per memory

| Spec asks for | Where |
|---|---|
| unique id, category, created/modified | `memories.id`, `category`, `created_at`, `updated_at` |
| source and provenance | `source` (owner supplied / owner asked / conversation / approved inference / imported), `memory_meta.source_ref` (`conversation:<id>`, `desktop:owner`), document links (`supports`), merge log, revisions |
| confidence | `memory_meta.confidence` (set by formation; rises when the owner repeats the fact) |
| importance | `memories.importance` (0–2), used as a ranking signal, never to discard |
| last accessed | `memory_meta.last_accessed`, `access_count` (updated when CLU uses a memory in a reply; **not** when the owner browses) |
| expiry / review date | `memories.expires_at` (temporary), `memory_meta.review_at` |
| approval status | `memory_meta.approval` (`APPROVED` for anything saved from a suggestion) |

## 3. Formation, consent and consolidation

**Formation** (`MemoryFormation`) reads what the owner says and sorts it. It is rule-based: it works offline, costs
nothing at idle, never calls the model, and only reacts to first-person statements.

| What was said | What happens |
|---|---|
| a temporary detail ("I'm at the airport right now", "tomorrow's meeting") | working memory (expires after `memory.workingHours`, default 24 h); never persistent |
| a stated fact, preference or procedure | per `memory.formation`: **off** / **suggest** (default: a suggestion the owner approves) / **auto** (stated facts and preferences saved directly, with provenance; procedures still suggested) |
| the same thing again | reinforces the existing memory (confidence up, repeat count), no duplicate |
| something that conflicts with a memory | never saved over it: a suggestion that names the conflict; if approved, both stay and a `contradicts` link is recorded |
| a habit or pattern ("I always forget…"), any inference about the owner | **not** handled by formation; it goes through the existing insight proposer, which only ever creates a suggestion (evidence and reasoning shown; approve, edit or reject; rejected text is erased) |

Not formed: questions, guesses ("maybe", "I think"), plans ("I'm going to…"), feelings ("I'm tired"), other people's
preferences, hypotheticals. This is covered by tests with positive and negative examples.

**Consolidation** (`Consolidator`, background): exact duplicates (same category, words, people and compatible dates)
are folded into the earlier record on their own: the folded record is tombstoned with `mergedInto`, its earlier text
stays in `record_revisions`, its links move to the survivor, and the merge is logged (`AUTO`). Near-duplicates are only
**suggested**; approving merges (both texts kept), rejecting is remembered so the pair is not suggested again. Records
about different people or at different dates are never even suggested. Expired temporary memories and working notes
are removed and logged. A run looks only at records changed since the last run, at most 100 at a time, with a
checkpoint, and stops when asked.

**Not built:** merging many related memories into a written summary. That needs a language model; the milestone's
rule is that consolidation never destroys information, and a model-written summary would be an inference, so it would
have to be a suggestion. Left for later.

## 4. Retrieval

`MemoryService.recall(RecallRequest)` is one path for chat and for the shell:

1. keyword search (the existing BM25 term index; SQLite FTS5 is not used because the index already exists, works on
   Android as well, and the store is small);
2. optional semantic search: if a local `Embedder` is set and has indexed vectors, its nearest matches are fused with
   the keyword ranking (reciprocal rank); without one, retrieval is keyword + metadata + relationships;
3. filters: kind, category, people, time window (event dates), scope; expired and merged records excluded;
4. weighting: explicit over inferred, recency, importance, pinned, confidence, how often used;
5. relationships: one hop from the best three hits (decision → project, document → memory, …), strongest links first;
6. indexed document passages, returned as documents (name and passage number), never as memories.

**Semantic retrieval status.** The machinery is real and tested (vector storage, backfill in bounded batches only
in the background, staleness by text hash, deletion, fusion), but **no embedding model is wired in**: running a GGUF
embedding model needs new JNI code, a Windows DLL rebuild and a model download, none of which could be verified here.
`/v1/memory/overview` reports `embeddings: ""` until one is set. The tests use a deterministic concept embedder to test
the mechanism, not model quality.

**Relationships** use the existing `archive_links` table with a typed vocabulary: `related`, `involves`, `about`,
`same_event`, `same_story`, `supports`, `attachment`, plus `belongs_to`, `influences`, `updates`, `derived_from`,
`contradicts`. `connected(id, depth)` walks the table breadth-first. No graph engine.

## 5. Learning from feedback

`PreferenceLearner` records signals (a feedback command, a reaction on a reply, a choice between two replies) with
their conversation and day, and computes a visible scope:

| Scope | Rule | Effect |
|---|---|---|
| once | one signal | recorded; changes nothing beyond what the owner explicitly commanded |
| task | ≥ 2 signals for the same kind of task (coding / writing / planning / research) | applied to that kind of task only |
| recurring | ≥ 3 signals, ≥ 2 conversations, ≥ 2 different days | CLU **asks** whether to make it standing |
| standing | the owner says yes (or said "from now on") | applied through the settings registry or `PreferenceEngine`; sensitive settings cannot be promoted this way |

A "no" is remembered until three more signals arrive. Negative signals are counted but never create a preference.
Switching learning off (Settings → Memory) stops recording; "Forget what it has noticed" clears the signals.

Explicit commands that already existed ("be more concise") still do what they say immediately; M4 adds the record of
them, so repeats can lead to a question. A reaction on a reply (new menu under each answer) is recorded only.
No model fine-tuning and no external service is involved.

## 6. Lifecycle and reliability

* **Transactions.** `remember`, merges, approvals (including provenance and `contradicts` links) and deletes run in
  one transaction each; tests show nothing is left behind when a write fails.
* **Integrity.** `MemoryHealth.check` runs SQLite `integrity_check` and the rules SQLite can't know (index entries
  without records, records missing from the index, links to deleted records, orphaned metadata/vectors/chunks).
  `repair` removes orphans and re-indexes missing records; `reindexAll` rebuilds the keyword index from the records
  (resumable after an interruption, checkpointed in `maintenance_jobs`). Repair never touches the records.
* **Backups (desktop).** `VACUUM INTO` while CLU runs, written as `.part`, integrity-checked, then renamed;
  rotation per kind; a daily automatic backup, `pre-upgrade-v<N>` before a schema upgrade, a manual button. Backups
  are in `<data folder>\backups`.
* **Recovery.** At start the live database gets a quick integrity check. If it fails, CLU does **not** open it and
  prints the newest verified backup and the command: `clu-host --restore-backup "<file>"`. Restore (with CLU stopped)
  verifies the backup first and moves the damaged database aside next to the backups; nothing is deleted.
* **Interrupted work.** Backups leave no half-written file that looks valid; consolidation and re-indexing resume
  from their checkpoint; the expiry and consolidation steps are idempotent.
* **Retention.** Temporary memories (`memory.temporaryDays`), working notes (`memory.workingHours`), backups (7
  automatic, 3 per upgrade, 10 manual). Persistent memories are never expired automatically.

## 7. Background work and resources

`MaintenanceScheduler` (desktop host) runs `MemoryMaintenance` every `maintenanceMinutes` (host.json, default 20,
0 = off) on one low-priority daemon thread. It skips a run while the model is loading or generating, or while the
PC's free memory is below reserve + pressure threshold; and a run stops early if either becomes true. Each run does a
bounded amount (100 records scanned; at most 40 vectors embedded; one integrity quick-check per day; one backup per
day). Idle cost between runs is a sleeping thread. Embeddings are never generated on the reply path.

Measured on the build machine (Linux container, not the target PC): see the report below.

## 8. Interface (desktop shell)

The Memory view keeps its screens and gains five tabs. All controls call the API; nothing is decorative.

* **Browse:** kind chips with counts, search (memories and documents together), category, person, date range,
  include-documents switch; add/edit/delete as before. Opening a memory shows an **About this memory** panel: kind,
  category, created, last changed, last used by CLU (when, how often), repeated by you, confidence, review date,
  source with its reference ("From your conversation …", "Entered by you in the desktop window"), approval, supporting
  documents, what it was merged from; **Related** records with their relationship (add by searching, remove with ×);
  **Earlier versions**; conflicts.
* **Working notes:** list, add, dismiss, or keep as a memory.
* **Tidy-up:** duplicates waiting for the owner (side by side; merge or keep separate), manual run, activity log.
* **Learning:** questions, what CLU has noticed with its scope, preferences in use, reset.
* **Health & backups:** integrity report, repair, rebuild index, backup list and "back up now", restore
  instructions, background job states and "run now".
* **Suggestions:** evidence, category choice, conflict with an existing memory, "inferred by CLU" label, editable text.
* **Chat:** a small reaction menu under each answer.

## 9. API additions

`POST /v1/session` etc. are unchanged. New, all behind the token:

| Method | Path |
|---|---|
| GET | `/v1/memory/overview`, `/v1/memory/search`, `/v1/memory/{id}`, `/v1/memory/working`, `/v1/memory/consolidation`, `/v1/memory/learning`, `/v1/memory/health`, `/v1/memory/backups`, `/v1/memory/maintenance` |
| POST / DELETE | `/v1/memory/{id}/links`, `/v1/memory/working[/{id}]`, `/v1/memory/consolidation/{id}/approve\|reject`, `/v1/memory/consolidation/run`, `/v1/memory/learning/{id}/accept\|reject`, `/v1/memory/learning/feedback`, `/v1/memory/learning/reset`, `/v1/memory/health` (`repair`/`reindex`), `/v1/memory/backups`, `/v1/memory/maintenance` |

Changed: `/v1/memories*` now go through the unified service (provenance written, deletes complete); `/v1/suggestions`
returns kind, category, confidence, conflict, conversation; approve accepts `category`; `DELETE` requests may carry a
JSON body; documents accept source-code and config file types.
