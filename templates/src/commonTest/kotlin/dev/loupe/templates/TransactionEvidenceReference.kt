package dev.loupe.templates

import dev.loupe.templates.TransactionEvidence.Verdict

/**
 * The gate exactly as it shipped before 2026-09-28 (look-behind word boundaries, no scan cap), kept
 * only so the tests can prove the rewrite gives the same verdict on every text it is shown.
 * Never used by the app: its look-behinds are what hung the phone on long texts.
 */
object TransactionEvidenceReference {
    fun assess(text: String): Verdict {
        val t = TransactionEvidence.normalise(text)
        if (STRONG.any { it.containsMatchIn(t) }) return Verdict.TRANSACTION
        val listing = LISTING.count { it.containsMatchIn(t) }
        val weak = WEAK.any { it.containsMatchIn(t) } || AMOUNT_WITH_TOTAL.containsMatchIn(t)
        return when {
            listing > 0 -> Verdict.LISTING
            weak -> Verdict.WEAK
            else -> Verdict.NONE
        }
    }

    private const val WORD = "a-z0-9À-ɏ؀-ۿ"
    private fun w(p: String) = Regex("(?<![$WORD])(?:$p)(?![$WORD])")

    private val STRONG: List<Regex> = listOf(
        w("receipts?|e-?receipt|tax invoice|invoice|proof of purchase|payment receipt|sales receipt"),
        w("order confirm(?:ation|ed)|thank(?:s| you) for (?:your )?(?:order|purchase|payment|donation)|purchase confirmation"),
        w("amount paid|total paid|paid in full|payment received|payment successful|payment confirmed|amount charged|you(?:'ve| have) been charged|was charged|charged to your"),
        w("paid (?:with|by|via|using)|payment method|card ending(?: in)?|ending in \\d{4}|auth(?:orisation|orization)? code|transaction id"),
        w("amount due|balance due|payment due|total due|due date|please pay|overdue|final notice|direct debit"),
        w("refunded|refund (?:of|issued|processed|has been|amount|to your)|reimbursed"),
        w("donation|gift aid"),
        Regex("(?<![a-zÀ-ɏ])(?:order|invoice|receipt|transaction|booking|confirmation|inv)\\s*(?:no\\.?|number|num|id|#|ref)?\\s*[:#]?\\s*[a-z0-9-]*\\d[a-z0-9-]{2,}"),
        Regex("[*x•]{2,}\\s?\\d{4}(?!\\d)"),
        Regex("فاتور|ايصال|وصل استلام|وصل دفع|سند قبض|اثبات (?:ال)?شراء|تاكيد (?:ال)?طلب|رقم (?:ال)?طلب|رقم (?:ال)?فاتوره|تم (?:ال)?دفع|المبلغ المدفوع|اجمالي المدفوع|تم الخصم|طريقه (?:ال)?دفع|المبلغ المستحق|تاريخ (?:ال)?استحقاق|تم (?:ال)?استرداد|مبلغ مسترد|تبرع"),
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

    private val AMOUNT_WITH_TOTAL = Regex("(?:total|اجمالي|المجموع)[^\\n]{0,20}\\d")
}
