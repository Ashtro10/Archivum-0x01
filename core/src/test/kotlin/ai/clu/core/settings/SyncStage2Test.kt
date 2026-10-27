package ai.clu.core.settings

import ai.clu.core.*
import ai.clu.core.config.Settings
import ai.clu.core.db.Schema
import ai.clu.core.diary.DiaryRepository
import ai.clu.core.sync.SyncBundle
import ai.clu.core.sync.SyncRow
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Stage 2 continuity between the phone and the tablet: diary sync as its own opt-in scope, conflict safety, and the
 * encrypted sync file for when the devices can't reach each other. Two independent installations talk over real
 * localhost TCP with the production pairing and encryption (no device involved).
 */
class SyncStage2Test {
    private val h = SyncHarness()
    @AfterEach fun tearDown() = h.close()

    @Test fun diaryScope() {
        h.pair()
        val e = h.phone.diary.add("Walked to the lake with Riya.", date = "2025-10-20")
        assertFalse(h.phone.settings.bool(Settings.SYNC_DIARY), "the diary is private: not synced by default")
        assertTrue(h.sync().ok)
        assertNull(h.tablet.diary.get(e.id), "nothing sent while diary sync is off")
        // Turned on later: entries written before are still sent.
        h.phone.settings.set(Settings.SYNC_DIARY, true); h.tablet.settings.set(Settings.SYNC_DIARY, true)
        assertTrue(h.sync().ok)
        assertEquals("Walked to the lake with Riya.", h.tablet.diary.get(e.id)!!.body)
        // Edits and deletions travel both ways; nothing is duplicated.
        h.tablet.diary.save(h.tablet.diary.get(e.id)!!.copy(body = "Walked to the lake with Riya and Arjun."))
        assertTrue(h.sync().ok)
        assertEquals("Walked to the lake with Riya and Arjun.", h.phone.diary.get(e.id)!!.body)
        h.phone.diary.delete(e.id); assertTrue(h.sync().ok)
        assertNull(h.tablet.diary.get(e.id)); assertEquals(0, h.tablet.diary.count())
        assertEquals(0, h.tablet.memories.count(), "synced diary entries are still not memories")
    }

    @Test fun offlineEditsMergeAndNewerIsNeverOverwrittenByOlder() {
        h.pair()
        val shared = h.memory(h.phone, "Thesis status", "Thesis: chapter 3 in progress.")
        assertTrue(h.sync().ok)
        // Both devices work offline, editing different things: both survive.
        h.memory(h.phone, "Phone-only note"); h.memory(h.tablet, "Tablet-only note")
        // The same memory is then edited on both; the tablet's edit is the later one.
        h.phone.memories.save(h.phone.memories.get(shared.id)!!.copy(content = "Thesis: chapter 4 in progress."))
        Thread.sleep(5)
        h.tablet.memories.save(h.tablet.memories.get(shared.id)!!.copy(content = "Thesis: chapter 4 drafted, chapter 5 started."))
        val r = h.sync()
        assertTrue(r.ok); assertTrue(r.conflicts >= 1, "a concurrent edit is detected and counted")
        for (c in listOf(h.phone, h.tablet)) {
            assertEquals("Thesis: chapter 4 drafted, chapter 5 started.", c.memories.get(shared.id)!!.content, "the newer edit wins on both devices")
            assertTrue(c.memories.list().map { it.title }.containsAll(listOf("Phone-only note", "Tablet-only note")))
        }
        // An older version arriving late is ignored.
        val stale = h.phone.syncEngine.allRows(setOf(ai.clu.core.memory.MemoryRepository.TABLE)).first { it.id == shared.id }
        val older = stale.copy(hlc = "0000000000000-0000-old", data = JsonObject(stale.data + ("content" to JsonPrimitive("Thesis: chapter 1."))))
        assertEquals(0, h.tablet.syncEngine.apply(listOf(older), "x").applied)
        assertEquals("Thesis: chapter 4 drafted, chapter 5 started.", h.tablet.memories.get(shared.id)!!.content)
        // With "keep both", a concurrent diary edit is kept as a copy instead of being lost.
        listOf(h.phone, h.tablet).forEach { it.settings.set(Settings.SYNC_DIARY, true); it.settings.set(Settings.CONFLICT_POLICY, "keep_both") }
        val d = h.phone.diary.add("Trip plan: Goa in December.", date = "2025-10-01"); assertTrue(h.sync().ok)
        h.phone.diary.save(h.phone.diary.get(d.id)!!.copy(body = "Trip plan: Goa in January."))
        h.tablet.diary.save(h.tablet.diary.get(d.id)!!.copy(body = "Trip plan: Kerala in December."))
        assertTrue(h.sync().ok)
        assertTrue(h.tablet.diary.list().any { it.title.endsWith("(conflict copy)") } || h.phone.diary.list().any { it.title.endsWith("(conflict copy)") },
            "keep-both keeps the other version as a copy")
    }

    @Test fun encryptedSyncFileMergesWithoutDuplicates() {
        // No pairing and no network between the devices: a file carried over by the owner.
        val m = h.memory(h.phone, "Preferred airline", "Owner prefers IndiGo window seats.")
        h.phone.settings.set(Settings.RESPONSE_LENGTH, "brief") // shared setting
        h.phone.settings.set(Settings.THREADS, 3) // device setting: never leaves the device
        val d = h.phone.diary.add("Private diary line about the exam.", date = "2025-10-05")
        val out = h.phone.syncFiles.export("correct horse battery", includeDiary = false)
        assertEquals(0, out.diary); assertTrue(out.memories >= 1)
        val text = String(out.bytes)
        assertFalse(text.contains("IndiGo") || text.contains("exam"), "contents are encrypted")
        assertFailsWith<SyncBundle.BundleException> { h.tablet.syncFiles.import(out.bytes, "wrong passphrase", includeDiary = true) }
        val tampered = text.replace("\"createdAt\":", "\"createdAt\":1").toByteArray()
        assertFailsWith<SyncBundle.BundleException> { h.tablet.syncFiles.import(tampered, "correct horse battery", includeDiary = true) }
        assertFailsWith<SyncBundle.BundleException> { h.phone.syncFiles.import(out.bytes, "correct horse battery", includeDiary = true) }
        val r = h.tablet.syncFiles.import(out.bytes, "correct horse battery", includeDiary = true)
        assertTrue(r.applied >= 2)
        assertEquals("Owner prefers IndiGo window seats.", h.tablet.memories.get(m.id)!!.content)
        assertEquals("brief", h.tablet.settings.string(Settings.RESPONSE_LENGTH))
        assertNotEquals(3, h.tablet.settings.int(Settings.THREADS), "device settings are not in the file")
        assertNull(h.tablet.diary.get(d.id), "the diary was not exported")
        assertEquals(0, h.tablet.syncFiles.import(out.bytes, "correct horse battery", includeDiary = true).applied, "importing again changes nothing")
        // A newer local edit is not overwritten by the older file; the diary only when chosen on both sides.
        h.tablet.memories.save(h.tablet.memories.get(m.id)!!.copy(content = "Owner prefers Air India aisle seats."))
        val withDiary = h.phone.syncFiles.export("correct horse battery", includeDiary = true)
        assertEquals(1, withDiary.diary)
        h.tablet.syncFiles.import(withDiary.bytes, "correct horse battery", includeDiary = false)
        assertNull(h.tablet.diary.get(d.id))
        h.tablet.syncFiles.import(withDiary.bytes, "correct horse battery", includeDiary = true)
        assertEquals("Private diary line about the exam.", h.tablet.diary.get(d.id)!!.body)
        assertEquals("Owner prefers Air India aisle seats.", h.tablet.memories.get(m.id)!!.content, "the newer local edit stays")
        assertFailsWith<IllegalArgumentException> { h.phone.syncFiles.export("short", includeDiary = false) }
    }

    @Test fun diaryMigrationKeepsEntriesAndMakesThemSyncable() {
        val db = tempDb()
        Schema.migrations.take(2).forEach { step -> step.forEach { db.execute(it) } }; db.userVersion = 2
        db.execute("""INSERT INTO diary_entries(id, entry_date, title, body, source, conversation_id, memory_id, created_at, updated_at, deleted)
            VALUES('d1','2025-09-01','Old entry','Written before 1.5.0.','manual',NULL,NULL,1,1,0)""")
        val c = newCore(db = db)
        assertEquals(Schema.currentVersion, db.userVersion)
        assertEquals("Written before 1.5.0.", c.diary.get("d1")!!.body)
        assertEquals(1, c.diary.count(), "no duplicates")
        assertEquals(1L, db.query("SELECT COUNT(*) FROM change_log WHERE table_name = ? AND row_id = 'd1'", listOf(DiaryRepository.TABLE)) { it.long(0) ?: 0L }.single())
    }
}
