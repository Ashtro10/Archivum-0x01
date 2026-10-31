package ai.clu.core.llm

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.util.newId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import java.util.concurrent.ConcurrentHashMap

/** Why a request did not produce a reply (stable codes for every client of the inference API). */
enum class InferenceError(val label: String) {
    BUSY("CLU is busy with other requests"), NO_MODEL("No model is available"), LOAD_FAILED("The model could not be loaded"),
    CONTEXT_TOO_LARGE("The conversation does not fit the model's context"), TIMEOUT_FIRST_TOKEN("The model took too long to start answering"),
    TIMEOUT_STALL("The model stopped producing text"), TIMEOUT_TOTAL("The reply took too long"), EMPTY("The model returned no text"),
    FAILED("Generation failed"),
}

/** How a finished request ended. */
enum class InferenceOutcome { COMPLETE, LENGTH, STOPPED, CANCELLED, REPETITION }

/** One request to the model. [id] identifies it to [InferenceService.cancel] and in every event; the model is not named: the service owns that. */
data class InferenceRequest(
    val messages: List<ChatMessage>,
    val params: GenParams = GenParams(),
    val id: String = newId(),
    /** Who asked ("chat", "memory", "planner"…): shown in the status, never used for decisions. */
    val owner: String = "",
    /** Drop the oldest turns if the conversation does not fit the context (the system message and the newest message are kept). */
    val fitContext: Boolean = true,
)

sealed class InferenceEvent {
    abstract val requestId: String
    data class Queued(override val requestId: String, val position: Int) : InferenceEvent()
    data class Started(override val requestId: String) : InferenceEvent()
    data class Token(override val requestId: String, val text: String) : InferenceEvent()
    data class Completed(override val requestId: String, val result: GenResult, val outcome: InferenceOutcome, val droppedMessages: Int = 0) : InferenceEvent()
    data class Failed(override val requestId: String, val error: InferenceError, val message: String) : InferenceEvent()
}

data class InferenceResult(val requestId: String, val text: String, val stats: GenStats, val outcome: InferenceOutcome, val droppedMessages: Int)

class InferenceException(val error: InferenceError, message: String) : LlmException(message)

/** One request as the status shows it. */
data class RequestInfo(val id: String, val owner: String, val state: String, val queuedAt: Long, val startedAt: Long?, val tokens: Int)

/** Everything a client needs to show or decide: the runtime state, what is running and waiting, and the limits in force. */
data class InferenceSnapshot(val state: LlmState, val running: RequestInfo?, val queued: List<RequestInfo>, val maxQueued: Int,
                             val firstTokenTimeoutS: Int, val stallTimeoutS: Int, val totalTimeoutS: Int)

/**
 * The one way CLU's components use the local model. Everything above this (the chat agent, memory, the planner, the
 * desktop and phone APIs) sends messages here and gets streamed events back; nothing above it touches the native
 * runtime, picks a model or holds a second copy of one.
 *
 * * **One model, one generation at a time** (llama contexts are single-threaded); up to `model.maxQueued` further
 *   requests wait, in order. More are refused at once with BUSY instead of piling up.
 * * **Request ids**: each request has one; [cancel] stops exactly that request (or drops it from the queue). A request
 *   whose collector goes away is cancelled too, so abandoned requests do not keep the model busy.
 * * **No endless thinking**: a watchdog stops a request that produces no first token, stalls between tokens, or runs
 *   past its total limit, and the caller gets a TIMEOUT error instead of a spinner that never ends.
 * * **Malformed output**: control characters are removed and a reply stuck in a loop is stopped and trimmed.
 * * **Context**: a conversation that does not fit is shortened from the oldest end (the system message and the newest
 *   message are always kept) or refused with CONTEXT_TOO_LARGE.
 * * **Recovery**: a cancelled or failed request leaves the model ready for the next one; a failed load can be retried.
 */
class InferenceService(
    private val llm: LlmService,
    private val settings: SettingsStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Active(val request: InferenceRequest, val signal: CancelSignal, val queuedAt: Long) {
        @Volatile var startedAt: Long? = null
        @Volatile var lastTokenAt: Long = 0
        @Volatile var tokens = 0
        @Volatile var cancelledBy: InferenceError? = null
        @Volatile var running = false
    }

    private val active = ConcurrentHashMap<String, Active>()
    private val order = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val admit = Any()
    val state get() = llm.state

    // ------------------------------------------------------------------------------------------ status
    fun snapshot(): InferenceSnapshot {
        fun info(id: String) = active[id]?.let { a ->
            RequestInfo(id, a.request.owner, if (a.signal.isCancelled) "cancelling" else if (a.running) "running" else "queued", a.queuedAt, a.startedAt, a.tokens) }
        val all = order.mapNotNull(::info)
        return InferenceSnapshot(llm.state.value, all.firstOrNull { it.state != "queued" }, all.filter { it.state == "queued" }, settings.int(Settings.MAX_QUEUED),
            settings.int(Settings.FIRST_TOKEN_TIMEOUT), settings.int(Settings.STALL_TIMEOUT), settings.int(Settings.TOTAL_TIMEOUT))
    }

    // ------------------------------------------------------------------------------------------ lifecycle
    /** Loads the selected model (retrying after an earlier failure). Returns the resulting state. */
    suspend fun load(): LlmState { llm.loadIfNeeded(); return llm.state.value }

    /** Releases the model. Running requests are cancelled first (they stop consuming the model), queued ones are dropped. Persistent memory is untouched. */
    suspend fun unload(): LlmState { cancelAll("The model was unloaded."); llm.unload(); return llm.state.value }

    suspend fun reload(): LlmState { cancelAll("The model is being reloaded."); llm.reload(); return llm.state.value }

    // ------------------------------------------------------------------------------------------ requests
    /** Cancels one request by id; false if there is none (already finished). */
    fun cancel(requestId: String): Boolean {
        val a = active[requestId] ?: return false
        a.signal.cancel(); if (a.running) llm.cancel()
        return true
    }

    fun cancelAll(@Suppress("UNUSED_PARAMETER") why: String = "") { order.toList().forEach { cancel(it) } }

    /**
     * Runs one request and streams its events: Queued, Started, Token…, then Completed or Failed. The flow is cold:
     * collecting it submits the request, cancelling the collector cancels the request.
     */
    fun generate(request: InferenceRequest): Flow<InferenceEvent> = channelFlow {
        val id = request.id
        val signal = CancelSignal()
        val a: Active
        synchronized(admit) {
            if (active.containsKey(id)) { trySend(InferenceEvent.Failed(id, InferenceError.FAILED, "A request with this id is already running.")); return@channelFlow }
            if (active.size >= 1 + settings.int(Settings.MAX_QUEUED)) {
                trySend(InferenceEvent.Failed(id, InferenceError.BUSY, "CLU is already working on ${active.size} requests. Try again in a moment.")); return@channelFlow
            }
            a = Active(request, signal, now()); active[id] = a; order += id
        }
        val job = coroutineContext[Job]
        // Whatever ends this flow (an error, the collector going away) ends the request.
        job?.invokeOnCompletion { signal.cancel(); if (a.running) llm.cancel() }
        var watchdog: Job? = null
        try {
            send(InferenceEvent.Queued(id, order.indexOf(id)))
            // The model: loads it if needed. A failed load is a clear error, not a hang.
            val engine = llm.engine()
            if (engine == null) {
                val st = llm.state.value
                send(InferenceEvent.Failed(id, if (st.status == LlmStatus.NO_MODEL) InferenceError.NO_MODEL else InferenceError.LOAD_FAILED, st.message.ifBlank { "The model could not be loaded." })); return@channelFlow
            }
            // Fit the conversation to the context before taking the model.
            val fitted = if (request.fitContext) ContextFitter.fit(request.messages, engine.contextSize(), request.params.maxTokens) { engine.countTokens(it) } else ContextFitter.Fit(request.messages, 0, true)
            if (!fitted.fits) { send(InferenceEvent.Failed(id, InferenceError.CONTEXT_TOO_LARGE, "The newest message and the instructions alone do not fit the model's ${engine.contextSize()}-token context. Shorten the message or raise the context in the model settings.")); return@channelFlow }
            val guard = OutputGuard()
            val out = this
            watchdog = launch {
                while (isActive) {
                    delay(250)
                    val t = now(); val started = a.startedAt ?: continue
                    val limit = when {
                        t - started > settings.int(Settings.TOTAL_TIMEOUT) * 1000L -> InferenceError.TIMEOUT_TOTAL
                        a.tokens == 0 && t - started > settings.int(Settings.FIRST_TOKEN_TIMEOUT) * 1000L -> InferenceError.TIMEOUT_FIRST_TOKEN
                        a.tokens > 0 && t - a.lastTokenAt > settings.int(Settings.STALL_TIMEOUT) * 1000L -> InferenceError.TIMEOUT_STALL
                        else -> null
                    }
                    if (limit != null && a.cancelledBy == null) { a.cancelledBy = limit; signal.cancel(); llm.cancel(); break }
                }
            }
            val result = withContext(Dispatchers.IO) {
                try {
                    llm.generateTracked(fitted.messages, request.params, signal, onStarted = {
                        a.running = true; a.startedAt = now(); a.lastTokenAt = a.startedAt!!; trySend(InferenceEvent.Started(id)) }) { text ->
                        val clean = guard.push(text)
                        if (clean.isNotEmpty()) { a.tokens++; a.lastTokenAt = now(); trySend(InferenceEvent.Token(id, clean)) }
                        if (guard.looping && a.cancelledBy == null) { signal.cancel("repetition"); llm.cancel() }
                    }
                } catch (e: LlmException) { throw e } catch (e: CancellationException) { throw e }
            }
            watchdog.cancel()
            val tail = guard.finish()
            if (tail.isNotEmpty()) send(InferenceEvent.Token(id, tail))
            val text = guard.text()
            val timedOut = a.cancelledBy
            when {
                timedOut != null -> send(InferenceEvent.Failed(id, timedOut, timedOut.label + (if (text.isNotBlank()) ". The partial reply is kept in the conversation." else ".")))
                guard.looping -> send(InferenceEvent.Completed(id, result.copy(text = text, stats = result.stats.copy(stopReason = "repetition")), InferenceOutcome.REPETITION, fitted.dropped))
                result.stats.stopReason == "cancelled" || signal.isCancelled -> send(InferenceEvent.Completed(id, result.copy(text = text), InferenceOutcome.CANCELLED, fitted.dropped))
                text.isBlank() -> send(InferenceEvent.Failed(id, InferenceError.EMPTY, "The model returned no text. Try again; if it keeps happening, check the model and its chat template in the model settings."))
                else -> send(InferenceEvent.Completed(id, result.copy(text = text), if (result.stats.stopReason == "length") InferenceOutcome.LENGTH else InferenceOutcome.COMPLETE, fitted.dropped))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val cause = a.cancelledBy
            trySend(InferenceEvent.Failed(id, cause ?: InferenceError.FAILED, (e.message ?: e.toString()).lineSequence().first().take(300)))
        } finally {
            watchdog?.cancel()
            active.remove(id); order.remove(id)
        }
    }

    /** Runs a request to the end and returns its result; throws [InferenceException] if it failed. */
    suspend fun complete(request: InferenceRequest): InferenceResult {
        var result: InferenceResult? = null
        generate(request).collect { ev ->
            when (ev) {
                is InferenceEvent.Completed -> result = InferenceResult(ev.requestId, ev.result.text, ev.result.stats, ev.outcome, ev.droppedMessages)
                is InferenceEvent.Failed -> throw InferenceException(ev.error, ev.message)
                else -> {}
            }
        }
        return result ?: throw InferenceException(InferenceError.FAILED, "The request ended without a result.")
    }

    // ------------------------------------------------------------------------------------------ for components that speak LlmEngine
    /**
     * The model as the chat agent and the sync node see it: the same [LlmEngine] interface as before, but every
     * generation goes through [generate] (ids, queue limit, watchdog, output guard). Null if no model can be loaded.
     */
    suspend fun engine(): LlmEngine? {
        llm.loadIfNeeded() ?: return null
        val svc = this
        return object : LlmEngine {
            override val id get() = llm.state.value.modelId ?: ""
            override val source = "local"
            override fun isReady() = llm.state.value.status.let { it == LlmStatus.READY || it == LlmStatus.GENERATING || it == LlmStatus.CANCELLING }
            override fun countTokens(messages: List<ChatMessage>) = llm.countTokens(messages)
            override fun contextSize() = llm.state.value.contextSize
            override fun generate(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit) = generate(messages, params, CancelSignal(), onText)
            override fun generate(messages: List<ChatMessage>, params: GenParams, signal: CancelSignal, onText: (String) -> Unit): GenResult = runBlocking {
                val req = InferenceRequest(messages, params, owner = "chat", fitContext = false)
                val reg = signal.onCancel { svc.cancel(req.id) }
                try {
                    var result: GenResult? = null
                    svc.generate(req).collect { ev ->
                        when (ev) {
                            is InferenceEvent.Token -> onText(ev.text)
                            is InferenceEvent.Completed -> result = ev.result
                            is InferenceEvent.Failed -> when (ev.error) {
                                // An empty reply is a result the agent already knows how to handle; everything else is an error to report.
                                InferenceError.EMPTY -> result = GenResult("", GenStats(stopReason = "eog"), "local")
                                else -> throw InferenceException(ev.error, ev.message)
                            }
                            else -> {}
                        }
                    }
                    result ?: cancelledResult("local")
                } finally { reg.close() }
            }
            override fun cancel() { svc.cancelAll() }
        }
    }
}

/**
 * Keeps a conversation inside the context window: if the prompt (plus room for the reply) is too long, whole turns are
 * dropped from the oldest end, never the leading system message and never the newest message.
 */
object ContextFitter {
    data class Fit(val messages: List<ChatMessage>, val dropped: Int, val fits: Boolean)

    fun fit(messages: List<ChatMessage>, contextSize: Int, maxTokens: Int, count: (List<ChatMessage>) -> Int): Fit {
        if (contextSize <= 0) return Fit(messages, 0, true)
        val budget = contextSize - maxTokens.coerceAtLeast(0) - 32
        val total = count(messages)
        if (total <= budget) return Fit(messages, 0, true)
        if (messages.size <= 1) return Fit(messages, 0, false)
        val system = messages.firstOrNull()?.takeIf { it.role == "system" }
        val rest = if (system != null) messages.drop(1) else messages
        // Binary search the first kept message: the smallest drop whose prompt fits, keeping the newest message.
        fun build(drop: Int) = listOfNotNull(system) + rest.drop(drop)
        var lo = 0; var hi = rest.size - 1
        if (count(build(hi)) > budget) return Fit(build(hi), hi, false)
        while (lo < hi) { val mid = (lo + hi) / 2; if (count(build(mid)) <= budget) hi = mid else lo = mid + 1 }
        // Start on a user turn when there is one (a reply without its question confuses the model).
        var start = lo
        while (start < rest.size - 1 && rest[start].role == "assistant") start++
        return Fit(build(start), start, true)
    }
}

/**
 * Cleans the text stream and notices a model stuck in a loop: control characters are removed (newlines and tabs stay)
 * (stop sequences are applied by the engine itself), and once the same chunk of at least 12 characters has repeated 6 times in a row the reply
 * is declared looping; the repeats are trimmed from the final text.
 */
class OutputGuard {
    private val sb = StringBuilder()
    var looping = false; private set

    fun push(raw: String): String {
        if (looping) return ""
        val cleaned = raw.filter { it == '\n' || it == '\t' || it >= ' ' && it != '\u007f' }
        if (cleaned.isEmpty()) return ""
        val before = sb.length
        sb.append(cleaned)
        val trimmed = loopStart()
        if (trimmed >= 0) { looping = true; val keep = (trimmed - before).coerceIn(0, cleaned.length); sb.setLength(trimmed); return cleaned.substring(0, keep) }
        return cleaned
    }

    /** Where a run of 6 repeats of a >= 12 character chunk starts, or -1. Checked on the tail only, so it costs nothing per token. */
    private fun loopStart(): Int {
        val n = sb.length
        if (n < 72) return -1
        for (len in 12..120) {
            if (n < len * 6) break
            val tail = sb.substring(n - len * 6)
            val unit = tail.substring(0, len)
            if (unit.isBlank()) continue
            if ((1 until 6).all { tail.regionMatches(it * len, unit, 0, len) }) return n - len * 5 // keep one copy
        }
        return -1
    }

    fun finish(): String = ""
    fun text(): String = sb.toString().trimEnd()
}
