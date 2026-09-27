package dev.loupe.kit.mail

import dev.loupe.kit.site.Hosts
import dev.loupe.kit.site.ParsedUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The union (fix loop 9): every link start tag outside a real comment is judged, whatever the tree
 * builder would make of it, so a wrong guess about raw text or SVG/MathML can only over-find. These
 * links are ones a browser does not render (inside <style>, <textarea>…) or does (after an end tag it
 * ignores): Loupe judges both, and those only the no-skip reading finds carry no link text.
 */
class HtmlAnchorsUnionTest {
    private val evil = """<a href="https://evil.example/login">Sign in to PayPal</a>"""

    private fun hosts(html: String) = MailMessage.anchors(html).mapNotNull { ParsedUrl.parse(Hosts.linkUrl(it.first))?.host }.toSet()

    @Test
    fun `a link inside any raw text or after any foreign wrapper is judged`() {
        val hiders = listOf("<style>", "<textarea>", "<plaintext>", "<title>", "<xmp>", "<script>", "<noembed>", "<noframes>", "<iframe>", "<noscript>")
        val wrappers = listOf("", "<svg>", "<math>", "<div><svg>", "<svg></div>", "<noscript><svg></noscript>", "<td><svg></td>", "<li><ul><svg></li>",
            "<body><svg></body>", "<form><svg></form>", "<div><svg><foreignObject><svg></div>", "<math><mi><svg></b>")
        for (w in wrappers) for (h in hiders) {
            val html = "$w$h<p>$evil"
            assertTrue("evil.example" in hosts(html), html)
        }
    }

    @Test
    fun `a link only the no-skip reading finds has no link text`() {
        val a = MailMessage.anchors("<style>$evil</style>")
        assertEquals(listOf("https://evil.example/login" to ""), a)
    }

    @Test
    fun `a link in a real comment is not judged`() {
        assertTrue(hosts("<!-- $evil -->").isEmpty())
    }
}
