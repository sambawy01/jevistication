package dev.loupe.kit.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackingTextTest {
    @Test
    fun foldsArabicAndPersianDigitsKeepingTheLength() {
        assertEquals("2028/03/14", TrackingText.foldDigits("٢٠٢٨/٠٣/١٤"))
        assertEquals("1403", TrackingText.foldDigits("۱۴۰۳"))
        val s = "المبلغ: ٦٩٫٩٩ ج.م"
        assertEquals(s.length, TrackingText.foldDigits(s).length)
    }

    @Test
    fun matchFormUnifiesArabicSpellingsCaseAndDigits() {
        assertEquals("صالحه حتي", TrackingText.matchForm("صالحة حتى"))
        assertEquals("اقامه", TrackingText.matchForm("إقامة"))
        assertEquals("تامين", TrackingText.matchForm("تأمين"))
        assertEquals("netflix 165", TrackingText.matchForm("NETFLIX ١٦٥"))
        val s = "تأمين إيجار آخر"
        assertEquals(s.length, TrackingText.matchForm(s).length)
    }

    @Test
    fun wordsMatchWholeWordsAndArabicAffixes() {
        val r = TrackingText.words(listOf("paid", "تم الدفع", "مبلغ", "re:expir[a-z]*"))
        assertTrue(r.containsMatchIn(TrackingText.matchForm("Amount paid: EGP 165")))
        assertFalse(r.containsMatchIn(TrackingText.matchForm("This invoice is unpaid")))
        assertTrue(r.containsMatchIn(TrackingText.matchForm("وتم الدفع بنجاح")))
        assertTrue(r.containsMatchIn(TrackingText.matchForm("تم تحويل بمبلغ ٣٥٠ جنيه")))
        assertFalse(r.containsMatchIn(TrackingText.matchForm("مبلغين")))
        assertTrue(r.containsMatchIn(TrackingText.matchForm("Date of EXPIRY")))
    }

    @Test
    fun phraseEscapesAndAllowsAnySpacing() {
        assertEquals("osn\\+", TrackingText.phrase("OSN+"))
        assertTrue(Regex(TrackingText.phrase("valid until")).containsMatchIn("valid   until"))
    }
}
