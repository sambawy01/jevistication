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
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_NOFOLLOW
import platform.posix.O_WRONLY
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.close
import platform.posix.errno
import platform.posix.lstat
import platform.posix.open
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.strerror
import platform.posix.unlink
import platform.posix.write

@OptIn(ExperimentalForeignApi::class)
actual object InboxFs {
    actual fun createDirectories(path: String) {
        if (!NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)) fail("mkdir", path)
    }

    actual fun writeNew(path: String, bytes: ByteArray) {
        val fd = open(path, O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW, 0x180)   // 0600
        if (fd < 0) fail("create", path)
        try {
            var at = 0
            while (at < bytes.size) {
                val n = bytes.usePinned { write(fd, it.addressOf(at), (bytes.size - at).convert()) }.toInt()
                if (n <= 0) fail("write", path)
                at += n
            }
        } finally {
            close(fd)
        }
    }

    actual fun deleteRecursively(path: String) {
        val isDir = memScoped {
            val st = alloc<stat>()
            if (lstat(path, st.ptr) != 0) return
            (st.st_mode.toInt() and S_IFMT.toInt()) == S_IFDIR.toInt()
        }
        if (isDir) {
            NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, null)?.forEach { deleteRecursively(path.trimEnd('/') + "/" + (it as String)) }
            rmdir(path)
        } else {
            unlink(path)
        }
    }

    private fun fail(op: String, path: String): Nothing =
        throw IllegalStateException("$op $path failed: ${strerror(errno)?.toKString()}")
}
