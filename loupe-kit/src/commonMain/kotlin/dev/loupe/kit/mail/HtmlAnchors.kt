package dev.loupe.kit.mail

import dev.loupe.engine.PortableText
import dev.loupe.kit.site.Hosts
import dev.loupe.sources.common.HtmlText

/**
 * The links of an HTML mail as a browser reads them (fix loop 5): `<a>` start tags tokenised by the
 * WHATWG rules (attribute names whole, so `data-href` is not `href`; quoted values may hold `>`; the
 * first `href` wins), the value's character references decoded ([decodeAttribute]), and an anchor
 * left open ending at the next `<a>` as the HTML tree builder closes it.
 */
internal object HtmlAnchors {
    /** Visible text of an anchor that is never closed: at most this much of what follows. */
    private const val OPEN_TEXT_MAX = 4000

    fun anchors(html: String, max: Int): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var i = 0
        // the next `</a` at or after the current anchor, found once and reused while it is ahead: a
        // page of anchors that never close stays linear (-1: there is none left)
        var closeAt = -2
        while (out.size < max) {
            val start = findA(html, i)
            if (start < 0) break
            val (attrs, contentStart) = tagAttributes(html, start + 2)
            val next = findA(html, contentStart).let { if (it < 0) html.length else it }
            if (closeAt != -1 && (closeAt == -2 || closeAt < contentStart)) closeAt = findClose(html, contentStart)
            val close = if (closeAt in contentStart until next) closeAt else -1
            val contentEnd = if (close >= 0) close else minOf(next, contentStart + OPEN_TEXT_MAX)
            attrs["href"]?.let { raw ->
                val href = decodeAttribute(raw)
                if (Hosts.stripC0(href).isNotEmpty()) {
                    out += href to PortableText.collapseSpaces(HtmlText.toText(html.substring(contentStart, contentEnd)))
                }
            }
            i = if (close >= 0) close else next
            if (i <= start) i = start + 2
        }
        return out
    }

    private fun space(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

    /** Index of the next `<a` start tag (`<a` then a space, `/` or `>`), or -1. */
    private fun findA(s: String, from: Int): Int {
        var i = s.indexOf('<', from)
        while (i >= 0) {
            if (i + 2 < s.length && (s[i + 1] == 'a' || s[i + 1] == 'A') && (space(s[i + 2]) || s[i + 2] == '/' || s[i + 2] == '>')) return i
            i = s.indexOf('<', i + 1)
        }
        return -1
    }

    /** Index of the next `</a` end tag from [from], or -1. */
    private fun findClose(s: String, from: Int): Int {
        var i = s.indexOf("</", from)
        while (i >= 0) {
            if (i + 3 <= s.length && (s[i + 2] == 'a' || s[i + 2] == 'A') && (i + 3 == s.length || space(s[i + 3]) || s[i + 3] == '>' || s[i + 3] == '/')) return i
            i = s.indexOf("</", i + 2)
        }
        return -1
    }

    /** The start tag's attributes from [pos] (after `<a`), first occurrence of each name, and where its content starts. */
    private fun tagAttributes(s: String, pos: Int): Pair<Map<String, String>, Int> {
        val attrs = LinkedHashMap<String, String>()
        var j = pos
        val n = s.length
        while (true) {
            while (j < n && (space(s[j]) || s[j] == '/')) j++
            if (j >= n) return attrs to n
            if (s[j] == '>') return attrs to j + 1
            val ns = j
            if (s[j] == '=') j++
            while (j < n && !space(s[j]) && s[j] != '/' && s[j] != '>' && s[j] != '=') j++
            val name = s.substring(ns, j).lowercase()
            while (j < n && space(s[j])) j++
            var value = ""
            if (j < n && s[j] == '=') {
                j++
                while (j < n && space(s[j])) j++
                if (j < n && (s[j] == '"' || s[j] == '\'')) {
                    val q = s[j]
                    val e = s.indexOf(q, j + 1).let { if (it < 0) n else it }
                    value = s.substring(j + 1, e)
                    j = minOf(n, e + 1)
                } else {
                    val vs = j
                    while (j < n && !space(s[j]) && s[j] != '>') j++
                    value = s.substring(vs, j)
                }
            }
            if (name !in attrs) attrs[name] = value
        }
    }

    private val NAMED: Map<String, String> by lazy {
        val m = HashMap<String, String>(2400)
        for (chunk in HtmlEntitiesData.TABLE) for (e in chunk.split(' ')) {
            if (e.isEmpty()) continue
            val eq = e.indexOf('=')
            m[e.substring(0, eq)] = buildString { for (h in e.substring(eq + 1).split(',')) appendCodePoint(this, h.toInt(16)) }
        }
        m
    }
    private val LEGACY: Set<String> by lazy { HtmlEntitiesData.LEGACY.split(' ').toSet() }

    /** Windows-1252 for the C1 range, as HTML maps `&#128;`–`&#159;`. */
    private val C1 = intArrayOf(
        0x20AC, 0x81, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x8D, 0x017D, 0x8F,
        0x90, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x9D, 0x017E, 0x0178,
    )

    private fun alnum(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    private fun appendCodePoint(sb: StringBuilder, cp: Int) {
        if (cp < 0x10000) sb.append(cp.toChar()) else {
            val v = cp - 0x10000
            sb.append((0xD800 + (v shr 10)).toChar()).append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }

    /**
     * An attribute value with its character references decoded as the WHATWG tokenizer decodes them:
     * decimal and hex numeric references (the `;` optional; 0, surrogates and values past U+10FFFF
     * become U+FFFD, 128–159 Windows-1252), every named reference of the HTML table with its `;`,
     * and the legacy names without it unless a letter, digit or `=` follows (in an attribute).
     */
    fun decodeAttribute(v: String): String {
        if ('&' !in v && '\u0000' !in v) return v
        val sb = StringBuilder(v.length)
        var i = 0
        val n = v.length
        while (i < n) {
            val c = v[i]
            if (c == '\u0000') { sb.append('�'); i++; continue }
            if (c != '&') { sb.append(c); i++; continue }
            if (i + 1 < n && v[i + 1] == '#') {
                var j = i + 2
                val hex = j < n && (v[j] == 'x' || v[j] == 'X')
                if (hex) j++
                val digitsStart = j
                var cp = 0L
                while (j < n) {
                    val d = when (val ch = v[j]) {
                        in '0'..'9' -> ch - '0'
                        in 'a'..'f' -> if (hex) ch - 'a' + 10 else -1
                        in 'A'..'F' -> if (hex) ch - 'A' + 10 else -1
                        else -> -1
                    }
                    if (d < 0) break
                    cp = minOf(cp * (if (hex) 16 else 10) + d, 0x110000L)
                    j++
                }
                if (j == digitsStart) { sb.append('&'); i++; continue }
                if (j < n && v[j] == ';') j++
                val x = cp.toInt()
                appendCodePoint(sb, when {
                    x == 0 || x > 0x10FFFF || x in 0xD800..0xDFFF -> 0xFFFD
                    x in 0x80..0x9F -> C1[x - 0x80]
                    else -> x
                })
                i = j
                continue
            }
            var j = i + 1
            while (j < n && j - i <= 32 && alnum(v[j])) j++
            val run = v.substring(i + 1, j)
            if (run.isNotEmpty() && j < n && v[j] == ';') {
                val hit = NAMED[run]
                if (hit != null) { sb.append(hit); i = j + 1; continue }
            }
            var k = run.length
            var done = false
            while (k > 0) {
                val p = run.substring(0, k)
                if (p in LEGACY) {
                    val next = if (i + 1 + k < n) v[i + 1 + k] else null
                    if (next == null || !(next == '=' || alnum(next))) {
                        sb.append(NAMED.getValue(p))
                        i += 1 + k
                        done = true
                    }
                    break
                }
                k--
            }
            if (!done) { sb.append('&'); i++ }
        }
        return sb.toString()
    }
}
