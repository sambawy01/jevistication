package dev.loupe.persistence

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock as jvmWithLock

actual object PlatformFiles {
    actual fun createDirectories(path: String) {
        Files.createDirectories(Path.of(path))
    }

    actual fun exists(path: String): Boolean = Files.exists(Path.of(path))

    actual fun readText(path: String): String? {
        val p = Path.of(path)
        return if (Files.exists(p)) Files.readString(p, StandardCharsets.UTF_8) else null
    }

    actual fun appendDurably(path: String, text: String) {
        FileChannel.open(Path.of(path), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { ch ->
            val buffer = StandardCharsets.UTF_8.encode(text)
            while (buffer.hasRemaining()) ch.write(buffer)
            ch.force(false)
        }
    }

    actual fun writeAtomically(path: String, text: String) {
        val file = Path.of(path)
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
