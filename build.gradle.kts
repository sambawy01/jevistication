plugins {
    kotlin("jvm") version "2.1.0" apply false
    // engine, templates and loupe-kit are Kotlin Multiplatform (JVM + iOS); see docs/BUILD.md.
    kotlin("multiplatform") version "2.1.0" apply false
    // The desktop demo game only. The Compose compiler ships with Kotlin since 2.0, so its plugin
    // version is the Kotlin version; Compose Multiplatform 1.7.3 is the last line built against
    // Kotlin 2.1.0, so no module's Kotlin had to move. See docs/LICENSING.md for the licence check.
    kotlin("plugin.compose") version "2.1.0" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
    // Loupe for Android (docs/ANDROID-PLAN.md). AGP 8.9.1 is the first release that compiles against
    // API 36, which Google Play requires of new apps and updates from 2026-08-31; 8.9.3 is that
    // line's last patch. It needs Gradle >= 8.11.1 (the wrapper is 8.14.3). Kotlin 2.1.0 is tested by
    // JetBrains up to AGP 8.7.2 only, so gradle.properties silences its "newer AGP" notice; the full
    // gate (check, the APK, the XCFramework) is the evidence that the pair works.
    id("com.android.library") version "8.9.3" apply false
    id("com.android.application") version "8.9.3" apply false
    kotlin("android") version "2.1.0" apply false
}
