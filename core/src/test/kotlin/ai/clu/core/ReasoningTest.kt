package ai.clu.core

import ai.clu.core.llm.*
import kotlin.test.*
import org.junit.jupiter.api.Test

/** Hidden reasoning (<think>…</think>) never reaches the user: detection, prompt switch, streaming filter, stored text. */
class ReasoningTest {
    private fun streamed(text: String, chunk: Int, startInside: Boolean = false): String {
        val f = ReasoningFilter(startInside)
        val out = StringBuilder()
        text.chunked(chunk).forEach { out.append(f.push(it)) }
        return out.append(f.finish()).toString()
    }

    @Test fun reasoningIsRemovedWhereverTheTagsAreSplit() {
        val raw = "<think>\nOkay, the user said hello. I should greet them.\n</think>\n\nHello! How can I help you today?"
        for (n in 1..raw.length) assertEquals("Hello! How can I help you today?", streamed(raw, n), "chunk size $n")
    }

    @Test fun otherTagNamesCaseAndSpacingAreHandled() {
        assertEquals("Answer.", streamed("<Analysis>secret plan</Analysis>Answer.", 3))
        assertEquals("Answer.", streamed("< thinking >x\ny</ thinking >Answer.", 2))
        assertEquals("Fine.", streamed("<reasoning>a</reasoning><reflection>b</reflection>Fine.", 1))
    }

    @Test fun anOrdinaryLessThanSignIsNotHeldBackOrLost() {
        assertEquals("3 < 5 and <b>bold</b> stay", streamed("3 < 5 and <b>bold</b> stay", 1))
        val f = ReasoningFilter()
        assertEquals("a ", f.push("a <th")) // could still become <think>: held
        assertEquals("<the end", f.push("e end")) // it did not: released
    }

    @Test fun whenThePromptOpenedTheBlockTheTextBeforeTheClosingTagIsHidden() {
        assertEquals("Paris.", streamed("The user asks about France.\n</think>\n\nParis.", 4, startInside = true))
    }

    @Test fun aStrayClosingTagIsDroppedAndAnUnclosedBlockHidesEverythingAfterIt() {
        assertEquals("Hi.", streamed("</think>Hi.", 2))
        val f = ReasoningFilter(maxHidden = 50)
        assertEquals("Sure. ", f.push("Sure. <think>"))
        f.push("x".repeat(60))
        assertTrue(f.overflow && f.inside, "a block that never closes is reported so the caller can stop and retry")
        assertEquals("", f.finish())
    }

    @Test fun templatesAreClassifiedAndReasoningIsSwitchedOffInThePrompt() {
        val qwen3 = "{%- if add_generation_prompt %}{{- '<|im_start|>assistant\\n' }}{%- if enable_thinking is defined and enable_thinking is false %}{{- '<think>\\n\\n</think>\\n\\n' }}{%- endif %}{%- endif %}"
        val r1 = "{% if add_generation_prompt %}{{'<｜Assistant｜><think>\\n'}}{% endif %}"
        val chatml = "{% for m in messages %}<|im_start|>{{m.role}}\\n{{m.content}}<|im_end|>\\n{% endfor %}{% if add_generation_prompt %}<|im_start|>assistant\\n{% endif %}"
        assertEquals(ReasoningMode.TOGGLE, Reasoning.mode(qwen3)); assertEquals(ReasoningMode.FORCED, Reasoning.mode(r1))
        assertEquals(ReasoningMode.NONE, Reasoning.mode(chatml)); assertEquals(ReasoningMode.NONE, Reasoning.mode(null))
        val p = "<|im_start|>user\nHi<|im_end|>\n<|im_start|>assistant\n"
        assertEquals(p + "<think>\n\n</think>\n\n", Reasoning.disable(p, ReasoningMode.TOGGLE), "exactly what the template renders for enable_thinking=false")
        assertEquals("<｜Assistant｜><think>\n\n</think>\n\n", Reasoning.disable("<｜Assistant｜><think>\n", ReasoningMode.FORCED))
        assertEquals(p, Reasoning.disable(p, ReasoningMode.NONE))
        assertEquals(p + "<think>\n\n</think>\n\n", Reasoning.disable(p + "<think>\n\n</think>\n\n", ReasoningMode.TOGGLE), "never closed twice")
    }

    @Test fun storedTextIsCleaned() {
        assertEquals("Hello!", Reasoning.strip("<think>\nOkay, the user said hello.\n</think>\n\nHello!"))
        assertEquals("", Reasoning.strip("<think>\nOkay, the user said"), "an unclosed block is all reasoning")
        assertEquals("Paris.", Reasoning.strip("The user asks about France.</think>Paris."))
        assertEquals("Plain text with a < sign.", Reasoning.strip("Plain text with a < sign."))
    }

    @Test fun theOutputGuardFiltersAnyEngine() {
        val g = OutputGuard()
        val out = listOf("<thi", "nk>plan", "</th", "ink>Hi ", "there.").joinToString("") { g.push(it) } + g.finish()
        assertEquals("Hi there.", out); assertEquals("Hi there.", g.text())
    }
}
