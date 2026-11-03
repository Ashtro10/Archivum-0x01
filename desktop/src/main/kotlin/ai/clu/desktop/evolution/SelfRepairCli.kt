package ai.clu.desktop.evolution

import ai.clu.core.CluCore
import ai.clu.core.config.Settings
import ai.clu.desktop.*
import kotlinx.coroutines.runBlocking
import java.io.File

/** Self-repair from the command line (`clu-host --self-…`). Works with CLU stopped; uses the same databases and model as the app. */
class SelfRepairCli {
    var repo: String? = null; var index = false; var ask: String? = null; var repair = false; var module = "core"; var tests: String? = null
    var patch: String? = null; var patchSource = "external"; var useModel = true; var list = false; var report: String? = null
    var approve: String? = null; var reject: String? = null; var reason = "no reason given"; var history = false; var incidents = false

    val requested get() = repo != null || index || ask != null || repair || list || report != null || approve != null || reject != null || history || incidents

    /** Consumes one self-repair option at [i]; returns the next index, or null if [args][i] is not one of ours. */
    fun parse(args: Array<String>, i: Int): Int? {
        fun next() = args.getOrNull(i + 1) ?: throw IllegalArgumentException("${args[i]} needs a value")
        when (args[i]) {
            "--self-repo" -> { repo = next(); return i + 2 }
            "--self-index" -> { index = true; return i + 1 }
            "--self-ask" -> { ask = next(); return i + 2 }
            "--self-repair" -> { repair = true; return i + 1 }
            "--module" -> { module = next(); return i + 2 }
            "--tests" -> { tests = next(); return i + 2 }
            "--patch" -> { patch = next(); return i + 2 }
            "--patch-source" -> { patchSource = next(); return i + 2 }
            "--no-model" -> { useModel = false; return i + 1 }
            "--self-repairs" -> { list = true; return i + 1 }
            "--self-report" -> { report = next(); return i + 2 }
            "--self-approve" -> { approve = next(); return i + 2 }
            "--self-reject" -> { reject = next(); return i + 2 }
            "--reason" -> { reason = next(); return i + 2 }
            "--self-history" -> { history = true; return i + 1 }
            "--self-incidents" -> { incidents = true; return i + 1 }
        }
        return null
    }

    /** Returns the process exit code. */
    fun run(paths: DesktopPaths, config: HostConfig): Int {
        System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"))
        if (patchSource !in setOf("external", "fixture")) { System.err.println("--patch-source must be external or fixture"); return 2 }
        val patchText = patch?.let { val f = File(it); if (!f.isFile) { System.err.println("no such patch file: $f"); return 2 }; f.readText() }
        SelfRepairSetup.evolutionDir(paths).mkdirs()
        val edb = JdbcSqlDriver(File(SelfRepairSetup.evolutionDir(paths), "evolution.db").absolutePath)
        // The model is needed only to write a patch; everything else works without loading CLU's core.
        val needCore = repair && patchText == null && useModel
        val cdb = if (needCore) JdbcSqlDriver(paths.database.absolutePath) else null
        val core = cdb?.let { db ->
            val governor = ResourceGovernor(config.governor(), SystemMemoryProbe, { ai.clu.core.llm.LlmStatus.NO_MODEL }, {})
            val platform = DesktopPlatform(paths, governor, config.nativeLibrary)
            CluCore(db, platform).also { c -> platform.wantGpu = { c.settings.string(Settings.ACCELERATION) == "gpu" }; c.startModel(preload = false) }
        }
        try {
            val svc = SelfRepairSetup.service(paths, core, edb, log = { println("  · $it") })
            if (repo != null) {
                val dir = File(repo!!); if (!dir.isDirectory) { System.err.println("no such folder: $dir"); return 2 }
                println(svc.connect(dir).describe())
            } else if (index || ask != null || repair) {
                val r = RepoDiscovery.find(null, svc.store.meta("repo_root")) ?: run { System.err.println("No source repository connected. Use --self-repo <path to CLU's Git checkout>."); return 2 }
                val res = if (svc.store.meta("repo_root") == null) svc.connect(r) else svc.reconcile()
                if (index) println(res.describe())
            }
            ask?.let { q -> val a = svc.knowledge.answer(q); println(a.text) }
            if (repair) {
                val o = runBlocking { svc.run(SelfRepairService.Options(module, tests, patchText, patchSource, useModel)) }
                println(); println(o.message)
                o.repair?.let { println("Repair ${it.id.take(8)}: ${it.state}${it.verdict?.let { v -> " — ${v.outcome}: ${v.summary}" } ?: ""}") }
                o.reportFile?.let { println("Report: $it") }
                if (o.repair == null) return 1
            }
            if (list) svc.repairs.list().forEach { r -> println("${r.id.take(8)}  ${r.state.padEnd(20)} ${r.source.padEnd(8)} ${r.verdict?.outcome ?: "-"}  ${r.files.joinToString()}  ${r.outcome.take(100)}") }
            if (incidents) svc.incidents.list().forEach { i -> println("${i.id.take(8)}  ${i.status.padEnd(16)} ×${i.count}  ${i.title}") }
            if (history) svc.store.recentHistory(40).reversed().forEach { e -> println("${java.time.Instant.ofEpochMilli(e.at)}  ${e.kind.padEnd(22)} ${e.summary}") }
            report?.let { id -> println(svc.report(id) ?: "no repair $id") }
            approve?.let { id -> val o = svc.approve(id); println(o.message); if (o.repair?.state != ai.clu.core.evolution.RepairRecord.APPROVED) return 1 }
            reject?.let { id -> println(svc.reject(id, reason).message) }
            return 0
        } catch (e: GitException) { System.err.println("git: ${e.message}"); return 1 }
        finally { runCatching { core?.shutdown() }; cdb?.close(); edb.close() }
    }

    companion object {
        const val USAGE = """
Self-repair (works with CLU stopped; records go to <data>/evolution/evolution.db):
  --self-repo <path>               connect CLU's own source repository (a Git checkout) and index it
  --self-index                     look for external changes (new commits, edits) and update the index
  --self-ask "<question>"          answer a question about CLU's own software from the index
  --self-repair [--module core] [--tests <filter>] [--patch <file.diff> [--patch-source external|fixture]] [--no-model]
                                   run the tests in an isolated worktree; investigate a failure; prepare and verify a repair
  --self-repairs | --self-incidents | --self-history   list records
  --self-report <id>               print a repair's report
  --self-approve <id>              integrate a verified repair into its branch (refuses over uncommitted work)
  --self-reject <id> [--reason ..] discard a proposed repair"""
    }
}
