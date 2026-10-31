package ai.clu.desktop

import ai.clu.core.config.Settings
import ai.clu.core.llm.*
import ai.clu.core.memory.*
import ai.clu.core.tools.HttpFetcher
import ai.clu.core.tools.HttpResponse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * The shell in a real browser (Playwright + Chromium) against the real host: chat with streaming, research with a
 * collapsed source, memory added in the window and recalled in chat, an approved suggestion, people, documents, status
 * and settings. The model is scripted and Wikipedia is faked (no network), everything else is the production path.
 *
 * Opt-in: `-Dclu.shellUi=<node binary>` (Node with the `playwright` package resolvable). Screenshots go to
 * `-Dclu.shellUiShots=<dir>` (default build/shell-ui).
 */
class ShellUiTest {
    /** Answers the way a cooperative small model would: from the evidence block when given one, from a remembered record when given one. */
    private class ScriptedModel : LlmEngine {
        val prompts = java.util.concurrent.CopyOnWriteArrayList<List<ChatMessage>>()
        override val id = "scripted"; override val source = "local"
        override fun isReady() = true
        override fun countTokens(messages: List<ChatMessage>) = messages.sumOf { it.content.length / 4 + 4 }
        override fun contextSize() = 8192
        override fun cancel() {}
        override fun generate(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit): GenResult {
            prompts += messages
            val last = messages.last().content
            val everything = messages.joinToString("\n") { it.content }
            val text = when {
                last.startsWith("[Information found]") || last.startsWith("[Correction]") ->
                    Regex("(?m)^\\[1] [^:]+: (.+)$").find(everything)?.groupValues?.get(1)?.substringBefore(". ")?.trimEnd('.')?.plus(".") ?: "I don't know."
                last.contains("SEARCHED:") -> "I looked, but I couldn't find anything reliable about that."
                last.contains("Riya", ignoreCase = true) && everything.contains("college fest") -> "You met Riya at the college fest, around March 2018."
                last.contains("Riya", ignoreCase = true) -> "I don't have anything about Riya yet."
                else -> "Hello from CLU. How can I help?"
            }
            text.chunked(6).forEach { onText(it); Thread.sleep(15) }
            return GenResult(text, GenStats(promptTokens = 100, generatedTokens = text.length / 4, stopReason = "eog"), source)
        }
    }

    /** Wikipedia's search API for one subject; nothing else is reachable. */
    private class FakeWikipedia : HttpFetcher {
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            requests += url
            if ("list=search" in url && "Leman" in url) return HttpResponse(200, """{"query":{"searchinfo":{"totalhits":1},"search":[{"title":"Leman Russ",""" +
                """"snippet":"Leman Russ is the Primarch of the Space Wolves, one of the Space Marine Legions in Warhammer 40,000. He leads the VI Legion.","timestamp":"2025-11-02T10:00:00Z"}]}}""")
            return HttpResponse(404, "")
        }
        override suspend fun postJson(url: String, json: String, headers: Map<String, String>) = HttpResponse(404, "")
    }

    /** Things for the memory screens to act on: a near-duplicate pair, a conflicting suggestion, a recurring preference, a working note, a knowledge memory. */
    private fun seedMemoryScreens(host: RunningHost) {
        val core = host.core
        listOf("Decided to use an STM32 board instead of Arduino for the hexapod project.", "Decided to use an STM32 board instead of an Arduino for the hexapod robot project.").forEach { t ->
            core.memory.remember(Memory(category = MemoryCategory.DECISION, title = "Chose STM32", content = t, source = MemorySource.OWNER_SUPPLIED)) }
        core.consolidator.run()
        core.memory.remember(Memory(category = MemoryCategory.PREFERENCE, title = "Editor", content = "My favourite editor is Vim.", source = MemorySource.OWNER_SUPPLIED))
        core.formation.process("My favourite editor is Neovim.", core.conversations.create("Editors chat").id)
        core.memory.remember(Memory(category = MemoryCategory.KNOWLEDGE, title = "PID control", content = "A PID controller combines proportional, integral and derivative terms.", source = MemorySource.OWNER_SUPPLIED))
        core.memory.working.put("Parked on level 3")
        val day = 86_400_000L; val now = System.currentTimeMillis()
        listOf(3, 2, 1).forEachIndexed { i, ago -> core.store.db.execute("INSERT INTO preference_signals(id, target, value, polarity, explicit, task_tag, conversation_id, note, created_at) VALUES(?,?,?,1,1,'','c$i','be more concise',?)",
            listOf("seed$i", "setting:${Settings.RESPONSE_LENGTH}", "brief", now - ago * day)) }
        core.learner.record("setting:${Settings.RESPONSE_LENGTH}", "brief", explicit = true, conversationId = "c4", note = "be more concise")
    }

    @Test fun theWindowWorksEndToEnd() {
        val node = System.getProperty("clu.shellUi")
        assumeTrue(!node.isNullOrBlank(), "opt-in: -Dclu.shellUi=<node>")
        val script = listOf(File("src/test/ui/shell.ui.js"), File("desktop/src/test/ui/shell.ui.js")).first { it.exists() }
        val shots = File(System.getProperty("clu.shellUiShots") ?: "build/shell-ui").apply { mkdirs() }

        val dir = Files.createTempDirectory("clu-shell-ui").toFile()
        val web = FakeWikipedia()
        val host = Host.start(DesktopPaths(File(dir, "home")), HostConfig(maintenanceMinutes = 0), port = 0, http = web, log = {})
        try {
            val model = ScriptedModel()
            host.core.remoteEngineProvider = { model }
            host.core.settings.set(Settings.REMOTE_INFERENCE, "prefer_home")
            host.core.settings.set(Settings.SEARCH_READ_PAGES, false)
            host.core.proposals.propose(ProposalKind.OBSERVATION, "Prefers tea in the evening.", "mentioned it twice in chat")
            seedMemoryScreens(host)

            val p = ProcessBuilder(node, script.absolutePath, host.url, host.api.issueLaunchCode(), shots.absolutePath).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it); println("[ui] $it") } }.apply { start() }
            val finished = p.waitFor(180, TimeUnit.SECONDS)
            if (!finished) p.destroyForcibly()
            reader.join(2000)
            assertTrue(finished, "UI script timed out:\n$out")
            assertEquals(0, p.exitValue(), "UI script failed:\n$out")

            // What the window did went through the API into the real stores.
            val memories = host.core.memories.list(null, null)
            assertTrue(memories.any { it.title == "Met Riya at the college fest" && it.historicalDate().label().contains("2018") }, memories.map { it.title }.toString())
            assertTrue(memories.any { it.content.contains("green tea") }, "the suggestion was approved with the owner's edit")
            assertTrue(host.core.proposals.pending().isEmpty())
            // The memory screens did real work on the stores.
            val editors = memories.filter { it.content.contains("editor is") }
            assertEquals(2, editors.size, "the conflicting statement was approved next to the old one, not over it")
            val newer = editors.first { it.content.contains("Neovim") }
            assertTrue(host.core.memory.relatedTo(newer.id).any { it.relation == MemoryService.CONTRADICTS })
            assertEquals(0, host.core.consolidator.pending().size); assertEquals(1, memories.count { it.title == "Chose STM32" }, "the near-duplicates were merged on the owner's say-so")
            assertEquals("brief", host.core.settings.string(Settings.RESPONSE_LENGTH), "the learning question was accepted by the owner")
            assertTrue(host.core.learner.overview().any { it.signals >= 1 && it.value == "brief" })
            assertTrue(DatabaseBackup.list(host.paths.backups).any { it.label == "manual" }, "the backup button made a verified backup")
            assertEquals(0, host.core.memory.working.list().count { it.text == "Parked on level 3" }, "the note was dismissed")
            assertTrue(host.core.archive.people.list().any { it.name == "Riya Sharma" })
            assertTrue(host.core.archive.documents.list().any { it.name == "club-notes.txt" })
            assertTrue(web.requests.none { "Riya" in it || "college" in it || "tea" in it }, "no memory or personal detail was sent to search: ${web.requests}")
        } finally {
            host.stop(); dir.deleteRecursively()
        }
    }
}
