package dev.loupe.engine

/** Lowercase hex of [bytes], two digits per byte: what `"%02x".format(b)` produced per byte. */
internal fun hex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xff
        out.append(digits[v ushr 4]).append(digits[v and 0x0f])
    }
    return out.toString()
}

/** The code points of [s], pairing surrogates as `String.codePoints()` does on the JVM. */
internal fun codePoints(s: String): IntArray {
    val out = ArrayList<Int>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            out += 0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
            i += 2
        } else {
            out += c.code
            i++
        }
    }
    return out.toIntArray()
}

/** Appends [codePoint] to [sb] as UTF-16. */
internal fun appendCodePoint(sb: StringBuilder, codePoint: Int) {
    if (codePoint < 0x10000) {
        sb.append(codePoint.toChar())
    } else {
        val v = codePoint - 0x10000
        sb.append((0xD800 + (v shr 10)).toChar()).append((0xDC00 + (v and 0x3ff)).toChar())
    }
}

/**
 * `"%.Nf".format(value)` as the JVM's `java.util.Formatter` does it, in portable Kotlin: the
 * shortest decimal digits of [value] rounded HALF_UP to [decimals] places, with an ASCII `.`.
 *
 * Used on iOS; the JVM keeps calling the real formatter, and a jvmTest checks the two agree.
 */
internal fun formatFixedPortable(value: Double, decimals: Int): String {
    require(decimals >= 0) { "decimals must not be negative" }
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    val negative = value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)
    val repr = kotlin.math.abs(value).toString() // e.g. "123.45", "1.0E-5", "1.2345E10"
    val mantissa = repr.substringBefore('E').substringBefore('e')
    val exponent = if ('E' in repr || 'e' in repr) repr.substringAfter('E').substringAfter('e').toInt() else 0
    val intPart = mantissa.substringBefore('.')
    val fracPart = if ('.' in mantissa) mantissa.substringAfter('.') else ""
    // value = 0.DIGITS * 10^point
    var digits = (intPart + fracPart).trimStart('0')
    var point = intPart.length + exponent - ((intPart + fracPart).length - (intPart + fracPart).trimStart('0').length)
    if (digits.isEmpty()) {
        digits = "0"
        point = 1
    }
    // Keep point + decimals digits, rounding half up on the next one.
    val keep = point + decimals
    val kept: IntArray
    var newPoint = point
    if (keep < 0) {
        kept = IntArray(0)
    } else if (keep >= digits.length) {
        kept = IntArray(keep) { if (it < digits.length) digits[it] - '0' else 0 }
    } else {
        val arr = IntArray(keep) { digits[it] - '0' }
        if (digits[keep] >= '5') {
            var i = keep - 1
            var carry = true
            while (carry && i >= 0) {
                arr[i]++
                if (arr[i] == 10) { arr[i] = 0; i-- } else carry = false
            }
            if (carry) {
                kept = IntArray(keep + 1) { if (it == 0) 1 else arr[it - 1] }
                newPoint = point + 1
            } else kept = arr
        } else kept = arr
    }
    // Render kept digits (value = 0.kept * 10^newPoint) with exactly `decimals` fraction digits.
    val sb = StringBuilder()
    if (negative) sb.append('-')
    if (newPoint <= 0) {
        sb.append('0')
    } else {
        for (i in 0 until newPoint) sb.append(if (i < kept.size) ('0' + kept[i]) else '0')
    }
    if (decimals > 0) {
        sb.append('.')
        for (i in 0 until decimals) {
            val idx = newPoint + i
            sb.append(if (idx >= 0 && idx < kept.size) ('0' + kept[idx]) else '0')
        }
    }
    return sb.toString()
}
