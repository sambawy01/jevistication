package dev.loupe.backend.onnx

import dev.loupe.engine.Judgment

/**
 * Runtime rules Loupe Station applies on top of the Laya package (issue #8), mirrored here so the
 * phone and Station send the model the same thing. They change what is *sent*, never the written
 * question, so criteria hashes stay identical on both apps.
 */
object LayaRuntimeRules {
    /**
     * Station's `REVERSE_SCORE_ON` (upstream laya #131: the multilingual checkpoint rarely picks the
     * first-listed level). For these checkpoints a score's levels are sent **highest first** and the
     * answer is mapped back to the written order (written index i = k - 1 - sent index). Station
     * measured +32 clear-cut answers on its 1,621-label set with no language losing a case.
     */
    val REVERSE_SCORE_ON: Set<String> = setOf("english", "multilingual")

    /** The checkpoint this build runs: the phone and the JVM backend ship only Laya multilingual. */
    const val CHECKPOINT: String = "multilingual"

    /** Whether [judgment]'s options are sent in reverse on [checkpoint]. */
    fun reversesScore(judgment: Judgment.Choice, checkpoint: String = CHECKPOINT): Boolean =
        judgment.ordinal && checkpoint in REVERSE_SCORE_ON
}
