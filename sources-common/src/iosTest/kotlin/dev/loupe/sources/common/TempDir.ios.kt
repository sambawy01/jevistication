package dev.loupe.sources.common

import dev.loupe.persistence.PlatformFiles
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

actual fun newTempDir(): String {
    val dir = NSTemporaryDirectory().trimEnd('/') + "/loupe-sources-" + NSUUID().UUIDString
    PlatformFiles.createDirectories(dir)
    return dir
}
