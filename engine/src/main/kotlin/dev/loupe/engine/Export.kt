package dev.loupe.engine

/**
 * Minimal JSON emission.
 *
 * Hand-rolled rather than pulled from a library: licence provenance of every dependency is a
 * documented concern in this project, and correct string escaping plus number formatting is a
 * small, testable amount of code to own outright.
 */
internal object Json {

    /** A quoted, escaped JSON string. */
    fun string(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    /** JSON `null`, or the escaped string. */
    fun stringOrNull(value: String?): String = value?.let { string(it) } ?: "null"

    /** A number, or `null`. Kotlin's toString is locale-independent, which is the point. */
    fun number(value: Double?): String = value?.toString() ?: "null"

    fun number(value: Long): String = value.toString()

    fun number(value: Int): String = value.toString()

    /** An object from already-encoded values. */
    fun obj(fields: List<Pair<String, String>>): String =
        fields.joinToString(",", "{", "}") { (key, encoded) -> "${string(key)}:$encoded" }

    /** An array of already-encoded values. */
    fun array(encoded: List<String>): String = encoded.joinToString(",", "[", "]")
}

/**
 * Export (F4): judgments, calibration and decision history as portable files the user owns.
 *
 * The ledger exports **losslessly** — the full distribution and the propensity, not just the
 * answer — because these files are what an overnight fine-tune reads and what any later
 * counterfactual is computed from. An export that kept only the chosen label would be a record of
 * what happened with the reason it happened thrown away.
 */
object Export {

    /**
     * The ledger as JSON Lines: one self-contained object per row.
     *
     * Line-oriented so a long history streams and appends without rewriting the file, and so a
     * truncated file still parses up to its last complete line.
     */
    fun ledgerToJsonl(rows: List<LedgerRow>): String =
        rows.joinToString("\n") { row ->
            Json.obj(
                listOfNotNull(
                    "judgmentId" to Json.string(row.judgmentId),
                    // Emitted only when known, so rows logged without an item keep their old shape.
                    row.itemId?.let { "itemId" to Json.string(it) },
                    "criteriaHash" to Json.string(row.criteriaHash),
                    "action" to Json.string(row.action),
                    "propensity" to Json.number(row.propensity.value),
                    "correction" to Json.stringOrNull(row.correction),
                    "failure" to Json.stringOrNull(row.failure),
                    // Always emitted since it was added; a line without it is a legacy row.
                    "resolvedBy" to Json.string(row.resolvedBy.code),
                    // Emitted only when known; a line without it is unknown (legacy or mechanical).
                    // `{}` is "known whole"; each present key is one cut, kept of total.
                    row.truncation?.let { "truncation" to truncation(it) },
                    "distribution" to Json.obj(
                        row.distribution.labels.map { label ->
                            label to Json.number(row.distribution.getValue(label).value)
                        },
                    ),
                ),
            )
        }

    private fun truncation(t: Truncation): String =
        Json.obj(
            listOfNotNull(
                t.textBudget?.let { "textBudget" to extent(it) },
                t.modelContext?.let { "modelContext" to extent(it) },
            ),
        )

    private fun extent(e: Extent): String =
        Json.obj(
            listOf(
                "kept" to Json.number(e.kept),
                "total" to Json.number(e.total),
                "unit" to Json.string(e.unit.code),
            ),
        )

    /** The judgment library, including the three-part template text that defines each one. */
    fun judgmentsToJson(definitions: List<JudgmentDefinition>): String =
        Json.array(
            definitions.map { definition ->
                Json.obj(
                    listOf(
                        "id" to Json.string(definition.judgment.id),
                        "question" to Json.string(definition.judgment.question),
                        "candidates" to Json.array(definition.judgment.candidates.map(Json::string)),
                        "criteriaHash" to Json.string(definition.judgment.criteriaHash),
                        "invariant" to Json.string(definition.invariant),
                        "breaks" to Json.string(definition.breaks),
                        "lookalikes" to Json.string(definition.lookalikes),
                    ),
                )
            },
        )

    /** Per-judgment calibration, in the same shape the app displays it. */
    fun calibrationToJson(views: List<JudgmentCalibrationView>): String =
        Json.array(
            views.map { view ->
                Json.obj(
                    listOf(
                        "judgmentId" to Json.string(view.judgmentId),
                        "decisions" to Json.number(view.decisions),
                        "corrections" to Json.number(view.corrections),
                        "mechanical" to Json.number(view.mechanical),
                        "agreement" to Json.number(view.agreement),
                        "ece" to Json.number(view.ece),
                        "reliability" to Json.array(
                            view.reliability.map { bin ->
                                Json.obj(
                                    listOf(
                                        "lower" to Json.number(bin.lower),
                                        "upper" to Json.number(bin.upper),
                                        "count" to Json.number(bin.count),
                                        "meanConfidence" to Json.number(bin.meanConfidence),
                                        "accuracy" to Json.number(bin.accuracy),
                                    ),
                                )
                            },
                        ),
                    ),
                )
            },
        )
}
