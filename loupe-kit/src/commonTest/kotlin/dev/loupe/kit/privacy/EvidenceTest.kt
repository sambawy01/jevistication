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

    // Station's fixtures (tests/test_scan_evidence.py, commit 4cb9026): Luhn-completed with "7" fill.
    private fun lc(prefix: String, length: Int, fill: Char = '7'): String {
        for (d in 0..9) {
            val n = prefix + fill.toString().repeat(length - prefix.length - 1) + d
            if (PiiRules.luhnOk(n)) return n
        }
        error(prefix)
    }
    private fun grouped(n: String, groups: List<Int> = listOf(4, 4, 4, 4), sep: String = " "): String {
        var i = 0
        return groups.joinToString(sep) { g -> n.substring(i, i + g).also { i += g } }
    }
    private val visa = lc("4539148", 16)
    private val mc2 = lc("2221003", 16)
    private val amex = lc("378282", 15)
    private val meeza = lc("507803", 16)
    private val grouped = CARD.chunked(4).joinToString(" ")

    private fun cardMatches(text: String, ocr: Boolean): List<PiiMatch> =
        PiiRules.detect(PiiRules.foldDigits(text), ocr, today).filter { it.kind == "card_number" }

    private fun cards(text: String, ocr: Boolean): Int =
        PiiCollector(today, ocr).also { it.scan(text) }.signals().firstOrNull { it.type == "card_number" }?.count ?: 0

    /** Station's TRUE_POSITIVES (name, text, ocr). */
    private val truePositives: List<Triple<String, String, Boolean>> by lazy {
        listOf(
            Triple("grouped visa, no word", grouped(visa), true),
            Triple("dashed visa", grouped(visa, sep = "-"), true),
            Triple("amex 4-6-5", grouped(amex, listOf(4, 6, 5)), true),
            Triple("mastercard 2-series with a word", "MASTERCARD " + grouped(mc2), true),
            Triple("visa with expiry after it", grouped(visa) + " 12/27", true),
            Triple("ungrouped with card word", "Card number: $visa", true),
            Triple("ungrouped with 'valid thru'", "$visa VALID THRU 08/29", true),
            Triple("arabic card word", "رقم البطاقة $visa", true),
            Triple("meeza", "ميزة " + grouped(meeza), true),
            Triple("order word but card nearer", "Order paid by card $visa", true),
        )
    }

    /** Station's FALSE_POSITIVES (name, text): never a card, with or without OCR. */
    private val falsePositives: List<Pair<String, String>> by lazy {
        listOf(
            "order number" to "Order $visa",
            "order number with #" to "Talabat order #$visa",
            "tracking number (longer spaced run)" to "Tracking: " + grouped(visa) + " 4821",
            "usps-like 22 digit run" to "9400 1000 0000 1234 5678 90",
            "imei (15 digits, JCB range but no 15-digit JCB)" to "IMEI " + lc("3569380", 15),
            "imei without a word" to lc("3569380", 15),
            "international phone" to "+$visa",
            "phone word" to "Tel $visa",
            "egyptian mobile run" to "01012345678 01112345678",
            "date-stamp reference" to lc("30052024", 14),
            "hex glued" to visa + "ab12",
            "uuid-like" to "a1b2c3d4-$visa",
            "14 digits in the Mastercard range (Mastercard only issues 16)" to lc("2221", 14),
            "invoice reference" to "INV $visa",
            "one repeated pattern" to "4111 1111 1111 1111",
        )
    }

    @Test
    fun stationCardTruePositives() {
        assertEquals(10, truePositives.size)
        for ((name, text, ocr) in truePositives) {
            val got = cardMatches(text, ocr)
            assertEquals(1, got.size, name)
            val chk = got.single().card!!
            assertTrue(chk.luhn && chk.digits in 13..19 && chk.brand != null, name)
            assertEquals(1, cards(text, ocr), name)
        }
    }

    @Test
    fun stationCardFalsePositives() {
        assertEquals(15, falsePositives.size)
        for ((name, text) in falsePositives) {
            assertTrue(cardMatches(text, false).isEmpty() && cardMatches(text, true).isEmpty(), name)
            assertEquals(0, cards(text, false) + cards(text, true), name)
        }
    }

    @Test
    fun ocrTextNeedsACardLayoutOrACardWord() {
        assertTrue(cardMatches("note $visa", false).isNotEmpty() && cardMatches("note $visa", true).isEmpty())
        assertTrue(cardMatches(grouped(visa), true).isNotEmpty())
        assertTrue(cardMatches("VISA $visa", true).isNotEmpty())
        assertTrue(cardMatches("$visa exp 09/28", true).isNotEmpty())
    }

    @Test
    fun theImg1507ShapeIsNotACard() {
        // IMG_1507.HEIC (a photo of an ESP32 breadboard): the silkscreen was read as one 14-digit run
        // starting with 22, Luhn-valid by chance. Mastercard's 2-series only issues 16 digits.
        val run = lc("2210", 14)
        assertTrue(PiiRules.luhnOk(run) && PiiRules.cardBrand(run) == null)
        val text = "GND D23 D22 TX0 RX0 D21 GND D19 D18 D5 $run EN VP VN D34 D35\n3V3 GND"
        assertTrue(cardMatches(text, true).isEmpty() && cardMatches(text, false).isEmpty())
        assertTrue(PiiCollector(today, ocr = true).also { it.scan(text) }.signals().isEmpty())
        assertTrue(PrivacyEvidence.find(text, "card_number", true, today).isEmpty())
    }

    @Test
    fun brandRangesAndLengths() {
        assertEquals("visa", PiiRules.cardBrand(visa)); assertEquals("amex", PiiRules.cardBrand(amex))
        assertEquals("mastercard", PiiRules.cardBrand(mc2)); assertEquals("meeza", PiiRules.cardBrand(meeza))
        assertNull(PiiRules.cardBrand(lc("4", 14)))                                  // Visa: 13, 16 or 19
        assertNull(PiiRules.cardBrand(lc("34", 16)))                                 // Amex: 15 only
        assertFalse(PiiRules.cardOk(lc("9", 16)))                                    // no network
        assertEquals("Visa", CardBrands.of(visa))
        assertEquals("American Express", CardBrands.of(amex))
    }

    @Test
    fun maskedValuesKeepOnlyWhatTheOwnerNeedsToRecognise() {
        assertEquals("•••• •••• •••• " + visa.takeLast(4), PrivacyMask.of("card_number", visa))
        val am = PrivacyMask.of("card_number", amex)
        assertTrue(am.endsWith(amex.takeLast(4)) && amex.take(6) !in am)
        val iban = "EG380019000500000000263180002"
        val m = PrivacyMask.of("iban", iban)
        assertTrue(m.startsWith("EG••") && m.endsWith("0002") && "0019" !in m, m)
        assertEquals("•".repeat(11) + "567", PrivacyMask.of("egypt_national_id", "29001011234567"))
        assertEquals("A••••••78", PrivacyMask.of("passport_number", "A12345678"))
        assertEquals("a•••@example.com", PrivacyMask.of("email", "ahmed@example.com"))
        assertEquals("••••••5678", PrivacyMask.of("phone", "1012345678"))
    }

    @Test
    fun contextLineMasksEverythingAroundTheValue() {
        val text = "Paid by ahmed@example.com with card ${grouped(visa)} and national id 29001011234567, phone +20 100 123 4567 " +
            "and a reference 99887766554433 on 2026-09-01"
        val m = cardMatches(text, false).single()
        val ctx = PrivacyEvidence.context(text, m.start, m.end, PrivacyMask.of(m.kind, m.value), width = 200)
        assertTrue("⟦•••• •••• •••• ${visa.takeLast(4)}⟧" in ctx, ctx)
        for (raw in listOf(visa.take(12), "ahmed@example.com", "29001011234567", "123 4567", "99887766554433")) assertFalse(raw in ctx, raw)
        assertFalse(Regex("""\d{5,}""").containsMatchIn(ctx.replace(visa.takeLast(4), "")), ctx)
    }

    @Test
    fun evidenceIsMaskedAndPointsAtTheMatch() {
        val text = "Photo (text recognised): IMG_1507.HEIC\n\nTOTAL 12.40\nVISA $grouped\nThank you"
        val hits = PrivacyEvidence.find(text, "card_number", ocr = true, today = today, lines = true)
        val h = hits.single()
        assertEquals("•••• •••• •••• " + CARD.takeLast(4), h.masked)
        assertEquals("Visa", h.brand)
        assertEquals("card_number", h.type)
        assertEquals(grouped, text.substring(h.start, h.end))
        assertEquals(4, h.line)
        assertTrue("VISA ⟦•••• •••• •••• ${CARD.takeLast(4)}⟧" in h.context, h.context)
        assertEquals(mapOf("brand" to "visa", "digits" to "16", "luhn" to "true", "grouping" to "4-4-4-4", "cue" to "visa", "ocr" to "true"), h.check)
        assertTrue(h.checks.first().startsWith("Luhn"))
        for (s in listOf(h.masked, h.context) + h.checks) assertFalse(CARD.take(8) in s.filter { it.isDigit() }, s)
        val two = PrivacyEvidence.find("card $grouped iban $IBAN", "card_number", true, today).single()
        assertFalse(IBAN.drop(4).take(10) in two.context, two.context)
        val ib = PrivacyEvidence.find("IBAN $IBAN", "iban", false, today).single()
        assertEquals(mapOf("mod97" to "true", "length" to "29", "country" to "EG"), ib.check)
        assertTrue(ib.masked.startsWith("EG••") && ib.masked.endsWith("0002"))
        assertEquals("c•••@example.com", PrivacyEvidence.find("mail cust7@example.com", "email", false, today).single().masked)
        // Evidence agrees with the check: a rejected number has no evidence either.
        assertTrue(PrivacyEvidence.find("Order no. $CARD", "card_number", true, today).isEmpty())
        assertTrue(PrivacyEvidence.find("Order no. $CARD", "card_number", false, today).isEmpty())
        // contact list: emails then phones, at most 12
        val many = (0 until 20).joinToString("\n") { "c$it@example.com 0100000${it.toString().padStart(4, '0')}" }
        assertEquals(PrivacyEvidence.MAX_HITS, PrivacyEvidence.find(many, "contact_list", false, today).size)
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
        assertTrue(PiiRules.luhnOk(visa) && PiiRules.luhnOk(mc2) && PiiRules.luhnOk(meeza))
    }
}
