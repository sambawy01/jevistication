package dev.loupe.kit.mail

import dev.loupe.engine.PortableText
import dev.loupe.kit.site.Hosts
import dev.loupe.sources.common.HtmlText

/**
 * The links of an HTML mail as a browser reads them (fix loops 5–6). One forward pass tokenises every
 * tag, comment and raw-text element by the WHATWG rules, so nothing hides an anchor or makes one up:
 * - comments (`<!-- … -->`, also `<!-->`), bogus comments (`<!…>`, `<?…>`, and `<![CDATA[…` outside
 *   SVG/MathML, which ends at the first `>`), and the text of script, style, xmp, iframe, noembed,
 *   noframes, textarea and title are not markup; an anchor inside `<template>` is not in the document;
 * - attribute names are whole (`data-href` is not `href`), quoted values may hold `>` and `<a`, the
 *   first of a repeated attribute wins, and a quote left open runs to the end of the document;
 * - an `<a>` inside `<svg>` takes `href`, else `xlink:href`; an anchor left open ends at the next `<a>`;
 * - a relative href is resolved against the document's first `<base href>` with an absolute web URL,
 *   as a browser resolves it, and that URL is judged.
 * The value's character references are decoded ([decodeAttribute]).
 */
internal object HtmlAnchors {
    /** Visible text of an anchor that is never closed: at most this much of what follows. */
    private const val OPEN_TEXT_MAX = 4000

    private val RAW_TEXT = setOf("script", "style", "xmp", "iframe", "noembed", "noframes", "textarea", "title")

    private class Tag(val name: String, val end: Boolean, val attrs: Map<String, String>, val start: Int, val after: Int, val selfClosing: Boolean)

    fun anchors(html: String, max: Int): List<Pair<String, String>> {
        val found = mutableListOf<Triple<String, Int, Int>>() // (raw href, content start, content end)
        var base: String? = null
        var open = -1 // index in found of the anchor whose content is running
        var openHref: String? = null
        var foreign = 0 // depth inside <svg>/<math>
        var template = 0
        fun closeOpen(at: Int) {
            if (open >= 0) { val (h, cs, _) = found[open]; found[open] = Triple(h, cs, at) }
            open = -1
        }
        var i = 0
        val n = html.length
        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) break
            // comments and other markup declarations
            if (html.startsWith("<!--", lt)) {
                i = commentEnd(html, lt + 4)
                continue
            }
            if (html.startsWith("<![CDATA[", lt) && foreign > 0) {
                i = html.indexOf("]]>", lt + 9).let { if (it < 0) n else it + 3 }
                continue
            }
            if (lt + 1 < n && (html[lt + 1] == '!' || html[lt + 1] == '?')) {
                i = html.indexOf('>', lt + 2).let { if (it < 0) n else it + 1 }
                continue
            }
            if (lt + 1 < n && html[lt + 1] == '/' && (lt + 2 >= n || !letter(html[lt + 2]))) {
                // `</>` is dropped; `</` then anything but a letter is a bogus comment to the next `>`
                i = html.indexOf('>', lt + 2).let { if (it < 0) n else it + 1 }
                continue
            }
            val tag = readTag(html, lt)
            if (tag == null) { i = lt + 1; continue }
            i = tag.after
            if (tag.end) {
                when (tag.name) {
                    "a" -> if (template == 0) closeOpen(tag.start)
                    "svg", "math" -> if (foreign > 0) foreign--
                    "template" -> if (template > 0) template--
                }
                continue
            }
            when (tag.name) {
                "svg", "math" -> if (!tag.selfClosing) foreign++
                "template" -> if (!tag.selfClosing) template++
                "base" -> if (base == null && template == 0) tag.attrs["href"]?.let { base = decodeAttribute(it) }
                "a" -> if (template == 0) {
                    closeOpen(tag.start)
                    val raw = tag.attrs["href"] ?: if (foreign > 0) tag.attrs["xlink:href"] else null
                    if (raw != null) {
                        found += Triple(raw, tag.after, -1)
                        open = found.size - 1
                    }
                }
            }
            // the text of a raw-text or RCDATA element is not markup (outside SVG/MathML)
            // (HTML ignores `/>` on these: `<script/>` still opens script text)
            if (foreign == 0 && tag.name in RAW_TEXT) i = rawTextEnd(html, tag.after, tag.name)
            if (found.size > max && open < 0) break
        }
        closeOpen(-1)
        val absBase = base?.let { Hosts.cleanHref(it) }?.takeIf { Hosts.isWebUrl(it) && Hosts.splitUrl(it).authority?.isNotEmpty() == true }
        val out = mutableListOf<Pair<String, String>>()
        for ((raw, cs, ce) in found) {
            if (out.size >= max) break
            var href = decodeAttribute(raw)
            if (Hosts.stripC0(href).isEmpty() && absBase == null) continue
            if (absBase != null) href = Hosts.resolve(absBase, href)
            val end = if (ce >= 0) ce else minOf(n, cs + OPEN_TEXT_MAX)
            out += href to PortableText.collapseSpaces(HtmlText.toText(html.substring(cs, maxOf(cs, end))))
        }
        return out
    }

    /** Where a comment opened at [from] (after `<!--`) ends: `-->`, `--!>`, or the abrupt `<!-->` / `<!--->`. */
    private fun commentEnd(s: String, from: Int): Int {
        if (s.startsWith(">", from)) return from + 1
        if (s.startsWith("->", from)) return from + 2
        var j = s.indexOf("--", from)
        while (j >= 0) {
            if (s.startsWith("-->", j)) return j + 3
            if (s.startsWith("--!>", j)) return j + 4
            j = s.indexOf("--", j + 1)
        }
        return s.length
    }

    /** Where the text of a raw-text element [name] opened before [from] ends: at its end tag. */
    private fun rawTextEnd(s: String, from: Int, name: String): Int {
        var j = s.indexOf("</", from)
        while (j >= 0) {
            val k = j + 2 + name.length
            if (s.regionMatches(j + 2, name, 0, name.length, ignoreCase = true) && (k >= s.length || space(s[k]) || s[k] == '/' || s[k] == '>')) return j
            j = s.indexOf("</", j + 2)
        }
        return s.length
    }

    private fun space(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'
    private fun letter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    /** The start or end tag at [lt] (`<` then a letter, or `</` then a letter), or null when there is none. */
    private fun readTag(s: String, lt: Int): Tag? {
        val end = lt + 1 < s.length && s[lt + 1] == '/'
        val ns = lt + (if (end) 2 else 1)
        if (ns >= s.length || !letter(s[ns])) return null
        var j = ns
        while (j < s.length && !space(s[j]) && s[j] != '/' && s[j] != '>') j++
        val name = s.substring(ns, j).lowercase()
        val (attrs, after, selfClosing) = tagAttributes(s, j)
        return Tag(name, end, attrs, lt, after, selfClosing)
    }

    /** The tag's attributes from [pos] (after its name), first occurrence of each name, where the tag ends, and `/>`. */
    private fun tagAttributes(s: String, pos: Int): Triple<Map<String, String>, Int, Boolean> {
        val attrs = LinkedHashMap<String, String>()
        var j = pos
        val n = s.length
        var slash = false
        while (true) {
            slash = false
            while (j < n && (space(s[j]) || s[j] == '/')) { slash = s[j] == '/'; j++ }
            if (j >= n) return Triple(attrs, n, false)
            if (s[j] == '>') return Triple(attrs, j + 1, slash)
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
