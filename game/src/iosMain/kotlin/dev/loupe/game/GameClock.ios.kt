package dev.loupe.game

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.CLOCK_UPTIME_RAW
import platform.posix.clock_gettime_nsec_np

@OptIn(ExperimentalForeignApi::class)
actual object GameClock {
    actual fun nanoTime(): Long = clock_gettime_nsec_np(CLOCK_UPTIME_RAW.toUInt()).toLong()
}
