package dev.loupe.sources.common

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

actual object SourceFs {
    private val NOFOLLOW = arrayOf(LinkOption.NOFOLLOW_LINKS)

    actual fun exists(path: String): Boolean = Files.exists(Path.of(path), *NOFOLLOW)

    actual fun isDirectory(path: String): Boolean = Files.isDirectory(Path.of(path), *NOFOLLOW)

    actual fun isReadable(path: String): Boolean = Files.isReadable(Path.of(path))

    actual fun list(dir: String): List<FsEntry>? = try {
        Files.list(Path.of(dir)).use { s ->
            s.map { p ->
                val a = Files.readAttributes(p, BasicFileAttributes::class.java, *NOFOLLOW)
                FsEntry(p.toString(), p.fileName.toString(), a.isDirectory, a.isRegularFile, a.isSymbolicLink)
            }.toList()
        }
    } catch (_: Exception) {
        null
    }

    actual fun size(path: String): Long = Files.size(Path.of(path))

    actual fun modifiedMillis(path: String): Long = Files.getLastModifiedTime(Path.of(path)).toMillis()

    actual fun readBytes(path: String): ByteArray = Files.readAllBytes(Path.of(path))
}
