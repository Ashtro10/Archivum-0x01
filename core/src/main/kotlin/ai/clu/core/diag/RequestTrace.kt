package ai.clu.core.diag

/**
 * Lifecycle of each request (text, voice, LiveKit) with timestamps, for Diagnostics → Requests and logcat (tag CluTurn).
 * Never contains conversation content: only event names, sizes, token counts, timings and error categories.
 */
object TurnEvent {
    const val MESSAGE_RECEIVED = "MESSAGE_RECEIVED"
    const val REQUEST_CREATED = "REQUEST_CREATED"
    const val MODEL_READY = "MODEL_READY"
    const val GENERATION_STARTED = "GENERATION_STARTED"
    const val FIRST_TOKEN = "FIRST_TOKEN"
    const val GENERATION_COMPLETED = "GENERATION_COMPLETED"
    const val REQUEST_CANCELLED = "REQUEST_CANCELLED"
    const val REQUEST_TIMEOUT = "REQUEST_TIMEOUT"
    const val MODEL_INIT_FAILED = "MODEL_INIT_FAILED"
    const val INFERENCE_ERROR = "INFERENCE_ERROR"
    val TERMINAL = setOf(GENERATION_COMPLETED, REQUEST_CANCELLED, REQUEST_TIMEOUT, MODEL_INIT_FAILED, INFERENCE_ERROR)
}

class RequestTrace(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }, private val max: Int = 40) {
    data class Event(val atMs: Long, val name: String, val detail: String)
    class Request(val id: Long, val origin: String, val startedAt: Long) {
        val events = java.util.concurrent.CopyOnWriteArrayList<Event>()
        val terminal: Event? get() = events.lastOrNull { it.name in TurnEvent.TERMINAL }
        fun at(name: String) = events.firstOrNull { it.name == name }?.atMs?.minus(startedAt)
        /** One line without content, e.g. "#3 text completed 2140ms · model 3ms · first token 812ms". */
        fun summary(): String {
            val t = terminal
            val outcome = t?.name?.lowercase()?.replace('_', ' ') ?: "running"
            val parts = listOfNotNull(
                at(TurnEvent.MODEL_READY)?.let { "model ${it}ms" },
                at(TurnEvent.FIRST_TOKEN)?.let { "first token ${it}ms" },
                t?.let { "total ${it.atMs - startedAt}ms" },
                t?.detail?.takeIf { it.isNotEmpty() },
            )
            return "#$id $origin $outcome" + if (parts.isEmpty()) "" else " · " + parts.joinToString(" · ")
        }
    }

    /** Mirror of each event (the app sends it to logcat). */
    @Volatile var sink: ((String) -> Unit)? = null
    private val requests = ArrayDeque<Request>()
    private var nextId = 1L

    @Synchronized fun begin(origin: String, detail: String = ""): Request {
        val r = Request(nextId++, origin, clock())
        requests.addFirst(r); while (requests.size > max) requests.removeLast()
        record(r, TurnEvent.MESSAGE_RECEIVED, detail)
        return r
    }

    fun record(r: Request, name: String, detail: String = "") {
        val e = synchronized(this) {
            if (r.terminal != null) return // exactly one terminal state; later events are ignored
            Event(clock(), name, detail).also { r.events += it }
        }
        sink?.invoke("#${r.id} ${r.origin} $name +${e.atMs - r.startedAt}ms${if (detail.isEmpty()) "" else " $detail"}")
    }

    @Synchronized fun recent(): List<Request> = requests.toList()
    @Synchronized fun active(): List<Request> = requests.filter { it.terminal == null }
}
