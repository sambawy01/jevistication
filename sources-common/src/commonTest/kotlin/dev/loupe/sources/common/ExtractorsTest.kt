package dev.loupe.sources.common

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtractorsTest {
    private fun eml(s: String) = s.replace("\n", "\r\n").encodeToByteArray()

    @Test
    fun plainTextIsUtf8ElseWindows1252() {
        assertEquals("café", PlainText.decode("café".encodeToByteArray()))
        assertEquals("café €", PlainText.decode(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte(), 0x20, 0x80.toByte())))
        assertEquals("x", PlainText.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x78)))
        assertTrue(PlainText.looksBinary(byteArrayOf(1, 0, 2)))
    }

    @Test
    fun htmlTextKeepsEntitiesAndDropsScripts() {
        assertEquals("a & b\n\nc", HtmlText.toText("<script>x()</script><p>a &amp; b</p><div>c</div>"))
        assertEquals("😀 £", HtmlText.toText("&#x1F600; &pound;"))
        assertEquals(listOf("https://x.example/a", "http://y.example"), Links.find("""<a href="https://x.example/a">x</a> see http://y.example."""))
    }

    @Test
    fun quotedPrintableAndBase64() {
        assertEquals("café = ok long line", Charsets.latin1(MimeCodecs.quotedPrintable("caf=E9 =3D ok long=\r\n line".encodeToByteArray())))
        assertEquals("hello world", MimeCodecs.base64("aGVsbG8g\r\nd29ybGQ=".encodeToByteArray()).decodeToString())
        assertEquals("hi", MimeCodecs.base64("aGk".encodeToByteArray()).decodeToString())
    }

    @Test
    fun encodedWordsInHeaders() {
        assertEquals("Café receipt", MimeCodecs.decodeHeader("=?utf-8?Q?Caf=C3=A9_receipt?="))
        assertEquals("Grüße aus Lisboa", MimeCodecs.decodeHeader("=?ISO-8859-1?Q?Gr=FC=DFe?= =?utf-8?B?IGF1cyBMaXNib2E=?="))
        assertEquals("plain", MimeCodecs.decodeHeader("plain"))
    }

    @Test
    fun rfc5322Dates() {
        val d = MimeParser.parseDate("Mon, 21 Sep 2026 23:30:00 -0200")!!
        assertEquals("2026-09-22T01:30:00Z", d.toString())
        assertEquals("2026-01-05T09:00:00Z", MimeParser.parseDate("5 Jan 26 04:00 EST").toString())
        assertNull(MimeParser.parseDate("not a date"))
    }

    @Test
    fun addressesWithQuotesGroupsAndComments() {
        val boxes = MimeParser.mailboxes("\"Sample, Alex\" <alex@sample.example>, Team: bo@x.example, cy@y.example;, dee@z.example (Dee)")
        assertEquals(listOf("alex@sample.example", "bo@x.example", "cy@y.example", "dee@z.example"), boxes.map { it.address })
        assertEquals("Sample, Alex", boxes[0].name)
        assertEquals("Dee", boxes[3].name)
    }

    @Test
    fun multipartAlternativeReadsThePlainPartOnce() {
        val msg = eml(
            """
            From: Shop <Orders@Shop.example>
            To: a@b.example
            Date: Tue, 01 Sep 2026 10:00:00 +0000
            Subject: =?utf-8?B?WW91ciBvcmRlciDigJQgc2hpcHBlZA==?=
            Content-Type: multipart/mixed; boundary="outer"

            preamble
            --outer
            Content-Type: multipart/alternative; boundary=inner

            --inner
            Content-Type: text/plain; charset=iso-8859-1
            Content-Transfer-Encoding: quoted-printable

            Total =A312.50, see https://shop.example/o/1
            --inner
            Content-Type: text/html; charset=utf-8
            Content-Transfer-Encoding: base64

            PHA+VG90YWwgwqMxMi41MDwvcD4=
            --inner--
            --outer
            Content-Type: application/pdf; name="r.pdf"
            Content-Disposition: attachment; filename="receipt.pdf"
            Content-Transfer-Encoding: base64

            JVBERi0=
            --outer--
            epilogue
            """.trimIndent(),
        )
        val p = MimeParser.parseEmail(msg, TimeZone.UTC)
        assertEquals("Total £12.50, see https://shop.example/o/1", p.body)
        assertEquals("orders@shop.example", p.facts.fromAddress)
        assertEquals("Shop", p.facts.fromName)
        assertEquals("Your order — shipped", p.facts.subject)
        assertEquals(LocalDate(2026, 9, 1), p.facts.date)
        assertEquals(listOf("receipt.pdf"), p.facts.attachmentNames)
        assertEquals(listOf("https://shop.example/o/1"), p.facts.links)
        assertTrue(p.modelText().startsWith("From: Shop <orders@shop.example>\nTo: a@b.example\nDate: 2026-09-01\nSubject: Your order — shipped\nAttachments: receipt.pdf\n\nTotal"))
    }

    @Test
    fun htmlOnlyMailIsStripped() {
        val p = MimeParser.parseEmail(eml("From: x@y.example\nContent-Type: text/html; charset=utf-8\n\n<p>Hi <b>there</b></p><a href=\"https://a.example\">go</a>"), TimeZone.UTC)
        assertEquals("Hi there\ngo", p.body)
        assertFalse("<" in p.body)
        assertEquals(listOf("https://a.example"), p.facts.links)
    }

    @Test
    fun mboxSplitsOnSeparatorLinesOnly() {
        val mbox = "From a@x.example Mon Sep 21 06:30:00 2026\nSubject: one\n\nFrom here on, prices rise.\n\nFrom b@x.example Tue Sep 22 06:30:00 2026\nSubject: two\n\nbody\n"
        val parts = Mbox.split(mbox.encodeToByteArray()).map { it.decodeToString() }
        assertEquals(2, parts.size)
        assertEquals("Subject: one\n\nFrom here on, prices rise.\n\n", parts[0])
        assertEquals("Subject: two\n\nbody\n", parts[1])
    }
}
