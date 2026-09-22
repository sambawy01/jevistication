package dev.loupe.engine

/** What moving the threshold would have done to decisions already made. */
data class ThresholdPreview(
    val current: Probability,
    val candidate: Probability,
    /** Items the candidate would act on that the current setting queues. */
    val additionalActions: Int,
    /** Items the current setting acts on that the candidate would queue. */
    val fewerActions: Int,
    /**
     * Among the newly-acted items the user has actually corrected, how many the engine would have
     * got wrong. Null when no correction covers any of them — in which case the preview says so
     * rather than inventing a number.
     */
    val mistakesIntroduced: Int?,
    /** Corrected rows the estimate rests on. */
    val correctionsConsulted: Int,
    val rowsConsidered: Int,
) {
    /** The sentence the slider shows, in the spec's own shape. */
    fun summary(): String = buildString {
        when {
            additionalActions > 0 -> append("$additionalActions more acted on")
            fewerActions > 0 -> append("$fewerActions fewer acted on")
            else -> append("no change to what is acted on")
        }
        if (mistakesIntroduced == null) {
            append("; no correction of yours covers those items, so the cost is unknown")
        } else {
            append(
                "; based on your $correctionsConsulted correction(s) you would have rescued " +
                    "$mistakesIntroduced",
            )
        }
    }
}

/**
 * The threshold slider (D3): move it and see what changes *before* committing.
 *
 * The preview is counted from decisions already logged, not predicted, and the cost half is
 * grounded only in items the user actually corrected. Where no correction covers the affected
 * items the preview says the cost is unknown rather than producing a confident figure from
 * nothing — the same discipline the engine applies to its own answers.
 */
object ThresholdSlider {

    /** Compares what [candidate] would have done against what [current] did, over [rows]. */
    fun preview(
        rows: List<LedgerRow>,
        current: Probability,
        candidate: Probability,
    ): ThresholdPreview {
        var additional = 0
        var fewer = 0
        var mistakes = 0
        var consulted = 0

        for (row in rows) {
            val top = row.distribution.getValue(row.distribution.argmax).value
            val actsNow = top >= current.value
            val actsThen = top >= candidate.value

            if (actsThen && !actsNow) {
                additional++
                row.correction?.let {
                    consulted++
                    if (row.distribution.argmax != it) mistakes++
                }
            }
            if (actsNow && !actsThen) fewer++
        }

        return ThresholdPreview(
            current = current,
            candidate = candidate,
            additionalActions = additional,
            fewerActions = fewer,
            mistakesIntroduced = if (consulted == 0) null else mistakes,
            correctionsConsulted = consulted,
            rowsConsidered = rows.size,
        )
    }
}
