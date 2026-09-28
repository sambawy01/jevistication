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
        hint("Fawry", "fawry", "فوري", rail = true),
    )

    /** WE writes its name in capitals; "we" in a sentence is not the company. Matched on the folded original. */
    private val WE_CAPS = Regex("(?<![A-Za-z])WE(?![A-Za-z])")

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
        val rail = ALL.filter { it.rail }.mapNotNull { h -> h.pattern.find(form)?.let { it.range.first to h.canonical } }.minByOrNull { it.first }
        if (rail != null) payee(text)?.let { return canonical(it) }
        return firstBrand(text, form) ?: rail?.second
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

    private fun firstBrand(text: String, form: String): String? {
        val hits = ALL.filter { !it.rail }.mapNotNull { h -> h.pattern.find(form)?.let { it.range.first to h.canonical } }.toMutableList()
        WE_CAPS.find(TrackingText.foldDigits(text))?.let { hits += it.range.first to "WE" }
        return hits.minByOrNull { it.first }?.second
    }
}
