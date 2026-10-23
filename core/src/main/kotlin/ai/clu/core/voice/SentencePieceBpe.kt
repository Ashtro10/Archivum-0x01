package ai.clu.core.voice

import java.io.InputStream
import java.util.PriorityQueue

/**
 * Minimal SentencePiece encoder (reads the model's protobuf directly) used to turn wake phrases into the token
 * sequence the keyword-spotting model expects, e.g. "HEY CLUE" -> "▁HE Y ▁C LU E". Supports Unigram (Viterbi) and
 * BPE models. Verified against the reference sentencepiece implementation in SentencePieceBpeTest.
 */
class SentencePieceBpe private constructor(private val scores: Map<String, Float>, private val unk: String, private val unigram: Boolean) {
    private val maxPieceLen = scores.keys.maxOfOrNull { it.length } ?: 1
    private val unkScore = (scores.values.minOrNull() ?: 0f) - 10f

    fun encode(text: String): List<String> {
        val normalized = "▁" + text.trim().uppercase().replace(Regex("\\s+"), "▁")
        if (unigram) return viterbi(normalized)
        val symbols = normalized.codePoints().toArray().map { String(Character.toChars(it)) }.toMutableList()
        if (symbols.isEmpty()) return emptyList()
        // Doubly linked list over symbols; merge the highest-scoring adjacent pair until none is in the vocabulary.
        val prev = IntArray(symbols.size) { it - 1 }
        val next = IntArray(symbols.size) { if (it + 1 < symbols.size) it + 1 else -1 }
        val text2 = symbols.toTypedArray()
        data class Pair(val left: Int, val right: Int, val score: Float, val merged: String)
        val queue = PriorityQueue<Pair>(compareByDescending<Pair> { it.score }.thenBy { it.left })
        fun tryAdd(l: Int, r: Int) {
            if (l < 0 || r < 0) return
            val m = text2[l] + text2[r]
            scores[m]?.let { queue.add(Pair(l, r, it, m)) }
        }
        for (i in 0 until text2.size - 1) tryAdd(i, i + 1)
        while (queue.isNotEmpty()) {
            val p = queue.poll()
            if (text2[p.left].isEmpty() || text2[p.right].isEmpty() || text2[p.left] + text2[p.right] != p.merged || next[p.left] != p.right) continue
            text2[p.left] = p.merged
            text2[p.right] = ""
            next[p.left] = next[p.right]
            if (next[p.right] >= 0) prev[next[p.right]] = p.left
            tryAdd(prev[p.left], p.left)
            tryAdd(p.left, next[p.left])
        }
        val out = mutableListOf<String>()
        var i = 0
        while (i >= 0) { if (text2[i].isNotEmpty()) out += if (text2[i] in scores) text2[i] else unk; i = next[i] }
        return out
    }

    /** Unigram segmentation maximising the sum of piece log-probabilities. */
    private fun viterbi(s: String): List<String> {
        val n = s.length
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        best[0] = 0.0
        for (i in 0 until n) {
            if (best[i] == Double.NEGATIVE_INFINITY) continue
            var matched = false
            for (len in 1..minOf(maxPieceLen, n - i)) {
                val sc = scores[s.substring(i, i + len)] ?: continue
                matched = matched || len == 1
                if (best[i] + sc > best[i + len]) { best[i + len] = best[i] + sc; from[i + len] = i }
            }
            if (!matched && best[i] + unkScore > best[i + 1]) { best[i + 1] = best[i] + unkScore; from[i + 1] = i }
        }
        val out = ArrayDeque<String>()
        var j = n
        while (j > 0) { val i = from[j]; val piece = s.substring(i, j); out.addFirst(if (piece in scores) piece else unk); j = i }
        return out.toList()
    }

    companion object {
        fun load(input: InputStream): SentencePieceBpe {
            val bytes = input.readBytes()
            val scores = HashMap<String, Float>()
            var unk = "<unk>"
            var modelType = 1 // TrainerSpec.model_type: 1 = UNIGRAM, 2 = BPE
            var pos = 0
            fun varint(): Long { var r = 0L; var s = 0; while (true) { val b = bytes[pos++].toInt() and 0xff; r = r or ((b and 0x7f).toLong() shl s); if (b < 0x80) return r; s += 7 } }
            while (pos < bytes.size) {
                val tag = varint().toInt(); val field = tag ushr 3; val wire = tag and 7
                when (wire) {
                    0 -> varint()
                    1 -> pos += 8
                    5 -> pos += 4
                    2 -> {
                        val len = varint().toInt(); val end = pos + len
                        if (field == 2) {
                            // TrainerSpec: model_type is field 3 (varint).
                            var q = pos
                            fun v2(): Long { var r = 0L; var sh = 0; while (true) { val b = bytes[q++].toInt() and 0xff; r = r or ((b and 0x7f).toLong() shl sh); if (b < 0x80) return r; sh += 7 } }
                            while (q < end) {
                                val t = v2().toInt(); val f = t ushr 3; val w = t and 7
                                when (w) { 0 -> { val v = v2(); if (f == 3) modelType = v.toInt() }; 1 -> q += 8; 5 -> q += 4; 2 -> { val l = v2().toInt(); q += l } }
                            }
                        }
                        if (field == 1) {
                            var piece = ""; var score = 0f; var type = 1
                            while (pos < end) {
                                val t = varint().toInt(); val f = t ushr 3; val w = t and 7
                                when {
                                    f == 1 && w == 2 -> { val l = varint().toInt(); piece = String(bytes, pos, l, Charsets.UTF_8); pos += l }
                                    f == 2 && w == 5 -> { score = java.lang.Float.intBitsToFloat((bytes[pos].toInt() and 0xff) or ((bytes[pos + 1].toInt() and 0xff) shl 8) or
                                        ((bytes[pos + 2].toInt() and 0xff) shl 16) or ((bytes[pos + 3].toInt() and 0xff) shl 24)); pos += 4 }
                                    f == 3 && w == 0 -> type = varint().toInt()
                                    w == 0 -> varint(); w == 1 -> pos += 8; w == 5 -> pos += 4
                                    w == 2 -> { val l = varint().toInt(); pos += l }
                                }
                            }
                            when (type) { 1, 4 -> scores[piece] = score; 2 -> unk = piece }
                        }
                        pos = end
                    }
                    else -> throw IllegalArgumentException("unsupported protobuf wire type $wire")
                }
            }
            return SentencePieceBpe(scores, unk, unigram = modelType != 2)
        }

        /** Builds the sherpa-onnx keyword specification: "▁HE Y ▁C LU E @HEY_CLUE/▁C LU E @CLUE". */
        fun keywordSpec(bpe: SentencePieceBpe, phrases: List<String>): String =
            phrases.distinct().joinToString("/") { p -> bpe.encode(p).joinToString(" ") + " @" + p.trim().uppercase().replace(Regex("\\s+"), "_") }
    }
}
