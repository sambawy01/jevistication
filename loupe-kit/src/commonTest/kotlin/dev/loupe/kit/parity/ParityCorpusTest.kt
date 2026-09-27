package dev.loupe.kit.parity

import dev.loupe.kit.watchers.PARITY_CORPUS
import dev.loupe.parity.ParityCorpus
import dev.loupe.persistence.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * tools/parity/corpus.json on this platform: the JVM, the iOS simulator (Kotlin/Native's own regex
 * engine and Foundation) and Android's unit tests. The same cases run on an emulator or phone through
 * :android-app's ParityCorpusMain (tools/parity/run-device.sh), and Loupe Station runs them too.
 */
class ParityCorpusTest {
    @Test
    fun `every corpus case gives the expected answer on this platform`() {
        val text = assertNotNull(PlatformFiles.readText(PARITY_CORPUS), PARITY_CORPUS)
        val result = ParityCorpus.run(text)
        assertTrue(result.cases >= 150, "only ${result.cases} cases")
        assertTrue(result.failures.isEmpty(), "${result.failures.size} of ${result.cases} cases differ:\n" + result.failures.joinToString("\n"))
    }
}
