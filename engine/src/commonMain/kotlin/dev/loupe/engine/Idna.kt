package dev.loupe.engine

/**
 * IDNA 2003 ToASCII for one label, in portable Kotlin: the iOS stand-in for
 * `java.net.IDN.toASCII(label, IDN.ALLOW_UNASSIGNED)` (no STD3 rules), which the JVM keeps using.
 *
 * Nameprep is approximated as: map-to-nothing (RFC 3454 B.1), lowercase plus the two B.2 case
 * folds that lowercase does not do (`ß`→`ss`, final `ς`→`σ`), then NFKC via [nfkc]. Nameprep's
 * prohibited-output and bidi checks are not replicated. A jvmTest checks agreement with the JDK on
 * every non-ASCII label of the bundled PSL and on the official PSL test vectors.
 */
internal object Idna {

    private const val ACE = "xn--"

    private fun mapToNothing(cp: Int): Boolean =
        cp == 0x00AD || cp == 0x034F || cp == 0x1806 || cp in 0x180B..0x180D ||
            cp in 0x200B..0x200D || cp == 0x2060 || cp in 0xFE00..0xFE0F || cp == 0xFEFF

    fun toAsciiLabel(label: String, nfkc: (String) -> String): String? {
        val mapped = StringBuilder()
        for (cp in codePoints(label)) {
            when {
                mapToNothing(cp) -> Unit
                cp == 0x00DF -> mapped.append("ss")
                cp == 0x03C2 -> mapped.append('σ')
                else -> appendCodePoint(mapped, cp)
            }
        }
        val prepared = nfkc(mapped.toString().lowercase()).lowercase()
        if (prepared.all { it.code < 128 }) {
            return prepared.takeIf { it.length <= 63 }
        }
        if (prepared.startsWith(ACE, ignoreCase = true)) return null
        val encoded = ACE + (Punycode.encode(prepared) ?: return null)
        return encoded.takeIf { it.length <= 63 }
    }
}

/** RFC 3492 Punycode (Bootstring with the Punycode parameters), encode direction only. */
internal object Punycode {
    private const val BASE = 36
    private const val TMIN = 1
    private const val TMAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 0x80

    private fun digit(d: Int): Char = if (d < 26) 'a' + d else '0' + (d - 26)

    private fun adapt(deltaIn: Int, numPoints: Int, first: Boolean): Int {
        var delta = if (first) deltaIn / DAMP else deltaIn / 2
        delta += delta / numPoints
        var k = 0
        while (delta > ((BASE - TMIN) * TMAX) / 2) {
            delta /= BASE - TMIN
            k += BASE
        }
        return k + (BASE - TMIN + 1) * delta / (delta + SKEW)
    }

    /** The Punycode of [input] without the ACE prefix, or null on overflow. */
    fun encode(input: String): String? {
        val cps = codePoints(input)
        val out = StringBuilder()
        for (cp in cps) if (cp < 0x80) out.append(cp.toChar())
        val basic = out.length
        var handled = basic
        if (basic > 0) out.append('-')
        var n = INITIAL_N
        var delta = 0L
        var bias = INITIAL_BIAS
        while (handled < cps.size) {
            var m = Int.MAX_VALUE
            for (cp in cps) if (cp >= n && cp < m) m = cp
            delta += (m - n).toLong() * (handled + 1)
            if (delta > Int.MAX_VALUE) return null
            n = m
            for (cp in cps) {
                if (cp < n) {
                    delta++
                    if (delta > Int.MAX_VALUE) return null
                }
                if (cp == n) {
                    var q = delta.toInt()
                    var k = BASE
                    while (true) {
                        val t = when {
                            k <= bias -> TMIN
                            k >= bias + TMAX -> TMAX
                            else -> k - bias
                        }
                        if (q < t) break
                        out.append(digit(t + (q - t) % (BASE - t)))
                        q = (q - t) / (BASE - t)
                        k += BASE
                    }
                    out.append(digit(q))
                    bias = adapt(delta.toInt(), handled + 1, handled == basic)
                    delta = 0
                    handled++
                }
            }
            delta++
            n++
        }
        return out.toString()
    }

    private fun value(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0' + 26
        in 'a'..'z' -> c - 'a'
        in 'A'..'Z' -> c - 'A'
        else -> null
    }

    /** [input] (Punycode without the ACE prefix) decoded, or null when it is not valid Punycode. */
    fun decode(input: String): String? {
        val out = ArrayList<Int>()
        val b = input.lastIndexOf('-')
        if (b > 0) {
            for (c in input.substring(0, b)) {
                if (c.code >= 0x80) return null
                out += c.code
            }
        }
        var i = 0L
        var n = INITIAL_N
        var bias = INITIAL_BIAS
        var pos = if (b > 0) b + 1 else 0
        while (pos < input.length) {
            val old = i
            var w = 1L
            var k = BASE
            while (true) {
                if (pos >= input.length) return null
                val d = value(input[pos++]) ?: return null
                i += d * w
                if (i > Int.MAX_VALUE) return null
                val t = when {
                    k <= bias -> TMIN
                    k >= bias + TMAX -> TMAX
                    else -> k - bias
                }
                if (d < t) break
                w *= (BASE - t)
                if (w > Int.MAX_VALUE) return null
                k += BASE
            }
            bias = adapt((i - old).toInt(), out.size + 1, old == 0L)
            n += (i / (out.size + 1)).toInt()
            i %= (out.size + 1)
            if (n > 0x10FFFF) return null
            out.add(i.toInt(), n)
            i++
        }
        val sb = StringBuilder()
        for (cp in out) appendCodePoint(sb, cp)
        return sb.toString()
    }
}
