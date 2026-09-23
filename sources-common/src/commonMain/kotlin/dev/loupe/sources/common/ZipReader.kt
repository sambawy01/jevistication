package dev.loupe.sources.common

/**
 * A small, defensive ZIP reader for the Inbox (epic #7 child 15): the central directory, stored and
 * deflated members, nothing else. Everything that could hurt the phone is refused per member, with
 * the reason shown, never guessed past:
 *
 * - path traversal: absolute names, drive letters, backslashes, `..` segments, NUL, empty segments;
 * - symbolic links (a Unix mode of `S_IFLNK` in the external attributes) — never materialised;
 * - zip bombs, with Loupe Station's limits (`laya_studio/scan/content.py` @ ea7697a): more than
 *   [Limits.maxEntries] (10 000) members refuses the archive; a member of 4 MB or more that claims a
 *   compression ratio over 200 is skipped; a member that inflates past its declared size is
 *   abandoned; and the archive's total output is capped ([Limits.maxTotalBytes]);
 * - encrypted members, ZIP64, and compression methods other than stored/deflate: not read;
 * - archives inside the archive: not opened (one level only).
 */
class ZipReader(private val limits: Limits = Limits()) {
    constructor() : this(Limits())

    data class Limits(
        val maxEntries: Int = 10_000,
        val maxRatio: Int = 200,
        val ratioMinBytes: Long = 4L * 1024 * 1024,
        val maxEntryBytes: Long = 50L * 1024 * 1024,
        val maxTotalBytes: Long = 512L * 1024 * 1024,
        val maxDepth: Int = 16,
    ) {
        init {
            require(maxEntries > 0 && maxRatio > 1 && maxEntryBytes > 0 && maxTotalBytes >= maxEntryBytes && maxDepth > 0)
        }
    }

    /** One member that passed every check: its safe relative [path] and its bytes. */
    class Entry(val path: String, val bytes: ByteArray)

    /** What an archive yielded. [refused] set means nothing was read, with why. */
    class Result(val entries: List<Entry>, val skipped: List<Skipped>, val refused: String?)

    private class Central(
        val name: String, val flags: Int, val method: Int, val crc: Long, val csize: Long, val usize: Long,
        val madeBy: Int, val extAttr: Long, val localOffset: Long,
    )

    fun read(bytes: ByteArray): Result {
        val eocd = findEocd(bytes) ?: return refuse("not a ZIP archive (no end-of-central-directory record)")
        val total = u16(bytes, eocd + 10)
        val cdSize = u32(bytes, eocd + 12)
        val cdOffset = u32(bytes, eocd + 16)
        if (total == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) return refuse("ZIP64 archives are not read")
        if (total > limits.maxEntries) return refuse("zip bomb: more than ${limits.maxEntries} members")
        if (cdOffset + cdSize > bytes.size) return refuse("damaged: the central directory is past the end of the file")
        val centrals = mutableListOf<Central>()
        var p = cdOffset.toInt()
        repeat(total) {
            if (p + 46 > bytes.size || u32(bytes, p) != 0x02014b50L) return refuse("damaged: bad central directory entry")
            val nameLen = u16(bytes, p + 28)
            val extraLen = u16(bytes, p + 30)
            val commentLen = u16(bytes, p + 32)
            if (p + 46 + nameLen > bytes.size) return refuse("damaged: bad central directory entry")
            val flags = u16(bytes, p + 8)
            val rawName = bytes.copyOfRange(p + 46, p + 46 + nameLen)
            val name = if (flags and 0x800 != 0) rawName.decodeToString() else Charsets.latin1(rawName).let { l ->
                if (l.all { it.code < 0x80 }) l else rawName.decodeToString()
            }
            centrals += Central(
                name = name, flags = flags, method = u16(bytes, p + 10), crc = u32(bytes, p + 16), csize = u32(bytes, p + 20),
                usize = u32(bytes, p + 24), madeBy = u16(bytes, p + 4), extAttr = u32(bytes, p + 38), localOffset = u32(bytes, p + 42),
            )
            p += 46 + nameLen + extraLen + commentLen
        }

        val entries = mutableListOf<Entry>()
        val skipped = mutableListOf<Skipped>()
        val seen = mutableSetOf<String>()
        var produced = 0L
        for (c in centrals) {
            val shown = c.name.replace('\u0000', '?')
            val unsafe = unsafePath(c.name.removeSuffix("/"), limits.maxDepth)
            if (unsafe != null) {
                skipped += Skipped(shown, unsafe)
                continue
            }
            if (c.name.endsWith("/") || isNoise(c.name)) continue            // folders; __MACOSX/, .DS_Store: silent
            val reason = check(c, produced)
            if (reason != null) {
                skipped += Skipped(shown, reason)
                if (reason.startsWith("archive total")) break
                continue
            }
            val path = c.name
            if (!seen.add(path.lowercase())) {
                skipped += Skipped(shown, "duplicate name in the archive: only the first copy was read")
                continue
            }
            val data = try {
                member(bytes, c)
            } catch (e: Inflate.TooLarge) {
                skipped += Skipped(shown, "zip bomb: expands past its declared size")
                continue
            } catch (e: Exception) {
                skipped += Skipped(shown, "damaged: ${e.message ?: "could not be decompressed"}")
                continue
            }
            if (Crc32.of(data) != c.crc) {
                skipped += Skipped(shown, "damaged: CRC mismatch")
                continue
            }
            produced += data.size
            if (isArchive(path)) {
                skipped += Skipped(shown, "archive inside an archive: not opened")
                continue
            }
            entries += Entry(path, data)
        }
        return Result(entries, skipped, null)
    }

    /** The reason [c] is not read, or null when it may be decompressed. */
    private fun check(c: Central, produced: Long): String? {
        unsafePath(c.name, limits.maxDepth)?.let { return it }
        val hostUnix = (c.madeBy ushr 8) == 3
        val type = ((c.extAttr ushr 16) and 0xF000L).toInt()
        if (hostUnix && type == 0xA000) return "symbolic link: refused"
        if (hostUnix && type != 0 && type != 0x8000) return "not a regular file: refused"
        if (c.flags and 0x1 != 0) return "encrypted: not read"
        if (c.method != 0 && c.method != 8) return "unsupported compression (method ${c.method})"
        if (c.usize >= limits.ratioMinBytes && c.usize > limits.maxRatio.toLong() * maxOf(1L, c.csize)) {
            return "zip bomb: compression ratio over ${limits.maxRatio}"
        }
        if (c.usize > limits.maxEntryBytes) return "too large: over ${limits.maxEntryBytes / (1024 * 1024)} MB"
        if (produced + c.usize > limits.maxTotalBytes) return "archive total over ${limits.maxTotalBytes / (1024 * 1024)} MB: the rest was not read"
        return null
    }

    private fun member(bytes: ByteArray, c: Central): ByteArray {
        val lh = c.localOffset
        if (lh + 30 > bytes.size || u32(bytes, lh.toInt()) != 0x04034b50L) throw IllegalStateException("bad local header")
        val start = lh.toInt() + 30 + u16(bytes, lh.toInt() + 26) + u16(bytes, lh.toInt() + 28)
        if (start < 0 || start + c.csize > bytes.size) throw IllegalStateException("member is past the end of the file")
        return when (c.method) {
            0 -> {
                if (c.csize != c.usize) throw IllegalStateException("stored member sizes disagree")
                bytes.copyOfRange(start, start + c.csize.toInt())
            }
            else -> Inflate.inflate(bytes, start, c.csize.toInt(), c.usize)
        }
    }

    private fun refuse(why: String) = Result(emptyList(), emptyList(), why)

    private fun findEocd(b: ByteArray): Int? {
        val lowest = maxOf(0, b.size - 22 - 0xFFFF)
        var i = b.size - 22
        while (i >= lowest) {
            if (b[i] == 0x50.toByte() && b[i + 1] == 0x4b.toByte() && b[i + 2] == 0x05.toByte() && b[i + 3] == 0x06.toByte()) return i
            i--
        }
        return null
    }

    companion object {
        private fun u16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

        private fun u32(b: ByteArray, i: Int): Long = (u16(b, i).toLong()) or (u16(b, i + 2).toLong() shl 16)

        private val ARCHIVES = setOf("zip", "jar", "7z", "rar", "gz", "tgz", "tar", "bz2", "xz")

        fun isArchive(name: String): Boolean = name.substringAfterLast('/').substringAfterLast('.', "").lowercase() in ARCHIVES

        private fun isNoise(name: String): Boolean {
            val segs = name.split('/')
            return segs.first() == "__MACOSX" || segs.any { it.startsWith(".") && it != "." && it != ".." }
        }

        /** Why [name] could escape the folder it is extracted into, or null when it cannot. */
        fun unsafePath(name: String, maxDepth: Int = 16): String? {
            if (name.isEmpty() || '\u0000' in name) return "unsafe path: empty or contains NUL"
            if ('\\' in name) return "unsafe path: backslash (would escape the archive on some systems)"
            if (name.startsWith("/")) return "unsafe path: absolute"
            if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) return "unsafe path: drive letter"
            val segs = name.split('/')
            if (segs.any { it == ".." }) return "unsafe path: '..' would escape the archive"
            if (segs.any { it.isEmpty() || it == "." }) return "unsafe path: empty or '.' segment"
            if (segs.size > maxDepth) return "unsafe path: nested deeper than $maxDepth folders"
            if (segs.any { it.encodeToByteArray().size > 255 }) return "unsafe path: a name longer than 255 bytes"
            return null
        }
    }
}
