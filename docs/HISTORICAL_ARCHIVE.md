# Personal archive, historical memory and simulated timeline (1.8.0, verified and fixed in 1.8.1)

_2026-10-29. Verified on the build machine only (JVM tests with real SQLite, Robolectric for the screens, real document
parsers on files made by fpdf2, python-docx and LibreOffice). **Not installed or tested on a device.**_

## 1. What it does

* **Clu's timeline and three clocks.** One time service (`core/.../history/Time.kt`, `CluTime`):
  * the **real clock** (the device's): used for the database, audit and diagnostics, never narrated as Clu's date;
  * the **conversation date**: the *simulated current date* when that setting is on (fixed, or advancing day by day
    from when it was set), otherwise the device date;
  * an optional **historical perspective** date: Clu answers as of that day.

  Clu's identity says its history with you began at the **timeline start** (default 1 January 2018), not at the
  install date.
* **Historical dates** (`HistoricalDate`): an exact day, a month, a year, a range ("between 2018 and 2019"), or
  unknown, each optionally approximate ("around March 2018").
  * Stored as ISO dates (first and last possible day), so no time zone can move a date-only memory.
  * The editor never assigns today's date to an event. An unreadable date is refused, not guessed.
* **Three times per memory**, never confused:
  1. when it happened (`eventDate`);
  2. when you told Clu, positioned in Clu's timeline (`told`: recorded, or by default the event date, never before
     the timeline start);
  3. when it was typed in (`createdAt`, internal only: it never reaches a prompt or an answer).
* **Memory records** additionally carry:
  * places;
  * certainty (confirmed fact, personal recollection, uncertain);
  * importance (normal, important, very important);
  * personal significance;
  * the role in a project or story (plan, decision, change, abandoned approach, goal, lesson, open question,
    important conversation, event);
  * links;
  * related memories, attached documents, and "same event" links;
  * a revision history (every edit keeps the previous version; deleting a record erases its history too, while a
    merge keeps provenance).
* **People catalog** (`ArchivePerson`).
  * Name, aliases and spellings, an optional photo, relationship, background, notes, first known date, how you know
    them, where you last left off, optional contact details, and important dates with optional reminders.
  * Memories link to profiles when the name is unambiguous (name, alias, first or last name, or a spelling variant).
  * Two possible matches are reported, never guessed.
  * The catalog is separate from the security "people" who may talk to Clu, and grants no access.
* **Documents** (`Documents.kt`).
  * Supported: TXT, Markdown, CSV, HTML and JSON; PDF, through a small built-in reader (Flate streams, text
    operators, ToUnicode maps, object streams); and Word `.docx`.
  * Images are kept as attachments without searchable text. There is no OCR.
  * Each import keeps a managed private copy, the SHA-256, extracted text split into passages, and a BM25 index.
  * Unsupported, damaged, empty, oversized (over 50 MB), duplicate or text-less files are refused with a clear reason.
  * A file with no text can be kept as an attachment if you choose.
* **Projects** (`Project`): name, aliases, description, status, start and end. A project's history is the memories
  linked to it or naming it, in date order.
* **Recall in conversation** (`Recall.kt`, deterministic). The answers come from the records and the clock, not the
  model. Questions it handles:
  * "Do you remember that girl I told you about?", "What did I tell you about Sarkar?", "Who is Riya?";
  * "Do you remember what happened in March 2018?";
  * "How long ago did I first tell you about this?", "Have I told you this before?";
  * "What happened after that?" / "before that?", "Who was with me at the time?";
  * "What did we decide about Primus?", "What was our original plan…", "What did we change later…", "Which approach
    did we abandon, and why?", "What was the last important decision…";
  * "Where are the gaps in my history?", "What does my document say about…".

  How the answers behave:
  * Clu says "you told me", never that it witnessed anything.
  * When several people fit, it asks which ("Do you mean Rahul Sharma (your cousin) or Rahul Verma (your
    colleague)?").
  * When nothing is stored, it says so.
  * "She", "that" and "what happened next" resolve from the conversation state (the topic records which memories and
    people are being discussed). Memory topics are never looked up on the web.
  * A general question about someone who isn't in the archive ("Who was Albert Einstein?") goes the normal way.
* **Historical perspective.** Only records Clu knew by the cutoff are used:
  * the record must have been told by then;
  * the event must have happened by then;
  * an undated record is not assumed to be known.

  This applies to recall, to the memories given to the model, to the timeline and to gap detection.
* **Checks that flag, never overwrite.**
  * Likely duplicate memories, people and documents are flagged.
  * When two records of the same event give different dates, a conflict is opened. You resolve it by choosing one
    (the replaced value stays in the history), keeping both, or dismissing it.
  * Merging memories keeps both texts. Merging people turns names into aliases and turns differing details into
    conflicts.
* **Gaps.** Months with no records since the timeline start are described as gaps in the archive, not proof that
  nothing happened. Records dated only to a year are mentioned but don't fill every month.
* **Missing-context hints** while entering or importing (no date, an unknown person, no description); they never
  block saving.
* **Contextual resurfacing.** Related earlier project decisions come along with a project memory, including ones that
  matched but didn't make the top-N cut (an existing bug, fixed).
* **Bulk entry and import.**
  * Formats: one memory per line (`date | title | details | people: … | project: … | kind: … | told: …`), CSV with a
    header row, or JSON.
  * Every row is validated and previewed (problems per line, likely duplicates, new people and projects) before
    anything is saved.
  * Rows are saved in transactions of 25, each keyed by its content. An interrupted or failed import can be run again
    and adds only what's missing.
* **Backup.** One JSON file containing memories with all their dates, people, projects, documents (text, and
  optionally the files), links and conflicts. Restoring adds what's missing and overwrites nothing. Exports from
  before 1.8.0 (a plain list of memories) still import.

## 2. Database

Schema **v4** (`core/.../db/Schema.kt`) is one migration, in one transaction (a failure leaves the database at v3,
unchanged).

* New columns on `memories`: `event_start`, `event_end`, `event_precision`, `event_approx`, `told`, `places`,
  `certainty`, `importance`, `significance`, `thread_kind`, `links`, `merged_into`.
* The legacy `event_at` becomes a date-only value (it was saved at local noon).
* New tables: `archive_people`, `archive_projects`, `archive_documents`, `document_chunks`, `document_terms`,
  `archive_links`, `record_revisions`, `archive_conflicts`, `import_jobs`, `import_keys`.
* Existing rows are only extended. Tested with a real v3 database: memories, pins, people lists, the diary and
  retrieval survive, and an injected broken migration step is rolled back with the version unchanged.

`Schema.migrate` now sets the version inside the same transaction as the steps.

## 3. Settings (Memory & diary)

Each is shown to change behaviour in `HistorySettingsTest`.

| Setting | Effect |
|---|---|
| Clu timeline start | Identity line in the prompt; timeline and gap start; earliest "told" date |
| Simulated current date (off / fixed / advancing) + Simulated date | Clu's "today", "last year", "N years ago"; the time tool; the prompt's date |
| Historical perspective (as of) | Recall, retrieval and timeline cut off at that date |
| When Clu learned an undated-told memory | Told = event date, or unknown (then "how long ago did I tell you" says it isn't recorded) |
| Answer memory questions from the archive | Record-based recall answers (off: the model answers with memories in its context) |
| Bring up related earlier decisions | Contextual resurfacing on or off |
| Suggest missing details | Editor and import hints on or off |

## 4. Screens (Memory)

* **The Memory hub:**
  * a timeline line;
  * quick add and bulk add;
  * Timeline, People, Documents and Projects;
  * Import & export, Settings, Diary and Suggestions;
  * a conflicts banner and today's reminders;
  * the memory list, with search across memories, people, projects and documents.
* **The editor:**
  * shows "When did it happen?" with a live reading ("Understood as: around March 2018") and an exact-day picker;
  * covers people, places, tags, projects, category, certainty and importance;
  * keeps "More details" for the told date, significance, role, links, summary, retention, attached documents and
    related memories;
  * offers "same event as…" and revision history;
  * after saving, shows duplicate, conflict and missing-context notes with Merge / Add to People.

Screen tests: `ArchiveUiTest`, `ArchiveWorkflowTest` (the connected workflows, section 7) and `AppSmokeTest.everyScreenOpens`.

## 5. Limits

* **Sync.** Memories (with their new fields) sync between phone and tablet as before. While one device is still on a
  version before 1.8.0, its edits keep the other device's archive fields (date, told, places, certainty, importance,
  role, links) instead of resetting them (1.8.1); the older device itself can't show those fields until it's updated. The People catalog, projects,
  documents, links and conflicts are **not synced** in this release. Use Import & export → Backup to move them.
* **PDFs.** The PDF reader handles PDFs from word processors, browsers and printers; tested with fpdf2 core and
  embedded fonts and with LibreOffice. Scanned PDFs, encrypted PDFs and unusual font encodings report "no readable
  text". There is no OCR.
* **Places.** Places are names on a memory, not a separate catalog.
* **Diary.** Diary dates remain real dates; the simulated date does not change when a diary entry is written.
* **Voice input.** Recall answers voice questions from the owner (as identified by the existing speaker checks). The
  LiveKit server engine does not use the phone's archive (its feature freeze, unchanged).
* **Model quality.** Phrasing for open-ended questions about memories is still the model's (SmolLM2-135M on the build
  machine is weak); the specific recall questions above don't depend on it.

## 6. Acceptance tests (§15 of the request)

`HistoryArchiveTest` (core) unless noted. Run: `./gradlew test` (or the full release check in `STATUS.md`).

| # | Requirement | Test |
|---|---|---|
| 1 | Simulated date independent of the device clock | `simulatedDateIsIndependentOfTheDeviceDate` |
| 2 | Memory entered today keeps its historical date | `aMemoryEnteredTodayKeepsItsHistoricalDate`; app: `ArchiveUiTest.theEditorKeepsTheHistoricalDateNotTodaysDate` |
| 3 | Approximate dates and ranges survive reopening | `approximateDatesAndRangesSurviveReopening`; app: `anUnreadableDateIsNotSaved` |
| 4 | Elapsed time from the simulated date | `elapsedTimeUsesTheSimulatedDate`, `howLongAgoAndHaveIToldYouBefore` |
| 5 | Natural question retrieves the memory | `aNaturalQuestionRetrievesTheMemory` |
| 6 | Follow-ups resolve to the person discussed | `followUpsResolveToThePersonBeingDiscussed` |
| 7 | Ambiguity clarified, nothing fabricated | `ambiguityIsClarifiedAndNothingIsFabricated` |
| 8 | People catalog linked to memories | `peopleCatalogLinksToMemories`; app: `hubTimelineAndPeopleShowTheArchive` |
| 9 | Documents imported, indexed, searched, linked; bad files refused | `documentsAreImportedIndexedSearchedAndLinked` |
| 10 | Duplicates and conflicts never destroy information | `duplicatesAndConflictsNeverDestroyInformation` |
| 11 | Historical perspective respects the cutoff | `historicalPerspectiveRespectsTheCutoff` |
| 12 | Backup preserves dates, relationships, documents | `backupPreservesDatesRelationshipsAndDocuments` |
| 13 | Existing memories survive the upgrade (and a failed migration rolls back) | `existingMemoriesSurviveTheUpgrade` |
| 14 | Memory functions work offline | `memoryFunctionsWorkOffline`; plus `scripts/test_offline.sh` (network namespace with no network) |
| 15 | Existing features unaffected | `existingConversationAndMemoryFeaturesStillWork` and the whole existing suite (0 failures) |
| 16 | Every new setting changes behaviour | `settings/HistorySettingsTest` (7 tests) |
| 17 | Import failures reported; interrupted imports resume | `importFailuresAreReportedAndInterruptedImportsResume`; app: `bulkAddPreviewsBeforeSaving` |

Also: `projectHistoryIsAnsweredFromTheRecords`, `timelineGapsAndReminders`, `missingContextIsSuggestedNotRequired`.

## 7. Connected workflows (1.8.1)

`app/src/test/kotlin/ai/clu/ArchiveWorkflowTest.kt` drives the real screens on Android framework code (Robolectric,
real Android SQLite, real archive services; file pickers answered with real files the way the system picker returns
them; chat through the real chat screen with no model loaded, so answers come from recall):

| Workflow | What is clicked and checked |
|---|---|
| Navigation | Every Memory-hub button opens its screen; Settings opens Memory & diary with the timeline settings; ☰ → Memory opens the hub |
| Simulated time + dates + recall | Quick add "around March 2018" → list shows the event date, not today; chat "Do you remember Riya?", "How long ago…her?" (eight years, from the simulated date), "what time is it?" (simulated date); moving the date to 2030 gives twelve years; unknown subject → "nothing stored" |
| People | + Add person with an important date → existing memory linked and listed; two Rahuls → chat asks which; duplicate warning; Merge → aliases + relation conflict → hub banner → Conflicts → chosen value applied |
| Photo picker | Typed name and notes of a new profile survive picking a photo (bug fixed in 1.8.1); managed photo copy |
| Editor | Same title → duplicate dialog → Merge keeps both texts; "Same event as…" with different dates → conflict → Use June 2021 corrects the other record, old date in history; editing keeps expiry and creation time (bug fixed in 1.8.1) |
| Documents | Import PDF + DOCX + corrupt PDF + XLSX in one pick → per-file results; search inside documents; link to a person → shown on the profile; hub search; chat quotes the document and says it isn't verified |
| Projects + timeline | New project → + Add to history (pre-linked) with role "Change" → history in date order; timeline year groups, period filter, gaps; View as of 2019-01-01 hides 2024 and chat says it doesn't know yet; Turn off restores |
| Bulk import + backup | CSV file with `details`/`project` columns → preview (problems per line, nothing saved) → add → re-import adds 0; Export with files → people/projects/documents removed → Restore brings them back with the file; damaged file refused, nothing changed |

Fixed in 1.8.1 as a result: editing a memory dropped fields the editor doesn't show (expiry, sync metadata); a new
profile lost typed fields when a photo was picked; CSV/JSON imports rejected the column names the line format uses
(`details`, `project`, `person`…); restoring a backup ran on the main thread (now in the background, with a message
if it fails); recall said "You first mentioned **it**" about a person ("You first told me about Riya…"); sync from a
pre-1.8.0 device could reset archive fields (above).

