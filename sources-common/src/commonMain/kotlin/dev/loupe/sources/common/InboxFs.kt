package dev.loupe.sources.common

/**
 * The few writes the Inbox makes (epic #7 child 15), inside its own folder only: copies of imported
 * files and members unpacked from a ZIP. New files are created exclusively (never over an existing
 * file, never through a symbolic link) and private to the app; removal never follows links.
 * [SourceFs] stays read-only.
 */
expect object InboxFs {
    fun createDirectories(path: String)

    /** Writes [bytes] to a new file at [path]; fails if anything already exists there. */
    fun writeNew(path: String, bytes: ByteArray)

    /** Removes [path] and, for a folder, everything under it; links are removed, not followed. */
    fun deleteRecursively(path: String)
}
