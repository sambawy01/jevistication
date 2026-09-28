package dev.loupe.kit.tracking

/** A known merchant (or a payment rail, whose merchant is on the payee or service line) and how it is written. */
data class MerchantHint(val canonical: String, val rail: Boolean, val pattern: Regex)

/**
 * The merchants people in Egypt pay most (spec §7.2), with their English, Arabic and colloquial spellings, and the
 * payment rails (InstaPay, Fawry) whose receipts name the real merchant on a payee or service line.
 */
object MerchantHints {
    private fun hint(canonical: String, vararg words: String, rail: Boolean = false) =
        MerchantHint(canonical, rail, TrackingText.words(words.toList()))

    val ALL: List<MerchantHint> = listOf(
        hint("Netflix", "netflix", "نتفليكس"),
        hint("Spotify", "spotify", "سبوتيفاي"),
        hint("Anghami", "anghami", "أنغامي", "انغامي"),
        hint("Shahid", "shahid", "re:شاهد\\s+vip"),
        hint("WATCH IT", "watch it", "watchit", "واتش إت"),
        hint("OSN+", "osn+", "osn plus"),
        hint("YouTube Premium", "youtube premium"),
        hint("iCloud", "icloud"),
        hint("Google One", "google one"),
        hint("Amazon Prime", "amazon prime", "prime video"),
        hint("WE", "telecom egypt", "we internet", "we home", "my we", "المصرية للاتصالات", "وي انترنت", "ماي وي"),
        hint("Vodafone", "vodafone", "فودافون"),
        hint("Orange", "orange egypt", "orange money", "orange mobile", "orange internet", "أورنج", "اورانج"),
        hint("e&", "etisalat", "e& egypt", "اتصالات مصر"),
        hint("InstaPay", "instapay", "انستاباي", "إنستاباي", rail = true),
        // "فوري" is also the adjective "instant" ("تحويل فوري"): the rail only as a line of its own (a receipt's
        // heading) or in its own phrases.
        hint("Fawry", "fawry", "كود فوري", "فوري باي", "منفذ فوري", "ماكينة فوري", "فوري للمدفوعات",
             "re:(?:^|(?<=\\n))[ \\t]*فوري[ \\t]*(?=\\r?\\n|$)", rail = true),
    )

    /**
     * WE writes its name in capitals; "we" in a sentence, and "WE'VE" / "WE HAVE" in a receipt written in capitals,
     * are not the company. WE is the company as a line of its own (a heading, a sender's name, "WE - فاتورة") or
     * before one of its services ("WE Internet", "WE Home", "WE Mobile", "WE Space", "WE Gold"). Matched on the
     * folded original.
     */
    private val WE_CAPS = Regex(
        "(?:^|(?<=\\n))[ \\t]*WE[ \\t]*(?=\\r?\\n|$|[-–|·:])" +
            "|(?<![A-Za-z'’])WE(?=[ \\t]+(?:Internet|INTERNET|Home|HOME|Mobile|MOBILE|Space|SPACE|Gold|GOLD)(?![A-Za-z]))",
    )

    /** "via InstaPay", "paid with …", "عن طريق فوري": what comes next is how it was paid, not who (match form). */
    private val PAID_WITH = Regex("(?:^|[^a-z0-9ء-ي])(?:via|with|using|paid by|pay by|بواسطه|عن طريق|من خلال|باستخدام|عبر)\\s*$")

    /** A brand followed by "Cash" or "wallet" ("Vodafone Cash", "فودافون كاش") is a wallet paid from (match form). */
    private val WALLET = Regex("^\\s*(?:cash|wallet|كاش|محفظه)(?![a-z0-9ء-ي])")

    /** "Beneficiary: …", "المستفيد: …", "الخدمة: …" (over the match form). */
    private val PAYEE = Regex(
        "^\\s*(?:beneficiary|payee|recipient|paid to|transfer to|to|merchant|biller|service|" +
            "المستفيد|اسم المستفيد|الي|التاجر|الخدمه|الجهه|المفوتر)\\s*[:：]\\s*(.+)$",
    )

    private val GENERIC = TrackingText.words(listOf(
        "receipt", "invoice", "tax invoice", "payment receipt", "order confirmation", "thank you", "photo", "screenshot",
        "إيصال", "فاتورة", "شكرا",
    ))

    /** The merchant a text is about: a rail's payee or service, else the first known brand, else the rail itself. */
    fun merchant(text: String): String? {
        val form = TrackingText.matchForm(text)
        val rail = rail(form)
        if (rail != null) payee(text)?.let { return canonical(it) }
        return firstBrand(text, form) ?: rail
    }

    /**
     * The merchant of an email receipt: a rail's payee when a rail is named and the payee line is there; else a known
     * brand that is the sender (its name or its address) or named in the subject; else a known brand named in the
     * body (Apple's receipt for iCloud); else the sender's name; else its address's domain; else the rail. A brand
     * named as the way it was paid ("paid via InstaPay", "Paid with Vodafone Cash") is never the merchant.
     */
    fun ofEmail(subject: String, body: String, fromName: String?, fromAddress: String?): String? {
        val read = subject + "\n" + body
        val rail = rail(TrackingText.matchForm(read))
        if (rail != null) payee(read)?.let { return canonical(it) }
        val sender = listOfNotNull(fromName?.trim(), fromAddress?.trim()).filter { it.isNotEmpty() }.joinToString("\n")
        return firstBrand(sender, TrackingText.matchForm(sender))
            ?: paidTo(subject)
            ?: paidTo(body)
            ?: fromName?.trim()?.takeIf { it.isNotEmpty() }?.let { canonical(it) }
            ?: fromAddress?.substringAfter('@', "")?.trim()?.takeIf { it.isNotEmpty() }
            ?: rail
    }

    /** A written merchant name brought to its canonical form when it is a known brand ("NETFLIX.COM" → Netflix). */
    fun canonical(name: String): String {
        val t = name.trim()
        return firstBrand(t, TrackingText.matchForm(t)) ?: t
    }

    /** The value of the first payee or service line, verbatim, up to " - " ("أورنج - فاتورة موبايل" → "أورنج"). */
    fun payee(text: String): String? {
        for (line in text.lines()) {
            val m = PAYEE.find(TrackingText.matchForm(line)) ?: continue
            val start = m.range.last + 1 - m.groupValues[1].length
            val value = line.substring(start).trim().substringBefore(" - ").trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    /** A shop's name on a receipt's first line (2-40 characters, a letter, at most 4 digits, not a generic word). */
    fun fromFirstLine(body: String): String? {
        val line = body.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        if (line.length !in 2..40 || line.none { it.isLetter() } || line.count { it.isDigit() } > 4) return null
        if (GENERIC.containsMatchIn(TrackingText.matchForm(line))) return null
        return canonical(line)
    }

    private fun rail(form: String): String? =
        ALL.filter { it.rail }.mapNotNull { h -> h.pattern.find(form)?.let { it.range.first to h.canonical } }.minByOrNull { it.first }?.second

    private fun firstBrand(text: String, form: String): String? = brands(text, form).firstOrNull()?.second

    /** The first known brand in [text] that is not named as the way it was paid (see [PAID_WITH], [WALLET]). */
    private fun paidTo(text: String): String? {
        val form = TrackingText.matchForm(text)
        return brands(text, form).firstOrNull { (range, _) ->
            val lineStart = form.lastIndexOf('\n', range.first - 1) + 1
            !PAID_WITH.containsMatchIn(form.substring(lineStart, range.first)) &&
                !WALLET.containsMatchIn(form.substring(range.last + 1))
        }?.second
    }

    /** Every known brand in [text] (not the rails), each at its first place, in order of appearance. */
    private fun brands(text: String, form: String): List<Pair<IntRange, String>> {
        val hits = ALL.filter { !it.rail }.mapNotNull { h -> h.pattern.find(form)?.let { it.range to h.canonical } }.toMutableList()
        WE_CAPS.find(TrackingText.foldDigits(text))?.let { hits += it.range to "WE" }
        return hits.sortedBy { it.first.first }
    }
}
