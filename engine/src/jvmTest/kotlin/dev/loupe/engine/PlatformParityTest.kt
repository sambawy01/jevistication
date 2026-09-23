package dev.loupe.engine

import java.net.IDN
import java.net.URI
import java.text.Normalizer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM-only: pins each portable (iOS) replacement to the JDK call the engine used before the
 * Kotlin Multiplatform port. These need the JDK as the oracle, so they cannot live in commonTest.
 */
class PlatformParityTest {

    /** OriginFacts.host exactly as it was before the port, java.net.URI first. */
    private fun legacyHost(url: String): String? {
        val candidate = if (url.contains("://")) url else "https://$url"
        val fromUri = runCatching { URI(candidate) }.getOrNull()?.host
        val raw = fromUri ?: candidate.substringAfter("://").substringBefore('/').substringBefore('?')
            .substringBefore('#').substringAfterLast('@').substringBefore(':')
        val host = raw.lowercase().removeSuffix(".")
        return host.takeIf { it.isNotEmpty() && Regex("""^[\p{L}\p{N}][\p{L}\p{N}.\-_]*$""").matches(it) }
    }

    @Test
    fun `host parser matches the java-net-URI implementation`() {
        val cases = listOf(
            "https://www.paypal.com/signin", "paypal.com", "HTTPS://PayPal.COM:443/x", "https://pаypal.com/signin",
            "https://user:pw@bank.com/x", "https://a.com@evil.com/", "https://evil.com\\@good.com", "https://a.com?x@y.com",
            "https://a.com#@evil.com", "https://[::1]:8080/", "https://192.168.0.1/x", "http://a_b.example.com/",
            "https://a-.com", "https:///a.com", "example.com/path?u=http://evil.com", "mailto:x@y.com",
            "https://a.com:abc/", "https://%70aypal.com", "https://exa mple.com", "https://example.com.",
            "https://xn--pypal-4ve.com/", "ftp://files.example.org/a", "https://a.com:80@b.com/", "",
            "://", "https://", "https://.", "https://-a.com", "https://食狮.com.cn/", "https://user@食狮.公司.cn:8443/p",
            "https://sub.domain.co.uk/path#frag", "javascript:alert(1)", "https://a..b.com", "//cdn.example.com/x.js",
        )
        for (c in cases) assertEquals(legacyHost(c), OriginFacts.host(c), "host($c)")
    }

    @Test
    fun `portable IDNA matches java-net-IDN on every PSL label and test vector`() {
        val labels = (bundledPublicSuffixList().lines() + PslTestVectors.TEXT.lines())
            .filter { it.isNotBlank() && !it.startsWith("//") }
            .flatMap { it.trim().split(Regex("\\s+")).first().removePrefix("!").split('.') }
            .map { it.lowercase() }
            .filter { it.isNotEmpty() && it.any { c -> c.code > 127 } }
            .toSet() + setOf("faß", "ｃｏｍ", "ﾃｽﾄ", "a­b", "ς", "xn--ä", "ä".repeat(70))
        assertTrue(labels.size > 300, "only ${labels.size} labels")
        val nfkc: (String) -> String = { Normalizer.normalize(it, Normalizer.Form.NFKC) }
        for (label in labels) {
            val jdk = runCatching { IDN.toASCII(label, IDN.ALLOW_UNASSIGNED).lowercase() }.getOrNull()
            assertEquals(jdk, Idna.toAsciiLabel(label, nfkc), "toAscii($label)")
        }
    }

    @Test
    fun `script table matches Character-UnicodeScript on every code point`() {
        for (cp in 0..Character.MAX_CODE_POINT) {
            val want = letterScript(cp)
            val got = UnicodeScripts.letterScript(cp)
            if (want != got) assertEquals(want, got, "code point ${cp.toString(16)}")
        }
    }

    @Test
    fun `portable fixed formatting matches java-util-Formatter`() {
        val random = Random(20260923)
        val values = listOf(0.0, -0.0, 0.5, 1.5, 2.5, 0.125, 0.375, 1.005, 99.95, 99.995, 0.0005, 1e-7, 123456789.125,
            -0.04, -1.25, 100.0, 1e20, 0.1 + 0.2, 66.66666666666667, 83.33333333333334, Double.NaN) +
            List(20_000) { random.nextDouble() } + List(5_000) { random.nextDouble() * 100 } +
            List(2_000) { (random.nextInt(100_000) / 1000.0) } + List(2_000) { random.nextInt(2000) / 8.0 }
        for (v in values) for (d in 0..3) {
            assertEquals(String.format("%.${d}f", v), formatFixedPortable(v, d), "format($v, $d)")
        }
    }

    @Test
    fun `kotlinx LocalDate parse accepts exactly what java-time did`() {
        val inputs = listOf("2026-09-23", "2026-02-29", "2024-02-29", "2026-13-01", "2026-9-23", "26-09-23",
            "+2026-09-23", "2026-09-23T00:00", " 2026-09-23", "2026/09/23", "0000-01-01", "9999-12-31", "")
        for (s in inputs) {
            val jdk = runCatching { java.time.LocalDate.parse(s) }.getOrNull()?.toString()
            val kx = runCatching { kotlinx.datetime.LocalDate.parse(s) }.getOrNull()?.toString()
            assertEquals(jdk, kx, "parse($s)")
        }
    }
}
