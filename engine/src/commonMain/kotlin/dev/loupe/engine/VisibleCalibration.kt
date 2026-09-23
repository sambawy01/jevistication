package dev.loupe.engine

/**
 * How well the engine knows you, for **one** judgment (D2).
 *
 * Every field is per judgment. "Is this a receipt" might sit at 94% on your data while "is this
 * urgent" sits at 61%; an average of the two describes neither, so this module offers no way to
 * compute one.
 */
data class JudgmentCalibrationView(
    val judgmentId: String,
    val decisions: Int,
    val corrections: Int,
    /** Share of corrected decisions the engine had already got right. Null with no corrections. */
    val agreement: Double?,
    /** Calibration error over corrected decisions. Null with no corrections. */
    val ece: Double?,
    val reliability: List<ReliabilityBin>,
    /** Bins claiming more confidence than they earned — where the engine overstates itself. */
    val overconfidentBins: List<ReliabilityBin>,
    /**
     * Of [decisions], how many a mechanical check answered. They are counted as decisions but
     * never as model predictions: [agreement], [ece] and [corrections] cover model rows only.
     */
    val mechanical: Int = 0,
) {
    fun summary(): String =
        if (agreement == null) {
            "$judgmentId: $decisions decisions, none corrected yet — nothing measured."
        } else {
            "$judgmentId: agrees with you ${formatFixed(agreement * 100, 0)}% over " +
                "$corrections correction(s), ECE ${ece?.let { formatFixed(it, 3) } ?: "null"}"
        }
}

/**
 * The visible-calibration surface (D2). It reports per judgment and **never a single system
 * accuracy number**, because one number hides every number it averaged.
 */
object VisibleCalibration {

    /** A view for each judgment present in [rows], ordered by how many decisions it has made. */
    fun perJudgment(
        rows: List<LedgerRow>,
        bins: Int = Calibration.DEFAULT_BINS,
    ): List<JudgmentCalibrationView> =
        rows.groupBy { it.judgmentId }
            .map { (judgmentId, judgmentRows) -> view(judgmentId, judgmentRows, bins) }
            .sortedByDescending { it.decisions }

    /** The view for one judgment, or null when [rows] contains none of its decisions. */
    fun forJudgment(
        rows: List<LedgerRow>,
        judgmentId: String,
        bins: Int = Calibration.DEFAULT_BINS,
    ): JudgmentCalibrationView? {
        val judgmentRows = rows.filter { it.judgmentId == judgmentId }
        return if (judgmentRows.isEmpty()) null else view(judgmentId, judgmentRows, bins)
    }

    private fun view(
        judgmentId: String,
        judgmentRows: List<LedgerRow>,
        bins: Int,
    ): JudgmentCalibrationView {
        // Only the model's own answers are calibration evidence. A mechanical row is certain by
        // construction and an unusable row's distribution is a placeholder; scoring either would
        // flatter (or smear) the model's numbers with answers it never gave.
        val corrected = judgmentRows.filter { it.isModelPrediction }.mapNotNull { row ->
            row.correction?.let { row.distribution to it }
        }
        val reliability =
            if (corrected.isEmpty()) emptyList() else Calibration.reliability(corrected, bins)

        return JudgmentCalibrationView(
            judgmentId = judgmentId,
            decisions = judgmentRows.size,
            corrections = corrected.size,
            agreement = if (corrected.isEmpty()) {
                null
            } else {
                corrected.count { (dist, truth) -> dist.argmax == truth }.toDouble() / corrected.size
            },
            ece = if (corrected.isEmpty()) null else Calibration.ece(corrected, bins),
            reliability = reliability,
            overconfidentBins = reliability.filter { it.count > 0 && it.meanConfidence > it.accuracy },
            mechanical = judgmentRows.count { it.isMechanical },
        )
    }
}
