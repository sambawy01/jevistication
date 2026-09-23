package dev.loupe.sources.common

import kotlin.io.encoding.Base64

/**
 * Builds ZIP archives for the archive-safety tests, byte by byte, so malicious fixtures (traversal
 * names, symbolic links, lying sizes, encrypted flags, too many members) are exact and identical on
 * the JVM and the iOS simulator.
 */
internal class TestZip {
    class Member(
        val name: String,
        val data: ByteArray,
        val method: Int = 0,
        val flags: Int = 0,
        val madeBy: Int = (3 shl 8) or 20,
        val unixMode: Int = 0x81A4,          // regular file, 0644
        val declaredSize: Long? = null,
        val crc: Long? = null,
        val plain: ByteArray = data,
    )

    private val members = mutableListOf<Member>()

    fun stored(name: String, text: String) = apply { members += Member(name, text.encodeToByteArray()) }

    fun add(m: Member) = apply { members += m }

    fun bytes(): ByteArray {
        val out = mutableListOf<Byte>()
        fun u16(v: Int) { out += (v and 0xFF).toByte(); out += ((v ushr 8) and 0xFF).toByte() }
        fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16(((v ushr 16) and 0xFFFF).toInt()) }
        val offsets = mutableListOf<Long>()
        for (m in members) {
            offsets += out.size.toLong()
            val name = m.name.encodeToByteArray()
            u32(0x04034b50); u16(20); u16(m.flags or 0x800); u16(m.method); u16(0); u16(0)
            u32(m.crc ?: Crc32.of(m.plain)); u32(m.data.size.toLong()); u32(m.declaredSize ?: m.plain.size.toLong())
            u16(name.size); u16(0); out.addAll(name.toList()); out.addAll(m.data.toList())
        }
        val cdStart = out.size.toLong()
        for ((i, m) in members.withIndex()) {
            val name = m.name.encodeToByteArray()
            u32(0x02014b50); u16(m.madeBy); u16(20); u16(m.flags or 0x800); u16(m.method); u16(0); u16(0)
            u32(m.crc ?: Crc32.of(m.plain)); u32(m.data.size.toLong()); u32(m.declaredSize ?: m.plain.size.toLong())
            u16(name.size); u16(0); u16(0); u16(0); u16(0); u32(m.unixMode.toLong() shl 16); u32(offsets[i])
            out.addAll(name.toList())
        }
        val cdSize = out.size.toLong() - cdStart
        u32(0x06054b50); u16(0); u16(0); u16(members.size); u16(members.size); u32(cdSize); u32(cdStart); u16(0)
        return out.toByteArray()
    }

    companion object {
        /** Raw deflate (zlib level 9, dynamic Huffman) of [DEFLATED_TEXT]: 6 900 bytes in 129. */
        val DEFLATED: ByteArray = Base64.decode(
            "7dAxCoMwAIbR3bNESVK1zSi4dvIEQVIU1IJE6PFdHPuvTt/8ttfHnMw77eMUt2y69XtsufDWt6V1pW3MkPcU188y/0yoQrjIa3poqjU1mlpNT00vTUGSs5r0hvu70RNLLLHEEkssscQSSyyxxBJLLLHEEkssscQSSyyxxBJ7V+wJ",
        )
        val DEFLATED_TEXT: String = ("Date,Merchant,Amount\n" + (1..12).joinToString("") { m -> "2026-${m.toString().padStart(2, '0')}-05,Streamflix,9.99\n" }).repeat(20)

        /** Raw deflate of "hello hello hello loupe" (fixed Huffman). */
        val FIXED: ByteArray = Base64.decode("y0jNyclXyEAic/JLC1IB")
    }
}
