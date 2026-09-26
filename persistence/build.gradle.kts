import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

// Loupe for Android (docs/ANDROID-PLAN.md): an Android library target beside JVM and iOS, only where
// settings.gradle.kts found an Android SDK (it says so when it did not).
val androidHost = gradle.extra["loupe.android"] as Boolean
if (androidHost) apply(plugin = "com.android.library")

repositories {
    mavenCentral()
    google()
}

// The app's own files (A5 ledger, corrections, user judgments) and the F4 export, shared by the
// desktop app and the iPhone (epic #7 child 1). The formats are the desktop's, byte for byte; file
// I/O is expect/actual (java.nio on the JVM, POSIX + Foundation on iOS). No third-party code.
val appleHost = System.getProperty("os.name").startsWith("Mac")

kotlin {
    // PlatformFiles and StoreLock are expect/actual declarations (Beta in Kotlin 2.1).
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    if (androidHost) {
        androidTarget {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }
    if (appleHost) {
        iosArm64()
        iosSimulatorArm64()
    }

    // jvmCommon: the JVM actuals, shared as is by the desktop JVM and Android (docs/ANDROID-PLAN.md,
    // "no logic forks"). Written against APIs Android has at minSdk 29.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmCommon") {
                withJvm()
                withAndroidTarget()
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":engine"))
            api(project(":templates"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation("org.junit.jupiter:junit-jupiter:5.11.4")
            // Test-only: proves the common writer matches the Gson the desktop wrote with.
            implementation("com.google.code.gson:gson:2.13.1")
            runtimeOnly("org.junit.platform:junit-platform-launcher")
        }
    }
}

if (androidHost) {
    extensions.configure<com.android.build.api.dsl.LibraryExtension> {
        namespace = "dev.loupe.persistence"
        compileSdk = 36
        defaultConfig {
            minSdk = 29
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
