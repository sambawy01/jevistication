package dev.loupe.engine

/** Lookup over [UnicodeScriptTable]: the portable stand-in for `Character.UnicodeScript`. */
internal object UnicodeScripts {

    private val starts: IntArray
    private val ids: IntArray

    init {
        val runs = UnicodeScriptTable.RUNS.split(';').filter { it.isNotEmpty() }
        starts = IntArray(runs.size)
        ids = IntArray(runs.size)
        runs.forEachIndexed { i, run ->
            starts[i] = run.substringBefore(':').toInt(36)
            ids[i] = run.substringAfter(':').toInt(36) - 1
        }
    }

    /** As [letterScript]: a script id for a letter of a real script, else -1. */
    fun letterScript(codePoint: Int): Int {
        if (codePoint < 0 || codePoint > 0x10FFFF) return -1
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= codePoint) lo = mid else hi = mid - 1
        }
        return ids[lo]
    }
}
