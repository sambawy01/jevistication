package dev.loupe.kit.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyReaderTest {
    private fun one(text: String): Pair<Long, String> = MoneyReader.find(text).single().let { it.minor to it.currency }

    @Test
    fun readsEgyptianPoundsWrittenEveryWay() {
        assertEquals(16500L to "EGP", one("Amount paid: EGP 165.00"))
        assertEquals(125000L to "EGP", one("Total amount paid: L.E. 1,250.00"))
        assertEquals(9900L to "EGP", one("Plan LE 99 a month"))
        assertEquals(35000L to "EGP", one("E£ 350"))
        assertEquals(35000L to "EGP", one("المبلغ: 350 جنيه"))
        assertEquals(6999L to "EGP", one("المبلغ المدفوع: ٦٩٫٩٩ ج.م"))
        assertEquals(22000L to "EGP", one("إجمالي المبلغ: ٢٢٠ جم"))
        assertEquals(150000L to "EGP", one("تم تحويل ١٬٥٠٠ جنيه"))
        assertEquals(4999L to "EGP", one("et5asam menha 49.99 geneh"))
    }

    @Test
    fun readsPoundsDollarsAndEuros() {
        assertEquals(99L to "USD", one("Total: $0.99"))
        assertEquals(999L to "GBP", one("You paid £9.99"))
        assertEquals(1250L to "EUR", one("€12.50 charged"))
        assertEquals(2000L to "GBP", one("20 جنيه إسترليني"))
        assertEquals(500L to "USD", one("US$ 5"))
    }

    @Test
    fun numbersThatAreNotMoneyAreIgnored() {
        for (t in listOf("Call 0100 123 4567", "رقم العملية: 88213345", "التاريخ: 05/06/2026", "Red 1000 plan",
                         "Your code 482913 expires", "SALE 50 today", "الرقم القومي: ٢٩٠٠١٠١٠١٢٣٤٥٦", "كود فوري: 9912-4455")) {
            assertEquals(emptyList(), MoneyReader.find(t), t)
        }
    }

    @Test
    fun theReceiptsAmountIsTheOneOnALabelledLine() {
        val text = "Subtotal EGP 150.00\nDelivery EGP 15.00\nTotal: EGP 165.00"
        assertEquals(16500L, MoneyReader.best(text)!!.minor)
        assertEquals(15000L, MoneyReader.best("Shoes EGP 150.00\nSocks EGP 15.00")!!.minor, "no label: the first amount")
        assertNull(MoneyReader.best("no amount here"))
    }

    @Test
    fun lineAtCutsTheLineVerbatim() {
        val text = "first\nالمبلغ: ٣٥٠ جنيه\nlast"
        val m = MoneyReader.find(text).single()
        assertEquals("المبلغ: ٣٥٠ جنيه", MoneyReader.lineAt(text, m.start))
        assertTrue(m.text.contains("٣٥٠"), "the match is the original text, digits as written")
    }
}
