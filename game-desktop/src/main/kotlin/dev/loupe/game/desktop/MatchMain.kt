package dev.loupe.game.desktop

import dev.loupe.game.BaselinePilot
import dev.loupe.game.Match
import dev.loupe.game.ModelPilot
import dev.loupe.game.Pilot
import dev.loupe.game.Rules
import kotlin.system.exitProcess

/**
 * Headless model-vs-baseline: `./gradlew :game-desktop:match -Pseeds=1,2,3 -Pseconds=60`.
 *
 * Flies both pilots on the same seeds with the same charged latency, once with the safety override
 * on (the product configuration) and once with it off (the pilots on their own), and prints the
 * table. Without the model it says so and flies the baseline alone.
 */
fun main(args: Array<String>) {
    val seeds = (args.getOrNull(0) ?: "1,2,3,4,5").split(',').map { it.trim().toLong() }
    val seconds = (args.getOrNull(1) ?: "60").toInt()
    val loaded = ModelLoader.load()
    val pilots = mutableListOf<Pilot>()
    if (loaded is ModelStatus.Ready) pilots += ModelPilot(loaded.model.backend) else println((loaded as ModelStatus.Unavailable).message)
    pilots += BaselinePilot()
    try {
        for (override in listOf(true, false)) {
            val settings = Match.Settings(maxTicks = seconds * Rules.TICK_HZ, overrideEnabled = override)
            println(Match.report(Match.run(seeds, pilots, settings), settings))
        }
    } finally {
        (loaded as? ModelStatus.Ready)?.model?.close()
    }
    exitProcess(0)
}
