package dev.loupe.kit.privacy

import dev.loupe.persistence.CorrectionKey
import dev.loupe.persistence.CorrectionRecord
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.SourceItem
import kotlinx.datetime.LocalDate

/**
 * Privacy check (epic #7 child 10): personal data, secrets and duplicate files in the items of every
 * enabled source. Mechanical only — no model — so it gives the same answer every time for the same
 * items.
 *
 * The rules are Loupe Station's (`PiiRules`, `SecretRules`, `Duplicates`, `NameHints`, ported from
 * the owner's repository with their rule ids, severities and redaction). What this file adds is
 * the phone's presentation: one [PrivacyFinding] per (item, rule), grouped by [PrivacyGroup], and
 * "mark safe" as an appended correction record.
 *
 * One deliberate difference from the station: the station raises personal-data and name findings
 * only for files in an *unsafe* place (Downloads, Desktop, a synced folder) and business data only
 * outside a business folder. A phone has no such places to tell apart, so every enabled source is
 * treated as the place to look. Secrets, severities, masking and duplicate grouping are unchanged.
 */

/** How findings are grouped on the Privacy screen, in this order. */
enum class PrivacyGroup(val id: String, val title: String, val blurb: String) {
    SECRETS("secrets", "API keys, tokens and private keys", "Credentials written down in plain text."),
    ID_DOCUMENTS("ids", "IDs and passports", "Identity documents and ID or passport numbers."),
    CARDS("cards", "Card numbers", "Payment card numbers that pass the Luhn check."),
    IBANS("ibans", "IBANs", "Bank account numbers with a valid IBAN checksum."),
    CONTACTS("contacts", "Phone numbers and emails", "Many distinct addresses or numbers in one file: a contact or customer list."),
    PAYROLL("payroll", "Payroll", "Salary and payroll column headers."),
    DUPLICATES("duplicates", "Duplicate files", "Exact copies of another file."),
}

/** One thing the check raised. Previews are masked; no matched value is ever held here. */
data class PrivacyFinding(
    /** Stable across runs: "mark safe" is keyed by it. */
    val key: String,
    val group: PrivacyGroup,
    /** The station's rule id: `passport_number`, `anthropic_key`, `id_document`, `duplicate`, … */
    val ruleId: String,
    /** The station's risk: `secret`, `personal_exposed`, `business_exposed` or `duplicate`. */
    val risk: String,
    /** The station's severity: 3 high, 2 medium, 1 low, 0 info. */
    val severity: Int,
    val title: String,
    /** Masked previews ("A… (passport number)", "sk-a… (Anthropic API key) · line 1"). */
    val previews: List<String>,
    /** The station's message for the finding, in plain words. */
    val message: String,
    /** The item to open; for a duplicate group, the suggested copy to keep. */
    val itemId: String,
    val itemName: String,
    val location: String,
    val sourceId: String,
    /** For duplicates: the whole group. */
    val duplicates: DuplicateGroup?,
    /** True when every item behind it is from the synthetic sample. */
    val sample: Boolean,
) {
    val severityTitle: String get() = PrivacyCheck.severityTitle(severity)
    val groupId: String get() = group.id
}

/** What the Privacy screen and Now show from one run. */
data class PrivacySummary(
    /** Findings not marked safe, most severe first. */
    val findings: List<PrivacyFinding>,
    /** How many the user has marked safe. */
    val markedSafe: Int,
    val itemsChecked: Int,
) {
    fun inGroup(group: PrivacyGroup): List<PrivacyFinding> = findings.filter { it.group == group }

    /** Groups that have findings, in [PrivacyGroup] order. */
    val groups: List<PrivacyGroup> get() = PrivacyGroup.entries.filter { g -> findings.any { it.group == g } }
}

object PrivacyCheck {
    /** Corrections-log judgment id and "criteria" for privacy verdicts (the rules have no wording). */
    const val JUDGMENT_ID = "privacy"
    const val CRITERIA = "privacy-v1"
    const val SAFE = "safe"

    /** Loupe Station's `SEVERITY` table, verbatim. */
    val SEVERITY: Map<String, Int> = mapOf("secret" to 3, "personal_exposed" to 3, "business_exposed" to 2, "duplicate" to 1, "large" to 1, "old" to 0)

    fun severityTitle(severity: Int): String = when (severity) {
        3 -> "High"
        2 -> "Medium"
        1 -> "Low"
        else -> "Info"
    }

    private fun groupFor(signal: String): PrivacyGroup = when (signal) {
        "egypt_national_id", "passport_number" -> PrivacyGroup.ID_DOCUMENTS
        "iban" -> PrivacyGroup.IBANS
        "card_number" -> PrivacyGroup.CARDS
        "contact_list" -> PrivacyGroup.CONTACTS
        else -> PrivacyGroup.PAYROLL
    }

    /** The station's `_signal_text`. */
    fun signalText(sg: PiiSignal): String {
        val n = if (sg.distinct != 0) sg.distinct else sg.count
        return when (sg.type) {
            "contact_list" -> "$n distinct emails/phone numbers (looks like a contact list)"
            "payroll_headers" -> "payroll column headers (${sg.previews.joinToString(", ").take(80)})"
            else -> "$n ${sg.label}${if (n == 1) "" else "s"}"
        }
    }

    /** One clean chip per signal: "Payment card ×1" (owner 2026-09-24: no nested previews). */
    fun chip(sg: PiiSignal): String = PiiRules.chip(sg.type, if (sg.distinct != 0) sg.distinct else sg.count)

    private fun title(sg: PiiSignal): String = when (sg.type) {
        "contact_list" -> "Looks like a contact list"
        "payroll_headers" -> "Payroll column headers"
        else -> chip(sg)
    }

    /** Text read by OCR (a photo, a scanned PDF): the card rule is stricter for it. */
    fun isOcr(item: SourceItem): Boolean = PrivacyEvidence.isOcr(item.kind == ItemKind.IMAGE, item.facts["text"])

    /** Every finding in [items], before the user's verdicts. */
    fun findings(items: List<SourceItem>, sampleSourceIds: Set<String>, today: LocalDate = PiiRules.systemToday()): List<PrivacyFinding> {
        val out = mutableListOf<PrivacyFinding>()
        for (item in items) {
            // Contact cards are the address book itself: a phone number there is the point, not a leak.
            if (item.kind == ItemKind.CONTACT) continue
            val sample = item.sourceId in sampleSourceIds
            fun finding(rule: String, group: PrivacyGroup, risk: String, title: String, previews: List<String>, message: String) =
                PrivacyFinding(
                    key = "privacy:$rule:${item.id}", group = group, ruleId = rule, risk = risk, severity = SEVERITY.getValue(risk),
                    title = title, previews = previews, message = message, itemId = item.id, itemName = item.fileName,
                    location = NameHints.displayPath(item), sourceId = item.sourceId, duplicates = null, sample = sample,
                )

            // ---- secrets (the station: always, wherever the file is)
            val env = item.messageIndex == null && SecretRules.isEnvName(item.fileName)
            val secrets = SecretRules.findSecrets(if (item.hasText) item.text else null, item.fileName, env)
            for ((type, list) in secrets.groupBy { it.type }) {
                val label = list.first().label
                out += finding(
                    type, PrivacyGroup.SECRETS, "secret", label.replaceFirstChar { it.uppercase() },
                    list.map { s -> s.preview + (s.line?.let { " · line $it" } ?: "") + (if (s.count > 1) " · ${s.count}×" else "") },
                    "Credentials in plain text: " + secrets.map { it.label }.distinct().sorted().joinToString(", "),
                )
            }

            // ---- personal and business data in the text
            val signals = if (item.hasText) PiiCollector(today, isOcr(item)).also { it.scan(item.text) }.signals() else emptyList()
            val personal = signals.filter { it.type in PiiRules.PERSONAL_SIGNALS }
            val business = signals.filter { it.type in PiiRules.BUSINESS_SIGNALS }
            if (personal.isNotEmpty()) {
                val message = "Personal data: " + personal.joinToString(", ") { chip(it) }
                for (sg in personal) {
                    out += finding(sg.type, groupFor(sg.type), "personal_exposed", title(sg), sg.previews, message)
                }
            } else if (item.messageIndex == null) {
                // ---- or an ID document by its name (the station's keyword hints)
                val hits = NameHints.idDocumentWords(item.fileName, NameHints.folderOf(item))
                if (hits.isNotEmpty()) {
                    val why = "name mentions " + hits.sorted().take(3).joinToString(", ")
                    out += finding("id_document", PrivacyGroup.ID_DOCUMENTS, "personal_exposed", "ID document", listOf(why), "ID document ($why)")
                }
            }
            if (business.isNotEmpty()) {
                val message = "Business data: " + business.joinToString(", ") { chip(it) }
                for (sg in business) {
                    out += finding(sg.type, groupFor(sg.type), "business_exposed", title(sg), sg.previews, message)
                }
            }
        }

        // ---- duplicates
        val byId = items.associateBy { it.id }
        for (g in Duplicates.groups(items)) {
            val keep = byId.getValue(g.keepItemId)
            val sample = g.members.all { byId[it.itemId]?.sourceId in sampleSourceIds }
            out += PrivacyFinding(
                key = "privacy:duplicate:${g.groupId}", group = PrivacyGroup.DUPLICATES, ruleId = "duplicate", risk = "duplicate",
                severity = SEVERITY.getValue("duplicate"), title = "${g.count} identical copies",
                previews = g.members.map { (if (it.keep) "keep (suggested) · " else "") + it.location },
                message = "${g.count} copies of ${humanSize(g.size)} · ${humanSize(g.wastedBytes)} could be freed",
                itemId = keep.id, itemName = keep.fileName, location = NameHints.displayPath(keep), sourceId = keep.sourceId,
                duplicates = g, sample = sample,
            )
        }
        return out.sortedWith(compareBy<PrivacyFinding>({ -it.severity }, { it.group.ordinal }, { it.location }, { it.ruleId }))
    }

    /** The findings the user has not marked safe, and how many they have. */
    fun summarise(
        items: List<SourceItem>,
        sampleSourceIds: Set<String>,
        corrections: Map<CorrectionKey, String>,
        today: LocalDate = PiiRules.systemToday(),
    ): PrivacySummary {
        val all = findings(items, sampleSourceIds, today)
        val shown = all.filter { corrections[CorrectionKey(JUDGMENT_ID, CRITERIA, it.key)] != SAFE }
        return PrivacySummary(shown, all.size - shown.size, items.count { it.kind != ItemKind.CONTACT })
    }

    /** For Swift, which does not see Kotlin default arguments: today's date from the system. */
    fun summariseToday(items: List<SourceItem>, sampleSourceIds: Set<String>, corrections: Map<CorrectionKey, String>): PrivacySummary =
        summarise(items, sampleSourceIds, corrections)

    /**
     * [summariseToday] under Model settings: with `features.scan.read_content` off only names,
     * folders and exact duplicates (by the hash taken at scan time) are checked — no text is read,
     * so the personal-data and secret rules find nothing in file contents. On is what always ran.
     */
    fun summariseWith(items: List<SourceItem>, sampleSourceIds: Set<String>, corrections: Map<CorrectionKey, String>, readContent: Boolean): PrivacySummary =
        summarise(if (readContent) items else withoutContent(items), sampleSourceIds, corrections)

    /** The items with their text withheld (`read_content` off). */
    fun withoutContent(items: List<SourceItem>): List<SourceItem> = items.map { it.copy(text = "", hasText = false, textTruncated = false) }

    /** "Mark safe": remembered as an appended correction, never rewritten. */
    fun markSafe(finding: PrivacyFinding, at: String): CorrectionRecord =
        CorrectionRecord(JUDGMENT_ID, CRITERIA, finding.key, SAFE, at, false)

    /** Undo: an appended retraction. */
    fun retraction(finding: PrivacyFinding, at: String): CorrectionRecord =
        CorrectionRecord(JUDGMENT_ID, CRITERIA, finding.key, null, at, false)

    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${oneDecimal(bytes / 1024.0)} KB"
        bytes < 1024L * 1024 * 1024 -> "${oneDecimal(bytes / (1024.0 * 1024))} MB"
        else -> "${oneDecimal(bytes / (1024.0 * 1024 * 1024))} GB"
    }

    private fun oneDecimal(v: Double): String {
        val tenths = kotlin.math.round(v * 10).toLong()
        return "${tenths / 10}.${tenths % 10}"
    }
}

/*
 * Keyword hints from a file's name and folders.
 *
 * PROVENANCE: `keyword_hints`, `normalise` and the `id_document` row of `KEYWORDS` from the owner's
 * Loupe Station repository (`laya_studio/scan/rules.py`, commit
 * ea7697a4f78e9a648ba49fc8bf9d13226c26cb0e), verbatim. Only the ID-document row is used here; the
 * station's other rows (bank, contract, tax, …) name ordinary documents that are not a privacy
 * finding on their own. Unicode NFC normalisation is not applied (Kotlin common has none).
 */
object NameHints {
    /** Latin keywords match whole words after normalisation; Arabic ones match as substrings. */
    val ID_DOCUMENT: List<String> = listOf(
        "passport", "national id", "id card", "idcard", "identity card", "driving licence",
        "driving license", "drivers license", "residence permit", "residency", "birth certificate",
        "visa", "جواز", "بطاقة", "هوية", "رقم قومي", "شهادة ميلاد", "إقامة", "اقامة", "رخصة",
    )
    private val ARABIC = Regex("[؀-ۿ]")
    // Python's `[^\w]+|_` (Unicode): anything but a letter, digit or mark, and the underscore.
    private val NON_WORD = Regex("[^\\p{L}\\p{Nd}\\p{M}]+|_")
    private val CAMEL = Regex("(?<=[a-z])(?=[A-Z])")
    private val SPACES = Regex("\\s+")

    fun normalise(text: String): String {
        val camel = CAMEL.replace(text, " ")
        val words = NON_WORD.replace(camel.lowercase(), " ").split(SPACES).filter { it.isNotEmpty() }
        return " " + words.joinToString(" ") + " "
    }

    private fun hits(norm: String, words: List<String>): List<String> = words.filter { w ->
        if (ARABIC.containsMatchIn(w)) w in norm else " $w " in norm
    }

    /** The ID-document words in a file's name (without extension) and its folders. */
    fun idDocumentWords(name: String, folderRel: String): List<String> {
        val stem = if (name.drop(1).contains('.')) name.substringBeforeLast('.') else name
        return hits(normalise("$stem $folderRel"), ID_DOCUMENT)
    }

    /** Where an item is, without the app's container path: `documents/identity/passport.txt`. */
    fun displayPath(item: SourceItem): String {
        val base = if (':' in item.id && !item.id.startsWith("/")) item.id.substringAfter(':').substringBefore('#') else item.path
        return if (item.messageIndex == null) base else "$base (message ${item.messageIndex})"
    }

    /** An item's folders relative to its source (`documents/identity` for the sample passport). */
    fun folderOf(item: SourceItem): String {
        val rel = if (':' in item.id && !item.id.startsWith("/")) item.id.substringAfter(':') else item.path
        return rel.substringBeforeLast('/', "")
    }
}
