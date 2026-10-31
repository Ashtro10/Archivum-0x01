"""Memory Engine tests.

Follows the twelve-test plan approved in ADR-0003. Every test uses a temporary
database file, so the suite writes nothing durable.
"""

from __future__ import annotations

import sqlite3
import tempfile
import types
import unittest
from datetime import datetime, timezone
from pathlib import Path

from astra.memory import (
    DuplicateRecordError,
    MemoryCategory,
    MemoryRecord,
    SQLiteMemoryStore,
    VerificationState,
    VerifiedRecordProtectedError,
)
from astra.memory.errors import MemoryStoreError
from astra.memory.schema import SCHEMA_VERSION, current_version


def _record(
    content: str = "Astra stores memory in SQLite.",
    *,
    category: MemoryCategory = MemoryCategory.ARCHITECTURE,
    verification: VerificationState = VerificationState.UNVERIFIED,
    record_id: str = "",
    source: str = "",
    tags: tuple[str, ...] = (),
) -> MemoryRecord:
    return MemoryRecord(
        record_id=record_id,
        category=category,
        content=content,
        verification=verification,
        source=source,
        tags=tags,
    )


class MemoryStoreTestCase(unittest.TestCase):
    """Shared fixture: an initialised store on a temporary file."""

    def setUp(self) -> None:
        self._temp = tempfile.TemporaryDirectory()
        self.addCleanup(self._temp.cleanup)
        self.database_path = Path(self._temp.name) / "memory.db"
        self.store = SQLiteMemoryStore(self.database_path)
        self.store.initialise()
        # Registered after the temporary directory, so it runs first.
        self.addCleanup(self.store.close)


class InitialisationTest(MemoryStoreTestCase):
    def test_initialise_creates_current_schema_version(self) -> None:
        connection = sqlite3.connect(self.database_path)
        self.addCleanup(connection.close)
        self.assertEqual(SCHEMA_VERSION, current_version(connection))

    def test_initialise_is_idempotent(self) -> None:
        self.store.initialise()
        self.store.initialise()
        connection = sqlite3.connect(self.database_path)
        self.addCleanup(connection.close)
        self.assertEqual(SCHEMA_VERSION, current_version(connection))

    def test_use_before_initialise_is_refused(self) -> None:
        store = SQLiteMemoryStore(Path(self._temp.name) / "unopened.db")
        with self.assertRaises(MemoryStoreError):
            store.get("anything")


class RoundTripTest(MemoryStoreTestCase):
    def test_record_survives_a_round_trip(self) -> None:
        original = _record(
            source="ADR-0003",
            tags=("sqlite", "schema"),
        )
        record_id = self.store.store(original)
        loaded = self.store.get(record_id)

        assert loaded is not None
        self.assertEqual(record_id, loaded.record_id)
        self.assertEqual(original.category, loaded.category)
        self.assertEqual(original.content, loaded.content)
        self.assertEqual(original.verification, loaded.verification)
        self.assertEqual("ADR-0003", loaded.source)
        self.assertEqual(("schema", "sqlite"), loaded.tags)

    def test_timestamps_are_timezone_aware_utc(self) -> None:
        loaded = self.store.get(self.store.store(_record()))
        assert loaded is not None
        assert loaded.created_at is not None and loaded.updated_at is not None
        self.assertIsInstance(loaded.created_at, datetime)
        self.assertEqual(timezone.utc, loaded.created_at.tzinfo)
        self.assertEqual(timezone.utc, loaded.updated_at.tzinfo)

    def test_unknown_identifier_returns_none(self) -> None:
        self.assertIsNone(self.store.get("no-such-record"))

    def test_empty_content_is_refused(self) -> None:
        with self.assertRaises(MemoryStoreError):
            self.store.store(_record("   "))


class DeduplicationTest(MemoryStoreTestCase):
    def test_duplicate_content_in_same_category_is_rejected(self) -> None:
        self.store.store(_record())
        with self.assertRaises(DuplicateRecordError):
            self.store.store(_record())

    def test_same_content_in_another_category_is_accepted(self) -> None:
        first = self.store.store(_record(category=MemoryCategory.ARCHITECTURE))
        second = self.store.store(_record(category=MemoryCategory.IDEAS))
        self.assertNotEqual(first, second)

    def test_whitespace_differences_are_duplicates(self) -> None:
        self.store.store(_record("Memory  is\tpermanent."))
        with self.assertRaises(DuplicateRecordError):
            self.store.store(_record(" Memory is permanent. "))

    def test_case_differences_are_not_duplicates(self) -> None:
        self.store.store(_record("Memory is permanent."))
        self.store.store(_record("memory is permanent."))
        self.assertEqual(2, len(tuple(self.store.iter_records())))


class VerificationTest(MemoryStoreTestCase):
    def _stored_verified(self) -> str:
        record_id = self.store.store(_record())
        stored = self.store.get(record_id)
        assert stored is not None
        promoted = MemoryRecord(
            record_id=record_id,
            category=stored.category,
            content=stored.content,
            verification=VerificationState.VERIFIED,
        )
        self.store.store(promoted)
        return record_id

    def test_promotion_to_verified_succeeds(self) -> None:
        loaded = self.store.get(self._stored_verified())
        assert loaded is not None
        self.assertIs(VerificationState.VERIFIED, loaded.verification)

    def test_demotion_to_refuted_succeeds(self) -> None:
        record_id = self._stored_verified()
        stored = self.store.get(record_id)
        assert stored is not None
        self.store.store(
            MemoryRecord(
                record_id=record_id,
                category=stored.category,
                content=stored.content,
                verification=VerificationState.REFUTED,
            )
        )
        loaded = self.store.get(record_id)
        assert loaded is not None
        self.assertIs(VerificationState.REFUTED, loaded.verification)

    def test_unverified_may_not_overwrite_verified(self) -> None:
        record_id = self._stored_verified()
        with self.assertRaises(VerifiedRecordProtectedError):
            self.store.store(
                MemoryRecord(
                    record_id=record_id,
                    category=MemoryCategory.ARCHITECTURE,
                    content="Something a model guessed.",
                    verification=VerificationState.UNVERIFIED,
                )
            )

    def test_rule_holds_against_raw_sql(self) -> None:
        """The trigger, not merely the Python guard, enforces the rule."""
        record_id = self._stored_verified()
        connection = sqlite3.connect(self.database_path)
        self.addCleanup(connection.close)
        with self.assertRaises(sqlite3.IntegrityError):
            connection.execute(
                "UPDATE memory_records SET verification = 'unverified' "
                "WHERE record_id = ?",
                (record_id,),
            )

    def test_verified_record_keeps_its_content_after_rejection(self) -> None:
        record_id = self._stored_verified()
        with self.assertRaises(VerifiedRecordProtectedError):
            self.store.store(
                MemoryRecord(
                    record_id=record_id,
                    category=MemoryCategory.ARCHITECTURE,
                    content="Something a model guessed.",
                    verification=VerificationState.UNVERIFIED,
                )
            )
        loaded = self.store.get(record_id)
        assert loaded is not None
        self.assertEqual("Astra stores memory in SQLite.", loaded.content)


class IterationTest(MemoryStoreTestCase):
    def test_iter_records_streams(self) -> None:
        self.store.store(_record("First fact."))
        result = self.store.iter_records()
        self.assertIsInstance(result, types.GeneratorType)
        self.assertNotIsInstance(result, (list, tuple))

    def test_iter_records_filters_by_category(self) -> None:
        self.store.store(_record("A design note.", category=MemoryCategory.ARCHITECTURE))
        self.store.store(_record("An idea.", category=MemoryCategory.IDEAS))
        ideas = tuple(self.store.iter_records(category=MemoryCategory.IDEAS))
        self.assertEqual(1, len(ideas))
        self.assertEqual("An idea.", ideas[0].content)


class DeletionTest(MemoryStoreTestCase):
    def test_delete_removes_record_tags_and_search_entry(self) -> None:
        record_id = self.store.store(_record(tags=("sqlite",)))
        self.assertTrue(self.store.delete(record_id))
        self.assertIsNone(self.store.get(record_id))

        connection = sqlite3.connect(self.database_path)
        self.addCleanup(connection.close)
        tags = connection.execute(
            "SELECT COUNT(*) FROM memory_tags WHERE record_id = ?", (record_id,)
        ).fetchone()[0]
        self.assertEqual(0, tags)

        if self.store.search_mode == "fts5":
            indexed = connection.execute(
                "SELECT COUNT(*) FROM memory_fts WHERE record_id = ?", (record_id,)
            ).fetchone()[0]
            self.assertEqual(0, indexed)

    def test_deleting_an_unknown_record_returns_false(self) -> None:
        self.assertFalse(self.store.delete("no-such-record"))


class SearchTest(MemoryStoreTestCase):
    def setUp(self) -> None:
        super().setUp()
        self.store.store(
            _record("The vector index is derived and disposable.",
                    category=MemoryCategory.ARCHITECTURE)
        )
        self.store.store(
            _record("Ollama runs GGUF models locally.",
                    category=MemoryCategory.RESEARCH)
        )

    def test_search_mode_is_reported(self) -> None:
        self.assertIn(self.store.search_mode, {"fts5", "like"})

    def test_search_finds_a_record_by_word(self) -> None:
        results = self.store.search("disposable")
        self.assertEqual(1, len(results))
        self.assertIn("disposable", results[0].content)

    def test_search_filters_by_category(self) -> None:
        self.assertEqual(
            0, len(self.store.search("disposable", category=MemoryCategory.RESEARCH))
        )
        self.assertEqual(
            1, len(self.store.search("disposable", category=MemoryCategory.ARCHITECTURE))
        )

    def test_search_respects_the_limit(self) -> None:
        self.assertEqual(1, len(self.store.search("the", limit=1)))

    def test_empty_query_returns_nothing(self) -> None:
        self.assertEqual((), self.store.search("   "))

    def test_punctuation_in_a_query_is_not_read_as_syntax(self) -> None:
        # An unquoted '(' or '"' would be an FTS5 syntax error.
        self.assertEqual((), self.store.search('nonexistent" OR ('))


class ReopenTest(MemoryStoreTestCase):
    def test_records_survive_reopening_the_database(self) -> None:
        record_id = self.store.store(_record(tags=("durable",)))
        self.store.close()

        reopened = SQLiteMemoryStore(self.database_path)
        reopened.initialise()
        self.addCleanup(reopened.close)

        loaded = reopened.get(record_id)
        assert loaded is not None
        self.assertEqual("Astra stores memory in SQLite.", loaded.content)
        self.assertEqual(("durable",), loaded.tags)


class EmbeddingProvenanceTest(MemoryStoreTestCase):
    """ADR-0012 D5: provenance columns, not MemoryRecord fields."""

    def test_round_trip(self) -> None:
        from datetime import datetime, timezone
        from astra.memory.contracts import EmbeddingProvenance

        record_id = self.store.store(_record())
        now = datetime(2026, 8, 14, 12, 0, 0, tzinfo=timezone.utc)
        self.store.set_embedding_provenance(
            record_id, "nomic-embed-text-v1.5", "nomic-embed-text-v1.5", 768, now
        )
        got = self.store.get_embedding_provenance(record_id)
        self.assertIsInstance(got, EmbeddingProvenance)
        assert got is not None
        self.assertEqual("nomic-embed-text-v1.5", got.embedding_model)
        self.assertEqual(768, got.embedding_dim)
        self.assertEqual(record_id, got.record_id)
        loaded = self.store.get(record_id)
        assert loaded is not None
        self.assertFalse(hasattr(loaded, "embedding_model"))

    def test_clear_removes_provenance(self) -> None:
        record_id = self.store.store(_record())
        self.store.set_embedding_provenance(record_id, "m", "m", 8)
        self.store.clear_embedding_provenance(record_id)
        self.assertIsNone(self.store.get_embedding_provenance(record_id))

    def test_unknown_id_get_returns_none(self) -> None:
        self.assertIsNone(self.store.get_embedding_provenance("no-such-id"))

    def test_unknown_id_set_raises(self) -> None:
        with self.assertRaises(MemoryStoreError):
            self.store.set_embedding_provenance("no-such-id", "m", "m", 8)

    def test_clear_unknown_id_is_noop(self) -> None:
        self.store.clear_embedding_provenance("no-such-id")

    def test_verified_record_still_protected_after_provenance_write(self) -> None:
        record_id = self.store.store(
            _record(verification=VerificationState.VERIFIED)
        )
        self.store.set_embedding_provenance(record_id, "m", "m", 8)
        with self.assertRaises(VerifiedRecordProtectedError):
            self.store.store(
                _record(
                    record_id=record_id,
                    verification=VerificationState.UNVERIFIED,
                )
            )


class StoreImportBoundaryTest(unittest.TestCase):
    def test_sqlite_store_has_no_index_or_runtime_imports(self) -> None:
        source = Path(__file__).parent.parent / "astra" / "memory" / "sqlite_store.py"
        text = source.read_text(encoding="utf-8")
        for forbidden in (
            "import faiss",
            "import numpy",
            "import llama_cpp",
            "astra.reasoning",
            "astra.memory.faiss_index",
            "astra.memory.indexer",
        ):
            self.assertNotIn(forbidden, text, f"{forbidden!r} found in sqlite_store.py")


if __name__ == "__main__":
    unittest.main()
