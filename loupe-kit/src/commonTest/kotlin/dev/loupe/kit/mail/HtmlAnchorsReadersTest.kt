package dev.loupe.kit.mail

import dev.loupe.kit.site.Hosts
import dev.loupe.kit.site.ParsedUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fix loop 11 (round-11 review): what a reader can open besides <a>/<area> (a form's action, a submit
 * button's, an SVG <animate>/<set> href, a frame, an embed, an object, a document framed by srcdoc or
 * data:text/html), all read by the tree-aware reading so they are verified; the visible text the pattern
 * fallback reads (SVG/MathML content, split URLs); and the link cap, which floods cannot fill.
 */
class HtmlAnchorsReadersTest {
    private val evil = "https://paypa1-secure.xyz/login"
    private val sender = "\"PayPal\" <service@account-notice-center.com>"

    private fun verified(html: String) = MailMessage.anchors(html).filter { it.second != HtmlAnchors.UNVERIFIED }
    private fun hostsOf(l: List<Pair<String, String>>) = l.mapNotNull { ParsedUrl.parse(Hosts.linkUrl(it.first))?.host }.toSet()
    private fun judged(html: String) = Phishing.assess(sender, "", links = MailMessage.anchors(html))

    @Test
    fun `forms and submit buttons are verified links with their label as text`() {
        val cases = mapOf(
            "<form action=\"$evil\"><button>Sign in to PayPal</button></form>" to "Sign in to PayPal",
            "<form><button formaction=\"$evil\">Sign in to PayPal</button></form>" to "Sign in to PayPal",
            "<form><input type=submit formaction=\"$evil\" value=\"Sign in to PayPal\"></form>" to "Sign in to PayPal",
            "<form action=\"$evil\"><input type=submit value=\"Sign in to PayPal\"></form>" to "Sign in to PayPal",
            "<form action=\"$evil\"><input type=image alt=\"Sign in to PayPal\" src=x.png></form>" to "Sign in to PayPal",
        )
        for ((html, label) in cases) {
            val v = verified(html)
            assertTrue((evil to label) in v, "$html: $v")
            val j = judged(html)
            assertTrue("link_lookalike_brand" in j.codes && "link_suspicious_tld" in j.codes && "link_brand_text" in j.codes, "$html: ${j.codes}")
        }
        // the form itself, with no text: every weight applies (a TLD note too)
        assertTrue((evil to "") in verified("<form action=\"$evil\" method=post>Password <input type=password></form>"))
        // a type=button or reset button submits nothing
        assertTrue(verified("<form><button type=button>Sign in to PayPal</button></form>").isEmpty())
        // text mismatch through a button label
        val m = judged("<form action=\"https://account-review.top/s\"><button>https://www.paypal.com/signin</button></form>")
        assertTrue("link_text_mismatch" in m.codes, "${m.codes}")
    }

    @Test
    fun `an SVG animate or set that gives an a its href is a verified link`() {
        for (html in listOf(
            "<svg><a><animate attributeName=\"href\" to=\"$evil\"/><text>Sign in to PayPal</text></a></svg>",
            "<svg><a><set attributeName=\"href\" to=\"$evil\"/><text>Sign in to PayPal</text></a></svg>",
            "<svg><a><animate attributeName=\"xlink:href\" values=\"https://www.paypal.com/;$evil\"/><text>Sign in to PayPal</text></a></svg>",
            "<svg><a><animate attributeName=\"href\" from=\"$evil\" to=\"#\"/><text>Sign in to PayPal</text></a></svg>",
        )) {
            val v = verified(html)
            assertTrue("paypa1-secure.xyz" in hostsOf(v), "$html: $v")
            assertTrue(v.any { it.second.contains("Sign in to PayPal") }, "$html: $v")
        }
        // an animation of another attribute is not a link
        assertTrue(verified("<svg><a><animate attributeName=\"fill\" to=\"https://x.example/\"/></a></svg>").isEmpty())
    }

    @Test
    fun `iframe srcdoc and data html are read in turn with their own link text`() {
        val plain = verified("<iframe srcdoc=\"<a href=$evil target=_top>Sign in to PayPal</a>\"></iframe>")
        assertTrue((evil to "Sign in to PayPal") in plain, "$plain")
        val entities = verified("<iframe srcdoc=\"&lt;a href=&quot;$evil&quot;&gt;Sign in to PayPal&lt;/a&gt;\"></iframe>")
        assertTrue((evil to "Sign in to PayPal") in entities, "$entities")
        val data = verified("<object data=\"data:text/html,%3Ca%20href%3D%22$evil%22%3ESign in to PayPal%3C%2Fa%3E\"></object>")
        assertTrue((evil to "Sign in to PayPal") in data, "$data")
        val b64 = verified("<iframe src=\"data:text/html;base64,PGEgaHJlZj0iaHR0cHM6Ly9wYXlwYTEtc2VjdXJlLnh5ei9sb2dpbiI+U2lnbiBpbjwvYT4=\"></iframe>")
        assertTrue((evil to "Sign in") in b64, "$b64")
        // nested three deep is read; the fourth level is not (bounded)
        fun wrap(inner: String) = "<iframe srcdoc=\"" + inner.replace("&", "&amp;").replace("\"", "&quot;") + "\"></iframe>"
        val a = "<a href=\"$evil\">x</a>"
        assertTrue("paypa1-secure.xyz" in hostsOf(verified(wrap(wrap(wrap(a))))))
        assertFalse("paypa1-secure.xyz" in hostsOf(verified(wrap(wrap(wrap(wrap(a)))))))
    }

    @Test
    fun `frames embeds and objects are verified links without text`() {
        for (html in listOf(
            "<iframe src=\"$evil\"></iframe>", "<frameset><frame src=\"$evil\"></frameset>",
            "<embed src=\"$evil\">", "<object data=\"$evil\"></object>",
        )) {
            assertTrue((evil to "") in verified(html), html)
            assertTrue("link_suspicious_tld" in judged(html).codes, html)
            assertTrue(evil !in HtmlAnchors.nonNavigableUrls(html), html)
        }
        // images, posters and stylesheets stay non-navigable
        val img = "<img src=\"https://cdn.example/a.png\"><video poster=\"https://cdn.example/p.jpg\"></video><link rel=stylesheet href=\"https://cdn.example/s.css\">"
        assertEquals(setOf("https://cdn.example/a.png", "https://cdn.example/p.jpg", "https://cdn.example/s.css"), HtmlAnchors.nonNavigableUrls(img))
        assertTrue(verified(img).isEmpty())
    }

    @Test
    fun `visible text follows SVG and MathML and joins what inline tags split`() {
        assertTrue(evil in HtmlAnchors.visibleText("<math><style><p>$evil</p></style></math>"))
        assertTrue(evil in HtmlAnchors.visibleText("<svg><style><p>$evil</p></style></svg>"))
        assertFalse(evil in HtmlAnchors.visibleText("<svg><style>$evil</style></svg>"), "SVG style text is not shown")
        for (split in listOf("https://pay<b></b>pa1-secure.xyz/login", "https://pay<span>pa1</span>-secure.xyz/login",
                "https://pay<!-- x -->pa1-secure.xyz/login", "https://pay<wbr>pa1-secure.xyz/login")) {
            assertTrue(evil in HtmlAnchors.visibleText(split), split)
        }
        // blocks and line breaks separate words
        assertEquals(listOf("a.com", "b.com"), HtmlAnchors.visibleText("<p>a.com</p><p>b.com</p>").split(Regex("\\s+")).filter { it.isNotEmpty() })
        assertFalse("x.example" in HtmlAnchors.visibleText("<textarea>x.example</textarea><template>x.example</template><style>x.example</style>"))
    }

    @Test
    fun `a flood of links cannot push the real one out`() {
        val flood = (0 until 70).joinToString("") { "<a href=\"https://n$it.example/\">n</a>" }
        val real = "<a href=\"$evil\">Sign in to PayPal</a>"
        for (hider in listOf("<!-- $flood -->", "<!--[if mso]>$flood<![endif]-->", "<textarea>$flood</textarea>",
                "<div title='$flood'></div>", "<style>/*$flood*/</style>", "<div style=\"display:none\">$flood</div>")) {
            val j = judged(hider + real)
            assertTrue("link_lookalike_brand" in j.codes && "link_brand_text" in j.codes, "$hider: ${j.codes}")
        }
        // verified links come first; an unverified one whose host is judged already is dropped
        val judgedLinks = Phishing.judgedLinks(listOf("https://a.example/1" to HtmlAnchors.UNVERIFIED, evil to "x", "https://a.example/2" to "y", "https://a.example/3" to HtmlAnchors.UNVERIFIED))
        assertEquals(listOf(evil to "x", "https://a.example/2" to "y"), judgedLinks)
    }
}
