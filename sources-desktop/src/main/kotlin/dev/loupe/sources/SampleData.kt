package dev.loupe.sources

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The built-in sample dataset: synthetic, clearly fake receipts, SPECIMEN identity documents,
 * subscription emails, a phishing email, an impersonation attempt, a premium-increase letter,
 * duplicates and files that must be skipped — so the app can be demonstrated, and tested in CI,
 * without anyone's personal data.
 *
 * It ships as classpath resources. [materialize] copies it into a folder the app owns (never the
 * user's own folders), and the app then scans that copy exactly as it would scan real files.
 */
object SampleData {
    private const val ROOT = "/dev/loupe/sources/sample/"

    /** Every file in the dataset, relative to its root. */
    val files: List<String> by lazy {
        val index = SampleData::class.java.getResourceAsStream(ROOT + "INDEX.txt")
            ?: error("sample dataset index missing from the classpath")
        index.bufferedReader().use { r -> r.readLines().map { it.trim() }.filter { it.isNotEmpty() } }
    }

    /** The folder of documents, relative to a materialised root. */
    const val DOCUMENTS: String = "documents"

    /** The mail export (a folder of `.eml` files and one `.mbox`), relative to a materialised root. */
    const val MAIL: String = "mail"

    /**
     * Copies the dataset under [target] (created if needed) and returns [target]. Existing files are
     * replaced, so a stale copy from an older build is refreshed.
     */
    fun materialize(target: Path): Path {
        for (relative in files) {
            require(!relative.contains("..")) { "sample index entry escapes its root: $relative" }
            val destination = target.resolve(relative)
            Files.createDirectories(destination.parent)
            val stream = SampleData::class.java.getResourceAsStream(ROOT + relative)
                ?: error("sample file listed in the index but missing: $relative")
            stream.use { Files.copy(it, destination, StandardCopyOption.REPLACE_EXISTING) }
        }
        return target
    }
}
