package dev.loupe.desktop

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Build risk 11: the notices that ship in the app jar must match what actually resolves.
 *
 * Independent of the generator (gradle/third-party-notices.gradle.kts): it re-reads the jars Gradle
 * resolved for the app's runtime classpath (passed in by the test task) and compares them with the
 * committed manifest, third-party/notices.lock, and the bundled THIRD_PARTY_NOTICES.txt.
 */
class ThirdPartyNoticesTest {
    private val hint = "Run ./gradlew :loupe-desktop:generateThirdPartyNotices and commit the result."
    private val dir = File(System.getProperty("loupe.thirdParty.dir") ?: fail("loupe.thirdParty.dir not set; run through Gradle"))
    private val lock = File(dir, "notices.lock").takeIf { it.isFile }?.readLines()
        ?.filter { it.isNotBlank() && !it.startsWith("#") }?.map { it.split('\t') }
        ?: fail("third-party/notices.lock is missing. $hint")

    /** coordinate (platform-normalised) -> jar, for every module artifact on the runtime classpath. */
    private val jars: Map<String, File> = (System.getProperty("loupe.thirdParty.jars") ?: fail("loupe.thirdParty.jars not set; run through Gradle"))
        .split("|").filter { it.isNotBlank() }.associate { entry ->
            val (coord, path) = entry.split("=", limit = 2)
            normalise(coord) to File(path)
        }

    private fun normalise(coord: String): String {
        val (g, a, v) = coord.split(':')
        return "$g:${a.replace(Regex("-(macos|linux|windows)-(arm64|x64)$"), "-<platform>")}:$v"
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun normaliseText(t: String) = t.replace("\r\n", "\n").replace('\r', '\n').trimEnd() + "\n"

    private val noticeName = Regex("""(?i)^(licen[cs]e|notice|copying|thirdpartynotices|third[-_]party[-_]notices)([.-][^/]*)?$""")
    private val nativeName = Regex("""(?i)\.(so|dylib|dll|jnilib)$""")

    private fun bundled(): String {
        val stream = javaClass.classLoader.getResourceAsStream("THIRD_PARTY_NOTICES.txt")
            ?: fail("THIRD_PARTY_NOTICES.txt is not on the app's classpath. $hint")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun `the bundled notices are the ones the manifest describes`() {
        val recorded = lock.single { it[0] == "notices" }[2]
        assertEquals(recorded, sha256(bundled().toByteArray(Charsets.UTF_8)), "THIRD_PARTY_NOTICES.txt does not match notices.lock. $hint")
    }

    @Test
    fun `every runtime jar is in the manifest and nothing else is`() {
        val locked = lock.filter { it[0] == "maven" }.map { it[1] }.toSet()
        val resolved = jars.keys
        assertEquals(
            emptySet(), resolved - locked,
            "Runtime dependencies with no bundled notice. $hint",
        )
        assertEquals(
            emptySet(), locked - resolved,
            "Notices for dependencies that no longer resolve. $hint",
        )
        assertTrue(locked.none { it.startsWith("org.junit") || it.startsWith("org.opentest4j") }, "test-only artifacts leaked into the notices")
    }

    @Test
    fun `each jar's own licence and notice files are reproduced unchanged`() {
        val bundled = bundled()
        val byCoord = lock.filter { it[0] == "maven" }.associate { it[1] to it[3] }
        for ((coord, jar) in jars) {
            val expected = ZipFile(jar).use { z ->
                z.entries().asSequence().filter { !it.isDirectory }.map { it.name }.sorted()
                    .filter { n -> n.substringAfterLast('/').let { !it.endsWith(".class") && noticeName.matches(it) } }
                    .map { n ->
                        val text = normaliseText(z.getInputStream(z.getEntry(n)).readBytes().toString(Charsets.UTF_8))
                        val id = sha256(text.toByteArray(Charsets.UTF_8)).take(16)
                        assertTrue(text in bundled, "$coord: $n is not reproduced in THIRD_PARTY_NOTICES.txt. $hint")
                        "$n=$id"
                    }.toList()
            }
            val recorded = (byCoord[coord] ?: fail("$coord is not in notices.lock. $hint")).split(',').filter { !it.startsWith("(") }
            assertEquals(expected, recorded, "$coord: licence files in the jar changed. $hint")
        }
    }

    @Test
    fun `every jar carrying native code has reviewed native components`() {
        val reviewed = lock.filter { it[0] == "native" }.map { it[1] }.toSet()
        for ((coord, jar) in jars) {
            val hasNative = ZipFile(jar).use { z -> z.entries().asSequence().any { nativeName.containsMatchIn(it.name) } }
            if (hasNative) assertTrue(
                coord in reviewed,
                "$coord bundles native libraries with no row in third-party/native-components.tsv; review it, then regenerate.",
            )
        }
    }

    @Test
    fun `the Rust crate list matches the libtokenizers that ships`() {
        val (coord, jar) = jars.entries.single { it.key.startsWith("ai.djl.huggingface:tokenizers:") }
        val djl = coord.substringAfterLast(':')
        val tsv = File(dir, "tokenizers-crates.tsv").readLines()
        assertTrue("tokenizers:$djl" in tsv.first(), "tokenizers-crates.tsv is for another DJL version; run tools/third-party/refresh-tokenizers-crates.py $djl")
        val listed = tsv.filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t').let { c -> "${c[0]} ${c[1]}" } }.toSet()
        val observed = sortedSetOf<String>()
        var rust: String? = null
        ZipFile(jar).use { z ->
            rust = z.getEntry("native/lib/tokenizers.properties")?.let { e ->
                Regex("""version=([0-9.]+)-""").find(z.getInputStream(e).readBytes().toString(Charsets.UTF_8))?.groupValues?.get(1)
            }
            val libs = z.entries().asSequence().filter { nativeName.containsMatchIn(it.name) && "tokenizers" in it.name }.toList()
            assertEquals(4, libs.size, "expected libtokenizers for four platforms")
            for (e in libs) {
                val text = String(z.getInputStream(e).readBytes(), Charsets.ISO_8859_1).replace('\\', '/')
                Regex("""\.cargo/registry/src/[^/]+/([A-Za-z0-9_.+-]+?)-(\d+\.\d+\.\d+[A-Za-z0-9.+-]*)/""")
                    .findAll(text).forEach { observed += "${it.groupValues[1]} ${it.groupValues[2]}" }
            }
        }
        assertTrue(observed.size > 40, "crate paths not found in libtokenizers (${observed.size}); has the binary format changed?")
        assertEquals(emptySet(), observed - listed, "crates linked into libtokenizers but missing from tokenizers-crates.tsv")
        val pinned = listed.single { it.startsWith("tokenizers ") }.substringAfter(' ')
        assertNotNull(rust)
        assertEquals(rust!!.split('.').take(2), pinned.split('.').take(2), "tokenizers.properties vs pinned tokenizers crate")
        val bundled = bundled()
        assertTrue(listed.all { c -> "\n$c " in bundled }, "a pinned crate is missing from THIRD_PARTY_NOTICES.txt. $hint")
    }
}
