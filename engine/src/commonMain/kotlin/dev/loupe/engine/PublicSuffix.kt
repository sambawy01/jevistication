package dev.loupe.engine

/**
 * The Mozilla Public Suffix List, bundled as a pinned snapshot (BUILD.md risk 10).
 *
 * The list is read from the engine's own resources (on iOS, a constant generated from the same
 * file at build time), once, on first use. **Nothing here touches
 * the network** — the engine is offline by construction (milestone 7). The snapshot is refreshed
 * at build time by `tools/update-psl.sh`, which re-downloads, sanity-checks and re-hashes it; a
 * test fails if the resource and its recorded SHA-256 ever disagree.
 *
 * A rule set is a plain `Set<String>` of PSL rules in their ASCII (punycode) form: `com`,
 * `co.uk`, wildcards such as `*.ck`, exceptions such as `!www.ck`. A hand-built set of plain
 * suffixes is a valid rule set too, which is why every call site takes one as a parameter.
 *
 * **Which section the fraud check uses: both, [ALL] is the default.** The PRIVATE section lists
 * suffixes under which unrelated parties get their own names — `github.io`, `blogspot.com`,
 * `herokuapp.com`, `pages.dev`. For the fraud check that is exactly the boundary that matters:
 * `paypal.github.io` is controlled by whoever owns that GitHub account, not by GitHub, so it
 * must read as its own registrable domain rather than borrow `github.io`'s reputation or match a
 * brand called "github". ICANN-only is available as [ICANN] for callers that need registry
 * semantics (e.g. matching what a registrar would sell).
 */
object PublicSuffix {

    /** ICANN and PRIVATE rules together — the default for every call site. */
    val ALL: Set<String> by lazy { load().let { it.icann + it.private } }

    /** ICANN-section rules only. */
    val ICANN: Set<String> by lazy { load().icann }

    /** The `// VERSION:` line of the bundled snapshot, e.g. `2026-09-21_18-50-07_UTC`. */
    val VERSION: String by lazy { load().version }

    /** The rule set every call site uses unless told otherwise. */
    val DEFAULT: Set<String> get() = ALL

    internal class Parsed(val icann: Set<String>, val private: Set<String>, val version: String)

    // Thread-safe on every target: lazy defaults to SYNCHRONIZED.
    private val parsed: Parsed by lazy { parse(bundledPublicSuffixList().lines()) }

    private fun load(): Parsed = parsed

    /** Parses the PSL text format. Exposed for tests. */
    internal fun parse(lines: List<String>): Parsed {
        val icann = HashSet<String>(12_000)
        val private = HashSet<String>(8_000)
        var version = "unknown"
        var section: MutableSet<String>? = null
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("// VERSION:") -> version = line.removePrefix("// VERSION:").trim()
                line.contains("===BEGIN ICANN DOMAINS===") -> section = icann
                line.contains("===BEGIN PRIVATE DOMAINS===") -> section = private
                line.contains("===END ICANN DOMAINS===") ||
                    line.contains("===END PRIVATE DOMAINS===") -> section = null
                line.isEmpty() || line.startsWith("//") -> Unit
                else -> {
                    // A rule is the first whitespace-delimited token of the line.
                    val rule = line.substringBefore(' ').substringBefore('\t')
                    val target = section ?: continue
                    normaliseRule(rule)?.let { target.add(it) }
                }
            }
        }
        check(icann.isNotEmpty() && private.isNotEmpty()) {
            "public suffix list has no ICANN or PRIVATE section; the resource is damaged"
        }
        return Parsed(icann, private, version)
    }

    private fun normaliseRule(rule: String): String? {
        val exception = rule.startsWith("!")
        val body = rule.removePrefix("!")
        val ascii = body.split('.').map { label ->
            if (label == "*") label else toAsciiLabel(label) ?: return null
        }.joinToString(".")
        return if (exception) "!$ascii" else ascii
    }

    /** One label to its lowercase ASCII (punycode) form, or null if it is not a valid label. */
    internal fun toAsciiLabel(label: String): String? {
        if (label.isEmpty()) return null
        val lower = label.lowercase()
        if (lower.all { it.code < 128 }) return lower
        return idnaToAscii(lower)?.takeIf { it.isNotEmpty() }
    }
}
