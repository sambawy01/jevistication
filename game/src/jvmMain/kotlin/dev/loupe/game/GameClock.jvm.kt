package dev.loupe.game

actual object GameClock {
    actual fun nanoTime(): Long = System.nanoTime()
}
