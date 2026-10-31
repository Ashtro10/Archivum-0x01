package ai.clu.core

import ai.clu.core.config.Settings
import ai.clu.core.history.Kind
import ai.clu.core.history.Relation
import ai.clu.core.memory.*
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Integrity checks, repair, rebuild of derived data, interrupted jobs and the background maintenance run. */
class MemoryHealthTest {
    private val clock = ai.clu.core.util.ManualClock(1_760_000_000_000L)
    private val core = newCore(clock = clock)
    private val db get() = core.store.db
    private val health get() = core.memoryHealth

    private fun seed(n: Int = 30): List<Memory> = (1..n).map { i ->
        clock.advance(1000)
        core.memory.remember(Memory(category = if (i % 3 == 0) MemoryCategory.KNOWLEDGE else MemoryCategory.EXPERIENCE, title = "Record $i",
            content = "Entry $i about ${listOf("servo", "battery", "gait", "firmware")[i % 4]} calibration, number ${i * 13}.", source = MemorySource.OWNER_SUPPLIED)).memory
    }

    @Test fun aHealthyStoreChecksOut() {
        seed(); val r = health.check()
        assertTrue(r.ok); assertEquals("ok", r.integrity); assertTrue(r.issues.isEmpty(), r.issues.toString()); assertEquals(30, r.memories)
    }

    @Test fun damageToDerivedDataIsFoundAndRepairedWithoutTouchingTheRecords() {
        val all = seed(); val a = all[0]; val b = all[1]
        db.execute("INSERT INTO memory_terms(memory_id, term, tf) VALUES('ghost','phantom',3)")
        db.execute("INSERT INTO archive_links(id, a_kind, a_id, b_kind, b_id, relation, note, created_at, deleted) VALUES('l1','memory',?, 'memory','vanished','related','',1,0)", listOf(a.id))
        db.execute("INSERT INTO memory_meta(memory_id, kind, updated_at) VALUES('ghost2','CORE',1)")
        db.execute("DELETE FROM memory_terms WHERE memory_id = ?", listOf(b.id))
        val r = health.check()
        assertTrue(r.ok, "SQLite itself is fine; these are our own consistency rules")
        assertEquals(setOf("orphan_terms", "dangling_links", "orphan_meta", "unindexed"), r.issues.map { it.code }.toSet(), r.issues.toString())
        assertTrue(core.memory.recall(RecallRequest("record 2", includeDocuments = false)).none { it.memory?.id == b.id && "keyword match" in it.why })
        val done = health.repair()
        assertTrue(done.any { it.contains("re-indexed 1") }, done.toString())
        assertTrue(health.check().issues.isEmpty())
        assertEquals(30, core.memories.count()); assertEquals(b.content, core.memories.get(b.id)!!.content)
        assertTrue(core.memory.recall(RecallRequest("Entry 2", includeDocuments = false, touch = false)).any { it.memory?.id == b.id }, "searchable again")
    }

    @Test fun aWipedKeywordIndexIsRebuiltFromTheRecords() {
        val all = seed(); db.execute("DELETE FROM memory_terms"); db.execute("DELETE FROM memory_stats")
        assertTrue(core.memory.recall(RecallRequest("servo calibration", includeDocuments = false)).isEmpty())
        assertEquals(30, health.reindexAll())
        assertEquals(30, core.memory.recall(RecallRequest("calibration", includeDocuments = false, limit = 40, expandRelated = false, touch = false)).size)
        assertTrue(all.all { m -> core.memory.recall(RecallRequest(m.content.substringBefore(" about"), includeDocuments = false, touch = false)).any { it.memory?.id == m.id } })
    }

    @Test fun aRebuildThatIsInterruptedResumesWhereItStopped() {
        seed(450); db.execute("DELETE FROM memory_terms")
        val first = health.reindexAll(maxBatches = 1)
        assertEquals(200, first)
        val job = Jobs.get(db, "reindex")!!
        assertEquals("RUNNING", job.state); assertTrue(job.checkpoint.isNotEmpty())
        // "Restart": a new core over the same database picks the job up and finishes the rest only.
        val c2 = newCore(db = db, clock = clock)
        val result = c2.maintenance.runOnce()
        assertTrue(result.steps.any { it.contains("interrupted re-index") }, result.steps.toString())
        assertEquals("IDLE", Jobs.get(db, "reindex")!!.state)
        assertTrue(c2.memoryHealth.check().issues.isEmpty(), c2.memoryHealth.check().issues.toString())
        assertEquals(450, c2.memories.count())
    }

    @Test fun backgroundMaintenanceIsBoundedCheckpointedAndYieldsWhenAsked() {
        seed(20); repeat(5) { core.memory.remember(Memory(category = MemoryCategory.CORE_FACT, title = "Dup", content = "Exactly the same words.", source = MemorySource.OWNER_SUPPLIED)); clock.advance(1000) }
        var checks = 0
        val stopped = core.maintenance.runOnce(shouldStop = { checks++ > 0 })
        assertNull(stopped.health); assertEquals("IDLE", Jobs.get(db, "maintenance")!!.state)
        val full = core.maintenance.runOnce()
        assertEquals(4, core.consolidator.activity(100).count { it.status == "AUTO" && it.action == "merge" }, "the five identical records were folded into one")
        assertNotNull(full.health, "the daily quick check ran"); assertTrue(full.health!!.ok)
        assertEquals(21, core.memories.count().toInt())
        clock.advance(3_600_000L)
        assertNull(core.maintenance.runOnce().health, "and not again within a day")
        assertTrue(Jobs.all(db).map { it.name }.containsAll(listOf("maintenance", "consolidate", "health")))
        // With memory off nothing breaks and nothing is written.
        core.settings.set(Settings.MEMORY_ENABLED, false)
        core.maintenance.runOnce(); assertEquals(21, core.memories.count().toInt())
    }

    @Test fun sqliteIntegrityProblemsAreReportedAsNotRepairable() {
        seed(5)
        val r = health.check(quick = true)
        assertTrue(r.ok && r.integrity == "ok")
        // The report type carries a failed integrity result unchanged (a corrupt file is exercised end to end in the desktop backup tests).
        val bad = HealthReport(false, "*** in database main *** Page 7: btree error", listOf(HealthIssue("sqlite_integrity", 1, "x", false)), 5, 0, 0)
        assertFalse(bad.ok); assertFalse(bad.issues.single().repairable)
    }

    @Test fun relationshipsToDeletedRecordsDoNotSurviveADeletion() {
        val (a, b) = seed(2)
        core.memory.relate(Kind.MEMORY, a.id, Kind.MEMORY, b.id, Relation.RELATED)
        core.memory.forget(b.id)
        assertTrue(health.check().issues.isEmpty()); assertTrue(core.memory.relatedTo(a.id).isEmpty())
    }
}
