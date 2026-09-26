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
// app (:android-app) need an Android SDK, found as the Android tools find it: ANDROID_HOME, then
// ANDROID_SDK_ROOT, then sdk.dir in local.properties. Without one they are left out, loudly, and
// `./gradlew check` still builds and tests the JVM and iOS targets (a Mac or Linux machine with no
// SDK). LOUPE_REQUIRE_ANDROID=1 (CI sets it) turns the skip into a failure, so CI never skips
// Android silently. Every module reads the answer from gradle.extra["loupe.android"].
val androidSdkDir: File? = run {
    val fromEnv = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").mapNotNull { System.getenv(it)?.takeIf(String::isNotBlank) }
    val localProps = file("local.properties")
    val fromLocal = if (localProps.isFile) {
        java.util.Properties().apply { localProps.inputStream().use { load(it) } }.getProperty("sdk.dir")?.takeIf(String::isNotBlank)
    } else {
        null
    }
    (fromEnv + listOfNotNull(fromLocal)).map(::File).firstOrNull { it.isDirectory }
}
val androidEnabled = androidSdkDir != null
gradle.extra["loupe.android"] = androidEnabled
if (androidEnabled) {
    include("android-app")
} else {
    val why = "Android SDK not found (ANDROID_HOME, ANDROID_SDK_ROOT, local.properties sdk.dir)"
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
