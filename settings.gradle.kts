// The Android Gradle Plugin comes from Google's Maven repository, which the Gradle Plugin Portal
// does not mirror; Kotlin and Compose resolve from the portal as before.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "loupe"

include("engine")
include("backend-laya-common")
include("backend-onnx")
include("game")
include("game-desktop")
include("templates")
include("persistence")
include("sources-common")
include("sources-desktop")
include("loupe-desktop")
include("loupe-kit")

// Loupe for Android (docs/ANDROID-PLAN.md). The Android targets of the shared modules and the Compose
// app (:android-app) need an Android SDK, found as the Android Gradle Plugin finds it: sdk.dir in
// local.properties, else ANDROID_HOME, else ANDROID_SDK_ROOT; the first one set decides, as in AGP
// (which then fails if that directory is missing, so a missing one counts as no SDK here). Without
// an SDK they are left out, loudly, and `./gradlew check` still builds and tests the JVM and iOS
// targets (a Mac or Linux machine with no SDK). LOUPE_REQUIRE_ANDROID=1 (CI sets it) turns the
// skip into a failure, so CI never skips Android silently. Every module reads the answer from
// gradle.extra["loupe.android"].
val androidSdkSetting: Pair<String, String>? = run {
    val localProps = file("local.properties")
    val fromLocal = if (localProps.isFile) {
        java.util.Properties().apply { localProps.inputStream().use { load(it) } }.getProperty("sdk.dir")?.takeIf(String::isNotBlank)
    } else {
        null
    }
    fromLocal?.let { "local.properties sdk.dir" to it }
        ?: listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").firstNotNullOfOrNull { name ->
            System.getenv(name)?.takeIf(String::isNotBlank)?.let { name to it }
        }
}
val androidEnabled = androidSdkSetting != null && File(androidSdkSetting.second).isDirectory
gradle.extra["loupe.android"] = androidEnabled
if (androidEnabled) {
    include("android-app")
} else {
    val why = if (androidSdkSetting == null) {
        "Android SDK not found (local.properties sdk.dir, ANDROID_HOME, ANDROID_SDK_ROOT)"
    } else {
        "Android SDK not found (${androidSdkSetting.first} is ${androidSdkSetting.second}, not a directory)"
    }
    if (System.getenv("LOUPE_REQUIRE_ANDROID") == "1") {
        throw GradleException("$why and LOUPE_REQUIRE_ANDROID=1: refusing to skip the Android targets")
    }
    // println, not logger.warn: it shows even under -q.
    println("WARNING: $why: Android targets skipped; :android-app and every androidTarget() left out")
}

// Laya on iOS: ONNX Runtime and the Hugging Face tokenizer through cinterop. Needs macOS and the
// native pieces ios-native/build.sh produces (gitignored); without them the module is left out and
// everything else builds as before. See docs/BUILD.md, progress log "Epic #6 child 3".
if (System.getProperty("os.name").startsWith("Mac")) {
    val native = file("ios-native/build")
    if (native.resolve("onnxruntime-lib").isDirectory && native.resolve("LoupeTokenizers.xcframework").isDirectory) {
        include("backend-onnx-ios")
    } else {
        println("backend-onnx-ios skipped: run ios-native/build.sh to build Laya for iOS")
    }
}
