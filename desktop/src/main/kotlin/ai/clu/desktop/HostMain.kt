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
    const val VERSION = "0.1.0-m2"

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
        val platform = DesktopPlatform(paths, governor, config.nativeLibrary, http)
        val core = CluCore(db, platform)
        coreRef = core
        core.startModel(preload = false)
        val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "clu-governor").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
        ticker.scheduleWithFixedDelay({ runCatching { governor.tick() } }, config.governorIntervalSeconds.toLong(), config.governorIntervalSeconds.toLong(), TimeUnit.SECONDS)
        val token = ApiToken.loadOrCreate(paths.token)
        val api = ApiServer(core, governor, token, port, VERSION).start()
        return RunningHost(core, api, governor, paths, token) {
            api.stop(); ticker.shutdownNow(); core.shutdown(); db.close()
        }
    }
}

private const val USAGE = """clu-host [options]
  (no options)                     start CLU on this PC and keep running (Ctrl-C stops it)
  --register-model <file.gguf>     add a model and make it the active one, then exit
  --print-token                    print the local API token, then exit
  --home <folder>                  use this data folder (default: per-user app data, or CLU_HOME)
  --port <n>                       local API port (default from host.json, 47821)"""

fun main(args: Array<String>) {
    var home: String? = null; var port: Int? = null; var register: String? = null; var printToken = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--home" -> home = args.getOrNull(++i)
            "--port" -> port = args.getOrNull(++i)?.toIntOrNull()
            "--register-model" -> register = args.getOrNull(++i)
            "--print-token" -> printToken = true
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
    val host = Host.start(paths, config, port ?: config.port)
    val done = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread { host.stop(); done.countDown() })
    println("CLU host ${Host.VERSION} ready on ${host.url} (data: ${paths.root}, token: ${paths.token})")
    done.await()
}
