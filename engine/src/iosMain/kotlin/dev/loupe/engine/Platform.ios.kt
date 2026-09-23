package dev.loupe.engine

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCompatibilityMapping

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256(input: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    // An empty ByteArray cannot be pinned to a valid address; hash a 1-byte buffer with length 0.
    val data = if (input.isEmpty()) ByteArray(1) else input
    data.usePinned { src ->
        digest.usePinned { dst ->
            CC_SHA256(src.addressOf(0), input.size.convert(), dst.addressOf(0))
        }
    }
    return digest.toByteArray()
}

// Kotlin/Native frameworks carry no classpath resources, so the snapshot is compiled in as a
// constant generated at build time from the same file the JVM reads (engine/build.gradle.kts).
internal actual fun bundledPublicSuffixList(): String = PslEmbedded.CHUNKS.joinToString("")

internal actual fun bundledPublicSuffixListSha256(): String = PslEmbedded.SHA256_FILE

@Suppress("CAST_NEVER_SUCCEEDS")
private fun nfkc(s: String): String = (s as NSString).precomposedStringWithCompatibilityMapping

internal actual fun idnaToAscii(label: String): String? = Idna.toAsciiLabel(label, ::nfkc)

internal actual fun letterScript(codePoint: Int): Int = UnicodeScripts.letterScript(codePoint)

internal actual fun formatFixed(value: Double, decimals: Int): String = formatFixedPortable(value, decimals)
