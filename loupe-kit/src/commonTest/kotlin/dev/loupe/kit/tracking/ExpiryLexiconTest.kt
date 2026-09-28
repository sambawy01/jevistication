package dev.loupe.kit.tracking

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpiryLexiconTest {
    private fun expiry(t: String) = ExpiryLexicon.EXPIRY.containsMatchIn(TrackingText.matchForm(t))
    private fun promo(t: String) = ExpiryLexicon.PROMO.containsMatchIn(TrackingText.matchForm(t))

    @Test
    fun expiryWordsInEnglishArabicAndEgyptian() {
        for (t in listOf("Date of expiry 14 JAN 2031", "Valid until 15/05/2026", "This policy expires on 30/11/2026",
                         "4b. 12.03.2031", "البطاقة سارية حتى ٢٠٢٨/٠٣/١٤", "تاريخ الانتهاء: ١٢/١١/٢٠٢٦", "صالحة لغاية ٠٥/٠٢/٢٠٢٩",
                         "وينتهي العقد في ٣٠ يونيو ٢٠٢٧", "رخصة العربية بتخلص يوم 2026/12/01", "صالح لحد ٢٠٢٦/١٠/٠٥")) {
            assertTrue(expiry(t), t)
        }
        for (t in listOf("An Egyptian passport is valid for seven years", "You can renew it at any office", "يجب تجديد البطاقة")) {
            assertFalse(expiry(t), t)
        }
    }

    @Test
    fun promotionWords() {
        for (t in listOf("This offer expires 30/10/2026", "Flash sale: 30% off", "العرض ينتهي في", "تخفيضات نهاية الموسم")) {
            assertTrue(promo(t), t)
        }
        assertFalse(promo("This policy expires on 30/11/2026"))
    }

    @Test
    fun egyptianDocumentKinds() {
        assertEquals(DocumentKind.CAR_LICENCE, DocumentKinds.of("رخصة تسيير ملاكي"))
        assertEquals(DocumentKind.CAR_LICENCE, DocumentKinds.of("رخصة العربية بتخلص يوم"))
        assertEquals(DocumentKind.DRIVING_LICENCE, DocumentKinds.of("رخصة قيادة خاصة"))
        assertEquals(DocumentKind.DRIVING_LICENCE, DocumentKinds.of("Driving licence (scanned copy)"))
        assertEquals(DocumentKind.NATIONAL_ID, DocumentKinds.of("بطاقة تحقيق الشخصية"))
        assertEquals(DocumentKind.NATIONAL_ID, DocumentKinds.of("الرقم القومي: ٢٩٠٠١٠١٠١٢٣٤٥٦"))
        assertEquals(DocumentKind.PASSPORT, DocumentKinds.of("PASSPORT Type P"))
        assertEquals(DocumentKind.PASSPORT, DocumentKinds.of("جواز سفر"))
        assertEquals(DocumentKind.INSURANCE, DocumentKinds.of("Motor Insurance Policy"))
        assertEquals(DocumentKind.INSURANCE, DocumentKinds.of("وثيقة تأمين طبي"))
        assertEquals(DocumentKind.CONTRACT, DocumentKinds.of("عقد إيجار شقة سكنية"))
        assertEquals(DocumentKind.MEMBERSHIP, DocumentKinds.of("كارنيه عضوية"))
        assertEquals(DocumentKind.RESIDENCE, DocumentKinds.of("Residence permit"))
        assertNull(DocumentKinds.of("Read our privacy policy"))
    }

    @Test
    fun aLeaseWithNationalIdsAndADepositIsAContract() {
        val lease = "عقد إيجار شقة سكنية\nالطرف الأول: أحمد فؤاد، الرقم القومي: ٢٨٠٠٥٠٥٠١٢٣٤٥٦، محل الإقامة: المعادي\n" +
            "الطرف الثاني: محمد سامي، الرقم القومي: ٢٩٠٠١٠١٠١٢٣٤٥٦\nمبلغ التأمين: ١٦٬٠٠٠ جنيه\nينتهي العقد في ٣٠ يونيو ٢٠٢٧"
        assertEquals(DocumentKind.CONTRACT, DocumentKinds.of(lease))
        assertEquals(DocumentKind.CONTRACT, DocumentKinds.of("Tenancy agreement\nTenant national ID: 29001010123456\nSecurity deposit (insurance): EGP 16,000"))
        assertEquals(DocumentKind.NATIONAL_ID, DocumentKinds.of("بطاقة تحقيق الشخصية\nالمهنة: موظف بعقد"), "an ID card's own title first")
        assertEquals(DocumentKind.INSURANCE, DocumentKinds.of("وثيقة تأمين سيارة\nيخضع هذا العقد لشروط الوثيقة"), "a policy's own title first")
        assertEquals(DocumentKind.RESIDENCE, DocumentKinds.of("تصريح إقامة\nالغرض: عقد عمل"), "a residence permit's own title first")
    }

    @Test
    fun datesInArabicDigitsYearFirstAndArabicMonths() {
        val ymd = TrackingDates.find("البطاقة سارية حتى ٢٠٢٨/٠٣/١٤").single()
        assertEquals(LocalDate(2028, 3, 14), ymd.date)
        assertEquals("ymd-slash", ymd.pattern)
        assertEquals(LocalDate(2027, 6, 30), TrackingDates.find("ينتهي العقد في ٣٠ يونيو ٢٠٢٧").single().date)
        assertEquals(LocalDate(2031, 1, 14), TrackingDates.find("Date of expiry 14 JAN 2031").single().date)
        val dmy = TrackingDates.find("تاريخ الانتهاء: ١٢/١١/٢٠٢٦").single()
        assertEquals(LocalDate(2026, 11, 12), dmy.date)
        assertTrue(dmy.ambiguous)
        assertEquals(listOf(LocalDate(2021, 3, 15), LocalDate(2028, 3, 14)),
                     TrackingDates.find("Issued 2021/03/15, expires 2028/03/14").map { it.date })
    }
}
