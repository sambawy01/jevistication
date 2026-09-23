plugins {
    kotlin("jvm") version "2.1.0" apply false
    // engine, templates and loupe-kit are Kotlin Multiplatform (JVM + iOS); see docs/BUILD.md.
    kotlin("multiplatform") version "2.1.0" apply false
    // The desktop demo game only. The Compose compiler ships with Kotlin since 2.0, so its plugin
    // version is the Kotlin version; Compose Multiplatform 1.7.3 is the last line built against
    // Kotlin 2.1.0, so no module's Kotlin had to move. See docs/LICENSING.md for the licence check.
    kotlin("plugin.compose") version "2.1.0" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
}
