package ai.clu.core.evolution

/**
 * Answers questions about CLU's own software from the persistent index, never by having the model read the repository:
 * "what components make up your architecture?", "where is memory implemented?", "which files control model
 * inference?", "what changed in your latest update?", "which tests cover the permission broker?".
 *
 * Every answer is built from index records and cites files and lines. The same facts are offered to the conversation
 * ([factsFor]) so CLU can talk about itself in its own words while staying grounded.
 */
class SelfKnowledge(private val indexer: SourceIndexer, private val reconciler: Reconciler, private val store: EvolutionStore) {
    data class Answer(val text: String, val citations: List<String>, val kind: String)

    /** Words that mean the same part of CLU (topic → declaration-name fragments and package fragments). */
    private val topics: List<Pair<Regex, List<String>>> = listOf(
        Regex("(?i)conversation|chat|repl(y|ies)|respon|turn|dialog") to listOf("CluAgent", "AgentController", "PromptBuilder", "ChatTurns", "Conversation", "agent", "conversation"),
        Regex("(?i)memor|remember|recall") to listOf("MemoryService", "ConversationMemory", "MemoryRetriever", "MemoryFormation", "Memory", "memory"),
        Regex("(?i)inferen|model|llm|generat|token|llama") to listOf("InferenceService", "LlmService", "LlamaEngine", "LlmEngine", "InferenceRouter", "Models", "llm"),
        Regex("(?i)permission|approv|broker|grant") to listOf("PermissionBroker", "Broker", "Approval", "Grant", "agents"),
        Regex("(?i)audit") to listOf("AuditLog", "Audit"),
        Regex("(?i)voice|speech|stt|tts|livekit|audio") to listOf("Voice", "Speech", "LiveKit", "Tts", "Stt", "voice"),
        Regex("(?i)setting|config") to listOf("Settings", "SettingsStore", "HostConfig", "config"),
        Regex("(?i)search|research|web") to listOf("SearchService", "Research", "WebSearch", "search"),
        Regex("(?i)sync") to listOf("Sync", "sync"),
        Regex("(?i)database|sqlite|schema|storage") to listOf("Schema", "SqlDriver", "CluStore", "JdbcSqlDriver", "db"),
        Regex("(?i)task|orchestr|agents? ") to listOf("AgentService", "Planner", "ExecutionManager", "agents"),
        Regex("(?i)diagnos|log|trace") to listOf("DiagnosticsLog", "RequestTrace", "diag"),
        Regex("(?i)self.?repair|repair|evolution|index") to listOf("SelfRepair", "SourceIndexer", "Reconciler", "Investigator", "evolution"),
        Regex("(?i)governor|resource|ram\\b|budget") to listOf("ResourceGovernor", "MaintenanceScheduler"),
        Regex("(?i)install|launcher") to listOf("clu.nsi", "clu_launcher", "Installer"),
    )

    /** True when the question is about CLU's own software (not the owner's projects). */
    fun isAboutSelf(q: String): Boolean {
        val s = q.lowercase()
        val self = Regex("\\b(you|your|yourself|clu|clue)\\b").containsMatchIn(s)
        val code = Regex("\\b(architecture|source( code)?|code ?base|module|modules|package|file|files|implemented|implementation|component|components|class|classes|tests?|cover(s|ed)?|repository|repo|commit|commits|changed|update|development|built|written|handles?|controls?)\\b").containsMatchIn(s)
        return self && code
    }

    fun answer(question: String): Answer {
        if (indexer.count() == 0) return Answer("I have not indexed my own source yet (no repository has been connected for self-repair).", emptyList(), "none")
        val q = question.lowercase()
        return when {
            Regex("\\b(test|tests|cover|covered|covers)\\b").containsMatchIn(q) -> tests(question)
            Regex("\\b(chang|latest|recent|update|commit|new in|last)\\w*").containsMatchIn(q) && !Regex("\\bwhere\\b").containsMatchIn(q) -> changes()
            Regex("\\b(architecture|components?|made of|consist|structure|modules)\\b").containsMatchIn(q) && !Regex("\\b(where|which (file|class))\\b").containsMatchIn(q) -> architecture()
            else -> locate(question)
        }
    }

    fun architecture(): Answer {
        val modules = indexer.modules()
        val deps = indexer.moduleDeps()
        val cites = mutableListOf<String>()
        val text = buildString {
            appendLine("My software is a Gradle build with ${modules.size} modules (indexed at ${indexer.indexedHead()?.take(10)}, ${indexer.count()} source files):")
            for (m in modules) {
                val files = indexer.files(module = m.substringBefore('/').let { if (m.startsWith("testing/")) m else it })
                val main = files.count { it.kind == "main" }; val test = files.count { it.kind == "test" }
                val uses = deps.filter { it.first == m }.joinToString { "${it.second} (${it.third})" }
                appendLine("- $m: $main main and $test test files${if (uses.isNotEmpty()) "; depends on $uses" else ""}")
            }
            val pk = indexer.packages().filter { it.first.startsWith("ai.clu.") }
            if (pk.isNotEmpty()) {
                appendLine("Main packages (files):")
                for ((p, n) in pk) {
                    val lead = indexer.files().firstOrNull { it.pkg == p && it.kind == "main" }?.let { f -> indexer.symbolsIn(f.path).firstOrNull { it.doc.isNotBlank() && it.kind != "fun" } }
                    appendLine("- $p ($n)${lead?.let { " — ${it.name}: ${it.doc}".also { _ -> cites += "${it.file}:${it.line}" } } ?: ""}")
                }
            }
        }
        return Answer(text.trimEnd(), cites, "architecture")
    }

    fun changes(limit: Int = 8): Answer {
        val commits = reconciler.recentCommits(limit)
        if (commits.isEmpty()) return Answer("I have not recorded any development history yet.", emptyList(), "changes")
        val text = buildString {
            appendLine("My most recent development updates (newest first):")
            for (c in commits) appendLine("- ${c.hash.take(10)} ${c.subject} — by ${c.author}, ${c.provenance}, ${c.files} file(s)${if (c.categories.isNotEmpty()) ", " + c.categories.joinToString() else ""}")
            val latest = reconciler.commitFiles(commits.first().hash)
            if (latest.isNotEmpty()) { appendLine("Files in the latest one:"); latest.take(15).forEach { appendLine("  $it") }; if (latest.size > 15) appendLine("  … ${latest.size - 15} more") }
        }
        return Answer(text.trimEnd(), commits.map { it.hash.take(10) }, "changes")
    }

    /** The files and declarations that implement what the question names. */
    fun locate(question: String, max: Int = 8): Answer {
        val hits = relevantFiles(question, max)
        if (hits.isEmpty()) return Answer("I could not find that in my source index.", emptyList(), "locate")
        val text = buildString {
            appendLine("From my source index:")
            for ((file, syms) in hits) {
                val f = indexer.file(file)
                appendLine("- $file (module ${f?.module}, package ${f?.pkg?.ifEmpty { "—" }}, ${f?.lines} lines)")
                syms.take(3).forEach { s -> appendLine("    ${s.kind} ${s.name} (line ${s.line})${if (s.doc.isNotBlank()) ": ${s.doc}" else ""}") }
            }
        }
        return Answer(text.trimEnd(), hits.map { (f, s) -> "$f:${s.firstOrNull()?.line ?: 1}" }, "locate")
    }

    fun tests(question: String): Answer {
        val target = relevantFiles(question.replace(Regex("(?i)\\b(which|what|tests?|cover(s|ed)?|this|functionality|the)\\b"), " "), 3).firstOrNull()
            ?: return Answer("I could not tell which part of my code you mean.", emptyList(), "tests")
        val tests = indexer.testsFor(target.first)
        val text = if (tests.isEmpty()) "I found no test that references ${target.first}. That part of my code may be untested."
        else "Tests that exercise ${target.first} (by imports, package and names; approximate, not measured coverage):\n" + tests.take(12).joinToString("\n") { "- $it" }
        return Answer(text, listOf(target.first) + tests, "tests")
    }

    /** Files ranked by how well their declarations and package match the topic of [question]. */
    fun relevantFiles(question: String, max: Int): List<Pair<String, List<SymbolHit>>> {
        val terms = topics.filter { it.first.containsMatchIn(question) }.flatMap { it.second }.toMutableList()
        // Also any CamelCase or long word the owner named directly ("PermissionBroker", "Reasoning").
        Regex("\\b[A-Z][a-z]+(?:[A-Z][a-z0-9]*)+\\b|\\b[A-Za-z]{6,}\\b").findAll(question).map { it.value }
            .filter { it.lowercase() !in STOP }.forEach { terms += it.replaceFirstChar { c -> c.uppercase() } }
        val score = HashMap<String, Double>(); val bySym = HashMap<String, MutableList<SymbolHit>>()
        terms.distinct().forEachIndexed { rank, term ->
            val weight = 1.0 / (1 + rank * 0.15)
            if (term.first().isLowerCase()) { // a package fragment
                indexer.files().filter { it.kind == "main" && it.pkg.split('.').contains(term) }.forEach { score[it.path] = (score[it.path] ?: 0.0) + 0.6 * weight }
                return@forEachIndexed
            }
            for (h in indexer.symbols(term, 40)) {
                val f = indexer.file(h.file) ?: continue
                if (f.kind != "main") continue
                val exact = h.name.equals(term, ignoreCase = true)
                val s = (if (exact) 3.0 else if (h.name.startsWith(term)) 1.5 else 0.7) * weight * (if (h.kind == "fun") 0.4 else 1.0)
                score[h.file] = (score[h.file] ?: 0.0) + s
                bySym.getOrPut(h.file) { mutableListOf() }.add(h)
            }
        }
        return score.entries.sortedByDescending { it.value }.take(max).map { (f, _) ->
            f to (bySym[f].orEmpty().distinctBy { it.name }.sortedWith(compareBy({ it.kind == "fun" }, { it.line })).ifEmpty { indexer.symbolsIn(f).filter { it.kind != "fun" }.take(3) }) }
    }

    /** Grounding facts for a conversation turn about CLU itself; empty for anything else. */
    fun factsFor(question: String): List<String> {
        if (!isAboutSelf(question) || indexer.count() == 0) return emptyList()
        return answer(question).text.lines().filter { it.isNotBlank() }.take(30)
    }

    companion object {
        private val STOP = setOf("which", "where", "module", "modules", "handles", "implemented", "implementation", "control", "controls", "files", "inside",
            "architecture", "component", "components", "functionality", "tests", "covers", "should", "would", "could", "about", "yourself", "source", "changed",
            "latest", "development", "update", "updates", "please", "explain", "repository")
    }
}
