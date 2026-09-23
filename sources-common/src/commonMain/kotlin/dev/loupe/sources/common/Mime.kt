package dev.loupe.sources.common

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * A small MIME reader in common code — enough for personal mail exports, not a general MIME
 * library: headers (folded, RFC 2047 encoded words), `multipart/` nesting, `quoted-printable` and
 * `base64`, charsets UTF-8, ISO-8859-1, Windows-1252 and US-ASCII. The JVM keeps Apache James
 * mime4j (:sources-desktop); a parity test holds this one to the same text and facts on the sample.
 */
object MimeParser {

    /** One MIME entity: its headers in order (names lower-cased) and its raw body bytes. */
    class Entity(val headers: List<Pair<String, String>>, val body: ByteArray) {
        fun header(name: String): String? = headers.firstOrNull { it.first == name }?.second

        val contentType: HeaderValue by lazy { HeaderValue.parse(header("content-type") ?: "text/plain") }
        val disposition: HeaderValue? by lazy { header("content-disposition")?.let(HeaderValue::parse) }

        /** Lower-cased `type/subtype`; `text/plain` when missing or malformed, as RFC 2045 says. */
        val mimeType: String get() = contentType.value.lowercase().takeIf { it.contains('/') } ?: "text/plain"

        val filename: String? get() = disposition?.params?.get("filename")?.takeIf { it.isNotEmpty() }
    }

    /** A structured header value: `value; key=val; key="quoted"`. Parameter names lower-cased. */
    class HeaderValue(val value: String, val params: Map<String, String>) {
        companion object {
            fun parse(raw: String): HeaderValue {
                val parts = splitOutsideQuotes(raw, ';')
                val params = LinkedHashMap<String, String>()
                for (p in parts.drop(1)) {
                    val eq = p.indexOf('=')
                    if (eq <= 0) continue
                    val key = p.substring(0, eq).trim().lowercase()
                    var v = p.substring(eq + 1).trim()
                    if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) v = unquote(v)
                    if (key !in params) params[key] = MimeCodecs.decodeHeader(v)
                }
                return HeaderValue(parts.firstOrNull()?.trim().orEmpty(), params)
            }
        }
    }

    fun parseEntity(bytes: ByteArray): Entity {
        val (headerEnd, bodyStart) = headerBoundary(bytes)
        val headerText = Charsets.utf8(bytes.copyOfRange(0, headerEnd))
        val headers = mutableListOf<Pair<String, String>>()
        var current: StringBuilder? = null
        var name: String? = null
        fun flush() {
            val n = name ?: return
            headers += n to current.toString().trim()
            name = null
        }
        for (line in headerText.split('\n').map { it.removeSuffix("\r") }) {
            if (line.isEmpty()) continue
            if ((line[0] == ' ' || line[0] == '\t') && name != null) {
                current!!.append(' ').append(line.trim())
                continue
            }
            flush()
            val colon = line.indexOf(':')
            if (colon <= 0) continue // permissive: a malformed header line is dropped, not fatal
            name = line.substring(0, colon).trim().lowercase()
            current = StringBuilder(line.substring(colon + 1))
        }
        flush()
        return Entity(headers, bytes.copyOfRange(bodyStart, bytes.size))
    }

    /** Index where the header block ends and where the body starts (after the blank line). */
    private fun headerBoundary(b: ByteArray): Pair<Int, Int> {
        var i = 0
        var lineStart = 0
        while (i < b.size) {
            if (b[i] == '\n'.code.toByte()) {
                val lineLen = i - lineStart
                val blank = lineLen == 0 || (lineLen == 1 && b[lineStart] == '\r'.code.toByte())
                if (blank) return lineStart to i + 1
                lineStart = i + 1
            }
            i++
        }
        return b.size to b.size
    }

    /** The decoded bytes of a leaf entity's body (transfer encoding undone). */
    fun decodedBody(entity: Entity): ByteArray = when (entity.header("content-transfer-encoding")?.trim()?.lowercase()) {
        "quoted-printable" -> MimeCodecs.quotedPrintable(entity.body)
        "base64" -> MimeCodecs.base64(entity.body)
        else -> entity.body
    }

    /** A text body as a string, in its declared charset (US-ASCII when none is declared). */
    fun text(entity: Entity): String {
        val bytes = decodedBody(entity)
        val charset = entity.contentType.params["charset"] ?: "us-ascii"
        return Charsets.decode(bytes, charset) ?: PlainText.decode(bytes)
    }

    /** The body parts of a multipart entity: the bytes between delimiter lines, preamble and epilogue dropped. */
    fun parts(entity: Entity): List<Entity> {
        val boundary = entity.contentType.params["boundary"] ?: return emptyList()
        val delimiter = "--$boundary".encodeToByteArray()
        val b = entity.body
        val out = mutableListOf<Entity>()
        var partStart = -1
        var lineStart = 0
        while (lineStart <= b.size) {
            var lineEnd = lineStart
            while (lineEnd < b.size && b[lineEnd] != '\n'.code.toByte()) lineEnd++
            val next = lineEnd + 1
            if (startsWithAt(b, lineStart, delimiter)) {
                val rest = lineStart + delimiter.size
                val closing = rest + 1 < b.size + 1 && rest + 1 <= lineEnd && b[rest] == '-'.code.toByte() && b[rest + 1] == '-'.code.toByte()
                if (partStart >= 0) {
                    // The line break before a delimiter belongs to the delimiter (RFC 2046).
                    var end = lineStart
                    if (end > partStart && b[end - 1] == '\n'.code.toByte()) end--
                    if (end > partStart && b[end - 1] == '\r'.code.toByte()) end--
                    out += parseEntity(b.copyOfRange(partStart, maxOf(partStart, end)))
                }
                if (closing) return out
                partStart = minOf(next, b.size)
            }
            if (lineEnd >= b.size) break
            lineStart = next
        }
        if (partStart in 0 until b.size) out += parseEntity(b.copyOfRange(partStart, b.size)) // unterminated: keep what is there
        return out
    }

    private fun startsWithAt(b: ByteArray, at: Int, prefix: ByteArray): Boolean {
        if (at + prefix.size > b.size) return false
        for (i in prefix.indices) if (b[at + i] != prefix[i]) return false
        return true
    }

    internal fun splitOutsideQuotes(s: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var angle = 0
        var paren = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && quoted && i + 1 < s.length -> { cur.append(c).append(s[i + 1]); i += 2; continue }
                c == '"' -> quoted = !quoted
                !quoted && c == '<' -> angle++
                !quoted && c == '>' -> angle = maxOf(0, angle - 1)
                !quoted && c == '(' -> paren++
                !quoted && c == ')' -> paren = maxOf(0, paren - 1)
                !quoted && angle == 0 && paren == 0 && c == sep -> { out += cur.toString(); cur.clear(); i++; continue }
            }
            cur.append(c)
            i++
        }
        out += cur.toString()
        return out
    }

    internal fun unquote(v: String): String {
        val inner = v.substring(1, v.length - 1)
        val sb = StringBuilder()
        var i = 0
        while (i < inner.length) {
            if (inner[i] == '\\' && i + 1 < inner.length) { sb.append(inner[i + 1]); i += 2 } else { sb.append(inner[i]); i++ }
        }
        return sb.toString()
    }

    /** A mailbox: display name and address. */
    data class Mailbox(val name: String?, val address: String)

    private val COMMENT = Regex("""\([^()]*\)""")

    /** An address list (`To`, `From`), groups flattened. Entries with no `@` are dropped. */
    fun mailboxes(raw: String?): List<Mailbox> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = mutableListOf<Mailbox>()
        for (piece in splitOutsideQuotes(raw.replace(';', ','), ',')) {
            var p = piece.trim()
            // A group: "Friends: a@x, b@y;" — the name before the colon is not an address.
            val colon = p.indexOf(':')
            if (colon > 0 && !p.substring(0, colon).contains('<') && !p.substring(0, colon).contains('"') && !p.substring(0, colon).contains('@')) {
                p = p.substring(colon + 1).trim()
            }
            if (p.isEmpty()) continue
            val lt = p.lastIndexOf('<')
            val gt = p.lastIndexOf('>')
            val mailbox = if (lt >= 0 && gt > lt) {
                var name = p.substring(0, lt).trim()
                if (name.length >= 2 && name.startsWith('"') && name.endsWith('"')) name = unquote(name)
                name = MimeCodecs.decodeHeader(name).trim()
                val address = p.substring(lt + 1, gt).trim().substringAfterLast(':') // drop an obsolete route
                Mailbox(name.takeIf { it.isNotEmpty() }, address)
            } else {
                val comment = COMMENT.find(p)?.value?.removeSurrounding("(", ")")?.trim()
                Mailbox(comment?.takeIf { it.isNotEmpty() }, COMMENT.replace(p, "").trim())
            }
            if (mailbox.address.contains('@')) out += mailbox
        }
        return out
    }

    private val ZONES = mapOf(
        "UT" to 0, "UTC" to 0, "GMT" to 0, "Z" to 0,
        "EST" to -5 * 60, "EDT" to -4 * 60, "CST" to -6 * 60, "CDT" to -5 * 60,
        "MST" to -7 * 60, "MDT" to -6 * 60, "PST" to -8 * 60, "PDT" to -7 * 60,
    )
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val DATE = Regex("""(\d{1,2})\s+([A-Za-z]{3})[a-z]*\s+(\d{2,4})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*([+-]\d{4}|[A-Za-z]{1,5})?""")

    /** An RFC 5322 date (lenient about the weekday, seconds and the zone), or null. */
    fun parseDate(raw: String?): Instant? {
        if (raw == null) return null
        val m = DATE.find(COMMENT.replace(raw, " ")) ?: return null
        val (d, mon, y, hh, mm) = m.destructured
        val month = MONTHS.indexOf(mon.lowercase()) + 1
        if (month == 0) return null
        var year = y.toInt()
        if (y.length == 2) year += if (year < 50) 2000 else 1900 else if (y.length == 3) year += 1900
        val ss = m.groupValues[6].toIntOrNull() ?: 0
        val zone = m.groupValues[7]
        val offsetMinutes = when {
            zone.isEmpty() -> 0
            zone[0] == '+' || zone[0] == '-' -> {
                val sign = if (zone[0] == '-') -1 else 1
                sign * (zone.substring(1, 3).toInt() * 60 + zone.substring(3, 5).toInt())
            }
            else -> ZONES[zone.uppercase()] ?: 0
        }
        return try {
            LocalDateTime(year, month, d.toInt(), hh.toInt(), mm.toInt(), ss)
                .toInstant(UtcOffset(hours = offsetMinutes / 60, minutes = offsetMinutes % 60))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Reads an email into its facts and body, the way :sources-desktop's mime4j path does. */
    fun parseEmail(bytes: ByteArray, zone: TimeZone): ParsedEmail {
        val message = parseEntity(bytes)
        val plain = mutableListOf<String>()
        val html = mutableListOf<String>()
        val attachments = mutableListOf<String>()
        collect(message, plain, html, attachments, 0)
        val htmlRaw = html.joinToString("\n")
        val body = when {
            plain.isNotEmpty() -> plain.joinToString("\n\n")
            html.isNotEmpty() -> HtmlText.toText(htmlRaw)
            else -> ""
        }
        val from = mailboxes(message.header("from")).firstOrNull()
        val facts = EmailFacts(
            fromName = from?.name?.takeIf { it.isNotBlank() },
            fromAddress = from?.address?.lowercase(),
            to = mailboxes(message.header("to")).map { it.address },
            subject = message.header("subject")?.let(MimeCodecs::decodeHeader)?.trim()?.takeIf { it.isNotEmpty() },
            date = parseDate(message.header("date"))?.toLocalDateTime(zone)?.date,
            links = Links.find(body, htmlRaw),
            attachmentNames = attachments,
        )
        return ParsedEmail(facts, body.trim())
    }

    private fun collect(entity: Entity, plain: MutableList<String>, html: MutableList<String>, attachments: MutableList<String>, depth: Int) {
        val type = entity.mimeType
        if (type.startsWith("multipart/") && depth < 32) {
            parts(entity).forEach { collect(it, plain, html, attachments, depth + 1) }
            return
        }
        val isAttachment = entity.disposition?.value.equals("attachment", ignoreCase = true) ||
            (entity.filename != null && !type.startsWith("text/"))
        if (isAttachment) {
            attachments += entity.filename ?: type
            return
        }
        if (type.startsWith("text/")) {
            when (type) {
                "text/plain" -> plain += text(entity)
                "text/html" -> html += text(entity)
            }
        }
    }
}

/** An email's facts and its text body. */
data class ParsedEmail(val facts: EmailFacts, val body: String) {
    /** What the model reads: the identifying headers, a blank line, then the body verbatim. */
    fun modelText(): String = buildString {
        val from = listOfNotNull(facts.fromName, facts.fromAddress?.let { "<$it>" }).joinToString(" ")
        if (from.isNotBlank()) append("From: ").append(from).append('\n')
        if (facts.to.isNotEmpty()) append("To: ").append(facts.to.joinToString(", ")).append('\n')
        facts.date?.let { append("Date: ").append(it).append('\n') }
        facts.subject?.let { append("Subject: ").append(it).append('\n') }
        if (facts.attachmentNames.isNotEmpty()) append("Attachments: ").append(facts.attachmentNames.joinToString(", ")).append('\n')
        append('\n').append(body)
    }.trim()
}

/**
 * Splits an mbox into its messages' bytes on `From ` separator lines — the same rule as the
 * desktop's mime4j `MboxIterator` with `^From \S+.*\d{4}$`: permissive about the sender, strict
 * about the trailing year, so an unescaped body line "From here on…" does not split a message
 * unless it ends in four digits (the Trap in docs/BUILD.md). A message runs from the line after its
 * separator to the start of the next separator.
 */
object Mbox {
    private val FROM_LINE = Regex("""^From \S+.*\d{4}$""")

    fun split(bytes: ByteArray): List<ByteArray> {
        val text = bytes.decodeToString()
        val out = mutableListOf<ByteArray>()
        var start = -1
        var lineStart = 0
        while (lineStart < text.length) {
            var lineEnd = text.indexOf('\n', lineStart)
            if (lineEnd < 0) lineEnd = text.length
            val line = text.substring(lineStart, lineEnd).removeSuffix("\r")
            if (FROM_LINE.matches(line)) {
                if (start >= 0) out += text.substring(start, lineStart).encodeToByteArray()
                start = minOf(lineEnd + 1, text.length)
            }
            lineStart = lineEnd + 1
        }
        if (start >= 0) out += text.substring(start).encodeToByteArray()
        return out
    }
}
