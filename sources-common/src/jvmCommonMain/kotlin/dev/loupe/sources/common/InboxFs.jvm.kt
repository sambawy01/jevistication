package dev.loupe.sources.common

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.stream.Collectors

// Shared by the desktop JVM and Android (jvmCommonMain): Paths.get and Collectors.toList, since
// Path.of and Stream.toList only arrived in Android API 34.
actual object InboxFs {
    actual fun createDirectories(path: String) {
        Files.createDirectories(Paths.get(path))
    }

    actual fun writeNew(path: String, bytes: ByteArray) {
        Files.write(Paths.get(path), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
    }

    actual fun deleteRecursively(path: String) {
        val root = Paths.get(path)
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.list(root).use { s -> s.collect(Collectors.toList()) }.forEach { deleteRecursively(it.toString()) }
        }
        Files.deleteIfExists(root)
    }
}
