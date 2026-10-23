package ai.clu.core

import ai.clu.core.voice.SentencePieceBpe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*
import org.junit.jupiter.api.Test

/** Compares the Kotlin BPE encoder with reference outputs produced by the official sentencepiece library. */
class SentencePieceBpeTest {
    private val bpe = SentencePieceBpe.load(javaClass.getResourceAsStream("/kws-bpe.model")!!)
    private val expected = Json.parseToJsonElement(javaClass.getResource("/kws-bpe-expected.json")!!.readText()).jsonObject

    @Test fun matchesReferenceTokenizer() {
        for ((text, toks) in expected) assertEquals(toks.jsonPrimitive.content, bpe.encode(text).joinToString(" "), text)
    }

    @Test fun keywordSpecFormat() {
        assertEquals("▁C LU E @CLUE/▁HE Y ▁C LU E @HEY_CLUE", SentencePieceBpe.keywordSpec(bpe, listOf("Clue", "hey  clue")))
    }
}
