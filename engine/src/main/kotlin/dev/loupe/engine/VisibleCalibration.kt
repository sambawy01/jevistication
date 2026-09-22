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
) {
    fun summary(): String =
        if (agreement == null) {
            "$judgmentId: $decisions decisions, none corrected yet — nothing measured."
        } else {
            "$judgmentId: agrees with you %.0f%% over $corrections correction(s), ECE %.3f"
                .format(agreement * 100, ece)
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
        val corrected = judgmentRows.mapNotNull { row ->
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
        )
    }
}
