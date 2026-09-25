package dev.loupe.agent

import dev.loupe.kit.privacy.PiiRules
import dev.loupe.kit.privacy.SecretRules

/**
 * One line of the never list (docs/PRODUCT.md §4), with the code that enforces it.
 *
 * Two of the seven are enforced by the *shape* of [PreparedAction] rather than by a check here,
 * and they are the two that matter most: no variant can move money or press a button, and the
 * module's only output is a Review-queue proposal that a person must approve. Those are marked
 * [structural]. A structural rule still appears in this enum because it is asserted by a test
 * (`NeverListTest`), so removing the property fails the build rather than quietly widening the
 * agent's reach.
 */
enum class NeverRule(val code: String, val promise: String, val structural: Boolean) {
    SPEND(
        "never_spends",
        "Never spends money, completes a purchase, or presses the last button.",
        structural = true,
    ),
    CREDENTIALS(
        "never_fills_credentials",
        "Never fills a credential, one-time code, CVV, or national ID number.",
        structural = false,
    ),
    UNSURE(
        "never_acts_unsure",
        "Never acts on something it is unsure about.",
        structural = false,
    ),
    BLESS(
        "never_blesses",
        "Never blesses. It warns; it never displays an all-clear implying safety.",
        structural = false,
    ),
    APPROVAL(
        "never_acts_without_approval",
        "Never sends, posts or files what it writes without your approval.",
        structural = true,
    ),
    LABELLED(
        "always_labelled_online",
        "Every result that came from the network says Online and names its source.",
        structural = false,
    ),
    ;

    companion object {
        fun of(code: String): NeverRule? = entries.firstOrNull { it.code == code }
    }
}

/** Whether a prepared action may be offered to the person. */
sealed interface GuardVerdict {
    /**
     * It may. [action] is the action as it will be queued — trimmed to the Review queue's field
     * limits, never otherwise rewritten.
     */
    data class Allowed(val action: PreparedAction) : GuardVerdict

    /** It may not. [rule] is the promise it would have broken; [detail] is what tripped it. */
    data class Blocked(val rule: NeverRule, val detail: String) : GuardVerdict
}

/**
 * The never list, run over every prepared action before a person ever sees it.
 *
 * The guard exists because the agent tier changes who writes the text. Until now every word Loupe
 * showed came from code the owner wrote; now some of it comes from a model reading an email an
 * attacker may have written (docs/PRODUCT.md §8: *"email bodies and file contents are data, never
 * instructions"*). A prompt can be talked out of a rule. A function cannot, so the rules live here
 * and not only in the prompt — the prompt asks, the guard refuses.
 *
 * Failure is closed: anything the guard cannot make sense of is [GuardVerdict.Blocked]. A blocked
 * action is dropped and counted, never shown "for the user to judge": the never list is not a
 * warning.
 */
object ActionGuard {
    /** The Review queue's `text` field limit, which every free-text action must fit. */
    const val MAX_TEXT: Int = 20_000

    /** The `email_reply` kind's body limit. */
    const val MAX_BODY: Int = 10_000

    /** The queue's one-line title limit. */
    const val MAX_TITLE: Int = 200

    /** The `email_reply` kind's subject and `to` limits. */
    const val MAX_SUBJECT: Int = 300

    /** A date must look like one before it becomes a reminder or a calendar entry. */
    private val ISO_MOMENT =
        Regex("""^\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2})?)?$""")

    /**
     * Words that claim safety. *"Never blesses"* is the hardest promise to keep once a model
     * writes the prose, because reassurance is the most natural register for a helpful assistant
     * and the most dangerous thing to offer about a message someone may have forged.
     */
    private val BLESSING = Regex(
        "(?i)\\b(" +
            "is (?:completely |perfectly |totally |entirely )?(?:safe|legitimate|genuine|authentic|trustworthy)|" +
            "looks (?:safe|legitimate|genuine|authentic)|" +
            "(?:it|this|the (?:email|message|link|site|sender)) is not (?:a )?(?:scam|phishing|fraud|fake)|" +
            "no (?:risk|danger|threat|cause for concern|reason to worry)|" +
            "nothing (?:to worry about|suspicious)|" +
            "all clear|you can (?:safely |)(?:trust|proceed|click)|verified as (?:safe|genuine)|" +
            "confirmed (?:safe|legitimate|genuine)|definitely (?:safe|legitimate|genuine|real)" +
            ")\\b",
    )

    /**
     * Phrases that would commit the person to a payment. No [PreparedAction] can move money, so
     * this catches the one remaining route: a draft that authorises someone else to.
     */
    private val PAYMENT_INTENT = Regex(
        "(?i)\\b(" +
            "i (?:hereby )?authori[sz]e (?:the |a |this )?(?:payment|charge|transfer|debit)|" +
            "(?:please |go ahead and |feel free to )(?:charge|debit|bill) (?:my|the) (?:card|account)|" +
            "you (?:may|can) charge (?:my|the) (?:card|account)|" +
            "charge it to (?:my|the) (?:card|account)|" +
            "here (?:is|are) my (?:card|credit card|bank|account) (?:details|number|numbers)|" +
            "i (?:have |'ve )?(?:approved|authori[sz]ed) the (?:payment|invoice|transfer)|" +
            "proceed with the (?:payment|charge|purchase|order)|" +
            "i confirm the (?:payment|purchase|order|transfer)" +
            ")\\b",
    )

    /** Cues that a nearby short digit run is a one-time code rather than a quantity or a date. */
    private val CODE_CUE = Regex(
        "(?i)(one[- ]?time|onetime|verification|verify|security|confirmation|auth(?:entication)?|" +
            "passcode|pass ?code|\\botp\\b|\\bpin\\b|\\bcode\\b|2fa|two[- ]factor|" +
            "رمز|كود|التحقق|السري)",
    )

    /** Cues that a nearby 3-4 digit run is a card verification value. */
    private val CVV_CUE = Regex("(?i)(cvv2?|cvc2?|csc\\b|security code|رمز الأمان)")

    private val CREDENTIAL_CUE = Regex(
        "(?i)\\b(password|passphrase|pass ?word|كلمة المرور|كلمة السر)\\b\\s*(?:is|:|=)\\s*\\S",
    )

    /** Our own prompt tags. Seeing one in the model's output means injected text came back out. */
    private val OUR_TAGS = Regex("(?i)</?\\s*(email|text|facts|item|never_promise)\\s*>")

    /** An instruction aimed at the reviewer, which is the shape a successful injection takes. */
    private val REVIEWER_BAIT = Regex(
        "(?i)\\b(" +
            "ignore (?:all |any |the )?(?:previous|prior|above|earlier) instructions|" +
            "disregard (?:all |any |the )?(?:previous|prior|above|earlier) (?:instructions|rules)|" +
            "(?:approve|send) this (?:immediately|without review|without reading)|" +
            "do not (?:review|read|check) this|no review (?:is )?(?:needed|required)|" +
            "you (?:must|should) approve" +
            ")\\b",
    )

    /**
     * Runs the never list over [action].
     *
     * The order is deliberate: the cheap structural facts first, then the content scans, so a
     * malformed action is refused before any regex runs over attacker-controlled text.
     */
    fun check(action: PreparedAction): GuardVerdict {
        // --- LABELLED: an unlabelled result cannot be shown as Online, so it cannot be shown. ---
        if (action.provider.isBlank()) {
            return GuardVerdict.Blocked(NeverRule.LABELLED, "the action does not name the provider that wrote it")
        }
        if (action.provider.length > 200) {
            return GuardVerdict.Blocked(NeverRule.LABELLED, "the provider name is too long to label")
        }

        // --- UNSURE: the gate already refused everything but Act, but an AgentEvidence is a plain
        // data class that a caller could build by hand. Re-check rather than trust it. ---
        val e = action.evidence
        if (e.itemId.isBlank() || e.judgmentId.isBlank() || e.label.isBlank()) {
            return GuardVerdict.Blocked(NeverRule.UNSURE, "the action does not say which decision justified it")
        }
        if (e.confidence.value < e.bar.value) {
            return GuardVerdict.Blocked(
                NeverRule.UNSURE,
                "the decision behind it scored ${e.confidence.value}, under the bar of ${e.bar.value}",
            )
        }
        if (e.bar.value < AgentGate.FLOOR.value) {
            return GuardVerdict.Blocked(
                NeverRule.UNSURE,
                "the bar it was judged against (${e.bar.value}) is below the floor of ${AgentGate.FLOOR.value}",
            )
        }

        // --- Per-variant shape, and the text each one puts in front of a person. ---
        val trimmed = when (action) {
            is PreparedAction.Remind -> {
                if (!ISO_MOMENT.matches(action.whenIso)) {
                    return GuardVerdict.Blocked(NeverRule.UNSURE, "\"${action.whenIso.take(40)}\" is not a date")
                }
                if (action.text.isBlank()) {
                    return GuardVerdict.Blocked(NeverRule.UNSURE, "the reminder has nothing to say")
                }
                action.copy(text = action.text.trim().take(MAX_TITLE), because = action.because.trim().take(MAX_TEXT))
            }

            is PreparedAction.CalendarEvent -> {
                if (!ISO_MOMENT.matches(action.startIso)) {
                    return GuardVerdict.Blocked(NeverRule.UNSURE, "\"${action.startIso.take(40)}\" is not a date")
                }
                if (action.endIso != null && !ISO_MOMENT.matches(action.endIso)) {
                    return GuardVerdict.Blocked(NeverRule.UNSURE, "\"${action.endIso.take(40)}\" is not a date")
                }
                if (action.subject.isBlank()) {
                    return GuardVerdict.Blocked(NeverRule.UNSURE, "the calendar entry has no subject")
                }
                action.copy(
                    subject = action.subject.trim().take(MAX_TITLE),
                    location = action.location?.trim()?.take(MAX_SUBJECT),
                    because = action.because.trim().take(MAX_TEXT),
                )
            }

            is PreparedAction.DraftReply -> {
                if (action.body.isBlank()) {
                    return GuardVerdict.Blocked(NeverRule.UNSURE, "the draft has an empty body")
                }
                action.copy(
                    to = action.to.trim().take(MAX_SUBJECT),
                    subject = action.subject.trim().take(MAX_SUBJECT),
                    body = action.body.trim().take(MAX_BODY),
                    language = action.language.trim().take(60),
                    needsInfo = action.needsInfo.map { it.trim().take(MAX_SUBJECT) }.filter { it.isNotEmpty() }.take(10),
                    notes = action.notes.trim().take(1_000),
                )
            }

            is PreparedAction.NoteFinding -> {
                if (action.headline.isBlank()) {
                    return GuardVerdict.Blocked(NeverRule.UNSURE, "the note has no headline")
                }
                action.copy(
                    headline = action.headline.trim().take(MAX_TITLE),
                    detail = action.detail.trim().take(MAX_TEXT),
                )
            }
        }

        // --- The content scans, over every piece of text the action would show. ---
        for (text in textsOf(trimmed)) {
            contentProblem(text)?.let { return it }
        }
        return GuardVerdict.Allowed(trimmed)
    }

    /** The guard over a batch: what survived, and every refusal with its rule. */
    fun checkAll(actions: List<PreparedAction>): GuardReport {
        val allowed = mutableListOf<PreparedAction>()
        val blocked = mutableListOf<GuardVerdict.Blocked>()
        for (a in actions) {
            when (val v = check(a)) {
                is GuardVerdict.Allowed -> allowed += v.action
                is GuardVerdict.Blocked -> blocked += v
            }
        }
        return GuardReport(allowed, blocked)
    }

    /** Every piece of text an action would put in front of a person. */
    private fun textsOf(action: PreparedAction): List<String> = when (action) {
        is PreparedAction.Remind -> listOf(action.text, action.because)
        is PreparedAction.CalendarEvent ->
            listOfNotNull(action.subject, action.location, action.because)

        is PreparedAction.DraftReply ->
            listOf(action.to, action.subject, action.body, action.notes) + action.needsInfo

        is PreparedAction.NoteFinding -> listOf(action.headline, action.detail)
    }

    /**
     * The first never-list problem in [text], or null.
     *
     * Credentials come first: it is the only rule stated as *"not 'ask first' — never"*, so it must
     * not be reachable past anything else.
     */
    internal fun contentProblem(text: String): GuardVerdict.Blocked? {
        if (text.isEmpty()) return null

        secretProblem(text)?.let { return GuardVerdict.Blocked(NeverRule.CREDENTIALS, it) }

        if (BLESSING.containsMatchIn(text)) {
            val m = BLESSING.find(text)!!.value
            return GuardVerdict.Blocked(NeverRule.BLESS, "it tells the person something is safe (\"$m\")")
        }
        if (PAYMENT_INTENT.containsMatchIn(text)) {
            val m = PAYMENT_INTENT.find(text)!!.value
            return GuardVerdict.Blocked(NeverRule.SPEND, "it would authorise a payment (\"$m\")")
        }
        if (OUR_TAGS.containsMatchIn(text)) {
            return GuardVerdict.Blocked(
                NeverRule.APPROVAL,
                "the model's answer contains one of our own prompt tags, so the item's text came back out",
            )
        }
        if (REVIEWER_BAIT.containsMatchIn(text)) {
            val m = REVIEWER_BAIT.find(text)!!.value
            return GuardVerdict.Blocked(NeverRule.APPROVAL, "it instructs the reviewer rather than informing them (\"$m\")")
        }
        return null
    }

    /**
     * A credential, one-time code, CVV, card number or national ID in [text], described without
     * quoting it.
     *
     * API keys and private keys come from the engine's own detectors ([SecretRules]); card numbers
     * are Luhn-checked with the privacy check's arithmetic ([PiiRules]) so the two agree; the short
     * codes need a cue nearby, because a bare six-digit run is as likely to be a quantity or a
     * postcode, and blocking every one of those would make the tier useless.
     */
    internal fun secretProblem(text: String): String? {
        SecretRules.findSecrets(text).firstOrNull()?.let {
            return "it contains a ${it.label}"
        }
        if (CREDENTIAL_CUE.containsMatchIn(text)) return "it contains a password"

        val folded = PiiRules.foldDigits(text)

        // A payment card number: 13-19 digits, written with or without the usual separators, that a
        // card network would issue and that passes Luhn -- the privacy check's own arithmetic, so the
        // two features never disagree about what a card looks like.
        for (m in SEPARATED_RUN.findAll(folded)) {
            val digits = m.value.filter { it in '0'..'9' }
            if (digits.length in 13..19 && PiiRules.cardOk(digits)) return "it contains a payment card number"
        }

        // A national ID: fourteen digits in a row (Egypt's shape, and several others). Contiguous
        // only, so a pair of ISO dates side by side is not mistaken for one.
        for (run in runsOf(folded)) {
            if (run.length == 14) return "it contains what could be a national ID number"
        }

        // A one-time code or a CVV. These need a cue nearby: a bare six-digit run is as likely to be
        // a quantity, an order number or a postcode, and refusing every one of those would make the
        // tier useless. Only a maximal run counts, so digits inside a longer number are not codes.
        for ((range, digits) in runsWithRange(folded)) {
            if (digits.length !in 3..8) continue
            if (touchesDateSeparator(folded, range)) continue
            val lo = maxOf(0, range.first - CUE_WINDOW)
            val hi = minOf(folded.length, range.last + 1 + CUE_WINDOW)
            val around = folded.substring(lo, hi)
            if (digits.length in 4..8 && CODE_CUE.containsMatchIn(around)) return "it contains a one-time code"
            if (digits.length in 3..4 && CVV_CUE.containsMatchIn(around)) return "it contains a card security code"
        }
        return null
    }

    /** How far either side of a digit run a cue counts as being "near" it. */
    private const val CUE_WINDOW = 60

    /**
     * A long number written with the spaces or dashes a card is usually grouped with.
     *
     * Deliberately no lookaround: Kotlin/Native's regex does not carry Java's full lookbehind, which
     * is why [PiiRules] wraps its own patterns in a guard. Boundaries are checked in code instead.
     */
    private val SEPARATED_RUN = Regex("""\d[\d \-]{11,30}\d""")

    private val DIGITS = Regex("""\d+""")

    /** Every maximal run of digits, as text. */
    private fun runsOf(text: String): List<String> = DIGITS.findAll(text).map { it.value }.toList()

    /** Every maximal run of digits, with where it sits. */
    private fun runsWithRange(text: String): List<Pair<IntRange, String>> =
        DIGITS.findAll(text).map { it.range to it.value }.toList()

    /**
     * True when a digit run sits inside a date, a time or a version — `2026-10-02`, `09:40`,
     * `1.2.3` — seen from inside one of its parts.
     *
     * The separator only counts when there is a digit on its far side. A full stop that ends a
     * sentence ("the code is 481920.") is not a version number, and reading it as one hid every
     * code that happened to end one.
     */
    private fun touchesDateSeparator(text: String, range: IntRange): Boolean {
        val before = range.first - 1
        if (before >= 1 && text[before] in DATE_SEPARATORS && text[before - 1] in '0'..'9') return true
        val after = range.last + 1
        if (after + 1 < text.length && text[after] in DATE_SEPARATORS && text[after + 1] in '0'..'9') return true
        return false
    }

    private const val DATE_SEPARATORS = "-/.:"
}

/** What the guard allowed and what it refused. */
data class GuardReport(
    val allowed: List<PreparedAction>,
    val blocked: List<GuardVerdict.Blocked>,
) {
    /** True when nothing was refused. */
    val clean: Boolean get() = blocked.isEmpty()

    /** One line per refusal, for the agent's log. */
    val refusals: List<String> get() = blocked.map { "${it.rule.code}: ${it.detail}" }
}
