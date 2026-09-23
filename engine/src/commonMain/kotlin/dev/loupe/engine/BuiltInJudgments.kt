package dev.loupe.engine

/**
 * A judgment authored to the three-part template (C1).
 *
 * The three parts exist because a bare question underspecifies the decision. Stating what breaks
 * the invariant, and what merely resembles it, is what separates "is this a receipt" from "does
 * this mention money".
 */
data class JudgmentDefinition(
    val judgment: Judgment.Choice,
    /** What must be true for the answer to be the positive label. */
    val invariant: String,
    /** What makes it false. */
    val breaks: String,
    /** What looks like it but is not — the near misses that decide the hard cases. */
    val lookalikes: String,
)

/**
 * The built-in judgment library (C1). Editable by the user; these are starting points, not rules.
 */
object BuiltInJudgments {

    val RECEIPT = define(
        id = "is-receipt",
        question = "Is this a receipt or proof of purchase?",
        invariant = "Records a completed transaction: a payee, an amount paid, and a date.",
        breaks = "Nothing was paid — a quote, an invoice awaiting payment, a price list, a menu.",
        lookalikes = "Order confirmations and shipping notices mention money and a merchant but " +
            "evidence dispatch, not payment. A bank statement is a record of many payments, not " +
            "a receipt for one.",
    )

    val NEEDS_REPLY = define(
        id = "needs-reply",
        question = "Is this waiting on a response from me?",
        invariant = "A person is expecting something back: a question, a decision, or a deadline " +
            "directed at the recipient.",
        breaks = "It is informational, automated, or already answered elsewhere in the thread.",
        lookalikes = "Newsletters and notifications often end in a question mark as a rhetorical " +
            "device. A thread where someone else already replied no longer waits on you.",
    )

    val UNSUBSCRIBE_CANDIDATE = define(
        id = "unsubscribe-candidate",
        question = "Is this a subscription the recipient has stopped engaging with?",
        invariant = "Recurring bulk mail from a sender whose messages go unopened and unanswered.",
        breaks = "It is transactional, from a person, or from a service still in active use.",
        lookalikes = "Statements, security alerts and receipts arrive on a schedule and look like " +
            "bulk mail, but unsubscribing from them loses records you need.",
    )

    val DUPLICATE = define(
        id = "is-duplicate",
        question = "Is this a redundant copy of something already kept?",
        invariant = "The same content exists elsewhere, and keeping both adds nothing.",
        breaks = "It differs in a way that matters — a better scan, a signed version, a later draft.",
        lookalikes = "A burst of similar photos is not duplicates; one of them is the good one. " +
            "Exact byte-identical copies are found mechanically and never reach this judgment.",
    )

    val STALE = define(
        id = "is-stale",
        question = "Has this stopped being useful to keep?",
        invariant = "Its purpose has passed and nothing references it any more.",
        breaks = "It has legal, tax, warranty or sentimental value that outlives its use.",
        lookalikes = "Old is not stale. A ten-year-old deed matters; a ten-day-old parking " +
            "confirmation does not.",
    )

    val EXPIRING = define(
        id = "is-expiring-document",
        question = "Is this a document with an expiry date that matters?",
        invariant = "An entitlement that lapses: passport, visa, licence, insurance, warranty, " +
            "lease, certification.",
        breaks = "It has no expiry, or expiry carries no consequence.",
        lookalikes = "Many documents carry dates that are not expiries — an issue date, a " +
            "statement period, a printed-on date. The date arithmetic is mechanical; deciding " +
            "the document type is not.",
        // Quiet failure here is indistinguishable from "your passport is fine".
        onFailure = FailurePosture.LOUD,
    )

    val JUNK = define(
        id = "is-junk",
        question = "Is this worthless to keep?",
        invariant = "No informational, legal or sentimental value to anyone, now or later.",
        breaks = "Anyone would want it back — it names a person, an obligation, or an amount.",
        lookalikes = "Screenshots and accidental photos are usually junk, but a screenshot of a " +
            "confirmation number is a record. When unsure, this judgment abstains rather than " +
            "deleting.",
    )

    /** Every built-in, in the order the library presents them. */
    val ALL: List<JudgmentDefinition> = listOf(
        RECEIPT, NEEDS_REPLY, UNSUBSCRIBE_CANDIDATE, DUPLICATE, STALE, EXPIRING, JUNK,
    )

    /** Looks a built-in up by its judgment id. */
    fun byId(id: String): JudgmentDefinition? = ALL.firstOrNull { it.judgment.id == id }

    private fun define(
        id: String,
        question: String,
        invariant: String,
        breaks: String,
        lookalikes: String,
        onFailure: FailurePosture = FailurePosture.NULL_ACTION,
    ): JudgmentDefinition {
        // Built-ins go through the same authoring path users do; nothing is exempt from the lint.
        val result = JudgmentAuthor.compile(id, question, onFailure = onFailure)
        check(result is AuthorResult.Compiled) {
            "built-in judgment '$id' does not pass its own lint: " +
                (result as AuthorResult.Rejected).findings
        }
        return JudgmentDefinition(result.judgment, invariant, breaks, lookalikes)
    }
}
