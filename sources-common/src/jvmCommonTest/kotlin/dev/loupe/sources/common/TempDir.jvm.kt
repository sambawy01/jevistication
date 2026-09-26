package dev.loupe.sources.common

actual fun newTempDir(): String = java.nio.file.Files.createTempDirectory("loupe-sources-").toString()
