package ai.clu.core

import ai.clu.core.config.Settings
import ai.clu.core.history.Kind
import ai.clu.core.history.Relation
import ai.clu.core.memory.*
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Duplicates, near-duplicates and expiry: tidy memory without ever losing what the owner entered. */
class ConsolidationTest {
    private val clock = ai.clu.core.util.ManualClock(1_760_000_000_000L)
    private val core = newCore(clock = clock)
    private val svc get() = core.memory
    private val live get() = core.memories.list(null, limit = 5000)

    private fun mem(title: String, content: String, cat: MemoryCategory = MemoryCategory.CORE_FACT, people: List<String> = emptyList(), date: String? = null): Memory {
        clock.advance(60_000)
        return svc.remember(Memory(category = cat, title = title, content = content, source = MemorySource.OWNER_SUPPLIED, people = people,
            eventDate = date?.let { ai.clu.core.history.DateParser.parse(it)!! } ?: ai.clu.core.history.HistoricalDate.UNKNOWN)).memory
    }

    @Test fun autoMergeKeepsHistory() {
        val a = mem("Sister's name", "My sister's name is Mira.")
        val b = mem("Sister's name", "My sister's name is Mira.")
        svc.relate(Kind.MEMORY, b.id, Kind.MEMORY, mem("Mira in Pune", "Mira lives in Pune and is a paediatrician.").id, Relation.RELATED)
        core.memories.save(b.copy(importance = 2))
        val r = core.consolidator.run()
        assertEquals(1, r.autoMerged)
        val kept = core.memories.get(a.id)!!
        assertFalse(kept.deleted); assertEquals(2, kept.importance, "the better of the two importances is kept")
        val gone = core.memories.get(b.id)!!
        assertTrue(gone.deleted); assertEquals(a.id, gone.mergedInto, "the folded record points at the survivor")
        assertTrue(core.memories.revisions(b.id).isNotEmpty(), "its earlier text is still in the history")
        assertTrue(svc.relatedTo(a.id).any { it.title == "Mira in Pune" }, "relationships moved to the survivor")
        val log = core.consolidator.activity().first { it.action == "merge" }
        assertEquals("AUTO", log.status); assertEquals(listOf(a.id, b.id), log.memoryIds); assertEquals(a.id, log.resultId)
        assertEquals(listOf("Sister's name"), svc.detail(a.id)!!.provenance.mergedFrom, "the detail view traces the merge")
        assertEquals(1, svc.meta(a.id)!!.reinforced, "the repeat is counted as reinforcement")
        // Searching finds one record, not two.
        assertEquals(1, svc.recall(RecallRequest("sister Mira", includeDocuments = false, expandRelated = false)).count { it.memory!!.title == "Sister's name" })
        // A second run has nothing left to do.
        assertEquals(0, core.consolidator.run().autoMerged)
    }

    @Test fun nearDuplicatesAreOnlySuggestedAndTheOwnerDecides() {
        val a = mem("Chose STM32", "Decided to use an STM32 board instead of Arduino for the hexapod project.", MemoryCategory.DECISION)
        val b = mem("Chose STM32", "Decided to use an STM32 board instead of an Arduino for the hexapod robot project.", MemoryCategory.DECISION)
        val r = core.consolidator.run()
        assertEquals(0, r.autoMerged); assertEquals(1, r.suggested)
        assertEquals(2, live.size, "nothing merged without approval")
        val s = core.consolidator.pending().single()
        assertEquals(setOf(a.id, b.id), s.memoryIds.toSet())
        val merged = core.consolidator.approve(s.id)
        assertEquals(1, live.size)
        assertTrue(merged.content.contains("Arduino for the hexapod") && merged.content.contains("an Arduino for the hexapod robot"), "both texts are kept in the merged record: ${merged.content}")
        assertEquals("APPROVED", core.consolidator.activity().first { it.id == s.id }.status)
    }

    @Test fun aRejectedSuggestionIsNotMadeAgain() {
        mem("Tea", "I prefer green tea in the evening and avoid coffee after four.", MemoryCategory.PREFERENCE)
        mem("Tea", "I prefer green tea in the evenings and avoid coffee after four.", MemoryCategory.PREFERENCE)
        assertEquals(1, core.consolidator.run().suggested)
        core.consolidator.reject(core.consolidator.pending().single().id)
        mem("Unrelated", "The hexapod needs a heavier battery.", MemoryCategory.PROJECT)
        val again = core.consolidator.run()
        assertEquals(0, again.suggested); assertEquals(0, core.consolidator.pending().size); assertEquals(3, live.size)
    }

    @Test fun differentThingsAreNeverMerged() {
        mem("Meeting", "Meeting with Mira on Friday about the robot.", people = listOf("Mira"))
        mem("Meeting", "Meeting with Mira on Friday about the robot.", people = listOf("Kabir"))        // different person
        mem("Meeting", "Meeting with Mira on Friday about the robot.", cat = MemoryCategory.EXPERIENCE)   // different kind of memory
        mem("Exam", "Exam on Friday.", date = "March 2018"); mem("Exam", "Exam on Friday.", date = "May 2019")  // different dates
        val r = core.consolidator.run()
        assertEquals(0, r.autoMerged); assertEquals(0, r.suggested, "different people, kinds or dates are not even suggested"); assertEquals(5, live.size)
    }

    @Test fun nothingIsLostWhenALargeRealisticCollectionIsConsolidated() {
        val distinct = (1..120).map { i -> mem("Note $i on ${listOf("gait", "battery", "firmware", "demo", "budget", "travel")[i % 6]}", "Observation number $i about the ${listOf("gait", "battery", "firmware", "demo", "budget", "travel")[i % 6]} work, logged during week ${i / 7}.") }
        repeat(30) { i -> mem(distinct[i].title, distinct[i].content) } // exact repeats
        val t0 = System.nanoTime()
        var merged = 0
        while (true) { val r = core.consolidator.run(maxScan = 40); merged += r.autoMerged; if (r.scanned == 0) break }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(30, merged); assertEquals(120, live.size)
        // Every original text is still findable; every folded record is traceable.
        assertTrue(distinct.all { d -> live.any { it.content == d.content } })
        assertEquals(30, core.consolidator.activity(500).count { it.status == "AUTO" && it.action == "merge" })
        assertTrue(ms < 20_000, "consolidation of 150 records took $ms ms")
    }

    @Test fun aRunStopsWhenAskedAndTheNextRunContinues() {
        repeat(10) { i -> mem("Item $i", "Distinct content about subject$i and topic${i * 7}.") }
        mem("Item 0", "Distinct content about subject0 and topic0.")
        var calls = 0
        val first = core.consolidator.run(maxScan = 50, shouldStop = { calls++ >= 3 })
        assertTrue(first.scanned in 1..4, "stopped part-way: $first")
        val rest = core.consolidator.run(maxScan = 50)
        assertEquals(1, first.autoMerged + rest.autoMerged, "the duplicate is found whichever run reaches it")
        assertEquals(10, live.size)
    }

    @Test fun switchedOffNothingIsMerged() {
        core.settings.set(Settings.CONSOLIDATION, false)
        mem("A", "Same text here."); mem("A", "Same text here.")
        assertEquals(0, core.consolidator.run().autoMerged); assertEquals(2, live.size)
    }

    @Test fun expiredTemporaryMemoriesAndWorkingNotesAreRemovedAndLogged() {
        core.settings.set(Settings.TEMP_RETENTION, 1)
        mem("Parking", "Level 3", MemoryCategory.TEMPORARY); svc.working.put("call the clinic", WorkingMemory.Kind.TASK)
        clock.advance(2 * 86_400_000L)
        val r = core.consolidator.run()
        assertEquals(1, r.expiredMemories); assertEquals(1, r.expiredWorking)
        assertTrue(core.consolidator.activity().any { it.action == "expire" && it.reason.contains("1 expired temporary memories") })
    }
}
