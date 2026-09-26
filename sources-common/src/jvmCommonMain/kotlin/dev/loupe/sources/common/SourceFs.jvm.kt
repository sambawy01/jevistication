package dev.loupe.sources.common

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.stream.Collectors

// Shared by the desktop JVM and Android (jvmCommonMain): Paths.get and Collectors.toList, since
// Path.of and Stream.toList only arrived in Android API 34.
actual object SourceFs {
    private val NOFOLLOW = arrayOf(LinkOption.NOFOLLOW_LINKS)

    actual fun exists(path: String): Boolean = Files.exists(Paths.get(path), *NOFOLLOW)

    actual fun isDirectory(path: String): Boolean = Files.isDirectory(Paths.get(path), *NOFOLLOW)

    actual fun isReadable(path: String): Boolean = Files.isReadable(Paths.get(path))

    actual fun list(dir: String): List<FsEntry>? = try {
        Files.list(Paths.get(dir)).use { s ->
            s.map { p ->
                val a = Files.readAttributes(p, BasicFileAttributes::class.java, *NOFOLLOW)
                FsEntry(p.toString(), p.fileName.toString(), a.isDirectory, a.isRegularFile, a.isSymbolicLink)
            }.collect(Collectors.toList())
        }
    } catch (_: Exception) {
        null
    }

    actual fun size(path: String): Long = Files.size(Paths.get(path))

    actual fun modifiedMillis(path: String): Long = Files.getLastModifiedTime(Paths.get(path)).toMillis()

    actual fun readBytes(path: String): ByteArray = Files.readAllBytes(Paths.get(path))
}
