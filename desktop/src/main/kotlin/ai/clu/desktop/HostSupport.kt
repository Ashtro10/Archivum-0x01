package ai.clu.desktop

import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Starting CLU when the owner signs in to Windows: a per-user entry in HKCU\...\Run (no service, no administrator rights). Off unless the owner turns it on. */
class Autostart(private val run: (List<String>) -> String? = ::runCommand, private val launcher: () -> String? = { System.getenv("CLU_LAUNCHER") }) {
    private val key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private val value = "CLU"

    val supported: Boolean get() = IS_WINDOWS

    /** The entry that would be written. */
    fun command(): String? = launcher()?.let { "\"$it\" --background" }

    /** Whether the entry exists (read from Windows, not remembered). Null where this does not apply. */
    fun isEnabled(): Boolean? {
        if (!supported) return null
        val out = run(listOf("reg", "query", key, "/v", value)) ?: return false
        return out.contains(value, ignoreCase = true) && out.contains("REG_SZ", ignoreCase = true)
    }

    /** Switches it. Returns an error message, or null on success. */
    fun set(enabled: Boolean): String? {
        if (!supported) return "Starting with Windows is available on Windows only."
        if (enabled) {
            val cmd = command() ?: return "CLU does not know where it is installed (start it from its Start Menu entry once, then try again)."
            run(listOf("reg", "add", key, "/v", value, "/t", "REG_SZ", "/d", cmd, "/f")) ?: return "Windows did not accept the change."
        } else run(listOf("reg", "delete", key, "/v", value, "/f")) // deleting a value that is not there is fine
        return if (isEnabled() == enabled) null else "Windows did not apply the change."
    }
}

/** The host's own log file: rotated, never holds tokens, and where a user is pointed when something goes wrong. */
class HostLog(private val dir: File, private val maxBytes: Long = 2L * 1024 * 1024) {
    private val file = File(dir, "host.log")
    @Synchronized fun write(line: String) {
        runCatching {
            dir.mkdirs()
            if (file.length() > maxBytes) { File(dir, "host.log.2").delete(); File(dir, "host.log.1").renameTo(File(dir, "host.log.2")); file.renameTo(File(dir, "host.log.1")) }
            file.appendText("${Instant.now()} ${redact(line)}\n")
        }
        println(line)
    }
    val path: File get() = file
    companion object {
        /** Removes anything that looks like a credential before it reaches a file. */
        fun redact(s: String): String = s.replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}"), "$1[hidden]")
            .replace(Regex("(?i)((?:token|password|passwd|secret|api[_-]?key|authorization)\\s*[=:]\\s*)[^\\s,;\"']{4,}"), "$1[hidden]")
            .replace(Regex("#code=[A-Za-z0-9_-]+"), "#code=[hidden]")
    }
}

/** Starts a fresh copy of CLU (same launcher, same arguments) after this one has gone, for "Restart". */
object Relauncher {
    fun command(afterPid: Long): List<String>? {
        val launcher = System.getenv("CLU_LAUNCHER")
        if (launcher != null) return listOf(launcher, "--open", "--wait-for", afterPid.toString())
        val info = ProcessHandle.current().info()
        val cmd = info.command().orElse(null) ?: return null
        val args = info.arguments().orElse(emptyArray()).filter { it != "--wait-for" }
        return listOf(cmd) + args + listOf("--wait-for", afterPid.toString())
    }

    fun relaunch(): Boolean = runCatching {
        val c = command(ProcessHandle.current().pid()) ?: return false
        ProcessBuilder(c).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start(); true
    }.getOrDefault(false)

    /** Blocks (up to [seconds]) until process [pid] has ended, so the new host finds the port free. */
    fun waitFor(pid: Long, seconds: Long = 30) { ProcessHandle.of(pid).ifPresent { runCatching { it.onExit().get(seconds, TimeUnit.SECONDS) } } }
}

/**
 * Memory of CLU's window (Edge's processes started with CLU's own profile folder), so the 16 GB ceiling covers the browser too.
 * Windows: working sets of msedge.exe processes whose command line names the profile folder; elsewhere: resident sets of processes
 * whose command line contains the marker. Cached for [ttlMs]: the governor asks often, the measurement is not free.
 */
class WindowMemory(private val marker: String, private val ttlMs: Long = 20_000, private val run: (List<String>) -> String? = ::runCommand,
                   private val procRoot: File = File("/proc"), private val now: () -> Long = System::currentTimeMillis) {
    @Volatile private var cached = 0L; @Volatile private var at = 0L

    fun bytes(): Long {
        val t = now(); if (t - at < ttlMs) return cached
        cached = runCatching { measure() }.getOrDefault(0L); at = t
        return cached
    }

    private fun measure(): Long {
        if (IS_WINDOWS) {
            val ps = "(Get-CimInstance Win32_Process -Filter \"Name='msedge.exe'\" | Where-Object { \$_.CommandLine -like '*${marker.replace("'", "''")}*' } | Measure-Object -Property WorkingSetSize -Sum).Sum"
            return run(listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", ps))?.trim()?.toLongOrNull() ?: 0L
        }
        var sum = 0L
        for (d in procRoot.listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) }.orEmpty()) {
            if (d.name == ProcessHandle.current().pid().toString()) continue
            val cmd = runCatching { File(d, "cmdline").readBytes().toString(Charsets.ISO_8859_1) }.getOrNull() ?: continue
            if (!cmd.contains(marker)) continue
            runCatching { File(d, "status").readLines().firstOrNull { it.startsWith("VmRSS:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLong()?.times(1024) }.getOrNull()?.let { sum += it }
        }
        return sum
    }
}

/** A memory probe that adds another process group (CLU's window) to the host's own figure. */
class CombinedMemoryProbe(private val base: MemoryProbe, private val extra: () -> Long) : MemoryProbe {
    override fun systemAvailableBytes() = base.systemAvailableBytes()
    override fun systemTotalBytes() = base.systemTotalBytes()
    override fun processBytes() = base.processBytes() + extra()
}

/** Window and lifecycle signals for the host: whether CLU's window is open (it pings while it is), and how to quit or restart. */
class HostLifecycle {
    @Volatile var lastPing = 0L; private set
    @Volatile var windowSeen = false; private set
    @Volatile var onQuit: (() -> Unit)? = null
    @Volatile var onRestart: (() -> Unit)? = null
    fun ping(now: Long = System.currentTimeMillis()) { lastPing = now; windowSeen = true }
    /** True if a window was open and has not pinged for [graceMs]: it has been closed. */
    fun windowClosed(now: Long = System.currentTimeMillis(), graceMs: Long = 20_000) = windowSeen && now - lastPing > graceMs
}
