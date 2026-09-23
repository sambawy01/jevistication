package dev.loupe.game

import java.util.Locale

// JVM-only: String.format has no common equivalent, and only the desktop and JVM tests print it.
/** A plain-text table of [Match] episodes plus per-pilot totals. */
fun Match.report(episodes: List<Match.Episode>, settings: Match.Settings): String = buildString {
    appendLine(
        "settings: maxTicks=${settings.maxTicks} (${settings.maxTicks / Rules.TICK_HZ}s), decision every " +
            "${settings.decisionInterval} ticks, charged delay ${settings.delayTicks} ticks, " +
            "override=${settings.overrideEnabled}, threshold=${settings.threshold}",
    )
    appendLine(
        String.format(
            Locale.ROOT, "%-9s %6s %6s %7s %-7s %5s %5s %5s %5s %5s %8s %8s %7s",
            "pilot", "seed", "score", "rows", "end", "kills", "dec", "mech", "fail", "ovr", "p50ms", "p95ms", "topRaw",
        ),
    )
    for (e in episodes) {
        appendLine(
            String.format(
                Locale.ROOT, "%-9s %6d %6d %7.1f %-7s %5d %5d %5d %5d %5d %8s %8s %7s",
                e.pilot, e.seed, e.score, e.rows, e.death?.name ?: "alive", e.kills, e.decisions, e.mechanical,
                e.failures, e.overrides, fmt(e.latencyP50Ms), fmt(e.latencyP95Ms), fmt(e.meanTopRaw),
            ),
        )
    }
    for ((pilot, group) in episodes.groupBy { it.pilot }) {
        appendLine(
            String.format(
                Locale.ROOT, "total %-9s score %d, rows %.1f, deaths %d/%d, overrides %d, failures %d",
                pilot, group.sumOf { it.score }, group.sumOf { it.rows }, group.count { it.death != null },
                group.size, group.sumOf { it.overrides }, group.sumOf { it.failures },
            ),
        )
    }
}

private fun fmt(v: Double?): String = v?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"
