package dev.loupe.kit.parity

import dev.loupe.kit.privacy.SecretRules
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Parity fixes in the privacy rules that the corpus does not reach. */
class PrivacyParityTest {
    @Test
    fun `a secret value runs through a no-break space and is redacted whole`() {
        val out = SecretRules.redactText("api_key=abcDEF123 ghiJKL456mnoPQR789")
        assertFalse("abcDEF123" in out, out)
        assertFalse("ghiJKL456" in out, out)
        assertTrue("[secret]" in out, out)
        // spaces around the `=` may still be no-break spaces
        assertFalse("zyxWVU987" in SecretRules.redactText("api_key = zyxWVU987tsrQPO654"))
    }
}
