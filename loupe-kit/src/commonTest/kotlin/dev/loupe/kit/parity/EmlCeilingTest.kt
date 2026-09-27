package dev.loupe.kit.parity

import dev.loupe.kit.mail.MailMessage
import dev.loupe.kit.mail.Phishing
import dev.loupe.kit.watchers.LEGIT_EML_DIR
import dev.loupe.persistence.JsonValue
import dev.loupe.persistence.PlatformFiles
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.MimeParser
import dev.loupe.sources.common.SourceItem
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Whole .eml files through MailMessage.fromItem (fix loops 10–11, tools/parity/eml/index.json): round
 * 10's 46 legitimate mails (MSO conditional comments, JSON-LD, textarea and xmp samples, <base>, SVG,
 * AMP, brand CDNs, Arabic and CJK), round 9's 10 and round 11's 5 newsletters, 7 newsletters with
 * YouTube/Vimeo/Maps/Spotify embeds, forms and split text; round 10's and round 11's attack mails
 * (forms, submit buttons, SVG <animate>/<set>, iframe srcdoc, frames, embeds, objects, hidden text,
 * link-cap floods). A legitimate mail never scores above its reviewed score (itself never above
 * origin/main's, 699761a); an attack never below its reviewed score (itself never below origin/main's,
 * but for noted rows where Chrome's document has no link). The pattern fallback reads only text/plain parts and the visible text of
 * HTML: a bare-URL pattern over the markup (a hidden MSO link, a comment, an image CDN) would raise a
 * legitimate score here.
 */
class EmlCeilingTest {
    private fun score(eml: String, name: String): Pair<Int, String> {
        val bytes = eml.encodeToByteArray()
        val parsed = MimeParser.parseEmail(bytes, TimeZone.UTC)
        val item = SourceItem(
            id = name, sourceId = "s", kind = ItemKind.EMAIL, path = name, messageIndex = null,
            name = name, text = parsed.modelText(), hasText = true, textTruncated = false,
            sizeBytes = bytes.size.toLong(), contentHash = "h", mime = "message/rfc822",
            date = null, dateOrigin = null, email = parsed.facts, facts = emptyMap(),
        )
        val m = MailMessage.fromItem(item, eml)
        val v = Phishing.assess(m.sender, m.body, m.replyTo, m.authResults, m.links)
        return v.score to "${v.codes} links=${m.links}"
    }

    @Test
    fun `legitimate mails never score above their ceiling and attacks never below origin main`() {
        val index = assertNotNull(PlatformFiles.readText("$LEGIT_EML_DIR/index.json"), LEGIT_EML_DIR)
        val root = JsonValue.parse(index).asObj
        require(root["format"]?.asString == "loupe-eml-ceilings")
        val bad = mutableListOf<String>()
        var legit = 0
        var attack = 0
        for (c in root["cases"]!!.asArr.items) {
            val o = c.asObj
            val file = o["file"]!!.asString
            val eml = assertNotNull(PlatformFiles.readText("$LEGIT_EML_DIR/$file"), file)
            val (s, why) = score(eml, file)
            if (o["kind"]!!.asString == "legit") {
                legit++
                val max = o["max"]!!.asInt
                if (max > o["origin_main"]!!.asInt) bad += "$file: ceiling $max above origin/main"
                if (s > max) bad += "$file: $s > $max $why"
            } else {
                attack++
                val min = o["min"]!!.asInt
                if (min < o["origin_main"]!!.asInt && o["note"] == null) bad += "$file: floor $min below origin/main"
                if (s < min) bad += "$file: $s < $min $why"
            }
        }
        println("eml ceilings: $legit legitimate, $attack attack, ${bad.size} out of bounds")
        assertTrue(legit >= 68 && attack >= 231, "legit=$legit attack=$attack")
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }
}
