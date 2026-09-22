package dev.loupe.engine

/** Exact content fingerprints, used for mechanical dedup (A3). */
object ContentHash {
    /** SHA-256 hex digest of [bytes]. */
    fun of(bytes: ByteArray): String = Hashing.sha256Hex(bytes)

    /** SHA-256 hex digest of [text] encoded as UTF-8. */
    fun of(text: String): String = Hashing.sha256Hex(text)
}

/**
 * Mechanical duplicate detection (A3): byte-identical items, found by hash rather than judged by
 * the model. Near-duplicates are a separate, model-side judgment; this is only the exact case.
 */
object Dedup {
    /**
     * Groups ids that share identical content. Only groups with more than one member are
     * returned, each in the iteration order of [contents].
     */
    fun duplicateGroups(contents: Map<String, ByteArray>): List<List<String>> =
        contents.entries
            .groupBy { ContentHash.of(it.value) }
            .values
            .map { entries -> entries.map { it.key } }
            .filter { it.size > 1 }

    /**
     * The ids that are redundant: every member of a duplicate group except the first one seen.
     * Deciding which copy to *prefer* is a judgment; this only reports exact redundancy.
     */
    fun redundant(contents: Map<String, ByteArray>): Set<String> =
        duplicateGroups(contents).flatMap { it.drop(1) }.toSet()
}
