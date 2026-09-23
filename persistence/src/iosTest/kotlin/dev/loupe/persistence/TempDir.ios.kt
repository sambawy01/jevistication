package dev.loupe.persistence

import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

actual fun newTempDir(): String {
    val dir = NSTemporaryDirectory().trimEnd('/') + "/loupe-persistence-" + NSUUID().UUIDString
    PlatformFiles.createDirectories(dir)
    return dir
}
