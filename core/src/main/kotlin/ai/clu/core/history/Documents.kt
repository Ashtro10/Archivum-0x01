package ai.clu.core.history

import ai.clu.core.db.CluStore
import ai.clu.core.db.lng
import ai.clu.core.db.queryOne
import ai.clu.core.db.str
import ai.clu.core.memory.TextAnalysis
import ai.clu.core.util.CluJson
import ai.clu.core.util.newId
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import kotlin.math.ln

/** A file in the personal archive: its metadata, where the managed copy lives, and whether its text is searchable. */
@Serializable
data class ArchiveDocument(
    val id: String = newId(),
    val name: String,
    /** txt, md, csv, html, json, docx, pdf, image */
    val type: String,
    val sizeBytes: Long,
    val sha256: String,
    /** The managed local copy (inside the app's private storage). */
    val storedPath: String = "",
    /** Where it was imported from (a content URI or path), for reference only. */
    val originalRef: String = "",
    /** indexed | no_text (kept as an attachment; nothing to search) */
    val textStatus: String = "indexed",
    val chars: Int = 0,
    val description: String = "",
    val date: HistoricalDate = HistoricalDate.UNKNOWN,
    val tags: List<String> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** A retrieved piece of a document: [chunk] is its position in the document (0-based), so a citation can point at it. */
data class Passage(val document: ArchiveDocument, val text: String, val score: Double, val chunk: Int = 0)

class DocumentImportException(val reason: Reason, message: String, val existingId: String? = null) : Exception(message) {
    enum class Reason { UNSUPPORTED, CORRUPT, NO_TEXT, TOO_LARGE, DUPLICATE, EMPTY }
}

/** Text extraction for the formats the archive supports, without OCR or heavyweight libraries. */
object TextExtractors {
    /** Source code and project files: read as plain text and indexed like any text (no parsing, no execution). */
    val CODE_TYPES = setOf("kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "c", "h", "cc", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "swift",
        "sh", "bat", "cmd", "ps1", "sql", "yaml", "yml", "toml", "ini", "cfg", "xml", "gradle", "properties", "cmake", "css", "scss", "tex", "rst", "adoc")
    val TEXT_TYPES = setOf("txt", "text", "md", "markdown", "csv", "log", "json", "html", "htm") + CODE_TYPES
    val IMAGE_TYPES = setOf("png", "jpg", "jpeg", "webp", "gif")
    const val MAX_BYTES = 50L * 1024 * 1024

    fun typeOf(name: String, bytes: ByteArray): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            bytes.size >= 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte() -> "pdf"
            ext == "docx" -> "docx"
            ext in IMAGE_TYPES -> "image"
            ext in setOf("md", "markdown") -> "md"
            ext in setOf("html", "htm") -> "html"
            ext in TEXT_TYPES -> if (ext == "text" || ext == "log") "txt" else ext
            ext == "pdf" -> "pdf"
            else -> null
        }
    }

    /** Extracted text; throws [DocumentImportException] for corrupt or unreadable content. */
    fun extract(type: String, bytes: ByteArray): String = when (type) {
        "pdf" -> Pdf.text(bytes)
        "docx" -> docx(bytes)
        "html" -> html(decodeText(bytes))
        "image" -> ""
        else -> decodeText(bytes)
    }

    fun decodeText(bytes: ByteArray): String {
        if (bytes.take(8192).count { it == 0.toByte() } > 0) throw DocumentImportException(DocumentImportException.Reason.CORRUPT, "This doesn't look like a text file (it contains binary data).")
        val dec = Charsets.UTF_8.newDecoder()
        val text = runCatching { dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrElse { String(bytes, Charsets.ISO_8859_1) }
        return text.removePrefix("﻿")
    }

    fun html(s: String) = s.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>"), "\n")
        .replace(Regex("<[^>]+>"), " ").let(::entities).replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n\n").trim()

    private fun entities(s: String) = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&#39;", "'").replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { c -> String(Character.toChars(c)) } ?: "" }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toIntOrNull(16)?.let { c -> String(Character.toChars(c)) } ?: "" }.replace("&amp;", "&")

    /** Word (.docx): the body text of word/document.xml, paragraph by paragraph. */
    fun docx(bytes: ByteArray): String {
        var xml: String? = null
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                while (true) { val e = z.nextEntry ?: break; if (e.name == "word/document.xml") { xml = z.readBytes().toString(Charsets.UTF_8); break } }
            }
        } catch (e: Exception) { throw DocumentImportException(DocumentImportException.Reason.CORRUPT, "The Word file is damaged or not a .docx file.") }
        val x = xml ?: throw DocumentImportException(DocumentImportException.Reason.CORRUPT, "The Word file has no document body (not a .docx file?).")
        val sb = StringBuilder()
        Regex("<w:p[ >/].*?</w:p>|<w:p/>", RegexOption.DOT_MATCHES_ALL).findAll(x).forEach { p ->
            val para = Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>|<w:tab/>|<w:br/>", RegexOption.DOT_MATCHES_ALL).findAll(p.value).joinToString("") {
                when { it.value == "<w:tab/>" -> "\t"; it.value == "<w:br/>" -> "\n"; else -> entities(it.groupValues[1]) } }
            sb.append(para).append('\n')
        }
        return sb.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /** Share of characters that are letters, digits, spaces or common punctuation (a garbled extraction scores low). */
    fun quality(s: String): Double {
        if (s.isEmpty()) return 0.0
        val good = s.count { it.isLetterOrDigit() || it.isWhitespace() || it in ".,;:!?'\"()-–—/&%€$£₹@#+*" }
        return good.toDouble() / s.length
    }
}

/**
 * A small PDF text reader: objects, Flate-compressed streams, text-showing operators (Tj, TJ, ', ") and ToUnicode
 * character maps (embedded TrueType/CID fonts). Enough for PDFs made by word processors, browsers and printers;
 * scanned pages (images) have no text and are reported as such — there is no OCR.
 */
object Pdf {
    private class Obj(val num: Int, val dict: String, val stream: ByteArray?)

    fun text(bytes: ByteArray): String {
        val raw = String(bytes, Charsets.ISO_8859_1)
        if (!raw.startsWith("%PDF")) throw DocumentImportException(DocumentImportException.Reason.CORRUPT, "Not a PDF file.")
        if (Regex("/Encrypt\\s").containsMatchIn(raw)) throw DocumentImportException(DocumentImportException.Reason.UNSUPPORTED, "This PDF is encrypted; save an unprotected copy and import that.")
        val objs = objects(raw, bytes)
        if (objs.isEmpty()) throw DocumentImportException(DocumentImportException.Reason.CORRUPT, "The PDF is damaged (no readable objects).")
        val byNum = objs.associateBy { it.num }
        // Fonts with ToUnicode maps, and the resource names that point at them.
        val cmaps = HashMap<Int, CMap>()
        objs.forEach { o -> Regex("/ToUnicode\\s+(\\d+)\\s+0\\s+R").find(o.dict)?.let { m ->
            byNum[m.groupValues[1].toInt()]?.stream?.let { s -> cmaps[o.num] = CMap.parse(String(s, Charsets.ISO_8859_1)) } } }
        val fontNames = HashMap<String, Int>()
        objs.map { it.dict }.forEach { d -> Regex("/Font\\s*<<(.*?)>>", RegexOption.DOT_MATCHES_ALL).findAll(d).forEach { f ->
            Regex("/([\\w.+-]+)\\s+(\\d+)\\s+0\\s+R").findAll(f.groupValues[1]).forEach { fontNames[it.groupValues[1]] = it.groupValues[2].toInt() } } }
        Regex("/Font\\s+(\\d+)\\s+0\\s+R").findAll(objs.joinToString(" ") { it.dict }).forEach { r ->
            byNum[r.groupValues[1].toInt()]?.dict?.let { d -> Regex("/([\\w.+-]+)\\s+(\\d+)\\s+0\\s+R").findAll(d).forEach { fontNames[it.groupValues[1]] = it.groupValues[2].toInt() } } }
        // Content streams in page order where possible.
        val contentNums = LinkedHashSet<Int>()
        objs.filter { Regex("/Type\\s*/Page[^s]").containsMatchIn(it.dict) }.forEach { p ->
            Regex("/Contents\\s*(?:\\[(.*?)]|(\\d+)\\s+0\\s+R)", RegexOption.DOT_MATCHES_ALL).find(p.dict)?.let { m ->
                if (m.groupValues[2].isNotEmpty()) contentNums += m.groupValues[2].toInt()
                else Regex("(\\d+)\\s+0\\s+R").findAll(m.groupValues[1]).forEach { contentNums += it.groupValues[1].toInt() } } }
        // Page contents; only when no page tree was found, any plain stream that looks like page content.
        val pageStreams = contentNums.mapNotNull { byNum[it]?.stream }
        val streams = pageStreams.ifEmpty { objs.filter { o -> o.stream != null && !Regex("/Subtype|/Type|/Length1|/Length2|/ObjStm").containsMatchIn(o.dict) &&
            String(o.stream, Charsets.ISO_8859_1).let { it.contains("BT") && (it.contains("Tj") || it.contains("TJ")) } }.mapNotNull { it.stream } }
        val sb = StringBuilder()
        for (s in streams) { val c = String(s, Charsets.ISO_8859_1); if (c.contains("BT")) sb.append(showText(c, fontNames, cmaps)).append('\n') }
        return sb.toString().replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun objects(raw: String, bytes: ByteArray): List<Obj> {
        val out = mutableListOf<Obj>()
        val re = Regex("(\\d+)\\s+(\\d+)\\s+obj\\b")
        var m = re.find(raw)
        while (m != null) {
            val num = m.groupValues[1].toInt()
            val bodyStart = m.range.last + 1
            val end = raw.indexOf("endobj", bodyStart).takeIf { it >= 0 } ?: raw.length
            val body = raw.substring(bodyStart, end)
            val si = Regex("stream\\r?\\n").find(body)
            val dict = if (si != null) body.substring(0, si.range.first) else body
            var stream: ByteArray? = null
            if (si != null) {
                val sStart = bodyStart + si.range.last + 1
                val len = Regex("/Length\\s+(\\d+)(?!\\s+\\d+\\s+R)").find(dict)?.groupValues?.get(1)?.toIntOrNull()
                val sEnd = run {
                    if (len != null && sStart + len <= bytes.size) { val after = raw.indexOf("endstream", sStart + len); if (after in (sStart + len)..(sStart + len + 4)) return@run sStart + len }
                    val e = raw.indexOf("endstream", sStart)
                    if (e < 0) sStart else { var x = e; while (x > sStart && (raw[x - 1] == '\n' || raw[x - 1] == '\r')) x--; x }
                }
                val data = bytes.copyOfRange(sStart, sEnd.coerceAtLeast(sStart))
                stream = when {
                    dict.contains("/FlateDecode") -> inflate(data)
                    Regex("/Filter").containsMatchIn(dict) -> null // images (DCT, JPX…), not text
                    else -> data
                }
            }
            out += Obj(num, dict, stream)
            // An object stream (PDF 1.5+) holds further objects, such as pages and fonts, compressed together.
            if (stream != null && Regex("/Type\\s*/ObjStm").containsMatchIn(dict)) out += objStm(stream, dict)
            m = re.find(raw, end)
        }
        return out
    }

    private fun objStm(stream: ByteArray, dict: String): List<Obj> = runCatching {
        val s = String(stream, Charsets.ISO_8859_1)
        val first = Regex("/First\\s+(\\d+)").find(dict)!!.groupValues[1].toInt()
        val nums = s.substring(0, first).trim().split(Regex("\\s+")).map { it.toInt() }
        val pairs = nums.chunked(2).filter { it.size == 2 }
        pairs.mapIndexed { k, (num, off) ->
            val end = if (k + 1 < pairs.size) first + pairs[k + 1][1] else s.length
            Obj(num, s.substring(first + off, end.coerceAtMost(s.length)), null)
        }
    }.getOrDefault(emptyList())

    private fun inflate(data: ByteArray): ByteArray? {
        for (nowrap in listOf(false, true)) {
            val inf = Inflater(nowrap)
            try {
                inf.setInput(data); val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384)
                while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0) { if (inf.needsInput() || inf.needsDictionary()) break }; out.write(buf, 0, n) }
                if (out.size() > 0) return out.toByteArray()
            } catch (_: Exception) {} finally { inf.end() }
        }
        return null
    }

    private class CMap(val map: Map<Int, String>, val twoByte: Boolean) {
        fun decode(b: ByteArray): String {
            val sb = StringBuilder(); var i = 0
            while (i < b.size) {
                if (twoByte && i + 1 < b.size) { val c = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff); sb.append(map[c] ?: ""); i += 2 }
                else { val c = b[i].toInt() and 0xff; sb.append(map[c] ?: c.toChar().toString()); i++ }
            }
            return sb.toString()
        }
        companion object {
            private fun hex(s: String) = s.trim().removePrefix("<").removeSuffix(">").toInt(16)
            private fun uni(s: String): String { val h = s.trim().removePrefix("<").removeSuffix(">")
                val chars = h.chunked(4).mapNotNull { it.toIntOrNull(16)?.toChar() }; return String(chars.toCharArray()) }
            fun parse(s: String): CMap {
                val map = HashMap<Int, String>()
                val two = Regex("begincodespacerange\\s*<([0-9A-Fa-f]+)>").find(s)?.groupValues?.get(1)?.length?.let { it >= 4 } ?: true
                Regex("beginbfchar(.*?)endbfchar", RegexOption.DOT_MATCHES_ALL).findAll(s).forEach { blk ->
                    Regex("(<[0-9A-Fa-f]+>)\\s*(<[0-9A-Fa-f]+>)").findAll(blk.groupValues[1]).forEach { map[hex(it.groupValues[1])] = uni(it.groupValues[2]) } }
                Regex("beginbfrange(.*?)endbfrange", RegexOption.DOT_MATCHES_ALL).findAll(s).forEach { blk ->
                    Regex("(<[0-9A-Fa-f]+>)\\s*(<[0-9A-Fa-f]+>)\\s*(<[0-9A-Fa-f]+>|\\[[^]]*])").findAll(blk.groupValues[1]).forEach { r ->
                        val a = hex(r.groupValues[1]); val b = hex(r.groupValues[2]); val dst = r.groupValues[3]
                        if (dst.startsWith("[")) Regex("<[0-9A-Fa-f]+>").findAll(dst).forEachIndexed { k, u -> map[a + k] = uni(u.value) }
                        else { val base = uni(dst); if (base.isNotEmpty() && b - a < 65536) for (c in a..b) map[c] = base.dropLast(1) + (base.last() + (c - a)) }
                    } }
                return CMap(map, two)
            }
        }
    }

    /** Runs the text operators of one content stream. */
    private fun showText(c: String, fontNames: Map<String, Int>, cmaps: Map<Int, CMap>): String {
        val sb = StringBuilder()
        var cmap: CMap? = null
        val operands = mutableListOf<Any>()
        var i = 0
        fun emit(b: ByteArray) { sb.append(cmap?.decode(b) ?: winAnsi(b)) }
        while (i < c.length) {
            val ch = c[i]
            when {
                ch == '(' -> { val (b, j) = literal(c, i); operands += b; i = j; continue }
                ch == '<' && i + 1 < c.length && c[i + 1] == '<' -> { i += 2; continue }
                ch == '>' && i + 1 < c.length && c[i + 1] == '>' -> { i += 2; continue }
                ch == '<' -> { val j = c.indexOf('>', i); val h = c.substring(i + 1, if (j < 0) c.length else j).filter { !it.isWhitespace() }
                    operands += (if (h.length % 2 == 1) h + "0" else h).chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray(); i = if (j < 0) c.length else j + 1; continue }
                ch == '[' -> { operands += "["; i++; continue }
                ch == ']' -> { val arr = mutableListOf<Any>(); while (operands.isNotEmpty() && operands.last() != "[") arr.add(0, operands.removeAt(operands.size - 1))
                    if (operands.isNotEmpty()) operands.removeAt(operands.size - 1); operands.add(arr); i++; continue }
                ch == '/' -> { var j = i + 1; while (j < c.length && !c[j].isWhitespace() && c[j] !in "/[]()<>{}") j++; operands += c.substring(i, j); i = j; continue }
                ch == '%' -> { while (i < c.length && c[i] != '\n' && c[i] != '\r') i++; continue }
                ch.isWhitespace() -> { i++; continue }
            }
            var j = i; while (j < c.length && !c[j].isWhitespace() && c[j] !in "/[]()<>{}%") j++
            if (j == i) { i++; continue }
            val tok = c.substring(i, j); i = j
            val num = tok.toDoubleOrNull()
            if (num != null) { operands += num; continue }
            when (tok) {
                "Tf" -> { val name = operands.filterIsInstance<String>().lastOrNull { it.startsWith("/") }?.removePrefix("/")
                    cmap = name?.let { fontNames[it] }?.let { cmaps[it] } }
                "Tj", "'", "\"" -> { if (tok != "Tj") sb.append('\n'); (operands.lastOrNull { it is ByteArray } as? ByteArray)?.let(::emit) }
                "TJ" -> (operands.lastOrNull { it is List<*> } as? List<*>)?.forEach { e -> when (e) { is ByteArray -> emit(e); is Double -> if (e < -200) sb.append(' ') } }
                "T*" -> sb.append('\n')
                "Td", "TD" -> { val ty = operands.filterIsInstance<Double>().lastOrNull() ?: 0.0
                    val tx = operands.filterIsInstance<Double>().let { if (it.size >= 2) it[it.size - 2] else 0.0 }
                    if (ty != 0.0) sb.append('\n') else if (tx > 0 && sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ') }
                "Tm" -> if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                "ET" -> if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
            }
            operands.clear()
        }
        return sb.toString()
    }

    private fun literal(c: String, start: Int): Pair<ByteArray, Int> {
        val out = java.io.ByteArrayOutputStream(); var depth = 0; var i = start
        while (i < c.length) {
            val ch = c[i]
            when {
                ch == '\\' && i + 1 < c.length -> {
                    val n = c[i + 1]
                    when (n) {
                        'n' -> { out.write('\n'.code); i += 2 }; 'r' -> { out.write('\r'.code); i += 2 }; 't' -> { out.write('\t'.code); i += 2 }
                        'b' -> { out.write(8); i += 2 }; 'f' -> { out.write(12); i += 2 }
                        in '0'..'7' -> { var j = i + 1; var v = 0; while (j < c.length && j < i + 4 && c[j] in '0'..'7') { v = v * 8 + (c[j] - '0'); j++ }; out.write(v and 0xff); i = j }
                        '\r', '\n' -> { i += 2; if (n == '\r' && i < c.length && c[i] == '\n') i++ }
                        else -> { out.write(n.code and 0xff); i += 2 }
                    }
                    continue
                }
                ch == '(' -> { depth++; if (depth > 1) out.write(ch.code) }
                ch == ')' -> { depth--; if (depth == 0) return out.toByteArray() to i + 1; out.write(ch.code) }
                else -> out.write(ch.code and 0xff)
            }
            i++
        }
        return out.toByteArray() to i
    }

    private val WIN = mapOf(0x91 to '‘', 0x92 to '’', 0x93 to '“', 0x94 to '”', 0x96 to '–', 0x97 to '—', 0x85 to '…', 0x80 to '€', 0x95 to '•')
    private fun winAnsi(b: ByteArray) = String(CharArray(b.size) { k -> val v = b[k].toInt() and 0xff; WIN[v] ?: v.toChar() })
}

/**
 * The document archive: managed local copies, extracted text split into passages, and a BM25 index over them (the
 * same text analysis as memories). Documents are only searched when relevant; nothing is loaded into memory wholesale.
 */
class DocumentRepository(private val store: CluStore, private val dir: () -> File?) {
    private val db = store.db

    fun list(): List<ArchiveDocument> = db.query("SELECT json FROM $TABLE WHERE deleted = 0 ORDER BY name COLLATE NOCASE") { CluJson.decodeFromString(ArchiveDocument.serializer(), it.str(0)) }
    fun get(id: String): ArchiveDocument? = db.query("SELECT json FROM $TABLE WHERE id = ? AND deleted = 0", listOf(id)) { CluJson.decodeFromString(ArchiveDocument.serializer(), it.str(0)) }.firstOrNull()
    fun bySha(sha: String): ArchiveDocument? = db.query("SELECT json FROM $TABLE WHERE sha256 = ? AND deleted = 0", listOf(sha)) { CluJson.decodeFromString(ArchiveDocument.serializer(), it.str(0)) }.firstOrNull()
    fun text(id: String): String = db.query("SELECT text FROM document_chunks WHERE doc_id = ? ORDER BY seq", listOf(id)) { it.str(0) }.joinToString("\n\n")

    /**
     * Imports a file: checks the type, extracts and indexes its text, keeps a managed copy. Unsupported, damaged,
     * empty, oversized, duplicate or text-less files are refused with a clear reason ([allowNoText] keeps a file with
     * no searchable text, such as a photo or a scanned PDF, as an attachment).
     */
    fun import(name: String, bytes: ByteArray, description: String = "", date: HistoricalDate = HistoricalDate.UNKNOWN, tags: List<String> = emptyList(),
               originalRef: String = "", allowNoText: Boolean = false, allowDuplicate: Boolean = false): ArchiveDocument {
        if (bytes.isEmpty()) throw DocumentImportException(DocumentImportException.Reason.EMPTY, "The file is empty.")
        if (bytes.size > TextExtractors.MAX_BYTES) throw DocumentImportException(DocumentImportException.Reason.TOO_LARGE, "The file is larger than 50 MB.")
        val type = TextExtractors.typeOf(name, bytes)
            ?: throw DocumentImportException(DocumentImportException.Reason.UNSUPPORTED, "\"${name.substringAfterLast('.', "this")}\" files aren't supported. Supported: text, Markdown, CSV, HTML, JSON, source code and config files, PDF, DOCX (and images as attachments).")
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        bySha(sha)?.let { if (!allowDuplicate) throw DocumentImportException(DocumentImportException.Reason.DUPLICATE, "This file is already in the archive as \"${it.name}\".", it.id) }
        val text = TextExtractors.extract(type, bytes).trim()
        val usable = text.length >= 2 && TextExtractors.quality(text) >= 0.75
        if (!usable && type != "image" && !allowNoText) throw DocumentImportException(DocumentImportException.Reason.NO_TEXT,
            if (type == "pdf") "No readable text in this PDF (a scanned page or an unusual font encoding). OCR isn't supported; you can keep it as an attachment."
            else "No readable text in this file.")
        val id = newId()
        val stored = dir()?.let { d -> File(d, "archive").apply { mkdirs() }.let { File(it, "$id.${name.substringAfterLast('.', "bin").lowercase()}") }.also { it.writeBytes(bytes) } }
        val doc = ArchiveDocument(id, name, type, bytes.size.toLong(), sha, stored?.absolutePath ?: "", originalRef, if (usable) "indexed" else "no_text",
            if (usable) text.length else 0, description, date, tags, store.clock.now(), store.clock.now())
        try {
            db.transaction {
                writeRow(doc)
                if (usable) index(doc.id, text)
            }
        } catch (e: Exception) { stored?.delete(); throw e }
        store.notifyChanged(TABLE)
        return doc
    }

    fun update(doc: ArchiveDocument): ArchiveDocument { val d = doc.copy(updatedAt = store.clock.now()); db.transaction { writeRow(d) }; store.notifyChanged(TABLE); return d }

    private fun writeRow(doc: ArchiveDocument) {
        val hlc = store.stamp(TABLE, doc.id)
        db.execute("""INSERT INTO $TABLE(id, name, sha256, json, created_at, updated_at, deleted, hlc, origin) VALUES(?,?,?,?,?,?,0,?,?)
            ON CONFLICT(id) DO UPDATE SET name=excluded.name, json=excluded.json, updated_at=excluded.updated_at, deleted=0, hlc=excluded.hlc, origin=excluded.origin""",
            listOf(doc.id, doc.name, doc.sha256, CluJson.encodeToString(ArchiveDocument.serializer(), doc), doc.createdAt, doc.updatedAt, hlc, store.deviceId))
    }

    /** Restores a document from a backup (metadata, text and, when included, the file). */
    fun restore(doc: ArchiveDocument, text: String, file: ByteArray?) {
        if (get(doc.id) != null) return
        val stored = file?.let { b -> dir()?.let { d -> File(File(d, "archive").apply { mkdirs() }, "${doc.id}.${doc.name.substringAfterLast('.', "bin").lowercase()}").also { it.writeBytes(b) } } }
        val d = doc.copy(storedPath = stored?.absolutePath ?: "")
        db.transaction { writeRow(d); if (text.isNotBlank()) index(d.id, text) }
        store.notifyChanged(TABLE)
    }

    fun fileBytes(doc: ArchiveDocument): ByteArray? = doc.storedPath.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.exists() }?.readBytes()

    private fun index(docId: String, text: String) {
        db.execute("DELETE FROM document_terms WHERE doc_id = ?", listOf(docId)); db.execute("DELETE FROM document_chunks WHERE doc_id = ?", listOf(docId))
        chunks(text).forEachIndexed { i, chunk ->
            val cid = "$docId:$i"
            db.execute("INSERT INTO document_chunks(id, doc_id, seq, text) VALUES(?,?,?,?)", listOf(cid, docId, i, chunk))
            TextAnalysis.termFrequencies(chunk).forEach { (t, n) -> db.execute("INSERT INTO document_terms(chunk_id, doc_id, term, tf) VALUES(?,?,?,?)", listOf(cid, docId, t, n)) }
        }
    }

    /** Passages of about [size] characters, split at paragraph and sentence boundaries. */
    fun chunks(text: String, size: Int = 900): List<String> {
        val out = mutableListOf<String>(); val cur = StringBuilder()
        for (para in text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }) {
            val pieces = if (para.length <= size) listOf(para) else para.split(Regex("(?<=[.!?])\\s+")).flatMap { if (it.length > size) it.chunked(size) else listOf(it) }
            for (p in pieces) { if (cur.length + p.length + 1 > size && cur.isNotEmpty()) { out += cur.toString().trim(); cur.clear() }; cur.append(p).append(if (para.length <= size) "\n\n" else " ") }
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    fun delete(id: String) {
        val d = get(id) ?: return
        db.transaction {
            val hlc = store.stamp(TABLE, id)
            db.execute("UPDATE $TABLE SET deleted = 1, updated_at = ?, hlc = ?, origin = ? WHERE id = ?", listOf(store.clock.now(), hlc, store.deviceId, id))
            db.execute("DELETE FROM document_terms WHERE doc_id = ?", listOf(id)); db.execute("DELETE FROM document_chunks WHERE doc_id = ?", listOf(id))
            db.execute("DELETE FROM archive_links WHERE (a_kind = 'document' AND a_id = ?) OR (b_kind = 'document' AND b_id = ?)", listOf(id, id))
        }
        d.storedPath.takeIf { it.isNotEmpty() }?.let { File(it).delete() }
        store.notifyChanged(TABLE)
    }

    /** The best passages for [query] across all documents (BM25 over passages), at most [limit]. */
    fun search(query: String, limit: Int = 5, onlyDoc: String? = null): List<Passage> {
        val q = TextAnalysis.tokens(query).distinct(); if (q.isEmpty()) return emptyList()
        val n = db.queryOne("SELECT COUNT(*) FROM document_chunks") { it.lng(0) } ?: 0L
        if (n == 0L) return emptyList()
        val ph = q.joinToString(",") { "?" }
        val df = db.query("SELECT term, COUNT(DISTINCT chunk_id) FROM document_terms WHERE term IN ($ph) GROUP BY term", q) { it.str(0) to it.lng(1) }.toMap()
        val postings = db.query("SELECT chunk_id, doc_id, term, tf FROM document_terms WHERE term IN ($ph)" + (if (onlyDoc != null) " AND doc_id = ?" else ""), q + listOfNotNull(onlyDoc)) {
            listOf(it.str(0), it.str(1), it.str(2), it.lng(3).toString()) }
        val scores = HashMap<String, Double>(); val docOf = HashMap<String, String>()
        for ((cid, did, term, tf) in postings) {
            val d = (df[term] ?: 1L).toDouble(); val idf = ln(1 + (n - d + 0.5) / (d + 0.5)); val f = tf.toDouble()
            scores[cid] = (scores[cid] ?: 0.0) + idf * (f * 2.2) / (f + 1.2); docOf[cid] = did
        }
        val docs = HashMap<String, ArchiveDocument?>()
        return scores.entries.sortedByDescending { it.value }.take(limit * 2).mapNotNull { (cid, s) ->
            val doc = docs.getOrPut(docOf[cid]!!) { get(docOf[cid]!!) } ?: return@mapNotNull null
            val row = db.queryOne("SELECT text, seq FROM document_chunks WHERE id = ?", listOf(cid)) { it.str(0) to it.lng(1).toInt() } ?: return@mapNotNull null
            Passage(doc, row.first, s, row.second)
        }.take(limit)
    }

    companion object { const val TABLE = "archive_documents" }
}
