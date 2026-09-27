package dev.loupe.kit.parity

import dev.loupe.kit.watchers.TAG_SOUP_FUZZ
import dev.loupe.persistence.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * tools/parity/fuzz/tagsoup-pinned.json (fix loop 11): round 11's 2,000 tag-soup documents (tables,
 * select, template, SVG/MathML, raw text, comments, forms, frames mixed at random, each ending with a
 * link), with the hosts of the links Chrome renders. The union, the tree-aware reading and the no-skip
 * reading must each find every one.
 */
class TagSoupFuzzTest {
    @Test
    fun `round 11 tag soup loses no link in the union or either reading alone`() {
        val text = assertNotNull(PlatformFiles.readText(TAG_SOUP_FUZZ), TAG_SOUP_FUZZ)
        val result = AnchorParityFuzz.run(text)
        println("tag soup: ${result.cases} documents, ${result.missing.size} missing, ${result.treeMissing.size} tree-aware, ${result.candidateMissing.size} no-skip")
        assertTrue(result.cases >= 2000, "only ${result.cases} cases")
        assertTrue(result.missing.isEmpty(), result.missing.take(40).joinToString("\n"))
        assertTrue(result.treeMissing.isEmpty(), result.treeMissing.take(40).joinToString("\n"))
        assertTrue(result.candidateMissing.isEmpty(), result.candidateMissing.take(40).joinToString("\n"))
    }
}
