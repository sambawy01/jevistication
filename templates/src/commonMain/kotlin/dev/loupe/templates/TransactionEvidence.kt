package dev.loupe.templates

import dev.loupe.engine.DecisionEngine

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
 *
 * **Bounded cost (2026-09-28).** The gate reads only the first [SCAN_CHARS] characters (or the
 * caller's `text_chars` budget): the part of the text the model is shown, so the rule never answers
 * on evidence the model could not have seen, and a 200 KB PDF costs what a 4 KB one does. The word
 * boundaries are written without a look-behind: Kotlin/Native's regex engine evaluates a negative
 * look-behind by searching backwards from every position, which over long texts on the main thread
 * tripped the iOS launch watchdog (0x8BADF00D). `(?:^|[^w])x` finds a match exactly where
 * `(?<![w])x` does, which is all [assess] asks (`containsMatchIn`, never the match's bounds).
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

    /**
     * Characters the gate reads by default: the judgments' model text budget
     * ([DecisionEngine.DEFAULT_STATE_BUDGET]). A caller under Model settings passes `text_chars`.
     */
    const val SCAN_CHARS: Int = DecisionEngine.DEFAULT_STATE_BUDGET

    /** The verdict on the first [maxChars] characters of [text] (see [SCAN_CHARS]). */
    fun assess(text: String, maxChars: Int = SCAN_CHARS): Verdict {
        val end = if (maxChars in 0 until text.length) maxChars else text.length
        val chars = normaliseChars(text, end)
        val found = ANCHORS.find(chars)
        // Most texts hold no anchor of most rules, and never reach a regex; the String is built only when one does.
        val t = lazy(LazyThreadSafetyMode.NONE) { chars.concatToString() }
        if (STRONG.any { it.hit(t, found) }) return Verdict.TRANSACTION
        val listing = LISTING.any { it.hit(t, found) }
        val weak = WEAK.any { it.hit(t, found) } || AMOUNT_WITH_TOTAL.hit(t, found)
        return when {
            listing -> Verdict.LISTING
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
    fun ruleAnswer(judgment: UserJudgment, text: String, maxChars: Int = SCAN_CHARS): String? =
        ruleAnswerFrom(judgment, text) { assess(text, maxChars) }

    /**
     * [ruleAnswer] with the verdict supplied by the caller (a memo of [assess] over the same text and
     * budget). [verdict] is asked only when the judgment is gated, has a negative option and the
     * text is not blank, so an ungated judgment never pays for the regexes.
     */
    fun ruleAnswerFrom(judgment: UserJudgment, text: String, verdict: () -> Verdict): String? {
        if (!applies(judgment) || text.isBlank()) return null
        val negative = negativeOption(judgment.shape) ?: return null
        return if (verdict().defers) null else negative
    }

    private fun negativeOption(shape: Shape): String? = when (shape) {
        Shape.YesNo -> "no"
        is Shape.Binary -> shape.negative
        is Shape.Pick -> shape.noOp
        is Shape.Ordinal -> null
    }

    /** Lowercase; Arabic diacritics and tatweel removed; alef, yaa and taa marbuta folded. */
    /**
     * [normalise] of `text[0, end)` as a CharArray, in one pass: the gate's fast path (on the phone, a
     * String `lowercase()` then a rebuild cost more than the rest of the gate). Lowercasing is per
     * character, with [LOWER_EXCEPTIONS] where the platform's `String.lowercase()` gives something else
     * for a lone character (İ). Two known differences, both outside every pattern and anchor so no verdict
     * can change: surrogate pairs are left as they are, and Greek final sigma is not contextual.
     */
    internal fun normaliseChars(text: String, end: Int): CharArray {
        val src = text.toCharArray(0, end)
        var out = CharArray(end)
        var n = 0
        val exceptional = EXCEPTIONAL
        for (i in 0 until end) {
            val c = src[i]
            val code = c.code
            val l = when {
                code < 128 -> if (c in 'A'..'Z') c + 32 else c
                code in 0x0590..0x06FF -> c   // Hebrew and Arabic have no case
                c.isSurrogate() -> c
                exceptional[code] -> {
                    val special = LOWER_EXCEPTIONS.getValue(c)
                    if (n + special.length + (end - i) > out.size) out = out.copyOf(out.size + special.length + 16)
                    for (k in special) n = put(out, n, k)
                    continue
                }
                else -> c.lowercaseChar()
            }
            n = put(out, n, l)
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    private fun put(out: CharArray, n: Int, c: Char): Int {
        when (c) {
            in 'ً'..'ٟ', 'ٰ', 'ـ' -> return n
            'أ', 'إ', 'آ', 'ٱ' -> out[n] = 'ا'
            'ى' -> out[n] = 'ي'
            'ة' -> out[n] = 'ه'
            else -> out[n] = c
        }
        return n + 1
    }

    private val EXCEPTIONAL: BooleanArray by lazy { BooleanArray(0x10000).also { f -> LOWER_EXCEPTIONS.keys.forEach { f[it.code] = true } } }

    /** Lone characters whose `String.lowercase()` is not their `lowercaseChar()` on this platform (İ → "i̇" on the JVM). */
    private val LOWER_EXCEPTIONS: Map<Char, String> by lazy {
        buildMap {
            for (code in 128..0xFFFF) {
                val c = code.toChar()
                if (c.isSurrogate()) continue
                val whole = c.toString().lowercase()
                if (whole.length != 1 || whole[0] != c.lowercaseChar()) put(c, whole)
            }
        }
    }

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

    /**
     * One pattern behind a cheap pre-filter: the regex runs only when the text holds one of
     * [anchors] — literal pieces every match must contain. A pre-filter never changes a verdict (a match always contains an anchor);
     * it spares the regex engine a pass over texts that cannot match, which is most of them.
     */
    private class Rule(val regex: Regex, val anchors: List<String>) {
        /** [anchors] as indices into [AnchorIndex], set once when the index is built. */
        var ids: IntArray = IntArray(0)

        fun hit(t: Lazy<String>, found: BooleanArray): Boolean {
            for (id in ids) if (found[id]) return regex.containsMatchIn(t.value)
            return false
        }
    }

    private fun rule(regex: Regex, vararg anchors: String) = Rule(regex, anchors.toList())
    private fun arabic(regex: Regex, vararg anchors: String) = Rule(regex, anchors.toList())

    /**
     * Which anchors a text holds, found in **one pass**: at each character only the anchors that start
     * with it are compared (a handful), instead of a search through the whole text per anchor.
     */
    private class AnchorIndex(rules: List<Rule>) {
        private val anchors: Array<CharArray>
        /** Anchor ids by first character; every anchor starts below U+2100 (ASCII, Latin, Arabic, the bullet •). */
        private val byFirst = arrayOfNulls<IntArray>(0x2100)

        init {
            val all = rules.flatMap { it.anchors }.distinct()
            anchors = Array(all.size) { all[it].toCharArray() }
            for (r in rules) r.ids = IntArray(r.anchors.size) { all.indexOf(r.anchors[it]) }
            for ((first, ids) in all.indices.groupBy { all[it][0] }) {
                require(first.code < byFirst.size) { "anchor '${all[ids[0]]}' starts past U+2100" }
                byFirst[first.code] = ids.toIntArray()
            }
        }

        fun find(t: CharArray): BooleanArray {
            val found = BooleanArray(anchors.size)
            val n = t.size
            for (i in 0 until n) {
                val code = t[i].code
                if (code >= byFirst.size) continue
                val bucket = byFirst[code] ?: continue
                for (id in bucket) {
                    if (found[id]) continue
                    val a = anchors[id]
                    if (i + a.size > n) continue
                    var k = 1
                    while (k < a.size && t[i + k] == a[k]) k++
                    if (k == a.size) found[id] = true
                }
            }
            return found
        }
    }

    // Word-ish boundaries for Latin (with accents) and Arabic. Explicit ranges, not \p{L}: the
    // Kotlin/Native regex engine (the phone) failed to compile Unicode classes inside lookarounds.
    // The leading boundary is `(?:^|[^…])`, not a look-behind (slow on Kotlin/Native; see the class
    // doc): it consumes the character before the word, which `containsMatchIn` never looks at.
    private const val WORD = "a-z0-9\u00C0-\u024F\u0600-\u06FF"
    private fun w(p: String) = Regex("(?:^|[^$WORD])(?:$p)(?![$WORD])")

    // Arabic patterns are written pre-normalised (ا for أ/إ/آ, ه for ة, ي for ى).
    // Each rule's anchors are pieces of text every match of its pattern contains.
    private val STRONG: List<Rule> = listOf(
        // EN wording
        rule(w("receipts?|e-?receipt|tax invoice|invoice|proof of purchase|payment receipt|sales receipt"), "receipt", "invoice", "proof of purchase"),
        rule(w("order confirm(?:ation|ed)|thank(?:s| you) for (?:your )?(?:order|purchase|payment|donation)|purchase confirmation"),
             "order confirm", "thank", "purchase confirmation"),
        rule(w("amount paid|total paid|paid in full|payment received|payment successful|payment confirmed|amount charged|you(?:'ve| have) been charged|was charged|charged to your"),
             "paid", "payment", "charged"),
        rule(w("paid (?:with|by|via|using)|payment method|card ending(?: in)?|ending in \\d{4}|auth(?:orisation|orization)? code|transaction id"),
             "paid ", "payment method", "card ending", "ending in ", "auth", "transaction id"),
        rule(w("amount due|balance due|payment due|total due|due date|please pay|overdue|final notice|direct debit"),
             "due", "please pay", "final notice", "direct debit"),
        rule(w("refunded|refund (?:of|issued|processed|has been|amount|to your)|reimbursed"), "refund", "reimbursed"),
        rule(w("donation|gift aid"), "donation", "gift aid"),
        // An order / invoice / receipt number: the word, optional "no"/"#", then a code with a digit.
        rule(Regex("(?:^|[^a-z\u00C0-\u024F])(?:order|invoice|receipt|transaction|booking|confirmation|inv)\\s*(?:no\\.?|number|num|id|#|ref)?\\s*[:#]?\\s*[a-z0-9-]*\\d[a-z0-9-]{2,}"),
             "order", "inv", "receipt", "transaction", "booking", "confirmation"),
        // A masked card number: two or more of * x • then four digits.
        rule(Regex("[*x•]{2,}\\s?\\d{4}(?!\\d)"), "**", "*x", "*•", "x*", "xx", "x•", "•*", "•x", "••"),
        // AR
        arabic(Regex("فاتور|ايصال|وصل استلام|وصل دفع|سند قبض|اثبات (?:ال)?شراء|تاكيد (?:ال)?طلب|رقم (?:ال)?طلب|رقم (?:ال)?فاتوره|تم (?:ال)?دفع|المبلغ المدفوع|اجمالي المدفوع|تم الخصم|طريقه (?:ال)?دفع|المبلغ المستحق|تاريخ (?:ال)?استحقاق|تم (?:ال)?استرداد|مبلغ مسترد|تبرع"),
               "فاتور", "ايصال", "وصل ", "سند قبض", "اثبات ", "تاكيد ", "رقم ", "تم ", "مبلغ", "اجمالي المدفوع", "طريقه ", "تاريخ ", "تبرع"),
        // FR / DE / ES core words
        rule(w("reçu|facture|quittung|rechnung|kassenbon|bestellbestätigung|factura|recibo|comprobante"),
             "reçu", "factur", "quittung", "rechnung", "kassenbon", "bestellbestätigung", "recibo", "comprobante"),
    )

    private val WEAK: List<Rule> = listOf(
        rule(w("paid|payment|total|subtotal|order|purchased?|vat|tax|visa|mastercard|amex|apple pay|google pay|paypal|mada|stc pay|bill|statement|warranty|guarantee|serial|refunds?|returns?"),
             "paid", "payment", "total", "order", "purchase", "vat", "tax", "visa", "mastercard", "amex", "apple pay", "google pay",
             "paypal", "mada", "stc pay", "bill", "statement", "warranty", "guarantee", "serial", "refund", "return"),
        arabic(Regex("اجمالي|المجموع|مدفوع|الدفع|ضريبه|شراء|طلب|فيزا|مدى|ضمان|استرداد"),
               "اجمالي", "المجموع", "مدفوع", "الدفع", "ضريبه", "شراء", "طلب", "فيزا", "مدى", "ضمان", "استرداد"),
    )

    private val LISTING: List<Rule> = listOf(
        rule(w("add to (?:cart|bag|basket|trolley|wish ?list)|buy (?:it )?now|shop now|order now|in stock|out of stock|only \\d+ left|free (?:delivery|shipping|returns)|customer reviews|\\d+ reviews?|write a review|rated \\d|compare (?:at|price)|select (?:size|colou?r)|choose (?:your )?(?:size|colou?r)|you may also like|frequently bought|product (?:details|description)|specifications|sku"),
             "add to ", "buy ", "shop now", "order now", "in stock", "out of stock", "only ", "free ", "review", "rated ", "compare ",
             "select ", "choose ", "you may also like", "frequently bought", "product ", "specifications", "sku"),
        arabic(Regex("(?:اضف|اضافه) (?:الي )?(?:ال|لل|ل)سله|اشتر(?:ي)? الان|تسوق الان|متوفر(?: في المخزون)?|غير متوفر|نفذت الكميه|تقييمات|مراجعات|اكتب تقييم|توصيل مجاني|شحن مجاني|مواصفات|تفاصيل المنتج"),
               "سله", "اشتر", "تسوق الان", "متوفر", "نفذت الكميه", "تقييم", "مراجعات", "مجاني", "مواصفات", "تفاصيل المنتج"),
        rule(w("ajouter au panier|acheter maintenant|en stock|in den warenkorb|jetzt kaufen|auf lager|añadir al carrito|comprar ahora|en existencia"),
             "ajouter au panier", "acheter maintenant", "en stock", "in den warenkorb", "jetzt kaufen", "auf lager", "añadir al carrito",
             "comprar ahora", "en existencia"),
    )

    /** "Total … 12.45" style lines: a sum with a number, in any script covered above. */
    private val AMOUNT_WITH_TOTAL = rule(Regex("(?:total|اجمالي|المجموع)[^\\n]{0,20}\\d"), "total", "اجمالي", "المجموع")

    private val ANCHORS = AnchorIndex(STRONG + WEAK + LISTING + AMOUNT_WITH_TOTAL)
}
