package ai.clu.core.vision

import ai.clu.core.security.Sensitivity
import ai.clu.core.tools.Tool
import ai.clu.core.tools.ToolContext
import ai.clu.core.tools.ToolResult
import ai.clu.core.tools.str
import kotlinx.serialization.json.JsonObject

/**
 * An optional local vision engine (for example llama.cpp's multimodal library with a small vision-language GGUF and its
 * projector). None ships with CLU yet: the vendored llama.cpp contains the multimodal code (tools/mtmd), but no vision
 * model could be obtained or verified in the build environment, so no engine is registered and CLU says it can't see
 * images. When an engine is set, [VisionDescribeTool] becomes available; every answer comes from the engine's actual
 * output for the actual image, never from the file name or a guess.
 */
interface VisionEngine {
    /** Model name for diagnostics. */
    val model: String
    /** Answers [question] about the image at [path] (JPEG/PNG), or null when the image couldn't be processed. */
    fun ask(path: String, question: String): String?
}

class VisionDescribeTool(private val engine: VisionEngine) : Tool {
    override val name = "vision.describe"
    override val description = "Describe or read an image the owner attached."
    override val argsExample = """{"path":"image file","question":"What is this?"}"""
    override val sensitivity = Sensitivity.PERSONAL
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult.fail("Which image?")
        val q = args.str("question") ?: "Describe this image, and read any text in it."
        val answer = runCatching { engine.ask(path, q) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return ToolResult.fail("I couldn't process that image.")
        return ToolResult.ok(answer)
    }
}

/** Without an engine: questions about images get an honest answer instead of a text model's guess. */
class VisionUnavailableTool : Tool {
    override val name = "vision.describe"
    override val description = "Describe or read an image (no vision model on this device)."
    override val argsExample = "{}"
    override val sensitivity = Sensitivity.ROUTINE
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = ToolResult.fail(Vision.UNAVAILABLE)
}

object Vision {
    /** What CLU says when asked about an image without a vision engine. */
    const val UNAVAILABLE = "I can't see images yet: there's no vision model on this device, so I won't guess what's in it. " +
        "I can read text documents and PDFs you attach."
}
