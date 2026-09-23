package dev.loupe.sources.common

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.posix.O_RDONLY
import platform.posix.R_OK
import platform.posix.S_IFDIR
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.access
import platform.posix.close
import platform.posix.errno
import platform.posix.lstat
import platform.posix.open
import platform.posix.read
import platform.posix.stat
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class)
actual object SourceFs {
    private class Stat(val mode: Int, val size: Long, val mtimeMillis: Long)

    private fun lstatOf(path: String): Stat? = memScoped {
        val st = alloc<stat>()
        if (lstat(path, st.ptr) != 0) return null
        Stat(st.st_mode.toInt(), st.st_size, st.st_mtimespec.tv_sec * 1000L + st.st_mtimespec.tv_nsec / 1_000_000L)
    }

    private fun type(s: Stat) = s.mode and S_IFMT.toInt()

    actual fun exists(path: String): Boolean = lstatOf(path) != null

    actual fun isDirectory(path: String): Boolean = lstatOf(path)?.let { type(it) == S_IFDIR.toInt() } == true

    actual fun isReadable(path: String): Boolean = access(path, R_OK) == 0

    actual fun list(dir: String): List<FsEntry>? {
        val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, error = null) ?: return null
        val base = dir.trimEnd('/')
        return names.map { it as String }.mapNotNull { name ->
            val p = "$base/$name"
            val s = lstatOf(p) ?: return@mapNotNull null
            val t = type(s)
            FsEntry(p, name, t == S_IFDIR.toInt(), t == S_IFREG.toInt(), t == S_IFLNK.toInt())
        }
    }

    actual fun size(path: String): Long = lstatOf(path)?.size ?: fail("stat", path)

    actual fun modifiedMillis(path: String): Long = lstatOf(path)?.mtimeMillis ?: fail("stat", path)

    actual fun readBytes(path: String): ByteArray {
        val fd = open(path, O_RDONLY)
        if (fd < 0) fail("open", path)
        try {
            val chunks = ArrayList<ByteArray>()
            var total = 0
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = buf.usePinned { read(fd, it.addressOf(0), buf.size.convert()) }.toInt()
                if (n < 0) fail("read", path)
                if (n == 0) break
                chunks += buf.copyOf(n)
                total += n
            }
            val all = ByteArray(total)
            var at = 0
            for (c in chunks) { c.copyInto(all, at); at += c.size }
            return all
        } finally {
            close(fd)
        }
    }

    private fun fail(op: String, path: String): Nothing =
        throw IllegalStateException("$op $path failed: ${strerror(errno)?.toKString()}")
}
