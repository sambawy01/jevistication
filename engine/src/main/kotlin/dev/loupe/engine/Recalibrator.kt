package dev.loupe.engine

/**
 * Maps a raw model [Distribution] to a calibrated one (A6), fitted per judgment × source ×
 * option-count. The fitting method (isotonic or Platt) lands in a later increment; the interface
 * and the type boundary come first. A recalibrator is the only thing that can mint a
 * [CalibratedDistribution].
 */
fun interface Recalibrator {
    fun calibrate(raw: Distribution): CalibratedDistribution

    companion object {
        /**
         * The no-op recalibrator: passes masses through unchanged. It is the honest baseline until
         * a fit exists, and A6's acceptance test is that a fitted recalibrator must beat it on ECE.
         */
        val Identity: Recalibrator = Recalibrator { raw -> CalibratedDistribution(raw) }
    }
}
