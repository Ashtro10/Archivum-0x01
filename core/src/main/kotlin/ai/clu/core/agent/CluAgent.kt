package ai.clu.core.agent

import ai.clu.core.commands.CommandKind
import ai.clu.core.commands.CommandRepository
import ai.clu.core.commands.CustomCommand
import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.conversation.*
import ai.clu.core.diag.DiagnosticsLog
import ai.clu.core.diag.RequestTrace
import ai.clu.core.diag.TurnEvent
import ai.clu.core.learning.AlternativeOffer
import ai.clu.core.learning.PreferenceEngine
import ai.clu.core.learning.PreferenceQuestion
import ai.clu.core.llm.*
import ai.clu.core.memory.*
import ai.clu.core.persona.*
import ai.clu.core.security.*
import ai.clu.core.tools.*
import ai.clu.core.voice.WakePhraseMatcher
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Who started a request. Cancelling one origin never touches another origin's request. */
enum class TurnOrigin { TEXT, VOICE, LIVEKIT }

data class TurnRequest(
    val conversationId: String,
    val text: String,
    val actor: Actor,
    val viaVoice: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val speakerName: String? = null,
    /** Timings measured before the agent (e.g. speech recognition), recorded with the reply. */
    val preStats: Map<String, Double> = emptyMap(),
    val origin: TurnOrigin = if (viaVoice) TurnOrigin.VOICE else TurnOrigin.TEXT,
)

sealed class AgentEvent {
    data class Status(val text: String) : AgentEvent()
    data class UserMessageSaved(val message: Message) : AgentEvent()
    data class Text(val delta: String) : AgentEvent()
    /** Discard text streamed so far (it turned out to be a tool call). */
    object ResetText : AgentEvent()
    data class ToolStarted(val tool: String, val args: JsonObject) : AgentEvent()
    data class ToolFinished(val tool: String, val result: ToolResult) : AgentEvent()
    data class NeedsVerification(val reason: String) : AgentEvent()
    data class Alternatives(val messageId: String, val offer: AlternativeOffer, val textA: String, val textB: String) : AgentEvent()
    data class Question(val question: PreferenceQuestion) : AgentEvent()
    data class ProposalCreated(val proposal: MemoryProposal) : AgentEvent()
    /** Voice-session control requested by the turn (e.g. "stop listening" from the owner). */
    data class Control(val command: String) : AgentEvent()
    data class Done(val message: Message?, val text: String, val meta: MessageMeta) : AgentEvent()
    /** The request failed. [kind]: "error" (inference), "timeout" or "model" (the model could not be loaded). */
    data class Error(val text: String, val kind: String = "error") : AgentEvent()
}

/**
 * Generation time limits. No first token within [firstTokenMs] of the generation call (includes waiting for another
 * request's generation and prompt processing), or no new token for [stallMs], ends the request with a timeout error
 * instead of leaving it "thinking" forever.
 */
data class TurnTimeouts(val firstTokenMs: Long = 120_000, val stallMs: Long = 30_000)

/** One running request: its own cancellation and its lifecycle trace. */
class Turn internal constructor(val origin: TurnOrigin, val signal: CancelSignal, val trace: RequestTrace.Request) {
    /** The reply has been delivered; only optional extra work (alternative reply, diary) is still running. */
    @Volatile var followUp = false
        internal set
}

/** Chooses the engine for a reply: local model or the paired home device's model. */
interface InferenceRouter {
    suspend fun select(): LlmEngine?
    /** Why [select] returned null when that was a failure (e.g. the model could not be loaded), else null. */
    fun unavailableReason(): String? = null
}

class MemoryRetriever(private val settings: SettingsStore, private val memories: MemoryRepository) {
    fun retrieve(query: String, scopes: List<String>): List<ScoredMemory> {
        if (!settings.bool(Settings.MEMORY_RETRIEVAL) || scopes.isEmpty()) return emptyList()
        val max = settings.int(Settings.MEMORY_MAX)
        if (max == 0) return emptyList()
        val min = effectiveThreshold(settings.double(Settings.MEMORY_MIN_SCORE), memories.count())
        return memories.rank(query, scopes, limit = max).filter { it.score >= min || it.memory.pinned && it.score >= min / 2 }
    }

    companion object {
        /**
         * BM25 scores grow with the size of the collection (IDF ~ ln N), so a fixed cut-off would be too strict for a
         * few memories and too lax for thousands. The configured threshold is scaled by ln(N+1)/ln(51) (1.0 at 50
         * memories), clamped to [0.35, 1.5].
         */
        fun effectiveThreshold(configured: Double, count: Long): Double =
            configured * (kotlin.math.ln(count + 1.0) / kotlin.math.ln(51.0)).coerceIn(0.35, 1.5)
    }
}

/** Suggests (never saves) observations from explicit self-descriptions in the owner's messages. */
object InsightProposer {
    private val pattern = Regex("\\bI(?:'m| am)?\\s+(always|never|usually|often|tend to|hate|love|prefer|really like|can't stand|get stressed|feel anxious)\\b([^.!?]{3,120})", RegexOption.IGNORE_CASE)
    fun candidate(text: String): Pair<String, String>? {
        val m = pattern.find(text) ?: return null
        val phrase = (m.groupValues[1] + m.groupValues[2]).trim().trimEnd(',', ';')
        return "Owner said they $phrase." to "You described this about yourself; it may matter for future advice."
    }
}

class CluAgent(
    private val settings: SettingsStore,
    private val conversations: ConversationRepository,
    private val memories: MemoryRepository,
    private val proposals: ProposalRepository,
    private val prefs: PreferenceEngine,
    private val personas: PersonaManager,
    private val prompt: PromptBuilder,
    private val templates: ResponseTemplates,
    private val tools: ToolExecutor,
    private val commands: CommandRepository,
    private val auth: AuthorizationPolicy,
    private val network: NetworkPolicy,
    private val inference: InferenceRouter,
    private val diag: DiagnosticsLog?,
    private val now: () -> Long = System::currentTimeMillis,
    private val deviceRole: () -> String = { "portable" },
    var timeouts: TurnTimeouts = TurnTimeouts(),
) {
    private val retriever = MemoryRetriever(settings, memories)
    private val turns = CopyOnWriteArrayList<Turn>()
    /** Request lifecycle (Diagnostics → Requests, logcat tag CluTurn). No conversation content. */
    val trace = RequestTrace()

    private val shutdownRe = Regex("^(?:(?:clu|clue)[, ]+)?(?:turn off|shut down|shutdown|power off|go to sleep|stop listening)(?: now)?[.!]?$", RegexOption.IGNORE_CASE)

    /**
     * Cancels the running requests of [origin] (all origins if null): the Stop button cancels text, closing the voice
     * screen cancels voice. A request from another origin keeps running.
     */
    fun cancel(origin: TurnOrigin?, reason: String = CancelSignal.USER) {
        turns.filter { origin == null || it.origin == origin }.forEach { it.signal.cancel(reason) }
    }

    /** True while a request of [origin] (any if null) is producing its reply (optional follow-up work excluded). */
    fun busy(origin: TurnOrigin? = null) = turns.any { (origin == null || it.origin == origin) && !it.followUp }

    /**
     * Runs one request. Policy for a new request while another runs: a new request of the same origin supersedes the
     * previous one (it is cancelled); requests of different origins both complete, one after the other (the model runs
     * one generation at a time). Optional follow-up work of earlier requests is always cancelled so it never delays a
     * new reply. Closing the flow (e.g. its collector is cancelled) cancels this request's generation natively.
     * Every request ends with exactly one terminal trace event and, unless its collector is gone, a Done or Error event.
     */
    fun respond(req: TurnRequest): Flow<AgentEvent> = callbackFlow {
        val turn = begin(req)
        val job = launch(Dispatchers.Default) {
            try { handle(req, turn) { trySend(it) } } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                diag?.e("agent", "turn failed", e)
                trace.record(turn.trace, TurnEvent.INFERENCE_ERROR, e::class.simpleName ?: "error")
                trySend(AgentEvent.Error("Something went wrong: ${e.message ?: e::class.simpleName}"))
            } finally { end(turn) }
            close()
        }
        awaitClose { turn.signal.cancel(CancelSignal.USER); job.cancel() }
    }.buffer(Channel.UNLIMITED) // never drop a token or the final event when the collector is briefly slow

    private fun begin(req: TurnRequest): Turn {
        turns.forEach { if (it.followUp || it.origin == req.origin) it.signal.cancel(if (it.followUp) "follow-up dropped" else CancelSignal.SUPERSEDED) }
        val t = Turn(req.origin, CancelSignal(), trace.begin(req.origin.name.lowercase(), "chars=${req.text.length}"))
        turns += t
        return t
    }

    private fun end(turn: Turn) {
        turns -= turn
        if (turn.trace.terminal == null) {
            val r = turn.signal.reason
            when (r) {
                null -> trace.record(turn.trace, TurnEvent.INFERENCE_ERROR, "ended without a result")
                CancelSignal.TIMEOUT -> trace.record(turn.trace, TurnEvent.REQUEST_TIMEOUT)
                else -> trace.record(turn.trace, TurnEvent.REQUEST_CANCELLED, r)
            }
        }
    }

    /** Core turn logic. [emit] receives events in order. */
    suspend fun handle(req: TurnRequest, emit: (AgentEvent) -> Unit) {
        val turn = begin(req)
        try { handle(req, turn, emit) } finally { end(turn) }
    }

    private suspend fun handle(req: TurnRequest, turn: Turn, emit: (AgentEvent) -> Unit) {
        val persona = personas.active()
        val userMsg = conversations.addMessage(req.conversationId, Role.USER, req.text,
            MessageMeta(attachments = req.attachments, speaker = req.actor.personId ?: (if (req.actor.role == PersonRole.OWNER) "" else "unknown"), personaId = persona.id))
        emit(AgentEvent.UserMessageSaved(userMsg))
        trace.record(turn.trace, TurnEvent.REQUEST_CREATED)
        val text = req.text.trim()
        val baseMeta = MessageMeta(personaId = persona.id, speaker = userMsg.meta.speaker, stats = req.preStats)

        // 1. Session termination phrase typed/spoken.
        if (WakePhraseMatcher.isTermination(text, settings.list(Settings.TERMINATION_PHRASES))) {
            finish(req, turn, templates.pick(Settings.TERMINATION_RESPONSES, now()), baseMeta.copy(source = "template"), emit)
            emit(AgentEvent.Control("end_session"))
            turn.followUp = true
            writeDiaryIfDue(req.conversationId, turn.signal)
            return
        }
        // 2. Shutdown/stop listening: owner only.
        if (shutdownRe.matches(text)) {
            when (auth.decide(req.actor, Sensitivity.CONFIG)) {
                AuthDecision.Allow -> { finish(req, turn, "Understood. Going quiet.", baseMeta.copy(source = "template"), emit); emit(AgentEvent.Control("stop_listening")) }
                else -> finish(req, turn, auth.refusalLine(), baseMeta.copy(source = "template"), emit)
            }
            return
        }
        val ctx = ToolContext(req.actor, persona, req.conversationId, now())

        // 3. Owner-defined custom commands.
        commands.match(text)?.let { cmd -> runCommand(cmd, req, turn, ctx, baseMeta, emit); return }

        // 4. Deterministic routing of explicit commands.
        val routed = IntentRouter.route(text)
        if (routed != null && tools.has(routed.tool)) {
            if (routed.tool == "memory.remember" && !settings.bool(Settings.REMEMBER_COMMAND)) {
                val p = proposals.propose(ai.clu.core.memory.ProposalKind.OBSERVATION, routed.args.str("text") ?: text, "You asked me to remember this.", req.conversationId)
                p?.let { emit(AgentEvent.ProposalCreated(it)) }
                finish(req, turn, if (p != null) "I've added that to your memory suggestions for approval." else "I couldn't add that to memory suggestions.",
                    baseMeta.copy(source = "template"), emit)
                return
            }
            emit(AgentEvent.ToolStarted(routed.tool, routed.args))
            var r = tools.execute(routed.tool, routed.args, ctx)
            emit(AgentEvent.ToolFinished(routed.tool, r))
            // Offline fallback: optionally answer from local knowledge, clearly marked, instead of only explaining.
            if (!r.success && !network.isOnline() && tools.networkCategory(routed.tool) != null && settings.string(Settings.OFFLINE_BEHAVIOR) == "local_answer") {
                r = r.copy(spoken = null, summary = "OFFLINE: live information is unavailable. Answer from your own knowledge only, and begin by saying " +
                    "that you are offline and the answer may be outdated.")
            }
            if (r.needsVerification) { emit(AgentEvent.NeedsVerification(r.error ?: "verification")); finish(req, turn, r.spoken ?: r.summary, baseMeta.copy(source = "tool", tools = listOf(routed.tool)), emit); return }
            val composeWithModel = r.spoken == null && (r.success || r.summary.startsWith("OFFLINE:"))
            if (!composeWithModel) {
                finish(req, turn, r.spoken ?: r.summary, baseMeta.copy(source = "tool", tools = listOf(routed.tool)), emit)
                return
            }
            // Information tools: let the model phrase an answer grounded in the result, if a model is available.
            generateReply(req, turn, persona, ctx, baseMeta.copy(tools = listOf(routed.tool)), emit, preToolResult = routed.tool to r)
            return
        }

        // 5. Model-driven reply (may call tools).
        generateReply(req, turn, persona, ctx, baseMeta, emit, preToolResult = null)
    }

    private suspend fun runCommand(cmd: CustomCommand, req: TurnRequest, turn: Turn, ctx: ToolContext, meta: MessageMeta, emit: (AgentEvent) -> Unit) {
        if (cmd.ownerOnly && req.actor.role != PersonRole.OWNER) { finish(req, turn, auth.refusalLine(), meta.copy(source = "template"), emit); return }
        val out = mutableListOf<String>()
        val used = mutableListOf<String>()
        for (step in cmd.steps) {
            emit(AgentEvent.ToolStarted(step.tool, step.args))
            val r = tools.execute(step.tool, step.args, ctx)
            emit(AgentEvent.ToolFinished(step.tool, r))
            used += step.tool
            out += r.spoken ?: r.summary
            if (!r.success) break
        }
        if (cmd.responses.isNotEmpty()) out.add(0, templates.render(cmd.responses[Math.floorMod(now() / 1000, cmd.responses.size.toLong()).toInt()], now()))
        finish(req, turn, out.joinToString(" "), meta.copy(source = if (used.isEmpty()) "template" else "tool", tools = used), emit)
    }

    private suspend fun generateReply(req: TurnRequest, turn: Turn, persona: ActivePersona, ctx: ToolContext, baseMeta: MessageMeta, emit: (AgentEvent) -> Unit,
                                      preToolResult: Pair<String, ToolResult>?) {
        val online = network.isOnline() && settings.bool(Settings.NET_ENABLED)
        emit(AgentEvent.Status("Thinking…"))
        val tSelect = System.nanoTime()
        val engine = inference.select()
        if (engine == null) {
            val reason = inference.unavailableReason()
            if (reason != null && preToolResult == null) {
                trace.record(turn.trace, TurnEvent.MODEL_INIT_FAILED, reason.lineSequence().first().take(160))
                emit(AgentEvent.Error("I couldn't load my language model: ${reason.lineSequence().first()}", kind = "model"))
                finish(req, turn, "", baseMeta.copy(source = "template", error = reason), emit)
                return
            }
            if (preToolResult == null) trace.record(turn.trace, TurnEvent.MODEL_INIT_FAILED, "no model selected")
            val fallback = preToolResult?.second?.summary
                ?: "My language model isn't loaded, so I can only handle direct commands right now (open apps, alarms, timers, memory, settings). Load a model in Models to talk normally."
            finish(req, turn, fallback, baseMeta.copy(source = if (preToolResult != null) "tool" else "template"), emit)
            return
        }
        val selectMs = (System.nanoTime() - tSelect) / 1_000_000
        trace.record(turn.trace, TurnEvent.MODEL_READY, "${engine.source} ${selectMs}ms")
        val canReadMemory = auth.decide(req.actor, Sensitivity.PERSONAL) == AuthDecision.Allow
        val mems = if (canReadMemory) retriever.retrieve(req.text, persona.readableScopes) else emptyList()
        val toolList = toolsFor(persona, req.text)
        val pctx = PromptContext(persona, mems, toolList, now(), online, deviceRole(), req.speakerName, req.actor.role == PersonRole.OWNER)
        val params = GenerationParams.from(settings, prefs, false).copy(stop = listOf("</tool>"))
        val history = conversations.messages(req.conversationId).filter { it.role == Role.USER || it.role == Role.ASSISTANT }.dropLast(1)
        val usedTools = baseMeta.tools.toMutableList()
        var usedNetwork = preToolResult?.second?.usedNetwork == true
        // Per-message context rides on the newest user message so the system prompt and history stay cacheable.
        val withContext = "[Context]\n${prompt.turnContext(pctx).trimEnd()}\n[/Context]\n"
        val extra = mutableListOf<ChatMessage>()
        if (preToolResult != null) {
            extra += ChatMessage("user", withContext + req.text)
            extra += ChatMessage("user", "[Tool result: ${preToolResult.first}]\n${preToolResult.second.summary}\nAnswer my request using only this result. Mention the source and any uncertainty.")
        }
        val maxSteps = settings.int(Settings.AGENT_MAX_STEPS)
        var finalText = ""
        var stats = GenStats()
        var step = 0
        var firstTokenRecorded = false
        while (true) {
            val system = prompt.staticSystem(pctx)
            val convo = if (preToolResult == null) listOf(ChatMessage("user", withContext + req.text)) + extra else extra
            val msgs = fitToContext(engine, system, history, convo, params.maxTokens)
            val buf = StringBuilder()
            var streaming = false
            var shown = 0
            val t0 = now()
            if (step == 0) trace.record(turn.trace, TurnEvent.GENERATION_STARTED, "messages=${msgs.size}")
            val result = try {
                timedGenerate(turn, engine, msgs, params) { delta ->
                    if (!firstTokenRecorded) { firstTokenRecorded = true; trace.record(turn.trace, TurnEvent.FIRST_TOKEN) }
                    buf.append(delta)
                    val s = buf.toString()
                    if (!streaming && !ToolCallParser.maybeToolPrefix(s)) streaming = true
                    if (streaming) {
                        val cut = s.indexOf("<tool>").let { if (it < 0) s.length else it }
                        if (cut > shown) { emit(AgentEvent.Text(s.substring(shown, cut))); shown = cut }
                    }
                }
            } catch (e: LlmException) {
                diag?.e("agent", "generation failed", e)
                val first = e.message?.lineSequence()?.firstOrNull() ?: "unknown error"
                // Shown as an error with Retry, not saved as Clu's reply (it would end up in the next prompt's history).
                emit(AgentEvent.Error("My model failed to produce a reply: $first"))
                finish(req, turn, "", baseMeta.copy(source = engine.source, error = e.message ?: "inference error"), emit)
                return
            }
            stats = result.stats
            diag?.d("agent", "gen ${now() - t0}ms tokens=${result.stats.generatedTokens} stop=${result.stats.stopReason}")
            val raw = buf.toString()
            val call = if (raw.contains("<tool>")) ToolCallParser.find(raw + if (raw.contains("</tool>")) "" else "</tool>") else null
            if (call != null && step < maxSteps && !turn.signal.isCancelled) {
                step++
                if (shown > 0) emit(AgentEvent.ResetText)
                // Without permission to share memories, the external AI only receives the owner's own words.
                val args = if (call.name == "external.ai" && !settings.bool(Settings.EXT_AI_SHARE_MEMORY))
                    kotlinx.serialization.json.buildJsonObject { put("question", kotlinx.serialization.json.JsonPrimitive(req.text)) } else call.args
                emit(AgentEvent.ToolStarted(call.name, args))
                val r = tools.execute(call.name, args, ctx)
                emit(AgentEvent.ToolFinished(call.name, r))
                usedTools += call.name
                usedNetwork = usedNetwork || r.usedNetwork
                if (r.needsVerification) {
                    emit(AgentEvent.NeedsVerification(r.error ?: "verification"))
                    finish(req, turn, "That requires your verification first.", baseMeta.copy(source = engine.source, tools = usedTools), emit)
                    return
                }
                extra += ChatMessage("assistant", "<tool>{\"name\": \"${call.name}\", \"args\": ${call.args}}</tool>")
                extra += ChatMessage("user", "[Tool result: ${call.name}] ${if (r.success) "" else "FAILED: "}${r.summary.take(3000)}")
                emit(AgentEvent.Status("Using ${call.name}…"))
                continue
            }
            // Tool markup is never reply text, also when it could not be parsed (malformed call from a small model).
            finalText = raw.substringBefore("<tool>").trim()
            break
        }
        val timedOut = turn.signal.reason == CancelSignal.TIMEOUT
        val emptyOutput = finalText.isEmpty() && !turn.signal.isCancelled
        if (timedOut) {
            val limit = if (firstTokenRecorded) "no new words for ${timeouts.stallMs / 1000} s" else "no reply after ${timeouts.firstTokenMs / 1000} s"
            emit(AgentEvent.Error("My model took too long ($limit) and was stopped. Try again, or choose a smaller model or shorter context in AI models.", kind = "timeout"))
        }
        // An empty reply (immediate end-of-generation, or only a malformed tool call) is an error with Retry, not "…".
        if (emptyOutput) emit(AgentEvent.Error("My model returned an empty reply. Please try again or rephrase.", kind = "empty"))
        val source = when { usedTools.contains("external.ai") -> "external"; else -> engine.source }
        val meta = baseMeta.copy(source = source, engine = engine.id, tools = usedTools.distinct(), interrupted = turn.signal.isCancelled,
            error = when { timedOut -> "timeout"; emptyOutput -> "empty_output"; else -> "" },
            stats = baseMeta.stats + mapOf("prompt_ms" to stats.promptMs, "first_token_ms" to stats.firstTokenMs, "gen_ms" to stats.generateMs,
                "tokens" to stats.generatedTokens.toDouble(), "tok_per_s" to stats.tokensPerSecond, "reused_tokens" to stats.reusedTokens.toDouble(),
                "prompt_tokens" to stats.promptTokens.toDouble())
                + (if (usedNetwork) mapOf("network" to 1.0) else emptyMap()))
        val msg = finish(req, turn, finalText, meta, emit, alreadyStreamed = true)
        turnMetrics(turn)

        // Post-turn learning (never during voice turns to avoid interrupting conversation). Runs as follow-up work: the
        // reply is already delivered and any new request cancels it, so it never delays the next answer.
        if (persona.isClu && !req.viaVoice && msg != null && !turn.signal.isCancelled) {
            prefs.recordReply()
            val alt = prefs.nextAlternative()
            if (alt != null) {
                turn.followUp = true
                try {
                    val altMsgs = fitToContext(engine, prompt.staticSystem(pctx.copy(styleOverride = alt.styleB)), history,
                        listOf(ChatMessage("user", withContext + req.text)), params.maxTokens)
                    val b = timedGenerate(turn, engine, altMsgs, params) {}
                    val bText = b.text.substringBefore("<tool>").trim()
                    if (bText.isNotEmpty() && b.stats.stopReason != "cancelled") emit(AgentEvent.Alternatives(msg.id, alt, finalText, bText))
                } catch (e: LlmException) { diag?.w("agent", "alternative reply failed: ${e.message?.lineSequence()?.firstOrNull()}") }
            } else prefs.nextQuestion()?.let { emit(AgentEvent.Question(it)) }
        }
        if (req.actor.role == PersonRole.OWNER && persona.isClu) InsightProposer.candidate(req.text)?.let { (t, why) ->
            proposals.propose(ai.clu.core.memory.ProposalKind.OBSERVATION, t, why, req.conversationId)?.let { emit(AgentEvent.ProposalCreated(it)) }
        }
    }

    /** Submit → model ready / first token / end, per request (Diagnostics → Performance). */
    private fun turnMetrics(turn: Turn) {
        val t = turn.trace
        t.at(TurnEvent.MODEL_READY)?.let { diag?.perf("turn_model_ready_ms", it.toDouble()) }
        t.at(TurnEvent.GENERATION_STARTED)?.let { diag?.perf("turn_generation_start_ms", it.toDouble()) }
        t.at(TurnEvent.FIRST_TOKEN)?.let { diag?.perf("turn_first_token_ms", it.toDouble()) }
        t.terminal?.let { diag?.perf("turn_total_ms", (it.atMs - t.startedAt).toDouble()) }
    }

    /**
     * Generates on the IO pool under [turn]'s signal. A watchdog cancels the generation with reason "timeout" when the
     * first token or the next token takes too long, and cancels it when this coroutine is cancelled (the blocking native
     * call cannot see coroutine cancellation by itself; before this, a cancelled turn kept the model busy to max tokens).
     */
    private suspend fun timedGenerate(turn: Turn, engine: LlmEngine, msgs: List<ChatMessage>, params: GenParams, onText: (String) -> Unit): GenResult =
        coroutineScope {
            val started = System.nanoTime()
            val lastToken = AtomicLong(0)
            val done = AtomicBoolean(false)
            val watchdog = launch {
                try {
                    while (true) {
                        delay(100)
                        val lt = lastToken.get()
                        val waitedMs = (System.nanoTime() - (if (lt == 0L) started else lt)) / 1_000_000
                        if (waitedMs > (if (lt == 0L) timeouts.firstTokenMs else timeouts.stallMs)) { turn.signal.cancel(CancelSignal.TIMEOUT); break }
                    }
                } finally { if (!done.get()) turn.signal.cancel(CancelSignal.USER) }
            }
            try {
                withContext(Dispatchers.IO) { engine.generate(msgs, params, turn.signal) { lastToken.set(System.nanoTime()); onText(it) } }
            } finally { done.set(true); watchdog.cancel() }
        }

    private fun toolsFor(persona: ActivePersona, text: String) = tools.available(persona).filter { t ->
        t.network == null || network.check(t.network!!) == null
    }.filter { it.name != "external.ai" || settings.string(Settings.EXT_AI_MODE) == "auto" || wantsDeepAnalysis(text) && settings.string(Settings.EXT_AI_MODE) == "on_request" }

    /**
     * Processes the fixed instructions (persona, tools, rules) right after the model loads, so the first message only
     * has to process its own words. Uses the same text the next reply starts with, so llama.cpp reuses it from its
     * cache. Skipped while a request is running and for remote engines. Returns the prompt statistics.
     */
    suspend fun warmUp(): GenStats? {
        if (busy()) return null
        val engine = inference.select()?.takeIf { it.source == "local" } ?: return null
        val persona = personas.active()
        val pctx = PromptContext(persona, emptyList(), toolsFor(persona, ""), now(), network.isOnline(), deviceRole())
        val system = ChatMessage("system", prompt.staticSystem(pctx))
        return try {
            withContext(Dispatchers.IO) { engine.generate(listOf(system), GenParams(maxTokens = 0), CancelSignal()) {} }.stats
                .also { diag?.i("llm", "warm-up: ${it.promptTokens} instruction tokens in ${it.promptMs.toLong()}ms") }
        } catch (e: LlmException) { diag?.w("llm", "warm-up failed: ${e.message?.lineSequence()?.firstOrNull()}"); null }
    }

    private fun wantsDeepAnalysis(text: String) = Regex("\\b(deep|deeper|in-depth|thorough|detailed) (analysis|research|look)|analy[sz]e (this )?(deeply|thoroughly)", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** Keeps the newest history that fits the context window together with system prompt and reply budget. */
    fun fitToContext(engine: LlmEngine, system: String, history: List<Message>, tail: List<ChatMessage>, maxTokens: Int): List<ChatMessage> {
        val ctxSize = engine.contextSize().takeIf { it > 0 } ?: 2048
        val budget = ctxSize - maxTokens - 32
        val hist = history.map { ChatMessage(if (it.role == Role.USER) "user" else "assistant", it.content) }.toMutableList()
        fun est(list: List<ChatMessage>) = list.sumOf { it.content.length / 3 + 8 }
        val fixed = listOf(ChatMessage("system", system)) + tail
        while (hist.isNotEmpty() && est(fixed + hist) > budget) hist.removeAt(0)
        var msgs = listOf(ChatMessage("system", system)) + hist + tail
        var exact = engine.countTokens(msgs)
        while (exact > budget && hist.isNotEmpty()) {
            hist.removeAt(0)
            msgs = listOf(ChatMessage("system", system)) + hist + tail
            exact = engine.countTokens(msgs)
        }
        if (exact > budget) {
            // Still too large: shrink the per-message context (memories) first, then the instructions, as a last resort.
            val over = (exact - budget) * 4
            val i = tail.indexOfFirst { it.content.startsWith("[Context]") }
            if (i >= 0) {
                val c = tail[i].content
                val end = c.indexOf("[/Context]")
                val ctxText = if (end > 0) c.substring(0, end) else ""
                val keep = ctxText.take(maxOf(80, ctxText.length - over))
                val shrunk = tail.toMutableList().also { it[i] = ChatMessage(it[i].role, keep.trimEnd() + "\n" + c.substring(maxOf(end, 0))) }
                msgs = listOf(ChatMessage("system", system)) + shrunk
                exact = engine.countTokens(msgs)
            }
            if (exact > budget) msgs = listOf(ChatMessage("system", system.take(maxOf(500, system.length - (exact - budget) * 4)))) + msgs.drop(1)
        }
        return msgs
    }

    private fun finish(req: TurnRequest, turn: Turn, text: String, meta: MessageMeta, emit: (AgentEvent) -> Unit, alreadyStreamed: Boolean = false): Message? {
        when {
            turn.signal.reason == CancelSignal.TIMEOUT -> trace.record(turn.trace, TurnEvent.REQUEST_TIMEOUT, "chars=${text.length}")
            turn.signal.isCancelled -> trace.record(turn.trace, TurnEvent.REQUEST_CANCELLED, "${turn.signal.reason} chars=${text.length}")
            meta.error.isNotEmpty() -> trace.record(turn.trace, TurnEvent.INFERENCE_ERROR, meta.error.lineSequence().first().take(120))
            else -> trace.record(turn.trace, TurnEvent.GENERATION_COMPLETED, "source=${meta.source}" + (meta.stats["tokens"]?.let { " tokens=${it.toInt()}" } ?: ""))
        }
        if (!alreadyStreamed && text.isNotEmpty()) emit(AgentEvent.Text(text))
        val m = if (text.isNotEmpty()) conversations.addMessage(req.conversationId, Role.ASSISTANT, text, meta) else null
        maybeTitle(req.conversationId, req.text)
        emit(AgentEvent.Done(m, text, meta))
        return m
    }

    private fun maybeTitle(conversationId: String, firstUserText: String) {
        val c = conversations.get(conversationId) ?: return
        if (c.title == "New conversation") conversations.rename(conversationId, firstUserText.lineSequence().first().take(48))
    }

    /** Owner picked one of two alternative replies. */
    fun chooseAlternative(messageId: String, offer: AlternativeOffer, chosenA: Boolean, textChosen: String, persist: Boolean) {
        prefs.chooseAlternative(offer, if (chosenA) offer.optionA else offer.optionB, persist)
        conversations.message(messageId)?.let { conversations.updateMessage(messageId, textChosen, it.meta) }
    }

    /** Creates a diary entry/proposal for a finished conversation according to diary.mode. */
    suspend fun writeDiaryIfDue(conversationId: String, signal: CancelSignal = CancelSignal()) {
        val mode = settings.string(Settings.DIARY_MODE)
        if (mode == "off" || !settings.bool(Settings.MEMORY_ENABLED)) return
        val msgs = conversations.messages(conversationId)
        val userMsgs = msgs.filter { it.role == Role.USER }
        if (userMsgs.size < 3) return
        val engine = inference.select()
        val summary = engine?.let { e ->
            runCatching {
                val transcript = msgs.takeLast(30).joinToString("\n") { "${if (it.role == Role.USER) "Owner" else "Clu"}: ${it.content.take(400)}" }
                withContext(Dispatchers.IO) {
                    e.generate(listOf(ChatMessage("system", "Write a 2-3 sentence diary entry, in third person about the owner, summarising what mattered in this conversation. No preamble."),
                        ChatMessage("user", transcript)), GenParams(maxTokens = 160, temperature = 0.3f), signal) {}
                }.takeIf { it.stats.stopReason != "cancelled" }?.text?.trim()
            }.getOrNull()
        } ?: ("Conversation about: " + userMsgs.take(4).joinToString("; ") { it.content.take(80) })
        if (mode == "automatic") memories.save(Memory(category = MemoryCategory.DIARY, title = "Conversation " +
            java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.ENGLISH).format(java.util.Date(now())), content = summary,
            source = MemorySource.CONVERSATION, eventAt = now()))
        else proposals.propose(ai.clu.core.memory.ProposalKind.DIARY, summary, "Summary of a conversation", conversationId)
    }
}
