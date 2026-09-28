package dev.loupe.templates

import dev.loupe.templates.TransactionEvidence.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The 2026-09-28 launch hang (0x8BADF00D): the gate's look-behinds ran over every ledger row's full
 * text on the main thread. The rewrite must give **the same verdict** as the shipped gate on every
 * text (checked here against [TransactionEvidenceReference] over each phrase with every kind of
 * neighbouring character, the fixtures, the templates' worked examples and phrase pairs) and must
 * stay fast on a 200 KB text, on the JVM and on Kotlin/Native (the simulator runs this file too).
 */
class TransactionEvidenceSpeedTest {
    private val phrases = listOf(
        "receipt", "receipts", "e-receipt", "ereceipt", "tax invoice", "invoice", "proof of purchase", "payment receipt",
        "order confirmation", "order confirmed", "thanks for your order", "thank you for payment", "purchase confirmation",
        "amount paid", "total paid", "paid in full", "payment received", "amount charged", "you've been charged", "was charged",
        "charged to your", "paid by", "paid using", "payment method", "card ending", "card ending in", "ending in 4412",
        "auth code", "authorisation code", "authorization code", "transaction id", "amount due", "balance due", "due date",
        "please pay", "overdue", "final notice", "direct debit", "refunded", "refund of", "refund issued", "reimbursed",
        "donation", "gift aid", "order no. a1029", "order number a1029384", "invoice #inv-2207", "receipt 8841-2210",
        "booking ref ab12c", "inv12345", "transaction: 9x77", "confirmation 12ab", "**** 4412", "xx 1234", "••••4412",
        "************44121", "فاتورة", "إيصال", "وصل دفع", "رقم الطلب", "رقم فاتورة", "تم الدفع", "المبلغ المدفوع", "تبرع",
        "reçu", "facture", "rechnung", "bestellbestätigung", "recibo", "paid", "payment", "total", "subtotal", "order",
        "purchase", "purchased", "vat", "tax", "visa", "mastercard", "amex", "apple pay", "google pay", "paypal", "mada",
        "stc pay", "bill", "statement", "warranty", "guarantee", "serial", "refund", "refunds", "return", "returns",
        "الإجمالي", "المجموع", "مدفوع", "الدفع", "ضريبة", "شراء", "طلب", "فيزا", "مدى", "ضمان", "add to cart",
        "add to bag", "add to wishlist", "add to wish list", "buy now", "buy it now", "shop now", "order now", "in stock",
        "out of stock", "only 3 left", "free delivery", "free returns", "customer reviews", "212 reviews", "1 review",
        "write a review", "rated 4", "compare at", "compare price", "select colour", "select color", "choose your size",
        "you may also like", "frequently bought", "product details", "product description", "specifications", "sku",
        "أضف إلى السلة", "اضافة للسلة", "اشتر الآن", "تسوق الآن", "متوفر في المخزون", "غير متوفر", "تقييمات", "شحن مجاني",
        "تفاصيل المنتج", "ajouter au panier", "en stock", "in den warenkorb", "jetzt kaufen", "añadir al carrito",
        "total 12.40", "total: £3", "المجموع ٥", "Receipt", "INVOICE", "Add To Basket", "ĀDD", "12 Reviews",
        // Every other alternative of every pattern, so each anchor list is shown to cover its pattern.
        "sales receipt", "thank you for your purchase", "thanks for donation", "payment successful", "payment confirmed",
        "you have been charged", "paid with", "paid via", "paid in fullx", "balance due", "payment due", "total due",
        "refund processed", "refund has been", "refund amount", "refund to your", "refunds", "order confirmedx",
        "وصل استلام", "سند قبض", "إثبات الشراء", "تأكيد الطلب", "رقم طلب", "تم دفع", "إجمالي المدفوع", "تم الخصم",
        "طريقة الدفع", "المبلغ المستحق", "تاريخ الاستحقاق", "تم الاسترداد", "مبلغ مسترد", "quittung", "kassenbon",
        "factura", "comprobante", "add to trolley", "add to basket", "add to wishlist", "compare price", "choose size",
        "choose colour", "select size", "free shipping", "rated 5", "only 12 left", "إضافة إلى السلة", "أضف للسلة",
        "اشتري الآن", "متوفر", "نفذت الكمية", "مراجعات", "اكتب تقييم", "توصيل مجاني", "مواصفات", "acheter maintenant",
        "auf lager", "comprar ahora", "en existencia", "total 12", "اجمالي 5", "إجمالي: ١٢", "x•4412", "•*1234",
        "Order: 12-ab", "ORDER#99x", "orderno123", "inv-00129", "booking no. 7ab", "xxx 12345", "XX4412",
    )

    /** Every kind of neighbour a boundary can see: none, space, Latin, digit, accented, Arabic, punctuation. */
    private val neighbours = listOf(
        "", " ", "a", "z", "Q", "1", "é", "ɏ", "ǿ", "Ā", "ب", "٣", "-", "\n", ".", "#", "_", "x", "*", "•", "'", "£", "İ", "ـ", "ً",
    )

    @Test
    fun rewrittenGateAgreesWithTheShippedOneOnEveryPhraseAndNeighbour() {
        var checked = 0
        for (p in phrases) for (before in neighbours) for (after in neighbours) {
            for (text in listOf(before + p + after, "x $before$p$after y")) {
                assertEquals(TransactionEvidenceReference.assess(text), TransactionEvidence.assess(text), "on '$text'")
                checked++
            }
        }
        assertTrue(checked > 50_000, "checked $checked texts")
    }

    @Test
    fun rewrittenGateAgreesOnFixturesExamplesAndPhrasePairs() {
        val texts = buildList {
            addAll(listOf(Fixtures.STROLLER_PAGE, Fixtures.SHOP_RECEIPT, Fixtures.ORDER_EMAIL, Fixtures.ARABIC_RECEIPT,
                          Fixtures.ARABIC_LISTING, Fixtures.INVOICE_DUE, "Cafe Luna\nFlat white 3.20\nTotal 3.20",
                          "Minutes of the residents' meeting, 3 March. Present: A, B, C.", "Holiday packing list: sunscreen, hats",
                          "إيصَالُ رقم ٤٤١ — المبلغ المدفوع: ١٢٠ ريال", "فاتورة ضريبية مبسطة", "", "   "))
            TemplateLibrary.ALL.forEach { t -> t.examples.forEach { add(it.text) } }
            for (a in phrases) for (b in phrases.filterIndexed { i, _ -> i % 7 == 0 }) add("$a … $b")
        }
        for (text in texts) assertEquals(TransactionEvidenceReference.assess(text), TransactionEvidence.assess(text), "on '$text'")
    }

    @Test
    fun rewrittenGateAgreesOnRandomTextsBuiltFromItsOwnPieces() {
        val pieces = phrases.flatMap { it.split(" ") } + neighbours + listOf(" ", " ", "  ", "\n", "12", "4412", "£9.99", ":", "#")
        val random = kotlin.random.Random(28)
        repeat(20_000) {
            val text = buildString { repeat(random.nextInt(1, 12)) { append(pieces[random.nextInt(pieces.size)]); if (random.nextBoolean()) append(' ') } }
            assertEquals(TransactionEvidenceReference.assess(text), TransactionEvidence.assess(text), "on '$text'")
        }
    }

    @Test
    fun theOnePassNormaliseEqualsNormaliseOnEveryCharacterAndPhrase() {
        for (code in 0..0xFFFF) {
            val c = code.toChar()
            if (c.isSurrogate()) continue
            val one = c.toString()
            assertEquals(TransactionEvidence.normalise(one), TransactionEvidence.normaliseChars(one, 1).concatToString(), "U+${code.toString(16)}")
        }
        for (p in phrases + Fixtures.STROLLER_PAGE + Fixtures.ARABIC_RECEIPT + "İNVOICE İade ẞ Ǆ") {
            assertEquals(TransactionEvidence.normalise(p), TransactionEvidence.normaliseChars(p, p.length).concatToString(), p)
        }
    }

    @Test
    fun theGateReadsOnlyTheModelsTextBudget() {
        val filler = "Minutes of the residents' meeting. Present: A, B, C. The lift is fixed. ".repeat(80)
        assertTrue(filler.length > TransactionEvidence.SCAN_CHARS)
        // Evidence past the budget is evidence the model is never shown either.
        assertEquals(Verdict.NONE, TransactionEvidence.assess(filler + "Amount paid £40"))
        assertEquals(Verdict.TRANSACTION, TransactionEvidence.assess(filler + "Amount paid £40", maxChars = Int.MAX_VALUE))
        assertEquals(Verdict.TRANSACTION, TransactionEvidence.assess("Amount paid £40 " + filler))
        // A budget from Model settings (text_chars) is honoured.
        assertEquals(Verdict.TRANSACTION, TransactionEvidence.assess(filler + "Amount paid £40", maxChars = filler.length + 20))
    }

    @Test
    fun assessOnA200KbTextIsFast() {
        // The worst case: nothing matches, so every pattern reads to the end of what it is given.
        val para = "Minutes of the residents' meeting on 3 March 2026. Present: A. Byrne, C. Dale, E. Fox. " +
            "The lift on the east stair is fixed; the bins move to Thursdays. محضر اجتماع السكان في ٣ مارس. "
        val text = para.repeat(200_000 / para.length + 1)
        assertTrue(text.length >= 200_000)
        repeat(3) { TransactionEvidence.assess(text) }   // warm up
        val runs = 10
        val capped = time { repeat(runs) { assertEquals(Verdict.NONE, TransactionEvidence.assess(text)) } } / runs
        val whole = time { assertEquals(Verdict.NONE, TransactionEvidence.assess(text, maxChars = Int.MAX_VALUE)) }
        val oldOn20Kb = time { assertEquals(Verdict.NONE, TransactionEvidenceReference.assess(text.substring(0, 20_000))) }
        println("GATE-SPEED 200 KB capped=${capped.inWholeMicroseconds}us uncapped=${whole.inWholeMilliseconds}ms old-on-20KB=${oldOn20Kb.inWholeMilliseconds}ms")
        assertTrue(capped.inWholeMilliseconds < 50, "assess on 200 KB took $capped")
    }

    private inline fun time(block: () -> Unit): Duration {
        val mark = TimeSource.Monotonic.markNow()
        block()
        return mark.elapsedNow()
    }
}
