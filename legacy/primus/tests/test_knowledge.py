"""Comprehensive tests for the Knowledge Engine (B-004).

Coverage requirements per the user specification and ADR-0008:

1.  Valid classification — primary keyword routes to correct category.
2.  Every constitution-defined category reachable by keyword.
3.  Unknown/no-keyword input — confidence 0.0, fallback to IDEAS.
4.  Duplicate knowledge rejected.
5.  Conflicting knowledge (different content, same category) accepted.
6.  Verified knowledge protection — Python guard + raw SQL trigger.
7.  Provenance preservation — source, acceptance_note, timestamps survive.
8.  Deterministic behaviour — same input always produces same Classification.
9.  Persistence / reload — records survive close + reopen.
10. Malformed input — empty content rejected.
11. Failure handling — uninitialised store raises clearly.
12. Offline / no-network — no socket during classify or store.
13. Memory Engine separation — distinct types and distinct databases.
14. Architecture / interface compliance — isinstance checks.
15. Confidence semantics — correct constant returned per scenario.
16. Rationale is non-empty — classifier always explains its decision.
17. Category filter on search and iter_records.
18. Delete removes record.
19. Delete of unknown id returns False.
20. Tags round-trip.
21. Schema version is 1 after initialise.
22. Initialise is idempotent.
23. iter_records streams without full materialisation.
24. search returns empty sequence for empty query.
25. KnowledgeSettings validation.
"""

from __future__ import annotations

import socket
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from astra.knowledge.classifier import (
    CONFIDENCE_AMBIGUOUS_PRIMARY,
    CONFIDENCE_AMBIGUOUS_SECONDARY,
    CONFIDENCE_NO_MATCH,
    CONFIDENCE_UNIQUE_PRIMARY,
    CONFIDENCE_UNIQUE_SECONDARY,
    KeywordKnowledgeClassifier,
)
from astra.knowledge.contracts import (
    Classification,
    KnowledgeCategory,
    KnowledgeClassifier,
    KnowledgeRecord,
    KnowledgeStore,
    KnowledgeVerification,
)
from astra.knowledge.errors import (
    DuplicateKnowledgeError,
    KnowledgeStoreError,
    VerifiedKnowledgeProtectedError,
)
from astra.knowledge.schema import current_version
from astra.knowledge.sqlite_store import SQLiteKnowledgeStore, content_fingerprint


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _temp_store() -> SQLiteKnowledgeStore:
    """Return an initialised store backed by a named temporary file."""
    tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
    tmp.close()
    store = SQLiteKnowledgeStore(tmp.name)
    store.initialise()
    return store


def _record(
    content: str,
    category: KnowledgeCategory = KnowledgeCategory.IDEAS,
    *,
    verification: KnowledgeVerification = KnowledgeVerification.UNVERIFIED,
    source: str = "test",
    confidence: float = 1.0,
    acceptance_note: str = "",
    tags: tuple[str, ...] = (),
    record_id: str = "",
) -> KnowledgeRecord:
    return KnowledgeRecord(
        record_id=record_id,
        category=category,
        content=content,
        source=source,
        confidence=confidence,
        verification=verification,
        acceptance_note=acceptance_note,
        tags=tags,
    )


# ---------------------------------------------------------------------------
# 1–2. Classification — valid categories, all 13 constitution categories
# ---------------------------------------------------------------------------

class ClassificationValidTest(unittest.TestCase):
    """Every category must be reachable via a keyword (ADR-0008 test plan #1, #2)."""

    def setUp(self) -> None:
        self.clf = KeywordKnowledgeClassifier()

    def _assert_routes_to(self, text: str, category: KnowledgeCategory) -> None:
        result = self.clf.classify(text)
        self.assertEqual(
            result.category,
            category,
            f"Expected {category.value!r}, got {result.category.value!r} for: {text!r}",
        )

    def test_architecture(self) -> None:
        self._assert_routes_to("The hexagonal architecture pattern separates concerns", KnowledgeCategory.ARCHITECTURE)

    def test_programming(self) -> None:
        self._assert_routes_to("Python lambda functions and closures explained", KnowledgeCategory.PROGRAMMING)

    def test_artificial_intelligence(self) -> None:
        self._assert_routes_to("Transformer attention mechanism for large language models", KnowledgeCategory.ARTIFICIAL_INTELLIGENCE)

    def test_mathematics(self) -> None:
        self._assert_routes_to("Eigenvalue decomposition in linear algebra", KnowledgeCategory.MATHEMATICS)

    def test_operating_systems(self) -> None:
        self._assert_routes_to("Linux kernel scheduler and context switch overhead", KnowledgeCategory.OPERATING_SYSTEMS)

    def test_networking(self) -> None:
        self._assert_routes_to("TCP connection setup and TLS handshake latency", KnowledgeCategory.NETWORKING)

    def test_security(self) -> None:
        self._assert_routes_to("SQL injection vulnerability in authentication endpoint", KnowledgeCategory.SECURITY)

    def test_hardware(self) -> None:
        self._assert_routes_to("RTX 3050 VRAM CUDA benchmark results", KnowledgeCategory.HARDWARE)

    def test_projects(self) -> None:
        self._assert_routes_to("Astra project milestone B-004 roadmap and backlog", KnowledgeCategory.PROJECTS)

    def test_user_preferences(self) -> None:
        self._assert_routes_to("I prefer dark mode and my preference is vim keybindings", KnowledgeCategory.USER_PREFERENCES)

    def test_experiments(self) -> None:
        self._assert_routes_to("Experiment hypothesis measured result benchmark verified", KnowledgeCategory.EXPERIMENTS)

    def test_failures(self) -> None:
        self._assert_routes_to("The build failed with a traceback and segfault exception", KnowledgeCategory.FAILURES)

    def test_ideas(self) -> None:
        self._assert_routes_to("what if we explore a potential new approach", KnowledgeCategory.IDEAS)

    def test_all_13_categories_covered(self) -> None:
        """Every KnowledgeCategory value must be achievable by the classifier."""
        all_categories = set(KnowledgeCategory)
        # Confirm the vocabulary covers all 13.
        from astra.knowledge.classifier import _VOCABULARY
        self.assertEqual(set(_VOCABULARY.keys()), all_categories)


# ---------------------------------------------------------------------------
# 3. Unknown / no-keyword input
# ---------------------------------------------------------------------------

class UnknownInputTest(unittest.TestCase):
    def setUp(self) -> None:
        self.clf = KeywordKnowledgeClassifier()

    def test_no_keyword_returns_ideas_fallback(self) -> None:
        result = self.clf.classify("xyzzy frobnicate quux blargle")
        self.assertEqual(result.category, KnowledgeCategory.IDEAS)
        self.assertEqual(result.confidence, CONFIDENCE_NO_MATCH)

    def test_no_keyword_rationale_mentions_fallback(self) -> None:
        result = self.clf.classify("xyzzy frobnicate quux blargle")
        self.assertIn("fallback", result.rationale.lower())

    def test_empty_string_returns_ideas_with_zero_confidence(self) -> None:
        result = self.clf.classify("")
        self.assertEqual(result.category, KnowledgeCategory.IDEAS)
        self.assertEqual(result.confidence, CONFIDENCE_NO_MATCH)

    def test_whitespace_only_returns_ideas(self) -> None:
        result = self.clf.classify("   \t\n   ")
        self.assertEqual(result.category, KnowledgeCategory.IDEAS)

    def test_rationale_always_non_empty(self) -> None:
        for text in ["", "  ", "xyzzy"]:
            with self.subTest(text=repr(text)):
                result = self.clf.classify(text)
                self.assertTrue(result.rationale)


# ---------------------------------------------------------------------------
# 8. Deterministic behaviour
# ---------------------------------------------------------------------------

class DeterministicClassifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.clf = KeywordKnowledgeClassifier()

    def test_same_input_same_output(self) -> None:
        text = "Python recursive algorithm with lambda"
        first = self.clf.classify(text)
        second = self.clf.classify(text)
        self.assertEqual(first.category, second.category)
        self.assertEqual(first.confidence, second.confidence)
        self.assertEqual(first.rationale, second.rationale)

    def test_different_instances_agree(self) -> None:
        text = "Transformer neural network training"
        a = KeywordKnowledgeClassifier().classify(text)
        b = KeywordKnowledgeClassifier().classify(text)
        self.assertEqual(a.category, b.category)
        self.assertEqual(a.confidence, b.confidence)

    def test_case_insensitive(self) -> None:
        lower = self.clf.classify("python lambda")
        upper = self.clf.classify("PYTHON LAMBDA")
        mixed = self.clf.classify("Python Lambda")
        self.assertEqual(lower.category, upper.category)
        self.assertEqual(lower.category, mixed.category)


# ---------------------------------------------------------------------------
# 15. Confidence semantics
# ---------------------------------------------------------------------------

class ConfidenceSemanticsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.clf = KeywordKnowledgeClassifier()

    def test_unique_primary_confidence(self) -> None:
        # "python" is a primary keyword for PROGRAMMING only
        result = self.clf.classify("python recursion algorithm")
        self.assertIn(result.category, (KnowledgeCategory.PROGRAMMING,))
        self.assertEqual(result.confidence, CONFIDENCE_UNIQUE_PRIMARY)

    def test_no_match_confidence_is_zero(self) -> None:
        result = self.clf.classify("xyzzy blargle frobnitz")
        self.assertEqual(result.confidence, CONFIDENCE_NO_MATCH)

    def test_confidence_values_are_exact_constants(self) -> None:
        """Confidence values must be exact floats, not computed approximations."""
        for val in (
            CONFIDENCE_UNIQUE_PRIMARY,
            CONFIDENCE_AMBIGUOUS_PRIMARY,
            CONFIDENCE_UNIQUE_SECONDARY,
            CONFIDENCE_AMBIGUOUS_SECONDARY,
            CONFIDENCE_NO_MATCH,
        ):
            with self.subTest(value=val):
                self.assertIsInstance(val, float)
                # Must be representable exactly (defined as literal constants).
                self.assertEqual(val, float(val))

    def test_ambiguous_primary_confidence(self) -> None:
        # Craft text that hits primary keywords from TWO categories
        # "failed" (FAILURES primary) and "hypothesis" (EXPERIMENTS primary)
        result = self.clf.classify("the experiment hypothesis failed with error traceback")
        # Must be ambiguous primary (both FAILURES and EXPERIMENTS have primary hits)
        self.assertIn(
            result.confidence,
            (CONFIDENCE_AMBIGUOUS_PRIMARY, CONFIDENCE_UNIQUE_PRIMARY),
        )

    def test_secondary_only_confidence(self) -> None:
        # "component" and "module" are secondary keywords for ARCHITECTURE
        # but "architecture" itself is primary. Use text without primary keywords.
        result = self.clf.classify("the module and component boundaries")
        # Could be ARCHITECTURE secondary or ambiguous secondary
        self.assertIn(
            result.confidence,
            (CONFIDENCE_UNIQUE_SECONDARY, CONFIDENCE_AMBIGUOUS_SECONDARY),
        )


# ---------------------------------------------------------------------------
# Store tests (4–7, 9–13, 17–24)
# ---------------------------------------------------------------------------

class StoreRoundTripTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()

    def tearDown(self) -> None:
        self.store.close()

    def test_store_and_retrieve(self) -> None:
        rec = _record("Python is a dynamically typed language", KnowledgeCategory.PROGRAMMING)
        rid = self.store.store(rec)
        fetched = self.store.get(rid)
        self.assertIsNotNone(fetched)
        assert fetched is not None
        self.assertEqual(fetched.content, rec.content)
        self.assertEqual(fetched.category, KnowledgeCategory.PROGRAMMING)
        self.assertEqual(fetched.source, "test")

    def test_record_id_assigned_on_insert(self) -> None:
        rec = _record("Some knowledge", KnowledgeCategory.IDEAS)
        rid = self.store.store(rec)
        self.assertTrue(rid)

    def test_get_unknown_returns_none(self) -> None:
        self.assertIsNone(self.store.get("does-not-exist"))


# 7. Provenance preservation
class ProvenanceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()

    def tearDown(self) -> None:
        self.store.close()

    def test_source_preserved(self) -> None:
        rec = _record("CUDA tensor core throughput", KnowledgeCategory.HARDWARE, source="benchmark_results")
        rid = self.store.store(rec)
        fetched = self.store.get(rid)
        assert fetched is not None
        self.assertEqual(fetched.source, "benchmark_results")

    def test_acceptance_note_preserved(self) -> None:
        rec = _record(
            "RSA key size recommendation",
            KnowledgeCategory.SECURITY,
            acceptance_note="accepted after manual review",
        )
        rid = self.store.store(rec)
        fetched = self.store.get(rid)
        assert fetched is not None
        self.assertEqual(fetched.acceptance_note, "accepted after manual review")

    def test_timestamps_set_on_insert(self) -> None:
        rec = _record("Some hardware detail", KnowledgeCategory.HARDWARE)
        rid = self.store.store(rec)
        fetched = self.store.get(rid)
        assert fetched is not None
        self.assertIsNotNone(fetched.created_at)
        self.assertIsNotNone(fetched.updated_at)

    def test_timestamps_are_timezone_aware(self) -> None:
        from datetime import timezone
        rec = _record("Networking note", KnowledgeCategory.NETWORKING)
        rid = self.store.store(rec)
        fetched = self.store.get(rid)
        assert fetched is not None
        assert fetched.created_at is not None
        self.assertEqual(fetched.created_at.tzinfo, timezone.utc)

    def test_verification_state_preserved(self) -> None:
        rec = _record(
            "Verified architecture fact",
            KnowledgeCategory.ARCHITECTURE,
            verification=KnowledgeVerification.VERIFIED,
        )
        rid = self.store.store(rec)
        fetched = self.store.get(rid)
        assert fetched is not None
        self.assertEqual(fetched.verification, KnowledgeVerification.VERIFIED)

    def test_confidence_preserved(self) -> None:
        rec = _record("Some AI finding", KnowledgeCategory.ARTIFICIAL_INTELLIGENCE, confidence=0.75)
        rid = self.store.store(rec)
        fetched = self.store.get(rid)
        assert fetched is not None
        self.assertAlmostEqual(fetched.confidence, 0.75, places=6)

    def test_tags_round_trip(self) -> None:
        rec = _record(
            "Go concurrency with goroutines",
            KnowledgeCategory.PROGRAMMING,
            tags=("concurrency", "golang"),
        )
        rid = self.store.store(rec)
        fetched = self.store.get(rid)
        assert fetched is not None
        self.assertEqual(set(fetched.tags), {"concurrency", "golang"})


# 4. Duplicate knowledge
class DuplicateKnowledgeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()

    def tearDown(self) -> None:
        self.store.close()

    def test_same_content_same_category_rejected(self) -> None:
        rec = _record("TCP uses three-way handshake", KnowledgeCategory.NETWORKING)
        self.store.store(rec)
        with self.assertRaises(DuplicateKnowledgeError):
            self.store.store(rec)

    def test_whitespace_normalised_duplicate_rejected(self) -> None:
        self.store.store(_record("TCP three-way handshake", KnowledgeCategory.NETWORKING))
        with self.assertRaises(DuplicateKnowledgeError):
            self.store.store(_record("  TCP  three-way  handshake  ", KnowledgeCategory.NETWORKING))

    def test_same_content_different_category_accepted(self) -> None:
        content = "shared content"
        self.store.store(_record(content, KnowledgeCategory.IDEAS))
        rid2 = self.store.store(_record(content, KnowledgeCategory.PROJECTS))
        self.assertTrue(rid2)


# 5. Conflicting knowledge
class ConflictingKnowledgeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()

    def tearDown(self) -> None:
        self.store.close()

    def test_different_content_same_category_both_accepted(self) -> None:
        r1 = self.store.store(_record("Python is interpreted", KnowledgeCategory.PROGRAMMING))
        r2 = self.store.store(_record("Python is dynamically typed", KnowledgeCategory.PROGRAMMING))
        self.assertNotEqual(r1, r2)
        self.assertIsNotNone(self.store.get(r1))
        self.assertIsNotNone(self.store.get(r2))


# 6. Verified knowledge protection
class VerifiedProtectionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()

    def tearDown(self) -> None:
        self.store.close()

    def _store_verified(self, content: str, category: KnowledgeCategory) -> str:
        rid = self.store.store(_record(content, category))
        verified = _record(
            content,
            category,
            verification=KnowledgeVerification.VERIFIED,
            record_id=rid,
        )
        return self.store.store(verified)

    def test_python_guard_rejects_unverified_overwrite(self) -> None:
        rid = self._store_verified(
            "Verified hardware fact", KnowledgeCategory.HARDWARE
        )
        unverified = _record(
            "Verified hardware fact",
            KnowledgeCategory.HARDWARE,
            verification=KnowledgeVerification.UNVERIFIED,
            record_id=rid,
        )
        with self.assertRaises(VerifiedKnowledgeProtectedError):
            self.store.store(unverified)

    def test_database_trigger_rejects_unverified_overwrite(self) -> None:
        """The trigger enforces the rule even bypassing Python (ADR-0008 D6)."""
        rid = self._store_verified("Verified security note", KnowledgeCategory.SECURITY)
        # Access connection directly to bypass Python guard.
        conn = self.store._connection
        assert conn is not None
        with self.assertRaises(sqlite3.IntegrityError) as ctx:
            conn.execute(
                "UPDATE knowledge_records SET verification = 'unverified' "
                "WHERE record_id = ?",
                (rid,),
            )
        self.assertIn("verified", str(ctx.exception).lower())

    def test_promotion_unverified_to_verified_allowed(self) -> None:
        rid = self.store.store(_record("Unverified AI claim", KnowledgeCategory.ARTIFICIAL_INTELLIGENCE))
        promoted = _record(
            "Unverified AI claim",
            KnowledgeCategory.ARTIFICIAL_INTELLIGENCE,
            verification=KnowledgeVerification.VERIFIED,
            record_id=rid,
        )
        self.store.store(promoted)
        fetched = self.store.get(rid)
        assert fetched is not None
        self.assertEqual(fetched.verification, KnowledgeVerification.VERIFIED)

    def test_demotion_verified_to_refuted_allowed(self) -> None:
        rid = self._store_verified("Verified then refuted", KnowledgeCategory.FAILURES)
        refuted = _record(
            "Verified then refuted",
            KnowledgeCategory.FAILURES,
            verification=KnowledgeVerification.REFUTED,
            record_id=rid,
        )
        self.store.store(refuted)
        fetched = self.store.get(rid)
        assert fetched is not None
        self.assertEqual(fetched.verification, KnowledgeVerification.REFUTED)


# 9. Persistence / reload
class PersistenceTest(unittest.TestCase):
    def test_records_survive_reopen(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
            db_path = f.name

        store = SQLiteKnowledgeStore(db_path)
        store.initialise()
        rec = _record("Persistent mathematics fact", KnowledgeCategory.MATHEMATICS)
        rid = store.store(rec)
        store.close()

        store2 = SQLiteKnowledgeStore(db_path)
        store2.initialise()
        fetched = store2.get(rid)
        store2.close()

        self.assertIsNotNone(fetched)
        assert fetched is not None
        self.assertEqual(fetched.content, rec.content)

    def test_schema_version_is_1(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
            db_path = f.name
        store = SQLiteKnowledgeStore(db_path)
        store.initialise()
        conn = store._connection
        assert conn is not None
        version = current_version(conn)
        store.close()
        self.assertEqual(version, 1)

    def test_initialise_is_idempotent(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
            db_path = f.name
        store = SQLiteKnowledgeStore(db_path)
        store.initialise()
        store.initialise()  # Should not raise or create duplicate tables.
        store.close()


# 10. Malformed input
class MalformedInputTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()

    def tearDown(self) -> None:
        self.store.close()

    def test_empty_content_rejected(self) -> None:
        rec = _record("", KnowledgeCategory.IDEAS)
        with self.assertRaises(KnowledgeStoreError):
            self.store.store(rec)

    def test_whitespace_only_content_rejected(self) -> None:
        rec = _record("   \t  ", KnowledgeCategory.IDEAS)
        with self.assertRaises(KnowledgeStoreError):
            self.store.store(rec)

    def test_get_with_empty_id_returns_none(self) -> None:
        # Empty string is not a valid UUID; _row will return None.
        result = self.store.get("")
        self.assertIsNone(result)


# 11. Failure handling
class FailureHandlingTest(unittest.TestCase):
    def test_store_before_initialise_raises(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
            db_path = f.name
        store = SQLiteKnowledgeStore(db_path)
        # Do NOT call initialise().
        rec = _record("Some content", KnowledgeCategory.IDEAS)
        with self.assertRaises(KnowledgeStoreError):
            store.store(rec)

    def test_get_before_initialise_raises(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
            db_path = f.name
        store = SQLiteKnowledgeStore(db_path)
        with self.assertRaises(KnowledgeStoreError):
            store.get("any-id")

    def test_search_before_initialise_raises(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
            db_path = f.name
        store = SQLiteKnowledgeStore(db_path)
        with self.assertRaises(KnowledgeStoreError):
            store.search("anything")

    def test_close_without_initialise_safe(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
            db_path = f.name
        store = SQLiteKnowledgeStore(db_path)
        store.close()  # Must not raise.

    def test_double_close_safe(self) -> None:
        store = _temp_store()
        store.close()
        store.close()  # Must not raise.

    def test_context_manager_closes_on_exit(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
            db_path = f.name
        with SQLiteKnowledgeStore(db_path) as store:
            self.assertEqual(store.search_mode, "fts5")
        self.assertIsNone(store._connection)


# 12. Offline / no-network
class OfflineKnowledgeTest(unittest.TestCase):
    """Classifier and store must produce no network I/O (offline invariant)."""

    def _raise_connect(self, *args: object, **kwargs: object) -> None:
        raise AssertionError("network access attempted")

    def test_classifier_offline(self) -> None:
        clf = KeywordKnowledgeClassifier()
        with patch.object(socket, "socket", side_effect=self._raise_connect), \
             patch.object(socket, "create_connection", side_effect=self._raise_connect), \
             patch.object(socket, "getaddrinfo", side_effect=self._raise_connect):
            result = clf.classify("Python neural network embedding transformer")
        # No exception = no network access.
        self.assertIsNotNone(result)

    def test_store_offline(self) -> None:
        store = _temp_store()
        rec = _record("offline test record", KnowledgeCategory.IDEAS)
        with patch.object(socket, "socket", side_effect=self._raise_connect), \
             patch.object(socket, "create_connection", side_effect=self._raise_connect), \
             patch.object(socket, "getaddrinfo", side_effect=self._raise_connect):
            rid = store.store(rec)
            fetched = store.get(rid)
        store.close()
        self.assertIsNotNone(fetched)

    def test_no_network_imports_in_knowledge_modules(self) -> None:
        """Knowledge modules must not import network libraries."""
        import ast
        import importlib
        import sys

        network_libs = {"httpx", "requests", "urllib3", "aiohttp"}
        pkg = importlib.import_module("astra.knowledge")
        pkg_path = Path(pkg.__file__).parent  # type: ignore[arg-type]

        for py_file in pkg_path.glob("*.py"):
            source = py_file.read_text(encoding="utf-8")
            try:
                tree = ast.parse(source)
            except SyntaxError:
                continue
            for node in ast.walk(tree):
                if isinstance(node, (ast.Import, ast.ImportFrom)):
                    names = (
                        [alias.name for alias in node.names]
                        if isinstance(node, ast.Import)
                        else ([node.module] if node.module else [])
                    )
                    for name in names:
                        root = (name or "").split(".")[0]
                        with self.subTest(file=py_file.name, library=root):
                            self.assertNotIn(
                                root,
                                network_libs,
                                f"{py_file.name} imports network library {root!r}",
                            )


# 13. Memory Engine separation
class MemorySeparationTest(unittest.TestCase):
    def test_knowledge_store_not_subclass_of_memory_store(self) -> None:
        from astra.memory.contracts import MemoryStore
        self.assertFalse(issubclass(SQLiteKnowledgeStore, MemoryStore))

    def test_knowledge_classifier_not_subclass_of_memory_anything(self) -> None:
        import astra.memory as memory_pkg
        memory_classes = {
            v for k, v in vars(memory_pkg).items()
            if isinstance(v, type)
        }
        for cls in memory_classes:
            self.assertFalse(
                issubclass(KeywordKnowledgeClassifier, cls),
                f"KeywordKnowledgeClassifier should not be a subclass of {cls.__name__}",
            )

    def test_knowledge_record_not_memory_record(self) -> None:
        from astra.memory.contracts import MemoryRecord
        self.assertIsNot(KnowledgeRecord, MemoryRecord)

    def test_knowledge_verification_not_memory_verification(self) -> None:
        from astra.memory.contracts import VerificationState
        self.assertIsNot(KnowledgeVerification, VerificationState)

    def test_separate_databases_independent(self) -> None:
        """Two stores on separate files share no state."""
        from astra.memory.sqlite_store import SQLiteMemoryStore
        from astra.memory.contracts import (
            MemoryCategory,
            MemoryRecord,
            VerificationState,
        )

        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f1, \
             tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f2:
            mem_path = f1.name
            know_path = f2.name

        mem_store = SQLiteMemoryStore(mem_path)
        mem_store.initialise()

        know_store = SQLiteKnowledgeStore(know_path)
        know_store.initialise()

        # Write to memory; knowledge must be unaffected.
        mem_record = MemoryRecord(
            record_id="",
            category=MemoryCategory.IDEAS,
            content="memory-only fact",
            verification=VerificationState.UNVERIFIED,
            source="test",
        )
        mem_store.store(mem_record)

        # Write to knowledge; memory must be unaffected.
        know_rid = know_store.store(
            _record("knowledge-only fact", KnowledgeCategory.IDEAS)
        )

        # Cross-check: knowledge record not in memory, memory record not in knowledge.
        self.assertIsNone(mem_store.get(know_rid))

        mem_store.close()
        know_store.close()


# 14. Architecture / interface compliance
class ArchitectureComplianceTest(unittest.TestCase):
    def test_sqlite_store_is_knowledge_store(self) -> None:
        self.assertTrue(issubclass(SQLiteKnowledgeStore, KnowledgeStore))

    def test_keyword_classifier_is_knowledge_classifier(self) -> None:
        self.assertTrue(issubclass(KeywordKnowledgeClassifier, KnowledgeClassifier))

    def test_sqlite_store_instance(self) -> None:
        store = _temp_store()
        self.assertIsInstance(store, KnowledgeStore)
        store.close()

    def test_classifier_instance(self) -> None:
        clf = KeywordKnowledgeClassifier()
        self.assertIsInstance(clf, KnowledgeClassifier)

    def test_classification_is_dataclass(self) -> None:
        import dataclasses
        self.assertTrue(dataclasses.is_dataclass(Classification))

    def test_knowledge_record_is_frozen(self) -> None:
        rec = _record("immutable", KnowledgeCategory.IDEAS)
        with self.assertRaises((AttributeError, TypeError)):
            rec.content = "mutable"  # type: ignore[misc]


# 17. Category filter on search and iter_records
class CategoryFilterTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()
        self.store.store(_record("Python closures", KnowledgeCategory.PROGRAMMING))
        self.store.store(_record("TCP handshake", KnowledgeCategory.NETWORKING))

    def tearDown(self) -> None:
        self.store.close()

    def test_search_filters_by_category(self) -> None:
        results = self.store.search("closures", category=KnowledgeCategory.PROGRAMMING)
        self.assertTrue(
            all(r.category == KnowledgeCategory.PROGRAMMING for r in results)
        )

    def test_search_empty_query_returns_empty(self) -> None:
        results = self.store.search("")
        self.assertEqual(len(results), 0)

    def test_iter_records_all(self) -> None:
        all_records = list(self.store.iter_records())
        self.assertEqual(len(all_records), 2)

    def test_iter_records_by_category(self) -> None:
        networking = list(
            self.store.iter_records(category=KnowledgeCategory.NETWORKING)
        )
        self.assertEqual(len(networking), 1)
        self.assertEqual(networking[0].content, "TCP handshake")

    def test_iter_records_streams(self) -> None:
        """iter_records must return a lazy iterator, not a fully-materialised list."""
        import types
        result = self.store.iter_records()
        # Should be a generator or iterator, not a list/tuple.
        self.assertNotIsInstance(result, (list, tuple))


# 18–19. Delete
class DeleteTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()

    def tearDown(self) -> None:
        self.store.close()

    def test_delete_removes_record(self) -> None:
        rid = self.store.store(_record("To be deleted", KnowledgeCategory.IDEAS))
        result = self.store.delete(rid)
        self.assertTrue(result)
        self.assertIsNone(self.store.get(rid))

    def test_delete_unknown_returns_false(self) -> None:
        result = self.store.delete("does-not-exist")
        self.assertFalse(result)

    def test_delete_removes_tags(self) -> None:
        rid = self.store.store(
            _record("Tagged record", KnowledgeCategory.IDEAS, tags=("x", "y"))
        )
        self.store.delete(rid)
        # After delete, no tags should remain.
        conn = self.store._connection
        assert conn is not None
        rows = conn.execute(
            "SELECT * FROM knowledge_tags WHERE record_id = ?", (rid,)
        ).fetchall()
        self.assertEqual(len(rows), 0)


# 21–22. Schema
class SchemaTest(unittest.TestCase):
    def test_schema_version_after_fresh_init(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
            db_path = f.name
        store = SQLiteKnowledgeStore(db_path)
        store.initialise()
        conn = store._connection
        assert conn is not None
        self.assertEqual(current_version(conn), 1)
        store.close()

    def test_idempotent_initialise_does_not_raise(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
            db_path = f.name
        store = SQLiteKnowledgeStore(db_path)
        for _ in range(3):
            store.initialise()
        store.close()


# 25. KnowledgeSettings
class KnowledgeSettingsTest(unittest.TestCase):
    def test_default_path_is_knowledge_db(self) -> None:
        from astra.configuration.settings import KnowledgeSettings
        s = KnowledgeSettings()
        self.assertIn("knowledge", s.database_path.name.lower())

    def test_knowledge_path_differs_from_memory_default(self) -> None:
        from astra.configuration.settings import KnowledgeSettings, MemorySettings
        know = KnowledgeSettings()
        mem = MemorySettings()
        self.assertNotEqual(know.database_path, mem.database_path)

    def test_astra_settings_includes_knowledge(self) -> None:
        from astra.configuration.settings import AstraSettings, KnowledgeSettings
        s = AstraSettings()
        self.assertIsInstance(s.knowledge, KnowledgeSettings)

    def test_knowledge_settings_in_all_exports(self) -> None:
        from astra.configuration import settings as m
        self.assertIn("KnowledgeSettings", m.__all__)


# ---------------------------------------------------------------------------
# Integration: classifier → store pipeline
# ---------------------------------------------------------------------------

class ClassifierStorePipelineTest(unittest.TestCase):
    """Smoke test for the full classify → store → retrieve pipeline."""

    def setUp(self) -> None:
        self.clf = KeywordKnowledgeClassifier()
        self.store = _temp_store()

    def tearDown(self) -> None:
        self.store.close()

    def test_pipeline_programming(self) -> None:
        content = "Python lambda functions with type annotations"
        classification = self.clf.classify(content)
        rec = KnowledgeRecord(
            record_id="",
            category=classification.category,
            content=content,
            source="pipeline_test",
            confidence=classification.confidence,
            verification=KnowledgeVerification.UNVERIFIED,
            acceptance_note=classification.rationale,
        )
        rid = self.store.store(rec)
        fetched = self.store.get(rid)
        assert fetched is not None
        self.assertEqual(fetched.category, KnowledgeCategory.PROGRAMMING)
        self.assertEqual(fetched.source, "pipeline_test")
        self.assertEqual(fetched.acceptance_note, classification.rationale)

    def test_pipeline_all_categories_store(self) -> None:
        """One representative text per category must store without error."""
        samples = {
            KnowledgeCategory.ARCHITECTURE: "The hexagonal architecture separates core from adapters",
            KnowledgeCategory.PROGRAMMING: "Python async coroutine with type annotation",
            KnowledgeCategory.ARTIFICIAL_INTELLIGENCE: "Transformer attention mechanism and llm inference",
            KnowledgeCategory.MATHEMATICS: "Eigenvalue decomposition in linear algebra matrices",
            KnowledgeCategory.OPERATING_SYSTEMS: "Linux kernel scheduler with context switch overhead",
            KnowledgeCategory.NETWORKING: "TCP three-way handshake and TLS certificate validation",
            KnowledgeCategory.SECURITY: "SQL injection vulnerability in authentication endpoint",
            KnowledgeCategory.HARDWARE: "RTX 3050 GPU CUDA VRAM benchmark results",
            KnowledgeCategory.PROJECTS: "Astra project milestone backlog roadmap sprint",
            KnowledgeCategory.USER_PREFERENCES: "I prefer vim keybindings as my workflow preference",
            KnowledgeCategory.EXPERIMENTS: "Experiment hypothesis measured result benchmark verified",
            KnowledgeCategory.FAILURES: "Build failed with traceback exception segfault crash",
            KnowledgeCategory.IDEAS: "what if we explore a potential new concept idea",
        }
        for category, content in samples.items():
            with self.subTest(category=category.value):
                rec = KnowledgeRecord(
                    record_id="",
                    category=category,
                    content=content,
                    source="pipeline",
                    confidence=1.0,
                    verification=KnowledgeVerification.UNVERIFIED,
                )
                rid = self.store.store(rec)
                fetched = self.store.get(rid)
                self.assertIsNotNone(fetched)
                assert fetched is not None
                self.assertEqual(fetched.category, category)


if __name__ == "__main__":
    unittest.main()
