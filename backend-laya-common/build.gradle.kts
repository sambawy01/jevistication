import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
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

// Notices for the iOS build of Laya (ONNX Runtime iOS, the tokenizers crates); see that file.
apply(from = rootProject.file("gradle/ios-third-party-notices.gradle.kts"))
