package dev.loupe.sources.common

/** One directory entry, as listed without following symbolic links. */
data class FsEntry(val path: String, val name: String, val isDirectory: Boolean, val isRegularFile: Boolean, val isSymbolicLink: Boolean)

/**
 * The read-only file operations a scan needs, per platform (java.nio on the JVM, Foundation + POSIX
 * on iOS). Nothing here writes, moves or deletes; links are reported, never followed.
 */
expect object SourceFs {
    fun exists(path: String): Boolean

    fun isDirectory(path: String): Boolean

    fun isReadable(path: String): Boolean

    /** Entries of a directory, unsorted; null when it cannot be listed. */
    fun list(dir: String): List<FsEntry>?

    fun size(path: String): Long

    /** Last-modified time, epoch milliseconds. */
    fun modifiedMillis(path: String): Long

    fun readBytes(path: String): ByteArray
}
