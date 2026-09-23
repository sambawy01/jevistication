package dev.loupe.engine

import java.net.IDN
import java.security.MessageDigest

internal actual fun sha256(input: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(input)

private const val PSL_RESOURCE = "public_suffix_list.dat"

private fun resourceText(name: String): String {
    val stream = PublicSuffix::class.java.getResourceAsStream(name)
        ?: error("bundled $name is missing from the engine's resources")
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}

internal actual fun bundledPublicSuffixList(): String = resourceText(PSL_RESOURCE)

internal actual fun bundledPublicSuffixListSha256(): String = resourceText("$PSL_RESOURCE.sha256")

internal actual fun idnaToAscii(label: String): String? =
    runCatching { IDN.toASCII(label, IDN.ALLOW_UNASSIGNED).lowercase() }.getOrNull()

internal actual fun letterScript(codePoint: Int): Int {
    if (!Character.isLetter(codePoint)) return -1
    val script = Character.UnicodeScript.of(codePoint)
    return if (script == Character.UnicodeScript.COMMON || script == Character.UnicodeScript.INHERITED) {
        -1
    } else {
        script.ordinal
    }
}

internal actual fun formatFixed(value: Double, decimals: Int): String = "%.${decimals}f".format(value)
