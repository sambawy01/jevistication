package dev.loupe.engine

/**
 * The decision model, behind one interface (A2).
 *
 * It returns the **raw** response as label-to-mass rather than a validated [Distribution], because
 * validation is the engine's job (A4): a backend must not be able to bypass the boundary by
 * handing over something already well-formed. Whatever the backend returns is treated as untrusted
 * until [Judgment.Choice.validate] has passed it.
 *
 * There is deliberately no hosted implementation. A network call breaks the offline guarantee the
 * product rests on, so the interface has no place to put one.
 */
fun interface Backend {
    /** Scores [judgment]'s candidate labels against [state]. */
    fun score(judgment: Judgment.Choice, state: TextState): Map<String, Double>
}
