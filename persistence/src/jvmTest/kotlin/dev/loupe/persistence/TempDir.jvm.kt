package dev.loupe.persistence

import java.nio.file.Files

actual fun newTempDir(): String = Files.createTempDirectory("loupe-persistence").toString()
