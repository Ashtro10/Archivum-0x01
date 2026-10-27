package ai.clu.core.settings

import ai.clu.core.*
import ai.clu.core.config.Settings
import ai.clu.core.memory.*
import ai.clu.core.sync.PairingHandler
import ai.clu.core.sync.SyncBundle
import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Stage 3 data-integrity audit of phone ↔ tablet sync: deletions vs older copies, scope toggles, damaged sync files,
 * restarts right after syncing and simultaneous syncs. Two installations over real localhost TCP.
 */
class SyncIntegrityTest {
    private val h = SyncHarness()
    @AfterEach fun tearDown() = h.close()

    @Test fun olderCopiesNeverResurrectDeletedRecords() {
        h.pair()
        val m = h.memory(h.phone, "Old address", "Flat 7B, Baner.")
        val stale = h.phone.syncFiles.export("passphrase one", includeDiary = false) // exported before the deletion
        assertTrue(h.sync().ok)
        h.tablet.memories.delete(m.id)
        assertTrue(h.sync().ok)
        assertTrue(h.phone.memories.get(m.id)!!.deleted, "the deletion reached the phone")
        // A stale file and a stale live row from before the deletion change nothing.
        assertEquals(0, h.tablet.syncFiles.import(stale.bytes, "passphrase one", includeDiary = false).applied)
        h.phone.db.execute("UPDATE sync_peers SET acked_seq = 0") // the phone re-sends everything it has
        assertTrue(h.sync().ok)
        for (c in listOf(h.phone, h.tablet)) {
            assertTrue(c.memories.get(m.id)!!.deleted); assertEquals("", c.memories.get(m.id)!!.content, "deleted text stays erased")
            assertTrue(c.memories.search("Baner").isEmpty())
        }
        // The same for the diary.
        listOf(h.phone, h.tablet).forEach { it.settings.set(Settings.SYNC_DIARY, true) }
        val d = h.phone.diary.add("A private note.", date = "2025-10-01"); assertTrue(h.sync().ok)
        val staleDiary = h.phone.syncFiles.export("passphrase one", includeDiary = true)
        h.tablet.diary.delete(d.id); assertTrue(h.sync().ok)
        h.tablet.syncFiles.import(staleDiary.bytes, "passphrase one", includeDiary = true)
        assertNull(h.tablet.diary.get(d.id)); assertNull(h.phone.diary.get(d.id))
    }

    @Test fun recordsWrittenWhileAScopeWasOffAreSentWhenItIsTurnedOn() {
        h.pair()
        h.phone.settings.set(Settings.SYNC_MEMORIES, false)
        val m = h.memory(h.phone, "Written while memory sync was off")
        assertTrue(h.sync().ok)
        assertNull(h.tablet.memories.get(m.id))
        h.phone.settings.set(Settings.SYNC_MEMORIES, true)
        assertTrue(h.sync().ok)
        assertEquals("Written while memory sync was off", h.tablet.memories.get(m.id)?.title, "not lost because it was skipped earlier")
    }

    @Test fun damagedSyncFilesAreRefusedCleanly() {
        h.memory(h.phone, "Something")
        val good = String(h.phone.syncFiles.export("passphrase one", includeDiary = false).bytes)
        val damaged = listOf(good.take(good.length / 2), good.replace("\"data\":\"", "\"data\":\"AAAA"), good.reversed(), "", "{}", "not a file at all",
            good.replace("clu-sync-file", "other-format"), good.replace("\"iter\":210000", "\"iter\":1"), good.replace("\"v\":1", "\"v\":9"))
        for (d in damaged) {
            val e = assertFailsWith<SyncBundle.BundleException>("should be refused: ${d.take(60)}") { h.tablet.syncFiles.import(d.toByteArray(), "passphrase one", includeDiary = false) }
            assertTrue(e.message!!.isNotBlank())
        }
        assertEquals(0, h.tablet.memories.count(), "nothing half-imported")
    }

    @Test fun restartRightAfterSyncKeepsStateAndDoesNotDuplicate() {
        val phoneDb = tempDb(); val tabletDb = tempDb()
        val accept = PairingHandler { _, _, _ -> true }
        fun open(db: ai.clu.core.db.SqlDriver, name: String, role: String) = newCore(db = db, platform = TestPlatform().apply { this.name = name }).also { it.settings.set(Settings.DEVICE_ROLE, role) }
        var phone = open(phoneDb, "phone", "portable"); var tablet = open(tabletDb, "tablet", "home")
        var tn = tablet.syncNode(accept); var pn = phone.syncNode(accept); var port = tn.startServer(0)
        runBlocking { tn.openPairingWindow(); assertTrue(pn.pair("127.0.0.1", port).ok) }
        repeat(20) { phone.memories.save(Memory(category = MemoryCategory.MANUAL, title = "Note $it", content = "Note $it", source = MemorySource.OWNER_SUPPLIED)) }
        assertTrue(runBlocking { pn.syncWith("127.0.0.1", port) }.ok)
        tn.shutdown(); pn.shutdown()
        // Both apps are killed and restarted (same databases).
        phone = open(phoneDb, "phone", "portable"); tablet = open(tabletDb, "tablet", "home")
        tn = tablet.syncNode(accept); pn = phone.syncNode(accept); port = tn.startServer(0)
        assertEquals(1, phone.syncEngine.peers().size, "pairing survives a restart")
        phone.memories.save(Memory(category = MemoryCategory.MANUAL, title = "After restart", content = "After restart", source = MemorySource.OWNER_SUPPLIED))
        repeat(2) { assertTrue(runBlocking { pn.syncWith("127.0.0.1", port) }.ok) }
        assertEquals(21, tablet.memories.count(), "everything once, nothing duplicated")
        tn.shutdown(); pn.shutdown()
    }

    @Test fun simultaneousSyncsDoNotDuplicateOrCorrupt() {
        h.pair()
        repeat(60) { h.memory(h.phone, "Phone $it"); h.memory(h.tablet, "Tablet $it") }
        val results = runBlocking { (1..4).map { async(Dispatchers.IO) { h.phoneNode.syncWith("127.0.0.1", h.port) } }.awaitAll() }
        assertTrue(results.any { it.ok }, "$results")
        assertTrue(h.sync().ok)
        for (c in listOf(h.phone, h.tablet)) {
            assertEquals(120, c.memories.count())
            assertEquals(120, c.memories.list(limit = 1000).map { it.title }.toSet().size)
        }
    }
}
