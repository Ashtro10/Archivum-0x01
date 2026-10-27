package ai.clu.core.llm

import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

data class ChatMessage(val role: String, val content: String)

data class GenParams(
    val maxTokens: Int = 400,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val seed: Int = -1,
    /** Optional GBNF grammar constraining the output. */
    val grammar: String? = null,
    /** Generation stops when any of these strings appears (the stop string is not included). */
    val stop: List<String> = emptyList(),
    /** Cores to leave free while generating token by token (on-device speech runs alongside); 0 = use all configured. */
    val reserveThreads: Int = 0,
)

@Serializable
data class GenStats(
    val promptTokens: Int = 0,
    val reusedTokens: Int = 0,
    val generatedTokens: Int = 0,
    val promptMs: Double = 0.0,
    val generateMs: Double = 0.0,
    val firstTokenMs: Double = -1.0,
    val stopReason: String = "",
) {
    val tokensPerSecond: Double get() = if (generateMs > 0) generatedTokens * 1000.0 / generateMs else 0.0
}

data class GenResult(val text: String, val stats: GenStats, val source: String)

class LlmException(message: String) : Exception(message)

/** A text generation engine. Implementations: local llama.cpp, the paired home device, optional external API. */
interface LlmEngine {
    val id: String
    /** "local" | "home_device" | "external" */
    val source: String
    fun isReady(): Boolean
    /** Number of tokens [messages] would occupy once formatted (used for context budgeting). */
    fun countTokens(messages: List<ChatMessage>): Int
    fun contextSize(): Int
    /** Blocking generation; [onText] receives decoded text increments. Throws [LlmException] on failure. */
    fun generate(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit): GenResult
    /** Cancels whatever this engine is generating. Prefer the per-request [CancelSignal] overload of [generate]. */
    fun cancel()

    /**
     * Generation owned by one request: cancelling [signal] stops this request only. Returns a result with stop reason
     * "cancelled" (and the text produced so far) instead of throwing. Engines that serialise requests override this so a
     * queued request is dropped without touching the running one.
     */
    fun generate(messages: List<ChatMessage>, params: GenParams, signal: CancelSignal, onText: (String) -> Unit): GenResult {
        if (signal.isCancelled) return cancelledResult(source)
        return signal.onCancel { cancel() }.use { generate(messages, params) { if (!signal.isCancelled) onText(it) } }
    }
}

fun cancelledResult(source: String) = GenResult("", GenStats(stopReason = "cancelled"), source)

/** Incremental UTF-8 decoder that tolerates multi-byte characters split across tokens. */
class Utf8Stream {
    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)

    fun push(bytes: ByteArray): String {
        val all = pending + bytes
        val bb = ByteBuffer.wrap(all)
        val out = CharBuffer.allocate(all.size + 4)
        decoder.decode(bb, out, false)
        pending = all.copyOfRange(bb.position(), all.size)
        (out as java.nio.Buffer).flip() // Buffer.flip(): CharBuffer.flip() overload is JDK 9+, absent on Android 13
        return out.toString()
    }

    fun finish(): String {
        if (pending.isEmpty()) return ""
        val s = String(pending, Charsets.UTF_8); pending = ByteArray(0); return s
    }
}

/**
 * Applies stop sequences to a text stream: holds back text that could be the beginning of a stop string and reports
 * when one is reached.
 */
class StopSequenceFilter(private val stops: List<String>) {
    private val held = StringBuilder()
    var stopped = false
        private set

    fun push(text: String): String {
        if (stopped) return ""
        held.append(text)
        val s = held.toString()
        for (stop in stops) {
            val i = s.indexOf(stop)
            if (i >= 0) { stopped = true; held.setLength(0); return s.substring(0, i) }
        }
        val keep = stops.maxOfOrNull { stop -> (1 until stop.length).filter { s.endsWith(stop.substring(0, it)) }.maxOrNull() ?: 0 } ?: 0
        val emit = s.substring(0, s.length - keep)
        held.setLength(0); held.append(s.substring(s.length - keep))
        return emit
    }

    fun flush(): String { val s = if (stopped) "" else held.toString(); held.setLength(0); return s }
}
