package ai.clu.ui

/**
 * The Markdown CLU's replies use, parsed without a library: headings, paragraphs, bullet and numbered lists, quotes,
 * rules, fenced code blocks, and inline bold, italic, `code` and [links](url). Tolerant of text still streaming in: an
 * unclosed fence is a code block up to the end, unclosed emphasis stays literal. Pure Kotlin (tested on the JVM).
 */
object Markdown {
    sealed class Block {
        data class Para(val text: String) : Block()
        data class Heading(val level: Int, val text: String) : Block()
        data class Item(val text: String, val marker: String, val depth: Int) : Block()
        data class Quote(val text: String) : Block()
        data class Code(val lang: String, val code: String, val closed: Boolean) : Block()
        object Rule : Block()
    }

    sealed class Inline {
        data class Text(val s: String) : Inline()
        data class Bold(val s: String) : Inline()
        data class Italic(val s: String) : Inline()
        data class Code(val s: String) : Inline()
        data class Link(val label: String, val url: String) : Inline()
    }

    private val heading = Regex("^(#{1,4})\\s+(.*)$")
    private val bullet = Regex("^(\\s*)([-*+•])\\s+(.*)$")
    private val numbered = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

    fun parse(src: String): List<Block> {
        val out = mutableListOf<Block>()
        val lines = src.replace("\r\n", "\n").split('\n')
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += Block.Para(para.toString().trim()); para.clear() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = line.trimStart()
            if (fence.startsWith("```")) {
                flush()
                val lang = fence.removePrefix("```").trim()
                val code = StringBuilder(); var closed = false; i++
                while (i < lines.size) { if (lines[i].trimStart().startsWith("```")) { closed = true; break }; if (code.isNotEmpty()) code.append('\n'); code.append(lines[i]); i++ }
                out += Block.Code(lang, code.toString(), closed); i++; continue
            }
            heading.matchEntire(line)?.let { flush(); out += Block.Heading(it.groupValues[1].length, it.groupValues[2].trim()); i++; continue }
            if (rule.matches(line)) { flush(); out += Block.Rule; i++; continue }
            bullet.matchEntire(line)?.let { flush(); out += Block.Item(it.groupValues[3], "•", it.groupValues[1].length / 2); i++; continue }
            numbered.matchEntire(line)?.let { flush(); out += Block.Item(it.groupValues[3], it.groupValues[2] + ".", it.groupValues[1].length / 2); i++; continue }
            if (line.startsWith(">")) { flush(); out += Block.Quote(line.removePrefix(">").trim()); i++; continue }
            if (line.isBlank()) flush() else { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
            i++
        }
        flush()
        return out
    }

    /** Inline spans of one line or paragraph; unmatched markers stay literal text. */
    fun inline(s: String): List<Inline> {
        val out = mutableListOf<Inline>()
        val buf = StringBuilder()
        fun text() { if (buf.isNotEmpty()) { out += Inline.Text(buf.toString()); buf.clear() } }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '`' -> { val e = s.indexOf('`', i + 1); if (e > i + 1) { text(); out += Inline.Code(s.substring(i + 1, e)); i = e + 1 } else { buf.append(c); i++ } }
                s.startsWith("**", i) || s.startsWith("__", i) -> {
                    val m = s.substring(i, i + 2); val e = s.indexOf(m, i + 2)
                    if (e > i + 2) { text(); out += Inline.Bold(s.substring(i + 2, e)); i = e + 2 } else { buf.append(m); i += 2 }
                }
                (c == '*' || c == '_') && i + 1 < s.length && !s[i + 1].isWhitespace() && (i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                    val e = s.indexOf(c, i + 1)
                    if (e > i + 1 && !s[e - 1].isWhitespace() && (e + 1 >= s.length || !s[e + 1].isLetterOrDigit())) { text(); out += Inline.Italic(s.substring(i + 1, e)); i = e + 1 }
                    else { buf.append(c); i++ }
                }
                c == '[' -> {
                    val close = s.indexOf("](", i + 1); val end = if (close > 0) s.indexOf(')', close + 2) else -1
                    val url = if (end > 0) s.substring(close + 2, end) else ""
                    if (close > i && end > close && (url.startsWith("http://") || url.startsWith("https://"))) { text(); out += Inline.Link(s.substring(i + 1, close), url); i = end + 1 }
                    else { buf.append(c); i++ }
                }
                else -> { buf.append(c); i++ }
            }
        }
        text()
        return out
    }
}
