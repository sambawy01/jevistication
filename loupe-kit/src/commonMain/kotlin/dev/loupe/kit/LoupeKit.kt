package dev.loupe.kit

import dev.loupe.engine.PublicSuffix

/**
 * Entry point of the LoupeKit framework. Everything useful is re-exported from `:engine` and
 * `:templates`; this object only reports what was bundled, so a host app can log it.
 */
object LoupeKit {
    /** The `// VERSION:` line of the bundled Public Suffix List snapshot. */
    val publicSuffixListVersion: String get() = PublicSuffix.VERSION
}
