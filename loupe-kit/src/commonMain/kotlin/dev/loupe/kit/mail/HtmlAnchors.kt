package dev.loupe.kit.mail

import dev.loupe.engine.PortableText
import dev.loupe.kit.site.Hosts
import dev.loupe.kit.site.ParsedUrl

/**
 * The links of an HTML mail as a browser reads them (fix loops 5–7). One forward pass tokenises every
 * tag, comment and raw-text element by the WHATWG rules and keeps a stack of open elements with their
 * namespaces, so that SVG and MathML are left where the tree builder leaves them: at a breakout tag
 * (`<p>`, `<div>`, `<br>`, `<b>`, `<img>`, `<table>`…), at `</p>` and `</br>`, at an end tag that closes an
 * HTML ancestor, and for the children of an integration point (SVG `foreignObject`, `desc`, `title`;
 * MathML `mi`, `mo`, `mn`, `ms`, `mtext`; `annotation-xml` for HTML).
 *
 * Every doubt leans toward finding MORE links, never fewer (fix loop 7):
 * - `<![CDATA[` always ends at the first `>` (a bogus comment), even where a browser would read a CDATA
 *   section: text a browser shows literally may then be read as a tag, but nothing is hidden;
 * - `<template>` content is read like any other (a browser keeps it out of the document);
 * - a relative href is judged against the first `<base href>` outside SVG/MathML, as a browser resolves
 *   it, and also as written (its own scheme) and against every other absolute `<base href>`, when those
 *   give another host.
 * Raw text (script, style, xmp, iframe, noembed, noframes, textarea, title, plaintext) is skipped only
 * for an element the stack puts in the HTML namespace. `<a>` and `<area>` take `href` (an `<a>` in SVG,
 * else `xlink:href`); `<meta http-equiv=refresh content="0;url=…">` is a link too. A tag cut off by the
 * end of the document is dropped, as the tokenizer drops it. The visible text of a link is the text the
 * tokenizer passes inside it (comments join what they split; script, style and title text is not shown).
 */
internal object HtmlAnchors {
    /**
     * The link text of a link only the no-skip reading found (fix loop 10): a word joiner, which shows
     * as nothing. Such a link may be markup a client shows as text (embed code in a <textarea>), so the
     * mail check lets it raise risk-level signals only.
     */
    const val UNVERIFIED = "\u2060"

    /** Visible text kept per link. */
    private const val TEXT_MAX = 4000

    /** Open elements tracked at most (a deeper, malformed document is read as HTML beyond this). */
    private const val STACK_MAX = 4096

    private val RAW_TEXT = setOf("script", "style", "xmp", "iframe", "noembed", "noframes", "textarea", "title")
    /** Foreign elements whose text is not shown (an SVG `<title>` is a tooltip, not link text). */
    private val HIDDEN_FOREIGN = setOf("title", "desc", "style", "script")
    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr", "param", "keygen", "frame", "basefont", "bgsound")
    private val BREAKOUT = setOf(
        "b", "big", "blockquote", "body", "br", "center", "code", "dd", "div", "dl", "dt", "em", "embed", "h1", "h2", "h3", "h4", "h5", "h6",
        "head", "hr", "i", "img", "li", "listing", "menu", "meta", "nobr", "ol", "p", "pre", "ruby", "s", "small", "span", "strong", "strike",
        "sub", "sup", "table", "tt", "u", "ul", "var",
    )
    private val MATHML_TEXT_IP = setOf("mi", "mo", "mn", "ms", "mtext")

    /** The HTML "special" elements (an end tag of another name stops at one; fix loop 8). */
    private val SPECIAL_HTML = setOf(
        "address", "applet", "area", "article", "aside", "base", "basefont", "bgsound", "blockquote", "body", "br", "button", "caption",
        "center", "col", "colgroup", "dd", "details", "dir", "div", "dl", "dt", "embed", "fieldset", "figcaption", "figure", "footer",
        "form", "frame", "frameset", "h1", "h2", "h3", "h4", "h5", "h6", "head", "header", "hgroup", "hr", "html", "iframe", "img", "input",
        "keygen", "li", "link", "listing", "main", "marquee", "menu", "meta", "nav", "noembed", "noframes", "noscript", "object", "ol", "p",
        "param", "plaintext", "pre", "script", "search", "section", "select", "source", "style", "summary", "table", "tbody", "td",
        "template", "textarea", "tfoot", "th", "thead", "title", "tr", "track", "ul", "wbr", "xmp",
    )

    /** What the tree builder keeps in the head: before anything else, the body has not started. */
    private val HEAD_OK = setOf("html", "head", "meta", "link", "style", "script", "title", "base", "basefont", "bgsound", "noframes", "noscript", "template")

    private val TABLE_CLOSERS = setOf("table", "tbody", "tfoot", "thead", "tr")
    /** End tags the tree builder checks in table scope from a cell or caption: only html, table and template bound them. */
    private val CELL_CLOSERS = setOf("td", "th", "caption")
    /** The elements that set a table insertion mode (the nearest one decides where a row or cell goes). */
    private val TABLE_CONTEXT = setOf("table", "tbody", "thead", "tfoot", "tr", "td", "th", "caption", "template")
    private val TABLE_BODIES = setOf("tbody", "thead", "tfoot")
    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    /** Table parts: the tree builder ignores them outside a table. */
    private val TABLE_PARTS = setOf("td", "th", "tr", "tbody", "thead", "tfoot", "caption", "col", "colgroup")

    /** The elements that bound "has an element in scope" (the default scope). */
    private val SCOPE_HTML = setOf("applet", "caption", "html", "table", "td", "th", "marquee", "object", "template")
    private val SVG_HTML_IP = setOf("foreignobject", "desc", "title")

    private const val HTML = 0
    private const val SVG = 1
    private const val MATH = 2

    private class El(val name: String, val ns: Int, val htmlIp: Boolean)
    private class Tag(val name: String, val end: Boolean, val attrs: Map<String, String>, val start: Int, val after: Int, val selfClosing: Boolean)
    private class Link(val raw: String, val text: StringBuilder, val fixedText: String?, val start: Int)

    fun anchors(html: String, max: Int): List<Pair<String, String>> {
        // (a) the tree-aware reading: namespaces, raw text, link text and the document's <base>
        val links = mutableListOf<Link>()
        val bases = mutableListOf<Pair<String, Boolean>>() // (href, outside SVG/MathML), in document order
        scan(html, true, links, bases)
        // (b) the no-skip reading (fix loop 9): the same tokeniser, but no raw text, RCDATA, plaintext or
        // foreign-content state skips anything, so every <a href>, <area href>, SVG link and meta refresh
        // that is a start tag anywhere outside a real comment is judged. A missed link would need the
        // tree builder emulated exactly; the union makes over-finding the only possible error. A link only
        // (b) finds is judged with empty text: it can raise host signals, never a text mismatch.
        val flat = mutableListOf<Link>()
        val flatBases = mutableListOf<Pair<String, Boolean>>()
        scanCandidates(html, flat, flatBases)
        val seenStarts = links.mapTo(HashSet()) { it.start }
        for (l in flat) if (l.start !in seenStarts) links += Link(l.raw, StringBuilder(), UNVERIFIED, l.start)
        links.sortBy { it.start }
        // a <base> only (b) finds (inside a textarea, a comment, raw text) never governs a verified reading:
        // links resolved against it are judged with no link text, like (b)-only links (fix loop 10)
        val unverifiedBases = flatBases.map { it.first }.filter { b -> bases.none { it.first == b } }
            .map { Hosts.cleanHref(it) }.toSet()
        for (b in unverifiedBases) bases += b to false
        return resolveAll(links, bases, max, unverifiedBases)
    }

    /**
     * The tree-aware reading alone (a), for tests that hold its precision (link text and <base> depend
     * on it): the regression set's links must all be found by it, not only by the union.
     */
    internal fun treeAnchors(html: String, max: Int): List<Pair<String, String>> {
        val links = mutableListOf<Link>()
        val bases = mutableListOf<Pair<String, Boolean>>()
        scan(html, true, links, bases)
        return resolveAll(links, bases, max)
    }

    /** The longest tag reading (b) reads from one `<` (it looks at every `<`, so this keeps it linear). */
    private const val CANDIDATE_TAG_MAX = 2048

    /**
     * Reading (b) (fix loops 9–10): every `<` of the raw markup is a candidate tag start, read on its own,
     * whatever came before it. Nothing is skipped: not comments (whether `<!--` opens one depends on the
     * tree), not raw text, not a quoted value an earlier candidate would swallow. Every <a href>, <area
     * href>, SVG link, meta refresh and <base href> start tag anywhere is found; over-finding is the only
     * error.
     */
    private fun scanCandidates(html: String, links: MutableList<Link>, bases: MutableList<Pair<String, Boolean>>) {
        var lt = html.indexOf('<')
        while (lt >= 0) {
            val tag = readTag(html, lt, minOf(html.length, lt + CANDIDATE_TAG_MAX))
            if (tag != null && !tag.end) {
                val a = tag.attrs
                when (tag.name) {
                    "a" -> (a["href"] ?: a["xlink:href"])?.let { links += Link(it, StringBuilder(), "", lt) }
                    "area" -> a["href"]?.let { links += Link(it, StringBuilder(), "", lt) }
                    "meta" -> if (a["http-equiv"]?.trim()?.lowercase() == "refresh") {
                        refreshUrl(decodeAttribute(a["content"] ?: ""))?.let { links += Link(it, StringBuilder(), "", lt) }
                    }
                    "base" -> a["href"]?.let { bases += decodeAttribute(it) to false }
                }
            }
            lt = html.indexOf('<', lt + 1)
        }
    }

    private fun scan(html: String, tree: Boolean, links: MutableList<Link>, bases: MutableList<Pair<String, Boolean>>) {
        val stack = ArrayList<El>()
        val count = HashMap<String, Int>()
        var open: Link? = null
        fun push(e: El) {
            if (stack.size >= STACK_MAX) return
            stack += e
            count[e.name] = (count[e.name] ?: 0) + 1
        }
        fun pop() {
            val e = stack.removeAt(stack.size - 1)
            count[e.name] = (count[e.name] ?: 1) - 1
        }
        fun current(): El? = stack.lastOrNull()
        fun inHtml(): Boolean { val c = current(); return c == null || c.ns == HTML }
        /** Pop foreign elements until the current node is HTML or an integration point (a breakout). */
        fun leaveForeign() {
            while (true) {
                val c = current() ?: return
                if (c.ns == HTML || c.htmlIp || (c.ns == MATH && c.name in MATHML_TEXT_IP)) return
                pop()
            }
        }
        fun popTo(index: Int) { while (stack.size > index) pop() }
        fun foreignIp(e: El) = (e.ns == SVG && e.name in SVG_HTML_IP) || (e.ns == MATH && (e.name in MATHML_TEXT_IP || e.name == "annotation-xml"))
        /**
         * An end tag, as the tree builder takes it (fix loop 8): in SVG/MathML it closes the nearest
         * foreign element of that name above the first HTML one; otherwise (the in-body rules) the
         * nearest HTML element of that name, unless a scope boundary comes first, or, for an end tag
         * that is not itself special, any special element (MathML annotation-xml is one): then it is
         * ignored.
         */
        fun close(name: String) {
            // </body> and </html> only switch insertion mode; they never pop (fix loop 9)
            if (name == "body" || name == "html") return
            if (name in HEADINGS) { if (HEADINGS.none { (count[it] ?: 0) > 0 }) return } else if ((count[name] ?: 0) <= 0) return
            var i = stack.size - 1
            if (stack.isNotEmpty() && stack[i].ns != HTML) {
                while (i >= 0 && stack[i].ns != HTML) {
                    if (stack[i].name == name) { popTo(i); return }
                    i--
                }
            }
            // the in-body walk starts at the current node, so an integration point above the HTML
            // element is a scope boundary (fix loop 9)
            i = stack.size - 1
            // </table>, </tbody>, </tr>… inside a cell or caption close it first ("table scope": only
            // html, table and template bound them); </hN> closes an open heading of any level (fix loop 10)
            // </template> needs no scope: it closes the nearest template (fix loop 10)
            if (name == "template") {
                while (i >= 0) { if (stack[i].ns == HTML && stack[i].name == "template") { popTo(i); return }; i-- }
                return
            }
            if (name in TABLE_CLOSERS || name in CELL_CLOSERS) {
                while (i >= 0) {
                    val e = stack[i]
                    if (e.ns == HTML && e.name == name) { popTo(i); return }
                    if (e.ns == HTML && (e.name == "html" || e.name == "table" || e.name == "template")) return
                    i--
                }
                return
            }
            if (name in HEADINGS) {
                while (i >= 0) {
                    val e = stack[i]
                    if (e.ns == HTML && e.name in HEADINGS) { popTo(i); return }
                    if ((e.ns == HTML && (e.name in SCOPE_HTML || e.name == "select")) || foreignIp(e)) return
                    i--
                }
                return
            }
            val specialName = name in SPECIAL_HTML
            while (i >= 0) {
                val e = stack[i]
                if (e.ns == HTML && e.name == name) {
                    if (name == "form") { // </form> removes the form element only, nothing above it
                        stack.removeAt(i)
                        count[name] = (count[name] ?: 1) - 1
                    } else popTo(i)
                    return
                }
                if ((e.ns == HTML && e.name in SCOPE_HTML) || foreignIp(e)) return
                // an open <select> bounds it too: `<h2><select><math></h2>` is ignored, `<select><h2><math></h2>`
                // is not (Chrome; round 9 r:552, round 10 r:3597; table end tags and </template> still close it)
                if (e.ns == HTML && e.name == "select") return
                if (name == "li" && e.ns == HTML && (e.name == "ul" || e.name == "ol")) return // list item scope
                if (!specialName && e.ns == HTML && e.name in SPECIAL_HTML) return
                i--
            }
        }
        /**
         * A row, cell or other table part start tag, as the table insertion modes place it (fix loop 10):
         * it first closes an open cell, caption, row or body it cannot go in, and a missing <tbody> or
         * <tr> is implied, so a later </tbody> or </tr> finds it (`<table><tr><td><svg></tbody>` leaves SVG).
         */
        fun tablePart(name: String) {
            var guard = 0
            while (guard++ < 8) {
                var j = stack.size - 1
                while (j >= 0 && !(stack[j].ns == HTML && stack[j].name in TABLE_CONTEXT)) j--
                if (j < 0) return
                val ctx = stack[j].name
                when {
                    ctx == "template" -> return
                    ctx == "td" || ctx == "th" || ctx == "caption" -> { popTo(j); continue } // close the cell or caption
                    ctx == "tr" -> {
                        if (name == "td" || name == "th") { popTo(j + 1); return }
                        popTo(j); continue // close the row
                    }
                    ctx in TABLE_BODIES -> {
                        popTo(j + 1)
                        when (name) {
                            "tr" -> return
                            "td", "th" -> { push(El("tr", HTML, false)); return }
                            else -> { popTo(j); continue } // close the body
                        }
                    }
                    else -> { // table
                        popTo(j + 1)
                        when (name) {
                            "tr" -> push(El("tbody", HTML, false))
                            "td", "th" -> { push(El("tbody", HTML, false)); push(El("tr", HTML, false)) }
                        }
                        return
                    }
                }
            }
        }
        fun text(from: Int, to: Int) {
            if (!tree) return
            val l = open ?: return
            // text right inside a foreign title/desc/style/script is not shown (HTML inside one is)
            val cur = stack.lastOrNull()
            if ((cur != null && cur.ns != HTML && cur.name in HIDDEN_FOREIGN) || to <= from || l.text.length >= TEXT_MAX) return
            l.text.append(html, from, minOf(to, from + TEXT_MAX - l.text.length))
        }

        // Whether the body has started (a start tag the head does not keep, or text): a <noscript> before
        // it is the head's, which the next other start tag closes, so its end tag later closes nothing
        // (fix loop 9: `<noscript><svg></noscript><style>` stays in SVG)
        var bodyStarted = false
        var i = 0
        val n = html.length
        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) { text(i, n); break }
            if (!bodyStarted) for (k in i until lt) if (!space(html[k])) { bodyStarted = true; break }
            text(i, lt)
            if (html.startsWith("<!--", lt)) { i = commentEnd(html, lt + 4); continue }
            // `<!…` (also `<![CDATA[`, see above) and `<?…`: a bogus comment to the next `>`
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
            if (tag == null) { text(lt, lt + 1); i = lt + 1; continue }
            if (tag.after < 0) break // cut off by the end of the document: dropped, and nothing follows
            i = tag.after
            val name = tag.name
            if (tag.end) {
                if (!tree) continue
                if (!inHtml() && (name == "p" || name == "br")) leaveForeign()
                if (name == "a") open = null
                close(name)
                continue
            }
            // where the start tag goes: HTML (no foreign element open, an integration point's child, or a
            // breakout tag, which first leaves SVG/MathML), else the namespace of the foreign element it is in
            var ns = HTML
            val c = current()
            if (tree && c != null && c.ns != HTML) {
                val ip = c.htmlIp || (c.ns == MATH && c.name in MATHML_TEXT_IP && name != "mglyph" && name != "malignmark")
                val font = name == "font" && (tag.attrs.containsKey("color") || tag.attrs.containsKey("face") || tag.attrs.containsKey("size"))
                ns = when {
                    ip -> HTML
                    c.ns == MATH && c.name == "annotation-xml" && name == "svg" -> SVG
                    name in BREAKOUT || font -> { leaveForeign(); HTML }
                    else -> c.ns
                }
            }
            if (tree && ns == HTML && name == "svg") ns = SVG
            if (tree && ns == HTML && name == "math") ns = MATH
            val selfClosing = tag.selfClosing && ns != HTML
            when {
                name == "base" && tag.attrs.containsKey("href") -> bases += decodeAttribute(tag.attrs.getValue("href")) to (ns == HTML)
                // <area> and a meta refresh are read in any namespace (a doubt about the namespace
                // must not lose one; in SVG/MathML a browser has no such link: over-finding)
                name == "meta" && tag.attrs["http-equiv"]?.trim()?.lowercase() == "refresh" ->
                    refreshUrl(decodeAttribute(tag.attrs["content"] ?: ""))?.let { links += Link(it, StringBuilder(), "", tag.start) }
                name == "area" -> tag.attrs["href"]?.let { links += Link(it, StringBuilder(), decodeText(tag.attrs["alt"] ?: ""), tag.start) }
                name == "a" -> {
                    if (ns == HTML) open = null // an <a> closes the one still open (the adoption agency)
                    val raw = tag.attrs["href"] ?: if (ns == SVG || !tree) tag.attrs["xlink:href"] else null
                    if (raw != null) { val l = Link(raw, StringBuilder(), null, tag.start); links += l; open = l }
                }
            }
            if (!tree) continue
            if (ns == HTML) {
                // html/head/body are the document's own (a stray one is merged or ignored); table parts
                // outside a table are ignored by the tree builder (fix loop 9)
                val headNoscript = name == "noscript" && !bodyStarted
                if (name !in HEAD_OK) bodyStarted = true
                val ignored = name == "html" || name == "head" || name == "body" || headNoscript || (name in TABLE_PARTS && (count["table"] ?: 0) <= 0)
                if (!ignored && name in TABLE_PARTS) tablePart(name)
                if (name !in VOID && !ignored) push(El(name, HTML, false))
                when {
                    name == "plaintext" -> { text(i, n); i = n }
                    name in RAW_TEXT -> {
                        val end = rawTextEnd(html, tag.after, name)
                        if (name == "textarea" || name == "xmp") text(tag.after, end)
                        i = end
                    }
                }
            } else if (!selfClosing) {
                push(El(name, ns, (ns == SVG && name in SVG_HTML_IP) || (ns == MATH && name == "annotation-xml" && tag.attrs["encoding"]?.let { decodeAttribute(it).lowercase() }.let { it == "text/html" || it == "application/xhtml+xml" })))
            }
        }

    }

    private fun resolveAll(
        links: List<Link>,
        bases: List<Pair<String, Boolean>>,
        max: Int,
        unverifiedBases: Set<String> = emptySet(),
    ): List<Pair<String, String>> {
        // resolve: the first <base href> outside SVG/MathML is the document's; the href as written and
        // every other absolute <base href> are judged too when they give another host (lean toward finding)
        val docBase = bases.firstOrNull { it.second }?.first?.let { Hosts.cleanHref(it) }
        val absDoc = docBase?.takeIf { absoluteWeb(it) }
        val others = bases.map { Hosts.cleanHref(it.first) }.filter { absoluteWeb(it) && it != absDoc }.distinct()
        val out = mutableListOf<Pair<String, String>>()
        for (l in links) {
            if (out.size >= max) break
            val href = decodeAttribute(l.raw)
            val shown = l.fixedText ?: PortableText.collapseSpaces(decodeText(l.text.toString()))
            // reading -> its text; the first text given for a reading wins
            val readings = LinkedHashMap<String, String>()
            readings.getOrPut(if (absDoc != null) Hosts.resolve(absDoc, href) else href) { shown }
            for (b in others) readings.getOrPut(Hosts.resolve(b, href)) { if (b in unverifiedBases) UNVERIFIED else shown }
            readings.getOrPut(href) { shown }
            val first = readings.entries.first()
            if (Hosts.stripC0(first.key).isEmpty()) continue
            out += first.key to first.value
            val seen = mutableSetOf(hostOf(first.key))
            for ((r, t) in readings.entries.drop(1)) {
                if (out.size >= max) break
                val h = hostOf(r) ?: continue
                if (seen.add(h)) out += r to t
            }
        }
        return out
    }

    /** Attributes whose URL loads or describes something (an image, a font, a namespace): nobody follows it. */
    private val NON_NAV_ATTRS = setOf("src", "srcset", "background", "poster", "lowsrc", "dynsrc", "longdesc", "cite", "codebase", "classid", "profile", "archive", "itemtype", "itemprop")

    /**
     * The URLs of [html] that no reader follows (fix loop 9): the DOCTYPE's (a DTD), namespace URIs
     * (`xmlns`, `xmlns:*`), and the values of attributes that load or describe rather than link (`src`,
     * `srcset`, `background`, `poster`, …; `<link href>`, a stylesheet or icon). The mail's pattern links
     * (`EmailFacts.links`) found only there are not judged.
     */
    fun nonNavigableUrls(html: String): Set<String> {
        val out = HashSet<String>()
        var i = 0
        val n = html.length
        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) break
            if (html.startsWith("<!--", lt)) { i = commentEnd(html, lt + 4); continue }
            if (lt + 1 < n && (html[lt + 1] == '!' || html[lt + 1] == '?')) {
                val e = html.indexOf('>', lt + 2).let { if (it < 0) n else it }
                Regex("[\"']([^\"']*://[^\"']*)[\"']").findAll(html.substring(lt, e)).forEach { out += it.groupValues[1].trim() }
                i = e + 1
                continue
            }
            val tag = readTag(html, lt)
            if (tag == null || tag.after < 0) { i = lt + 1; continue }
            i = tag.after
            for ((k, v) in tag.attrs) {
                if (k == "xmlns" || k.startsWith("xmlns:") || k in NON_NAV_ATTRS || (tag.name == "link" && k == "href")) {
                    val d = decodeAttribute(v).trim()
                    if (k == "srcset") d.split(',').forEach { out += it.trim().substringBefore(' ') } else out += d
                }
            }
        }
        return out
    }

    private val INVISIBLE = setOf("script", "style", "textarea", "title", "iframe", "noembed", "noframes", "xmp", "template")

    /**
     * The text of [html] a mail client shows and would linkify (fix loop 10): text outside comments,
     * tags and the elements whose content is code or not shown (script, style, textarea, title, iframe,
     * noembed, noframes, xmp, template), character references decoded. The mail's pattern links come
     * from this, not from the raw markup (JSON-LD, CSS url(), data-src, embed code, VML).
     */
    fun visibleText(html: String): String {
        val out = StringBuilder()
        var i = 0
        val n = html.length
        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) { out.append(html, i, n); break }
            out.append(html, i, lt).append(' ')
            if (html.startsWith("<!--", lt)) { i = commentEnd(html, lt + 4); continue }
            if (lt + 1 < n && (html[lt + 1] == '!' || html[lt + 1] == '?')) { i = html.indexOf('>', lt + 2).let { if (it < 0) n else it + 1 }; continue }
            val tag = readTag(html, lt)
            if (tag == null) { out.append('<'); i = lt + 1; continue }
            if (tag.after < 0) break
            i = tag.after
            if (!tag.end && tag.name in INVISIBLE) i = rawTextEnd(html, tag.after, tag.name)
        }
        return decodeText(out.toString())
    }

    /** The host the mail check judges for [href], or null for none. */
    private fun hostOf(href: String): String? = ParsedUrl.parse(Hosts.linkUrl(href))?.host?.ifEmpty { null }

    private fun absoluteWeb(u: String): Boolean = Hosts.isWebUrl(u) && Hosts.splitUrl(u).authority?.isNotEmpty() == true

    /** The URL of a meta refresh's content (`0; url='https://…'`), or null. */
    internal fun refreshUrl(content: String): String? {
        var j = 0
        val s = content
        while (j < s.length && (s[j] == ' ' || s[j] == '\t' || s[j] == '\n' || s[j] == '\r' || s[j] == '\u000C')) j++
        while (j < s.length && (s[j].isDigit() || s[j] == '.')) j++
        while (j < s.length && (s[j] == ' ' || s[j] == '\t' || s[j] == '\n' || s[j] == '\r' || s[j] == '\u000C')) j++
        if (j < s.length && (s[j] == ';' || s[j] == ',')) j++
        while (j < s.length && (s[j] == ' ' || s[j] == '\t' || s[j] == '\n' || s[j] == '\r' || s[j] == '\u000C')) j++
        if (s.regionMatches(j, "url", 0, 3, ignoreCase = true)) {
            var k = j + 3
            while (k < s.length && s[k] == ' ') k++
            if (k < s.length && s[k] == '=') {
                k++
                while (k < s.length && s[k] == ' ') k++
                j = k
            }
        }
        var u = s.substring(j)
        if (u.isNotEmpty() && (u[0] == '"' || u[0] == '\'')) u = u.substring(1).substringBefore(u[0])
        u = u.trim()
        return u.ifEmpty { null }
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

    /** The start or end tag at [lt], or null when there is none; `after` is -1 when the document ends inside it. */
    private fun readTag(s: String, lt: Int, limit: Int = s.length): Tag? {
        val end = lt + 1 < limit && s[lt + 1] == '/'
        val ns = lt + (if (end) 2 else 1)
        if (ns >= limit || !letter(s[ns])) return null
        var j = ns
        while (j < limit && !space(s[j]) && s[j] != '/' && s[j] != '>') j++
        val name = s.substring(ns, j).lowercase()
        val (attrs, after, selfClosing) = tagAttributes(s, j, limit)
        return Tag(name, end, attrs, lt, after, selfClosing)
    }

    /** The tag's attributes from [pos] (after its name), first occurrence of each name, where the tag ends (-1: never), and `/>`. */
    private fun tagAttributes(s: String, pos: Int, limit: Int = s.length): Triple<Map<String, String>, Int, Boolean> {
        val attrs = LinkedHashMap<String, String>()
        var j = pos
        val n = limit
        var slash: Boolean
        while (true) {
            slash = false
            while (j < n && (space(s[j]) || s[j] == '/')) { slash = s[j] == '/'; j++ }
            if (j >= n) return Triple(attrs, -1, false)
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
                    val e = s.indexOf(q, j + 1).let { if (it >= n) -1 else it }
                    if (e < 0) return Triple(attrs, -1, false)
                    value = s.substring(j + 1, e)
                    j = e + 1
                } else {
                    val vs = j
                    while (j < n && !space(s[j]) && s[j] != '>') j++
                    value = s.substring(vs, j)
                }
            }
            if (name !in attrs) attrs[name] = value
        }
    }

    /** Text's character references decoded (as in an attribute, without the attribute-only rule). */
    private fun decodeText(t: String): String = decodeAttribute(t, attribute = false)

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
    fun decodeAttribute(v: String, attribute: Boolean = true): String {
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
                    if (!attribute || next == null || !(next == '=' || alnum(next))) {
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
