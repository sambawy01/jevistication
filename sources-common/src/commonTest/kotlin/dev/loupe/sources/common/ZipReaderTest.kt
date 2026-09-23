package dev.loupe.sources.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Archive safety (epic #7 child 15), with malicious fixtures built byte by byte. The limits are
 * Loupe Station's (`laya_studio/scan/content.py` @ ea7697a: 10 000 members, ratio 200 above 4 MB).
 */
class ZipReaderTest {
    private fun reasons(r: ZipReader.Result) = r.skipped.associate { it.path to it.reason }

    @Test
    fun inflatesDynamicFixedAndStoredMembers() {
        assertEquals(TestZip.DEFLATED_TEXT, Inflate.inflate(TestZip.DEFLATED, 0, TestZip.DEFLATED.size, 1_000_000).decodeToString())
        assertEquals("hello hello hello loupe", Inflate.inflate(TestZip.FIXED, 0, TestZip.FIXED.size, 100).decodeToString())
        val zip = TestZip()
            .add(TestZip.Member("statements/2026.csv", TestZip.DEFLATED, method = 8, plain = TestZip.DEFLATED_TEXT.encodeToByteArray()))
            .stored("notes/readme.txt", "Plain stored member")
            .bytes()
        val r = ZipReader().read(zip)
        assertNull(r.refused)
        assertEquals(listOf("statements/2026.csv", "notes/readme.txt"), r.entries.map { it.path })
        assertEquals(TestZip.DEFLATED_TEXT, r.entries[0].bytes.decodeToString())
        assertTrue(r.skipped.isEmpty())
    }

    @Test
    fun pathTraversalAbsoluteAndDriveNamesAreRefused() {
        val zip = TestZip()
            .stored("../../evil.txt", "x").stored("/etc/passwd", "x").stored("C:/win.ini", "x")
            .stored("a\\..\\..\\b.txt", "x").stored("ok/../../up.txt", "x").stored("fine/ok.txt", "kept")
            .bytes()
        val r = ZipReader().read(zip)
        assertEquals(listOf("fine/ok.txt"), r.entries.map { it.path })
        val why = reasons(r)
        assertTrue(why.getValue("../../evil.txt").startsWith("unsafe path"))
        assertTrue(why.getValue("/etc/passwd").startsWith("unsafe path: absolute"))
        assertTrue(why.getValue("C:/win.ini").startsWith("unsafe path: drive"))
        assertTrue(why.getValue("a\\..\\..\\b.txt").startsWith("unsafe path: backslash"))
        assertTrue(why.getValue("ok/../../up.txt").startsWith("unsafe path"))
    }

    @Test
    fun symbolicLinksAreRefused() {
        val zip = TestZip()
            .add(TestZip.Member("link-to-home", "/Users/me".encodeToByteArray(), unixMode = 0xA1FF))
            .stored("real.txt", "a real file").bytes()
        val r = ZipReader().read(zip)
        assertEquals(listOf("real.txt"), r.entries.map { it.path })
        assertEquals("symbolic link: refused", reasons(r)["link-to-home"])
    }

    @Test
    fun zipBombsAreRefusedByRatioByLyingSizeAndByMemberCount() {
        // Claims 5 MB from 100 bytes: ratio 52 428 > 200 above 4 MB (Station's rule), skipped unread.
        val ratio = TestZip.Member("bomb.bin", ByteArray(100), method = 8, declaredSize = 5L * 1024 * 1024, crc = 0)
        // Declares 100 bytes but inflates to 6 900: abandoned at the declared size.
        val liar = TestZip.Member("liar.csv", TestZip.DEFLATED, method = 8, declaredSize = 100, plain = TestZip.DEFLATED_TEXT.encodeToByteArray())
        val r = ZipReader().read(TestZip().add(ratio).add(liar).stored("ok.txt", "fine").bytes())
        assertEquals(listOf("ok.txt"), r.entries.map { it.path })
        assertEquals("zip bomb: compression ratio over 200", reasons(r)["bomb.bin"])
        assertEquals("zip bomb: expands past its declared size", reasons(r)["liar.csv"])

        val many = TestZip().apply { repeat(12) { stored("f$it.txt", "x") } }.bytes()
        val refused = ZipReader(ZipReader.Limits(maxEntries = 10)).read(many)
        assertEquals("zip bomb: more than 10 members", refused.refused)
        assertTrue(refused.entries.isEmpty())

        val total = ZipReader(ZipReader.Limits(maxEntryBytes = 8, maxTotalBytes = 10)).read(TestZip().stored("a.txt", "12345678").stored("b.txt", "12345678").bytes())
        assertEquals(listOf("a.txt"), total.entries.map { it.path })
        assertTrue(reasons(total).getValue("b.txt").startsWith("archive total over"))
    }

    @Test
    fun encryptedDamagedNestedAndNoiseMembers() {
        val zip = TestZip()
            .add(TestZip.Member("secret.txt", "xxxx".encodeToByteArray(), flags = 1))
            .add(TestZip.Member("bad-crc.txt", "hello".encodeToByteArray(), crc = 1))
            .stored("inner.zip", "PK not really")
            .stored("__MACOSX/._x.txt", "resource fork").stored("folder/.DS_Store", "noise")
            .add(TestZip.Member("weird.bin", "x".encodeToByteArray(), method = 12))
            .stored("keep.txt", "kept").bytes()
        val r = ZipReader().read(zip)
        assertEquals(listOf("keep.txt"), r.entries.map { it.path })
        val why = reasons(r)
        assertEquals("encrypted: not read", why["secret.txt"])
        assertEquals("damaged: CRC mismatch", why["bad-crc.txt"])
        assertEquals("archive inside an archive: not opened", why["inner.zip"])
        assertEquals("unsupported compression (method 12)", why["weird.bin"])
        assertEquals(4, r.skipped.size, "macOS resource forks and dot files are skipped silently")
    }

    @Test
    fun notAZipAndTruncatedArchives() {
        assertNotNull(ZipReader().read("just text".encodeToByteArray()).refused)
        val good = TestZip().stored("a.txt", "hello").bytes()
        assertNotNull(ZipReader().read(good.copyOfRange(0, good.size - 30)).refused)
        val broken = Inflate.runCatching { inflate(byteArrayOf(0x07), 0, 1, 10) }
        assertTrue(broken.isFailure)
    }
}
