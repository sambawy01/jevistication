package dev.loupe.kit.parity

import dev.loupe.kit.watchers.HOST_PARITY_FUZZ
import dev.loupe.persistence.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * tools/parity/fuzz/pinned.json: a few thousand hrefs and text lines with the hosts Chrome,
 * linkify-it and NSDataDetector give them. Loupe must judge the host the browser opens and every
 * host a linkifier links, or flag the message (HostParityFuzz). Regenerate with tools/parity/fuzz/.
 */
class HostParityFuzzTest {
    @Test
    fun `Loupe judges the host the browser opens and every host a linkifier links`() {
        val text = assertNotNull(PlatformFiles.readText(HOST_PARITY_FUZZ), HOST_PARITY_FUZZ)
        val result = HostParityFuzz.run(text)
        assertTrue(result.cases >= 3000, "only ${result.cases} cases")
        println("host-parity fuzz: ${result.cases} cases, ${result.mismatches.size} differ, ${result.overJudged} over-judged (browser opens no host, Loupe judges one and says safe)")
        assertTrue(result.mismatches.isEmpty(), "${result.mismatches.size} of ${result.cases} cases differ from browser truth:\n" + result.mismatches.joinToString("\n"))
    }
}
