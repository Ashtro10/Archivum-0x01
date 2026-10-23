package ai.clu.core.agent

import ai.clu.core.commands.CommandKind
import ai.clu.core.commands.CommandRepository
import ai.clu.core.commands.CustomCommand
import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.conversation.*
import ai.clu.core.diag.DiagnosticsLog
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonObject

data class TurnRequest(
    val conversationId: String,
    val text: String,
    val actor: Actor,
    val viaVoice: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val speakerName: String? = null,
    /** Timings measured before the agent (e.g. speech recognition), recorded with the reply. */
    val preStats: Map<String, Double> = emptyMap(),
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
    data class Error(val text: String) : AgentEvent()
}

/** Chooses the engine for a reply: local model or the paired home device's model. */
interface InferenceRouter { suspend fun select(): LlmEngine? }

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
) {
    private val retriever = MemoryRetriever(settings, memories)
    @Volatile private var activeEngine: LlmEngine? = null
    @Volatile private var cancelled = false

    private val shutdownRe = Regex("^(?:(?:clu|clue)[, ]+)?(?:turn off|shut down|shutdown|power off|go to sleep|stop listening)(?: now)?[.!]?$", RegexOption.IGNORE_CASE)

    fun cancel() { cancelled = true; activeEngine?.cancel() }

    fun respond(req: TurnRequest): Flow<AgentEvent> = callbackFlow {
        val job = launch(Dispatchers.Default) {
            try { handle(req) { trySend(it) } } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                diag?.e("agent", "turn failed", e)
                trySend(AgentEvent.Error("Something went wrong: ${e.message ?: e::class.simpleName}"))
            }
            close()
        }
        awaitClose { job.cancel() }
    }

    /** Core turn logic. [emit] receives events in order. */
    suspend fun handle(req: TurnRequest, emit: (AgentEvent) -> Unit) {
        cancelled = false
        val persona = personas.active()
        val userMsg = conversations.addMessage(req.conversationId, Role.USER, req.text,
            MessageMeta(attachments = req.attachments, speaker = req.actor.personId ?: (if (req.actor.role == PersonRole.OWNER) "" else "unknown"), personaId = persona.id))
        emit(AgentEvent.UserMessageSaved(userMsg))
        val text = req.text.trim()
        val baseMeta = MessageMeta(personaId = persona.id, speaker = userMsg.meta.speaker, stats = req.preStats)

        // 1. Session termination phrase typed/spoken.
        if (WakePhraseMatcher.isTermination(text, settings.list(Settings.TERMINATION_PHRASES))) {
            finish(req, templates.pick(Settings.TERMINATION_RESPONSES, now()), baseMeta.copy(source = "template"), emit)
            emit(AgentEvent.Control("end_session"))
            writeDiaryIfDue(req.conversationId)
            return
        }
        // 2. Shutdown/stop listening: owner only.
        if (shutdownRe.matches(text)) {
            when (auth.decide(req.actor, Sensitivity.CONFIG)) {
                AuthDecision.Allow -> { finish(req, "Understood. Going quiet.", baseMeta.copy(source = "template"), emit); emit(AgentEvent.Control("stop_listening")) }
                else -> finish(req, auth.refusalLine(), baseMeta.copy(source = "template"), emit)
            }
            return
        }
        val ctx = ToolContext(req.actor, persona, req.conversationId, now())

        // 3. Owner-defined custom commands.
        commands.match(text)?.let { cmd -> runCommand(cmd, req, ctx, baseMeta, emit); return }

        // 4. Deterministic routing of explicit commands.
        val routed = IntentRouter.route(text)
        if (routed != null && tools.has(routed.tool)) {
            if (routed.tool == "memory.remember" && !settings.bool(Settings.REMEMBER_COMMAND)) {
                val p = proposals.propose(ai.clu.core.memory.ProposalKind.OBSERVATION, routed.args.str("text") ?: text, "You asked me to remember this.", req.conversationId)
                p?.let { emit(AgentEvent.ProposalCreated(it)) }
                finish(req, if (p != null) "I've added that to your memory suggestions for approval." else "I couldn't add that to memory suggestions.",
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
            if (r.needsVerification) { emit(AgentEvent.NeedsVerification(r.error ?: "verification")); finish(req, r.spoken ?: r.summary, baseMeta.copy(source = "tool", tools = listOf(routed.tool)), emit); return }
            val composeWithModel = r.spoken == null && (r.success || r.summary.startsWith("OFFLINE:"))
            if (!composeWithModel) {
                finish(req, r.spoken ?: r.summary, baseMeta.copy(source = "tool", tools = listOf(routed.tool)), emit)
                return
            }
            // Information tools: let the model phrase an answer grounded in the result, if a model is available.
            generateReply(req, persona, ctx, baseMeta.copy(tools = listOf(routed.tool)), emit, preToolResult = routed.tool to r)
            return
        }

        // 5. Model-driven reply (may call tools).
        generateReply(req, persona, ctx, baseMeta, emit, preToolResult = null)
    }

    private suspend fun runCommand(cmd: CustomCommand, req: TurnRequest, ctx: ToolContext, meta: MessageMeta, emit: (AgentEvent) -> Unit) {
        if (cmd.ownerOnly && req.actor.role != PersonRole.OWNER) { finish(req, auth.refusalLine(), meta.copy(source = "template"), emit); return }
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
        finish(req, out.joinToString(" "), meta.copy(source = if (used.isEmpty()) "template" else "tool", tools = used), emit)
    }

    private suspend fun generateReply(req: TurnRequest, persona: ActivePersona, ctx: ToolContext, baseMeta: MessageMeta, emit: (AgentEvent) -> Unit,
                                      preToolResult: Pair<String, ToolResult>?) {
        val online = network.isOnline() && settings.bool(Settings.NET_ENABLED)
        emit(AgentEvent.Status("Thinking…"))
        val engine = inference.select()
        if (engine == null) {
            val fallback = preToolResult?.second?.summary
                ?: "My language model isn't loaded, so I can only handle direct commands right now (open apps, alarms, timers, memory, settings). Load a model in Models to talk normally."
            finish(req, fallback, baseMeta.copy(source = if (preToolResult != null) "tool" else "template"), emit)
            return
        }
        activeEngine = engine
        val canReadMemory = auth.decide(req.actor, Sensitivity.PERSONAL) == AuthDecision.Allow
        val mems = if (canReadMemory) retriever.retrieve(req.text, persona.readableScopes) else emptyList()
        val toolList = tools.available(persona).filter { t ->
            t.network == null || network.check(t.network!!) == null
        }.filter { it.name != "external.ai" || settings.string(Settings.EXT_AI_MODE) == "auto" || wantsDeepAnalysis(req.text) && settings.string(Settings.EXT_AI_MODE) == "on_request" }
        val pctx = PromptContext(persona, mems, toolList, now(), online, deviceRole(), req.speakerName, req.actor.role == PersonRole.OWNER)
        val params = GenerationParams.from(settings, prefs, false).copy(stop = listOf("</tool>"))
        val history = conversations.messages(req.conversationId).filter { it.role == Role.USER || it.role == Role.ASSISTANT }.dropLast(1)
        val usedTools = baseMeta.tools.toMutableList()
        var usedNetwork = preToolResult?.second?.usedNetwork == true
        val extra = mutableListOf<ChatMessage>()
        if (preToolResult != null) {
            extra += ChatMessage("user", req.text)
            extra += ChatMessage("user", "[Tool result: ${preToolResult.first}]\n${preToolResult.second.summary}\nAnswer my request using only this result. Mention the source and any uncertainty.")
        }
        val maxSteps = settings.int(Settings.AGENT_MAX_STEPS)
        var finalText = ""
        var stats = GenStats()
        var step = 0
        while (true) {
            val system = prompt.system(pctx)
            val convo = if (preToolResult == null) listOf(ChatMessage("user", req.text)) + extra else extra
            val msgs = fitToContext(engine, system, history, convo, params.maxTokens)
            val buf = StringBuilder()
            var streaming = false
            var shown = 0
            val t0 = now()
            val result = try {
                withContext(Dispatchers.IO) {
                    engine.generate(msgs, params) { delta ->
                        buf.append(delta)
                        val s = buf.toString()
                        if (!streaming && !ToolCallParser.maybeToolPrefix(s)) streaming = true
                        if (streaming) {
                            val cut = s.indexOf("<tool>").let { if (it < 0) s.length else it }
                            if (cut > shown) { emit(AgentEvent.Text(s.substring(shown, cut))); shown = cut }
                        }
                    }
                }
            } catch (e: LlmException) {
                diag?.e("agent", "generation failed", e)
                finish(req, "My model failed to produce a reply: ${e.message?.lineSequence()?.firstOrNull() ?: "unknown error"}",
                    baseMeta.copy(source = engine.source, error = e.message ?: ""), emit)
                return
            }
            stats = result.stats
            diag?.d("agent", "gen ${now() - t0}ms tokens=${result.stats.generatedTokens} stop=${result.stats.stopReason}")
            val raw = buf.toString()
            val call = if (raw.contains("<tool>")) ToolCallParser.find(raw + if (raw.contains("</tool>")) "" else "</tool>") else null
            if (call != null && step < maxSteps && !cancelled) {
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
                    finish(req, "That requires your verification first.", baseMeta.copy(source = engine.source, tools = usedTools), emit)
                    return
                }
                extra += ChatMessage("assistant", "<tool>{\"name\": \"${call.name}\", \"args\": ${call.args}}</tool>")
                extra += ChatMessage("user", "[Tool result: ${call.name}] ${if (r.success) "" else "FAILED: "}${r.summary.take(3000)}")
                emit(AgentEvent.Status("Using ${call.name}…"))
                continue
            }
            finalText = (if (call != null) raw.substringBefore("<tool>") else raw).trim()
            if (finalText.isEmpty()) finalText = if (cancelled) "" else "…"
            break
        }
        val source = when { usedTools.contains("external.ai") -> "external"; else -> engine.source }
        val meta = baseMeta.copy(source = source, engine = engine.id, tools = usedTools.distinct(), interrupted = cancelled,
            stats = baseMeta.stats + mapOf("prompt_ms" to stats.promptMs, "first_token_ms" to stats.firstTokenMs, "gen_ms" to stats.generateMs,
                "tokens" to stats.generatedTokens.toDouble(), "tok_per_s" to stats.tokensPerSecond, "reused_tokens" to stats.reusedTokens.toDouble())
                + (if (usedNetwork) mapOf("network" to 1.0) else emptyMap()))
        val msg = finish(req, finalText, meta, emit, alreadyStreamed = true)

        // Post-turn learning (never during voice turns to avoid interrupting conversation).
        if (persona.isClu && !req.viaVoice && msg != null && !cancelled) {
            prefs.recordReply()
            val alt = prefs.nextAlternative()
            if (alt != null) runCatching {
                val altMsgs = fitToContext(engine, prompt.system(pctx.copy(styleOverride = alt.styleB)), history, listOf(ChatMessage("user", req.text)), params.maxTokens)
                val b = withContext(Dispatchers.IO) { engine.generate(altMsgs, params) {} }.text.substringBefore("<tool>").trim()
                if (b.isNotEmpty()) emit(AgentEvent.Alternatives(msg.id, alt, finalText, b))
            } else prefs.nextQuestion()?.let { emit(AgentEvent.Question(it)) }
        }
        if (req.actor.role == PersonRole.OWNER && persona.isClu) InsightProposer.candidate(req.text)?.let { (t, why) ->
            proposals.propose(ai.clu.core.memory.ProposalKind.OBSERVATION, t, why, req.conversationId)?.let { emit(AgentEvent.ProposalCreated(it)) }
        }
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
            // Still too large: shrink the system prompt's memory section by truncation as a last resort.
            val sys = system.take(maxOf(500, system.length - (exact - budget) * 4))
            msgs = listOf(ChatMessage("system", sys)) + tail
        }
        return msgs
    }

    private fun finish(req: TurnRequest, text: String, meta: MessageMeta, emit: (AgentEvent) -> Unit, alreadyStreamed: Boolean = false): Message? {
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
    suspend fun writeDiaryIfDue(conversationId: String) {
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
                        ChatMessage("user", transcript)), GenParams(maxTokens = 160, temperature = 0.3f)) {}
                }.text.trim()
            }.getOrNull()
        } ?: ("Conversation about: " + userMsgs.take(4).joinToString("; ") { it.content.take(80) })
        if (mode == "automatic") memories.save(Memory(category = MemoryCategory.DIARY, title = "Conversation " +
            java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.ENGLISH).format(java.util.Date(now())), content = summary,
            source = MemorySource.CONVERSATION, eventAt = now()))
        else proposals.propose(ai.clu.core.memory.ProposalKind.DIARY, summary, "Summary of a conversation", conversationId)
    }
}
