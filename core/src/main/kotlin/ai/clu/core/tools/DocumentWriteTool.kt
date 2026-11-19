package ai.clu.core.tools

import ai.clu.core.history.DocumentImportException
import ai.clu.core.history.DocumentRepository
import ai.clu.core.security.Sensitivity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Saves a new Markdown document in the owner's archive (the same store as imported documents, so it appears in the
 * Documents view on the PC and on Android and is searchable). It never overwrites: a name already in use gets a number.
 * The text is CLU's, composed from the owner's words and the evidence a goal found (see GoalEngine provenance).
 */
class DocumentWriteTool(private val documents: () -> DocumentRepository?) : Tool {
    override val name = "document.write"
    override val description = "Save a new document (never overwrites)."
    override val argsExample = """{"title":"title","content":"text"}"""
    override val sensitivity = Sensitivity.PERSONAL

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val repo = documents() ?: return ToolResult.fail("This device has no document archive to save to.")
        val title = args.str("title")?.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.take(80)
            ?.ifBlank { null } ?: return ToolResult.fail("What should the document be called?")
        val content = args.str("content")?.takeIf { it.length >= 20 } ?: return ToolResult.fail("There is no text to save.")
        val taken = repo.list().map { it.name.lowercase() }.toSet()
        val name = generateSequence(1) { it + 1 }.map { n -> if (n == 1) "$title.md" else "$title ($n).md" }.first { it.lowercase() !in taken }
        val doc = try {
            repo.import(name, content.toByteArray(Charsets.UTF_8), description = "Written by CLU", allowDuplicate = true)
        } catch (e: DocumentImportException) { return ToolResult.fail("I couldn't save the document: ${e.message}") }
        val words = Regex("\\S+").findAll(content).count()
        return ToolResult.ok("Saved the document \"${doc.name}\" ($words words) in your documents.", verified = repo.get(doc.id) != null,
            data = buildJsonObject { put("id", doc.id); put("name", doc.name); put("words", words) })
    }
}
