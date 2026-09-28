package dev.loupe.kit.tracking

import dev.loupe.sources.common.ItemKind
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChargeExtractorTest {
    private val netflixMail = TrackingItems.email("n1", "2026-06-03", "Netflix <info@mailer.netflix.com>", "Your Netflix payment receipt",
        "Thanks for your payment. We've charged your card.\n\nAmount paid: EGP 165.00\nYour membership will renew on 3 July 2026.")

    @Test
    fun anEmailReceiptIsACharge() {
        val c = ChargeExtractor.of(netflixMail).single()
        assertEquals("Netflix", c.merchant)
        assertEquals(16500L, c.amountMinor)
        assertEquals("EGP", c.currency)
        assertEquals(LocalDate(2026, 6, 3), c.date)
        assertEquals("Amount paid: EGP 165.00", c.line)
        assertTrue(c.saysSubscription)
    }

    @Test
    fun photoReceiptsNameTheirMerchant() {
        val insta = TrackingItems.image("i1", "2026-06-01",
            "InstaPay\nتمت العملية بنجاح\nتم تحويل ١٬٥٠٠ جنيه\nالمستفيد: نادي الجزيرة\nالتاريخ: ٢٠٢٦/٠٦/٠١")
        val c = ChargeExtractor.of(insta).single()
        assertEquals("نادي الجزيرة", c.merchant)
        assertEquals(150000L, c.amountMinor)
        assertEquals(LocalDate(2026, 6, 1), c.date)
        val fawry = TrackingItems.image("f1", "2026-06-15",
            "فوري\nإيصال سداد\nالخدمة: أورنج - فاتورة موبايل\nإجمالي المبلغ: ٢٢٠ جم\nتم الدفع بنجاح يوم ١٥/٠٦/٢٠٢٦")
        assertEquals("Orange", ChargeExtractor.of(fawry).single().merchant)
        assertEquals(LocalDate(2026, 6, 15), ChargeExtractor.of(fawry).single().date)
    }

    @Test
    fun aPdfBillIsDatedByItsPaidLine() {
        val bill = TrackingItems.file("v1.pdf", "2026-06-21", ItemKind.PDF,
            "Vodafone Egypt\nBill period: 20/05/2026 - 19/06/2026\n\nTotal amount paid: L.E. 1,250.00\nPaid on 20/06/2026 by Vodafone Cash")
        val c = ChargeExtractor.of(bill).single()
        assertEquals("Vodafone", c.merchant)
        assertEquals(125000L, c.amountMinor)
        assertEquals(LocalDate(2026, 6, 20), c.date)
    }

    @Test
    fun aCalendarEventWithAnAmountIsACharge() {
        val gym = TrackingItems.event("g1", "2026-06-01", "Gym membership", "Paid the monthly membership: EGP 800")
        val c = ChargeExtractor.of(gym).single()
        assertEquals("Gym membership", c.merchant)
        assertEquals(80000L, c.amountMinor)
        assertEquals(LocalDate(2026, 6, 1), c.date)
    }

    @Test
    fun statementRowsAreChargesAndCreditsAreNot() {
        val shahid = ChargeExtractor.of(TrackingItems.csvRow("r1", "2026-06-10", "SHAHID VIP", 9999, "EGP")).single()
        assertEquals("Shahid", shahid.merchant)
        assertEquals(emptyList(), ChargeExtractor.of(TrackingItems.csvRow("r2", "2026-07-01", "SALARY TRANSFER", 1500000, "EGP", "credit")))
    }

    @Test
    fun theSameChargeInMailAndStatementIsOneWithBothReferences() {
        val row = TrackingItems.csvRow("r3", "2026-06-04", "NETFLIX.COM", 16500, "EGP")
        val all = ChargeExtractor.extract(listOf(row, netflixMail))
        val one = all.single()
        assertEquals(ItemKind.EMAIL, one.kind, "the receipt is kept; the statement row is a second reference")
        assertEquals(listOf(row.id), one.alsoSeenIn)
    }

    @Test
    fun twoMonthlyChargesStayTwo() {
        val july = TrackingItems.email("n2", "2026-07-03", "Netflix <info@mailer.netflix.com>", "Your Netflix payment receipt",
            "We've charged your card.\n\nAmount paid: EGP 165.00")
        assertEquals(2, ChargeExtractor.extract(listOf(netflixMail, july)).size)
    }

    @Test
    fun twoRowsOfOneStatementAreNotMerged() {
        val file = TrackingItems.file("talabat.csv", "2026-06-02", ItemKind.CSV,
            "talabat.csv\n\nDate,Merchant,Amount\n2026-06-01,Talabat,-120.00\n2026-06-01,Talabat,-120.00\n")
        assertEquals(2, ChargeExtractor.extract(listOf(file)).size)
    }

    @Test
    fun promotionsCreditsAndRefundsAreNotCharges() {
        val items = listOf(
            TrackingItems.email("p1", "2026-06-10", "Vodafone <offers@vodafone.com.eg>", "عرض خاص لك",
                "اشترك الآن في باقة فليكس ٧٠ بـ ١٢٠ جنيه شهرياً واستمتع بدقائق وإنترنت أكتر."),
            TrackingItems.email("p2", "2026-06-11", "StreamPlus <news@streamplus.example>", "Watch everything",
                "Subscribe now for just $9.99/month and watch everything."),
            TrackingItems.email("p3", "2026-09-01", "CIB <alerts@cibeg.example>", "Account credited",
                "Your account ending 4411 was credited with EGP 15,000.00 on 01/09/2026. Salary transfer."),
            TrackingItems.email("p4", "2026-08-02", "Uber <receipts@uber.example>", "Refund processed",
                "We have refunded $4.99 to your card for trip 8812."),
            TrackingItems.file("fawry-pending", "2026-09-20", ItemKind.TEXT,
                "فوري\nرقم مرجعي للدفع: 7788123\nالمبلغ المطلوب: ١٥٠ جنيه\nادفع قبل ٢٠٢٦/١٠/٠٥ من أي منفذ فوري"),
        )
        assertEquals(emptyList(), ChargeExtractor.extract(items))
    }
}
