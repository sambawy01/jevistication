package dev.loupe.parity

import dev.loupe.engine.DateFacts
import dev.loupe.engine.OriginFacts
import dev.loupe.engine.PortableRegex
import dev.loupe.engine.PortableText
import dev.loupe.engine.TermChangeDetector
import dev.loupe.kit.mail.MailMessage
import dev.loupe.kit.mail.Phishing
import dev.loupe.kit.site.Brands
import dev.loupe.kit.site.PageFacts
import dev.loupe.kit.site.PageForm
import dev.loupe.kit.site.ParsedUrl
import dev.loupe.kit.site.SiteCheck
import dev.loupe.kit.site.SiteConfig
import dev.loupe.kit.site.SiteSignals
import dev.loupe.persistence.JsonValue
import dev.loupe.sources.common.CsvRows
import dev.loupe.templates.Baseline

/**
 * Runs tools/parity/corpus.json (format: tools/parity/README.md) against the shared Kotlin code and
 * returns one line per case whose answer differs from the expected one; empty means every case
 * passed. The same file is compiled into loupe-kit's commonTest (JVM, iOS simulator and Android unit
 * tests) and into :android-app's debug build (ParityCorpusMain, run on an emulator or phone), so the
 * corpus is asserted on every regex engine and Unicode stack Loupe runs on.
 *
 * Only public API is used, so the file compiles in both places.
 */
object ParityCorpus {

    class Result(val cases: Int, val failures: List<String>, val byFn: Map<String, Int>)

    fun run(corpusJson: String): Result {
        val root = JsonValue.parse(corpusJson).asObj
        require(root["format"]?.asString == "loupe-parity-corpus") { "not a Loupe parity corpus" }
        val unicode = root["unicode"]?.asString
        val failures = mutableListOf<String>()
        if (unicode != PortableText.UNICODE_VERSION) {
            failures += "corpus is for Unicode $unicode, the pinned data is ${PortableText.UNICODE_VERSION}"
        }
        val byFn = LinkedHashMap<String, Int>()
        val cases = root["cases"]!!.asArr.items
        for (c in cases) {
            val o = c.asObj
            val id = o["id"]!!.asString
            val fn = o["fn"]!!.asString
            byFn[fn] = (byFn[fn] ?: 0) + 1
            val got = try {
                canonical(JsonValue.parse(evaluate(fn, o["input"]!!, o["args"]?.asObj)))
            } catch (e: Throwable) {
                failures += "$id ($fn): threw ${e::class.simpleName}: ${e.message}"
                continue
            }
            val want = canonical(o["expect"]!!)
            if (got != want) failures += "$id ($fn): expected $want, got $got"
        }
        return Result(cases.size, failures, byFn)
    }

    /** A JSON value as a canonical string, for comparison and messages (object keys sorted). */
    private fun canonical(v: JsonValue): String = when (v) {
        is JsonValue.Null -> "null"
        is JsonValue.Bool -> v.value.toString()
        is JsonValue.Num -> v.text
        is JsonValue.Str -> quote(v.value)
        is JsonValue.Arr -> v.items.joinToString(",", "[", "]") { canonical(it) }
        is JsonValue.Obj -> v.fields.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (k, x) -> quote(k) + ":" + canonical(x) }
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch.code < 0x20 || ch.code > 0x7E -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun str(s: String?): String = if (s == null) "null" else quote(s)

    private fun list(items: List<String>): String = items.joinToString(",", "[", "]") { quote(it) }

    /** `links`: [[href, visible text], ...] (a message's anchors). */
    private fun links(args: JsonValue.Obj?): List<Pair<String, String>> =
        (args?.get("links")?.asArr?.items?.map { it.asArr.items.let { p -> p[0].asString to p[1].asString } } ?: emptyList()) +
            (args?.get("html")?.asString?.let { MailMessage.anchors(it) } ?: emptyList())

    private fun hex(cp: Int): String = "U+" + cp.toString(16).uppercase().padStart(4, '0')

    /** The mechanical answer of [fn] for [input], as canonical JSON text. */
    private fun evaluate(fn: String, input: JsonValue, args: JsonValue.Obj?): String {
        val s = input.asString
        return when (fn) {
            "digits.fold" -> str(PortableText.foldDigits(s))
            "int.parse" -> PortableText.toLongOrNull(s)?.toString() ?: "null"
            "space.split" -> list(PortableText.splitSpaces(s))
            "case.fold" -> str(PortableText.fold(s))
            "case.lower" -> str(PortableText.lowercase(s))
            "match.form" -> str(PortableText.matchForm(s))
            "keyword.contains" -> PortableText.containsWord(s, args!!["keyword"]!!.asString).toString()
            "nfc" -> str(PortableText.nfc(s))
            "nfd" -> str(PortableText.nfd(s))
            "nfkc" -> str(PortableText.nfkc(s))
            "nfkd" -> str(PortableText.nfkd(s))
            "nfkc.casefold" -> str(PortableText.nfkcCasefold(s))
            "unicode32.drift" -> list(PortableText.unicode32Drift(s).map(::hex))
            "idna.label" -> str(OriginFacts.asciiLabel(s))
            "url.host" -> str(OriginFacts.host(s))
            "host.registrable" -> str(OriginFacts.registrableDomain(s))
            "host.mixed_scripts" -> OriginFacts.hasMixedScripts(s).toString()
            "date.find" -> DateFacts.find(s, args?.get("dayFirst")?.asBoolean ?: true).joinToString(",", "[", "]") { d ->
                "{\"text\":${str(d.text)},\"date\":${str(d.date.toString())},\"pattern\":${str(d.pattern)}," +
                    "\"ambiguous\":${d.ambiguous},\"alternate\":${str(d.alternate?.toString())}}"
            }
            "date.cell" -> str(CsvRows.parseDate(s, args?.get("dayFirst")?.asBoolean ?: true)?.toString())
            "number.parse" -> CsvRows.parseNumber(s)?.toString() ?: "null"
            "amount.parse" -> CsvRows.parseAmount(s).let { (minor, ccy) -> "{\"minor\":${minor ?: "null"},\"currency\":${str(ccy)}}" }
            "terms.amounts" -> TermChangeDetector.amounts(s).entries.joinToString(",", "{", "}") { (k, v) -> quote(k) + ":" + v }
            "regex.translate" -> try {
                "{\"pattern\":${str(PortableRegex.translate(s))}}"
            } catch (e: IllegalArgumentException) {
                "{\"error\":true}"
            }
            "baseline.pattern" -> Baseline.Pattern(s, "yes", "no", "corpus").answer(args!!["text"]!!.asString)
                .let { (it == "yes").toString() }
            "baseline.keyword" -> Baseline.Keyword(args!!["keywords"]!!.asArr.items.map { it.asString }, "yes", "no")
                .answer(s).let { (it == "yes").toString() }
            "link.host" -> str(ParsedUrl.parse(s)?.host)
            "link.host_signals" -> {
                val u = ParsedUrl.parse(s)
                if (u == null) "null" else list(SiteSignals.hostSignals(u, SiteConfig.DEFAULT).map { it.code })
            }
            "unicode.disguised" -> list(PortableText.disguisedCodePoints(s).map(::hex))
            "page.check" -> {
                val known = args?.get("knownGood")?.asArr?.items?.map { it.asString } ?: emptyList()
                val config = SiteConfig(Brands.BRANDS, known, Brands.SUSPICIOUS_TLDS, Brands.SHORTENERS)
                val pw = args?.get("password")?.asBoolean ?: false
                val page = if (pw) PageFacts(s, passwordFields = 1, forms = listOf(PageForm(password = true))) else PageFacts(s)
                SiteCheck.check(page, config).verdict.let { v ->
                    "{\"level\":${str(v.level)},\"codes\":${list(v.reasons.filter { it.weight > 0 }.map { it.code }.sorted())}}"
                }
            }
            "mail.check" -> Phishing.assess(
                s, args?.get("body")?.asString ?: "",
                replyTo = args?.get("replyTo")?.asString ?: "",
                trusted = args?.get("trusted")?.asArr?.items?.map { it.asString } ?: emptyList(),
                links = links(args),
            ).let { v -> "{\"level\":${str(v.level)},\"codes\":${list(v.reasons.map { it.code }.sorted())}}" }
            "mail.hrefs" -> list(MailMessage.anchors(s).map { it.first })
            "mail.anchors" -> MailMessage.anchors(s).joinToString(",", "[", "]") { (h, t) -> "[" + quote(h) + "," + quote(t) + "]" }
            "mail.link_targets" -> list(Phishing.linkTargets(args?.get("body")?.asString ?: "", links(args), s.ifEmpty { null }, Phishing.DEFAULT_CONFIG))
            "link.check" -> SiteCheck.checkUrl(s).verdict.let { v ->
                "{\"level\":${str(v.level)},\"codes\":${list(v.reasons.filter { it.weight > 0 }.map { it.code }.sorted())}}"
            }
            else -> throw IllegalArgumentException("unknown fn $fn")
        }
    }
}
