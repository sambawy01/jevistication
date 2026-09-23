package dev.loupe.persistence

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSRecursiveLock
import platform.posix.O_APPEND
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fsync
import platform.posix.open
import platform.posix.read
import platform.posix.rename
import platform.posix.strerror
import platform.posix.write
import kotlinx.cinterop.toKString

@OptIn(ExperimentalForeignApi::class)
actual object PlatformFiles {
    private const val MODE: Int = 420 // 0644

    actual fun createDirectories(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        check(exists(path)) { "could not create $path" }
    }

    actual fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    actual fun readText(path: String): String? {
        if (!exists(path)) return null
        val fd = open(path, O_RDONLY)
        if (fd < 0) fail("open", path)
        try {
            val out = ArrayList<ByteArray>()
            var total = 0
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = buf.usePinned { read(fd, it.addressOf(0), buf.size.convert()) }.toInt()
                if (n < 0) fail("read", path)
                if (n == 0) break
                out += buf.copyOf(n)
                total += n
            }
            val all = ByteArray(total)
            var at = 0
            for (chunk in out) { chunk.copyInto(all, at); at += chunk.size }
            return all.decodeToString()
        } finally {
            close(fd)
        }
    }

    actual fun appendDurably(path: String, text: String) {
        val fd = open(path, O_WRONLY or O_CREAT or O_APPEND, MODE)
        if (fd < 0) fail("open", path)
        try {
            writeAll(fd, text.encodeToByteArray(), path)
            if (fsync(fd) != 0) fail("fsync", path)
        } finally {
            close(fd)
        }
    }

    actual fun writeAtomically(path: String, text: String) {
        val tmp = "$path.tmp"
        val fd = open(tmp, O_WRONLY or O_CREAT or O_TRUNC, MODE)
        if (fd < 0) fail("open", tmp)
        try {
            writeAll(fd, text.encodeToByteArray(), tmp)
            if (fsync(fd) != 0) fail("fsync", tmp)
        } finally {
            close(fd)
        }
        if (rename(tmp, path) != 0) fail("rename", path)
    }

    private fun writeAll(fd: Int, bytes: ByteArray, path: String) {
        var off = 0
        while (off < bytes.size) {
            val n = bytes.usePinned { write(fd, it.addressOf(off), (bytes.size - off).convert()) }.toInt()
            if (n < 0) fail("write", path)
            off += n
        }
    }

    private fun fail(op: String, path: String): Nothing =
        throw IllegalStateException("$op $path failed: ${strerror(errno)?.toKString()}")
}

actual class StoreLock actual constructor() {
    private val lock = NSRecursiveLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
