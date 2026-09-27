package dev.loupe.kit.mail

/**
 * The decision model's `is_phishing` probability as the phishing formula takes it (formula v1.3's
 * related input change, Station's `docs/model-calibration.md`): **calibrated**. The formula's numbers
 * do not change — `layaP >= 0.8` / `>= 0.5` for 20 / 10 points ([Phishing.LAYA_STRONG],
 * [Phishing.LAYA_WEAK]) — but they now mean a calibrated 0.8 / 0.5.
 *
 * This is the one seam between a model reading and [Phishing.assess]: a caller holding the model's
 * raw yes mass for an `is_phishing` question passes it, with the text the model read, through
 * [modelReading] and hands the result to `assess(layaP = …)`. [RAW] is the adapter used until the
 * phone's calibration file is in place; the calibration prior plugs in as another
 * [PhishingCalibration] (`{ raw, text -> ModelPrior.calibratedPhishingProbability(raw, text) }`) with
 * no change to the formula or its callers. Keyword baselines' 0 / 1 answers are rules, not model
 * readings, and do not go through here.
 */
fun interface PhishingCalibration {
    /** The calibrated probability for a raw model probability in [0, 1] on [text] (its language picks the cell). */
    fun calibrated(raw: Double, text: String): Double

    companion object {
        /** No calibration file on the phone yet: the raw probability, clamped to [0, 1]. */
        val RAW: PhishingCalibration = PhishingCalibration { raw, _ -> raw.coerceIn(0.0, 1.0) }

        /**
         * The probability [Phishing.assess] takes as `layaP`: [raw] through [calibration], clamped to
         * [0, 1]; null (no reading) when there is none or it is not a number.
         */
        fun modelReading(raw: Double?, text: String, calibration: PhishingCalibration = RAW): Double? {
            if (raw == null || raw.isNaN()) return null
            val p = calibration.calibrated(raw.coerceIn(0.0, 1.0), text)
            return if (p.isNaN()) null else p.coerceIn(0.0, 1.0)
        }
    }
}
