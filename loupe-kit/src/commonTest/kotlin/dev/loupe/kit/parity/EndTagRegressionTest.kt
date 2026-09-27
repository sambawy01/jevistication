package dev.loupe.kit.parity

import dev.loupe.kit.watchers.END_TAG_REGRESSION
import dev.loupe.persistence.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * tools/parity/fuzz/end-pinned.json (fix loop 9): round 9's documents that end SVG/MathML (or do not)
 * with an end tag before a raw-text element — every hand-written case, every document 77bb9c5 missed and
 * a deterministic sample — with Chrome's hosts. The union must find every one, and so must the
 * tree-aware reading alone (it decides link text and <base>).
 */
class EndTagRegressionTest {
    @Test
    fun `the end-tag regression set loses no link in the union or the tree-aware reading`() {
        val text = assertNotNull(PlatformFiles.readText(END_TAG_REGRESSION), END_TAG_REGRESSION)
        val result = AnchorParityFuzz.run(text)
        println("end-tag regression: ${result.cases} documents, ${result.missing.size} missing, ${result.treeMissing.size} missed by the tree-aware reading")
        assertTrue(result.cases >= 800, "only ${result.cases} cases")
        assertTrue(result.missing.isEmpty(), result.missing.joinToString("\n"))
        assertTrue(result.treeMissing.isEmpty(), result.treeMissing.take(40).joinToString("\n"))
    }
}
