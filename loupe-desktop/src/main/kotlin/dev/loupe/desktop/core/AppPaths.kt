package dev.loupe.desktop.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where Loupe keeps what it learns: judgments, the ledger and corrections, and its copy of the
 * sample dataset. Never inside a repository and never inside a source the user added.
 *
 * - macOS: `~/Library/Application Support/Loupe`
 * - Linux: `$XDG_DATA_HOME/loupe`, else `~/.local/share/loupe`
 * - Windows: `%APPDATA%\Loupe`
 *
 * `-Dloupe.home=<dir>` overrides it, which is how tests and the snapshot task keep a throwaway home.
 */
object AppPaths {
    fun home(): Path {
        System.getProperty("loupe.home")?.let { return Paths.get(it).toAbsolutePath() }
        val userHome = Paths.get(System.getProperty("user.home"))
        val os = System.getProperty("os.name").lowercase()
        return when {
            "mac" in os -> userHome.resolve("Library/Application Support/Loupe")
            "win" in os -> Paths.get(System.getenv("APPDATA") ?: userHome.toString()).resolve("Loupe")
            else -> (System.getenv("XDG_DATA_HOME")?.let(Paths::get) ?: userHome.resolve(".local/share")).resolve("loupe")
        }
    }

    /** Creates [home] if needed and returns it. */
    fun ensure(home: Path): Path = Files.createDirectories(home)
}
