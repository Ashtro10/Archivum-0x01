package ai.clu.core.agent

import ai.clu.core.voice.StreamingSpeech
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns typed chat requests for the whole app, independent of any screen. The chat screen only observes [state], so
 * rotating the device, switching theme or leaving and reopening the chat neither cancels a reply nor leaves a stale
 * "thinking" bubble (before, the request ran in the activity's scope and died with it).
 *
 * Guarantees: at most one typed request at a time (Send is ignored while one runs; the button is Stop then);
 * [State.streaming] becomes null as soon as the reply is delivered, fails or is cancelled, whatever happens inside;
 * stopping speech never cancels generation (only [stop] and a newer typed request do).
 */
class ChatTurns(private val agent: CluAgent, private val scope: CoroutineScope) {
    data class State(
        /** Increments with every request; screens use it to reset what they show. */
        val seq: Long = 0,
        val conversationId: String? = null,
        /** The owner's text, for Retry. */
        val text: String = "",
        /** Reply text streamed so far; null when no reply is being produced. "" = waiting for the first word. */
        val streaming: String? = null,
        /** Events the screen shows besides the reply (errors, tool traces, questions, alternatives, proposals). */
        val events: List<AgentEvent> = emptyList(),
        /** Set when the request needs the owner to verify; the screen asks and calls [verificationHandled]. */
        val verification: String? = null,
    ) { val running get() = streaming != null }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state
    val running get() = _state.value.running

    /**
     * Starts [req] (origin TEXT). [speech] is created when the reply should also be spoken; [incremental] speaks while
     * generating. Returns false (and does nothing) if a typed request is still producing its reply.
     */
    @Synchronized
    fun send(req: TurnRequest, retryText: String, speech: (() -> StreamingSpeech.Response?)? = null, incremental: Boolean = true): Boolean {
        if (running) return false
        val seq = _state.value.seq + 1
        _state.value = State(seq, req.conversationId, retryText, streaming = "")
        scope.launch {
            val reply = runCatching { speech?.invoke() }.getOrNull()
            try {
                agent.respond(req.copy(origin = TurnOrigin.TEXT)).collect { ev -> onEvent(seq, ev, reply, incremental) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                add(seq, AgentEvent.Error("Something went wrong: ${e.message ?: e::class.simpleName}"))
            } finally {
                reply?.finish()
                _state.update { if (it.seq == seq) it.copy(streaming = null) else it }
            }
        }
        return true
    }

    private fun onEvent(seq: Long, ev: AgentEvent, reply: StreamingSpeech.Response?, incremental: Boolean) {
        when (ev) {
            is AgentEvent.Text -> {
                if (incremental) reply?.text(ev.delta)
                _state.update { if (it.seq == seq && it.streaming != null) it.copy(streaming = it.streaming + ev.delta) else it }
            }
            AgentEvent.ResetText -> _state.update { if (it.seq == seq && it.streaming != null) it.copy(streaming = "") else it }
            is AgentEvent.Done -> {
                if (reply != null) { if (incremental) reply.finish() else reply.say(ev.text) }
                // The reply is saved and shown from the conversation; optional follow-up work may still run.
                _state.update { if (it.seq == seq) it.copy(streaming = null) else it }
            }
            is AgentEvent.Error -> { reply?.finish(); add(seq, ev) }
            is AgentEvent.NeedsVerification -> _state.update { if (it.seq == seq) it.copy(verification = ev.reason) else it }
            is AgentEvent.Status, is AgentEvent.UserMessageSaved -> {}
            else -> add(seq, ev)
        }
    }

    private fun add(seq: Long, ev: AgentEvent) = _state.update { if (it.seq == seq) it.copy(events = it.events + ev) else it }

    fun verificationHandled() = _state.update { it.copy(verification = null) }

    /** Stop button: cancels the typed request (voice requests are untouched). */
    fun stop() = agent.cancel(TurnOrigin.TEXT)
}
