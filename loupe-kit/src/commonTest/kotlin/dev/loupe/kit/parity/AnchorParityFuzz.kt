package dev.loupe.kit.parity

import dev.loupe.kit.mail.HtmlAnchors
import dev.loupe.kit.mail.MailMessage
import dev.loupe.kit.site.Hosts
import dev.loupe.kit.site.ParsedUrl
import dev.loupe.persistence.JsonValue

/**
 * The anchor-parity fuzz (tools/parity/fuzz/README.md, fix loop 7): Loupe's reading of mail HTML
 * against the document Chrome builds. For each generated document, every web host a link in Chrome's
 * document opens (<a>, <area>, an SVG link, a meta refresh; resolved against its <base>) must be a host
 * Loupe judges for one of [MailMessage.anchors]. Loupe may find more (it leans toward finding: CDATA
 * and template content are read, other bases are tried); those are counted, not failed.
 */
object AnchorParityFuzz {
    /**
     * [missing]: documents where Loupe's links (the union) lose a host Chrome's document has.
     * [treeMissing]: the same for the tree-aware reading alone (fix loop 9): it decides link text and
     * <base>, so it is held to Chrome too, although the union already guarantees coverage.
     */
    class Result(val cases: Int, val missing: List<String>, val overFound: Int, val treeMissing: List<String>)

    private fun norm(h: String?): String = (h ?: "").lowercase().trimEnd('.')

    fun run(json: String): Result {
        val root = JsonValue.parse(json).asObj
        require(root["format"]?.asString == "loupe-anchor-parity-fuzz") { "not an anchor-parity fuzz file" }
        val cases = root["cases"]!!.asArr.items
        val missing = mutableListOf<String>()
        val treeMissing = mutableListOf<String>()
        var over = 0
        for (c in cases) {
            val o = c.asObj
            val html = o["html"]!!.asString
            val chrome = o["hosts"]!!.asArr.items.map { norm(it.asString) }.toSet()
            val loupe = MailMessage.anchors(html).map { norm(ParsedUrl.parse(Hosts.linkUrl(it.first))?.host) }.filter { it.isNotEmpty() }.toSet()
            val lost = chrome - loupe
            if (lost.isNotEmpty()) missing += "${o["id"]!!.asString} $html: Chrome opens $lost, Loupe finds $loupe"
            if ((loupe - chrome).isNotEmpty()) over++
            val tree = HtmlAnchors.treeAnchors(html, 60).map { norm(ParsedUrl.parse(Hosts.linkUrl(it.first))?.host) }.filter { it.isNotEmpty() }.toSet()
            val treeLost = chrome - tree
            if (treeLost.isNotEmpty()) treeMissing += "${o["id"]!!.asString} $html: Chrome opens $treeLost, the tree-aware reading finds $tree"
        }
        return Result(cases.size, missing, over, treeMissing)
    }
}
