package ai.clu.desktop.evolution

import ai.clu.core.CluCore
import ai.clu.core.config.Settings
import ai.clu.core.evolution.EvolutionStore
import ai.clu.core.util.SystemClock
import ai.clu.desktop.DesktopPaths
import ai.clu.desktop.JdbcSqlDriver
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Opens self-repair for a running host: its database, the service, the API routes and a periodic look for external changes. */
class SelfRepairSetup private constructor(val service: SelfRepairService, val routes: EvolutionRoutes, private val db: JdbcSqlDriver, private val ticker: ScheduledExecutorService?) {
    fun close() { ticker?.shutdownNow(); db.close() }

    companion object {
        /** Re-check the repository this often for external development (cheap: Git status + changed blobs only). */
        const val RECONCILE_MINUTES = 15L

        fun evolutionDir(paths: DesktopPaths) = File(paths.root, "evolution")

        fun service(paths: DesktopPaths, core: CluCore?, db: JdbcSqlDriver, log: (String) -> Unit, busy: () -> String? = { null }): SelfRepairService {
            val store = EvolutionStore(db, SystemClock)
            return SelfRepairService(store, File(evolutionDir(paths), "workspaces"), CommandRunner(), core?.let { InferenceRepairModel(it) }, busy,
                selfName = { core?.settings?.string(Settings.CLU_NAME)?.ifBlank { null } ?: "CLU" }, log = log)
        }

        fun open(paths: DesktopPaths, core: CluCore, log: (String) -> Unit, busy: () -> String?): SelfRepairSetup? = try {
            evolutionDir(paths).mkdirs()
            val db = JdbcSqlDriver(File(evolutionDir(paths), "evolution.db").absolutePath)
            val svc = service(paths, core, db, log, busy)
            core.selfKnowledge = svc.knowledge
            // Find CLU's source (an explicit earlier connection, CLU_SOURCE_REPO, or the folder CLU runs from) and keep up with it.
            val repo = RepoDiscovery.find(null, svc.store.meta("repo_root"))
            val ticker = if (repo != null) Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "clu-self-index").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }.also { t ->
                t.schedule({ runCatching { svc.connect(repo) }.onFailure { log("self-repair: could not index ${repo.path}: ${it.message}") } }, 30, TimeUnit.SECONDS)
                t.scheduleWithFixedDelay({ if (busy() == null) runCatching { svc.reconcile() }.onFailure { log("self-repair: reconcile failed: ${it.message}") } },
                    RECONCILE_MINUTES, RECONCILE_MINUTES, TimeUnit.MINUTES) } else null
            if (repo == null) log("self-repair: no source repository found (connect one with POST /v1/evolution/repo or clu-host --self-repo <path>)")
            SelfRepairSetup(svc, EvolutionRoutes(svc), db, ticker)
        } catch (e: Exception) { log("self-repair unavailable: ${e.message}"); null }
    }
}
