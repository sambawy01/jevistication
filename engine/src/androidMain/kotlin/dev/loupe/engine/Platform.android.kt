package dev.loupe.engine

import java.security.MessageDigest

/*
 * Android actuals. The aim is the iPhone's and Loupe Station's answers on every phone, so wherever
 * the JVM actual leans on data that varies with the device (ICU's Unicode version, the user's
 * locale), Android takes the portable implementation iOS already uses, which the jvmTest parity
 * tests pin to the JDK. SHA-256 comes from the platform. IDNA and NFKC are no longer platform
 * seams at all: they read Loupe's pinned Unicode data (PortableText, UnicodeDataTable), and the shared
 * regexes spell their classes out (docs/ANDROID-PLAN.md, "Known parity gaps", B1 and B2, closed).
 */

/** `MessageDigest`, as on the JVM: SHA-256 is fixed by the standard, so there is nothing to vary. */
internal actual fun sha256(input: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(input)

// The snapshot compiled in by :engine:generatePslEmbedded (engine/build.gradle.kts), as on iOS.
internal actual fun bundledPublicSuffixList(): String = PslEmbedded.CHUNKS.joinToString("")

internal actual fun bundledPublicSuffixListSha256(): String = PslEmbedded.SHA256_FILE

/** The table generated from the JDK's data (as on iOS), not the device's `Character.UnicodeScript`. */
internal actual fun letterScript(codePoint: Int): Int = UnicodeScripts.letterScript(codePoint)

/**
 * The portable formatter (as on iOS): `"%.Nf".format` would use the default locale, and on an
 * Arabic-locale phone that prints Arabic-Indic digits.
 */
internal actual fun formatFixed(value: Double, decimals: Int): String = formatFixedPortable(value, decimals)
