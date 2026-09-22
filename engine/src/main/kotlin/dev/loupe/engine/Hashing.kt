package dev.loupe.engine

import java.security.MessageDigest

/** Hashing helper used to fingerprint judgment wording into ledger rows (A5, A7). */
internal object Hashing {
    fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
