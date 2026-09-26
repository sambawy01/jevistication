package dev.loupe.persistence

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock as jvmWithLock

// Shared by the desktop JVM and Android (jvmCommonMain), so only APIs Android has at minSdk 29:
// Paths.get rather than Path.of, and a strict UTF-8 decode rather than Files.readString (both Java
// 11 APIs Android lacks below API 33/34); like readString it throws on malformed input.
actual object PlatformFiles {
    actual fun createDirectories(path: String) {
        Files.createDirectories(Paths.get(path))
    }

    actual fun exists(path: String): Boolean = Files.exists(Paths.get(path))

    actual fun readText(path: String): String? {
        val p = Paths.get(path)
        return if (Files.exists(p)) StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(p))).toString() else null
    }

    actual fun appendDurably(path: String, text: String) {
        FileChannel.open(Paths.get(path), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { ch ->
            val buffer = StandardCharsets.UTF_8.encode(text)
            while (buffer.hasRemaining()) ch.write(buffer)
            ch.force(false)
        }
    }

    actual fun writeAtomically(path: String, text: String) {
        val file = Paths.get(path)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            val buffer = StandardCharsets.UTF_8.encode(text)
            while (buffer.hasRemaining()) ch.write(buffer)
            ch.force(false)
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

actual class StoreLock actual constructor() {
    private val lock = ReentrantLock()

    actual fun <T> withLock(block: () -> T): T = lock.jvmWithLock(block)
}
