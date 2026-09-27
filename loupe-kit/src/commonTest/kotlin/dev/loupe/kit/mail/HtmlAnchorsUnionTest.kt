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
    fun `a link only the no-skip reading finds is marked unverified`() {
        val a = MailMessage.anchors("<style>$evil</style>")
        assertEquals(listOf("https://evil.example/login" to HtmlAnchors.UNVERIFIED), a)
    }

    @Test
    fun `an unverified link raises risk signals only`() {
        // embed code a client shows as text (r10-work 18-base-in-textarea): a TLD note is not raised
        val widget = Phishing.assess("\"Shop\" <news@shop.example>", "", links = MailMessage.anchors("<textarea><a href=\"https://widgets.gdi-cdn.top/w/1\">x</a></textarea>"))
        assertEquals(emptySet(), widget.codes, "${widget.codes}")
        // a look-alike host is still raised
        val look = Phishing.assess("\"Shop\" <news@shop.example>", "", links = MailMessage.anchors("<textarea><a href=\"https://paypa1-secure.xyz/login\">x</a></textarea>"))
        assertTrue("link_lookalike_brand" in look.codes, "${look.codes}")
    }

    @Test
    fun `a link inside a comment is judged with no link text`() {
        // fix loop 10: whether `<!--` opens a comment depends on the tree (inside HTML <style> it is text),
        // so the no-skip reading trusts no comment; such a link carries no text (host signals only)
        assertEquals(listOf("https://evil.example/login" to HtmlAnchors.UNVERIFIED), MailMessage.anchors("<!-- $evil -->"))
        assertTrue("evil.example" in hosts("<table><tr><td><svg></table><style><!--</style><p>$evil"))
    }

    @Test
    fun `a base only the no-skip reading finds gives unverified readings only`() {
        // r10-work 18-base-in-textarea: embed code's <base> must not lend a verified link its text
        val html = "<textarea><base href=\"https://widgets.gdi-cdn.top/\"></textarea><a href=\"/account\">Your account</a>"
        val a = MailMessage.anchors(html)
        assertTrue(("https://widgets.gdi-cdn.top/account" to HtmlAnchors.UNVERIFIED) in a, "$a")
        assertTrue(a.none { it.first.contains("gdi-cdn") && it.second != HtmlAnchors.UNVERIFIED }, "$a")
        val v = Phishing.assess("\"Shop\" <news@shop.example>", "", links = a)
        assertEquals(emptySet(), v.codes, "${v.codes}")
        // a <base> the tree-aware reading sees keeps its readings verified (another base: text kept)
        val two = MailMessage.anchors("<base href=\"https://www.paypal.com/\"><base href=\"https://paypa1-secure.xyz/\"><a href=\"login\">PayPal</a>")
        assertTrue(("https://paypa1-secure.xyz/login" to "PayPal") in two, "$two")
    }

    @Test
    fun `the tree-aware reading follows Chrome through select and table insertion modes`() {
        // (document, whether Chrome's document has the link): Chrome headless DOMParser, fix loop 10
        val cases = listOf(
            "<h2><select><details><math></dd></main></h1><plaintext><br>" to true, // round 9 r:552
            "<h2><select><math></h1><plaintext><br>" to true,
            "<h2><select><math></h2><plaintext><br>" to true,
            "<div><select><math></div><plaintext><br>" to true,
            "<h2><details><math></h1><plaintext><br>" to false,
            "<h2><math></h1><plaintext><br>" to false,
            "<p><select><math></p><plaintext><br>" to false,
            "<select><h2><math></h2><script><!--</script><br>" to true,
            "<select><applet><svg></applet><script><!--</script><br>" to true,
            "<select><applet><dl><dd><svg><g><mtext></address></applet><script><!--</script><br>" to true, // round 10 r:3597
            "<p><select><math></p><script><!--</script><br>" to true,
            "<h2><select><math></h2><script><!--</script><br>" to false,
            // an implied <tbody>/<tr>, and </td> </caption> </template> through SVG/MathML (cmx round 10)
            "<table><tr><td><svg></tbody><style><!--</style><p>" to true,
            "<table><tr><td><math><mrow></tbody><textarea><!--</textarea><p>" to true,
            "<table><tr><td><svg><foreignObject><svg></td><style><!--</style><p>" to true,
            "<table><tr><td><math><mi><svg></td><style><!--</style><p>" to true,
            "<table><caption><svg><foreignObject><svg></caption><style><!--</style><p>" to true,
            "<template><math><annotation-xml encoding=text/html><svg></template><style><!--</style><p>" to true,
        )
        for ((doc, chrome) in cases) {
            val html = doc + evil
            val tree = HtmlAnchors.treeAnchors(html, 60).any { "evil.example" in it.first }
            if (chrome) assertTrue(tree, html)
            assertTrue("evil.example" in hosts(html), html) // the union judges it either way
        }
    }
}
