package dev.loupe.kit.parity

import dev.loupe.kit.watchers.ANCHOR_PARITY_FUZZ
import dev.loupe.persistence.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * tools/parity/fuzz/anchor-pinned.json: 2,000 generated mail documents with the hosts of the links in
 * the document Chrome's DOMParser builds. Loupe must judge every one (AnchorParityFuzz).
 */
class AnchorParityFuzzTest {
    @Test
    fun `Loupe finds every link Chrome's document has`() {
        val text = assertNotNull(PlatformFiles.readText(ANCHOR_PARITY_FUZZ), ANCHOR_PARITY_FUZZ)
        val result = AnchorParityFuzz.run(text)
        println("anchor-parity fuzz: ${result.cases} documents, ${result.missing.size} missing a link Chrome has, ${result.overFound} with more links than Chrome")
        assertTrue(result.cases >= 2000, "only ${result.cases} cases")
        assertTrue(result.missing.isEmpty(), "${result.missing.size} of ${result.cases} documents lose a link:\n" + result.missing.joinToString("\n"))
    }
}
