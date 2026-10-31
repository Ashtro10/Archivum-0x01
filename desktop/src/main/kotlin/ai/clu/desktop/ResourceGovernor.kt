package ai.clu.desktop

import ai.clu.core.llm.LlmStatus

/** The limits CLU keeps to on this PC (from host.json). */
data class GovernorConfig(
    /** CLU's whole ceiling (host process including the model). A ceiling, not a target. */
    val budgetBytes: Long,
    /** Memory always left for the user's own programs when deciding whether a model may load. */
    val reserveBytes: Long,
    /** If the PC's free memory falls below this, an idle model is unloaded at once. */
    val pressureFreeBytes: Long,
)

data class GovernorEvent(val at: Long, val action: String, val reason: String)

/**
 * Keeps CLU inside its budget without getting in the user's way (M2, first version):
 * * a model may load only into what is left of the budget and of the PC's free memory minus a reserve
 *   ([availableForModel], used by the core's own load check);
 * * when the PC runs short of memory, or CLU is over its budget, an idle model is unloaded ([tick]);
 * * a reply being generated is never interrupted: the unload waits for the next check.
 * Idle unloading after a quiet period is the core's own (`model.autoUnloadMin`). Pure logic over injected probes, so
 * every rule is tested without a real model.
 */
class ResourceGovernor(
    @Volatile var config: GovernorConfig,
    private val probe: MemoryProbe,
    private val status: () -> LlmStatus,
    private val unload: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val history = ArrayDeque<GovernorEvent>()

    fun availableForModel(): Long {
        val c = config
        val fromSystem = probe.systemAvailableBytes() - c.reserveBytes
        val fromBudget = c.budgetBytes - probe.processBytes()
        return maxOf(0L, minOf(fromSystem, fromBudget))
    }

    /** One check; returns why the model was unloaded, or null when nothing needed doing. */
    fun tick(): String? {
        if (status() != LlmStatus.READY) return null // nothing loaded, or busy loading/generating: never interrupted
        val c = config
        val free = probe.systemAvailableBytes()
        val mine = probe.processBytes()
        val reason = when {
            free < c.pressureFreeBytes -> "the PC is low on memory (${mb(free)} MB free, threshold ${mb(c.pressureFreeBytes)} MB)"
            mine > c.budgetBytes -> "CLU is over its memory budget (${mb(mine)} MB of ${mb(c.budgetBytes)} MB)"
            else -> return null
        }
        unload()
        record("unloaded the model", reason)
        log("governor: unloaded the model because $reason")
        return reason
    }

    @Synchronized private fun record(action: String, reason: String) {
        history.addLast(GovernorEvent(clock(), action, reason))
        while (history.size > 20) history.removeFirst()
    }

    @Synchronized fun events(): List<GovernorEvent> = history.toList()

    fun snapshot(): Map<String, Long> = mapOf(
        "budgetMB" to mb(config.budgetBytes), "reserveMB" to mb(config.reserveBytes), "pressureFreeMB" to mb(config.pressureFreeBytes),
        "systemAvailableMB" to mb(probe.systemAvailableBytes()), "systemTotalMB" to mb(probe.systemTotalBytes()),
        "processMB" to mb(probe.processBytes()), "availableForModelMB" to mb(availableForModel()),
    )

    companion object {
        const val MB = 1024L * 1024
        const val GB = 1024L * MB
        fun mb(bytes: Long) = bytes / MB
    }
}
