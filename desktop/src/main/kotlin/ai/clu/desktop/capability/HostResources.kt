package ai.clu.desktop.capability

import java.io.File
import java.lang.management.ManagementFactory

/** What this computer can actually offer development right now (measured, not assumed). */
object HostResources {
    data class Snapshot(val cores: Int, val ramTotal: Long, val ramFree: Long, val diskFree: Long, val gpus: List<String>, val node: String?, val browser: String?, val online: Boolean?)

    fun snapshot(root: File, kit: KitServices? = null): Snapshot {
        val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        val node = listOf("/opt/node22/bin/node", "/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
        return Snapshot(Runtime.getRuntime().availableProcessors(), os?.totalMemorySize ?: 0, os?.freeMemorySize ?: 0, root.usableSpace,
            gpus(), node, ExperienceVerifier.find()?.name,
            kit?.let { runCatching { it.status()["weather"]?.available }.getOrNull() })
    }

    /** Graphics adapters, the same way the first-run checks find them. */
    fun gpus(): List<String> = runCatching {
        val out = if (ai.clu.desktop.IS_WINDOWS) ai.clu.desktop.runCommand(listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", "(Get-CimInstance Win32_VideoController).Name"))
            else ai.clu.desktop.runCommand(listOf("sh", "-c", "lspci 2>/dev/null | grep -i -E 'vga|3d|display' | sed 's/^[^:]*: //'"))
        out?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }.getOrDefault(emptyList())

    fun describe(root: File, kit: KitServices? = null): List<String> = snapshot(root, kit).let { s ->
        val gb = { b: Long -> "%.1f GB".format(b / 1e9) }
        listOf("host: ${s.cores} CPU cores, RAM ${gb(s.ramFree)} free of ${gb(s.ramTotal)}, disk ${gb(s.diskFree)} free",
            "GPU: ${s.gpus.ifEmpty { listOf("none detected") }.joinToString()}", "JavaScript syntax checker: ${s.node ?: "none (the browser run catches syntax errors)"}",
            "browser: ${s.browser ?: "none"}", "internet sources: ${when (s.online) { true -> "reachable"; false -> "not reachable"; null -> "unknown" }}") +
            (if (s.diskFree < 2_000_000_000L) listOf("disk is low: no large downloads") else emptyList())
    }
}
