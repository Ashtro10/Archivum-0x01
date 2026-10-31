package ai.clu.desktop

import ai.clu.core.CluCore
import ai.clu.core.llm.LlmStatus
import ai.clu.core.memory.Jobs
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Runs CLU's background memory work (expiry, consolidation, optional vector indexing, integrity check) and the daily
 * database backup, politely:
 *
 * * only every [intervalMinutes] (default 20), on one low-priority thread, so idle CPU stays at zero between runs;
 * * a run is skipped while the model is loading or generating, or while the PC is short of memory (the same limits the
 *   resource governor uses: free memory under reserve + the pressure threshold);
 * * each run does a bounded amount of work and stops early if the PC becomes busy meanwhile; unfinished work resumes
 *   at the next run from its checkpoint.
 */
class MaintenanceScheduler(
    private val core: CluCore, private val governor: ResourceGovernor, private val paths: DesktopPaths,
    private val intervalMinutes: Int, private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    /** Whether the model is loading or generating (replaceable in tests). */
    private val modelBusy: () -> Boolean = { core.llm.state.value.status.let { it == LlmStatus.GENERATING || it == LlmStatus.LOADING } },
) {
    data class Outcome(val at: Long, val ran: Boolean, val summary: String)
    @Volatile var last: Outcome? = null; private set
    /** Whether runs happen on their own (maintenanceMinutes > 0); "Run now" works either way. */
    val scheduled get() = intervalMinutes > 0
    private var executor: ScheduledExecutorService? = null
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Why background work should wait right now, or null if it may run. */
    fun busyReason(): String? {
        if (modelBusy()) return "the model is busy"
        val snap = governor.snapshot()
        if (snap.getValue("systemAvailableMB") < snap.getValue("reserveMB") + snap.getValue("pressureFreeMB")) return "the PC is short of memory"
        return null
    }

    /** One pass. [force] (the "Run now" button) ignores the schedule but not the busy checks. */
    fun tick(force: Boolean = false): Outcome {
        if (!running.compareAndSet(false, true)) return Outcome(clock(), false, "already running")
        try {
            busyReason()?.let { return Outcome(clock(), false, "skipped: $it").also { o -> last = o } }
            val steps = mutableListOf<String>()
            val result = core.maintenance.runOnce(shouldStop = { busyReason() != null })
            steps += result.steps
            val lastBackup = Jobs.get(core.db, "backup")?.lastRun ?: 0L
            if (busyReason() == null && (force && lastBackup == 0L || clock() - lastBackup > 24 * 3_600_000L)) {
                val b = DatabaseBackup.create(core.db, paths.backups, clock(), "auto", keep = 7)
                Jobs.put(core.db, clock(), "backup", "IDLE", detail = b.file.name)
                steps += "backup ${b.file.name}"
            }
            return Outcome(clock(), true, steps.joinToString("; ").ifBlank { "nothing to do" }).also { last = it }
        } catch (e: Exception) {
            log("maintenance failed: ${e.message}")
            return Outcome(clock(), false, "failed: ${e.message?.lineSequence()?.firstOrNull()}").also { last = it }
        } finally { running.set(false) }
    }

    fun start() {
        if (intervalMinutes <= 0 || executor != null) return
        executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "clu-maintenance").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
            .also { it.scheduleWithFixedDelay({ runCatching { tick() } }, 2, intervalMinutes.toLong(), TimeUnit.MINUTES) }
    }

    fun stop() { executor?.shutdownNow(); executor = null }
}
