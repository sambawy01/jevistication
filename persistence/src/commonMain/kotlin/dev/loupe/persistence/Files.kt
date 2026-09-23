package dev.loupe.persistence

/**
 * The few file operations the stores need, per platform. Paths are absolute strings.
 *
 * Durability is the contract, not a detail: [appendDurably] returns only once the bytes are on
 * disk (fsync), and [writeAtomically] replaces a file by writing a synced temporary sibling and
 * renaming it over, so a crash leaves the old file or the new one, never half of either.
 */
expect object PlatformFiles {
    fun createDirectories(path: String)

    fun exists(path: String): Boolean

    /** The file's text (UTF-8), or null when it does not exist. */
    fun readText(path: String): String?

    /** Appends [text] (UTF-8), creating the file, and syncs it to disk before returning. */
    fun appendDurably(path: String, text: String)

    /** Replaces the file's contents with [text] via a synced temporary file and a rename. */
    fun writeAtomically(path: String, text: String)
}

/** A mutual-exclusion lock, so appends from two threads never interleave. */
expect class StoreLock() {
    fun <T> withLock(block: () -> T): T
}

internal fun joinPath(dir: String, name: String): String = if (dir.endsWith("/")) dir + name else "$dir/$name"
