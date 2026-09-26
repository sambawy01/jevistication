package dev.loupe.engine

import java.security.MessageDigest
import java.text.Normalizer

/*
 * Android actuals. The aim is the iPhone's and Loupe Station's answers on every phone, so wherever
 * the JVM actual leans on data that varies with the device (ICU's Unicode version, the user's
 * locale), Android takes the portable implementation iOS already uses, which the jvmTest parity
 * tests pin to the JDK. SHA-256 and NFKC come from the platform.
 *
 * That aim is not met everywhere yet: NFKC here is the device's ICU, whose Unicode version varies
 * by API level, and the shared regexes run on ICU, whose \d \s \w \b \p{..} and case folding
 * differ from the JDK's and Kotlin/Native's. See docs/ANDROID-PLAN.md, "Known parity gaps" (B1, B2).
 */

/** `MessageDigest`, as on the JVM: SHA-256 is fixed by the standard, so there is nothing to vary. */
internal actual fun sha256(input: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(input)

// The snapshot compiled in by :engine:generatePslEmbedded (engine/build.gradle.kts), as on iOS.
internal actual fun bundledPublicSuffixList(): String = PslEmbedded.CHUNKS.joinToString("")

internal actual fun bundledPublicSuffixListSha256(): String = PslEmbedded.SHA256_FILE

/**
 * Android's `java.net.IDN` is ICU's IDNA, whose results depend on the device's Unicode version; the
 * portable IDNA 2003 port (as on iOS) follows the JDK's rules instead. Its NFKC step is still the
 * device's (docs/ANDROID-PLAN.md, "Known parity gaps", B2).
 */
internal actual fun idnaToAscii(label: String): String? =
    Idna.toAsciiLabel(label) { Normalizer.normalize(it, Normalizer.Form.NFKC) }

/** The table generated from the JDK's data (as on iOS), not the device's `Character.UnicodeScript`. */
internal actual fun letterScript(codePoint: Int): Int = UnicodeScripts.letterScript(codePoint)

/**
 * The portable formatter (as on iOS): `"%.Nf".format` would use the default locale, and on an
 * Arabic-locale phone that prints Arabic-Indic digits.
 */
internal actual fun formatFixed(value: Double, decimals: Int): String = formatFixedPortable(value, decimals)
