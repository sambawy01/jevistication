package dev.loupe.engine

/**
 * A host after UTS #46 processing: its ASCII form (what DNS is asked), its Unicode form (each `xn--`
 * label decoded), and the problems found. Problems do not stop the processing: a browser would refuse
 * such a host, and the caller decides what that means (see [Uts46.toAscii]).
 */
class IdnaHost(
    val ascii: String,
    val unicode: String,
    /**
     * `P1` a disallowed code point; `C` a ZERO WIDTH JOINER / NON-JOINER where CONTEXTJ (RFC 5892
     * A.1, A.2) does not allow it; `P4` an `xn--` label that is not valid Punycode.
     */
    val errors: Set<String>,
)

/**
 * UTS #46 (Unicode IDNA Compatibility Processing) over Loupe's pinned Unicode data: the mapping the
 * WHATWG URL standard applies to a host, and so what Chrome and Safari resolve. Non-transitional by
 * default: `ß`, `ς`, ZWJ and ZWNJ are kept (`faß.de` is `xn--fa-hia.de`, not `fass.de`), and a joiner
 * must pass CONTEXTJ. The transitional form (IDNA 2003's answer: `fass.de`) is available to detect
 * a host that old and new software read as two different names; it must never be used to decide
 * whose host it is.
 *
 * Checked as WHATWG's `domain to ASCII` does (UseSTD3ASCIIRules false, CheckHyphens false,
 * CheckJoiners true), except CheckBidi and the DNS length limits, which are not checked here.
 * Verified at generation against every error-free answer of the Unicode IdnaTestV2.txt
 * (tools/unicode/gen_unicode_tables.py).
 */
object Uts46 {

    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D

    /** The deviations: kept by non-transitional processing, mapped like this by transitional. */
    private val DEVIATIONS: Map<Int, IntArray> = mapOf(
        0x00DF to intArrayOf(0x73, 0x73),
        0x03C2 to intArrayOf(0x03C3),
        ZWNJ to IntArray(0),
        ZWJ to IntArray(0),
    )

    /** [domain] (a host, labels separated by `.` or an ideographic / full-width / halfwidth full stop). */
    fun toAscii(domain: String, transitional: Boolean = false): IdnaHost {
        val errors = LinkedHashSet<String>()
        if (domain.all { it.code < 0x80 && it !in 'A'..'Z' }) {
            return finish(domain, codePoints(domain), transitional, errors)
        }
        val mapped = ArrayList<Int>(domain.length + 4)
        for (cp in codePoints(domain)) {
            val dev = DEVIATIONS[cp]
            when {
                dev != null -> if (transitional) dev.forEach { mapped += it } else mapped += cp
                UnicodeData.uts46Disallowed(cp) -> {
                    errors += "P1"
                    mapped += cp
                }
                else -> for (x in UnicodeData.uts46Map(cp)) {
                    // transitional: a deviation the mapping produced (U+1E9E -> ß) is mapped too
                    val d = if (transitional) DEVIATIONS[x] else null
                    if (d != null) d.forEach { mapped += it } else mapped += x
                }
            }
        }
        val normalized = UnicodeData.compose(UnicodeData.decompose(mapped.toIntArray(), compat = false))
        return finish(domain, normalized, transitional, errors)
    }

    private fun finish(domain: String, cps: IntArray, transitional: Boolean, errors: MutableSet<String>): IdnaHost {
        val ascii = StringBuilder(domain.length + 8)
        val unicode = StringBuilder(domain.length + 8)
        var start = 0
        for (i in 0..cps.size) {
            if (i < cps.size && cps[i] != '.'.code) continue
            val label = cps.copyOfRange(start, i)
            if (start > 0) {
                ascii.append('.')
                unicode.append('.')
            }
            label(label, transitional, errors, ascii, unicode)
            start = i + 1
        }
        return IdnaHost(ascii.toString(), unicode.toString(), errors)
    }

    private fun label(label: IntArray, transitional: Boolean, errors: MutableSet<String>, ascii: StringBuilder, unicode: StringBuilder) {
        val allAscii = label.all { it < 0x80 }
        var uni = label
        if (allAscii) {
            val text = fromCodePoints(label)
            if (text.startsWith("xn--")) {
                val decoded = Punycode.decode(text.substring(4))
                if (decoded == null) errors += "P4" else uni = codePoints(decoded)
            }
            ascii.append(text)
        } else {
            val enc = Punycode.encode(fromCodePoints(label))
            ascii.append(if (enc == null) fromCodePoints(label) else "xn--$enc")
        }
        for (i in uni.indices) {
            val c = uni[i]
            if ((c == ZWNJ || c == ZWJ) && !contextJ(uni, i)) errors += "C"
            if (UnicodeData.uts46Disallowed(c)) errors += "P1"
        }
        unicode.append(fromCodePoints(uni))
    }

    /**
     * CONTEXTJ (RFC 5892 appendix A.1, A.2) for the joiner at [label]`[`[i]`]`: after a virama
     * (combining class 9); or, for ZWNJ only, between a left- or dual-joining letter and a right- or
     * dual-joining one with only transparent characters around it (Persian `نمونه‌ای`).
     */
    fun contextJ(label: IntArray, i: Int): Boolean {
        if (i > 0 && UnicodeData.combiningClass(label[i - 1]) == 9) return true
        if (label[i] != ZWNJ) return false
        var j = i - 1
        while (j >= 0 && UnicodeData.joiningType(label[j]) == 4) j--
        if (j < 0 || UnicodeData.joiningType(label[j]) !in 1..2) return false
        var k = i + 1
        while (k < label.size && UnicodeData.joiningType(label[k]) == 4) k++
        return k < label.size && UnicodeData.joiningType(label[k]).let { it == 2 || it == 3 }
    }

    private fun fromCodePoints(cps: IntArray): String {
        val sb = StringBuilder(cps.size)
        for (cp in cps) appendCodePoint(sb, cp)
        return sb.toString()
    }
}
