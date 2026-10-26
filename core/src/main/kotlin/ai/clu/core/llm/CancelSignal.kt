package ai.clu.core.llm

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Cancellation of one request. Owned by whoever started the request; cancelling it stops that request only (never a
 * generation that belongs to someone else). Thread-safe; [cancel] is idempotent and the first reason wins.
 */
class CancelSignal {
    @Volatile var reason: String? = null
        private set
    val isCancelled get() = reason != null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun cancel(reason: String = "cancelled") {
        synchronized(this) { if (this.reason != null) return; this.reason = reason }
        listeners.forEach { runCatching(it) }
    }

    /** Runs [l] when cancelled (immediately if already cancelled). Close the result to unregister. */
    fun onCancel(l: () -> Unit): AutoCloseable {
        listeners += l
        if (isCancelled) runCatching(l)
        return AutoCloseable { listeners -= l }
    }

    companion object {
        const val USER = "cancelled"
        const val TIMEOUT = "timeout"
        const val SUPERSEDED = "superseded"
    }
}
