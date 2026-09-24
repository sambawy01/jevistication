package dev.loupe.kit.site

import dev.loupe.engine.Contact
import dev.loupe.kit.mail.Phishing
import dev.loupe.persistence.JsonValue

/**
 * Runs the shared test vectors of the phishing formula (docs/phishing-vectors.json, documented in
 * docs/PHISHING-FORMULA.md §8). Loupe Station runs the same file; each vector's `expect` is what both
 * implementations must produce: level, score, the set of weighted signal codes, and for pages the two
 * PSL answers (host control with the full PSL; the online-facts domain with ICANN only).
 */
internal object PhishingVectors {
    /** v1.1 vectors whose verdict v1.2 changes by design (docs/PHISHING-FORMULA.md changelog). */
    val V12_CHANGES: Map<String, Map<String, JsonValue>> = mapOf(
        "online-feed-host" to mapOf("level" to JsonValue.Str("caution"), "score" to JsonValue.Num("45")),     // v1.1: danger 60
        "online-feed-domain" to mapOf("level" to JsonValue.Str("caution"), "score" to JsonValue.Num("30")),   // v1.1: danger 60
    )

    data class Outcome(
        val id: String,
        val level: String,
        val score: Int,
        val signals: List<String>,
        val hostControl: String?,
        val onlineDomain: String?,
        val flag: Boolean?,
    )

    data class Vector(val kind: String, val id: String, val input: JsonValue.Obj, val expect: JsonValue.Obj?)

    fun vectors(json: String): Pair<String, List<Vector>> {
        val root = JsonValue.parse(json).asObj
        val now = root.str("now") ?: "2026-09-24T12:00:00Z"
        val out = mutableListOf<Vector>()
        for (kind in listOf("pages", "emails")) {
            for (v in (root[kind] as? JsonValue.Arr)?.items.orEmpty()) {
                val o = v.asObj
                out += Vector(kind, o.str("id")!!, o, o["expect"] as? JsonValue.Obj)
            }
        }
        return now to out
    }

    private fun strings(v: JsonValue?): List<String> = (v as? JsonValue.Arr)?.items?.mapNotNull { (it as? JsonValue.Str)?.value }.orEmpty()

    private fun bool(o: JsonValue.Obj, key: String): Boolean = (o[key] as? JsonValue.Bool)?.value ?: false

    fun online(o: JsonValue.Obj?, now: String): OnlineContext? {
        if (o == null) return null
        val facts = (o["facts"] as? JsonValue.Obj)?.fields?.mapValues { (domain, f) ->
            OnlineSignals.parseDomainFacts(JsonValue.compactOf(f, domain), domain)
        }.orEmpty()
        val feeds = (o["feeds"] as? JsonValue.Obj)?.fields?.mapValues { strings(it.value) }
        val sb = strings(o["safeBrowsing"]).toSet()
        // A "phishingdb" feed goes through the phone's Phishing.Database index, the others through FeedIndex.
        val plain = feeds?.filterKeys { it != PhishingDb.SOURCE }?.takeIf { it.isNotEmpty() }?.let(::FeedIndex)
        val pdb = feeds?.get(PhishingDb.SOURCE)?.let { PhishingDb.build(listOf(it.joinToString("\n")), emptyList(), now) }
        return OnlineContext(now, facts, plain, sb, safeBrowsingFetchedAt = now, feedsFetchedAt = now, phishingDb = pdb)
    }

    fun run(v: Vector, now: String): Outcome {
        val o = v.input
        val ctx = online(o["online"] as? JsonValue.Obj, now)
        return if (v.kind == "pages") {
            val url = o.str("url")!!
            val forms = (o["forms"] as? JsonValue.Arr)?.items?.map { f ->
                val fo = f.asObj
                PageForm(fo.str("action"), bool(fo, "password"), bool(fo, "card"))
            }.orEmpty()
            val page = PageFacts(
                url = url, title = o.str("title"), siteName = o.str("siteName"), faviconHost = o.str("faviconHost"),
                passwordFields = if (bool(o, "password")) 1 else 0, cardFields = if (bool(o, "card")) 1 else 0,
                forms = forms, claimedBrand = o.str("claimedBrand"),
            )
            val verdict = SiteScoring.verdict(page, online = ctx?.let { OnlineSignals.pageReasons(url, it) }.orEmpty())
            val host = ParsedUrl.parse(url)?.host.orEmpty()
            Outcome(
                v.id, verdict.level, verdict.score, verdict.reasons.filter { it.weight > 0 }.map { it.code }.sorted(),
                Hosts.registrableDomain(host), OnlineSignals.lookupDomain(host), null,
            )
        } else {
            val contacts = (o["contacts"] as? JsonValue.Arr)?.items?.map { c ->
                Contact(c.asObj.str("name")!!, strings(c.asObj["addresses"]).toSet())
            }.orEmpty()
            val links = (o["links"] as? JsonValue.Arr)?.items?.map { l -> strings(l).let { it[0] to it.getOrElse(1) { "" } } }.orEmpty()
            val verdict = Phishing.assess(
                sender = o.str("from")!!, text = o.str("body").orEmpty(), replyTo = o.str("replyTo").orEmpty(),
                authHeader = o.str("auth").orEmpty(), links = links, contacts = contacts, online = ctx,
            )
            Outcome(v.id, verdict.level, verdict.score, verdict.reasons.filter { it.weight > 0 }.map { it.code }.sorted(), null, null, verdict.flag)
        }
    }

    /** Differences between [actual] and the vector's `expect` (empty when they agree). */
    fun check(v: Vector, actual: Outcome): List<String> {
        val e0 = v.expect ?: return listOf("${v.id}: no expect")
        // Formula v1.2 list strength moves exactly these two v1.1 vectors (the file stays the byte-for-byte
        // v1.1 copy, as in Station's tests/test_phishing_vectors.py V12_CHANGES).
        val e = V12_CHANGES[v.id]?.let { ch -> JsonValue.Obj(LinkedHashMap(e0.fields).also { it.putAll(ch) }) } ?: e0
        val out = mutableListOf<String>()
        if (e.str("level") != actual.level) out += "${v.id}: level ${actual.level}, expected ${e.str("level")}"
        if (e.str("score")?.toInt() != actual.score) out += "${v.id}: score ${actual.score}, expected ${e.str("score")}"
        val signals = strings(e["signals"]).sorted()
        if (signals != actual.signals) out += "${v.id}: signals ${actual.signals}, expected $signals"
        if (v.kind == "pages") {
            if (e.fields.containsKey("hostControl") && (e["hostControl"] as? JsonValue.Str)?.value != actual.hostControl) {
                out += "${v.id}: hostControl ${actual.hostControl}, expected ${e["hostControl"]}"
            }
            if (e.fields.containsKey("onlineDomain") && (e["onlineDomain"] as? JsonValue.Str)?.value != actual.onlineDomain) {
                out += "${v.id}: onlineDomain ${actual.onlineDomain}, expected ${e["onlineDomain"]}"
            }
        } else if (e.fields.containsKey("flag") && (e["flag"] as? JsonValue.Bool)?.value != actual.flag) {
            out += "${v.id}: flag ${actual.flag}, expected ${e["flag"]}"
        }
        return out
    }
}

/** JSON text of one value, so a fact object can go back through the same parser the phone uses. */
internal fun JsonValue.Companion.compactOf(v: JsonValue, domain: String): String {
    // The facts in a vector are written flat ({created, first_seen, sources, fetched_at}); the helper
    // nests them. Rebuild the helper's shape so the vector exercises the real parser.
    val o = v.asObj
    fun s(k: String): JsonValue = o[k] ?: JsonValue.Null
    val reg = JsonValue.obj("created" to s("created"), "updated" to s("updated"), "expires" to s("expires"), "registrar" to s("registrar"))
    val certs = JsonValue.obj("first_seen" to s("first_seen"), "latest_issued" to s("latest_issued"), "count_90d" to s("count_90d"), "issuers" to (o["issuers"] ?: JsonValue.Arr(emptyList())))
    return dev.loupe.persistence.JsonText.compact(
        JsonValue.obj(
            "domain" to JsonValue.Str(domain), "fetched_at" to s("fetched_at"), "sources" to (o["sources"] ?: JsonValue.Arr(emptyList())),
            "registration" to reg, "certificates" to certs, "errors" to JsonValue.Arr(emptyList()),
        ),
    )
}
