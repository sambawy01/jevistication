package dev.loupe.kit.privacy

import dev.loupe.engine.BoundedRegex
import dev.loupe.engine.ContentHash
import dev.loupe.engine.PortableText
import dev.loupe.engine.Rx
import kotlin.math.ln

/*
 * Regex secret detectors. Only a redacted preview ever leaves this file.
 *
 * PROVENANCE: ported from the owner's Loupe Station repository (`~/laya-studio`,
 * `laya_studio/scan/secret_rules.py`, commit ea7697a4f78e9a648ba49fc8bf9d13226c26cb0e). The
 * detector table (rule ids, labels, patterns, value groups), the assignment / placeholder patterns,
 * the entropy thresholds, the redaction format and MAX_FINDINGS are copied verbatim. The one
 * mechanical difference: the in-memory de-duplication hash is SHA-256 (the engine's `ContentHash`)
 * instead of BLAKE2b-64. Nothing hashed or matched is stored or logged.
 *
 * `findSecrets` returns [SecretFinding]s like type "aws_access_key", label "AWS access key",
 * preview "AKIA… (AWS access key)", line 3. The matched value itself is dropped here.
 */

/** One credential found in a text: what it is and a redacted preview. Never the value. */
data class SecretFinding(
    val type: String,
    val label: String,
    val preview: String,
    /** 1-based line of the first occurrence; null for the `.env` file-name finding. */
    val line: Int?,
    /** The assignment's key name (`DB_PASSWORD`), at most 60 characters, for assignments only. */
    val keyName: String? = null,
    /** How many times the same value was seen. */
    val count: Int = 1,
)

object SecretRules {
    /**
     * (type, label, pattern, group holding the secret value). [rx] is a [BoundedRegex]: a leading `\b`
     * is checked in code, not by a lookbehind (Kotlin/Native pays O(position) for one).
     */
    internal class Detector(val type: String, val label: String, val rx: BoundedRegex, val group: Int)

    internal val DETECTORS: List<Detector> = listOf(
        Detector("private_key", "private key (PEM)",
            BoundedRegex("""-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED |PGP )?PRIVATE KEY(?: BLOCK)?-----""", bounded = false), 0),
        Detector("anthropic_key", "Anthropic API key", BoundedRegex("""sk-ant-[A-Za-z0-9_\-]{20,}"""), 0),
        Detector("openai_key", "OpenAI API key",
            BoundedRegex("""sk-(?!ant-)(?:proj-|svcacct-|admin-)?[A-Za-z0-9_\-]{20,}"""), 0),
        Detector("aws_access_key", "AWS access key", BoundedRegex("""(?:AKIA|ASIA|ABIA|ACCA)[0-9A-Z]{16}${Rx.WB_END}"""), 0),
        Detector("aws_secret_key", "AWS secret key",
            // (?i) spelled out for these ASCII words (the JDK's ASCII case-insensitivity, on every engine)
            BoundedRegex("""[aA][wW][sS]_?[sS][eE][cC][rR][eE][tT]_?(?:[aA][cC][cC][eE][sS][sS]_?)?[kK][eE][yY]["']?${Rx.SP}*[:=]${Rx.SP}*["']?([A-Za-z0-9/+=]{40})${Rx.WB}""", bounded = false), 1),
        Detector("google_api_key", "Google API key", BoundedRegex("""AIza[0-9A-Za-z_\-]{35}"""), 0),
        Detector("google_oauth_secret", "Google OAuth client secret", BoundedRegex("""GOCSPX-[A-Za-z0-9_\-]{20,}"""), 0),
        Detector("stripe_key", "Stripe key", BoundedRegex("""(?:sk|rk)_(?:live|test)_[0-9A-Za-z]{16,}"""), 0),
        Detector("stripe_webhook_secret", "Stripe webhook secret", BoundedRegex("""whsec_[0-9A-Za-z]{20,}"""), 0),
        Detector("github_token", "GitHub token",
            BoundedRegex("""(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{22,})"""), 0),
        Detector("slack_token", "Slack token", BoundedRegex("""xox[abposre]-[A-Za-z0-9\-]{10,}"""), 0),
        Detector("slack_webhook", "Slack webhook URL",
            BoundedRegex("""https://hooks\.slack\.com/services/[A-Za-z0-9/_\-]{20,}""", bounded = false), 0),
        Detector("jwt", "JSON Web Token", BoundedRegex("""eyJ[A-Za-z0-9_\-]{8,}\.eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}"""), 0),
    )

    // Possessive `++`: a prefix segment is a whole alphanumeric run, split only at separators, so a
    // long run of letters can't make the engine backtrack (it was polynomial before).
    // Python: the same pattern with (?<![A-Za-z0-9_]) after (?i); guarded (see GuardedRegex). The (?i)
    // is spelled out as ASCII case classes (exactly the JDK's (?i), on every engine), so the groups
    // keep the text's case; spaces are Rx.SP and digits [0-9] (the text is digit-folded first).
    internal val ASSIGN = GuardedRegex(
        """((?:[A-Za-z0-9]++[_.\-]){0,4}?""" +
            """([pP][aA][sS][sS][wW][oO][rR][dD]|[pP][aA][sS][sS][wW][dD]|[pP][wW][dD]|[sS][eE][cC][rR][eE][tT]|[tT][oO][kK][eE][nN]|[aA][pP][iI][_\-]?[kK][eE][yY]|[aA][cC][cC][eE][sS][sS][_\-]?[kK][eE][yY]|[aA][uU][tT][hH][_\-]?[kK][eE][yY]|[cC][lL][iI][eE][nN][tT][_\-]?[sS][eE][cC][rR][eE][tT]|""" +
            """[pP][rR][iI][vV][aA][tT][eE][_\-]?[kK][eE][yY]|[cC][rR][eE][dD][eE][nN][tT][iI][aA][lL])[sS]?(?:[_.\-]?(?:[kK][eE][yY]|[vV][aA][lL][uU][eE]|[hH][aA][sS][hH]|[sS][tT][rR]|[sS][tT][rR][iI][nN][gG]|[pP][lL][aA][iI][nN]|[bB]64|[bB][aA][sS][eE]64|[sS][eE][cC][rR][eE][tT]|[tT][oO][kK][eE][nN]|[iI][dD]|[0-9]+))*)""" +
            """["']?${Rx.SP}*(?:=|:|=>)${Rx.SP}*["']?([^${Rx.SPACE}"'#,;]{4,200})""",
    ) { isAsciiAlnum(it) || it == '_' }

    /** Matched on [PortableText.fold]ed text (only the ranges are used). */
    private val ASSIGN_KW = Regex(
        """passw|pwd|secret|token|api[_\-]?key|access[_\-]?key|auth[_\-]?key|""" +
            """client[_\-]?secret|private[_\-]?key|credential""",
    )

    /** Matched on [PortableText.fold]ed values: see [isPlaceholder]. */
    private val PLACEHOLDER = Regex(
        """^(?:x+|\*+|\.+|-+|null|none|nil|true|false|changeme|change[_\-]?me|example|sample|test|""" +
            """password|secret|token|your[_\-].*|<.*>|\$\{.*\}|\$[a-z_]+|\{\{.*\}\}|%\(.*\)s|os\.environ.*|""" +
            """process\.env.*|env\(.*|getenv.*|input\(.*|required|optional|string|str|int|todo|tbd|redacted)$""",
    )

    /** A value that is a placeholder, not a secret (`changeme`, `${API_KEY}`, `<token>`), case-insensitively. */
    internal fun isPlaceholder(value: String): Boolean = PLACEHOLDER.containsMatchIn(PortableText.fold(value))

    private val LABELS: Map<String, String> = DETECTORS.associate { it.type to it.label } + mapOf(
        "password_assignment" to "password in plain text", "secret_assignment" to "secret or token assignment",
        "high_entropy_secret" to "high-entropy key value", "env_file" to ".env file",
    )

    const val MAX_FINDINGS = 20

    fun labelFor(kind: String): String = LABELS[kind] ?: kind

    fun shannonEntropy(s: String): Double {
        if (s.isEmpty()) return 0.0
        val counts = HashMap<Char, Int>()
        for (ch in s) counts[ch] = (counts[ch] ?: 0) + 1
        val n = s.length.toDouble()
        return -counts.values.sumOf { c -> c / n * (ln(c / n) / ln(2.0)) }
    }

    /** First few characters + an ellipsis + what it is. Short secrets reveal fewer characters. */
    fun redact(value: String, kind: String): String {
        val keep = if (value.length >= 16) 4 else maxOf(0, minOf(4, value.length / 4))
        return "${value.take(keep)}… (${labelFor(kind)})"
    }

    /** `.env`, `.env.local`, `prod.env`: the file itself is a finding. */
    fun isEnvName(name: String): Boolean {
        val low = name.lowercase()
        return low == ".env" || low.startsWith(".env.") || low.endsWith(".env")
    }

    /** Where group [group] of [m] starts. Every value group in this file ends where its match ends. */
    internal fun groupStart(m: MatchResult, group: Int): Int =
        if (group == 0) m.range.first else m.range.last + 1 - m.groupValues[group].length

    internal fun assignKeywords(window: String, from: Int): Sequence<MatchResult> =
        if (from > window.length) emptySequence() else ASSIGN_KW.findAll(PortableText.fold(window), from)

    /** Scan one piece of text for credentials. Never returns the secret itself. */
    fun findSecrets(text: String?, name: String = "", isEnv: Boolean = false): List<SecretFinding> {
        val c = SecretCollector(name, isEnv)
        if (!text.isNullOrEmpty()) c.scan(text)
        return c.found
    }

    /** Replace every detected secret in [text] with a placeholder. */
    fun redactText(text: String): String {
        var out = text
        for (d in DETECTORS) {
            out = d.rx.replace(out) { m -> if (d.group != 0) m.value.replace(m.groupValues[d.group], "[secret]") else "[secret]" }
        }
        out = ASSIGN.replace(out) { m ->
            if (!isPlaceholder(m.groupValues[3])) m.value.replace(m.groupValues[3], "[secret]") else m.value
        }
        return out
    }
}

/**
 * Secret detection over a stream of overlapping windows.
 *
 * `scan(text, lo, hi, lineBase)` looks at the whole window but only accepts matches that start in
 * [lo, hi), so a key that straddles two chunks is found exactly once (the caller keeps an overlap
 * longer than any secret). Findings are de-duplicated by (kind, value hash); a repeat bumps `count`.
 * Only redacted previews are kept; the value hashes live in memory for de-duplication only.
 */
class SecretCollector(name: String = "", isEnv: Boolean = false) {
    private val items = mutableListOf<SecretFinding>()
    private val seen = HashMap<Pair<String, String>, Int>()   // (kind, hash) -> index in items

    val found: List<SecretFinding> get() = items.toList()

    var hits: MutableList<Pair<Int, String>> = mutableListOf()
        private set

    init {
        if (isEnv) {
            items += SecretFinding("env_file", SecretRules.labelFor("env_file"), "${name.take(60)} (${SecretRules.labelFor("env_file")})", null)
        }
    }

    private fun add(text: String, lineBase: Int, kind: String, value: String, start: Int, key: String? = null) {
        val dedup = kind to ContentHash.of(value)
        hits += start to kind
        val prev = seen[dedup]
        if (prev != null) {
            items[prev] = items[prev].copy(count = items[prev].count + 1)
            return
        }
        if (items.size >= SecretRules.MAX_FINDINGS) return
        val preview = if (kind == "private_key") "-----BEGIN … PRIVATE KEY----- (${SecretRules.labelFor(kind)})" else SecretRules.redact(value, kind)
        val line = lineBase + text.substring(0, start).count { it == '\n' }
        seen[dedup] = items.size
        items += SecretFinding(kind, SecretRules.labelFor(kind), preview, line, key?.take(60))
    }

    /**
     * ASSIGN matches starting in [lo, hi). The full regex is slow, so it only runs in small regions
     * around a cheap keyword hit (its key group always contains one of these words).
     */
    private fun assignments(text: String, lo: Int, hi: Int): List<MatchResult> {
        val regions = mutableListOf<IntArray>()
        val kwWindow = text.substring(0, minOf(text.length, hi + 80))
        for (k in SecretRules.assignKeywords(kwWindow, maxOf(0, lo - 1))) {
            val a = maxOf(lo, k.range.first - 80)
            val b = minOf(text.length, k.range.last + 1 + 280)
            if (regions.isNotEmpty() && a <= regions.last()[1]) {
                regions.last()[1] = maxOf(regions.last()[1], b)      // merge overlapping regions: scan once
            } else {
                regions += intArrayOf(a, b)
            }
        }
        val out = mutableListOf<MatchResult>()
        var lastEnd = -1
        for ((a, b) in regions.map { it[0] to it[1] }) {
            val window = text.substring(0, b)
            val from = maxOf(a, lastEnd)
            if (from > window.length) continue
            for (m in SecretRules.ASSIGN.findAll(window, from)) {
                if (m.range.first >= hi) break
                out += m
                lastEnd = m.range.last + 1
            }
        }
        return out.sortedBy { it.range.first }
    }

    fun scan(text: String, lo: Int = 0, hi: Int = text.length, lineBase: Int = 1) {
        hits = mutableListOf()
        val spans = mutableListOf<Pair<Int, Int>>()
        fun overlaps(a: Int, b: Int): Boolean = spans.any { (s, e) -> a < e && s < b }

        for (d in SecretRules.DETECTORS) {
            val window = text.substring(0, minOf(text.length, hi + 4096))
            if (lo > window.length) continue
            for (m in d.rx.findAll(window, lo)) {
                if (m.range.first >= hi) break
                val gs = SecretRules.groupStart(m, d.group)
                val ge = gs + m.groupValues[d.group].length
                if (overlaps(gs, ge)) continue
                spans += gs to ge
                val value = if (d.type == "private_key") "-----BEGIN" else m.groupValues[d.group]
                add(text, lineBase, d.type, value, gs)
            }
        }
        for (m in assignments(text, lo, hi)) {
            val key = m.groupValues[1]
            val word = m.groupValues[2].lowercase()
            val value = m.groupValues[3]
            val vs = SecretRules.groupStart(m, 3)
            if (SecretRules.isPlaceholder(value) || overlaps(vs, vs + value.length)) continue
            val kind = when {
                word in setOf("password", "passwd", "pwd") -> "password_assignment"
                value.length >= 20 && SecretRules.shannonEntropy(value) >= 3.5 -> "high_entropy_secret"
                "secret" in word || "token" in word -> "secret_assignment"
                // api_key/access_key etc. with a short, low-entropy value: most likely a placeholder
                value.length < 12 || SecretRules.shannonEntropy(value) < 3.0 -> continue
                else -> "secret_assignment"
            }
            spans += vs to vs + value.length
            add(text, lineBase, kind, value, vs, key)
        }
    }
}
