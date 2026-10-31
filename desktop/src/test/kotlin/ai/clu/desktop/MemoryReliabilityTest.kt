package ai.clu.desktop

import ai.clu.core.db.Schema
import ai.clu.core.history.DateParser
import ai.clu.core.llm.LlmStatus
import ai.clu.core.memory.*
import ai.clu.desktop.ResourceGovernor.Companion.GB
import org.junit.jupiter.api.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** The database on a real PC: backups while running, recovery from damage, safe upgrades, restarts, and being polite to the machine. */
class MemoryReliabilityTest {
    private val dir = Files.createTempDirectory("clu-rel").toFile().also { d -> Runtime.getRuntime().addShutdownHook(Thread { d.deleteRecursively() }) }
    private fun paths(name: String = "home") = DesktopPaths(File(dir, name)).ensure()
    private fun cfg() = HostConfig(maintenanceMinutes = 0)

    private fun fill(host: RunningHost, n: Int) = (1..n).forEach { i ->
        host.core.memory.remember(Memory(category = if (i % 3 == 0) MemoryCategory.KNOWLEDGE else MemoryCategory.EXPERIENCE, title = "Entry $i",
            content = "Observation $i about ${listOf("servo", "battery", "gait", "firmware", "demo")[i % 5]} work: ${"detail ".repeat(20)}number ${i * 17}", source = MemorySource.OWNER_SUPPLIED,
            eventDate = DateParser.parse("March ${2000 + i % 25}")!!)) }

    @Test fun memoryAndItsMetadataSurviveAStopAndStartOfTheHost() {
        val p = paths()
        val first = Host.start(p, cfg(), port = 0, log = {})
        val id = first.core.memory.remember(Memory(category = MemoryCategory.CORE_FACT, title = "Allergy", content = "Allergic to penicillin.", source = MemorySource.OWNER_SUPPLIED, importance = 2),
            sourceRef = "conversation:c1", confidence = 0.95).memory.id
        first.core.memory.working.put("Parked on level 3")
        first.core.memory.recall(RecallRequest("penicillin"))
        first.stop()
        val second = Host.start(p, cfg(), port = 0, log = {})
        try {
            val hit = second.core.memory.recall(RecallRequest("penicillin allergy", touch = false)).first()
            assertEquals(id, hit.memory!!.id)
            val meta = second.core.memory.meta(id)!!
            assertEquals("conversation:c1", meta.sourceRef); assertEquals(0.95, meta.confidence); assertEquals(1, meta.accessCount)
            assertEquals(1, second.core.memory.working.count())
        } finally { second.stop() }
    }

    @Test fun aBackupIsConsistentWhileTheHostKeepsWritingAndAnInterruptedOneLeavesNothingBehind() {
        val p = paths(); val host = Host.start(p, cfg(), port = 0, log = {})
        try {
            fill(host, 200)
            val writer = Thread { repeat(150) { i -> host.core.memory.remember(Memory(category = MemoryCategory.KNOWLEDGE, title = "Live $i", content = "Written during the backup number $i", source = MemorySource.OWNER_SUPPLIED)) } }
            writer.start()
            val backups = (1..3).map { DatabaseBackup.create(host.core.db, p.backups, System.currentTimeMillis() + it * 1000L, "manual$it", keep = 10) }
            writer.join()
            backups.forEach { b -> val v = DatabaseBackup.verify(b.file); assertTrue(v.ok, v.toString()); assertTrue(v.memories in 200..350, "a consistent snapshot, not a torn one: ${v.memories}"); assertEquals(Schema.currentVersion, v.schemaVersion) }
            // A leftover of an interrupted backup is not mistaken for one, and is cleaned up by the next backup.
            File(p.backups, "clu-19700101-000000-crashed.db.part").writeBytes(ByteArray(100) { 7 })
            assertTrue(DatabaseBackup.list(p.backups).none { it.file.name.endsWith(".part") })
            DatabaseBackup.create(host.core.db, p.backups, System.currentTimeMillis() + 9000, "manual4", keep = 10)
            assertFalse(File(p.backups, "clu-19700101-000000-crashed.db.part").exists())
            // Rotation keeps the newest N of each kind.
            repeat(4) { i -> DatabaseBackup.create(host.core.db, p.backups, System.currentTimeMillis() + 20_000 + i * 1000L, "auto", keep = 2) }
            assertEquals(2, DatabaseBackup.list(p.backups).count { it.label == "auto" })
        } finally { host.stop() }
    }

    @Test fun aDamagedDatabaseIsNeverOpenedAndTheNewestGoodBackupBringsItBack() {
        val p = paths()
        val host = Host.start(p, cfg(), port = 0, log = {})
        fill(host, 300)
        val good = DatabaseBackup.create(host.core.db, p.backups, System.currentTimeMillis(), "manual", keep = 5)
        host.stop()
        // Damage the live file: overwrite the middle of it with garbage (a bad disk sector, a crashed copy).
        val size = p.database.length(); assertTrue(size > 50_000, "a real database: $size bytes")
        RandomAccessFile(p.database, "rw").use { f -> f.seek(size / 3); f.write(ByteArray(8192) { (it * 31).toByte() }) }
        val err = assertFailsWith<DatabaseDamagedException> { Host.start(p, cfg(), port = 0, log = {}) }
        assertTrue(err.message!!.contains("did not pass its integrity check") && err.message!!.contains("--restore-backup") && err.message!!.contains(good.file.name), err.message)
        // A corrupted backup is refused too, and nothing is moved.
        val bad = File(dir, "bad.db"); good.file.copyTo(bad); RandomAccessFile(bad, "rw").use { f -> f.seek(bad.length() / 3); f.write(ByteArray(8192) { 9 }) }
        val before = p.database.length()
        assertFailsWith<IllegalArgumentException> { DatabaseBackup.restore(bad, p, System.currentTimeMillis()) }
        assertEquals(before, p.database.length())
        // Restoring the good one: the damaged file is kept aside, never deleted, and CLU starts with the data.
        val aside = DatabaseBackup.restore(good.file, p, System.currentTimeMillis())!!
        assertTrue(aside.exists() && aside.length() == before, "the damaged database is kept: $aside")
        val again = Host.start(p, cfg(), port = 0, log = {})
        try {
            assertEquals(300, again.core.memories.count().toInt())
            assertEquals("Entry 150", again.core.memory.recall(RecallRequest("Observation 150 about", includeDocuments = false, touch = false)).first().memory!!.title)
            assertTrue(again.core.memoryHealth.check().ok)
        } finally { again.stop() }
    }

    @Test fun aDatabaseWithNoBackupGetsAnHonestMessage() {
        val p = paths(); Files.write(p.database.toPath(), ByteArray(4096) { 1 })
        val err = assertFailsWith<DatabaseDamagedException> { Host.start(p, cfg(), port = 0, log = {}) }
        assertTrue(err.message!!.contains("no usable backup") && err.message!!.contains("rather than deleting it"), err.message)
        assertEquals(4096, p.database.length(), "left exactly as it was")
    }

    @Test fun anOldDatabaseIsBackedUpBeforeItIsUpgradedAndKeepsItsData() {
        val p = paths()
        val raw = JdbcSqlDriver(p.database.absolutePath)
        Schema.migrations.take(4).forEach { step -> step.forEach { raw.execute(it) } }; raw.userVersion = 4
        raw.execute("""INSERT INTO memories(id, category, title, content, summary, retain_mode, event_at, event_has_time, source, people, tags, pinned, scope,
            expires_at, created_at, updated_at, deleted, hlc, origin) VALUES('old1','CORE_FACT','Exam','Exam on Friday.',NULL,'FULL',NULL,0,'OWNER_SUPPLIED','[]','[]',0,'clu',NULL,1700000000000,1700000000000,0,'h','o')""")
        raw.close()
        val logs = mutableListOf<String>()
        val host = Host.start(p, cfg(), port = 0, log = { logs += it })
        try {
            assertEquals(Schema.currentVersion, host.core.db.userVersion)
            assertEquals("Exam on Friday.", host.core.memories.get("old1")!!.content)
            val b = DatabaseBackup.list(p.backups).single { it.label == "pre-upgrade-v4" }
            val v = DatabaseBackup.verify(b.file); assertTrue(v.ok); assertEquals(4, v.schemaVersion, "the copy is the old schema, untouched by the upgrade"); assertEquals(1, v.memories)
            assertTrue(logs.any { it.contains("backed up") })
        } finally { host.stop() }
    }

    // ---------------------------------------------------------------------------------------- resources

    private class Probe(var free: Long) : MemoryProbe {
        override fun systemAvailableBytes() = free; override fun systemTotalBytes() = 32 * GB; override fun processBytes() = GB / 4
    }

    @Test fun backgroundWorkWaitsWhileTheModelOrThePcIsBusyAndRunsWhenIdle() {
        val p = paths(); val host = Host.start(p, cfg(), port = 0, log = {})
        try {
            fill(host, 5)
            host.core.memory.remember(Memory(category = MemoryCategory.CORE_FACT, title = "Dup", content = "Exactly the same words.", source = MemorySource.OWNER_SUPPLIED))
            host.core.memory.remember(Memory(category = MemoryCategory.CORE_FACT, title = "Dup", content = "Exactly the same words.", source = MemorySource.OWNER_SUPPLIED))
            val probe = Probe(free = 24 * GB); var modelBusy = false
            val gov = ResourceGovernor(GovernorConfig(16 * GB, 2 * GB, (1.5 * GB).toLong()), probe, { LlmStatus.NO_MODEL }, {})
            val sched = MaintenanceScheduler(host.core, gov, p, 0, {}, modelBusy = { modelBusy })
            val before = host.core.memories.count()
            // The model is generating: nothing runs, nothing changes.
            modelBusy = true
            sched.tick().let { assertFalse(it.ran); assertTrue(it.summary.contains("model is busy")) }
            assertEquals(before, host.core.memories.count())
            // The PC is short of memory (free < reserve 2 GB + pressure 1.5 GB): nothing runs either.
            modelBusy = false; probe.free = 3 * GB
            sched.tick().let { assertFalse(it.ran); assertTrue(it.summary.contains("short of memory")) }
            assertEquals(before, host.core.memories.count())
            // Idle: it runs, folds the duplicate, and makes the daily backup.
            probe.free = 24 * GB
            val o = sched.tick(); assertTrue(o.ran, o.summary); assertTrue(o.summary.contains("merged 1"), o.summary); assertTrue(o.summary.contains("backup"), o.summary)
            assertEquals(before - 1, host.core.memories.count())
            // Asked again right away: no second backup within a day.
            assertFalse(sched.tick().summary.contains("backup"))
        } finally { host.stop() }
    }

    @Test fun aRunStopsAsSoonAsThePcBecomesBusyAndTheRestWaitsForTheNextRun() {
        val p = paths(); val host = Host.start(p, cfg(), port = 0, log = {})
        try {
            fill(host, 40)
            val busyAfter = AtomicInteger(3); val calls = AtomicInteger()
            val gov = ResourceGovernor(GovernorConfig(16 * GB, 2 * GB, (1.5 * GB).toLong()), Probe(24 * GB), { LlmStatus.NO_MODEL }, {})
            val sched = MaintenanceScheduler(host.core, gov, p, 0, {}, modelBusy = { calls.incrementAndGet() > busyAfter.get() })
            val r = sched.tick()
            assertTrue(r.ran, r.summary)
            val job = ai.clu.core.memory.Jobs.get(host.core.db, "consolidate")
            assertTrue(job == null || job.detail.contains("scanned"), "it stopped early and recorded where: $job")
            busyAfter.set(Int.MAX_VALUE); calls.set(0)
            sched.tick(force = true)
            assertEquals(40L, host.core.memories.count(), "nothing lost whichever run did the work")
        } finally { host.stop() }
    }

    @Test fun retrievalStaysFastAndLightOnALargeStoreAndBackgroundIndexingIsBounded() {
        val p = paths(); val host = Host.start(p, cfg(), port = 0, log = {})
        try {
            val words = listOf("servo", "battery", "gait", "firmware", "demo", "budget", "travel", "robot", "python", "kotlin", "chassis", "sensor", "motor", "wiring", "thesis")
            val rnd = java.util.Random(42)
            host.core.store.db.transaction {
                repeat(3000) { i ->
                    val w = (1..6).joinToString(" ") { words[rnd.nextInt(words.size)] }
                    host.core.memories.save(Memory(category = if (i % 4 == 0) MemoryCategory.KNOWLEDGE else MemoryCategory.EXPERIENCE, title = "Note $i on ${words[i % words.size]}",
                        content = "Log entry $i: $w. ${"filler text ".repeat(10)}unique${i}", source = MemorySource.OWNER_SUPPLIED))
                }
            }
            val svc = host.core.memory
            // Warm up, then measure the whole recall path (keyword ranking + metadata + relationships + documents).
            repeat(5) { svc.recall(RecallRequest("servo battery gait", touch = false)) }
            val times = (1..40).map { i -> val t0 = System.nanoTime(); svc.recall(RecallRequest("${words[i % words.size]} ${words[(i * 7) % words.size]} unique${i * 50}", touch = false)); (System.nanoTime() - t0) / 1e6 }.sorted()
            val median = times[times.size / 2]; val p95 = times[(times.size * 0.95).toInt()]
            println("[resource] recall over 3000 memories: median %.1f ms, p95 %.1f ms".format(median, p95))
            assertTrue(p95 < 400, "p95 recall latency $p95 ms")
            // One background pass is bounded: it looks at a limited batch, however large the store is, and the JVM heap stays within the host's cap.
            val t0 = System.nanoTime(); val r = host.core.consolidator.run(maxScan = 100); val ms = (System.nanoTime() - t0) / 1_000_000
            println("[resource] consolidation pass over 100 of 3000 memories: $ms ms, scanned ${r.scanned}")
            assertEquals(100, r.scanned); assertTrue(ms < 15_000, "one bounded pass took $ms ms")
            val rt = Runtime.getRuntime(); val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            println("[resource] heap in use after the run: $usedMb MB")
            assertTrue(usedMb < 700, "heap $usedMb MB")
            assertEquals(3000, host.core.memories.count().toInt(), "distinct notes: nothing merged away")
        } finally { host.stop() }
    }
}
