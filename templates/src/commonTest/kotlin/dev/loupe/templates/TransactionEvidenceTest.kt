package dev.loupe.templates

import dev.loupe.templates.TransactionEvidence.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionEvidenceTest {
    private fun judgment(templateId: String): UserJudgment {
        val r = TemplateLibrary.byId(templateId)!!.instantiate("j-$templateId")
        return (r as Template.InstantiateResult.Created).judgment
    }

    @Test
    fun strollerProductPageIsNotAReceiptByRule() {
        assertEquals(Verdict.LISTING, TransactionEvidence.assess(Fixtures.STROLLER_PAGE))
        assertEquals("not a receipt", TransactionEvidence.ruleAnswer(judgment("is-receipt"), Fixtures.STROLLER_PAGE))
        // The whole receipt family answers its own negative option.
        assertEquals("not needed for tax", TransactionEvidence.ruleAnswer(judgment("tax-receipt"), Fixtures.STROLLER_PAGE))
        assertEquals("not warranty proof", TransactionEvidence.ruleAnswer(judgment("warranty-proof"), Fixtures.STROLLER_PAGE))
        assertEquals("no refund issued", TransactionEvidence.ruleAnswer(judgment("refund-issued"), Fixtures.STROLLER_PAGE))
        assertEquals("nothing left to pay", TransactionEvidence.ruleAnswer(judgment("bill-unpaid"), Fixtures.STROLLER_PAGE))
        assertEquals("not a purchase", TransactionEvidence.ruleAnswer(judgment("receipt-kind"), Fixtures.STROLLER_PAGE))
    }

    @Test
    fun realReceiptsReachTheModel() {
        val j = judgment("is-receipt")
        for (text in listOf(Fixtures.SHOP_RECEIPT, Fixtures.ORDER_EMAIL, Fixtures.ARABIC_RECEIPT, Fixtures.INVOICE_DUE)) {
            assertEquals(Verdict.TRANSACTION, TransactionEvidence.assess(text), text)
            assertNull(TransactionEvidence.ruleAnswer(j, text))
        }
    }

    @Test
    fun arabicListingIsNegativeAndArabicReceiptWithDiacriticsDefers() {
        assertEquals(Verdict.LISTING, TransactionEvidence.assess(Fixtures.ARABIC_LISTING))
        // Hamza and taa-marbuta variants and harakat still match.
        assertEquals(Verdict.TRANSACTION, TransactionEvidence.assess("إيصَالُ رقم ٤٤١ — المبلغ المدفوع: ١٢٠ ريال"))
        assertEquals(Verdict.TRANSACTION, TransactionEvidence.assess("فاتورة ضريبية مبسطة"))
    }

    @Test
    fun weakHintsWithoutAListingStillReachTheModel() {
        // A bare total, no receipt wording, no shop furniture: the model decides.
        assertEquals(Verdict.WEAK, TransactionEvidence.assess("Cafe Luna\nFlat white 3.20\nTotal 3.20"))
        assertNull(TransactionEvidence.ruleAnswer(judgment("is-receipt"), "Cafe Luna\nFlat white 3.20\nTotal 3.20"))
    }

    @Test
    fun documentsWithNothingAboutMoneyAreNegative() {
        assertEquals(Verdict.NONE, TransactionEvidence.assess("Minutes of the residents' meeting, 3 March. Present: A, B, C."))
        assertEquals("not a receipt", TransactionEvidence.ruleAnswer(judgment("is-receipt"), "Holiday packing list: sunscreen, hats"))
    }

    @Test
    fun ungatedJudgmentsAndBlankTextAreLeftAlone() {
        assertNull(TransactionEvidence.ruleAnswer(judgment("is-receipt"), "  "))
        val other = judgment("is-receipt").copy(templateId = null, mechanical = null)
        assertNull(TransactionEvidence.ruleAnswer(other, Fixtures.STROLLER_PAGE))
        // An old judgment saved before the gate (no mechanical field) is still gated by its template.
        assertTrue(TransactionEvidence.applies(judgment("is-receipt").copy(mechanical = null)))
    }

    @Test
    fun receiptFamilyTemplatesDeclareTheGate() {
        for (id in TransactionEvidence.GATED_TEMPLATES) {
            assertEquals(MechanicalCheck.NO_TRANSACTION_EVIDENCE, TemplateLibrary.byId(id)!!.mechanical, id)
        }
    }

    @Test
    fun templateExamplesAgreeWithTheGate() {
        // A worked "yes" example must never be answered "no" by the rule.
        for (id in TransactionEvidence.GATED_TEMPLATES) {
            val t = TemplateLibrary.byId(id)!!
            val j = judgment(id)
            val negative = TransactionEvidence.ruleAnswer(j, "nothing here at all")
            for (e in t.examples) {
                val ruled = TransactionEvidence.ruleAnswer(j, e.text) ?: continue
                assertEquals(negative, ruled)
                assertEquals(e.answer, ruled, "$id: example '${e.text}' is '${e.answer}' but the rule says '$ruled'")
            }
        }
    }
}

/** Fixtures modelled on what PDFKit's `doc.string` returns for each kind of document. */
object Fixtures {
    /** The owner's case: a shop's product page saved to Files as a PDF ("File: …" is what Loupe prefixes). */
    const val STROLLER_PAGE = """File: Bugaboo Donkey 5 Mono complete stroller.pdf

Bugaboo Donkey 5 Mono complete stroller
Black / Grey Melange
★★★★★ 4.8 (212 reviews)
£1,199.00
Pay in 3 interest-free payments of £399.67 with Klarna.
Colour: Black  Select colour
Add to basket
Add to wishlist
In stock – Free delivery on orders over £50
Product details
Converts from a mono to a side-by-side duo stroller in 3 easy steps. Comfortable for your
child from birth up to 22 kg. 2-year warranty, extendable when you register.
Specifications
Weight 11.2 kg. Folded 61 x 60 x 38 cm.
Customer reviews
Write a review
You may also like
Bugaboo Butterfly £399.00  Bugaboo Fox 5 £1,099.00
"""

    const val SHOP_RECEIPT = """File: receipt-2026-08-14.pdf

John Lewis & Partners, Oxford Street
Receipt No. 8841-2210-77
14/08/2026 13:42
Bugaboo Donkey 5 Mono      £1,199.00
TOTAL                      £1,199.00
Paid by VISA ************4412
Thank you for shopping with us
"""

    const val ORDER_EMAIL = """From: orders@example-shop.com
Subject: Order confirmation #A1029384

Thank you for your order. Order number A1029384. Amount charged £59.99 to your card ending in 1111."""

    const val ARABIC_RECEIPT = """File: فاتورة-متجر.pdf

متجر النور
فاتورة ضريبية مبسطة
رقم الفاتورة: 20931
التاريخ: 2026/08/14
حليب ٢ × ٦٫٥٠
الإجمالي: ١٣٫٠٠ ريال
طريقة الدفع: مدى
شكراً لتسوقكم"""

    const val ARABIC_LISTING = """File: عربة أطفال.pdf

عربة أطفال بوغابو دونكي
٤٫٨ (٢١٢ تقييمات)
٤٬٩٩٩ ريال
أضف إلى السلة
اشتر الآن
متوفر في المخزون — توصيل مجاني
تفاصيل المنتج
مواصفات"""

    const val INVOICE_DUE = "Invoice INV-2207 from Brightside Plumbing. Amount due £180.00 by 30 September 2026."
}
