# Memory (beginner)

Memory is what Primus knows about **you**. It is not personality and not the
GGUF model.

## Where is memory stored?

SQLite file from settings:

- source: `<repo>/data/astra.db`
- packaged: `<exe-dir>/data/astra.db`
- override: `%ASTRA_HOME%/astra.db` (or `PRIMUS_HOME`)

The Memory Engine (`astra/memory/sqlite_store.py`) is the only store. FAISS
under `data/vectors.faiss` is disposable and rebuilt from SQLite.

## How is a memory created?

`ConversationOrchestrator` extracts **justified personal facts**, then calls
`MemoryIndexer.store` → SQLite `commit`.

Examples (any similar wording works; nothing is hardcoded):

- identity: `My name is Alex.`
- preference: `I prefer C.`
- project: `My current project is Atlas.`
- relationship: `My first crush's name was Isha.`
- background: `I grew up in Rourkela.`

Not every sentence is stored. Questions are not stored as facts. Goals stay in
the planning database. Session chat is a separate conversation-tagged
transcript.

Implementation: `_durable_facts()` and `_maybe_persist_durable()` in
`astra/presentation/orchestration.py`.

Provenance and field keys (`user.name`, `user.first_crush`, …) live in
`astra/memory/user_facts.py`. Only `explicit_user_*` sources are eligible for
normal retrieval. Probe/test rows and `MemProbe*` / `Project-*` patterns are
filtered at read time.

## How is a memory retrieved?

On a turn, Primus loads durable rows, scores them for **question relevance**
(token overlap, topic, category, phrase match), and picks the top few. Identity
(name) is not injected into unrelated questions (for example crush vs name).

Selected hits go into the ChatML **system** message. The model does not get the
whole database.

Implementation: `_retrieve_memory_scored()` and `_memory_relevance_score()` in
`astra/presentation/orchestration.py`.

## How does it survive restart?

Write is `connection.commit()`. Close runs `PRAGMA wal_checkpoint(TRUNCATE)`.
The next process opens the same file via `data_root()` (exe directory when
frozen).

## Corrections and forgetting

Explicit corrections refute older related rows. `Forget …` marks matching durable
rows refuted. Restart must not resurrect forgotten facts.

## How do I change memory behavior?

| Goal | Path |
|------|------|
| What gets remembered | `astra/presentation/orchestration.py` → `_durable_facts`, `analyze_intent` |
| User-memory boundary / sources | `astra/memory/user_facts.py` |
| What gets selected for a question | same file → `_retrieve_memory_scored`, `_memory_relevance_score` |
| Storage format / SQL | `astra/memory/sqlite_store.py` |
| UI | `ui/` |

Do not add a second memory database.

## Probe / test isolation

Runtime probes **never write** to the production user database. Unless you set
`ASTRA_HOME` yourself, probes use an isolated directory under the OS temp
scratch area (`astra-scratch/probes/…`).

Probe rows are tagged `source=probe` and filtered out of normal retrieval.

Inspect active user memory (developer only):

```text
Primus.exe --probe-memory
```

## How do I test memory?

```text
python -m unittest tests.test_general_memory tests.test_persistent_memory -q
```

Product path:

```text
Primus.exe --probe-memory-store "My first crush's name was <value>."
Primus.exe --probe-memory-ask "What was my first crush's name?"
Primus.exe --probe-runtime-write
Primus.exe --probe-runtime-read
```

Probe JSON results land in `<exe-dir>/logs/` (or `<repo>/logs/` from source).
Read probe reopens the same scratch DB recorded in `probe-runtime-write.json`.
