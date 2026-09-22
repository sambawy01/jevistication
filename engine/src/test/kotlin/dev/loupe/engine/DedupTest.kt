package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DedupTest {

    private fun bytes(s: String) = s.toByteArray()

    @Test
    fun `identical content hashes identically`() {
        assertEquals(ContentHash.of("receipt"), ContentHash.of("receipt"))
        assertEquals(ContentHash.of(bytes("receipt")), ContentHash.of("receipt"))
    }

    @Test
    fun `different content hashes differently`() {
        assertNotEquals(ContentHash.of("receipt"), ContentHash.of("Receipt"))
    }

    @Test
    fun `hash is a sha256 hex digest`() {
        val hash = ContentHash.of("")
        assertEquals(64, hash.length)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hash,
        )
    }

    @Test
    fun `finds groups of byte-identical items`() {
        val contents = linkedMapOf(
            "photo-1" to bytes("same"),
            "photo-2" to bytes("different"),
            "photo-3" to bytes("same"),
        )
        val groups = Dedup.duplicateGroups(contents)
        assertEquals(1, groups.size)
        assertEquals(listOf("photo-1", "photo-3"), groups.single())
    }

    @Test
    fun `reports everything after the first copy as redundant`() {
        val contents = linkedMapOf(
            "a" to bytes("x"),
            "b" to bytes("x"),
            "c" to bytes("x"),
            "d" to bytes("y"),
        )
        assertEquals(setOf("b", "c"), Dedup.redundant(contents))
    }

    @Test
    fun `finds nothing when every item is distinct`() {
        val contents = linkedMapOf("a" to bytes("1"), "b" to bytes("2"))
        assertTrue(Dedup.duplicateGroups(contents).isEmpty())
        assertTrue(Dedup.redundant(contents).isEmpty())
    }
}
