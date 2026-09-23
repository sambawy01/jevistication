package dev.loupe.sources.common

import kotlin.io.encoding.Base64

/** Byte-to-text decoders the sources need, in common code: UTF-8, ISO-8859-1, Windows-1252, ASCII. */
object Charsets {
    /** Windows-1252's 0x80–0x9F; the five undefined bytes decode to U+FFFD, as the JDK's decoder does. */
    private val CP1252_HIGH = charArrayOf(
        '€', '�', '‚', 'ƒ', '„', '…', '†', '‡',
        'ˆ', '‰', 'Š', '‹', 'Œ', '�', 'Ž', '�',
        '�', '‘', '’', '“', '”', '•', '–', '—',
        '˜', '™', 'š', '›', 'œ', '�', 'ž', 'Ÿ',
    )

    fun latin1(bytes: ByteArray): String = buildString(bytes.size) { for (b in bytes) append((b.toInt() and 0xFF).toChar()) }

    fun windows1252(bytes: ByteArray): String = buildString(bytes.size) {
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            append(if (c in 0x80..0x9F) CP1252_HIGH[c - 0x80] else c.toChar())
        }
    }

    fun ascii(bytes: ByteArray): String = buildString(bytes.size) {
        for (b in bytes) { val c = b.toInt() and 0xFF; append(if (c < 0x80) c.toChar() else '�') }
    }

    /** Lenient UTF-8 (malformed input becomes U+FFFD). */
    fun utf8(bytes: ByteArray): String = bytes.decodeToString()

    /** Decodes by MIME charset name; null when the charset is not one of the four. */
    fun decode(bytes: ByteArray, charset: String): String? = when (charset.trim().lowercase()) {
        "utf-8", "utf8" -> utf8(bytes)
        "iso-8859-1", "iso8859-1", "latin1", "latin-1", "l1", "iso_8859-1" -> latin1(bytes)
        "windows-1252", "cp1252" -> windows1252(bytes)
        "us-ascii", "ascii" -> ascii(bytes)
        else -> null
    }
}

/**
 * Plain text from bytes: UTF-8 when the bytes are valid UTF-8, otherwise Windows-1252 — as
 * :sources-desktop's `PlainText`.
 */
object PlainText {
    fun decode(bytes: ByteArray): String {
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        return try {
            body.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            Charsets.windows1252(body)
        }
    }

    /** A NUL byte in the first 8 KB means this is not a text file whatever its name says. */
    fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 8192)
        for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
        return false
    }
}

/** Visible text from HTML — the same rules as :sources-desktop's `HtmlText`. */
object HtmlText {
    private val DROP = Regex("""<(script|style|head|noscript)\b[^>]*>.*?</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val BLOCK = Regex("""<\s*(br|/p|/div|/li|/tr|/h[1-6]|/table|p|div|li|tr|h[1-6])\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]+>""")
    private val NUMERIC = Regex("""&#(x[0-9a-fA-F]+|\d+);""")
    private val NAMED = listOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
        "&#39;" to "'", "&apos;" to "'", "&pound;" to "£", "&euro;" to "€", "&copy;" to "©",
        "&ndash;" to "–", "&mdash;" to "—", "&hellip;" to "…",
    )
    private val SPACES = Regex("[ \t ]+")
    private val BLANK_LINES = Regex("""\n\s*\n\s*\n+""")

    fun toText(html: String): String {
        var s = COMMENT.replace(html, " ")
        s = DROP.replace(s, " ")
        s = BLOCK.replace(s, "\n")
        s = TAG.replace(s, " ")
        s = NUMERIC.replace(s) { m ->
            val code = m.groupValues[1]
            val cp = if (code.startsWith("x")) code.drop(1).toIntOrNull(16) else code.toIntOrNull()
            if (cp != null && cp in 0..0x10FFFF) codePointToString(cp) else " "
        }
        for ((entity, replacement) in NAMED) s = s.replace(entity, replacement)
        s = s.lines().joinToString("\n") { SPACES.replace(it, " ").trim() }
        return BLANK_LINES.replace(s, "\n\n").trim()
    }

    internal fun codePointToString(cp: Int): String = if (cp < 0x10000) {
        cp.toChar().toString()
    } else {
        val v = cp - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }
}

/** Links in text or HTML, as :sources-desktop's `Links`. */
object Links {
    private val HREF = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val BARE = Regex("""https?://[^\s"'<>)\]]+""", RegexOption.IGNORE_CASE)

    fun find(vararg texts: String): List<String> {
        val found = LinkedHashSet<String>()
        for (text in texts) {
            HREF.findAll(text).map { it.groupValues[1].trim() }.filter { it.startsWith("http", ignoreCase = true) }.forEach { found += it }
            BARE.findAll(text).map { it.value.trimEnd('.', ',', ';') }.forEach { found += it }
        }
        return found.toList()
    }
}

/** MIME transfer and header encodings. */
object MimeCodecs {
    /** Quoted-printable (RFC 2045): `=XX` escapes and soft line breaks; malformed escapes kept as written. */
    fun quotedPrintable(bytes: ByteArray): ByteArray {
        val out = ByteArray(bytes.size)
        var n = 0
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            if (b == '='.code.toByte()) {
                // Soft line break: "=" then optional trailing whitespace then CRLF or LF.
                var j = i + 1
                while (j < bytes.size && (bytes[j] == ' '.code.toByte() || bytes[j] == '\t'.code.toByte())) j++
                if (j < bytes.size && bytes[j] == '\r'.code.toByte() && j + 1 < bytes.size && bytes[j + 1] == '\n'.code.toByte()) { i = j + 2; continue }
                if (j < bytes.size && bytes[j] == '\n'.code.toByte()) { i = j + 1; continue }
                if (j >= bytes.size) { i = j; continue }
                val hi = if (i + 1 < bytes.size) hex(bytes[i + 1]) else -1
                val lo = if (i + 2 < bytes.size) hex(bytes[i + 2]) else -1
                if (hi >= 0 && lo >= 0) { out[n++] = ((hi shl 4) or lo).toByte(); i += 3; continue }
            }
            out[n++] = b
            i++
        }
        return out.copyOf(n)
    }

    private fun hex(b: Byte): Int = when (val c = b.toInt().toChar()) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> c - 'A' + 10
        in 'a'..'f' -> c - 'a' + 10
        else -> -1
    }

    /** Base64, ignoring line breaks and anything outside the alphabet; a broken tail is dropped. */
    fun base64(bytes: ByteArray): ByteArray {
        val clean = StringBuilder(bytes.size)
        for (b in bytes) {
            val c = b.toInt().toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/') clean.append(c)
        }
        val usable = clean.length - clean.length % 4
        val tail = clean.length % 4
        val padded = clean.substring(0, usable) + when (tail) {
            2 -> clean.substring(usable) + "=="
            3 -> clean.substring(usable) + "="
            else -> ""
        }
        return Base64.decode(padded)
    }

    private val ENCODED_WORD = Regex("""=\?([^?]+)\?([bBqQ])\?([^?]*)\?=""")
    private val BETWEEN_WORDS = Regex("""(=\?[^?]+\?[bBqQ]\?[^?]*\?=)\s+(?==\?[^?]+\?[bBqQ]\?[^?]*\?=)""")

    /** RFC 2047 encoded words in a header value; whitespace between two encoded words is dropped. */
    fun decodeHeader(value: String): String {
        if (!value.contains("=?")) return value
        val joined = BETWEEN_WORDS.replace(value, "$1")
        return ENCODED_WORD.replace(joined) { m ->
            val charset = m.groupValues[1].substringBefore('*')
            val raw = m.groupValues[3]
            val bytes = if (m.groupValues[2].equals("B", ignoreCase = true)) {
                base64(raw.encodeToByteArray())
            } else {
                quotedPrintable(raw.replace('_', ' ').encodeToByteArray())
            }
            Charsets.decode(bytes, charset) ?: m.value
        }
    }
}
