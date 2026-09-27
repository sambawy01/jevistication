package dev.loupe.kit.parity

import dev.loupe.kit.mail.MailMessage
import dev.loupe.kit.mail.Phishing
import dev.loupe.kit.site.Hosts
import dev.loupe.kit.site.ParsedUrl
import dev.loupe.persistence.JsonValue

/**
 * The differential host-parity fuzz (tools/parity/fuzz/README.md): Loupe against browser truth.
 *
 * - `href` cases: the value of an `<a href="...">` as written in the HTML source. Chrome's answers
 *   are the hosts it resolves the decoded value to against the two kinds of base a mail client
 *   gives a message (a web client's `https://mail.example.com/inbox/`, where `https:x` is relative,
 *   and Apple Mail's non-special `x-msg://base.invalid/`, where it is host x). Loupe reads the anchor
 *   with [MailMessage.anchors] and judges the host the mail check judges. It must be one of Chrome's,
 *   or Loupe must flag the link (the message is at least caution) when it cannot say.
 * - `text` cases: a line of plain-text mail. Every web host linkify-it or NSDataDetector links must be
 *   one of the hosts Loupe judges ([Phishing.urls]), or Loupe must flag the line.
 *
 * Hosts are compared lower-case, without a trailing dot or IPv6 brackets; the base's own host
 * (a relative href) and a dots-only host both mean "no host of its own". A linked text host counts
 * as judged when Loupe judges a host of the same registrable domain: linkify-it glues the CJK text
 * before `www.` into the name (`访问www.example.com` is a subdomain of example.com).
 */
object HostParityFuzz {
    /**
     * [overJudged]: hrefs a browser opens no host for (relative or refused under both bases), where
     * Loupe says safe but judges the host written in them (`www.example.com/x` and `\\paypa1-secure.xyz/x`
     * read as http://, a URL behind a no-break space): stricter than the browser, never hiding a host
     * the browser opens, so allowed and counted.
     */
    class Result(val cases: Int, val mismatches: List<String>, val overJudged: Int)

    private const val SENDER = "\"News\" <news@list.example.org>"
    private val BASE_HOSTS = setOf("mail.example.com", "base.invalid")

    fun norm(host: String?): String {
        var h = (host ?: "").lowercase().trimEnd('.')
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
        return if (h in BASE_HOSTS || h.all { it == '.' }) "" else h
    }

    /** The host the mail check judges for one href: "" for none (relative, mailto:, javascript:...). */
    fun judgedHost(href: String): String {
        val t = Hosts.stripC0(href).filter { it != '\t' && it != '\r' && it != '\n' }.lowercase()
        if (t.isEmpty() || listOf("mailto:", "tel:", "cid:", "#", "sms:", "data:", "blob:", "javascript:").any { t.startsWith(it) }) return ""
        return norm(ParsedUrl.parse(Hosts.linkUrl(href))?.host)
    }

    fun run(json: String): Result {
        val root = JsonValue.parse(json).asObj
        require(root["format"]?.asString == "loupe-host-parity-fuzz") { "not a host-parity fuzz file" }
        val cases = root["cases"]!!.asArr.items
        val out = mutableListOf<String>()
        var over = 0
        for (c in cases) {
            val o = c.asObj
            val id = o["id"]!!.asString
            val input = o["input"]!!.asString
            if (o["kind"]!!.asString == "href") {
                val anchors = MailMessage.anchors("<a href=\"$input\">x</a>")
                val loupe = anchors.firstOrNull()?.let { judgedHost(it.first) } ?: ""
                val chrome = o["chrome"]!!.asArr.items.map { norm(it.asString) }.toSet()
                val safe = loupe !in chrome && Phishing.assess(SENDER, "", links = anchors).level == "safe"
                if (safe && chrome == setOf("")) {
                    over++
                } else if (safe) {
                    out += "$id ${show(input)}: Chrome opens ${chrome.map { "\"$it\"" }}, Loupe judges \"$loupe\" and says safe"
                }
            } else {
                val linked = o["linked"]!!.asArr.items.map { norm(it.asString) }.filter { it.isNotEmpty() }.toSet()
                val loupe = Phishing.urls(input).map { norm(ParsedUrl.parse(Hosts.linkUrl(it))?.host) }.toSet()
                val loupeRegs = loupe.map { Hosts.registrableDomain(it) ?: it }.toSet()
                val missing = linked.filter { it !in loupe && (Hosts.registrableDomain(it) ?: it) !in loupeRegs }
                if (missing.isNotEmpty() && Phishing.assess(SENDER, input).level == "safe") {
                    out += "$id ${show(input)}: linked $missing, Loupe judges $loupe and says safe"
                }
            }
        }
        return Result(cases.size, out, over)
    }

    private fun show(s: String): String = buildString {
        append('"')
        for (ch in s) if (ch.code < 0x20 || ch.code == 0x7F || ch.code in 0x80..0xA0) append("\\u" + ch.code.toString(16).padStart(4, '0')) else append(ch)
        append('"')
    }
}
