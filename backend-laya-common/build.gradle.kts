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

// Laya's prompt and sequence logic (LayaPrompt, LayaTokenizer, the model-input types and the
// logits-to-masses step), in common code so the JVM backend (:backend-onnx) and the iOS backend
// (:backend-onnx-ios) build byte-identical token sequences from one implementation. Pure Kotlin:
// no runtime dependency beyond :engine. Its behaviour is pinned by :backend-onnx's JVM tests.
val appleHost = System.getProperty("os.name").startsWith("Mac")

kotlin {
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

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":engine"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

if (androidHost) {
    extensions.configure<com.android.build.api.dsl.LibraryExtension> {
        namespace = "dev.loupe.backend.laya"
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

// Notices for the iOS build of Laya (ONNX Runtime iOS, the tokenizers crates); see that file.
apply(from = rootProject.file("gradle/ios-third-party-notices.gradle.kts"))
