package dev.loupe.kit.tracking

/**
 * Charge and subscription words (spec §7.2), over [TrackingText.matchForm] text: English, Arabic, Egyptian and
 * Franco. A charge needs a [CHARGE] word on a line without a [NOT_A_CHARGE] word; a [NOT_A_CHARGE] word on the
 * amount's line or the first line vetoes it.
 */
object ChargeLexicon {
    /** Something was paid, charged, deducted, renewed or transferred. */
    val CHARGE: Regex = TrackingText.words(listOf(
        "charged", "paid", "payment received", "payment successful", "payment confirmed", "payment confirmation",
        "receipt for", "has been renewed", "was renewed", "renewed", "billed", "debited", "deducted",
        "thank you for your payment", "your payment of", "purchase successful",
        "تم الدفع", "تم دفع", "تم سداد", "سداد فاتورة", "دفعت", "تم خصم", "خصم مبلغ", "اتخصم", "تم تحصيل", "تم تجديد",
        "اتجدد", "تم تحويل", "عملية ناجحة", "إيصال دفع", "إيصال سداد", "المبلغ المدفوع", "مدفوع", "مدفوعة",
        "et5asam", "etkhasam", "it5asam", "et5sam", "dafa3t", "dafa3na", "tam el daf3", "tam daf3", "etdafa3", "etgadad",
    ))

    /** Money that came in, came back, is still due, is only asked for, is still to come, failed, or is advertised. */
    val NOT_A_CHARGE: Regex = TrackingText.words(listOf(
        "refund", "refunded", "credited", "deposit", "deposited", "request to pay", "payment request", "amount due",
        "due date", "pay now", "subscribe now", "sign up", "offer", "re:[0-9]+% off",
        "will be charged", "will be renewed", "will renew", "will be billed", "reminder", "upcoming",
        "declined", "failed", "unsuccessful",
        "استرداد", "مسترد", "تم إيداع", "إيداع", "طلب تحويل", "طلب دفع", "مستحق", "ادفع", "اشترك الآن", "اشترك دلوقتي",
        "عرض", "غير مدفوع", "غير مدفوعة", "فشل", "فشلت", "مرفوض", "مرفوضة", "لم تتم",
        "momken", "3ayez", "e3mel eshterak", "eshtrek delwa2ty",
    ))

    /** The text names a subscription or a recurring bill (kept as evidence; a cadence still needs three charges). */
    val SUBSCRIPTION: Regex = TrackingText.words(listOf(
        "subscription", "membership", "monthly plan", "renewal", "auto-renew", "plan",
        "اشتراك", "باقة", "تجديد", "شهري", "شهرية", "عضوية", "فاتورة",
        "eshterak", "eshtrak", "ba2a", "shahry", "fatoora", "tagdeed",
    ))
}
