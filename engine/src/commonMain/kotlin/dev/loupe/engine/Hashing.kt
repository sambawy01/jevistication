package dev.loupe.engine

/** Hashing helper used to fingerprint judgment wording into ledger rows (A5, A7). */
internal object Hashing {
    fun sha256Hex(input: String): String = sha256Hex(input.encodeToByteArray())

    fun sha256Hex(input: ByteArray): String = hex(sha256(input))
}
