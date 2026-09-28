package dev.loupe.kit.results

import dev.loupe.kit.mail.MailRow
import dev.loupe.kit.mail.MailSummary
import dev.loupe.kit.mail.PhishReason
import dev.loupe.kit.mail.PhishVerdict
import dev.loupe.kit.mail.ProviderCategory
import dev.loupe.kit.mail.WebLinkCheck
import dev.loupe.kit.privacy.DuplicateGroup
import dev.loupe.kit.privacy.DuplicateMember
import dev.loupe.kit.privacy.PrivacyFinding
import dev.loupe.kit.privacy.PrivacyGroup
import dev.loupe.kit.privacy.PrivacySummary
import dev.loupe.kit.site.NotCounted
import dev.loupe.kit.site.SiteCheckResult
import dev.loupe.kit.site.SiteContextInfo
import dev.loupe.kit.site.SiteFact
import dev.loupe.kit.site.SiteReason
import dev.loupe.kit.site.SiteVerdict
import dev.loupe.kit.watchers.CensusRow
import dev.loupe.kit.watchers.ExpiryRow
import dev.loupe.kit.watchers.FindingVerdict
import dev.loupe.kit.watchers.SubscriptionCensus
import dev.loupe.kit.watchers.WatcherFinding
import dev.loupe.kit.watchers.WatcherKind
import dev.loupe.kit.watchers.WatcherSummary
import dev.loupe.persistence.JsonText
import dev.loupe.persistence.JsonValue

/**
 * The latest results of the three checks, as files (owner decision 2026-09-28: opening the app only loads and shows
 * the latest saved results; the checks run in the nightly run, the first check after onboarding and on request).
 *
 * One JSON document per check, lossless: every field of [WatcherSummary], [PrivacySummary] and [MailSummary], so a
 * summary read back equals the one written (the round-trip tests compare them with `==`). Written by the app after
 * each run and after each answer the user gives on a result; read at launch. A file this build cannot read (a
 * newer version, a torn write) decodes to null: the app then shows "not checked yet" rather than guessing.
 * Nothing here leaves the phone.
 */
object ResultsCodec {
    const val VERSION: Int = 1

    // ---------------------------------------------------------------------------------------------- watchers

    fun encodeWatchers(s: WatcherSummary): String = JsonText.compact(
        JsonValue.obj(
            "version" to JsonValue.num(VERSION),
            "kind" to JsonValue.Str("watchers"),
            "findings" to JsonValue.Arr(s.findings.map(::finding)),
            "setAside" to JsonValue.num(s.setAside),
            "census" to census(s.census),
            "modelRan" to JsonValue.Bool(s.modelRan),
            "itemsChecked" to JsonValue.num(s.itemsChecked),
            "emailsChecked" to JsonValue.num(s.emailsChecked),
            "linksChecked" to JsonValue.num(s.linksChecked),
            "todayIso" to JsonValue.Str(s.todayIso),
            "ruleName" to JsonValue.Str(s.ruleName),
            "expiries" to JsonValue.Arr(s.expiries.map(::expiry)),
        ),
    )

    /** The summary [encodeWatchers] wrote, or null when [text] is not one this build can read. */
    fun decodeWatchers(text: String): WatcherSummary? = read(text, "watchers") { o ->
        WatcherSummary(
            findings = o.arr("findings").map { finding(it.asObj) },
            setAside = o.int("setAside"),
            census = census(o.obj("census")),
            modelRan = o.bool("modelRan"),
            itemsChecked = o.int("itemsChecked"),
            emailsChecked = o.int("emailsChecked"),
            linksChecked = o.int("linksChecked"),
            todayIso = o.str("todayIso"),
            ruleName = o.str("ruleName"),
            expiries = o.arr("expiries").map { expiry(it.asObj) },
        )
    }

    private fun finding(f: WatcherFinding): JsonValue = JsonValue.obj(
        "key" to JsonValue.Str(f.key),
        "watcher" to JsonValue.Str(f.watcher.name),
        "title" to JsonValue.Str(f.title),
        "evidence" to JsonValue.strings(f.evidence),
        "why" to JsonValue.Str(f.why),
        "itemId" to JsonValue.Str(f.itemId),
        "itemName" to JsonValue.Str(f.itemName),
        "otherItemId" to JsonValue.str(f.otherItemId),
        "sample" to JsonValue.Bool(f.sample),
        "verdict" to JsonValue.str(f.verdict?.name),
    )

    private fun finding(o: JsonValue.Obj) = WatcherFinding(
        key = o.str("key"), watcher = WatcherKind.valueOf(o.str("watcher")), title = o.str("title"),
        evidence = o.strings("evidence"), why = o.str("why"), itemId = o.str("itemId"), itemName = o.str("itemName"),
        otherItemId = o.opt("otherItemId"), sample = o.bool("sample"), verdict = o.opt("verdict")?.let(FindingVerdict::valueOf),
    )

    private fun census(c: SubscriptionCensus): JsonValue = JsonValue.obj(
        "rows" to JsonValue.Arr(c.rows.map(::censusRow)),
        "monthlyTotalMinor" to long(c.monthlyTotalMinor),
        "chargesFound" to JsonValue.num(c.chargesFound),
        "sample" to JsonValue.Bool(c.sample),
        "setAside" to JsonValue.num(c.setAside),
    )

    private fun census(o: JsonValue.Obj) = SubscriptionCensus(
        rows = o.arr("rows").map { censusRow(it.asObj) }, monthlyTotalMinor = o.long("monthlyTotalMinor"),
        chargesFound = o.int("chargesFound"), sample = o.bool("sample"), setAside = o.int("setAside"),
    )

    private fun censusRow(r: CensusRow): JsonValue = JsonValue.obj(
        "merchant" to JsonValue.Str(r.merchant),
        "cadence" to JsonValue.Str(r.cadence),
        "occurrences" to JsonValue.num(r.occurrences),
        "typicalMinor" to long(r.typicalMinor),
        "lastChargedIso" to JsonValue.Str(r.lastChargedIso),
        "daysSinceLastCharge" to long(r.daysSinceLastCharge),
        "monthlyMinor" to (r.monthlyMinor?.let(::long) ?: JsonValue.Null),
        "sample" to JsonValue.Bool(r.sample),
        "itemIds" to JsonValue.strings(r.itemIds),
        "nextExpectedIso" to JsonValue.str(r.nextExpectedIso),
        "verdict" to JsonValue.str(r.verdict?.name),
        "currency" to JsonValue.Str(r.currency),
        "lines" to JsonValue.strings(r.lines),
    )

    private fun censusRow(o: JsonValue.Obj) = CensusRow(
        merchant = o.str("merchant"), cadence = o.str("cadence"), occurrences = o.int("occurrences"),
        typicalMinor = o.long("typicalMinor"), lastChargedIso = o.str("lastChargedIso"),
        daysSinceLastCharge = o.long("daysSinceLastCharge"), monthlyMinor = o.optLong("monthlyMinor"),
        sample = o.bool("sample"), itemIds = o.strings("itemIds"), nextExpectedIso = o.opt("nextExpectedIso"),
        verdict = o.opt("verdict")?.let(FindingVerdict::valueOf),
        // Absent in results saved before the tracking engine: read as "no currency named" and no lines.
        currency = o.opt("currency") ?: "",
        lines = if (o["lines"] == null) emptyList() else o.strings("lines"),
    )

    private fun expiry(e: ExpiryRow): JsonValue = JsonValue.obj(
        "itemId" to JsonValue.Str(e.itemId),
        "itemName" to JsonValue.Str(e.itemName),
        "expiryIso" to JsonValue.Str(e.expiryIso),
        "daysRemaining" to long(e.daysRemaining),
        "ambiguous" to JsonValue.Bool(e.ambiguous),
        "breachesRule" to JsonValue.Bool(e.breachesRule),
        "documentType" to JsonValue.str(e.documentType),
        "findingKey" to JsonValue.str(e.findingKey),
        "line" to JsonValue.str(e.line),
        "sample" to JsonValue.Bool(e.sample),
        "documentKind" to JsonValue.str(e.documentKind),
    )

    private fun expiry(o: JsonValue.Obj) = ExpiryRow(
        itemId = o.str("itemId"), itemName = o.str("itemName"), expiryIso = o.str("expiryIso"),
        daysRemaining = o.long("daysRemaining"), ambiguous = o.bool("ambiguous"), breachesRule = o.bool("breachesRule"),
        documentType = o.opt("documentType"), findingKey = o.opt("findingKey"), line = o.opt("line"), sample = o.bool("sample"),
        documentKind = o.opt("documentKind"),
    )

    // ---------------------------------------------------------------------------------------------- privacy

    fun encodePrivacy(s: PrivacySummary): String = JsonText.compact(
        JsonValue.obj(
            "version" to JsonValue.num(VERSION),
            "kind" to JsonValue.Str("privacy"),
            "findings" to JsonValue.Arr(s.findings.map(::privacyFinding)),
            "markedSafe" to JsonValue.num(s.markedSafe),
            "itemsChecked" to JsonValue.num(s.itemsChecked),
        ),
    )

    fun decodePrivacy(text: String): PrivacySummary? = read(text, "privacy") { o ->
        PrivacySummary(
            findings = o.arr("findings").map { privacyFinding(it.asObj) },
            markedSafe = o.int("markedSafe"),
            itemsChecked = o.int("itemsChecked"),
        )
    }

    private fun privacyFinding(f: PrivacyFinding): JsonValue = JsonValue.obj(
        "key" to JsonValue.Str(f.key),
        "group" to JsonValue.Str(f.group.name),
        "ruleId" to JsonValue.Str(f.ruleId),
        "risk" to JsonValue.Str(f.risk),
        "severity" to JsonValue.num(f.severity),
        "title" to JsonValue.Str(f.title),
        "previews" to JsonValue.strings(f.previews),
        "message" to JsonValue.Str(f.message),
        "itemId" to JsonValue.Str(f.itemId),
        "itemName" to JsonValue.Str(f.itemName),
        "location" to JsonValue.Str(f.location),
        "sourceId" to JsonValue.Str(f.sourceId),
        "duplicates" to (f.duplicates?.let(::duplicateGroup) ?: JsonValue.Null),
        "sample" to JsonValue.Bool(f.sample),
    )

    private fun privacyFinding(o: JsonValue.Obj) = PrivacyFinding(
        key = o.str("key"), group = PrivacyGroup.valueOf(o.str("group")), ruleId = o.str("ruleId"), risk = o.str("risk"),
        severity = o.int("severity"), title = o.str("title"), previews = o.strings("previews"), message = o.str("message"),
        itemId = o.str("itemId"), itemName = o.str("itemName"), location = o.str("location"), sourceId = o.str("sourceId"),
        duplicates = o.optObj("duplicates")?.let(::duplicateGroup), sample = o.bool("sample"),
    )

    private fun duplicateGroup(g: DuplicateGroup): JsonValue = JsonValue.obj(
        "groupId" to JsonValue.Str(g.groupId),
        "size" to long(g.size),
        "count" to JsonValue.num(g.count),
        "wastedBytes" to long(g.wastedBytes),
        "keepItemId" to JsonValue.Str(g.keepItemId),
        "members" to JsonValue.Arr(
            g.members.map { m ->
                JsonValue.obj(
                    "itemId" to JsonValue.Str(m.itemId), "name" to JsonValue.Str(m.name),
                    "location" to JsonValue.Str(m.location), "suggest" to JsonValue.Str(m.suggest),
                )
            },
        ),
    )

    private fun duplicateGroup(o: JsonValue.Obj) = DuplicateGroup(
        groupId = o.str("groupId"), size = o.long("size"), count = o.int("count"), wastedBytes = o.long("wastedBytes"),
        keepItemId = o.str("keepItemId"),
        members = o.arr("members").map { v ->
            val m = v.asObj
            DuplicateMember(itemId = m.str("itemId"), name = m.str("name"), location = m.str("location"), suggest = m.str("suggest"))
        },
    )

    // ---------------------------------------------------------------------------------------------- mail

    fun encodeMail(s: MailSummary): String = JsonText.compact(
        JsonValue.obj(
            "version" to JsonValue.num(VERSION),
            "kind" to JsonValue.Str("mail"),
            "rows" to JsonValue.Arr(s.rows.map(::mailRow)),
            "webLinks" to JsonValue.Arr(
                s.webLinks.map {
                    JsonValue.obj("itemId" to JsonValue.Str(it.itemId), "itemName" to JsonValue.Str(it.itemName), "check" to siteCheck(it.check))
                },
            ),
        ),
    )

    fun decodeMail(text: String): MailSummary? = read(text, "mail") { o ->
        MailSummary(
            rows = o.arr("rows").map { mailRow(it.asObj) },
            webLinks = o.arr("webLinks").map { v ->
                val w = v.asObj
                WebLinkCheck(itemId = w.str("itemId"), itemName = w.str("itemName"), check = siteCheck(w.obj("check")))
            },
        )
    }

    private fun mailRow(r: MailRow): JsonValue = JsonValue.obj(
        "itemId" to JsonValue.Str(r.itemId),
        "sender" to JsonValue.Str(r.sender),
        "subject" to JsonValue.Str(r.subject),
        "dateIso" to JsonValue.str(r.dateIso),
        "categoryKey" to JsonValue.Str(r.categoryKey),
        "categoryTitle" to JsonValue.Str(r.categoryTitle),
        "categoryWeak" to JsonValue.Bool(r.categoryWeak),
        "labels" to JsonValue.strings(r.labels),
        "weakLabels" to JsonValue.strings(r.weakLabels),
        "needsReply" to JsonValue.Bool(r.needsReply),
        "urgencyLevel" to JsonValue.num(r.urgencyLevel),
        "urgencyTitle" to JsonValue.Str(r.urgencyTitle),
        "urgentCue" to JsonValue.str(r.urgentCue),
        "phishingCue" to JsonValue.str(r.phishingCue),
        "verdict" to phishVerdict(r.verdict),
        "phishing" to JsonValue.Bool(r.phishing),
        "personVerdict" to JsonValue.str(r.personVerdict),
        "spam" to JsonValue.Bool(r.spam),
        "transactional" to JsonValue.Bool(r.transactional),
        "providerCategory" to (
            r.providerCategory?.let {
                JsonValue.obj("key" to JsonValue.Str(it.key), "provider" to JsonValue.Str(it.provider), "name" to JsonValue.Str(it.name))
            } ?: JsonValue.Null
            ),
        "linkChecks" to JsonValue.Arr(r.linkChecks.map(::siteCheck)),
    )

    private fun mailRow(o: JsonValue.Obj) = MailRow(
        itemId = o.str("itemId"), sender = o.str("sender"), subject = o.str("subject"), dateIso = o.opt("dateIso"),
        categoryKey = o.str("categoryKey"), categoryTitle = o.str("categoryTitle"), categoryWeak = o.bool("categoryWeak"),
        labels = o.strings("labels"), weakLabels = o.strings("weakLabels"), needsReply = o.bool("needsReply"),
        urgencyLevel = o.int("urgencyLevel"), urgencyTitle = o.str("urgencyTitle"), urgentCue = o.opt("urgentCue"),
        phishingCue = o.opt("phishingCue"), verdict = phishVerdict(o.obj("verdict")), phishing = o.bool("phishing"),
        personVerdict = o.opt("personVerdict"), spam = o.bool("spam"), transactional = o.bool("transactional"),
        providerCategory = o.optObj("providerCategory")?.let { ProviderCategory(it.str("key"), it.str("provider"), it.str("name")) },
        linkChecks = o.arr("linkChecks").map { siteCheck(it.asObj) },
    )

    private fun phishVerdict(v: PhishVerdict): JsonValue = JsonValue.obj(
        "flag" to JsonValue.Bool(v.flag),
        "score" to JsonValue.num(v.score),
        "reasons" to JsonValue.Arr(
            v.reasons.map {
                JsonValue.obj(
                    "code" to JsonValue.Str(it.code), "text" to JsonValue.Str(it.text), "weight" to JsonValue.num(it.weight),
                    "params" to params(it.params), "source" to JsonValue.Str(it.source),
                )
            },
        ),
        "known" to JsonValue.Bool(v.known),
        "trusted" to JsonValue.Bool(v.trusted),
        "domain" to JsonValue.Str(v.domain),
        "gates" to JsonValue.strings(v.gates),
        "formula" to JsonValue.Str(v.formula),
    )

    private fun phishVerdict(o: JsonValue.Obj) = PhishVerdict(
        flag = o.bool("flag"), score = o.int("score"),
        reasons = o.arr("reasons").map { v ->
            val r = v.asObj
            PhishReason(code = r.str("code"), text = r.str("text"), weight = r.int("weight"), params = params(r["params"]), source = r.str("source"))
        },
        known = o.bool("known"), trusted = o.bool("trusted"), domain = o.str("domain"), gates = o.strings("gates"),
        formula = o.str("formula"),
    )

    private fun siteCheck(c: SiteCheckResult): JsonValue = JsonValue.obj(
        "url" to JsonValue.Str(c.url),
        "verdict" to siteVerdict(c.verdict),
    )

    private fun siteCheck(o: JsonValue.Obj) = SiteCheckResult(url = o.str("url"), verdict = siteVerdict(o.obj("verdict")))

    private fun siteVerdict(v: SiteVerdict): JsonValue = JsonValue.obj(
        "score" to JsonValue.num(v.score),
        "level" to JsonValue.Str(v.level),
        "reasons" to JsonValue.Arr(
            v.reasons.map {
                JsonValue.obj(
                    "code" to JsonValue.Str(it.code), "text" to JsonValue.Str(it.text), "weight" to JsonValue.num(it.weight),
                    "source" to JsonValue.Str(it.source), "params" to params(it.params),
                )
            },
        ),
        "gates" to JsonValue.strings(v.gates),
        "facts" to JsonValue.Arr(
            v.facts.map {
                JsonValue.obj(
                    "code" to JsonValue.Str(it.code), "tone" to JsonValue.Str(it.tone), "text" to JsonValue.Str(it.text),
                    "params" to params(it.params),
                )
            },
        ),
        "notCounted" to JsonValue.Arr(
            v.notCounted.map {
                JsonValue.obj(
                    "code" to JsonValue.Str(it.code), "text" to JsonValue.Str(it.text), "wouldAdd" to JsonValue.num(it.wouldAdd),
                    "why" to JsonValue.Str(it.why), "whyText" to JsonValue.Str(it.whyText),
                )
            },
        ),
        "context" to JsonValue.obj(
            "tier" to JsonValue.Str(v.context.tier),
            "paymentExpected" to JsonValue.Bool(v.context.paymentExpected),
            "processor" to JsonValue.str(v.context.processor),
        ),
        "formula" to JsonValue.Str(v.formula),
    )

    private fun siteVerdict(o: JsonValue.Obj): SiteVerdict {
        val ctx = o.obj("context")
        return SiteVerdict(
            score = o.int("score"), level = o.str("level"),
            reasons = o.arr("reasons").map { v ->
                val r = v.asObj
                SiteReason(code = r.str("code"), text = r.str("text"), weight = r.int("weight"), source = r.str("source"), params = params(r["params"]))
            },
            gates = o.strings("gates"),
            facts = o.arr("facts").map { v ->
                val f = v.asObj
                SiteFact(code = f.str("code"), tone = f.str("tone"), text = f.str("text"), params = params(f["params"]))
            },
            notCounted = o.arr("notCounted").map { v ->
                val n = v.asObj
                NotCounted(code = n.str("code"), text = n.str("text"), wouldAdd = n.int("wouldAdd"), why = n.str("why"), whyText = n.str("whyText"))
            },
            context = SiteContextInfo(tier = ctx.str("tier"), paymentExpected = ctx.bool("paymentExpected"), processor = ctx.opt("processor")),
            formula = o.str("formula"),
        )
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private fun <T> read(text: String, kind: String, decode: (JsonValue.Obj) -> T): T? = try {
        val o = JsonValue.parse(text).asObj
        if (o["version"]?.asInt != VERSION || o["kind"]?.asString != kind) null else decode(o)
    } catch (_: Exception) {
        null
    }

    /** A Long as written (a JSON number keeps every digit; `Double` would round past 2^53). */
    private fun long(v: Long): JsonValue = JsonValue.Num(v.toString())

    private fun params(m: Map<String, String>): JsonValue = JsonValue.Obj(LinkedHashMap(m.mapValues { JsonValue.Str(it.value) as JsonValue }))

    private fun params(v: JsonValue?): Map<String, String> =
        if (v == null || v.isNull) emptyMap() else v.asObj.fields.mapValues { it.value.asString }

    private fun JsonValue.Obj.need(k: String): JsonValue = this[k] ?: throw IllegalArgumentException("missing '$k'")

    private fun JsonValue.Obj.str(k: String): String = need(k).asString

    private fun JsonValue.Obj.opt(k: String): String? = this[k]?.takeUnless { it.isNull }?.asString

    private fun JsonValue.Obj.int(k: String): Int = need(k).asInt

    private fun JsonValue.Obj.long(k: String): Long = need(k).asString.toLong()

    private fun JsonValue.Obj.optLong(k: String): Long? = this[k]?.takeUnless { it.isNull }?.asString?.toLong()

    private fun JsonValue.Obj.bool(k: String): Boolean = need(k).asBoolean

    private fun JsonValue.Obj.obj(k: String): JsonValue.Obj = need(k).asObj

    private fun JsonValue.Obj.optObj(k: String): JsonValue.Obj? = this[k]?.takeUnless { it.isNull }?.asObj

    private fun JsonValue.Obj.arr(k: String): List<JsonValue> = need(k).asArr.items

    private fun JsonValue.Obj.strings(k: String): List<String> = arr(k).map { it.asString }
}
