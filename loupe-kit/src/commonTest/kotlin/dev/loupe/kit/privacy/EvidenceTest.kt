package dev.loupe.kit.privacy

import dev.loupe.engine.ContentHash
import dev.loupe.kit.privacy.PrivacyRulesTest.Companion.CARD
import dev.loupe.kit.privacy.PrivacyRulesTest.Companion.IBAN
import dev.loupe.kit.privacy.PrivacyRulesTest.Companion.luhnComplete
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.PhoneItems
import dev.loupe.sources.common.SourceItem
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Owner rule 2026-09-24: the stricter card rule for OCR'd text, masking, "Show where" evidence and
 * clean chips. Runs on the JVM and the iOS simulator.
 */
class EvidenceTest {
    private val today = LocalDate(2026, 9, 23)
    private val grouped = CARD.chunked(4).joinToString(" ")
    private val arabic = CARD.map { "٠١٢٣٤٥٦٧٨٩"[it - '0'] }.joinToString("")
    private val amex = "378282246310005"   // the network's published test number

    private fun cards(text: String, ocr: Boolean): Int =
        PiiCollector(today, ocr).also { it.scan(text) }.signals().firstOrNull { it.type == "card_number" }?.count ?: 0

    @Test
    fun ocrCardTruePositivesInBothScripts() {
        for (t in listOf(
            "VISA\n$grouped\nVALID THRU 09/29",
            "Card no: $CARD",
            "Charged to card $grouped",
            grouped,                                   // strict 4-4-4-4 grouping, no cue needed
            "AMEX 3782 822463 10005",
            "رقم البطاقة $arabic",
            "بطاقة ائتمان: " + arabic.chunked(4).joinToString(" "),
            "exp 12/28 cvv ***  $CARD",
        )) assertEquals(1, cards(t, ocr = true), t)
    }

    @Test
    fun ocrCardFalsePositivesInBothScripts() {
        for (t in listOf(
            CARD,                                              // ungrouped, no card word
            "$grouped 7781",                                   // part of a longer digit run
            "1234 $grouped",
            "10:$CARD",                                        // a time / date
            "31/$CARD",
            "+$CARD",                                          // a phone number
            "IMEI: 356938035643809",                           // an IMEI (Luhn-valid, IIN 35)
            "356938035643809",
            "Tracking $grouped",                               // tracking / order numbers
            "Order no. $CARD card",
            "Ref $grouped",
            "0xa${CARD}f",                                     // hex
            "deadbeef${CARD}",
            "رقم الطلب $arabic",                               // Arabic: order number
            "تتبع الشحنة " + arabic.chunked(4).joinToString(" "),
            "هاتف $arabic",
        )) assertEquals(0, cards(t, ocr = true), t)
    }

    @Test
    fun filesKeepStationsRule() {
        // Not OCR: Station's rule, unchanged (an ungrouped number without a card word still counts).
        assertEquals(1, cards(CARD, ocr = false))
        assertEquals(1, cards("Order no. $CARD", ocr = false))
    }

    @Test
    fun cardBrandsAndVerdictChecks() {
        assertEquals("Visa", CardBrands.of(CARD))
        assertEquals("American Express", CardBrands.of(amex))
        assertEquals("Mastercard", CardBrands.of("5500005555555559"))
        assertNull(CardBrands.of("9999999999999995"))
        val v = CardRules.judge("Visa $grouped", 5 until 5 + grouped.length, CARD, ocr = true)
        assertTrue(v.ok)
        assertTrue(v.checks.any { "Luhn" in it } && v.checks.any { "IIN 4539" in it } && v.checks.any { "visa" in it.lowercase() })
    }

    @Test
    fun masking() {
        assertEquals("•••• •••• •••• " + CARD.takeLast(4), PrivacyMask.card(CARD))
        assertEquals("EG•• •••• •••• 0002", PrivacyMask.iban(IBAN))
        assertEquals("c•••@e•••.com", PrivacyMask.email("cust7@example.com"))
        assertEquals("+20 •••••••• 67", PrivacyMask.phone("+20 100 123 4567"))
        assertEquals("•••••••••• 4567", PrivacyMask.number("29001011234567"))
        assertEquals("A••••••78", PrivacyMask.passport("A12345678"))
    }

    @Test
    fun evidenceIsMaskedAndPointsAtTheMatch() {
        val text = "Photo (text recognised): IMG_1507.HEIC\n\nTOTAL 12.40\nVISA $grouped\nThank you"
        val hits = PrivacyEvidence.find(text, "card_number", ocr = true, today = today)
        assertEquals(1, hits.size)
        val h = hits.single()
        assertEquals("•••• •••• •••• " + CARD.takeLast(4), h.masked)
        assertEquals("Visa", h.brand)
        assertEquals(grouped, text.substring(h.start, h.end))
        assertEquals("VISA •••• •••• •••• " + CARD.takeLast(4), h.context)
        assertTrue(h.checks.first().startsWith("Luhn"))
        // No run of the real number survives anywhere in what is shown.
        for (s in listOf(h.masked, h.context) + h.checks) assertFalse(CARD.take(8) in s.filter { it.isDigit() }, s)
        // A second number on the same line is masked in the context too.
        val two = PrivacyEvidence.find("card $grouped iban $IBAN", "card_number", true, today).single()
        assertFalse(IBAN.drop(4).take(10) in two.context, two.context)
        // The other rules.
        assertEquals("EG•• •••• •••• 0002", PrivacyEvidence.find("IBAN $IBAN", "iban", false, today).single().masked)
        assertEquals("c•••@e•••.com", PrivacyEvidence.find("mail cust7@example.com", "email", false, today).single().masked)
        // Evidence agrees with the check: a rejected OCR number has no evidence either.
        assertTrue(PrivacyEvidence.find("Order no. $CARD", "card_number", true, today).isEmpty())
    }

    private fun photo(ocr: String) = PhoneItems().photo("P1", "IMG_1507.HEIC", ocr, "2026-09-01", null, null, null, false, false, 1000)

    @Test
    fun chipsAreCleanAndOcrPhotosUseTheStricterRule() {
        val item = SourceItem(
            id = "files:x/card.txt", sourceId = "files", kind = ItemKind.TEXT, path = "/tmp/card.txt", messageIndex = null,
            name = "card.txt", text = "card $CARD", hasText = true, textTruncated = false, sizeBytes = 10,
            contentHash = ContentHash.of("x"), mime = "text/plain", date = null, dateOrigin = null, email = null, facts = emptyMap(),
        )
        val f = PrivacyCheck.findings(listOf(item), emptySet(), today).single { it.ruleId == "card_number" }
        assertEquals("Payment card ×1", f.title)
        assertEquals("Personal data: Payment card ×1", f.message)
        for (p in f.previews) assertFalse('(' in p, p)
        assertEquals("Payment card ×3", PiiRules.chip("card_number", 3))

        // The owner's IMG_1507.HEIC case: an OCR'd receipt with a long order number is not a card.
        val receipt = photo("RECEIPT\nOrder 4539148800000007\nTotal 12.40\nThank you")
        assertTrue(PrivacyCheck.isOcr(receipt))
        assertTrue(PrivacyCheck.findings(listOf(receipt), emptySet(), today).none { it.ruleId == "card_number" })
        // A photo of a card still is.
        assertTrue(PrivacyCheck.findings(listOf(photo("VISA\n$grouped\nVALID THRU 09/29")), emptySet(), today).any { it.ruleId == "card_number" })
    }

    @Test
    fun anOcrReceiptPhotoReadsAsAPhotoNotCode() {
        // Rule level: what a judgment (and a category question) reads first says it is a photo whose
        // text was recognised — never a bare file of text that could read as code. (A model test of
        // the category answer needs Laya and is gated.)
        val receipt = photo("RECEIPT\nCafe Nile\n2 x Latte 90.00\nTOTAL EGP 180.00\nVISA **** 1234")
        assertTrue(receipt.text.startsWith("Photo (text recognised): IMG_1507.HEIC\n\n"), receipt.text)
        assertTrue(PhoneItems.isRecognisedPhotoText(receipt.text))
        assertEquals(ItemKind.IMAGE, receipt.kind)
        assertFalse(receipt.text.startsWith("File:"))
        assertEquals("read on this iPhone (Vision OCR)", receipt.facts["text"])
    }

    @Test
    fun luhnFixturesAreValid() {
        assertTrue(PiiRules.luhnOk(amex) && PiiRules.luhnOk("356938035643809") && PiiRules.luhnOk(luhnComplete("45391488", 16)))
    }
}
