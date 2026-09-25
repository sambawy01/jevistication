package dev.loupe.game

/**
 * Headless model-versus-baseline runs: the same seeds, the same decision rate, the same charged
 * latency, the same safety net — only the pilot differs.
 *
 * Whichever wins is the result. The spec's baseline check exists precisely so that a model losing
 * to a few lines of rules is reported rather than hidden (proving milestone 6), and untuned Laya
 * has no reason to be good at flying.
 */
object Match {

    data class Settings(
        /** Simulation ticks per episode before it is stopped as a survivor (60 per second). */
        val maxTicks: Int = 60 * Rules.TICK_HZ,
        val decisionInterval: Int = GameSession.DEFAULT_DECISION_INTERVAL,
        /**
         * Simulated latency charged to every pilot, in ticks. 4 is 67 ms: at or above the ~62 ms P50
         * measured for this game's Laya INT8 decisions on an Apple M4 CPU, so the model is not
         * flattered. The baseline, which takes microseconds, is charged the same, so only the pilot
         * differs.
         */
        val delayTicks: Int = 4,
        val overrideEnabled: Boolean = true,
        /** Hand-off threshold. Headless there is no human, so a hand-off flies the no-op. */
        val threshold: Double = 0.0,
        val difficulty: Difficulty = Difficulty.CLASSIC,
    )

    data class Episode(
        val pilot: String,
        val seed: Long,
        val score: Int,
        val rows: Double,
        /** Null when the episode reached [Settings.maxTicks] alive. */
        val death: DeathCause?,
        val ticks: Long,
        val decisions: Int,
        val modelDecisions: Int,
        val mechanical: Int,
        val failures: Int,
        val handOffs: Int,
        val overrides: Int,
        val kills: Int,
        val latencyP50Ms: Double?,
        val latencyP95Ms: Double?,
        /** Mean of the top raw probability over model decisions; raw, uncalibrated. */
        val meanTopRaw: Double?,
        /** The level reached (1 on [Difficulty.CLASSIC]). */
        val level: Int = 1,
        /** Decision slots the pilot was too busy to take (see [DecisionStats.dropped]). */
        val dropped: Int = 0,
    )

    fun episode(seed: Long, pilot: Pilot, settings: Settings = Settings()): Episode {
        var simNanos = 0L
        val tops = mutableListOf<Double>()
        val decider = LockstepDecider(pilot, settings.delayTicks)
        GameSession(
            seed = seed,
            control = Control.Piloted(decider),
            decisionInterval = settings.decisionInterval,
            threshold = settings.threshold,
            overrideEnabled = settings.overrideEnabled,
            clock = { simNanos },
            difficulty = settings.difficulty,
        ).use { session ->
            var seen: PilotDecision? = null
            while (!session.world.over && session.world.tick < settings.maxTicks) {
                session.tick()
                simNanos += 1_000_000_000L / Rules.TICK_HZ
                val current = session.current
                if (current !== seen && current != null) {
                    current.topProbability?.let { tops += it }
                    seen = current
                }
            }
            val w = session.world
            val s = session.stats
            return Episode(
                pilot = pilot.name,
                seed = seed,
                score = w.score,
                rows = w.cameraY,
                death = w.death,
                ticks = w.tick,
                decisions = s.total,
                modelDecisions = s.count(DecisionSource.MODEL),
                mechanical = s.count(DecisionSource.MECHANICAL),
                failures = s.count(DecisionSource.FAILURE),
                handOffs = s.handOffs,
                overrides = s.overrides,
                kills = w.tally.kills,
                latencyP50Ms = s.latencyMillis(0.5),
                latencyP95Ms = s.latencyMillis(0.95),
                meanTopRaw = if (tops.isEmpty()) null else tops.average(),
                level = w.level,
                dropped = s.dropped,
            )
        }
    }

    fun run(seeds: List<Long>, pilots: List<Pilot>, settings: Settings = Settings()): List<Episode> =
        pilots.flatMap { pilot -> seeds.map { episode(it, pilot, settings) } }
}
