package dev.loupe.engine

/**
 * The reader of [UnicodeDataTable]: Loupe's own pinned Unicode data (the version is
 * [UnicodeDataTable.UNICODE_VERSION]) and a normalizer over it, the same on the JVM, Android and iOS.
 *
 * The platforms' own Unicode data differs (the JDK's IDN is Unicode 3.2, `java.text.Normalizer` the
 * JDK's version, Android the phone's ICU, iOS the OS's), so the same host normalised differently per
 * device (docs/ANDROID-PLAN.md, "Known parity gaps", B2). Every mechanical check that needs a
 * character property, a case fold or a normal form reads it from here instead.
 *
 * A line-for-line port of the reference implementation in `tools/unicode/gen_unicode_tables.py`,
 * which is verified there against the UCD's NormalizationTest.txt and NFKC_Casefold property.
 * Decoded lazily, once, on first use; read-only afterwards, so safe to share across threads.
 */
internal object UnicodeData {

    const val OTHER = 0
    const val LETTER = 1
    const val DECIMAL_DIGIT = 2
    const val LETTER_NUMBER = 3
    const val OTHER_NUMBER = 4
    const val NONSPACING_MARK = 5
    const val SPACING_MARK = 6
    const val ENCLOSING_MARK = 7
    const val SPACE_SEPARATOR = 8

    private const val MAX = 0x10FFFF
    private const val S_BASE = 0xAC00
    private const val L_BASE = 0x1100
    private const val V_BASE = 0x1161
    private const val T_BASE = 0x11A7
    private const val L_COUNT = 19
    private const val V_COUNT = 21
    private const val T_COUNT = 28
    private const val N_COUNT = V_COUNT * T_COUNT
    private const val S_COUNT = L_COUNT * N_COUNT

    /** Run starts and values of a `length:value;` field. */
    private class Runs(field: String) {
        val starts: IntArray
        val values: IntArray

        init {
            val parts = field.split(';')
            starts = IntArray(parts.size)
            values = IntArray(parts.size)
            var at = 0
            parts.forEachIndexed { i, p ->
                starts[i] = at
                values[i] = p.substringAfter(':').toInt(36)
                at += p.substringBefore(':').toInt(36)
            }
            check(at == MAX + 1) { "unicode table: runs cover $at code points" }
        }

        operator fun get(cp: Int): Int {
            if (cp < 0 || cp > MAX) return 0
            var lo = 0
            var hi = starts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (starts[mid] <= cp) lo = mid else hi = mid - 1
            }
            return values[lo]
        }
    }

    /** A code point set from alternating `out;in;out;...` run lengths. */
    private class Flags(field: String) {
        private val bounds: IntArray

        init {
            val lengths = field.split(';').map { it.toInt(36) }
            bounds = IntArray(lengths.size)
            var at = 0
            lengths.forEachIndexed { i, n ->
                at += n
                bounds[i] = at
            }
            check(at == MAX + 1) { "unicode table: flags cover $at code points" }
        }

        /** In the set when the first bound above [cp] is at an odd index. */
        operator fun contains(cp: Int): Boolean {
            if (cp < 0 || cp > MAX) return false
            var lo = 0
            var hi = bounds.size - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (bounds[mid] > cp) hi = mid else lo = mid + 1
            }
            return lo % 2 == 1
        }
    }

    /** `gap:t:t1,t2` / `gap:T:count:target` entries, into [into] as code point -> (tag, sequence). */
    private fun readSequences(field: String, into: (Int, Char, IntArray) -> Unit) {
        if (field.isEmpty()) return
        var end = 0
        for (entry in field.split(';')) {
            val f = entry.split(':')
            val start = end + f[0].toInt(36)
            val tag = f[1][0]
            if (tag.isUpperCase()) {
                val count = f[2].toInt(36)
                val target = f[3].toInt(36)
                for (k in 0 until count) into(start + k, tag.lowercaseChar(), intArrayOf(target + k))
                end = start + count
            } else {
                into(start, tag, f[2].split(',').map { it.toInt(36) }.toIntArray())
                end = start + 1
            }
        }
    }

    private val category by lazy { Runs(UnicodeDataTable.CATEGORY) }
    private val ccc by lazy { Runs(UnicodeDataTable.CCC) }
    private val excluded by lazy { Flags(UnicodeDataTable.EXCLUDED) }
    private val ignorable by lazy { Flags(UnicodeDataTable.IGNORABLE) }
    private val stable32 by lazy { Flags(UnicodeDataTable.STABLE32) }
    private val uts46Disallowed by lazy { Flags(UnicodeDataTable.UTS46_DISALLOWED) }
    private val joining by lazy { Runs(UnicodeDataTable.JOINING) }

    /** UTS #46 mappings that differ from NFKC_Casefold (an empty array: mapped to nothing). */
    private val uts46Mapping: HashMap<Int, IntArray> by lazy {
        val out = HashMap<Int, IntArray>()
        readSequences(UnicodeDataTable.UTS46_MAPPING) { cp, tag, seq -> out[cp] = if (tag == 'e') IntArray(0) else seq }
        out
    }

    /** A `gap:count:step:delta` field as sorted entries of four: start, count, step, delta. */
    private fun deltaEntries(field: String): IntArray {
        val entries = field.split(';')
        val out = IntArray(entries.size * 4)
        var end = 0
        entries.forEachIndexed { i, e ->
            val f = e.split(':')
            val start = end + f[0].toInt(36)
            val count = f[1].toInt(36)
            val step = f[2].toInt(36)
            out[i * 4] = start
            out[i * 4 + 1] = count
            out[i * 4 + 2] = step
            out[i * 4 + 3] = f[3].toInt(36)
            end = start + (count - 1) * step + 1
        }
        return out
    }

    /** [cp] mapped by [deltaEntries] (unchanged when no entry covers it). */
    private fun deltaMap(t: IntArray, cp: Int): Int {
        var lo = 0
        var hi = t.size / 4 - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (t[mid * 4] <= cp) lo = mid else hi = mid - 1
        }
        val start = t[lo * 4]
        if (cp < start) return cp
        val off = cp - start
        val step = t[lo * 4 + 2]
        return if (off % step == 0 && off / step < t[lo * 4 + 1]) cp + t[lo * 4 + 3] else cp
    }

    private val fold: IntArray by lazy { deltaEntries(UnicodeDataTable.FOLD) }
    private val lower: IntArray by lazy { deltaEntries(UnicodeDataTable.LOWER) }

    private class Decompositions(val canonical: HashMap<Int, IntArray>, val compat: HashMap<Int, IntArray>)

    private val decompositions: Decompositions by lazy {
        val canonical = HashMap<Int, IntArray>()
        val compat = HashMap<Int, IntArray>()
        readSequences(UnicodeDataTable.DECOMP) { cp, tag, seq -> if (tag == 'c') canonical[cp] = seq else compat[cp] = seq }
        Decompositions(canonical, compat)
    }

    /** Primary composites: the two-character canonical decompositions not excluded from composition. */
    private val composites: HashMap<Long, Int> by lazy {
        val out = HashMap<Long, Int>()
        for ((cp, seq) in decompositions.canonical) {
            if (seq.size == 2 && cp !in excluded) out[pairKey(seq[0], seq[1])] = cp
        }
        out
    }

    private val fullFold: HashMap<Int, IntArray> by lazy {
        val out = HashMap<Int, IntArray>()
        readSequences(UnicodeDataTable.FULLFOLD) { cp, _, seq -> out[cp] = seq }
        out
    }

    private val casefoldExceptions: HashMap<Int, IntArray> by lazy {
        val out = HashMap<Int, IntArray>()
        readSequences(UnicodeDataTable.CASEFOLD_NFKC) { cp, _, seq -> out[cp] = seq }
        out
    }

    private fun pairKey(a: Int, b: Int): Long = (a.toLong() shl 21) or b.toLong()

    // ------------------------------------------------------------------------------ properties
    fun category(cp: Int): Int = category[cp]

    fun combiningClass(cp: Int): Int = ccc[cp]

    /** UTS #46 status `disallowed` (IdnaMappingTable.txt of the pinned version). */
    fun uts46Disallowed(cp: Int): Boolean = cp in uts46Disallowed

    /** The UTS #46 mapping of a code point that is not disallowed and not a deviation. */
    fun uts46Map(cp: Int): IntArray = uts46Mapping[cp] ?: nfkcCasefoldChar(cp)

    /** Joining_Type: 0 U (non-joining, the default), 1 L, 2 D, 3 R, 4 T (transparent). */
    fun joiningType(cp: Int): Int = joining[cp]

    /** Assigned in Unicode 3.2 and mapped the same by 3.2 nameprep and by the pinned NFKC_Casefold. */
    fun stableSince32(cp: Int): Boolean = cp in stable32

    /** The simple case fold `lower(upper(cp))`: one UTF-16 unit per unit (the generator asserts it). */
    fun fold(cp: Int): Int {
        if (cp < 0x80) return if (cp in 0x41..0x5A) cp + 32 else cp
        return deltaMap(fold, cp)
    }

    /** The simple lowercase mapping of UnicodeData.txt: one UTF-16 unit per unit. */
    fun lower(cp: Int): Int {
        if (cp < 0x80) return if (cp in 0x41..0x5A) cp + 32 else cp
        return deltaMap(lower, cp)
    }

    // ------------------------------------------------------------------------------ normal forms
    fun decompose(cps: IntArray, compat: Boolean): IntArray {
        val out = IntList(cps.size + 8)
        val d = decompositions
        fun one(c: Int) {
            if (c >= S_BASE && c < S_BASE + S_COUNT) {
                val s = c - S_BASE
                out.add(L_BASE + s / N_COUNT)
                out.add(V_BASE + (s % N_COUNT) / T_COUNT)
                if (s % T_COUNT != 0) out.add(T_BASE + s % T_COUNT)
                return
            }
            val m = d.canonical[c] ?: (if (compat) d.compat[c] else null)
            if (m != null) for (x in m) one(x) else out.add(c)
        }
        for (c in cps) one(c)
        // Canonical ordering: a stable sort of each run of non-starters by combining class.
        val a = out.toIntArray()
        var i = 0
        while (i < a.size) {
            if (ccc[a[i]] == 0) {
                i++
                continue
            }
            var j = i
            while (j < a.size && ccc[a[j]] != 0) j++
            // insertion sort: runs are short, and it is stable
            for (k in i + 1 until j) {
                val v = a[k]
                val cv = ccc[v]
                var p = k - 1
                while (p >= i && ccc[a[p]] > cv) {
                    a[p + 1] = a[p]
                    p--
                }
                a[p + 1] = v
            }
            i = j
        }
        return a
    }

    private fun composePair(a: Int, b: Int): Int {
        if (a >= L_BASE && a < L_BASE + L_COUNT && b >= V_BASE && b < V_BASE + V_COUNT) {
            return S_BASE + ((a - L_BASE) * V_COUNT + (b - V_BASE)) * T_COUNT
        }
        if (a >= S_BASE && a < S_BASE + S_COUNT && (a - S_BASE) % T_COUNT == 0 && b > T_BASE && b < T_BASE + T_COUNT) {
            return a + (b - T_BASE)
        }
        return composites[pairKey(a, b)] ?: -1
    }

    /** Canonical composition (UAX #15, the sample algorithm of its annex). */
    fun compose(cps: IntArray): IntArray {
        if (cps.isEmpty()) return cps
        val out = IntList(cps.size)
        out.add(cps[0])
        var starter = 0
        var last = ccc[cps[0]]
        if (last != 0) last = 256
        for (i in 1 until cps.size) {
            val c = cps[i]
            val cc = ccc[c]
            val comp = composePair(out[starter], c)
            if (comp >= 0 && (last < cc || last == 0)) {
                out[starter] = comp
                continue
            }
            if (cc == 0) starter = out.size
            last = cc
            out.add(c)
        }
        return out.toIntArray()
    }

    /** NFKC_Casefold of one code point: nothing for a default ignorable, else NFKC(full fold(NFKC(cp))). */
    fun nfkcCasefoldChar(cp: Int): IntArray {
        casefoldExceptions[cp]?.let { return it }
        if (cp in ignorable) return IntArray(0)
        val first = compose(decompose(intArrayOf(cp), compat = true))
        val folded = IntList(first.size + 2)
        for (c in first) {
            val f = fullFold[c]
            if (f != null) for (x in f) folded.add(x) else folded.add(c)
        }
        return compose(decompose(folded.toIntArray(), compat = true))
    }

    /** toNFKC_Casefold (Unicode chapter 3.13): NFKC_CF of every character, then NFC. */
    fun nfkcCasefold(cps: IntArray): IntArray {
        val mapped = IntList(cps.size + 4)
        for (c in cps) for (x in nfkcCasefoldChar(c)) mapped.add(x)
        return compose(decompose(mapped.toIntArray(), compat = false))
    }

    /** A growable IntArray: the normalizer's buffers without boxing. */
    private class IntList(capacity: Int) {
        private var a = IntArray(maxOf(capacity, 4))
        var size = 0
            private set

        fun add(v: Int) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }

        operator fun get(i: Int): Int = a[i]

        operator fun set(i: Int, v: Int) {
            a[i] = v
        }

        fun toIntArray(): IntArray = a.copyOf(size)
    }
}
