package ai.clu.core.settings

import ai.clu.core.butler.Butler
import ai.clu.core.config.Settings
import ai.clu.core.db.Schema
import ai.clu.core.newCore
import ai.clu.core.tempDb
import kotlin.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Phone ↔ tablet continuity for tasks, lists and reminders: two independent installations talking over real localhost
 * TCP with the production pairing and encryption (integration; no device involved).
 */
class ButlerSyncTest {
    private val h = SyncHarness()
    @AfterEach fun tearDown() = h.close()

    @Test fun scope() {
        h.pair()
        assertTrue(h.phone.settings.bool(Settings.SYNC_TASKS), "tasks sync by default")
        val milk = h.phone.butler.add("milk", "shopping")
        val rem = h.phone.butler.add("call the bank", dueAt = 2_000_000_000_000L)
        assertTrue(h.sync().ok)
        assertEquals("milk", h.tablet.butler.get(milk.id)!!.title)
        assertEquals(rem.dueAt, h.tablet.butler.get(rem.id)!!.dueAt, "reminders travel with their time")
        // Turned off: new items stay on the device.
        h.phone.settings.set(Settings.SYNC_TASKS, false)
        val secret = h.phone.butler.add("surprise gift")
        assertTrue(h.sync().ok)
        assertNull(h.tablet.butler.get(secret.id))
        // Turned on again: what was written meanwhile is sent.
        h.phone.settings.set(Settings.SYNC_TASKS, true)
        assertTrue(h.sync().ok)
        assertEquals("surprise gift", h.tablet.butler.get(secret.id)!!.title)
    }

    @Test fun bothDirectionsNoDuplicatesDeletionsAndDeliveredOnce() {
        h.pair()
        val eggs = h.tablet.butler.add("eggs", "shopping")
        h.phone.butler.add("bread", "shopping")
        assertTrue(h.sync().ok); assertTrue(h.sync().ok, "syncing twice changes nothing")
        for (c in listOf(h.phone, h.tablet)) assertEquals(setOf("eggs", "bread"), c.butler.open("shopping").map { it.title }.toSet(), "no duplicates")
        // Bought on the phone: done on the tablet too.
        h.phone.butler.complete("eggs", "shopping"); assertTrue(h.sync().ok)
        assertTrue(h.tablet.butler.row(eggs.id)!!.done)
        // Removed on the tablet: removed on the phone (tombstone), never resurrected by an older copy.
        val bread = h.tablet.butler.open("shopping").single()
        h.tablet.butler.remove(bread.id); assertTrue(h.sync().ok)
        assertNull(h.phone.butler.get(bread.id)); assertTrue(h.phone.butler.open("shopping").isEmpty())
        // A reminder rung on the tablet isn't rung again on the phone.
        val due = h.tablet.butler.add("stretch", dueAt = 1_000L)
        assertTrue(h.sync().ok)
        h.tablet.butler.delivered(h.tablet.butler.get(due.id)!!)
        assertTrue(h.sync().ok)
        assertTrue(h.phone.butler.due(5_000_000_000_000L).none { it.id == due.id })
    }

    @Test fun offlineEditsConvergeAndTheNewerEditWins() {
        h.pair()
        val t = h.phone.butler.add("renew passport")
        assertTrue(h.sync().ok)
        // Both offline: the phone closes it, then the tablet edits the due time later (newer version).
        h.phone.butler.complete("renew passport")
        Thread.sleep(5)
        val tabletCopy = h.tablet.butler.get(t.id)!!
        h.tablet.butler.applyRemote(tabletCopy.copy(dueAt = 2_100_000_000_000L, hlc = h.tablet.store.hlc.now(), origin = h.tablet.store.deviceId))
        val r = h.sync()
        assertTrue(r.ok)
        val a = h.phone.butler.row(t.id)!!; val b = h.tablet.butler.row(t.id)!!
        assertEquals(a.hlc, b.hlc, "both devices hold the same version"); assertEquals(a.dueAt, b.dueAt); assertEquals(a.done, b.done)
    }

    @Test fun itemsFromBeforeSyncExistedAreSentOnceVersioned() {
        // A v7 database (1.9.0) with items that have no version.
        val db = tempDb()
        Schema.migrations.take(7).forEach { step -> step.forEach { db.execute(it) } }; db.userVersion = 7
        db.execute("INSERT INTO butler_items(id, title, list_name, due_at, recur, done, notified_at, created_at, updated_at, done_at) VALUES('old1','water plants','tasks',NULL,'',0,NULL,1,1,NULL)")
        val upgraded = newCore(db = db)
        assertEquals(8, db.userVersion)
        assertEquals("water plants", upgraded.butler.get("old1")!!.title, "the migration keeps items")
        assertEquals("", upgraded.butler.row("old1")!!.hlc)
        val (rows, _) = upgraded.syncEngine.changesSince(0)
        val sent = rows.single { it.table == Butler.TABLE && it.id == "old1" }
        assertTrue(sent.hlc.isNotEmpty(), "stamped before sending")
    }
}
