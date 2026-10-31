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
                  private val onStop: () -> Unit) {
    val url get() = "http://127.0.0.1:${api.port}"
    @Volatile private var stopped = false
    fun stop() {
        if (stopped) return
        stopped = true
        onStop()
    }
}

object Host {
    const val VERSION = "0.2.0-m3"

    /**
     * Starts CLU on this PC: opens (and migrates) the database in [paths], starts the model service without loading a
     * model (it loads on first use), the governor, and the local API. [port] 0 picks a free port (tests).
     */
    fun start(paths: DesktopPaths, config: HostConfig, port: Int = config.port, http: HttpFetcher = JvmHttp(), log: (String) -> Unit = ::println): RunningHost {
        paths.ensure()
        val db = JdbcSqlDriver(paths.database.absolutePath)
        var coreRef: CluCore? = null
        val governor = ResourceGovernor(config.governor(), SystemMemoryProbe,
            status = { coreRef?.llm?.state?.value?.status ?: ai.clu.core.llm.LlmStatus.NO_MODEL },
            unload = { kotlinx.coroutines.runBlocking { coreRef?.llm?.unload() } }, log = log)
        val hostConfig = HostConfigStore(paths.config, config) { governor }
        val platform = DesktopPlatform(paths, governor, config.nativeLibrary, http)
        val core = CluCore(db, platform)
        coreRef = core
        core.startModel(preload = false)
        val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "clu-governor").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
        ticker.scheduleWithFixedDelay({ runCatching { governor.tick() } }, config.governorIntervalSeconds.toLong(), config.governorIntervalSeconds.toLong(), TimeUnit.SECONDS)
        val token = ApiToken.loadOrCreate(paths.token)
        val api = ApiServer(core, governor, token, port, VERSION, hostConfig = hostConfig).start()
        return RunningHost(core, api, governor, paths, token) {
            api.stop(); ticker.shutdownNow(); core.shutdown(); db.close()
        }
    }
}

private const val USAGE = """clu-host [options]
  (no options)                     start CLU on this PC and keep running (Ctrl-C stops it)
  --register-model <file.gguf>     add a model and make it the active one, then exit
  --print-token                    print the local API token, then exit
  --open                           open CLU's window (if CLU is already running, opens that one's window)
  --no-tray                        no notification-area icon (servers, remote sessions)
  --home <folder>                  use this data folder (default: per-user app data, or CLU_HOME)
  --port <n>                       local API port (default from host.json, 47821)"""

fun main(args: Array<String>) {
    var home: String? = null; var port: Int? = null; var register: String? = null; var printToken = false
    var open = false; var tray = true
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--home" -> home = args.getOrNull(++i)
            "--port" -> port = args.getOrNull(++i)?.toIntOrNull()
            "--register-model" -> register = args.getOrNull(++i)
            "--print-token" -> printToken = true
            "--open" -> open = true
            "--no-tray" -> tray = false
            "-h", "--help" -> { println(USAGE); return }
            else -> { System.err.println("unknown option ${args[i]}\n$USAGE"); exitProcess(2) }
        }
        i++
    }
    val paths = DesktopPaths.resolve(home = home).ensure()
    if (printToken) { println(ApiToken.loadOrCreate(paths.token)); return }
    val config = try { HostConfig.load(paths.config) } catch (e: Exception) { System.err.println("host.json: ${e.message}"); exitProcess(2) }
    if (register != null) {
        val f = File(register)
        if (!f.isFile) { System.err.println("no such file: $f"); exitProcess(2) }
        val db = JdbcSqlDriver(paths.database.absolutePath)
        val governor = ResourceGovernor(config.governor(), SystemMemoryProbe, { ai.clu.core.llm.LlmStatus.NO_MODEL }, {})
        val core = CluCore(db, DesktopPlatform(paths, governor, config.nativeLibrary))
        val entry = core.models.register(f)
        core.settings.set(Settings.MODEL_ACTIVE, entry.id)
        println("registered ${entry.name} (${entry.sizeBytes / 1_048_576} MB) as the active model")
        db.close(); return
    }
    val p = port ?: config.port
    // One CLU per user: if it is already running, open its window instead of starting a second one.
    if (alreadyRunning(p)) {
        if (open) runCatching { postOpen(p, ApiToken.loadOrCreate(paths.token)) }.onFailure { System.err.println("CLU is running but its window could not be opened: ${it.message}") }
        println("CLU is already running on http://127.0.0.1:$p"); return
    }
    val host = Host.start(paths, config, p)
    host.api.openShell = {
        val url = "${host.url}/#code=${host.api.issueLaunchCode()}"
        // The code in the URL works once and only for 60 seconds, so showing it here on CLU's own console is harmless.
        if (!ShellLauncher.open(url)) println("Couldn't open a browser window. Open this address within a minute: $url")
    }
    val done = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread { host.stop(); done.countDown() })
    if (tray && !ShellLauncher.installTray(host) { done.countDown(); host.stop(); exitProcess(0) }) println("(no notification area here: running without a tray icon)")
    println("CLU host ${Host.VERSION} ready on ${host.url} (data: ${paths.root}, token: ${paths.token})")
    if (open) host.api.openShell?.invoke()
    done.await()
}

private fun alreadyRunning(port: Int): Boolean = runCatching {
    val c = java.net.URI("http://127.0.0.1:$port/v1/health").toURL().openConnection() as java.net.HttpURLConnection
    c.connectTimeout = 500; c.readTimeout = 1000
    c.inputStream.use { it.readBytes().toString(Charsets.UTF_8).contains("\"name\":\"CLU\"") }
}.getOrDefault(false)

private fun postOpen(port: Int, token: String) {
    val c = java.net.URI("http://127.0.0.1:$port/v1/shell/open").toURL().openConnection() as java.net.HttpURLConnection
    c.requestMethod = "POST"; c.setRequestProperty("Authorization", "Bearer $token"); c.doOutput = true
    c.outputStream.use { it.write("{}".toByteArray()) }
    check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
}
