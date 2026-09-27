package dev.loupe.kit.parity

import dev.loupe.kit.watchers.COMMENT_REGRESSION
import dev.loupe.persistence.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * tools/parity/fuzz/comment-pinned.json (fix loop 10): round 10's documents where a `<!--` inside
 * <style> or <textarea> hid a link from a reading that skipped comments while the tree-aware reading
 * misread the tree (124), plus a deterministic sample of the rest, with Chrome's hosts. The union must
 * find every one; the tree-aware reading alone must too (in-cell end tags, h1–h6 end tags).
 */
class CommentRegressionTest {
    @Test
    fun `the comment-in-raw-text regression set loses no link in the union or the tree-aware reading`() {
        val text = assertNotNull(PlatformFiles.readText(COMMENT_REGRESSION), COMMENT_REGRESSION)
        val result = AnchorParityFuzz.run(text)
        println("comment regression: ${result.cases} documents, ${result.missing.size} missing, ${result.treeMissing.size} missed by the tree-aware reading")
        assertTrue(result.cases >= 400, "only ${result.cases} cases")
        assertTrue(result.missing.isEmpty(), result.missing.take(40).joinToString("\n"))
        assertTrue(result.treeMissing.isEmpty(), result.treeMissing.take(40).joinToString("\n"))
    }
}
