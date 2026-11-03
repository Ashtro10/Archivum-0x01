package ai.clu.core.llm

/**
 * Reasoning ("thinking") models: Qwen3, DeepSeek-R1 distills, QwQ and others first write hidden reasoning between
 * `<think>` and `</think>` and only then the answer. For conversation CLU never wants that: it costs hundreds of tokens
 * (seconds to tens of seconds) before the first word, and the reasoning must never be shown, spoken or stored.
 *
 * [mode] reads the model's own chat template; [CLOSED_BLOCK] is what the template itself emits when reasoning is turned
 * off (Qwen3's `enable_thinking=false`: an empty, closed think block after the assistant prefix), so the model answers
 * directly. [ReasoningFilter] removes any reasoning a model writes anyway, also when a tag is split across tokens.
 */
enum class ReasoningMode {
    /** No reasoning markup in the template. */
    NONE,
    /** The template can switch reasoning off (`enable_thinking`): CLU does, with [Reasoning.CLOSED_BLOCK]. */
    TOGGLE,
    /** The template opens `<think>` for every reply (R1-style): CLU closes it right away with [Reasoning.CLOSED_BLOCK]. */
    FORCED,
}

object Reasoning {
    const val CLOSED_BLOCK = "<think>\n\n</think>\n\n"

    fun mode(chatTemplate: String?): ReasoningMode {
        val t = chatTemplate ?: return ReasoningMode.NONE
        if ("enable_thinking" in t) return ReasoningMode.TOGGLE
        // R1-style: the generation prompt itself starts the reasoning block.
        val gen = t.substringAfterLast("add_generation_prompt", "")
        if (Regex("<think>\\\\?n?['\"]").containsMatchIn(gen) || gen.contains("<think>\\n")) return ReasoningMode.FORCED
        return ReasoningMode.NONE
    }

    /** The prompt with reasoning switched off for [mode] (unchanged for [ReasoningMode.NONE] or if already closed). */
    fun disable(prompt: String, mode: ReasoningMode): String = when (mode) {
        ReasoningMode.NONE -> prompt
        else -> {
            val trimmed = prompt.trimEnd()
            when {
                trimmed.endsWith("</think>") -> prompt
                trimmed.endsWith("<think>") -> trimmed + "\n\n</think>\n\n" // the template already opened the block
                else -> prompt + CLOSED_BLOCK
            }
        }
    }

    private val BLOCK = Regex("(?is)<\\s*(think|thinking|analysis|reasoning|reflection)\\s*>.*?(<\\s*/\\s*\\1\\s*>|$)")
    private val STRAY = Regex("(?i)<\\s*/?\\s*(think|thinking|analysis|reasoning|reflection)\\s*>")

    /** Whole reasoning blocks (closed or not) and stray tags removed from finished text (stored history, older replies). */
    fun strip(text: String): String {
        if ('<' !in text) return text
        // Text before a lone closing tag was reasoning whose opening tag was in the prompt.
        val afterLoneClose = Regex("(?is)^(?:(?!<\\s*(?:think|thinking|analysis|reasoning|reflection)\\s*>).)*?<\\s*/\\s*(think|thinking|analysis|reasoning|reflection)\\s*>").find(text)
        val t = if (afterLoneClose != null && afterLoneClose.range.first == 0) text.substring(afterLoneClose.range.last + 1) else text
        val noReasoning = STRAY.replace(BLOCK.replace(t, ""), "")
        // Chat-template control tokens: an end-of-turn token ends the reply (what follows is the model inventing the next turn).
        val end = END_TOKEN.find(noReasoning)?.range?.first ?: noReasoning.length
        return CONTROL_TOKEN.replace(noReasoning.substring(0, end), "").trim()
    }

    /** End-of-turn / end-of-text tokens of common chat templates (ChatML, Llama 3, Phi, Gemma, Mistral-style). */
    internal val END_TOKEN = Regex("<\\|(?:im_end|eot_id|end_of_text|endoftext|end|eos)\\|>|<end_of_turn>|</s>")
    /** Any other template control token ("<|im_start|>", "<|start_header_id|>", "<|assistant|>"…). */
    internal val CONTROL_TOKEN = Regex("<\\|[a-z_]{2,24}\\|>|<start_of_turn>(?:model|user)?")
}

/**
 * Streaming removal of reasoning blocks. Text is released as soon as it cannot be part of a tag: a trailing `<…` that
 * could still become `<think>` / `</think>` is held back (at most [HOLD] characters). Inside a block nothing is released.
 * [startInside]: the prompt already opened the block. [maxHidden]: characters of reasoning after which [overflow] is set
 * (the caller stops and retries with reasoning closed, so a model that never stops thinking never leaves the user waiting).
 */
class ReasoningFilter(startInside: Boolean = false, private val maxHidden: Int = 1500) {
    private val pending = StringBuilder()
    var inside = startInside; private set
    private var closeTag = if (startInside) "think" else ""
    var hiddenChars = 0; private set
    var hiddenPieces = 0; private set
    val overflow get() = inside && hiddenChars > maxHidden
    /** True once any reasoning was seen (and removed). */
    var sawReasoning = startInside; private set

    /** An end-of-turn token was seen: everything after it is dropped. */
    var ended = false; private set

    fun push(piece: String): String {
        if (piece.isEmpty() || ended) return ""
        if (inside) hiddenPieces++
        pending.append(piece)
        return drain(final = false)
    }

    fun finish(): String {
        val out = drain(final = true)
        val rest = if (inside) { hiddenChars += pending.length; "" } else pending.toString()
        pending.setLength(0)
        return out + rest
    }

    private fun drain(final: Boolean): String {
        val out = StringBuilder()
        while (pending.isNotEmpty()) {
            if (inside) {
                val m = CLOSE.find(pending)
                if (m != null && m.groupValues[1].equals(closeTag, true)) {
                    val first = m.range.first; val last = m.range.last
                    hiddenChars += first
                    pending.delete(0, last + 1)
                    inside = false; trimLeading = true // no blank line where the block was
                    continue
                }
                // Keep a possible partial closing tag; everything before it is reasoning.
                val keep = if (final) 0 else partialTagLength()
                hiddenChars += pending.length - keep
                pending.delete(0, pending.length - keep)
                return out.toString()
            }
            // An end-of-turn token: the reply is over; anything after it is discarded.
            Reasoning.END_TOKEN.find(pending)?.let { m -> val first = m.range.first; release(out, first); pending.setLength(0); ended = true; return out.toString() }
            val ctl = Reasoning.CONTROL_TOKEN.find(pending)
            if (ctl != null) { val first = ctl.range.first; val last = ctl.range.last; release(out, first); pending.delete(0, last - first + 1); continue }
            val open = OPEN.find(pending)
            val stray = CLOSE.find(pending)
            if (stray != null && (open == null || stray.range.first < open.range.first)) {
                // A closing tag with no opening: drop the tag (the reasoning it closed was in the prompt).
                val first = stray.range.first; val last = stray.range.last
                release(out, first); pending.delete(0, last - first + 1); sawReasoning = true; trimLeading = true
                continue
            }
            if (open != null) {
                val first = open.range.first; val last = open.range.last; val name = open.groupValues[1]
                release(out, first)
                pending.delete(0, last - first + 1)
                inside = true; sawReasoning = true; closeTag = name
                continue
            }
            val keep = if (final) 0 else partialTagLength()
            release(out, pending.length - keep)
            return out.toString()
        }
        return out.toString()
    }

    private var trimLeading = false
    /** Moves the first [n] pending characters to [out] (dropping leading whitespace right after a removed block). */
    private fun release(out: StringBuilder, n: Int) {
        var start = 0
        if (trimLeading) { while (start < n && pending[start].isWhitespace()) start++; if (start < n) trimLeading = false }
        out.append(pending, start, n); pending.delete(0, n)
    }

    /** Length of a trailing `<…` that may still grow into a reasoning tag. */
    private fun partialTagLength(): Int {
        val lt = pending.lastIndexOf("<")
        if (lt < 0 || pending.length - lt > HOLD) return 0
        val tail = pending.substring(lt).lowercase().replace(" ", "")
        return if (NAMES.any { n -> "<$n>".startsWith(tail) || "</$n>".startsWith(tail) } || tail.startsWith("<|") && tail.drop(2).indexOf('|').let { it == -1 || it == tail.length - 3 } || "</s>".startsWith(tail) ||
            "<end_of_turn>".startsWith(tail) || "<start_of_turn>".startsWith(tail)) pending.length - lt else 0
    }

    companion object {
        private const val HOLD = 32
        private val NAMES = listOf("think", "thinking", "analysis", "reasoning", "reflection")
        private val OPEN = Regex("(?i)<\\s*(think|thinking|analysis|reasoning|reflection)\\s*>")
        private val CLOSE = Regex("(?i)<\\s*/\\s*(think|thinking|analysis|reasoning|reflection)\\s*>")
    }
}
