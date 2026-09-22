package dev.loupe.engine

import java.security.MessageDigest

/** Hashing helper used to fingerprint judgment wording into ledger rows (A5, A7). */
internal object Hashing {
    fun sha256Hex(input: String): String = sha256Hex(input.toByteArray(Charsets.UTF_8))

    fun sha256Hex(input: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input)
            .joinToString("") { "%02x".format(it) }
}
