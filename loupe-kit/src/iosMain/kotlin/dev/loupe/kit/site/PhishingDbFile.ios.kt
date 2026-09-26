package dev.loupe.kit.site

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Foundation.writeToFile

/**
 * Writes Phishing.Database's index as [PhishingDbBinary]'s flat file (atomically), for the Safari
 * extension and the share sheet to map into memory. Returns false when the write failed.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object PhishingDbFile {
    fun write(index: PhishingDbIndex, path: String): Boolean {
        val bytes = PhishingDbBinary.encode(index)
        return bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()).writeToFile(path, atomically = true)
        }
    }
}
