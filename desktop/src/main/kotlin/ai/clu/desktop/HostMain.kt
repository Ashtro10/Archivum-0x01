package ai.clu.desktop

import ai.clu.core.CluCore
import ai.clu.core.config.Settings
import ai.clu.core.tools.HttpFetcher
import ai.clu.core.net.JvmHttp
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** A running CLU host: the core, its local API and the resource governor. */
class RunningHost(val core: CluCore, val api: ApiServer, val governor: ResourceGovernor, val paths: DesktopPaths, val token: String,
                  val maintenance: MaintenanceScheduler, val lifecycle: HostLifecycle = HostLifecycle(), val hostLog: HostLog? = null, private val onStop: () -> Unit) {
    val url get() = "http://127.0.0.1:${api.port}"
    @Volatile private var stopped = false
    fun stop() {
        if (stopped) return
        stopped = true
        onStop()
    }
}

object Host {
    const val VERSION = "0.6.0-m6"

    /**
     * Starts CLU on this PC: opens (and migrates) the database in [paths], starts the model service without loading a
     * model (it loads on first use), the governor, and the local API. [port] 0 picks a free port (tests).
     */
    fun start(paths: DesktopPaths, config: HostConfig, port: Int = config.port, http: HttpFetcher = JvmHttp(), log: ((String) -> Unit)? = null,
              autostart: Autostart = Autostart(), windowMemory: WindowMemory? = null): RunningHost {
        paths.ensure()
        val hostLog = if (log == null) HostLog(paths.logs) else null
        val log: (String) -> Unit = log ?: { hostLog!!.write(it) }
        DatabaseBackup.requireHealthy(paths) // a damaged database is never opened for writing; the message says how to go back to a backup
        val db = JdbcSqlDriver(paths.database.absolutePath)
        // Before a schema upgrade, keep a copy of what is there (the migration itself is all-or-nothing as well).
        db.userVersion.let { v -> if (v in 1 until ai.clu.core.db.Schema.currentVersion) {
            val b = DatabaseBackup.create(db, paths.backups, System.currentTimeMillis(), "pre-upgrade-v$v", keep = 3); log("database upgrade from schema v$v: backed up to ${b.file.name}") } }
        var coreRef: CluCore? = null
        val governor = ResourceGovernor(config.governor(), if (windowMemory != null) CombinedMemoryProbe(SystemMemoryProbe) { windowMemory.bytes() } else SystemMemoryProbe,
            status = { coreRef?.llm?.state?.value?.status ?: ai.clu.core.llm.LlmStatus.NO_MODEL },
            unload = { kotlinx.coroutines.runBlocking { coreRef?.llm?.unload() } }, log = log)
        val hostConfig = HostConfigStore(paths.config, config) { governor }
        val platform = DesktopPlatform(paths, governor, config.nativeLibrary, http)
        val core = CluCore(db, platform)
        platform.wantGpu = { core.settings.string(Settings.ACCELERATION) == "gpu" }
        coreRef = core
        core.startModel(preload = false)
        val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "clu-governor").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
        ticker.scheduleWithFixedDelay({ runCatching { governor.tick() } }, config.governorIntervalSeconds.toLong(), config.governorIntervalSeconds.toLong(), TimeUnit.SECONDS)
        val token = ApiToken.loadOrCreate(paths.token)
        val maintenance = MaintenanceScheduler(core, governor, paths, config.maintenanceMinutes, log).also { it.start() }
        val lifecycle = HostLifecycle()
        val api = ApiServer(core, governor, token, port, VERSION, hostConfig = hostConfig, maintenance = maintenance, paths = paths,
            platform = platform.llm, lifecycle = lifecycle, autostart = autostart).start()
        return RunningHost(core, api, governor, paths, token, maintenance, lifecycle, hostLog) {
            api.stop(); maintenance.stop(); ticker.shutdownNow(); core.shutdown(); db.close()
        }
    }
}

private const val USAGE = """clu-host [options]
  (no options)                     start CLU on this PC and keep running (Ctrl-C stops it)
  --register-model <file.gguf>     register a model file (it stays where it is) and make it the active one, then exit
  --models                         list registered models with their details and memory estimates, then exit
  --select-model <id|name>         make a registered model the default, then exit (CLU stopped)
  --unregister-model <id|name>     remove a registration, then exit (the model file is never deleted; CLU stopped)
  --benchmark [id|name]            measure a model (load time, first token, speed, CPU, RAM) and save the result, then exit (CLU stopped)
  --print-token                    print the local API token, then exit
  --restore-backup <file>          put a backup of CLU's database back (CLU must be stopped; the current database is kept aside)
  --open                           open CLU's window (if CLU is already running, opens that one's window)
  --background                     start without opening the window (what "start with Windows" uses)
  --quit                           ask a running CLU to quit cleanly, then exit
  --backup                         make a verified backup of CLU's database now, then exit
  --version                        print the version, then exit
  --no-tray                        no notification-area icon (servers, remote sessions)
  --home <folder>                  use this data folder (default: per-user app data, or CLU_HOME)
  --port <n>                       local API port (default from host.json, 47821)"""

fun main(args: Array<String>) {
    var home: String? = null; var port: Int? = null; var register: String? = null; var printToken = false; var restore: String? = null
    var listModels = false; var selectModel: String? = null; var unregister: String? = null; var bench = false; var benchModel: String? = null
    var open = false; var tray = true; var background = false; var quit = false; var backup = false; var waitFor: Long? = null; var version = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--home" -> home = args.getOrNull(++i)
            "--port" -> port = args.getOrNull(++i)?.toIntOrNull()
            "--register-model" -> register = args.getOrNull(++i)
            "--print-token" -> printToken = true
            "--restore-backup" -> restore = args.getOrNull(++i)
            "--models" -> listModels = true
            "--select-model" -> selectModel = args.getOrNull(++i)
            "--unregister-model" -> unregister = args.getOrNull(++i)
            "--benchmark" -> { bench = true; if (args.getOrNull(i + 1)?.startsWith("--") == false) benchModel = args[++i] }
            "--open" -> open = true
            "--no-tray" -> tray = false
            "--background" -> background = true
            "--quit" -> quit = true
            "--backup" -> backup = true
            "--wait-for" -> waitFor = args.getOrNull(++i)?.toLongOrNull()
            "--version" -> version = true
            "-h", "--help" -> { println(USAGE); return }
            else -> { System.err.println("unknown option ${args[i]}\n$USAGE"); exitProcess(2) }
        }
        i++
    }
    if (version) { println("CLU ${Host.VERSION}"); return }
    val paths = DesktopPaths.resolve(home = home).ensure()
    waitFor?.let { Relauncher.waitFor(it) }
    if (printToken) { println(ApiToken.loadOrCreate(paths.token)); return }
    val config = try { HostConfig.load(paths.config) } catch (e: Exception) { System.err.println("host.json: ${e.message}"); exitProcess(2) }
    val p = port ?: config.port
    if (register != null || listModels || selectModel != null || unregister != null || bench) {
        // Model management without the window. Changes need CLU stopped (a running CLU keeps its own copy of the settings in memory).
        val mutating = register != null || selectModel != null || unregister != null || bench
        if (mutating && alreadyRunning(p)) { System.err.println("CLU is running. Use the window (Models) or stop CLU first (Quit CLU in the tray)."); exitProcess(2) }
        val db = JdbcSqlDriver(paths.database.absolutePath)
        val governor = ResourceGovernor(config.governor(), SystemMemoryProbe, { ai.clu.core.llm.LlmStatus.NO_MODEL }, {})
        val platform = DesktopPlatform(paths, governor, config.nativeLibrary)
        val core = CluCore(db, platform)
        platform.wantGpu = { core.settings.string(Settings.ACCELERATION) == "gpu" }
        try {
            fun find(q: String) = core.models.list().let { all -> all.firstOrNull { it.id == q } ?: all.filter { it.name.contains(q, true) || File(it.path).name.contains(q, true) }.singleOrNull() }
            fun mb(b: Long) = "${b / 1_048_576} MB"
            fun describe(e: ai.clu.core.llm.ModelEntry, active: String) {
                val est = ai.clu.core.llm.ModelRegistry.estimateRamBytes(e, core.settings.int(Settings.CONTEXT_LENGTH)); val allowed = governor.availableForModel()
                println("${if (e.id == active) "*" else " "} ${e.id}  ${e.name}  [${e.info.architecture} ${e.info.quantization}, ${if (e.info.parameterCountHint > 0) "%.1fB params, ".format(e.info.parameterCountHint / 1e9) else ""}${mb(e.sizeBytes)}, trained for ${e.info.contextLength} tokens]")
                println("    ${e.path}")
                println("    needs about ${mb(est)} at ${core.settings.int(Settings.CONTEXT_LENGTH)}-token context; CLU may use ${mb(allowed)} right now" + if (est > allowed) "  (TOO LARGE right now: lower the context or free memory)" else "")
                println("    status: ${e.status.label.replace("–", "-")}${e.lastError?.let { " - $it" } ?: ""}")
            }
            if (register != null) {
                val f = File(register!!)
                if (!f.isFile) { System.err.println("no such file: $f"); exitProcess(2) }
                val entry = try { core.models.register(f) } catch (e: ai.clu.core.llm.LlmException) {
                    System.err.println("Not registered: ${e.message}\nTry ${ai.clu.core.llm.ModelRegistry.catalog.first().name} (CLU never downloads models itself; get it from ${ai.clu.core.llm.ModelRegistry.catalog.first().url})"); exitProcess(2) }
                core.settings.set(Settings.MODEL_ACTIVE, entry.id)
                println("Registered and selected:"); describe(entry, entry.id)
            }
            if (selectModel != null) { val e = find(selectModel!!) ?: run { System.err.println("no single model matches \"$selectModel\" (see --models)"); exitProcess(2) }; core.settings.set(Settings.MODEL_ACTIVE, e.id); println("Default model: ${e.name}") }
            if (unregister != null) {
                val e = find(unregister!!) ?: run { System.err.println("no single model matches \"$unregister\" (see --models)"); exitProcess(2) }
                if (core.settings.string(Settings.MODEL_ACTIVE) == e.id) core.settings.set(Settings.MODEL_ACTIVE, "")
                core.models.remove(e.id, ownedDir = null); println("Removed the registration of ${e.name}. The file ${e.path} was not touched.")
            }
            if (listModels) { val all = core.models.list(); val act = core.settings.string(Settings.MODEL_ACTIVE); if (all.isEmpty()) println("No models registered. Register one with: clu-host --register-model <file.gguf>") else all.forEach { describe(it, act) } }
            if (bench) {
                val e = (benchModel?.let { find(it) } ?: core.models.get(core.settings.string(Settings.MODEL_ACTIVE)) ?: core.models.list().singleOrNull())
                    ?: run { System.err.println("which model? give an id or name (see --models)"); exitProcess(2) }
                println("Benchmarking ${e.name} on ${ModelBenchmark.cpuName()} ..."); core.startModel(preload = false)
                val r = ModelBenchmark.run(core, e, ModelBenchmark.Options(), { println("  - $it") })
                val f = ModelBenchmark.save(paths, r); println(ModelBenchmark.summary(r)); println("Saved: $f")
                if (r["error"] != null) exitProcess(1)
            }
        } finally { runCatching { core.shutdown() }; db.close() }
        return
    }
    if (quit) {
        if (!alreadyRunning(p)) { println("CLU is not running."); return }
        runCatching { postJson(p, ApiToken.loadOrCreate(paths.token), "/v1/shell/quit") }.onFailure { System.err.println("Could not ask CLU to quit: ${it.message}"); exitProcess(2) }
        println("CLU is quitting."); return
    }
    if (backup) {
        if (alreadyRunning(p)) { System.err.println("CLU is running; use Settings > Backup in its window."); exitProcess(2) }
        val db = JdbcSqlDriver(paths.database.absolutePath)
        try { val b = DatabaseBackup.create(db, paths.backups, System.currentTimeMillis(), "manual", keep = 10); println("Backup written: ${b.file}") } finally { db.close() }
        return
    }
    if (restore != null) {
        if (alreadyRunning(p)) { System.err.println("CLU is running; stop it first (Quit CLU in the tray), then restore."); exitProcess(2) }
        try {
            val aside = DatabaseBackup.restore(File(restore), paths, System.currentTimeMillis())
            println("Restored ${File(restore).name}." + (aside?.let { " The previous database was kept as $it." } ?: ""))
        } catch (e: Exception) { System.err.println("Not restored: ${e.message}"); exitProcess(2) }
        return
    }
    // One CLU per user: if it is already running, open its window instead of starting a second one.
    if (alreadyRunning(p)) {
        if (!background) runCatching { postJson(p, ApiToken.loadOrCreate(paths.token), "/v1/shell/open") }.onFailure { System.err.println("CLU is running but its window could not be opened: ${it.message}") }
        println("CLU is already running on http://127.0.0.1:$p"); return
    }
    val profile = File(paths.root, "window-profile")
    val windowMemory = WindowMemory(profile.absolutePath)
    val host = try { Host.start(paths, config, p, windowMemory = windowMemory) } catch (e: DatabaseDamagedException) { System.err.println(e.message); exitProcess(3) }
    val log: (String) -> Unit = { host.hostLog?.write(it) ?: println(it) }
    Thread.setDefaultUncaughtExceptionHandler { t, e -> log("unexpected error in ${t.name}: ${e::class.simpleName}: ${e.message}") }
    host.api.openShell = {
        val url = "${host.url}/#code=${host.api.issueLaunchCode()}"
        // The code in the URL works once and only for 60 seconds, so it is never logged.
        if (!ShellLauncher.open(url, profile, log)) log("Couldn't open a window. Open this address within a minute: ${url.substringBefore('#')} (launch code not shown)")
    }
    val done = CountDownLatch(1)
    val restartFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    val shutdown = { done.countDown() }
    host.lifecycle.onQuit = shutdown
    host.lifecycle.onRestart = { restartFlag.set(true); done.countDown() }
    Runtime.getRuntime().addShutdownHook(Thread { host.stop(); done.countDown() })
    if (tray && !ShellLauncher.installTray(host, { host.lifecycle.onRestart?.invoke() }, { shutdown() })) log("(no notification area here: running without a tray icon)")
    // Closing the window sends CLU to the tray (default) or quits it, as the owner chose in Settings.
    java.util.Timer("clu-window-watch", true).schedule(object : java.util.TimerTask() { override fun run() {
        if (host.lifecycle.windowClosed() && !(hostConfigRunInBackground(paths))) { log("window closed and background running is off: quitting"); shutdown() }
    } }, 5000, 5000)
    log("CLU host ${Host.VERSION} ready on ${host.url} (data: ${paths.root})")
    if (!background) host.api.openShell?.invoke()
    if (open) host.api.openShell?.invoke()
    done.await()
    host.stop()
    if (restartFlag.get()) { log("restarting"); Relauncher.relaunch() }
    exitProcess(0)
}

private fun hostConfigRunInBackground(paths: DesktopPaths): Boolean = runCatching { HostConfig.load(paths.config).runInBackground }.getOrDefault(true)

private fun alreadyRunning(port: Int): Boolean = runCatching {
    val c = java.net.URI("http://127.0.0.1:$port/v1/health").toURL().openConnection() as java.net.HttpURLConnection
    c.connectTimeout = 500; c.readTimeout = 1000
    c.inputStream.use { it.readBytes().toString(Charsets.UTF_8).contains("\"name\":\"CLU\"") }
}.getOrDefault(false)

private fun postJson(port: Int, token: String, path: String) {
    val c = java.net.URI("http://127.0.0.1:$port$path").toURL().openConnection() as java.net.HttpURLConnection
    c.requestMethod = "POST"; c.setRequestProperty("Authorization", "Bearer $token"); c.doOutput = true
    c.outputStream.use { it.write("{}".toByteArray()) }
    check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
}
