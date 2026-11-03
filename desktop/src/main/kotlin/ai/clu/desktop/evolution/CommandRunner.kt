package ai.clu.desktop.evolution

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs build and test commands for self-repair: an argument list (never a shell string), in a repair workspace, with a
 * time limit, at reduced CPU priority where the OS allows it (`nice` on Linux and macOS). It captures the complete
 * output and the exit code. A command that runs over its limit is killed with its child processes and reported as
 * timed out, never as a pass.
 */
class CommandRunner(private val lowPriority: Boolean = true) {
    data class Result(val command: List<String>, val cwd: File, val exitCode: Int, val output: String, val durationMs: Long, val timedOut: Boolean) {
        val display get() = command.joinToString(" ") { if (it.any(Char::isWhitespace)) "'$it'" else it }
    }

    private val niceAvailable by lazy { !isWindows && File("/usr/bin/nice").canExecute() }

    fun run(command: List<String>, cwd: File, timeoutMs: Long, env: Map<String, String> = emptyMap(), onLine: (String) -> Unit = {}): Result {
        val full = if (lowPriority && niceAvailable) listOf("/usr/bin/nice", "-n", "10") + command else command
        val pb = ProcessBuilder(full).directory(cwd).redirectErrorStream(true)
        pb.environment().putAll(env)
        val t0 = System.nanoTime()
        val p = pb.start(); p.outputStream.close()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { l -> synchronized(out) { out.appendLine(l) }; onLine(l) } }.apply { isDaemon = true; start() }
        val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) { p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly(); p.waitFor(10, TimeUnit.SECONDS) }
        reader.join(5_000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        return Result(command, cwd, if (finished) p.exitValue() else -1, synchronized(out) { out.toString() }, ms, !finished)
    }

    companion object { val isWindows = System.getProperty("os.name").lowercase().contains("win") }
}
