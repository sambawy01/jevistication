package dev.loupe.kit.tracking

/**
 * Expiry words (spec §7.2), over [TrackingText.matchForm] text: English, Arabic and Egyptian (تنتهي، تاريخ الانتهاء،
 * صالحة حتى، صالح لغاية، الصلاحية، سارية حتى, and the colloquial بتخلص / لحد). [PROMO] marks an offer, whose
 * "expires" is not a document's.
 */
object ExpiryLexicon {
    val EXPIRY: Regex = TrackingText.words(listOf(
        "re:expir[a-z]*", "valid until", "valid till", "valid to", "valid thru", "valid through", "renewal date", "renew by", "re:4b\\.",
        "تنتهي", "ينتهي", "تاريخ الانتهاء", "انتهاء الصلاحية", "الصلاحية", "صالحة حتى", "صالح حتى", "صالحة لغاية", "صالح لغاية",
        "سارية حتى", "ساري حتى", "سارية لغاية", "صالحة لحد", "صالح لحد", "تاريخ التجديد", "ميعاد التجديد", "بتخلص", "هتخلص",
    ))

    val PROMO: Regex = TrackingText.words(listOf(
        "offer", "sale", "discount", "coupon", "promo", "promo code", "voucher", "deal", "re:[0-9]+% off",
        "عرض", "عروض", "كوبون", "تخفيض", "تخفيضات", "خصم",
    ))
}

/** What a dated document is, decided by rules from its words (the model's own judgment is the radar's model half). */
enum class DocumentKind(val id: String, val title: String) {
    CAR_LICENCE("car_licence", "car licence"),
    DRIVING_LICENCE("driving_licence", "driving licence"),
    NATIONAL_ID("national_id", "national ID"),
    PASSPORT("passport", "passport"),
    RESIDENCE("residence", "residence permit"),
    INSURANCE("insurance", "insurance policy"),
    CONTRACT("contract", "contract"),
    MEMBERSHIP("membership", "membership"),
    WARRANTY("warranty", "warranty"),
}

/**
 * Egyptian and English document kinds, the most specific first (a car licence before a driving licence). An ID
 * card's, a policy's or a residence permit's own title comes before a contract; a contract comes before the words
 * a contract also uses: an Egyptian lease lists its parties' "الرقم القومي" and a "مبلغ التأمين" deposit (and their
 * "محل الإقامة"), and is still a contract.
 */
object DocumentKinds {
    private val RULES: List<Pair<DocumentKind, Regex>> = listOf(
        DocumentKind.CAR_LICENCE to TrackingText.words(listOf("vehicle licence", "vehicle license", "car licence", "car license",
            "رخصة تسيير", "رخصة سيارة", "رخصة العربية", "رخصة مركبة")),
        DocumentKind.DRIVING_LICENCE to TrackingText.words(listOf("driving licence", "driving license", "driver's license", "driver license",
            "رخصة قيادة", "رخصة السواقة")),
        DocumentKind.NATIONAL_ID to TrackingText.words(listOf("national id card", "national identity card", "id card", "identity card",
            "بطاقة الرقم القومي", "بطاقة تحقيق الشخصية", "البطاقة الشخصية")),
        DocumentKind.INSURANCE to TrackingText.words(listOf("insurance policy", "policy of insurance", "certificate of insurance",
            "وثيقة تأمين", "وثيقة التأمين", "بوليصة")),
        DocumentKind.RESIDENCE to TrackingText.words(listOf("residence permit", "تصريح إقامة", "بطاقة إقامة")),
        DocumentKind.CONTRACT to TrackingText.words(listOf("contract", "lease", "tenancy", "agreement", "عقد")),
        DocumentKind.NATIONAL_ID to TrackingText.words(listOf("national id", "national identity", "الرقم القومي", "تحقيق الشخصية")),
        DocumentKind.PASSPORT to TrackingText.words(listOf("passport", "جواز سفر", "جواز السفر", "باسبور")),
        DocumentKind.RESIDENCE to TrackingText.words(listOf("إقامة")),
        DocumentKind.INSURANCE to TrackingText.words(listOf("insurance", "تأمين")),
        DocumentKind.MEMBERSHIP to TrackingText.words(listOf("membership", "member card", "عضوية", "كارنيه")),
        DocumentKind.WARRANTY to TrackingText.words(listOf("warranty", "guarantee", "ضمان")),
    )

    fun of(text: String): DocumentKind? {
        val form = TrackingText.matchForm(text)
        return RULES.firstOrNull { it.second.containsMatchIn(form) }?.first
    }
}
