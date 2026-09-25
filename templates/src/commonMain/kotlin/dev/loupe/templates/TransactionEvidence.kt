package dev.loupe.templates

/**
 * The transaction-evidence gate for the money judgments (A3's "mechanical first", like the
 * exact-duplicate rule): a document that carries **no sign that money moved or is owed** is
 * answered with the judgment's negative option by rule, logged `mechanical:no-transaction-evidence`,
 * and never reaches the model or the Unsure queue.
 *
 * Why: a shop's product page saved as a PDF ("Bugaboo Donkey stroller … £1,099 … Add to basket …
 * 4.8 stars, 212 reviews") mentions a merchant and a price, which is exactly what the model keys on
 * for "is this a receipt?" — it came back torn (about 50/50) and was put in front of the owner as
 * a question worth their time. Nothing on such a page records a payment.
 *
 * **Conservative by construction.** The rule only ever answers *no*; anything that looks like a
 * transaction goes to the model as before. Evidence comes in two strengths:
 *  - [STRONG] — receipt / invoice / order-confirmation wording, "amount paid", "amount due", a
 *    refund, a masked card number, an order or invoice number: always defers to the model.
 *  - [WEAK] — bare "paid", "total", "order", "VAT", a card brand: defers to the model **unless**
 *    the text also reads as a shop listing ([LISTING]: add to cart, buy now, in stock, reviews),
 *    since listings print "order now", "total", "VAT included" and "we accept Visa" routinely.
 * No evidence at all → negative by rule. English and Arabic are covered in full (Arabic is
 * normalised: diacritics and tatweel dropped, alef/yaa/taa-marbuta forms folded), with the core
 * receipt/invoice/cart words in French, German and Spanish. A language not covered finds no
 * words at all, so the rule answers *no* there too — the gate's known limit, documented in
 * `docs/BUILD.md` with the other gates.
 */
object TransactionEvidence {
    /** The ledger check name: rows read `mechanical:no-transaction-evidence`. */
    const val CHECK: String = "no-transaction-evidence"

    /** Templates the gate applies to (also applied by id to judgments made before the gate existed). */
    val GATED_TEMPLATES: Set<String> = setOf("is-receipt", "tax-receipt", "warranty-proof", "refund-issued", "bill-unpaid", "receipt-kind")

    enum class Verdict {
        /** Something says money moved or is owed: the model decides. */
        TRANSACTION,

        /** Only weak hints, and nothing that reads as a shop listing: the model decides. */
        WEAK,

        /** A shop/product listing with no transaction evidence: negative by rule. */
        LISTING,

        /** Nothing about a transaction at all: negative by rule. */
        NONE,
        ;

        val defers: Boolean get() = this == TRANSACTION || this == WEAK
    }

    fun assess(text: String): Verdict {
        val t = normalise(text)
        if (STRONG.any { it.containsMatchIn(t) }) return Verdict.TRANSACTION
        val listing = LISTING.count { it.containsMatchIn(t) }
        val weak = WEAK.any { it.containsMatchIn(t) } || AMOUNT_WITH_TOTAL.containsMatchIn(t)
        return when {
            listing > 0 -> Verdict.LISTING
            weak -> Verdict.WEAK
            else -> Verdict.NONE
        }
    }

    /** True when [judgment] is gated: marked [MechanicalCheck.NO_TRANSACTION_EVIDENCE] or made from a gated template. */
    fun applies(judgment: UserJudgment): Boolean =
        judgment.mechanical == MechanicalCheck.NO_TRANSACTION_EVIDENCE || judgment.templateId in GATED_TEMPLATES

    /**
     * The label the rule answers for [judgment] on [text], or null to let the model decide. Null
     * when the judgment is not gated, has no negative option, or the text is blank (no text is
     * never sent to the model anyway, and is not evidence of anything).
     */
    fun ruleAnswer(judgment: UserJudgment, text: String): String? {
        if (!applies(judgment) || text.isBlank()) return null
        val negative = negativeOption(judgment.shape) ?: return null
        return if (assess(text).defers) null else negative
    }

    private fun negativeOption(shape: Shape): String? = when (shape) {
        Shape.YesNo -> "no"
        is Shape.Binary -> shape.negative
        is Shape.Pick -> shape.noOp
        is Shape.Ordinal -> null
    }

    /** Lowercase; Arabic diacritics and tatweel removed; alef, yaa and taa marbuta folded. */
    fun normalise(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text.lowercase()) {
            when (c) {
                in 'ً'..'ٟ', 'ٰ', 'ـ' -> Unit
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                'ى' -> sb.append('ي')
                'ة' -> sb.append('ه')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    // Word-ish boundaries for Latin (with accents) and Arabic. Explicit ranges, not \p{L}: the
    // Kotlin/Native regex engine (the phone) failed to compile Unicode classes inside lookarounds.
    private const val WORD = "a-z0-9\u00C0-\u024F\u0600-\u06FF"
    private fun w(p: String) = Regex("(?<![$WORD])(?:$p)(?![$WORD])")

    // Arabic patterns are written pre-normalised (ا for أ/إ/آ, ه for ة, ي for ى).
    private val STRONG: List<Regex> = listOf(
        // EN wording
        w("receipts?|e-?receipt|tax invoice|invoice|proof of purchase|payment receipt|sales receipt"),
        w("order confirm(?:ation|ed)|thank(?:s| you) for (?:your )?(?:order|purchase|payment|donation)|purchase confirmation"),
        w("amount paid|total paid|paid in full|payment received|payment successful|payment confirmed|amount charged|you(?:'ve| have) been charged|was charged|charged to your"),
        w("paid (?:with|by|via|using)|payment method|card ending(?: in)?|ending in \\d{4}|auth(?:orisation|orization)? code|transaction id"),
        w("amount due|balance due|payment due|total due|due date|please pay|overdue|final notice|direct debit"),
        w("refunded|refund (?:of|issued|processed|has been|amount|to your)|reimbursed"),
        w("donation|gift aid"),
        // An order / invoice / receipt number: the word, optional "no"/"#", then a code with a digit.
        Regex("(?<![a-z\u00C0-\u024F])(?:order|invoice|receipt|transaction|booking|confirmation|inv)\\s*(?:no\\.?|number|num|id|#|ref)?\\s*[:#]?\\s*[a-z0-9-]*\\d[a-z0-9-]{2,}"),
        Regex("[*x•]{2,}\\s?\\d{4}(?!\\d)"),
        // AR
        Regex("فاتور|ايصال|وصل استلام|وصل دفع|سند قبض|اثبات (?:ال)?شراء|تاكيد (?:ال)?طلب|رقم (?:ال)?طلب|رقم (?:ال)?فاتوره|تم (?:ال)?دفع|المبلغ المدفوع|اجمالي المدفوع|تم الخصم|طريقه (?:ال)?دفع|المبلغ المستحق|تاريخ (?:ال)?استحقاق|تم (?:ال)?استرداد|مبلغ مسترد|تبرع"),
        // FR / DE / ES core words
        w("reçu|facture|quittung|rechnung|kassenbon|bestellbestätigung|factura|recibo|comprobante"),
    )

    private val WEAK: List<Regex> = listOf(
        w("paid|payment|total|subtotal|order|purchased?|vat|tax|visa|mastercard|amex|apple pay|google pay|paypal|mada|stc pay|bill|statement|warranty|guarantee|serial|refunds?|returns?"),
        Regex("اجمالي|المجموع|مدفوع|الدفع|ضريبه|شراء|طلب|فيزا|مدى|ضمان|استرداد"),
    )

    private val LISTING: List<Regex> = listOf(
        w("add to (?:cart|bag|basket|trolley|wish ?list)|buy (?:it )?now|shop now|order now|in stock|out of stock|only \\d+ left|free (?:delivery|shipping|returns)|customer reviews|\\d+ reviews?|write a review|rated \\d|compare (?:at|price)|select (?:size|colou?r)|choose (?:your )?(?:size|colou?r)|you may also like|frequently bought|product (?:details|description)|specifications|sku"),
        Regex("(?:اضف|اضافه) (?:الي )?(?:ال|لل|ل)سله|اشتر(?:ي)? الان|تسوق الان|متوفر(?: في المخزون)?|غير متوفر|نفذت الكميه|تقييمات|مراجعات|اكتب تقييم|توصيل مجاني|شحن مجاني|مواصفات|تفاصيل المنتج"),
        w("ajouter au panier|acheter maintenant|en stock|in den warenkorb|jetzt kaufen|auf lager|añadir al carrito|comprar ahora|en existencia"),
    )

    /** "Total … 12.45" style lines: a sum with a number, in any script covered above. */
    private val AMOUNT_WITH_TOTAL = Regex("(?:total|اجمالي|المجموع)[^\\n]{0,20}\\d")
}
