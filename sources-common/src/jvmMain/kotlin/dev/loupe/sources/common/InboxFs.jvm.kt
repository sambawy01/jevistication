package dev.loupe.sources.common

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

actual object InboxFs {
    actual fun createDirectories(path: String) {
        Files.createDirectories(Path.of(path))
    }

    actual fun writeNew(path: String, bytes: ByteArray) {
        Files.write(Path.of(path), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
    }

    actual fun deleteRecursively(path: String) {
        val root = Path.of(path)
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.list(root).use { s -> s.toList() }.forEach { deleteRecursively(it.toString()) }
        }
        Files.deleteIfExists(root)
    }
}
