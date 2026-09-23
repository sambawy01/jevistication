package dev.loupe.engine

/*
 * The engine's only platform seams. Everything else in :engine is common Kotlin.
 *
 * Each JVM actual is the exact call the engine made before the Kotlin Multiplatform port, so JVM
 * behaviour is unchanged by construction; each iOS actual is pinned to it by tests (see the
 * jvmTest parity tests and docs/BUILD.md, "KMP port").
 */

/** SHA-256 of [input]. JVM: `MessageDigest`; iOS: CommonCrypto `CC_SHA256`. */
internal expect fun sha256(input: ByteArray): ByteArray

/** The bundled Public Suffix List snapshot, verbatim. JVM: classpath resource; iOS: embedded. */
internal expect fun bundledPublicSuffixList(): String

/** The recorded `public_suffix_list.dat.sha256` file contents (`<hex>  <name>`). */
internal expect fun bundledPublicSuffixListSha256(): String

/**
 * A non-ASCII, already lowercased label to its IDNA ASCII form, or null when it is not a valid
 * label. JVM: `java.net.IDN.toASCII(label, ALLOW_UNASSIGNED)`; iOS: [Idna.toAsciiLabel].
 */
internal expect fun idnaToAscii(label: String): String?

/**
 * The script of [codePoint] as an opaque id when it is a letter of a real script, or -1 when it
 * is not a letter or its script is COMMON / INHERITED. JVM: `Character.UnicodeScript`; iOS:
 * [UnicodeScriptTable], generated from the same JDK data.
 */
internal expect fun letterScript(codePoint: Int): Int

/** [value] with exactly [decimals] fraction digits: `"%.Nf".format(value)` on the JVM. */
internal expect fun formatFixed(value: Double, decimals: Int): String
