package dev.loupe.sources.common

/**
 * Raw DEFLATE (RFC 1951) and CRC-32, in common Kotlin, for [ZipReader] (epic #7 child 15). No
 * third-party code: a straightforward canonical-Huffman decoder in the manner of zlib's `puff.c`
 * reference decoder (written from the RFC, not copied). Output is capped: past [maxOut] bytes the
 * stream is abandoned with [TooLarge], so a stream that lies about its size cannot fill memory.
 */
internal object Inflate {
    class TooLarge : Exception("expands past its limit")

    class Corrupt(message: String) : Exception(message)

    private class Huffman(val count: IntArray, val symbol: IntArray)

    private class Reader(val src: ByteArray, var pos: Int, val end: Int) {
        var bitBuf = 0
        var bitCnt = 0

        fun bits(need: Int): Int {
            var v = bitBuf
            while (bitCnt < need) {
                if (pos >= end) throw Corrupt("truncated deflate stream")
                v = v or ((src[pos++].toInt() and 0xFF) shl bitCnt)
                bitCnt += 8
            }
            bitBuf = v ushr need
            bitCnt -= need
            return v and ((1 shl need) - 1)
        }

        fun alignToByte() {
            bitBuf = 0
            bitCnt = 0
        }
    }

    private class Out(private val max: Long) {
        var buf = ByteArray(64 * 1024)
        var size = 0

        fun put(b: Byte) {
            if (size.toLong() >= max) throw TooLarge()
            if (size == buf.size) buf = buf.copyOf(minOf(Int.MAX_VALUE - 8L, buf.size * 2L).toInt())
            buf[size++] = b
        }
    }

    private val LEN_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LEN_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DIST_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073,
        4097, 6145, 8193, 12289, 16385, 24577,
    )
    private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
    private val CL_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    private val FIXED_LEN: Huffman by lazy {
        val lengths = IntArray(288) { i -> if (i < 144) 8 else if (i < 256) 9 else if (i < 280) 7 else 8 }
        build(lengths, 288)
    }
    private val FIXED_DIST: Huffman by lazy { build(IntArray(30) { 5 }, 30) }

    /** Decompresses [length] bytes of raw deflate at [offset]; at most [maxOut] bytes of output. */
    fun inflate(src: ByteArray, offset: Int, length: Int, maxOut: Long): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= src.size)
        val r = Reader(src, offset, offset + length)
        val out = Out(maxOut)
        do {
            val last = r.bits(1)
            when (r.bits(2)) {
                0 -> stored(r, out)
                1 -> codes(r, out, FIXED_LEN, FIXED_DIST)
                2 -> dynamic(r, out)
                else -> throw Corrupt("invalid deflate block type")
            }
        } while (last == 0)
        return out.buf.copyOf(out.size)
    }

    private fun stored(r: Reader, out: Out) {
        r.alignToByte()
        if (r.pos + 4 > r.end) throw Corrupt("truncated stored block")
        val len = (r.src[r.pos].toInt() and 0xFF) or ((r.src[r.pos + 1].toInt() and 0xFF) shl 8)
        val nlen = (r.src[r.pos + 2].toInt() and 0xFF) or ((r.src[r.pos + 3].toInt() and 0xFF) shl 8)
        if (len != (nlen.inv() and 0xFFFF)) throw Corrupt("stored block length check failed")
        r.pos += 4
        if (r.pos + len > r.end) throw Corrupt("truncated stored block")
        for (i in 0 until len) out.put(r.src[r.pos + i])
        r.pos += len
    }

    private fun build(lengths: IntArray, n: Int): Huffman {
        val count = IntArray(16)
        for (i in 0 until n) count[lengths[i]]++
        if (count[0] == n) return Huffman(count, IntArray(n))   // no codes: only valid if never used
        var left = 1
        for (len in 1..15) {
            left = (left shl 1) - count[len]
            if (left < 0) throw Corrupt("over-subscribed Huffman code")
        }
        val offs = IntArray(16)
        for (len in 1 until 15) offs[len + 1] = offs[len] + count[len]
        val symbol = IntArray(n)
        for (s in 0 until n) if (lengths[s] != 0) symbol[offs[lengths[s]]++] = s
        return Huffman(count, symbol)
    }

    private fun decode(r: Reader, h: Huffman): Int {
        var code = 0
        var first = 0
        var index = 0
        for (len in 1..15) {
            code = code or r.bits(1)
            val count = h.count[len]
            if (code - count < first) return h.symbol[index + (code - first)]
            index += count
            first += count
            first = first shl 1
            code = code shl 1
        }
        throw Corrupt("invalid Huffman code")
    }

    private fun codes(r: Reader, out: Out, lencode: Huffman, distcode: Huffman) {
        while (true) {
            var sym = decode(r, lencode)
            if (sym < 256) {
                out.put(sym.toByte())
            } else if (sym == 256) {
                return
            } else {
                sym -= 257
                if (sym >= 29) throw Corrupt("invalid length symbol")
                val len = LEN_BASE[sym] + r.bits(LEN_EXTRA[sym])
                val ds = decode(r, distcode)
                if (ds >= 30) throw Corrupt("invalid distance symbol")
                val dist = DIST_BASE[ds] + r.bits(DIST_EXTRA[ds])
                if (dist > out.size) throw Corrupt("distance too far back")
                for (i in 0 until len) out.put(out.buf[out.size - dist])
            }
        }
    }

    private fun dynamic(r: Reader, out: Out) {
        val nlen = r.bits(5) + 257
        val ndist = r.bits(5) + 1
        val ncode = r.bits(4) + 4
        if (nlen > 286 || ndist > 30) throw Corrupt("bad dynamic block counts")
        val lengths = IntArray(320)
        for (i in 0 until ncode) lengths[CL_ORDER[i]] = r.bits(3)
        val lencode = build(lengths, 19)
        var index = 0
        val codeLens = IntArray(nlen + ndist)
        while (index < nlen + ndist) {
            var sym = decode(r, lencode)
            if (sym < 16) {
                codeLens[index++] = sym
            } else {
                var len = 0
                when (sym) {
                    16 -> {
                        if (index == 0) throw Corrupt("repeat with no first length")
                        len = codeLens[index - 1]
                        sym = 3 + r.bits(2)
                    }
                    17 -> sym = 3 + r.bits(3)
                    else -> sym = 11 + r.bits(7)
                }
                if (index + sym > nlen + ndist) throw Corrupt("too many code lengths")
                repeat(sym) { codeLens[index++] = len }
            }
        }
        if (codeLens[256] == 0) throw Corrupt("no end-of-block code")
        val lit = build(codeLens.copyOfRange(0, nlen), nlen)
        val dist = build(codeLens.copyOfRange(nlen, nlen + ndist), ndist)
        codes(r, out, lit, dist)
    }
}

/** CRC-32 (IEEE 802.3, as ZIP uses it). */
internal object Crc32 {
    private val TABLE = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1) }
        c
    }

    fun of(bytes: ByteArray): Long {
        var c = -1
        for (b in bytes) c = TABLE[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
        return (c.inv().toLong() and 0xFFFFFFFFL)
    }
}
