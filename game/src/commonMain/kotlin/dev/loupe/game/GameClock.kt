package dev.loupe.game

/**
 * The monotonic wall clock the live game measures against: pilot latency and decisions per second.
 * `System.nanoTime` on the JVM, the kernel's monotonic clock on iOS. The simulation itself never
 * reads it (see [World]); a host that shows [DecisionStats] passes the same clock back in.
 */
expect object GameClock {
    fun nanoTime(): Long
}
