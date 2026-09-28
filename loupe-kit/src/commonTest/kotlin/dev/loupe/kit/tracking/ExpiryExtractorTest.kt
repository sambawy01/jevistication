package dev.loupe.kit.tracking

import dev.loupe.engine.ValidityRule
import dev.loupe.sources.common.ItemKind
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpiryExtractorTest {
    private val today = LocalDate(2026, 9, 28)
    private val rule = ValidityRule("six months of validity (e.g. Schengen passports)", 6)

    @Test
    fun anEgyptianNationalIdOnThePhotos() {
        val id = TrackingItems.image("id", "2026-03-02",
            "جمهورية مصر العربية\nبطاقة تحقيق الشخصية\nتاريخ الإصدار: ٢٠٢١/٠٣/١٥\nالبطاقة سارية حتى ٢٠٢٨/٠٣/١٤")
        val f = ExpiryExtractor.of(id, today, rule)!!
        assertEquals(LocalDate(2028, 3, 14), f.expiry)
        assertEquals(DocumentKind.NATIONAL_ID, f.kind)
        assertEquals("البطاقة سارية حتى ٢٠٢٨/٠٣/١٤", f.line)
        assertFalse(f.breachesRule)
    }

    @Test
    fun theDateAfterTheExpiryWordWinsOverTheLatest() {
        val policy = TrackingItems.file("policy.pdf", "2025-12-01", ItemKind.PDF,
            "Motor Insurance Policy\nPeriod of insurance: 01/12/2025 to 30/11/2026\nThis policy expires on 30/11/2026.\nNext review 2027/01/15")
        assertEquals(LocalDate(2026, 11, 30), ExpiryExtractor.of(policy, today, rule)!!.expiry)
    }

    @Test
    fun theTimelineKeepsEveryDateNear() {
        val passport = TrackingItems.image("pp", "2021-01-15", "PASSPORT\nDate of issue 15 JAN 2021\nDate of expiry 14 JAN 2031")
        val old = TrackingItems.file("licence.txt", "2016-05-15", ItemKind.TEXT, "Driving licence\nValid until 15/05/2026")
        val found = ExpiryExtractor.find(listOf(passport, old), today, rule)
        assertEquals(listOf("files:licence.txt", "photos:pp"), found.map { it.item.id }, "overdue first, years away kept")
        assertTrue(found[0].daysRemaining < 0)
        assertTrue(found[0].breachesRule, "the six-month rule stays as a highlight")
        assertFalse(found[1].breachesRule)
        assertTrue(found[1].daysRemaining > 366, "no one-year cut any more")
    }

    @Test
    fun cardEndingWordsOnAReceiptAreNotAnExpiry() {
        val receipt = TrackingItems.email("sp", "2026-06-12", "Spotify <no-reply@spotify.com>", "إيصال اشتراك Spotify Premium",
            "تم تجديد اشتراكك.\nالمبلغ المدفوع: ٦٩٫٩٩ ج.م\nتاريخ الدفع: ١٢/٠٦/٢٠٢٦\nطريقة الدفع: ماستركارد تنتهي بـ ٧٧٢٠")
        assertNull(ExpiryExtractor.of(receipt, today, rule))
    }

    @Test
    fun anOfferThatExpiresIsNotADocument() {
        val offer = TrackingItems.email("of", "2026-09-20", "Noon <deals@noon.example>", "Flash sale",
            "Flash sale! 30% off electronics.\nThis offer expires 30/10/2026. Use code SAVE30.")
        assertNull(ExpiryExtractor.of(offer, today, rule))
        val policyOffer = TrackingItems.file("renew.pdf", "2026-09-01", ItemKind.PDF,
            "Special renewal offer for your insurance policy\nYour policy expires on 30/11/2026")
        assertEquals(DocumentKind.INSURANCE, ExpiryExtractor.of(policyOffer, today, rule)!!.kind, "a document with an offer stays")
    }

    @Test
    fun anExpiryWordWithoutADateNearIsNothingUnlessTheDocumentIsKnown() {
        val otp = TrackingItems.file("otp.txt", "2026-09-27", ItemKind.TEXT, "Your code 482913 expires in 10 minutes. Do not share this code with anyone; your bank will never ask for it.\n\nSent 2026/09/27")
        assertNull(ExpiryExtractor.of(otp, today, rule))
        val lease = TrackingItems.file("lease.pdf", "2025-07-01", ItemKind.PDF,
            "Tenancy agreement\nThe tenancy expires at the end of the term agreed by both parties below, as signed.\nTerm: 01/07/2025 - 30/06/2027")
        assertEquals(LocalDate(2027, 6, 30), ExpiryExtractor.of(lease, today, rule)!!.expiry, "a known document takes its latest date")
    }

    @Test
    fun buckets() {
        assertEquals(ExpiryBucket.OVERDUE, ExpiryBucket.of(-1))
        assertEquals(ExpiryBucket.WEEK, ExpiryBucket.of(0))
        assertEquals(ExpiryBucket.WEEK, ExpiryBucket.of(7))
        assertEquals(ExpiryBucket.MONTH, ExpiryBucket.of(8))
        assertEquals(ExpiryBucket.MONTH, ExpiryBucket.of(30))
        assertEquals(ExpiryBucket.LATER, ExpiryBucket.of(31))
    }

    @Test
    fun documentsExpiredMoreThanAYearAgoAreOlder() {
        assertEquals(ExpiryBucket.OVERDUE, ExpiryBucket.of(-1))
        assertEquals(ExpiryBucket.OVERDUE, ExpiryBucket.of(-365))
        assertEquals(ExpiryBucket.OLDER, ExpiryBucket.of(-366))
        assertEquals(ExpiryBucket.OLDER, ExpiryBucket.of(-5000))

        val licence = TrackingItems.file("licence-old.txt", "2024-05-01", ItemKind.TEXT,
            "Driving licence\nValid until 15/05/2024")
        val found = ExpiryExtractor.find(listOf(licence), today, rule)
        assertEquals(1, found.size, "the timeline keeps it; it is not filtered out")
        val f = found[0]
        assertEquals(LocalDate(2024, 5, 15), f.expiry)
        assertEquals(ExpiryBucket.OLDER, ExpiryBucket.of(f.daysRemaining))
        assertTrue(f.breachesRule)
    }
}
