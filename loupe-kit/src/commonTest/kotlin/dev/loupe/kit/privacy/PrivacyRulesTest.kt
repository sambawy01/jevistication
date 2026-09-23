package dev.loupe.kit.privacy

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Loupe Station's rule tests, ported case for case from the owner's repository (`~/laya-studio`,
 * `tests/test_scan.py`, commit ea7697a4f78e9a648ba49fc8bf9d13226c26cb0e): `test_pii_rules_unit`,
 * `test_secret_rules_unit`, `test_secret_regexes_are_linear_on_hostile_text`, and the per-file
 * secret assertions of `test_secret_detection_is_redacted_everywhere` (same fixtures, same values).
 */
class PrivacyRulesTest {
    private val today = LocalDate(2026, 9, 23)

    // The station's fixtures, verbatim (split so no scanner flags this file as a leak).
    companion object {
        val ANTHROPIC = "sk-ant-" + "api03-" + "Zq8".repeat(30)
        val AWS = "AKIA" + "QWERTYUIOPASDFGH"
        val STRIPE = "sk_live_" + "4eC39HqLyjWDarjtT1zdp7dc"
        val GITHUB = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"
        const val PASSWORD = "Tr0ub4dor&3horse"
        val JWT = "eyJhbGciOiJIUzI1NiJ9" + ".eyJzdWIiOiIxMjM0NTY3ODkwIn0" + ".dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        val ALL_SECRETS = listOf(ANTHROPIC, AWS, STRIPE, GITHUB, PASSWORD, JWT)

        val NIDS = listOf("29001011234567", "30512310112345", "28807152101234")
        const val IBAN = "EG380019000500000000263180002"
        val CARD = luhnComplete("45391488", 16)
        const val PASSPORT_NO = "A12345678"

        fun luhnComplete(prefix: String, length: Int): String {
            for (d in 0..9) {
                val n = prefix + "0".repeat(length - prefix.length - 1) + d
                if (PiiRules.luhnOk(n)) return n
            }
            error("no Luhn digit")
        }
    }

    @Test
    fun piiRulesUnit() {
        for (nid in NIDS) assertTrue(PiiRules.validEgyptNid(nid, today), nid)
        // century, month, day, governorate, future
        for (bad in listOf("19001011234567", "29013011234567", "29002301234567", "29001019934567", "39901011234567")) {
            assertFalse(PiiRules.validEgyptNid(bad, today), bad)
        }
        assertTrue(PiiRules.ibanOk(IBAN) && !PiiRules.ibanOk(IBAN.dropLast(1) + "3"))
        assertTrue(PiiRules.cardOk(CARD) && !PiiRules.cardOk(CARD.dropLast(1) + ((CARD.last() - '0' + 1) % 10)))
        var c = PiiCollector(today)
        val text = "الرقم القومي ٢٩٠٠١٠١١٢٣٤٥٦٧ | Passport No: $PASSPORT_NO | رقم جواز السفر B7654321 | IBAN " +
            IBAN.chunked(4).joinToString(" ") + " | card " + CARD.chunked(4).joinToString(" ") + " | " +
            "+20 100 123 4567, 0020 122 555 6666, 01012345678 | not an id 12345678901234"
        c.scan(text, 0, text.length)
        val got = c.signals().associateBy { it.type }
        assertEquals(1, got.getValue("egypt_national_id").count)
        assertEquals(2, got.getValue("passport_number").count)
        assertEquals(1, got.getValue("iban").count)
        assertEquals(1, got.getValue("card_number").count)
        assertEquals(3, got.getValue("phone").distinct)
        for (s in got.values) for (p in s.previews) assertTrue(p.split("…")[0].length <= 1, p)

        // bulk contacts -> contact list; payroll headers in English and Arabic
        c = PiiCollector(today)
        val t = (0 until 15).joinToString("\n") { i -> "cust$i@example.com, 010" + i.toString().padStart(8, '0') }
        c.scan(t, 0, t.length)
        assertTrue(c.signals().map { it.type }.toSet().containsAll(setOf("email", "phone", "contact_list")))
        for (header in listOf("Employee Name,Basic Salary,Net Pay,Overtime", "اسم الموظف,الراتب الأساسي,خصومات,صافي الراتب")) {
            c = PiiCollector(today)
            c.scan(header, 0, header.length)
            assertTrue("payroll_headers" in c.signals().map { it.type }, header)
        }
        for (prose in listOf("the employee enjoyed the dinner", "## Payroll tests\nEach department ran the suite.")) {
            c = PiiCollector(today)
            c.scan(prose, 0, prose.length)
            assertEquals(emptyList(), c.signals(), prose)
        }
    }

    @Test
    fun secretRulesUnit() {
        val found = SecretRules.findSecrets(
            "aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\n" +
                "passport_number: A1234567\nsecretKey=q8WmZ3pLx7vN2rT5yB9kHc4J\n",
        )
        val kinds = found.map { it.type }
        assertEquals("aws_secret_key", kinds[0])
        assertTrue("high_entropy_secret" in kinds)
        assertTrue(found.all { "A1234567" !in it.preview })
        assertTrue("wJal…" in found[0].preview && "wJalr" !in found[0].preview)
    }

    /** `test_secret_detection_is_redacted_everywhere`, file by file: kinds, previews, lines, placeholders. */
    @Test
    fun secretDetectionPerFile() {
        val env = SecretRules.findSecrets("ANTHROPIC_API_KEY=$ANTHROPIC\nAWS_ACCESS_KEY_ID=$AWS\nDB_PASSWORD=$PASSWORD\n", ".env", isEnv = true)
        assertTrue(env.map { it.type }.toSet().containsAll(setOf("env_file", "anthropic_key", "aws_access_key", "password_assignment")), env.toString())
        for (s in env) assertTrue("…" in s.preview || s.type == "env_file", s.preview)
        val anth = env.first { it.type == "anthropic_key" }
        assertEquals("sk-a… (Anthropic API key)", anth.preview)
        assertEquals(1, anth.line)

        val config = """{"stripe": "$STRIPE", "gh": "$GITHUB", "session": "$JWT"}"""
        assertTrue(SecretRules.findSecrets(config).map { it.type }.toSet().containsAll(setOf("stripe_key", "github_token", "jwt")))
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----\n"
        assertEquals(listOf("private_key"), SecretRules.findSecrets(pem).map { it.type })
        assertEquals(emptyList(), SecretRules.findSecrets("password: changeme\napi_key = <your key here>\ntokenizer: bert\n"))

        // No raw value, nor a long substring of one, in anything a finding holds.
        val everything = (env + SecretRules.findSecrets(config) + SecretRules.findSecrets(pem)).toString()
        for (secret in ALL_SECRETS) {
            assertFalse(secret in everything, secret)
            assertFalse(secret.substring(6, minOf(24, secret.length)) in everything, secret)
        }
    }

    @Test
    fun maskingHidesEveryShape() {
        val text = "mail cust7@example.com iban $IBAN card $CARD id ${NIDS[0]} phone +20 100 123 4567 passport $PASSPORT_NO acct 1234567890"
        val masked = PiiRules.maskPii(text)
        for (v in listOf("cust7@example.com", IBAN, CARD, NIDS[0], "100 123 4567", PASSPORT_NO, "1234567890")) {
            assertFalse(v in masked, "$v in $masked")
        }
        assertTrue("[email]" in masked && "[iban]" in masked && "[national-id]" in masked)
        val red = SecretRules.redactText("ANTHROPIC_API_KEY=$ANTHROPIC\nDB_PASSWORD=$PASSWORD\ntoken: changeme")
        for (s in listOf(ANTHROPIC, PASSWORD)) assertFalse(s in red)
        assertTrue("token: changeme" in red)
    }

    @Test
    fun secretRegexesAreLinearOnHostileText() {
        // The station allows 1 s per text on CPython; Kotlin/Native's regex engine is slower, so
        // the bound is looser here: what this catches is a polynomial blow-up, not a constant.
        for (text in listOf("x".repeat(50_000), "ab1".repeat(20_000), "a_".repeat(20_000), "password".repeat(5_000), "token=".repeat(5_000))) {
            val t0 = TimeSource.Monotonic.markNow()
            SecretRules.findSecrets(text)
            SecretRules.redactText(text)
            PiiRules.maskPii(text)
            val ms = t0.elapsedNow().inWholeMilliseconds
            assertTrue(ms < 5_000, "${text.take(12)}: $ms ms")
        }
    }
}
